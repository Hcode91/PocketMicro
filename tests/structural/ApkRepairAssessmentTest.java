package com.hcode91.pocketmicro;
import java.nio.file.*;import java.util.*;import java.util.zip.*;
public final class ApkRepairAssessmentTest {
 static void check(boolean b){if(!b)throw new AssertionError("APK assessment regression");}
 static byte[] dex()throws Exception{return GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls("Lexample/Game;","Ljava/lang/Object;",List.of()));}
 static void apk(Path out,Map<String,byte[]> files)throws Exception{ExperimentalUnityRuntimeRepairTest.archive(out,files);}
 public static void main(String[]args)throws Exception{
  Path dir=Files.createTempDirectory("apk-assessment-");Path in=dir.resolve("original.apk");
  try{
   Map<String,byte[]> files=new LinkedHashMap<>();files.put("AndroidManifest.xml",GeneralCompatibilityTest.manifest(false));files.put("classes.dex",dex());apk(in,files);byte[] original=Files.readAllBytes(in);
   var result=ApkRepairAssessment.inspect(in.toFile(),34,new String[]{"armeabi-v7a"});check(result.suggested==ApkRepairAssessment.Route.STANDARD&&result.report.contains("API 23")&&Arrays.equals(original,Files.readAllBytes(in)));
   check(ApkRepairAssessment.inspect(in.toFile(),35,new String[]{"armeabi-v7a"}).report.contains("API 24"));
   files.put("lib/armeabi-v7a/libUnseen.so",SharedCompatibilityRulesTest.elf(0));apk(in,files);result=ApkRepairAssessment.inspect(in.toFile(),34,new String[]{"armeabi-v7a"});check(result.suggested==ApkRepairAssessment.Route.STARTUP&&result.report.contains(WritableRelocationRule.ID));
   check(ApkRepairAssessment.inspect(in.toFile(),34,new String[]{"arm64-v8a"}).suggested==ApkRepairAssessment.Route.NONE);
   files.remove("lib/armeabi-v7a/libUnseen.so");files.put("classes.dex",SharedCompatibilityRulesTest.dex(0,false));apk(in,files);check(ApkRepairAssessment.inspect(in.toFile(),34,new String[]{"armeabi-v7a"}).suggested==ApkRepairAssessment.Route.STARTUP);
   files.put("classes.dex",dex());files.put("lib/armeabi-v7a/libmono.so",ExperimentalUnityRuntimeRepairTest.fixture(28,"libmono_proxy.so",false,false));files.put("lib/armeabi-v7a/libunity.so",ExperimentalUnityRuntimeRepairTest.fixture(28,"libunity_proxy.so",false,false));files.put("assets/libs/armeabi-v7a/libmono.so",ExperimentalUnityRuntimeRepairTest.fixture(28,"libmono.so",true,false));files.put("assets/libs/armeabi-v7a/libunity.so",ExperimentalUnityRuntimeRepairTest.fixture(28,"libunity.so",false,false));apk(in,files);original=Files.readAllBytes(in);
   try(ZipFile z=new ZipFile(in.toFile())){var preview=ExperimentalUnityRuntimeRepair.inspect(z);check(preview.applicable&&preview.replacements.isEmpty());}
   check(ApkRepairAssessment.inspect(in.toFile(),34,new String[]{"armeabi-v7a"}).suggested==ApkRepairAssessment.Route.EXPERIMENTAL);check(Arrays.equals(original,Files.readAllBytes(in)));
   Path converted=dir.resolve("converted.apk");ApkRebuilder.rebuildExperimental(in.toFile(),converted.toFile(),24);try(ZipFile z=new ZipFile(converted.toFile())){check(ManifestPatcher.patch(z.getInputStream(z.getEntry("AndroidManifest.xml")).readAllBytes(),24).newTarget==24);}Files.delete(converted);
   check(ModernApkDoctor.inspect(in.toFile(),34,new String[]{"armeabi-v7a"}).contains("This doctor\'s Prepare fixes does not enable the experiment"));
   files.put("assets/libs/armeabi-v7a/libunity.so",ExperimentalUnityRuntimeRepairTest.fixture(28,"libunity.so",false,true));apk(in,files);result=ApkRepairAssessment.inspect(in.toFile(),34,new String[]{"armeabi-v7a"});check(result.suggested!=ApkRepairAssessment.Route.EXPERIMENTAL&&result.report.contains("Thumb"));
   files.remove("assets/libs/armeabi-v7a/libmono.so");apk(in,files);check(ApkRepairAssessment.inspect(in.toFile(),34,new String[]{"armeabi-v7a"}).suggested!=ApkRepairAssessment.Route.EXPERIMENTAL);
   files.clear();files.put("AndroidManifest.xml",ModernApkDoctorTest.sdk(GeneralCompatibilityTest.manifest(false),4,36));files.put("classes.dex",dex());apk(in,files);check(ApkRepairAssessment.inspect(in.toFile(),34,new String[]{"armeabi-v7a"}).suggested==ApkRepairAssessment.Route.NONE);
   files.put("AndroidManifest.xml",GeneralCompatibilityTest.manifest(false));files.put("classes.dex",new byte[]{1});apk(in,files);check(ApkRepairAssessment.inspect(in.toFile(),34,new String[]{"armeabi-v7a"}).suggested==ApkRepairAssessment.Route.NONE);
   files.remove("classes.dex");apk(in,files);check(ApkRepairAssessment.inspect(in.toFile(),34,new String[]{"armeabi-v7a"}).suggested==ApkRepairAssessment.Route.NONE);
   files.put("AndroidManifest.xml",new byte[]{1,2});apk(in,files);try{ApkRepairAssessment.inspect(in.toFile(),34,new String[]{"armeabi-v7a"});throw new AssertionError("malformed manifest accepted");}catch(java.io.IOException expected){}
   byte[] cpu=UnityCpuStringRuleTest.fixture(0,false),copy=cpu.clone();var preview=UnityCpuStringRule.inspect(Map.of("classes.dex",cpu));check(preview.applicable&&preview.replacements.isEmpty()&&Arrays.equals(cpu,copy));
  }finally{Files.deleteIfExists(in);Files.delete(dir);}
  System.out.println("APK assessment: standard/startup/experimental selection, ABI/minSdk blocks, read-only preview, API floors and uncertain/Thumb/missing/malformed rejection pass");
 }
}
