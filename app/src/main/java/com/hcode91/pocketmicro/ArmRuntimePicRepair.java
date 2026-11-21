package com.hcode91.pocketmicro;

import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.*;

/** PIC instruction/table repair used only after a runtime catalog fingerprint matches. */
final class ArmRuntimePicRepair {
    private ArmRuntimePicRepair(){}
    static String sha(byte[] bytes)throws IOException{
        try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(NoSuchAlgorithmException e){throw new IOException(e);}
    }
    static void require(boolean condition,String error)throws IOException{if(!condition)throw new IOException(error);}
    /** Shared with synthetic fixtures; the public APK entry point always checks source hashes. */
    static byte[] repairLayout(byte[] original,int initializer,int target,int thunk,int plt)throws IOException{
        byte[] data=original.clone();Elf elf=new Elf(data);ByteBuffer b=elf.b;
        int init=elf.offset(initializer,16);
        require(b.getInt(init)==0xe28f0004&&b.getInt(init+4)==0xe5900000&&b.getInt(init+12)==target,"Unexpected ARM initializer");
        require((b.getInt(init+8)&0xff000000)==0xea000000,"Unexpected initializer branch");
        // LDR a fixed relative distance, add the current PC, preserve the existing tail branch.
        b.putInt(init,0xe59f0004);b.putInt(init+4,0xe08f0000);b.putInt(init+12,target-(initializer+12));
        Map<Integer,Integer> remove=new HashMap<>();remove.put(initializer+12,23); // R_ARM_RELATIVE
        if(thunk>=0){
            int t=elf.offset(thunk,24);
            byte[] old={0x10,(byte)0xb5,0x03,0x4b,0x00,0x21,0x7b,0x44,0x1a,0x68,0x02,0x4b,(byte)0x98,0x47,0x10,(byte)0xbd};
            for(int i=0;i<old.length;i++)require(data[t+i]==old[i],"Unexpected Thumb destructor thunk");
            require(b.getInt(t+16)==target-(thunk+10)&&b.getInt(t+20)==0,"Unexpected Thumb literal pool");
            elf.offset(plt,12); // Destination is the original __cxa_atexit PLT, resolved through its writable GOT.
            byte[] code={0x03,0x4b,0x00,0x21,0x7b,0x44,0x1a,0x68,0x02,0x4b,0x7b,0x44,0x18,0x47,0x00,(byte)0xbf};
            System.arraycopy(code,0,data,t,code.length);
            b.putInt(t+16,target-(thunk+8));b.putInt(t+20,plt-(thunk+14));remove.put(thunk+20,2); // R_ARM_ABS32
        }
        Map<Integer,Integer> dynamic=elf.dynamic();
        int table=elf.offset(elf.value(dynamic,17),elf.value(dynamic,18)),size=elf.value(dynamic,18);
        require(size%8==0&&elf.value(dynamic,19)==8,"Unsupported relocation table");
        int write=table,removedRelative=0;Set<Integer> found=new HashSet<>();
        for(int read=table;read<table+size;read+=8){
            int address=b.getInt(read),info=b.getInt(read+4);Integer kind=remove.get(address);
            if(kind!=null){require((info&255)==kind&&found.add(address),"Unexpected/duplicate code relocation");if(kind==23)removedRelative++;}
            else{System.arraycopy(data,read,data,write,8);write+=8;}
        }
        require(found.equals(remove.keySet()),"Required code relocations are missing");
        Arrays.fill(data,write,table+size,(byte)0);int newSize=write-table;
        b.putInt(dynamic.get(18)+4,newSize);
        int count=elf.value(dynamic,0x6ffffffa);require(count>=removedRelative,"Invalid relative relocation count");b.putInt(dynamic.get(0x6ffffffa)+4,count-removedRelative);
        int sh=b.getInt(32),stride=Short.toUnsignedInt(b.getShort(46)),sections=Short.toUnsignedInt(b.getShort(48));
        require(stride>=40&&Elf.range(sh,(long)stride*sections,data.length),"Invalid ELF section table");
        boolean unwindUpdated=thunk<0;
        for(int i=0;i<sections;i++){
            int at=sh+i*stride,type=b.getInt(at+4);
            if(type==9&&b.getInt(at+16)==table)b.putInt(at+20,newSize);
            if(type==0x70000001&&thunk>=0){ // SHT_ARM_EXIDX: original thunk pushed r4/lr, new tail call has no frame.
                int offset=b.getInt(at+16),length=b.getInt(at+20),address=b.getInt(at+12);
                require(length%8==0&&Elf.range(offset,length,data.length),"Invalid ARM unwind table");
                for(int entry=0;entry<length;entry+=8){int relative=(b.getInt(offset+entry)<<1)>>1;
                    if(((address+entry+relative)&~1)==thunk){
                        require(!unwindUpdated&&b.getInt(offset+entry+4)==0x80a8b0b0,"Unexpected Thumb unwind descriptor");
                        b.putInt(offset+entry+4,0x80b0b0b0);unwindUpdated=true;
                    }
                }
            }
        }
        require(unwindUpdated,"Thumb unwind descriptor not found");
        // Only clear the declaration after every remaining loader write is in a writable segment.
        validateRelocations(elf);
        int end=elf.dynamicOffset+elf.dynamicSize;write=elf.dynamicOffset;boolean textrel=false;
        for(int read=elf.dynamicOffset;read<end;read+=8){int tag=b.getInt(read);if(tag==0)break;if(tag==22){textrel=true;continue;}require(tag!=30||(b.getInt(read+4)&4)==0,"Unexpected DF_TEXTREL");System.arraycopy(data,read,data,write,8);write+=8;}
        require(textrel,"Missing expected text-relocation declaration");Arrays.fill(data,write,end,(byte)0);
        require(!NativeStartupChecks.hasTextRelocations(data),"Text relocations remain declared");return data;
    }
    static void validateRelocations(Elf elf)throws IOException{
        Map<Integer,Integer> d=elf.dynamic();
        for(int[] pair:new int[][]{{17,18},{23,2}}){
            if(!d.containsKey(pair[0]))continue;int length=elf.value(d,pair[1]);require(length%8==0,"Malformed relocation table");int start=elf.offset(elf.value(d,pair[0]),length);
            for(int at=start;at<start+length;at+=8){int info=elf.b.getInt(at+4);if((info&255)==0)continue;long address=Integer.toUnsignedLong(elf.b.getInt(at));boolean writable=false;
                for(long[] segment:elf.loads)if(address>=segment[0]&&address+4<=segment[0]+segment[2]&&(segment[3]&2)!=0)writable=true;
                require(writable,"A loader relocation still writes to a non-writable segment");
            }
        }
    }
    static final class Elf {
        final ByteBuffer b;final byte[] data;final List<long[]> loads=new ArrayList<>();int dynamicOffset=-1,dynamicSize;
        Elf(byte[] bytes)throws IOException{
            data=bytes;b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            require(bytes.length>=52&&b.getInt(0)==0x464c457f&&bytes[4]==1&&bytes[5]==1&&b.getShort(18)==40,"Expected little-endian ELF32 ARM");
            int table=b.getInt(28),stride=Short.toUnsignedInt(b.getShort(42)),count=Short.toUnsignedInt(b.getShort(44));
            require(stride>=32&&count<=128&&range(table,(long)stride*count,bytes.length),"Invalid program headers");
            for(int i=0;i<count;i++){int at=table+i*stride,type=b.getInt(at);long offset=Integer.toUnsignedLong(b.getInt(at+4)),address=Integer.toUnsignedLong(b.getInt(at+8)),size=Integer.toUnsignedLong(b.getInt(at+16)),memory=Integer.toUnsignedLong(b.getInt(at+20));
                require(range(offset,size,bytes.length),"Invalid program segment");
                if(type==1)loads.add(new long[]{address,offset,memory,b.getInt(at+24),size});
                if(type==2){dynamicOffset=(int)offset;dynamicSize=(int)size;}
            }require(dynamicOffset>=0&&dynamicSize%8==0,"Missing dynamic table");
        }
        int offset(int address,int length)throws IOException{long v=Integer.toUnsignedLong(address);for(long[] s:loads)if(v>=s[0]&&v+(long)length<=s[0]+s[4]){long out=s[1]+v-s[0];require(range(out,length,data.length),"Invalid file mapping");return (int)out;}throw new IOException("ELF address is not file-backed");}
        Map<Integer,Integer> dynamic()throws IOException{Map<Integer,Integer> out=new HashMap<>();for(int at=dynamicOffset;at<dynamicOffset+dynamicSize;at+=8){int tag=b.getInt(at);if(tag==0)return out;Integer previous=out.put(tag,at);require(tag==1||previous==null,"Duplicate dynamic tag");}throw new IOException("Unterminated dynamic table");}
        int value(Map<Integer,Integer> d,int tag)throws IOException{require(d.containsKey(tag),"Required ELF dynamic tag missing");return b.getInt(d.get(tag)+4);}
        static boolean range(long at,long length,int size){return at>=0&&length>=0&&at<=size&&length<=size-at;}
    }
}
