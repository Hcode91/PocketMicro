package com.hcode91.pocketmicro;
import java.nio.file.*;import java.util.*;import java.util.zip.*;import java.io.*;import org.json.*;
/** Offline evidence runner. Fixture paths come from a private index, never the detector. */
public final class NativeProfileMatrixAudit {
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 static JSONObject detection(CompatibilityEvidence result){
  JSONArray findings=new JSONArray(),candidates=new JSONArray();
  for(var f:result.findings)findings.put(new JSONObject().put("status",f.status.name()).put("code",f.code).put("scope",f.scope).put("detail",f.detail).put("address",f.address));
  for(var c:result.candidates)candidates.put(new JSONObject().put("finalizer",c.finalizer).put("target",c.target).put("thunk",c.thunk).put("atexit_plt",c.atexitPlt));
  return new JSONObject().put("status",result.status.name()).put("transformation_authorized",result.transformationAuthorized).put("findings",findings).put("candidates",candidates);
 }
 static NativeRuntimeRepair.Plan plan(byte[] bytes,String name)throws Exception{
  Path p=Files.createTempFile("native-baseline-", ".zip");
  try{
   try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(p))){ZipEntry e=new ZipEntry("lib/armeabi-v7a/"+name);CRC32 crc=new CRC32();crc.update(bytes);e.setMethod(ZipEntry.STORED);e.setSize(bytes.length);e.setCrc(crc.getValue());z.putNextEntry(e);z.write(bytes);z.closeEntry();}
   try(ZipFile z=new ZipFile(p.toFile())){return NativeRuntimeRepair.plan(z);}
  }finally{Files.deleteIfExists(p);}
 }
 public static void main(String[]args)throws Exception{
  if(args.length!=3)throw new IllegalArgumentException("matrix.json private-fixture-index.json results.json");
  JSONObject matrix=new JSONObject(Files.readString(Path.of(args[0])));JSONArray rows=matrix.getJSONArray("profiles");
  Path repository=Path.of(args[0]).toAbsolutePath().getParent().getParent();JSONObject immutable=matrix.getJSONObject("transformation_source_sha256");
  for(String source:immutable.keySet())check(ArmRuntimePicRepair.sha(Files.readAllBytes(repository.resolve(source))).equals(immutable.getString(source)),"Transformation source changed: "+source);
  JSONObject files=new JSONObject(Files.readString(Path.of(args[1])));JSONArray results=new JSONArray();
  var field=NativeRuntimeRepair.class.getDeclaredField("CATALOG");field.setAccessible(true);
  @SuppressWarnings("unchecked") List<NativeRuntimeRepair.Profile> catalog=(List<NativeRuntimeRepair.Profile>)field.get(null);
  check(rows.length()==17&&catalog.size()==17,"Frozen profile count changed");int missing=0,failed=0,verified=0,tp=0,tn=0,fp=0,fn=0,positiveEvaluated=0,parameters=0;
  for(int i=0;i<rows.length();i++){
   JSONObject row=rows.getJSONObject(i);String id=row.getString("id");NativeRuntimeRepair.Profile p=catalog.get(i);
   check(p.name.equals(row.getString("library"))&&p.sourceHash.equals(row.getString("source_sha256"))&&p.outputHash.equals(row.getString("output_sha256")),"Catalog differs from frozen matrix: "+id);
   JSONObject record=new JSONObject().put("id",id).put("library",p.name).put("family",row.getString("family"));results.put(record);
   String path=files.optString(id,"");
   if(path.isEmpty()||!Files.isRegularFile(Path.of(path))){record.put("baseline_status","SKIPPED").put("reason","FIXTURE_UNAVAILABLE").put("detector_status","SKIPPED");missing++;continue;}
   try {
   byte[] source=Files.readAllBytes(Path.of(path));check(ArmRuntimePicRepair.sha(source).equals(p.sourceHash),"Wrong fixture identity: "+id);
   byte[] before=source.clone();CompatibilityEvidence detector=ArmFinalizerDetector.inspect(source);
   check(Arrays.equals(before,source),"Detector mutated input: "+id);record.put("detector",detection(detector));
   boolean positive=row.getString("family").equals("initializer_atexit"),matched=detector.status==CompatibilityEvidence.Status.SUPPORTED;
   if(positive){positiveEvaluated++;if(matched)tp++;else fn++;
    if(!detector.candidates.isEmpty()){
     JSONArray expected=row.getJSONArray("expected_parameters");var c=detector.candidates.get(0);
     check(c.finalizer==expected.getLong(0)&&c.target==expected.getLong(1)&&c.thunk==expected.getLong(2)&&(c.thunk<0?c.atexitPlt==-1:c.atexitPlt==expected.getLong(3)),"Derived parameters disagree: "+id);parameters++;record.put("derived_parameters_match",true);
    }
   }else{if(matched)fp++;else tn++;}
   byte[] output=p.repair.apply(source);
   check(Arrays.equals(source,before),"Existing transformer mutated input: "+id);
   check(ArmRuntimePicRepair.sha(output).equals(p.outputHash),"Baseline output changed: "+id);
   NativeRuntimeRepair.Plan first=plan(source,p.name);check(Arrays.equals(output,first.replacements.get("lib/armeabi-v7a/"+p.name)),"Catalog selection changed: "+id);
   check(plan(output,p.name).replacements.isEmpty(),"Output not recognized as repaired: "+id);
   record.put("baseline_status","VERIFIED").put("verification_scope","existing output SHA-256, immutable input, catalog selection and idempotence; no Android launch");verified++;
   }catch(Exception|AssertionError error){record.put("baseline_status","UNKNOWN").put("reason","AUDIT_VERIFICATION_FAILED").put("detail",error.toString());failed++;}
  }
  JSONObject report=new JSONObject().put("schema",1).put("all_fixtures_evaluated",missing==0).put("profiles",results)
   .put("audit_failures",failed).put("baseline_verified",verified).put("fixture_unavailable",missing).put("initializer_family_evaluated",positiveEvaluated).put("derived_parameter_matches",parameters)
   .put("structural_true_positive",tp).put("structural_true_negative",tn).put("structural_false_positive",fp).put("structural_false_negative_including_conservative_unknown",fn)
   .put("scope","Read-only local-pattern recognition; no transformation integration or whole-program safety proof");
  Files.writeString(Path.of(args[2]),report.toString(2)+"\n");System.out.println(report.toString());
  check(failed==0,"Baseline/parameter verification failures recorded in report");
  check(fp==0,"Structural false positive observed");
  check(missing==0,"INCOMPLETE: missing fixtures recorded as SKIPPED, not passing");
 }
}
