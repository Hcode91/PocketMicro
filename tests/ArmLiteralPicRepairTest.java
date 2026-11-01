package io.github.hozdogus.pocketouya;
import java.io.*;import java.nio.*;import java.util.*;
public final class ArmLiteralPicRepairTest {
 static void check(boolean value){if(!value)throw new AssertionError("ARM literal PIC/unwind regression");}
 static byte[] fixture(boolean symbolic,boolean unwind){
  byte[] data=MarmaladeRuntimeRepairTest.fixture();ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
  b.putInt(0x100,0xe59f0004).putInt(0x104,0xe1a00000).putInt(0x108,0xe1a00000);
  b.putInt(0x2f0,(0x80-0x2f0)&0x7fffffff).putInt(0x2f4,(0x2e0-0x2f4)&0x7fffffff).putInt(0x2e0,0x80b0b0b0);
  if(symbolic){b.putInt(0x208,0x1600).putInt(0x20c,23).putInt(0x210,0x180).putInt(0x214,0x102).putInt(0x51c,2).putInt(0x180,0x10);}
  if(!unwind){b.putInt(52,0).putShort(48,(short)1);}return data;
 }
 public static void main(String[]args)throws Exception{
  for(boolean symbolic:new boolean[]{false,true})for(boolean hasUnwind:new boolean[]{false,true}){
   byte[] source=fixture(symbolic,hasUnwind),copy=source.clone();byte[] fixed=ArmLiteralPicRepair.repair(source,symbolic?1:2,symbolic?1:0,2);check(Arrays.equals(source,copy));
   ByteBuffer b=ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN);ArmRuntimePicRepair.Elf elf=new ArmRuntimePicRepair.Elf(fixed);
   check(!NativeStartupChecks.hasTextRelocations(fixed));ArmRuntimePicRepair.validateRelocations(elf);
   check(b.getShort(44)==(symbolic?7:6));
   int count=Short.toUnsignedInt(b.getShort(44)),ph=b.getInt(28),ex=-1,rw=-1;
   for(int i=0;i<count;i++){int p=ph+32*i;if(b.getInt(p)==0x70000001)ex=p;if(b.getInt(p)==1){check(b.getInt(p+24)!=7);if(b.getInt(p+24)==6&&b.getInt(p+16)==4)rw=p;}}
   check((ex>=0)==hasUnwind);check((rw>=0)==symbolic);
   if(hasUnwind){int off=b.getInt(ex+4),addr=b.getInt(ex+8);check(b.getInt(ex+16)==32);check(addr+((b.getInt(off)<<1)>>1)==0x80);check(addr+4+((b.getInt(off+4)<<1)>>1)==0x2e0);check(b.getInt(off+28)==1);}
   if(symbolic){int slot=b.getInt(rw+8),slotOffset=b.getInt(rw+4);check(b.getInt(slotOffset)==0x10);boolean relocated=false;
    Map<Integer,Integer> d=elf.dynamic();int start=elf.offset(elf.value(d,17),elf.value(d,18));
    for(int p=start;p<start+elf.value(d,18);p+=8)if(b.getInt(p)==slot&&b.getInt(p+4)==0x102)relocated=true;check(relocated);
   }
  }
  for(int kind=0;kind<5;kind++){
   byte[] source=fixture(false,true);ByteBuffer b=ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
   if(kind==0)b.putInt(0x120,0xe59fd058); // Do not clobber SP.
   if(kind==1)b.putInt(0x120,0xe1a00000); // Missing inspected reference.
   if(kind==2)b.putInt(0x10c,0x180); // A literal address itself is taken.
   if(kind==3)b.putInt(0x2f4,0x100000); // Extab is not mapped.
   if(kind==4)b.putInt(0x130,0xe28f3048); // ADR takes address of literal 0x180.
   try{ArmLiteralPicRepair.repair(source,2,0,2);throw new AssertionError("Unsupported literal layout accepted "+kind);}catch(IOException expected){}
  }
  System.out.println("ARM literal PIC branches, cloned PREL31 unwind recipes, symbolic writable cells, absent unwind and rejection fixtures pass");
 }
}
