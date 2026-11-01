package io.github.hozdogus.pocketouya;
import java.io.*;import java.nio.*;import java.nio.file.*;import java.util.zip.*;
public final class NativeStartupChecksTest {
 static void check(boolean ok){if(!ok)throw new AssertionError("Native loading diagnostics regression");}
 static byte[] elf(boolean wide,int tag,int flags){
  ByteBuffer b=ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
  b.put(0,(byte)0x7f).put(1,(byte)'E').put(2,(byte)'L').put(3,(byte)'F').put(4,(byte)(wide?2:1)).put(5,(byte)1);
  if(wide){b.putLong(32,64);b.putShort(54,(short)56);b.putShort(56,(short)1);b.putInt(64,2);b.putLong(72,128);b.putLong(96,32);b.putLong(128,tag);b.putLong(136,flags);}
  else{b.putInt(28,64);b.putShort(42,(short)32);b.putShort(44,(short)1);b.putInt(64,2);b.putInt(68,128);b.putInt(80,16);b.putInt(128,tag);b.putInt(132,flags);}
  return b.array();
 }
 public static void main(String[] args)throws Exception {
  for(boolean wide:new boolean[]{false,true}){
   check(NativeStartupChecks.hasTextRelocations(elf(wide,22,0)));
   check(NativeStartupChecks.hasTextRelocations(elf(wide,30,4)));
   check(!NativeStartupChecks.hasTextRelocations(elf(wide,30,2)));
   byte[] malformed=elf(wide,22,0);ByteBuffer b=ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN);
   if(wide)b.putLong(72,Long.MAX_VALUE);else b.putInt(68,-1);
   check(!NativeStartupChecks.hasTextRelocations(malformed));
   check(!NativeStartupChecks.hasTextRelocations(java.util.Arrays.copyOf(elf(wide,22,0),80)));
  }
  check(!NativeStartupChecks.hasTextRelocations(new byte[]{1,2,3}));
  Path apk=Files.createTempFile("native-warning-", ".apk");
  try{
   try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(apk))){out.putNextEntry(new ZipEntry("lib/armeabi/liblegacy.so"));out.write(elf(false,22,0));out.closeEntry();}
   try(ZipFile zip=new ZipFile(apk.toFile())){String report=NativeStartupChecks.inspect(zip);check(report.contains("lib/armeabi/liblegacy.so")&&report.contains("API 23")&&report.contains("not a crash log"));}
  }finally{Files.delete(apk);}
  System.out.println("ELF32/ELF64 DT_TEXTREL/DF_TEXTREL, malformed bounds and APK native-warning tests pass");
 }
}
