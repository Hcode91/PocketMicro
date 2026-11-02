package io.github.hozdogus.pocketouya;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.zip.*;
public final class ExperimentalUnityRuntimeRepairTest {
 static void check(boolean b){if(!b)throw new AssertionError("Experimental runtime regression");}
 static byte[] fixture(int shift,String name,boolean clean,boolean thumb){
  StructuralFixture f=new StructuralFixture(shift,thumb,thumb?"typed":"none");int len=f.b.getInt(0x804+3*8);byte[] n=(name+"\0").getBytes(StandardCharsets.US_ASCII);System.arraycopy(n,0,f.bytes,f.str+len,n.length);
  f.b.putInt(0x804+3*8,len+n.length).putInt(0x1700+4*40+20,len+n.length);
  f.b.putInt(0x800+12*8,14).putInt(0x804+12*8,len).putInt(0x800+13*8,0x6ffffffa).putInt(0x804+13*8,2).putInt(0x800+14*8,20).putInt(0x804+14*8,17).putInt(0x800+15*8,0);
  if(clean){f.b.putInt(f.rel,f.target);f.b.putInt(0x800+11*8,30);}
  return f.bytes;
 }
 static void archive(Path p,Map<String,byte[]> files)throws Exception{try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(p))){for(var e:files.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}}}
 public static void main(String[]args)throws Exception{
  Path dir=Files.createTempDirectory("unity-experiment-");try{Path in=dir.resolve("in.apk"),out=dir.resolve("out.apk");
   for(int shift:new int[]{0,28,84}){
    Map<String,byte[]> files=new LinkedHashMap<>();files.put("AndroidManifest.xml",GeneralCompatibilityTest.manifest(false));files.put("classes.dex",new byte[]{1,2,3});files.put("assets/game-data",new byte[]{7,8});
    files.put("lib/armeabi-v7a/libmono.so",fixture(shift,"libmono_proxy.so",false,false));files.put("lib/armeabi-v7a/libunity.so",fixture(shift,"libunity_proxy.so",false,false));
    files.put("assets/libs/armeabi-v7a/libmono.so",fixture(shift,"libmono.so",true,false));files.put("assets/libs/armeabi-v7a/libunity.so",fixture(shift,"libunity.so",false,false));archive(in,files);byte[] original=Files.readAllBytes(in);
    try(ZipFile z=new ZipFile(in.toFile())){var p=ExperimentalUnityRuntimeRepair.plan(z);check(p.replacements.size()==2);check(Arrays.equals(p.replacements.get("lib/armeabi-v7a/libmono.so"),files.get("assets/libs/armeabi-v7a/libmono.so")));check(!NativeStartupChecks.hasTextRelocations(p.replacements.get("lib/armeabi-v7a/libunity.so")));}
    ApkRebuilder.rebuildExperimental(in.toFile(),out.toFile());check(Arrays.equals(original,Files.readAllBytes(in)));try(ZipFile z=new ZipFile(out.toFile())){for(String name:files.keySet())if(!name.equals("AndroidManifest.xml")&&!name.startsWith("lib/"))check(Arrays.equals(files.get(name),z.getInputStream(z.getEntry(name)).readAllBytes()));check(ExperimentalUnityRuntimeRepair.plan(z).replacements.isEmpty());}
    ApkRebuilder.rebuild(in.toFile(),out.toFile());try(ZipFile z=new ZipFile(out.toFile())){check(Arrays.equals(files.get("lib/armeabi-v7a/libunity.so"),z.getInputStream(z.getEntry("lib/armeabi-v7a/libunity.so")).readAllBytes()));}
    files.put("assets/libs/armeabi-v7a/libunity.so",fixture(shift,"libunity.so",false,true));archive(in,files);try(ZipFile z=new ZipFile(in.toFile())){check(ExperimentalUnityRuntimeRepair.plan(z).replacements.isEmpty());}
    files.remove("assets/libs/armeabi-v7a/libmono.so");archive(in,files);try(ZipFile z=new ZipFile(in.toFile())){check(ExperimentalUnityRuntimeRepair.plan(z).replacements.isEmpty());}
    files.put("assets/libs/armeabi-v7a/libmono.so",new byte[]{1});archive(in,files);try(ZipFile z=new ZipFile(in.toFile())){check(ExperimentalUnityRuntimeRepair.plan(z).replacements.isEmpty());}
   }
  }finally{try(var stream=Files.list(dir)){for(Path p:stream.toArray(Path[]::new))Files.delete(p);}Files.delete(dir);}
  System.out.println("Experimental Unity: shifted layouts, real-runtime promotion, native verification, immutable assets/DEX, disabled-path isolation, repeat edits, missing/malformed and Thumb rejection pass");
 }
}
