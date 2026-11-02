package io.github.hozdogus.pocketouya;
import java.nio.*;import java.nio.file.*;import java.util.*;import java.util.zip.*;
public final class ModernApkDoctorTest {
 static void check(boolean b){if(!b)throw new AssertionError("APK doctor regression");}
 static void apk(Path p,byte[] manifest,Map<String,byte[]> entries)throws Exception{
  try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(p))){z.putNextEntry(new ZipEntry("AndroidManifest.xml"));z.write(manifest);z.closeEntry();for(var e:entries.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}}
 }
 static byte[] sdk(byte[] data,int attribute,int value){byte[] out=data.clone();ByteBuffer b=ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);for(int n=8;n+8<=out.length;){int t=b.getShort(n)&65535,size=b.getInt(n+4);if(t==0x102){int start=n+16+(b.getShort(n+24)&65535),count=b.getShort(n+28)&65535,stride=b.getShort(n+26)&65535;for(int a=0;a<count;a++)if(b.getInt(start+a*stride+4)==attribute)b.putInt(start+a*stride+16,value);}n+=size;}return out;}
 public static void main(String[]args)throws Exception{
  Path d=Files.createTempDirectory("doctor-test-");Path in=d.resolve("input.apk"),out=d.resolve("output.apk");
  try{
   byte[] manifest=GeneralCompatibilityTest.manifest(false);
   apk(in,manifest,Map.of("classes.dex",new byte[]{1},"assets/preserve",new byte[]{2,3}));byte[] original=Files.readAllBytes(in);
   String report=ModernApkDoctor.inspect(in.toFile(),35,new String[]{"arm64-v8a"});
   check(report.contains("doctor also attempts on-screen controls")&&report.contains("No purchase hooks are added by the doctor."));
   check(report.contains("SUPPORTED FIX")&&report.contains("target to API 24")&&report.contains("No lib/ libraries"));
   check(Arrays.equals(original,Files.readAllBytes(in)));check(ManifestPatcher.patch(manifest).newTarget==23);
   var converted=ApkRebuilder.rebuild(in.toFile(),out.toFile(),null,false,false,true,24);check(converted.newTarget==24);
   try(ZipFile z=new ZipFile(out.toFile())){check(ManifestPatcher.patch(z.getInputStream(z.getEntry("AndroidManifest.xml")).readAllBytes(),24).newTarget==24);check(Arrays.equals(z.getInputStream(z.getEntry("assets/preserve")).readAllBytes(),new byte[]{2,3}));}
   byte[] nativeLib=NativeRuntimeRepairTest.fixture(false);
   apk(in,manifest,Map.of("lib/armeabi-v7a/libunknown.so",nativeLib));
   report=ModernApkDoctor.inspect(in.toFile(),34,new String[]{"arm64-v8a"});check(report.contains("no packaged ABI matches")&&report.contains("libunknown.so")&&report.contains("Remaining after supported repairs")&&report.contains("no primary/application DEX"));
   report=ModernApkDoctor.inspect(in.toFile(),34,new String[]{"arm64-v8a","armeabi-v7a"});check(report.contains("A packaged ABI matches"));
   apk(in,sdk(manifest,4,36),Map.of());check(ModernApkDoctor.inspect(in.toFile(),34,new String[]{"arm64-v8a"}).contains("requires a newer Android"));
   apk(in,sdk(manifest,5,35),Map.of());check(ModernApkDoctor.inspect(in.toFile(),35,new String[]{"arm64-v8a"}).contains("no target increase selected"));
   check(ModernApkDoctor.minimumTarget(34)==23&&ModernApkDoctor.minimumTarget(35)==24&&ModernApkDoctor.minimumTarget(36)==24);
   apk(in,manifest,Map.of("classes.dex",new byte[]{1},"classeS.dex",new byte[]{2}));
   byte[] dup=Files.readAllBytes(in),old="classeS.dex".getBytes(java.nio.charset.StandardCharsets.US_ASCII),replacement="classes.dex".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
   for(int at=0;at<=dup.length-old.length;at++){boolean match=true;for(int x=0;x<old.length;x++)if(dup[at+x]!=old[x])match=false;if(match)System.arraycopy(replacement,0,dup,at,replacement.length);}Files.write(in,dup);
   try{ModernApkDoctor.inspect(in.toFile(),35,new String[]{"arm64-v8a"});throw new AssertionError("accepted duplicate entry");}catch(java.io.IOException expected){check(expected.getMessage().contains("duplicate"));}
   apk(in,new byte[]{1,2},Map.of());try{ModernApkDoctor.inspect(in.toFile(),35,new String[]{"arm64-v8a"});throw new AssertionError("accepted malformed manifest");}catch(java.io.IOException expected){}
   System.out.println("Doctor API floors, device ABI/minSdk warnings, native residual warnings, immutable scan and conversion preservation passed");
  }finally{Files.deleteIfExists(in);Files.deleteIfExists(out);Files.delete(d);}
 }
}
