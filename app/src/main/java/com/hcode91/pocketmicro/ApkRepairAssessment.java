package com.hcode91.pocketmicro;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Static pre-install assessment. Never installs, executes game code, or edits the input APK. */
public final class ApkRepairAssessment {
    public enum Route {STANDARD, STARTUP, EXPERIMENTAL, NONE}
    public static final class Finding {
        public final CompatibilityEvidence.Status status;
        public final String rule,detail;
        Finding(CompatibilityEvidence.Status status,String rule,String detail){this.status=status;this.rule=rule;this.detail=detail;}
    }
    public static final class Result {
        public final String packageName,report;
        public final Route suggested;
        public final List<Finding> findings;
        Result(String pkg,Route route,List<Finding> findings){
            packageName=pkg;suggested=route;this.findings=Collections.unmodifiableList(new ArrayList<>(findings));
            StringBuilder out=new StringBuilder("Package: ").append(pkg).append("\n\n");
            switch(route){
                case STARTUP:out.append("Suggested: Startup repairs. Matching supported repair evidence was found.\n");break;
                case EXPERIMENTAL:out.append("Experimental candidate: Unity embedded-runtime repair. This requires a separate confirmation; startup and stability are unverified.\n");break;
                case STANDARD:out.append("Suggested: Standard conversion. No supported startup repair matched the checks performed. This is not a successful-launch prediction.\n");break;
                default:out.append("No conversion fix is recommended for the detected device or package limitation.\n");
            }
            for(Finding f:findings){if(out.length()>55000){out.append("\nSKIPPED: additional findings exceed the preview limit.\n");break;}out.append('\n').append(f.status).append(" — ").append(f.rule).append(": ").append(f.detail).append('\n');}
            out.append("\nStatic checks cannot establish the cause of every crash, missing service, input problem or loading stall. Missing evidence is not a clean bill of health. Eligibility is checked again during conversion. Touch/purchase choices remain separate; no APK changes or installation occur during assessment.");
            report=out.toString();
        }
    }
    private static void add(List<Finding> f,CompatibilityEvidence.Status s,String rule,String detail){f.add(new Finding(s,rule,detail));}
    public static Result inspect(File apk,int androidApi,String[] deviceAbis)throws IOException{
        List<Finding> findings=new ArrayList<>();boolean blocker=false,startup=false,experimental=false,dexSkipped=false;
        Map<String,byte[]> dex=new LinkedHashMap<>();Set<String> names=new HashSet<>(),abis=new TreeSet<>();
        String pkg;long nativeBudget=64L*1024*1024,dexBudget=64L*1024*1024;int nativeCount=0;
        try(ZipFile zip=new ZipFile(apk)){
            for(Enumeration<? extends ZipEntry> it=zip.entries();it.hasMoreElements();){
                ZipEntry e=it.nextElement();String n=e.getName();if(names.size()>=50000||n.length()>2048)throw new IOException("APK structure exceeds assessment limits");if(!names.add(n))throw new IOException("Duplicate APK entry: ambiguous input");
                if(n.matches("lib/[^/]+/[^/]+\\.so"))abis.add(n.split("/")[1]);
            }
            ZipEntry manifest=zip.getEntry("AndroidManifest.xml");if(manifest==null)throw new IOException("APK manifest is missing; extract the original APK from its archive first");
            ManifestPatcher.Result m;
            try{m=ManifestPatcher.patch(ApkRebuilder.readBounded(zip.getInputStream(manifest),4*1024*1024),ModernApkDoctor.minimumTarget(androidApi));}catch(RuntimeException bad){throw new IOException("Malformed or unsupported APK manifest",bad);}
            pkg=m.packageName;
            if(m.minSdk>androidApi){blocker=true;add(findings,CompatibilityEvidence.Status.UNSUPPORTED,"Android version","Requires API "+m.minSdk+"; phone is API "+androidApi+". Target conversion cannot supply newer APIs.");}
            if(!abis.isEmpty()&&Collections.disjoint(abis,Arrays.asList(deviceAbis))){blocker=true;add(findings,CompatibilityEvidence.Status.UNSUPPORTED,"CPU architecture","APK ABIs "+abis+" do not match phone ABIs "+Arrays.toString(deviceAbis)+". Native repair does not translate the CPU.");}
            if(m.changed)add(findings,CompatibilityEvidence.Status.SUPPORTED,"Install target","Manifest target "+m.oldTarget+" can be raised to API "+m.newTarget+". This may expose further runtime differences.");
            else add(findings,CompatibilityEvidence.Status.SKIPPED,"Install target","No target increase required by this converter's policy.");
            if(androidApi>36)add(findings,CompatibilityEvidence.Status.UNKNOWN,"Android policy","Installation changes after API 36 are not modeled.");
            if(!names.contains("classes.dex")&&!abis.isEmpty())add(findings,CompatibilityEvidence.Status.UNKNOWN,"Package completeness","No primary DEX; may be native-only or an incomplete split. Split-set installation is unsupported.");
            if(!names.contains("classes.dex")&&abis.isEmpty()){blocker=true;add(findings,CompatibilityEvidence.Status.UNSUPPORTED,"Package completeness","No application DEX or packaged native libraries; a resource-only split cannot be converted to a complete game.");}
            if(names.contains("assets/game.droid")&&(names.contains("lib/armeabi-v7a/libyoyo.so")||names.contains("lib/armeabi/libyoyo.so")))add(findings,CompatibilityEvidence.Status.UNKNOWN,"GameMaker runtime","32-bit GameMaker structure detected. PocketMicro's separate GameMaker engine may be tested after import; this does not prove it can run this game.");
            try{
                NativeRuntimeRepair.Plan legacy=NativeRuntimeRepair.plan(zip);
                if(!legacy.replacements.isEmpty()){startup=true;add(findings,CompatibilityEvidence.Status.SUPPORTED,"Exact native repair",legacy.replacements.size()+" runtime replacement(s) matched the existing catalog.\n"+legacy.report);}
                else add(findings,CompatibilityEvidence.Status.SKIPPED,"Exact native repair","No new exact-catalog runtime repair applies. Already repaired or unknown builds may remain.");
            }catch(IOException|RuntimeException bad){add(findings,CompatibilityEvidence.Status.UNKNOWN,"Exact native repair","Inspection failed: "+bad.getClass().getSimpleName()+". No match is assumed.");}
            for(Enumeration<? extends ZipEntry> it=zip.entries();it.hasMoreElements();){
                ZipEntry e=it.nextElement();String n=e.getName();
                if(n.matches("lib/[^/]+/[^/]+\\.so")){
                    long size=e.getSize();if(++nativeCount>128||size<0||size>24L*1024*1024||size>nativeBudget){if(nativeCount==129||nativeCount<=128)add(findings,CompatibilityEvidence.Status.SKIPPED,"Native inspection",n+": size/count/budget limit");continue;}
                    nativeBudget-=size;byte[] bytes=ApkRebuilder.readBounded(zip.getInputStream(e),24*1024*1024);
                    var dependency=NdkDependencyPathRule.inspect(bytes);if(dependency.status!=CompatibilityEvidence.Status.SKIPPED)add(findings,dependency.status,NdkDependencyPathRule.ID,n+": "+dependency.reason);if(dependency.status==CompatibilityEvidence.Status.SUPPORTED)startup=true;
                    var r=WritableRelocationRule.inspect(bytes);
                    if(r.status==CompatibilityEvidence.Status.SUPPORTED){startup=true;add(findings,r.status,WritableRelocationRule.ID,n+": "+r.reason);}
                    else if(r.status!=CompatibilityEvidence.Status.SKIPPED)add(findings,r.status,WritableRelocationRule.ID,n+": "+r.reason);
                }
                if(n.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")){
                    long size=e.getSize();if(dex.size()>=64||size<0||size>32L*1024*1024||size>dexBudget){dexSkipped=true;continue;}
                    byte[] bytes=ApkRebuilder.readBounded(zip.getInputStream(e),32*1024*1024);dexBudget-=bytes.length;dex.put(n,bytes);
                }
            }
            if(dexSkipped)add(findings,CompatibilityEvidence.Status.SKIPPED,"SDK inspection","DEX budget exceeded; no SDK match is assumed.");
            else{
                var sdk=OuyaServiceCleanupRule.inspect(dex);add(findings,sdk.status,OuyaServiceCleanupRule.ID,sdk.reason);if(sdk.status==CompatibilityEvidence.Status.SUPPORTED)startup=true;if(sdk.status==CompatibilityEvidence.Status.MALFORMED)blocker=true;
                var platform=LegacySdkCrashRules.inspect(dex);for(var finding:platform.findings){if(finding.status!=CompatibilityEvidence.Status.SKIPPED)add(findings,finding.status,finding.id,finding.detail);if(finding.status==CompatibilityEvidence.Status.MALFORMED)blocker=true;}if(platform.supported())startup=true;
                var air=AirLoaderDiagnosticsRule.inspect(dex);
                if(air.status!=CompatibilityEvidence.Status.SKIPPED)add(findings,air.status,AirLoaderDiagnosticsRule.ID,air.reason+". Startup repairs can enable this diagnostic; it does not establish repair eligibility or resolve the hidden failure. Export the full global log after testing.");
                var cpu=UnityCpuStringRule.inspect(dex);if(cpu.applicable)add(findings,CompatibilityEvidence.Status.SUPPORTED,UnityCpuStringRule.ID,"Matching nullable description getter; current crash cause is unverified. Guard is available only with an eligible experimental runtime conversion.");
                else if(cpu.report.startsWith("UNSUPPORTED"))add(findings,CompatibilityEvidence.Status.UNKNOWN,UnityCpuStringRule.ID,cpu.report);
            }
            var candidate=ExperimentalUnityRuntimeRepair.inspect(zip);experimental=candidate.applicable;
            if(experimental)add(findings,CompatibilityEvidence.Status.SUPPORTED,"Experimental Unity candidate",candidate.report);
            else if(names.contains("assets/libs/armeabi-v7a/libunity.so")||names.contains("assets/libs/armeabi-v7a/libmono.so"))add(findings,CompatibilityEvidence.Status.UNKNOWN,"Experimental Unity candidate",candidate.report);
        }
        return new Result(pkg,blocker?Route.NONE:startup?Route.STARTUP:experimental?Route.EXPERIMENTAL:Route.STANDARD,findings);
    }
    private ApkRepairAssessment(){}
}
