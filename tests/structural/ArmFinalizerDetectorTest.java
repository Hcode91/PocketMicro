package io.github.hozdogus.pocketouya;
import java.util.*;
import java.nio.file.*;
import org.json.*;
import static io.github.hozdogus.pocketouya.CompatibilityEvidence.Status.*;
public final class ArmFinalizerDetectorTest {
 static int tp,tn,fp,fn;static JSONArray cases=new JSONArray();
 static void check(boolean b){if(!b)throw new AssertionError("Structural detector regression");}
 static CompatibilityEvidence run(String id,byte[] b,boolean positive){byte[] before=b.clone();CompatibilityEvidence r=ArmFinalizerDetector.inspect(b);check(Arrays.equals(before,b));check(!r.transformationAuthorized);boolean accepted=r.status==SUPPORTED;if(positive){if(accepted)tp++;else fn++;}else{if(accepted)fp++;else tn++;}cases.put(new JSONObject().put("case",id).put("expected_supported",positive).put("actual",r.status.name()).put("reason",r.findings.get(r.findings.size()-1).code));check(positive==accepted);return r;}
 public static void main(String[]args)throws Exception{
  for(int shift:new int[]{0,28,84,128}){
   for(String mode:new String[]{"none","typed","mapped"}){boolean thunk=!mode.equals("none");StructuralFixture f=new StructuralFixture(shift,thunk,mode);var r=run("layout-"+shift+"-"+mode,f.bytes,true);var c=r.candidates.get(0);check(c.finalizer==f.f&&c.target==f.target&&c.thunk==(thunk?f.t:-1)&&c.atexitPlt==(thunk?f.plt+12:-1));}
  }
  StructuralFixture f=new StructuralFixture(0,true,"stripped");check(run("stripped-thumb",f.bytes,false).status==UNKNOWN);
  f=new StructuralFixture(0,true,"conflict");check(run("contradictory-mode",f.bytes,false).status==UNKNOWN);
  f=new StructuralFixture(0,false,"none");f.b.putInt(f.f+8,0x1a000000);run("conditional-tail",f.bytes,false);
  f=new StructuralFixture(0,false,"none");f.b.putInt(f.f+4,0xe5901000);run("different-register",f.bytes,false);
  f=new StructuralFixture(0,false,"none");f.b.putInt(f.jmp+4,(2<<8)|22);run("wrong-symbol",f.bytes,false);
  f=new StructuralFixture(0,false,"none");f.b.putInt(f.fini,0x600);run("unrooted-byte-pattern",f.bytes,false);
  f=new StructuralFixture(0,false,"none");f.b.putInt(f.f+12,0x100);run("target-in-code",f.bytes,false);
  f=new StructuralFixture(0,true,"typed");f.b.putInt(f.t+16,1);run("wrong-literal",f.bytes,false);
  f=new StructuralFixture(0,true,"typed");f.b.putInt(0x504,1);run("wrong-unwind",f.bytes,false);
  f=new StructuralFixture(0,false,"none");f.b.putInt(f.fini+4,f.f+12);f.b.putInt(0x800+10*8+4,8);run("missing-root-relocation",f.bytes,false);
  f=new StructuralFixture(0,false,"none");f.b.putInt(0x1700+3*40+36,8);run("symbol-stride",f.bytes,false);
  f=new StructuralFixture(0,true,"typed");f.b.putShort(f.ds+48+14,(short)0);run("undefined-thumb-symbol",f.bytes,false);
  f=new StructuralFixture(0,true,"mapped");f.b.putInt(f.ds+64+4,f.t+18);run("literal-code-overlap",f.bytes,false);
  f=new StructuralFixture(0,false,"none");f.b.putInt(0x1700+2*40+16,0x700);run("inconsistent-plt-section",f.bytes,false);
  f=new StructuralFixture(0,false,"none");f.b.putInt(f.rel+8,f.f+12);run("duplicate-relocation",f.bytes,false);
  f=new StructuralFixture(0,false,"none");f.b.putInt(f.jmp+4,(10000<<8)|22);run("symbol-index-overflow",f.bytes,false);
  f=new StructuralFixture(0,false,"none");f.b.putInt(116+16,0x100000);check(run("bad-dynamic-range",f.bytes,false).status==MALFORMED);
  for(int length:new int[]{0,8,32,51,100,1000,5000}){f=new StructuralFixture(0,false,"none");check(run("truncated-"+length,Arrays.copyOf(f.bytes,length),false).status==MALFORMED);}
  f=new StructuralFixture(0,false,"none");f.b.putShort(18,(short)183);check(run("different-cpu",f.bytes,false).status==UNSUPPORTED);
  f=new StructuralFixture(0,false,"none");f.b.putInt(32,0).putShort(48,(short)0);check(run("missing-sections",f.bytes,false).status==UNKNOWN);
  f=new StructuralFixture(0,false,"none");f.b.put(6,(byte)0);run("bad-elf-version",f.bytes,false);
  f=new StructuralFixture(0,false,"none");f.b.putShort(40,(short)0);run("bad-header-size",f.bytes,false);
  f=new StructuralFixture(0,false,"none");f.b.putShort(50,(short)0);check(run("unnamed-sections",f.bytes,false).status==UNKNOWN);
  f=new StructuralFixture(0,false,"none");f.b.putInt(f.rel+8,f.got).putInt(f.rel+12,(1<<8)|22);check(run("overlapping-relocation-targets",f.bytes,false).status==UNKNOWN);
  check(run("over-budget",new byte[24*1024*1024+1],false).status==SKIPPED);
  ExperimentalUnityRuntimeRepairTest.main(new String[0]);
  JSONObject result=new JSONObject().put("suite","generated structural layouts").put("true_positive",tp).put("true_negative",tn).put("false_positive",fp).put("false_negative",fn).put("cases",cases);
  if(args.length>0)Files.writeString(Path.of(args[0]),result.toString(2));System.out.println(result.toString());
 }
}
