package io.github.hozdogus.pocketouya;

import java.io.IOException;
import java.nio.*;
import java.util.*;
import static io.github.hozdogus.pocketouya.ArmRuntimePicRepair.require;

/** Exact-profile ARM literal PIC conversion. No source/runtime bytes are bundled. */
final class ArmLiteralPicRepair {
    private ArmLiteralPicRepair(){}
    static byte[] repair(byte[] source,int relativeCount,int absoluteCount,int siteCount)throws IOException{
        byte[] data=source.clone();ArmRuntimePicRepair.Elf elf=new ArmRuntimePicRepair.Elf(data);ByteBuffer b=elf.b;
        Map<Integer,Integer> tags=elf.dynamic();int relSize=elf.value(tags,18),relOffset=elf.offset(elf.value(tags,17),relSize);
        require(relSize%8==0&&elf.value(tags,19)==8,"Unsupported ARM relocation table");
        Map<Integer,Integer> bad=new TreeMap<>();int relatives=0,absolutes=0;
        for(int at=relOffset;at<relOffset+relSize;at+=8){int address=b.getInt(at),info=b.getInt(at+4);if((info&255)==0)continue;boolean writable=false;long v=Integer.toUnsignedLong(address);
            for(long[] p:elf.loads)if(v>=p[0]&&v+4<=p[0]+p[2]&&(p[3]&2)!=0)writable=true;
            if(!writable){require(bad.put(address,info)==null,"Duplicate ARM code relocation");if(info==23)relatives++;else {require((info&255)==2&&(info>>>8)>0,"Unsupported symbolic code relocation");absolutes++;}}
        }
        require(relatives==relativeCount&&absolutes==absoluteCount,"Unexpected ARM literal relocation count");
        for(int at=relOffset;at<relOffset+relSize;at+=8)if((b.getInt(at+4)&255)==23)require(!bad.containsKey(b.getInt(elf.offset(b.getInt(at),4))),"Address-taken ARM literal");
        List<int[]> sites=new ArrayList<>();Set<Integer> covered=new HashSet<>();
        for(long[] p:elf.loads)if((p[3]&1)!=0){
            for(long va=p[0];va+4<=p[0]+p[4];va+=4){int v=(int)va,ins=b.getInt(elf.offset(v,4)),adr=ins&0x0fff0000;
                if(adr==0x028f0000||adr==0x024f0000){int imm=Integer.rotateRight(ins&255,((ins>>>8)&15)*2);require(!bad.containsKey(v+8+(adr==0x028f0000?imm:-imm)),"Unsupported address reference to code literal");}
                if((ins&0x0f7f0000)!=0x051f0000)continue;
                int literal=v+8+((ins&0x800000)!=0?(ins&4095):-(ins&4095));if(!bad.containsKey(literal))continue;
                int rd=(ins>>>12)&15;require((ins>>>28)==14&&rd!=13&&rd!=15,"Unsupported ARM literal load");sites.add(new int[]{v,rd,literal});covered.add(literal);
            }
            for(long va=p[0];va+2<=p[0]+p[4];va+=2){int half=Short.toUnsignedInt(b.getShort(elf.offset((int)va,2)));if((half&0xf800)==0x4800||(half&0xf800)==0xa000)require(!bad.containsKey((((int)va+4)&~3)+((half&255)<<2)),"Unsupported Thumb code-literal reference");}
        }
        require(sites.size()==siteCount&&covered.equals(bad.keySet()),"ARM literal sites do not match the inspected profile");
        int ph=b.getInt(28),phStride=Short.toUnsignedInt(b.getShort(42)),phCount=Short.toUnsignedInt(b.getShort(44));
        require(phStride==32&&phCount<120,"Unsupported ARM program headers");List<int[]> headers=new ArrayList<>();long maximum=0;
        for(int i=0;i<phCount;i++){int[] p=new int[8];for(int j=0;j<8;j++)p[j]=b.getInt(ph+i*32+j*4);require(p[0]!=6,"Unexpected existing PT_PHDR");headers.add(p);if(p[0]==1)maximum=Math.max(maximum,Integer.toUnsignedLong(p[2])+Integer.toUnsignedLong(p[5]));}
        int sh=b.getInt(32),shStride=Short.toUnsignedInt(b.getShort(46)),shCount=Short.toUnsignedInt(b.getShort(48));
        require(shStride>=40&&ArmRuntimePicRepair.Elf.range(sh,(long)shStride*shCount,data.length),"Invalid ARM section table");int ex=-1;
        for(int i=0;i<shCount;i++){int at=sh+i*shStride;if(b.getInt(at+4)==0x70000001){require(ex<0,"Multiple ARM unwind tables");ex=at;}}
        boolean hasUnwind=ex>=0;int oldExOffset=hasUnwind?b.getInt(ex+16):0,oldExAddress=hasUnwind?b.getInt(ex+12):0,oldExSize=hasUnwind?b.getInt(ex+20):0;
        require((!hasUnwind||oldExSize>0)&&oldExSize%8==0&&ArmRuntimePicRepair.Elf.range(oldExOffset,oldExSize,data.length),"Invalid ARM unwind table");
        // Record original function starts and decoded descriptors. Extab pointers
        // are PREL31 relative to the descriptor word, so moving the table must rebase them.
        List<int[]> unwind=new ArrayList<>();int previous=-1;
        for(int at=0;at<oldExSize;at+=8){int fn=oldExAddress+at+prel(b.getInt(oldExOffset+at));int word=b.getInt(oldExOffset+at+4);
            require(fn>=0&&fn>previous,"Unsorted ARM unwind functions");previous=fn;
            int extab=(word!=1&&(word&0x80000000)==0)?oldExAddress+at+4+prel(word):-1;
            if(extab>=0)elf.offset(extab,4);unwind.add(new int[]{fn,word,extab});
        }
        int tableSize=(phCount+2+(absoluteCount>0?1:0))*32;
        long fileStart=((long)data.length+4095)&~4095L,memoryStart=(maximum+4095)&~4095L;
        int stubsSize=0;for(int[] site:sites)stubsSize+=(bad.get(site[2])&255)==2?20:16;
        int newExSize=hasUnwind?oldExSize+(sites.size()+1)*8:0,rxSize=tableSize+stubsSize+newExSize;
        require(fileStart+rxSize<0x7ffff000L&&memoryStart+rxSize<0x7ffff000L,"ARM extension exceeds supported bounds");
        int file=(int)fileStart,memory=(int)memoryStart,newExFile=file+tableSize+stubsSize,newExAddress=memory+tableSize+stubsSize;
        int rwFile=(file+rxSize+4095)&~4095,rwAddress=(memory+rxSize+4095)&~4095;
        Map<Integer,Integer> slots=new HashMap<>();int slot=0;
        for(int literal:bad.keySet())if((bad.get(literal)&255)==2)slots.put(literal,rwAddress+4*slot++);
        byte[] extended=Arrays.copyOf(data,absoluteCount>0?rwFile+4*absoluteCount:file+rxSize);ByteBuffer out=ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN);
        int code=memory+tableSize,codeFile=file+tableSize;
        for(int[] site:sites){int address=site[0],rd=site[1],literal=site[2];boolean symbolic=slots.containsKey(literal);int value=symbolic?slots.get(literal):b.getInt(elf.offset(literal,4));
            out.putInt(elf.offset(address,4),branch(address,code));
            out.putInt(codeFile,0xe59f0000|(rd<<12)|(symbolic?8:4));out.putInt(codeFile+4,0xe08f0000|(rd<<12)|rd);
            if(symbolic){out.putInt(codeFile+8,0xe5900000|(rd<<16)|(rd<<12));out.putInt(codeFile+12,branch(code+12,address+4));out.putInt(codeFile+16,value-(code+12));}
            else {out.putInt(codeFile+8,branch(code+8,address+4));out.putInt(codeFile+12,value-(code+12));}
            if(hasUnwind){int[] recipe=null;for(int[] entry:unwind)if((entry[0]&~1)<=address)recipe=entry;else break;
                require(recipe!=null,"Literal site precedes unwind coverage");unwind.add(new int[]{code,recipe[1],recipe[2]});} // Appended code sorts after all old functions.
            code+=symbolic?20:16;codeFile+=symbolic?20:16;
        }
        if(hasUnwind)unwind.add(new int[]{code,1,-1});require(codeFile==newExFile,"Incorrect ARM stub extent");
        unwind.sort(Comparator.comparingInt(p->p[0]));
        for(int i=0;i<unwind.size();i++){int[] entry=unwind.get(i);int address=newExAddress+i*8;out.putInt(newExFile+i*8,relative31(entry[0],address));out.putInt(newExFile+i*8+4,entry[2]>=0?relative31(entry[2],address+4):entry[1]);}
        boolean unwindHeader=false;
        for(int[] p:headers)if(p[0]==0x70000001){require(!unwindHeader&&p[1]==oldExOffset&&p[2]==oldExAddress&&p[4]==oldExSize&&p[5]==oldExSize,"Unsupported ARM unwind header");p[1]=newExFile;p[2]=p[3]=newExAddress;p[4]=p[5]=newExSize;unwindHeader=true;}
        require(unwindHeader==hasUnwind,"Inconsistent ARM unwind program header/section");
        if(hasUnwind){out.putInt(ex+12,newExAddress);out.putInt(ex+16,newExFile);out.putInt(ex+20,newExSize);}
        List<int[]> outputHeaders=new ArrayList<>();outputHeaders.add(new int[]{6,file,memory,memory,tableSize,tableSize,4,4});outputHeaders.addAll(headers);outputHeaders.add(new int[]{1,file,memory,memory,rxSize,rxSize,5,4096});
        if(absoluteCount>0)outputHeaders.add(new int[]{1,rwFile,rwAddress,rwAddress,4*absoluteCount,4*absoluteCount,6,4096});
        for(int i=0;i<outputHeaders.size();i++)for(int j=0;j<8;j++)out.putInt(file+i*32+j*4,outputHeaders.get(i)[j]);out.putInt(28,file);out.putShort(44,(short)outputHeaders.size());
        int write=relOffset;
        for(int read=relOffset;read<relOffset+relSize;read+=8){int address=b.getInt(read),info=b.getInt(read+4);if(bad.containsKey(address)&&info==23)continue;
            if(slots.containsKey(address)){int newAddress=slots.get(address);out.putInt(rwFile+newAddress-rwAddress,b.getInt(elf.offset(address,4)));out.putInt(write,newAddress);out.putInt(write+4,info);}
            else {out.putInt(write,address);out.putInt(write+4,info);}write+=8;
        }
        Arrays.fill(extended,write,relOffset+relSize,(byte)0);out.putInt(tags.get(18)+4,write-relOffset);int relCount=elf.value(tags,0x6ffffffa);require(relCount>=relativeCount,"Invalid ARM relative count");out.putInt(tags.get(0x6ffffffa)+4,relCount-relativeCount);
        for(int i=0;i<shCount;i++){int at=sh+i*shStride;if(b.getInt(at+4)==9&&b.getInt(at+16)==relOffset)out.putInt(at+20,write-relOffset);}
        ArmRuntimePicRepair.validateRelocations(new ArmRuntimePicRepair.Elf(extended));
        write=elf.dynamicOffset;boolean textrel=false;
        for(int read=elf.dynamicOffset;read<elf.dynamicOffset+elf.dynamicSize;read+=8){int tag=b.getInt(read);if(tag==0)break;if(tag==22){textrel=true;continue;}require(tag!=30||(b.getInt(read+4)&4)==0,"Unexpected DF_TEXTREL");System.arraycopy(extended,read,extended,write,8);write+=8;}
        require(textrel,"Missing expected DT_TEXTREL");Arrays.fill(extended,write,elf.dynamicOffset+elf.dynamicSize,(byte)0);require(!NativeStartupChecks.hasTextRelocations(extended),"ARM PIC repair still declares text relocations");return extended;
    }
    private static int prel(int word){return (word<<1)>>1;}
    private static int relative31(int target,int place)throws IOException{long delta=(long)target-place;require(delta>=-(1L<<30)&&delta<(1L<<30),"ARM unwind pointer out of range");return (int)delta&0x7fffffff;}
    private static int branch(int from,int to)throws IOException{long delta=(long)to-from-8;require(delta%4==0&&delta>=-(1L<<25)&&delta<(1L<<25),"ARM PIC branch out of range");return 0xea000000|((int)(delta/4)&0xffffff);}
}
