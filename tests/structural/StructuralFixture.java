package io.github.hozdogus.pocketouya;
import java.nio.*;
import java.nio.charset.StandardCharsets;
/** Generated ELF test layouts. No proprietary bytes; no offsets shared with production detector. */
final class StructuralFixture {
 final byte[] bytes;final ByteBuffer b;final int f,t,plt,target,got,rel,jmp,ds,str,fini;
 StructuralFixture(int shift,boolean thumb,String mode){
  b=ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);bytes=b.array();f=0x200+shift;t=f+0x40;plt=0x400+shift;target=0x2300+shift;got=0x2380+shift;rel=0xc00;jmp=0xd00;ds=0xe00;str=0x1000;fini=0xa00;
  b.putInt(0,0x464c457f).put(4,(byte)1).put(5,(byte)1).put(6,(byte)1).putShort(16,(short)3).putShort(18,(short)40).putInt(20,1).putInt(28,52).putInt(32,0x1700).putShort(40,(short)52).putShort(42,(short)32).putShort(44,(short)3).putShort(46,(short)40).putShort(48,(short)8).putShort(50,(short)1);
  ph(52,1,0,0,0x800,0x800,5);ph(84,1,0x800,0x2000,0xc00,0xc00,6);ph(116,2,0x800,0x2000,0x100,0x100,6);
  b.putInt(f,0xe28f0004).putInt(f+4,0xe5900000).putInt(f+8,0xea000000|((plt-f-16)/4&0xffffff)).putInt(f+12,target);
  plt(plt,got);plt(plt+12,got+4);
  b.putInt(fini,f);int rr=rel;b.putInt(rr,f+12).putInt(rr+4,23);rr+=8;b.putInt(rr,0x2200).putInt(rr+4,23);rr+=8;
  String names="\0__cxa_finalize\0__cxa_atexit\0thumb_function\0$t\0$d\0$a\0";byte[] ns=names.getBytes(StandardCharsets.US_ASCII);System.arraycopy(ns,0,bytes,str,ns.length);
  symbol(ds+16,names.indexOf("__cxa_finalize"),0,0,2);symbol(ds+32,names.indexOf("__cxa_atexit"),0,0,2);
  b.putInt(jmp,got).putInt(jmp+4,(1<<8)|22).putInt(jmp+8,got+4).putInt(jmp+12,(2<<8)|22);
  int symCount=3;
  if(thumb){
   int[] half={0xb510,0x4b03,0x2100,0x447b,0x681a,0x4b02,0x4798,0xbd10};for(int i=0;i<half.length;i++)b.putShort(t+2*i,(short)half[i]);
   b.putInt(t+16,target-(t+10)).putInt(t+20,0);b.putInt(rr,t+20).putInt(rr+4,(2<<8)|2);rr+=8;
   b.putInt(0x500,(t-0x500)&0x7fffffff).putInt(0x504,0x80a8b0b0).putInt(0x508,(t+24-0x508)&0x7fffffff).putInt(0x50c,1);
   if(mode.equals("typed")||mode.equals("conflict")){symbol(ds+48,names.indexOf("thumb_function"),t|1,24,2);symCount++;}
   if(mode.equals("mapped")){symbol(ds+48,names.indexOf("$t"),t,0,0);symbol(ds+64,names.indexOf("$d"),t+16,0,0);symCount+=2;}
   if(mode.equals("conflict")){symbol(ds+64,names.indexOf("$a"),t,0,0);symCount++;}
  }
  int[][] tags={{6,0x2600},{11,16},{5,0x2800},{10,ns.length},{17,0x2400},{18,rr-rel},{19,8},{23,0x2500},{2,16},{26,0x2200},{28,4},{22,0},{0,0}};
  for(int i=0;i<tags.length;i++)b.putInt(0x800+i*8,tags[i][0]).putInt(0x804+i*8,tags[i][1]);
  String labels="\0.shstrtab\0.plt\0.dynsym\0.dynstr\0.ARM.exidx\0.spare\0";byte[] ls=labels.getBytes(StandardCharsets.US_ASCII);System.arraycopy(ls,0,bytes,0x1500,ls.length);
  sh(1,labels.indexOf(".shstrtab"),3,0,0x1500,ls.length,0,0);sh(2,labels.indexOf(".plt"),1,plt,plt,24,0,0);
  sh(3,labels.indexOf(".dynsym"),11,0x2600,ds,16*symCount,4,16);sh(4,labels.indexOf(".dynstr"),3,0x2800,str,ns.length,0,0);
  sh(5,labels.indexOf(".ARM.exidx"),0x70000001,0x500,0x500,thumb?16:0,0,8);sh(6,labels.indexOf(".spare"),1,0,0x1600,4,0,0);sh(7,labels.indexOf(".spare"),1,f,f,0x100,0,0);b.putInt(0x1700+7*40+8,6);b.putInt(0x1700+2*40+8,6);
 }
 void ph(int at,int kind,int off,int va,int file,int mem,int flags){b.putInt(at,kind).putInt(at+4,off).putInt(at+8,va).putInt(at+16,file).putInt(at+20,mem).putInt(at+24,flags).putInt(at+28,4096);}
 void sh(int index,int name,int kind,int va,int off,int size,int link,int stride){int at=0x1700+40*index;b.putInt(at,name).putInt(at+4,kind).putInt(at+12,va).putInt(at+16,off).putInt(at+20,size).putInt(at+24,link).putInt(at+36,stride);}
 void symbol(int at,int name,int value,int size,int info){b.putInt(at,name).putInt(at+4,value).putInt(at+8,size).put(at+12,(byte)info).putShort(at+14,(short)(value==0?0:7));}
 void plt(int at,int slot){b.putInt(at,0xe28fca01).putInt(at+4,0xe28cc000).putInt(at+8,0xe5bcf000|(slot-at-8-0x1000));}
}
