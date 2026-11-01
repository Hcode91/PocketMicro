package io.github.hozdogus.pocketouya;

import java.io.IOException;
import java.nio.*;
import java.util.*;
import static io.github.hozdogus.pocketouya.ArmRuntimePicRepair.require;

/** PIC repair for the cataloged Vector ARM library. Public selection requires its exact fingerprint. */
final class MarmaladeRuntimeRepair {
    private MarmaladeRuntimeRepair(){}
    static byte[] repair(byte[] source)throws IOException{return guardSurfaceRelease(repairLayout(source,0xea60,0xbf2b0,12,43),0x33154);}
    static byte[] upgrade(byte[] source)throws IOException{return guardSurfaceRelease(source,0x33154);}
    static byte[] repairLayout(byte[] source,int initializer,int dso,int expectedTargets,int expectedSites)throws IOException{
        byte[] data=source.clone();ArmRuntimePicRepair.Elf elf=new ArmRuntimePicRepair.Elf(data);ByteBuffer b=elf.b;
        int ph=b.getInt(28),stride=Short.toUnsignedInt(b.getShort(42)),count=Short.toUnsignedInt(b.getShort(44));
        require(stride==32&&count<128,"Unsupported program header layout");
        List<int[]> headers=new ArrayList<>();long maximum=0;
        for(int i=0;i<count;i++){int[] p=new int[8];for(int j=0;j<8;j++)p[j]=b.getInt(ph+i*32+j*4);require(p[0]!=6,"Unexpected existing PT_PHDR");headers.add(p);if(p[0]==1)maximum=Math.max(maximum,Integer.toUnsignedLong(p[2])+Integer.toUnsignedLong(p[5]));}
        Map<Integer,Integer> tags=elf.dynamic();int size=elf.value(tags,18),start=elf.offset(elf.value(tags,17),size);
        require(size%8==0&&elf.value(tags,19)==8,"Unsupported relocation table");
        Map<Integer,Integer> bad=new TreeMap<>();
        for(int at=start;at<start+size;at+=8){int address=b.getInt(at),info=b.getInt(at+4);if((info&255)==0)continue;
            boolean writable=false;long value=Integer.toUnsignedLong(address);
            for(long[] segment:elf.loads)if(value>=segment[0]&&value+4<=segment[0]+segment[2]&&(segment[3]&2)!=0)writable=true;
            if(!writable)require((info&255)==23&&bad.put(address,info)==null,"Unsupported/duplicate code relocation");
        }
        require(bad.size()==expectedTargets,"Unexpected code relocation count");
        for(int at=start;at<start+size;at+=8)if((b.getInt(at+4)&255)==23)
            require(!bad.containsKey(b.getInt(elf.offset(b.getInt(at),4))),"A code literal is referenced by another relocation");
        int init=elf.offset(initializer,16);
        require(b.getInt(init)==0xe28f0004&&b.getInt(init+4)==0xe5900000&&(b.getInt(init+8)&0xff000000)==0xea000000&&b.getInt(init+12)==dso&&bad.containsKey(initializer+12),"Unexpected initializer");
        b.putInt(init,0xe59f0004);b.putInt(init+4,0xe08f0000);b.putInt(init+12,dso-(initializer+12));
        ByteBuffer original=ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        List<int[]> sites=new ArrayList<>();Set<Integer> covered=new HashSet<>();covered.add(initializer+12);
        for(long[] segment:elf.loads)if((segment[3]&1)!=0){
            for(long va=segment[0];va+4<=segment[0]+segment[4];va+=4){int site=(int)va,at=elf.offset(site,4),instruction=original.getInt(at);
                int adr=instruction&0x0fff0000;
                if(adr==0x028f0000||adr==0x024f0000){int immediate=Integer.rotateRight(instruction&255,((instruction>>>8)&15)*2),literal=site+8+(adr==0x028f0000?immediate:-immediate);require(!bad.containsKey(literal)||site==initializer,"Unsupported ADR reference to a code literal");}
                if((instruction&0x0f7f0000)!=0x051f0000)continue;
                int literal=site+8+((instruction&0x800000)!=0?(instruction&4095):-(instruction&4095));if(!bad.containsKey(literal))continue;
                int rd=(instruction>>>12)&15;require((instruction>>>28)==14&&rd!=13&&rd!=15,"Unsupported ARM literal load");
                sites.add(new int[]{site,rd,original.getInt(elf.offset(literal,4))});covered.add(literal);
            }
            for(long va=segment[0];va+2<=segment[0]+segment[4];va+=2){int half=Short.toUnsignedInt(original.getShort(elf.offset((int)va,2)));if((half&0xf800)==0x4800||(half&0xf800)==0xa000)require(!bad.containsKey((((int)va+4)&~3)+((half&255)<<2)),"Unsupported Thumb reference to a code literal");}
        }
        require(sites.size()==expectedSites&&covered.equals(bad.keySet()),"Code relocation references do not match inspected layout");
        // Append one CANTUNWIND entry in existing padding. The original assembly
        // sites precede the first unwind-covered function; the stubs must not
        // accidentally inherit the last unrelated function's unwind recipe.
        int sh=b.getInt(32),shStride=Short.toUnsignedInt(b.getShort(46)),shCount=Short.toUnsignedInt(b.getShort(48));
        require(shStride>=40&&ArmRuntimePicRepair.Elf.range(sh,(long)shStride*shCount,data.length),"Invalid section table");
        int ex=-1;
        for(int i=0;i<shCount;i++){int at=sh+i*shStride;if(b.getInt(at+4)==0x70000001){require(ex==-1,"Multiple unwind tables");ex=at;}}
        require(ex>=0,"Missing unwind table");int exOffset=b.getInt(ex+16),exAddress=b.getInt(ex+12),exSize=b.getInt(ex+20);
        require(exSize>=8&&exSize%8==0&&ArmRuntimePicRepair.Elf.range(exOffset,(long)exSize+8,data.length),"Invalid unwind table/padding");
        int first=exAddress+((b.getInt(exOffset)<<1)>>1);
        require(initializer<first,"Initializer has unsupported unwind coverage");for(int[] site:sites)require(site[0]+4<=first,"A literal site has unsupported unwind coverage");
        int end=exOffset+exSize,endAddress=exAddress+exSize;
        require(b.getLong(end)==0,"Unwind padding is not empty");
        for(int i=0;i<shCount;i++){int at=sh+i*shStride,type=b.getInt(at+4),offset=b.getInt(at+16),length=b.getInt(at+20);if(type!=8&&length>0)require((long)offset+length<=end||offset>=end+8,"Unwind extension overlaps a section");}
        boolean extended=false,unwindHeader=false;
        for(int[] p:headers){
            if(p[0]==1&&p[1]+p[4]==end){require(!extended&&p[2]+p[5]==endAddress&&p[4]==p[5]&&p[6]==5,"Unwind table does not end a plain RX segment");p[4]+=8;p[5]+=8;extended=true;}
            else if(p[0]==1)require(Integer.toUnsignedLong(p[2])>=Integer.toUnsignedLong(endAddress)+8||Integer.toUnsignedLong(p[2])+Integer.toUnsignedLong(p[5])<=Integer.toUnsignedLong(exAddress),"Unwind extension overlaps another mapping");
            if(p[0]==0x70000001){require(!unwindHeader&&p[1]==exOffset&&p[2]==exAddress&&p[4]==exSize&&p[5]==exSize,"Unexpected ARM unwind program header");p[4]+=8;p[5]+=8;unwindHeader=true;}
        }
        require(extended&&unwindHeader,"Cannot extend ARM unwind mapping");b.putInt(ex+20,exSize+8);
        long fileStart=((long)data.length+4095)&~4095L,memoryStart=(maximum+4095)&~4095L;
        int headerCount=count+2,tableSize=headerCount*32,newSize=tableSize+sites.size()*16;
        require(fileStart+newSize<=Integer.MAX_VALUE&&memoryStart+newSize<0x80000000L,"Runtime extension exceeds supported bounds");
        int file=(int)fileStart,memory=(int)memoryStart;
        b.putInt(end,(memory+tableSize-endAddress)&0x7fffffff);b.putInt(end+4,1);
        data=Arrays.copyOf(data,file+newSize);b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        List<int[]> outputHeaders=new ArrayList<>();outputHeaders.add(new int[]{6,file,memory,memory,tableSize,tableSize,4,4});outputHeaders.addAll(headers);outputHeaders.add(new int[]{1,file,memory,memory,newSize,newSize,5,4096});
        for(int i=0;i<outputHeaders.size();i++)for(int j=0;j<8;j++)b.putInt(file+i*32+j*4,outputHeaders.get(i)[j]);
        b.putInt(28,file);b.putShort(44,(short)headerCount);
        for(int i=0;i<sites.size();i++){int[] site=sites.get(i);int code=memory+tableSize+i*16,at=file+tableSize+i*16,rd=site[1];
            b.putInt(elf.offset(site[0],4),branch(site[0],code));
            b.putInt(at,0xe59f0004|(rd<<12));b.putInt(at+4,0xe08f0000|(rd<<12)|rd);b.putInt(at+8,branch(code+8,site[0]+4));b.putInt(at+12,site[2]-(code+12));
        }
        int write=start;
        for(int read=start;read<start+size;read+=8)if(!bad.containsKey(b.getInt(read))){System.arraycopy(data,read,data,write,8);write+=8;}
        Arrays.fill(data,write,start+size,(byte)0);b.putInt(tags.get(18)+4,write-start);
        int relative=elf.value(tags,0x6ffffffa);require(relative>=bad.size(),"Invalid relative count");b.putInt(tags.get(0x6ffffffa)+4,relative-bad.size());
        for(int i=0;i<shCount;i++){int at=sh+i*shStride;if(b.getInt(at+4)==9&&b.getInt(at+16)==start)b.putInt(at+20,write-start);}
        ArmRuntimePicRepair.validateRelocations(new ArmRuntimePicRepair.Elf(data));
        int dynamicEnd=elf.dynamicOffset+elf.dynamicSize;write=elf.dynamicOffset;boolean found=false;
        for(int read=elf.dynamicOffset;read<dynamicEnd;read+=8){int tag=b.getInt(read);if(tag==0)break;if(tag==22){found=true;continue;}require(tag!=30||(b.getInt(read+4)&4)==0,"Unexpected DF_TEXTREL");System.arraycopy(data,read,data,write,8);write+=8;}
        require(found,"Missing expected DT_TEXTREL");Arrays.fill(data,write,dynamicEnd,(byte)0);return data;
    }
    /** Guard the inspected ReleaseIntArrayElements call, retaining DeleteLocalRef and normal cleanup. */
    static byte[] guardSurfaceRelease(byte[] source,int site)throws IOException{
        ArmRuntimePicRepair.Elf elf=new ArmRuntimePicRepair.Elf(source);ByteBuffer b=elf.b;
        int at=elf.offset(site,10),before=elf.offset(site-6,6);
        require(b.getInt(before)==0x1c30469c&&b.getInt(at-2)==0x23004652&&b.getInt(at+2)==0x683347e0&&b.getInt(at+6)==0x6d9b4642,"Unexpected Marmalade surface release call");
        int ph=b.getInt(28),count=Short.toUnsignedInt(b.getShort(44)),last=-1;
        for(int i=0;i<count;i++){int p=ph+i*32;
            if(b.getInt(p)==1&&Integer.toUnsignedLong(b.getInt(p+4))+Integer.toUnsignedLong(b.getInt(p+16))==source.length){require(last<0,"Ambiguous runtime extension");last=p;}}
        require(last>=0&&b.getInt(last+24)==5&&b.getInt(last+16)==b.getInt(last+20),"Missing plain RX runtime extension");
        int size=b.getInt(last+16),stub=b.getInt(last+8)+size;long delta=(long)stub-site-4;
        require((stub&1)==0&&(delta&1)==0&&delta>=-(1<<22)&&delta<(1<<22),"Thumb guard is out of branch range");
        byte[] data=Arrays.copyOf(source,source.length+12);b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        // Thumb-1 BL sets exactly the original BLX's return address (site + 5).
        b.putShort(at,(short)(0xf000|((delta>>12)&0x7ff)));b.putShort(at+2,(short)(0xf800|((delta>>1)&0x7ff)));
        int end=source.length;
        b.putShort(end,(short)0x2a00);       // cmp r2, #0 (native elements pointer)
        b.putShort(end+2,(short)0xd001);     // beq return
        b.putShort(end+4,(short)0x2300);     // movs r3, #0 (original JNI mode)
        b.putShort(end+6,(short)0x4760);     // bx ip: original JNI target, original LR
        b.putShort(end+8,(short)0x4770);     // return: bx lr, continue DeleteLocalRef
        b.putShort(end+10,(short)0x46c0);    // alignment padding
        b.putInt(last+16,size+12);b.putInt(last+20,size+12);
        ArmRuntimePicRepair.validateRelocations(new ArmRuntimePicRepair.Elf(data));return data;
    }
    private static int branch(int from,int to)throws IOException{
        long delta=(long)to-from-8;require(delta%4==0&&delta>=-(1L<<25)&&delta<(1L<<25),"ARM branch is out of range");return 0xea000000|((int)(delta/4)&0xffffff);
    }
}
