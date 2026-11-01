package io.github.hozdogus.pocketouya;
import java.io.*;import java.nio.*;import java.nio.file.*;import java.util.*;import java.util.zip.*;
public final class NativeRuntimeRepairTest {
 static void check(boolean value){if(!value)throw new AssertionError("PIC runtime repair regression");}
 static byte[] fixture(boolean thunk){
  ByteBuffer b=ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
  b.putInt(0,0x464c457f).put(4,(byte)1).put(5,(byte)1).putShort(18,(short)40).putInt(28,52).putInt(32,0x700).putShort(42,(short)32).putShort(44,(short)3).putShort(46,(short)40).putShort(48,(short)(thunk?2:1));
  int p=52;b.putInt(p,1).putInt(p+4,0).putInt(p+8,0).putInt(p+16,0x400).putInt(p+20,0x400).putInt(p+24,5);
  p+=32;b.putInt(p,1).putInt(p+4,0x400).putInt(p+8,0x1000).putInt(p+16,0x400).putInt(p+20,0x400).putInt(p+24,6);
  p+=32;b.putInt(p,2).putInt(p+4,0x500).putInt(p+8,0x1100).putInt(p+16,128).putInt(p+20,128).putInt(p+24,6);
  b.putInt(0x100,0xe28f0004).putInt(0x104,0xe5900000).putInt(0x108,0xea00001c).putInt(0x10c,0x1200);
  if(thunk){byte[] bytes={0x10,(byte)0xb5,3,0x4b,0,0x21,0x7b,0x44,0x1a,0x68,2,0x4b,(byte)0x98,0x47,0x10,(byte)0xbd};System.arraycopy(bytes,0,b.array(),0x120,16);b.putInt(0x130,0x1200-0x12a);}
  int at=0x200;b.putInt(at,0x10c).putInt(at+4,23);at+=8;b.putInt(at,0x1200).putInt(at+4,23);at+=8;
  if(thunk){b.putInt(at,0x134).putInt(at+4,0x102);at+=8;}
  b.putInt(at,0x1210).putInt(at+4,0x116);at+=8;
  if(thunk){b.putInt(0x72c,0x70000001).putInt(0x734,0x1280).putInt(0x738,0x680).putInt(0x73c,8);b.putInt(0x680,(0x120-0x1280)&0x7fffffff).putInt(0x684,0x80a8b0b0);}
  int size=at-0x200;b.putInt(0x704,9).putInt(0x710,0x200).putInt(0x714,size);
  int[][] tags={{17,0x200},{18,size},{19,8},{0x6ffffffa,2},{22,0},{0,0}};
  for(int i=0;i<tags.length;i++)b.putInt(0x500+i*8,tags[i][0]).putInt(0x504+i*8,tags[i][1]);return b.array();
 }
 static NativeRuntimeRepair.Plan inspect(Map<String,byte[]> files,List<NativeRuntimeRepair.Profile> catalog)throws Exception{
  Path path=Files.createTempFile("runtime-catalog-", ".apk");
  try{try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(path))){for(var file:files.entrySet()){out.putNextEntry(new ZipEntry(file.getKey()));out.write(file.getValue());out.closeEntry();}}
   try(ZipFile zip=new ZipFile(path.toFile())){return NativeRuntimeRepair.plan(zip,catalog);}
  }finally{Files.delete(path);}
 }
 static void catalogTests()throws Exception{
  byte[] source=fixture(false),fixed=ArmRuntimePicRepair.repairLayout(source,0x100,0x1200,-1,0),proxy={10,20,30};
  String originalHash=ArmRuntimePicRepair.sha(source),outputHash=ArmRuntimePicRepair.sha(fixed),proxyHash=ArmRuntimePicRepair.sha(proxy);
  NativeRuntimeRepair.Profile mono=new NativeRuntimeRepair.Profile("libmono.so",originalHash,outputHash,proxyHash,"test Mono",0x100,0x1200,-1,0);
  NativeRuntimeRepair.Profile unity=new NativeRuntimeRepair.Profile("libunity.so",originalHash,outputHash,proxyHash,"test Unity",0x100,0x1200,-1,0);
  List<NativeRuntimeRepair.Profile> catalog=Arrays.asList(mono,unity);
  String direct="lib/armeabi-v7a/libmono.so",alias="lib/armeabi/libmono.so",asset="assets/libs/armeabi-v7a/libmono.so";
  NativeRuntimeRepair.Plan directPlan=inspect(Map.of(direct,source),catalog);
  check(Arrays.equals(directPlan.replacements.get(direct),fixed)&&directPlan.report.contains("Repaired installed runtime"));
  NativeRuntimeRepair.Plan promoted=inspect(Map.of(direct,proxy,asset,source),catalog);
  check(Arrays.equals(promoted.replacements.get(direct),fixed)&&promoted.report.contains("promoted embedded"));
  NativeRuntimeRepair.Plan aliases=inspect(Map.of(direct,source,alias,source),catalog);
  check(aliases.replacements.size()==2&&aliases.replacements.get(direct)==aliases.replacements.get(alias));
  check(inspect(Map.of(direct,fixed),catalog).report.contains("Already repaired"));
  check(inspect(Map.of(direct,fixed),catalog).replacements.isEmpty());
  check(inspect(Map.of(direct,proxy,asset,fixed),catalog).replacements.size()==1);
  check(inspect(Map.of(direct,proxy),catalog).replacements.isEmpty());
  check(inspect(Map.of(direct,proxy),catalog).report.contains("Known proxy, missing"));
  check(inspect(Map.of(direct,proxy,asset,new byte[]{99}),catalog).report.contains("Unrecognized embedded payload"));
  check(inspect(Map.of(direct,new byte[]{99},asset,source),catalog).replacements.isEmpty()); // Unknown proxy cannot be bypassed.
  check(inspect(Map.of("lib/arm64-v8a/libmono.so",source),catalog).report.contains("Unsupported ABI"));
  NativeRuntimeRepair.Plan partial=inspect(Map.of(direct,source,"lib/armeabi-v7a/libunity.so",new byte[]{99}),catalog);
  check(partial.replacements.size()==1&&partial.report.contains("Unsupported candidates: 1")&&partial.report.contains("partial repair"));
  check(inspect(Collections.emptyMap(),catalog).replacements.isEmpty());
  byte[] version2=source.clone();version2[0x7ff]=2;byte[] repaired2=ArmRuntimePicRepair.repairLayout(version2,0x100,0x1200,-1,0);
  NativeRuntimeRepair.Profile second=new NativeRuntimeRepair.Profile("libmono.so",ArmRuntimePicRepair.sha(version2),ArmRuntimePicRepair.sha(repaired2),proxyHash,"second version",0x100,0x1200,-1,0);
  NativeRuntimeRepair.Plan versions=inspect(Map.of(direct,proxy,asset,version2),Arrays.asList(mono,second));
  check(Arrays.equals(versions.replacements.get(direct),repaired2)&&versions.report.contains("second version")&&versions.report.contains("Unsupported candidates: 0"));
  NativeRuntimeRepair.Profile broken=new NativeRuntimeRepair.Profile("libmono.so",originalHash,"0".repeat(64),proxyHash,"broken catalog fixture",0x100,0x1200,-1,0);
  try{inspect(Map.of(direct,source),Collections.singletonList(broken));throw new AssertionError("accepted unverified output");}catch(IOException expected){}
  System.out.println("Game-independent direct/proxy runtime matching, ABI aliases, partial/unknown/missing/idempotent cases and version/output-hash tests pass");
 }
 public static void main(String[] args)throws Exception{
  for(boolean thunk:new boolean[]{false,true}){
   byte[] original=fixture(thunk),before=original.clone();
   byte[] fixed=ArmRuntimePicRepair.repairLayout(original,0x100,0x1200,thunk?0x120:-1,0x180);
   ByteBuffer b=ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN);
   check(Arrays.equals(original,before));check(!NativeStartupChecks.hasTextRelocations(fixed));
   check(b.getInt(0x100)==0xe59f0004&&b.getInt(0x104)==0xe08f0000&&b.getInt(0x108)==0xea00001c&&b.getInt(0x10c)==0x1200-0x10c);
   check(b.getInt(0x200)==0x1200&&b.getInt(0x208)==0x1210);check(b.getInt(0x714)==16);
   check(b.getInt(0x51c)==1); // DT_RELCOUNT (2 -> 1)
   check(b.getInt(52+24)==5&&b.getInt(84+24)==6); // RX/RW unchanged
   if(thunk){check(b.getInt(0x684)==0x80b0b0b0);check(b.getShort(0x12c)==0x4718);check(b.getInt(0x130)==0x1200-0x128&&b.getInt(0x134)==0x180-0x12e);}
   byte[] corrupt=fixture(thunk);corrupt[0x100]=0;
   try{ArmRuntimePicRepair.repairLayout(corrupt,0x100,0x1200,thunk?0x120:-1,0x180);throw new AssertionError("accepted unknown initializer");}catch(IOException expected){}
   corrupt=fixture(thunk);ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putInt(0x208,0x300); // Another relocation into RX.
   try{ArmRuntimePicRepair.repairLayout(corrupt,0x100,0x1200,thunk?0x120:-1,0x180);throw new AssertionError("cleared flag with a remaining code relocation");}catch(IOException expected){}
  }
  catalogTests();
  System.out.println("ARM/Thumb PIC fixture, relocation/count/section compaction, unchanged segment permissions and fail-closed runtime repair tests pass");
 }
}
