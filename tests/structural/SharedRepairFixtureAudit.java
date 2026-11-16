package com.hcode91.pocketmicro;
import java.nio.file.*;import java.util.*;import java.util.zip.*;import org.json.*;
/** Regression oracle is used only for byte-output comparison, never to classify a new rule. */
public final class SharedRepairFixtureAudit {
 public static void main(String[]args)throws Exception{
  JSONObject matrix=new JSONObject(Files.readString(Path.of(args[0]))),index=new JSONObject(Files.readString(Path.of(args[1])));JSONArray results=new JSONArray();int verified=0,skipped=0;
  for(Object value:matrix.getJSONArray("profiles")){
   JSONObject row=(JSONObject)value;String id=row.getString("id"),file=index.optString(id,"");JSONObject result=new JSONObject().put("id",id);results.put(result);
   if(file.isEmpty()||!Files.isRegularFile(Path.of(file))){result.put("status","SKIPPED").put("reason","FIXTURE_UNAVAILABLE");skipped++;continue;}
   byte[] source=Files.readAllBytes(Path.of(file));if(!ArmRuntimePicRepair.sha(source).equals(row.getString("source_sha256")))throw new AssertionError("Wrong fixture: "+id);
   Path temp=Files.createTempFile("shared-baseline-", ".zip");String name="lib/armeabi-v7a/"+row.getString("library");
   try{
    try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(temp))){z.putNextEntry(new ZipEntry(name));z.write(source);z.closeEntry();}
    try(ZipFile z=new ZipFile(temp.toFile())){
     NativeRuntimeRepair.Plan old=NativeRuntimeRepair.plan(z);SharedCompatibilityRepairs.Plan shared=SharedCompatibilityRepairs.plan(z,true);
     if(!old.replacements.keySet().equals(shared.replacements.keySet()))throw new AssertionError("Replacement scope changed: "+id);
     for(String key:old.replacements.keySet())if(!Arrays.equals(old.replacements.get(key),shared.replacements.get(key)))throw new AssertionError("Existing output changed: "+id);
     if(!ArmRuntimePicRepair.sha(shared.replacements.get(name)).equals(row.getString("output_sha256")))throw new AssertionError("Expected output hash changed: "+id);
    }
   }finally{Files.delete(temp);}
   result.put("status","VERIFIED").put("scope","shared coordinator output and replacement set equal existing native plan and frozen output hash");verified++;
  }
  JSONObject report=new JSONObject().put("verified",verified).put("skipped",skipped).put("complete",skipped==0).put("profiles",results);
  Files.writeString(Path.of(args[2]),report.toString(2)+"\n");System.out.println("Shared coordinator replay: "+verified+" verified, "+skipped+" skipped");
  if(skipped!=0)throw new AssertionError("INCOMPLETE: unavailable fixtures are skipped, not passing");
 }
}
