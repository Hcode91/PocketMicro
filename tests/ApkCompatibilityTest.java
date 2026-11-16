package com.hcode91.pocketmicro;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
public class ApkCompatibilityTest {
 static File apk(Path dir,String name,String...entries)throws Exception{
  Path file=dir.resolve(name);try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(file))){
   for(String entry:entries){z.putNextEntry(new ZipEntry(entry));
    if(entry.equals("classes.dex")){z.write(new byte[8180]);z.write("Ltv/ouya/console/api/OuyaEncryptionHelper; requestReceipts".getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
    z.closeEntry();
   }
  }return file.toFile();
 }
 static void check(boolean value){if(!value)throw new AssertionError("compatibility regression");}
 public static void main(String[] args)throws Exception{
  Path dir=Files.createTempDirectory("pocketouya-apk-test");
  try{
   var gm=ApkCompatibility.inspect(apk(dir,"gm.apk","assets/game.droid","lib/armeabi-v7a/libyoyo.so","classes.dex"));
   check(gm.canRunEngine);check(gm.report.contains("OuyaEncryptionHelper"));check(gm.report.contains("requestReceipts"));
   var unity=ApkCompatibility.inspect(apk(dir,"unity.apk","lib/armeabi-v7a/libunity.so","lib/armeabi-v7a/libmono.so","assets/bin/Data/Managed/Assembly-CSharp.dll"));check(!unity.canRunEngine);check(unity.report.contains("Unity"));check(unity.report.contains("Mono"));check(unity.report.contains("Assembly-CSharp.dll"));
   var air=ApkCompatibility.inspect(apk(dir,"air.apk","assets/META-INF/AIR/application.xml"));check(!air.canRunEngine);check(air.report.contains("Adobe AIR"));
   check(!ApkCompatibility.inspect(apk(dir,"64.apk","assets/game.droid","lib/arm64-v8a/libyoyo.so")).canRunEngine);
   check(!ApkCompatibility.inspect(apk(dir,"missing.apk","lib/armeabi/libyoyo.so")).canRunEngine);
   System.out.println("APK engine and purchase-marker inspection tests pass");
  }finally{try(var files=Files.list(dir)){for(Path file:files.toArray(Path[]::new))Files.delete(file);}Files.delete(dir);}
 }
}
