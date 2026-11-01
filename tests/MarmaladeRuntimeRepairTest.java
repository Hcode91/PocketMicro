package io.github.hozdogus.pocketouya;
import java.io.*;import java.nio.*;import java.util.*;
public final class MarmaladeRuntimeRepairTest {
 static void check(boolean ok){if(!ok)throw new AssertionError("Marmalade PIC repair regression");}
 static byte[] fixture(){
  ByteBuffer b=ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
  b.putInt(0,0x464c457f).put(4,(byte)1).put(5,(byte)1).putShort(18,(short)40).putInt(28,52).putInt(32,0x800).putShort(42,(short)32).putShort(44,(short)4).putShort(46,(short)40).putShort(48,(short)2);
  int p=52;b.putInt(p,0x70000001).putInt(p+4,0x2f0).putInt(p+8,0x2f0).putInt(p+16,8).putInt(p+20,8).putInt(p+24,4);
  p+=32;b.putInt(p,1).putInt(p+4,0).putInt(p+8,0).putInt(p+16,0x2f8).putInt(p+20,0x2f8).putInt(p+24,5);
  p+=32;b.putInt(p,1).putInt(p+4,0x400).putInt(p+8,0x1400).putInt(p+16,0x400).putInt(p+20,0x600).putInt(p+24,6);
  p+=32;b.putInt(p,2).putInt(p+4,0x500).putInt(p+8,0x1500).putInt(p+16,128).putInt(p+20,128).putInt(p+24,6);
  b.putInt(0x100,0xe28f0004).putInt(0x104,0xe5900000).putInt(0x108,0xea000000).putInt(0x10c,0x1700);
  b.putInt(0x120,0xe59fe058).putInt(0x180,0x1600).putInt(0x600,0x1604);
  b.putInt(0x200,0x10c).putInt(0x204,23).putInt(0x208,0x180).putInt(0x20c,23).putInt(0x210,0x1600).putInt(0x214,23);
  int[][] tags={{17,0x200},{18,24},{19,8},{0x6ffffffa,3},{22,0},{0,0}};
  for(int i=0;i<tags.length;i++)b.putInt(0x500+i*8,tags[i][0]).putInt(0x504+i*8,tags[i][1]);
  b.putInt(0x804,9).putInt(0x810,0x200).putInt(0x814,24);
  b.putInt(0x82c,0x70000001).putInt(0x834,0x2f0).putInt(0x838,0x2f0).putInt(0x83c,8);
  b.putInt(0x2f0,(0x280-0x2f0)&0x7fffffff).putInt(0x2f4,1);return b.array();
 }
 public static void main(String[] args)throws Exception{
  byte[] source=fixture(),snapshot=source.clone();byte[] fixed=MarmaladeRuntimeRepair.repairLayout(source,0x100,0x1700,2,1);ByteBuffer b=ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN);
  check(Arrays.equals(source,snapshot)&&!NativeStartupChecks.hasTextRelocations(fixed));
  check(b.getInt(28)==0x1000&&b.getShort(44)==6&&b.getInt(0x1000)==6); // Explicit resident PT_PHDR.
  check(b.getInt(0x1008)==0x2000&&b.getInt(0x1010)==192&&b.getInt(0x1000+5*32)==1&&b.getInt(0x1000+5*32+24)==5);
  check(b.getInt(0x1000+2*32+16)==0x300&&b.getInt(0x1000+2*32+24)==5); // Extend original RX into padding only.
  check(b.getInt(0x83c)==16&&b.getInt(0x2fc)==1&&b.getInt(0x2f8)==0x20c0-0x2f8);
  check(b.getInt(0x814)==8&&b.getInt(0x51c)==1&&b.getInt(0x200)==0x1600);
  check(b.getInt(0x10c0)==0xe59fe004&&b.getInt(0x10c4)==0xe08fe00e&&b.getInt(0x10cc)==0x1600-0x20cc);
  int branch=b.getInt(0x120),delta=((branch&0xffffff)<<8)>>6;check(0x128+delta==0x20c0);
  for(int kind=0;kind<5;kind++){
   byte[] corrupt=fixture();ByteBuffer c=ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN);
   if(kind==0)c.putInt(0x120,0xe59fd058); // SP cannot be used as temporary distance.
   if(kind==1)c.putInt(0x120,0xe1a00000); // Missing literal reference.
   if(kind==2)c.putInt(0x2f8,1); // Padding is occupied.
   if(kind==4)c.putShort(0x130,(short)0xa013); // Unhandled Thumb ADR into a code literal.
   if(kind==3)c.putInt(0x2f0,(0x110-0x2f0)&0x7fffffff); // Existing unwind coverage at patched site.
   try{MarmaladeRuntimeRepair.repairLayout(corrupt,0x100,0x1700,2,1);throw new AssertionError("accepted unsupported fixture "+kind);}catch(IOException expected){}
  }
  byte[] candidate=fixed.clone();ByteBuffer c=ByteBuffer.wrap(candidate).order(ByteOrder.LITTLE_ENDIAN);
  c.position(0x23a);c.put(new byte[]{(byte)0x9c,0x46,0x30,0x1c,0x52,0x46,0,0x23,(byte)0xe0,0x47,0x33,0x68,0x42,0x46,(byte)0x9b,0x6d});
  byte[] guarded=MarmaladeRuntimeRepair.guardSurfaceRelease(candidate,0x240);ByteBuffer g=ByteBuffer.wrap(guarded).order(ByteOrder.LITTLE_ENDIAN);
  int tail=candidate.length,load=0x1000+5*32;
  check(guarded.length==candidate.length+12&&g.getInt(load+16)==c.getInt(load+16)+12&&g.getInt(load+20)==g.getInt(load+16)&&g.getInt(load+24)==5);
  check(g.getShort(tail)==(short)0x2a00&&g.getShort(tail+2)==(short)0xd001&&g.getShort(tail+4)==(short)0x2300&&g.getShort(tail+6)==(short)0x4760&&g.getShort(tail+8)==(short)0x4770);
  int distance=((g.getShort(0x240)&0x7ff)<<12)|((g.getShort(0x242)&0x7ff)<<1);if((distance&(1<<22))!=0)distance|=~((1<<23)-1);
  check(0x244+distance==c.getInt(load+8)+c.getInt(load+16));
  check(!NativeStartupChecks.hasTextRelocations(guarded));
  for(int kind=0;kind<3;kind++){
   byte[] corrupt=candidate.clone();ByteBuffer v=ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN);
   if(kind==0)v.putShort(0x242,(short)0x47a0); // Wrong indirect target register.
   if(kind==1)v.putInt(load+24,7); // Never extend writable executable code.
   if(kind==2)v.putInt(load+20,v.getInt(load+20)+4); // Unsupported BSS at extension end.
   try{MarmaladeRuntimeRepair.guardSurfaceRelease(corrupt,0x240);throw new AssertionError("accepted unsupported surface guard "+kind);}catch(IOException expected){}
  }
  System.out.println("Marmalade JNI surface guard branch, RX extension and rejection fixtures pass");
  System.out.println("ARM literal branch stubs, resident program headers, RX/RW mapping, unwind boundary, relocation compaction and rejection tests pass");
 }
}
