package io.github.hozdogus.pocketouya;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Rule coordinator for explicit startup repair/doctor requests. No structural finalizer integration. */
public final class SharedCompatibilityRepairs {
    public static final class Plan {
        public final Map<String,byte[]> replacements=new LinkedHashMap<>();
        public String report;
    }
    public static Plan plan(ZipFile zip,boolean apply)throws IOException {
        Plan plan=new Plan();NativeRuntimeRepair.Plan existing=NativeRuntimeRepair.plan(zip);
        if(apply)plan.replacements.putAll(existing.replacements);
        StringBuilder report=new StringBuilder(existing.report).append("\n\nShared compatibility rules\n");
        UnityEmbeddedRuntimePromotion.Result unity=UnityEmbeddedRuntimePromotion.plan(zip,existing.replacements,apply);
        if(unity.status!=CompatibilityEvidence.Status.SKIPPED)report.append(unity.status).append(' ').append(UnityEmbeddedRuntimePromotion.ID).append(": ").append(unity.reason).append('\n');
        if(apply)plan.replacements.putAll(unity.replacements);
        Map<String,byte[]> dex=new LinkedHashMap<>();long nativeBudget=64L*1024*1024,dexBudget=64L*1024*1024;int nativeCount=0;boolean dexSkipped=false;
        Set<String> names=new HashSet<>();Map<String,byte[]> nativeLibs=new LinkedHashMap<>();
        Enumeration<? extends ZipEntry> entries=zip.entries();
        while(entries.hasMoreElements()){
            ZipEntry e=entries.nextElement();String name=e.getName();if(name.length()>2048||names.size()>=50000)throw new IOException("APK exceeds shared-rule entry budget");if(!names.add(name))throw new IOException("Duplicate APK entry; shared repair is ambiguous");
            if(name.matches("lib/[^/]+/[^/]+\\.so")&&!existing.replacements.containsKey(name)&&!unity.replacements.containsKey(name)){
                long size=e.getSize();if(++nativeCount>128||size<0||size>24L*1024*1024||size>nativeBudget){report.append("SKIPPED ").append(WritableRelocationRule.ID).append(": native inspection budget\n");continue;}
                nativeBudget-=size;byte[] bytes=ApkRebuilder.readBounded(zip.getInputStream(e),24*1024*1024);
                NdkDependencyPathRule.Result dependency=apply?NdkDependencyPathRule.repair(bytes):NdkDependencyPathRule.inspect(bytes);
                if(dependency.status!=CompatibilityEvidence.Status.SKIPPED)report.append(dependency.status).append(' ').append(NdkDependencyPathRule.ID).append(" [").append(name).append("]: ").append(dependency.reason).append('\n');
                byte[] dependencyBytes=dependency.replacement();if(dependencyBytes!=null){bytes=dependencyBytes;plan.replacements.put(name,bytes);}
                WritableRelocationRule.Result result=apply?WritableRelocationRule.repair(bytes):WritableRelocationRule.inspect(bytes);
                if(result.status!=CompatibilityEvidence.Status.SKIPPED)report.append(result.status).append(' ').append(WritableRelocationRule.ID).append(" [").append(name).append("]: ").append(result.reason).append('\n');
                byte[] changed=result.replacement();if(changed!=null)plan.replacements.put(name,changed);
                if(changed==null&&(name.startsWith("lib/armeabi-v7a/")||name.startsWith("lib/armeabi/"))){
                    DetectedFinalizerRule.Result fin=apply?DetectedFinalizerRule.repair(bytes):DetectedFinalizerRule.inspect(bytes);
                    if(fin.status!=CompatibilityEvidence.Status.SKIPPED)report.append(fin.status).append(' ').append(DetectedFinalizerRule.ID).append(" [").append(name).append("]: ").append(fin.reason).append('\n');
                    byte[] repaired=fin.replacement();if(repaired!=null)plan.replacements.put(name,repaired);
                }
                byte[] finalBytes=plan.replacements.get(name);nativeLibs.put(name,finalBytes!=null?finalBytes:bytes);
            }
            if(name.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")){
                long size=e.getSize();if(dex.size()>=64||size<0||size>32L*1024*1024||size>dexBudget){dexSkipped=true;continue;}
                dexBudget-=size;dex.put(name,ApkRebuilder.readBounded(zip.getInputStream(e),32*1024*1024));
            }
        }
        PrivateLibraryStubs.Result stubs=PrivateLibraryStubs.plan(nativeLibs,apply);
        if(stubs.status!=CompatibilityEvidence.Status.SKIPPED)report.append(stubs.status).append(' ').append(PrivateLibraryStubs.ID).append(": ").append(stubs.reason).append('\n');
        if(apply)for(var add:stubs.additions.entrySet())if(!names.contains(add.getKey()))plan.replacements.put(add.getKey(),add.getValue());
        ZipEntry table=zip.getEntry("resources.arsc");
        if(table!=null&&table.getSize()>=0&&table.getSize()<=32L*1024*1024){
            TvLayoutRule.Result tv=apply?TvLayoutRule.repair(ApkRebuilder.readBounded(zip.getInputStream(table),32*1024*1024)):TvLayoutRule.inspect(ApkRebuilder.readBounded(zip.getInputStream(table),32*1024*1024));
            if(tv.status!=CompatibilityEvidence.Status.SKIPPED)report.append(tv.status).append(' ').append(TvLayoutRule.ID).append(": ").append(tv.reason).append('\n');
            if(apply&&tv.replacement!=null)plan.replacements.put("resources.arsc",tv.replacement);
        }
        if(dexSkipped)report.append("SKIPPED ").append(OuyaServiceCleanupRule.ID).append(": DEX budget; all original DEX preserved\n");
        else{
            OuyaServiceCleanupRule.Result result=apply?OuyaServiceCleanupRule.repair(dex):OuyaServiceCleanupRule.inspect(dex);
            report.append(result.status).append(' ').append(OuyaServiceCleanupRule.ID).append(": ").append(result.reason).append('\n');
            if(apply)plan.replacements.putAll(result.replacements);
            Map<String,byte[]> diagnosticInput=new LinkedHashMap<>(dex);
            if(apply)diagnosticInput.putAll(result.replacements);
            LegacySdkCrashRules.Result sdk=apply?LegacySdkCrashRules.repair(diagnosticInput):LegacySdkCrashRules.inspect(diagnosticInput);
            report.append(sdk.report());if(apply){plan.replacements.putAll(sdk.replacements);diagnosticInput.putAll(sdk.replacements);}
            DeviceIdRule.Result ids=apply?DeviceIdRule.repair(diagnosticInput):DeviceIdRule.inspect(diagnosticInput);
            if(ids.status!=CompatibilityEvidence.Status.SKIPPED)report.append(ids.status).append(' ').append(DeviceIdRule.ID).append(": ").append(ids.reason).append('\n');
            if(apply){plan.replacements.putAll(ids.replacements);diagnosticInput.putAll(ids.replacements);}
            CallGuardRules.Result guards=apply?CallGuardRules.repair(diagnosticInput):CallGuardRules.inspect(diagnosticInput);
            report.append(guards.report());if(apply){plan.replacements.putAll(guards.replacements);diagnosticInput.putAll(guards.replacements);}
            OuyaStoreOfflineRule.Result store=apply?OuyaStoreOfflineRule.repair(diagnosticInput):OuyaStoreOfflineRule.inspect(diagnosticInput);
            if(store.status!=CompatibilityEvidence.Status.SKIPPED)report.append(store.status).append(' ').append(OuyaStoreOfflineRule.ID).append(": ").append(store.reason).append('\n');
            if(apply){plan.replacements.putAll(store.replacements);diagnosticInput.putAll(store.replacements);}
            OuyaGameDataLocalRule.Result saves=apply?OuyaGameDataLocalRule.repair(diagnosticInput):OuyaGameDataLocalRule.inspect(diagnosticInput);
            if(saves.status!=CompatibilityEvidence.Status.SKIPPED)report.append(saves.status).append(' ').append(OuyaGameDataLocalRule.ID).append(": ").append(saves.reason).append('\n');
            if(apply){plan.replacements.putAll(saves.replacements);diagnosticInput.putAll(saves.replacements);}
            OuyaControllerPlayersRule.Result players=apply?OuyaControllerPlayersRule.repair(diagnosticInput):OuyaControllerPlayersRule.inspect(diagnosticInput);
            if(players.status!=CompatibilityEvidence.Status.SKIPPED)report.append(players.status).append(' ').append(OuyaControllerPlayersRule.ID).append(": ").append(players.reason).append('\n');
            if(apply){plan.replacements.putAll(players.replacements);diagnosticInput.putAll(players.replacements);}
            OuyaAccountRule.Result account=apply?OuyaAccountRule.repair(diagnosticInput):OuyaAccountRule.inspect(diagnosticInput);
            if(account.status!=CompatibilityEvidence.Status.SKIPPED)report.append(account.status).append(' ').append(OuyaAccountRule.ID).append(": ").append(account.reason).append('\n');
            if(apply){plan.replacements.putAll(account.replacements);diagnosticInput.putAll(account.replacements);}
            AndEngineBase64Rule.Result base64=apply?AndEngineBase64Rule.repair(diagnosticInput):AndEngineBase64Rule.inspect(diagnosticInput);
            if(base64.status!=CompatibilityEvidence.Status.SKIPPED)report.append(base64.status).append(' ').append(AndEngineBase64Rule.ID).append(": ").append(base64.reason).append('\n');
            if(apply){plan.replacements.putAll(base64.replacements);diagnosticInput.putAll(base64.replacements);}
            GlUniformCountRule.Result uniforms=apply?GlUniformCountRule.repair(diagnosticInput):GlUniformCountRule.inspect(diagnosticInput);
            if(uniforms.status!=CompatibilityEvidence.Status.SKIPPED)report.append(uniforms.status).append(' ').append(GlUniformCountRule.ID).append(": ").append(uniforms.reason).append('\n');
            if(apply){plan.replacements.putAll(uniforms.replacements);diagnosticInput.putAll(uniforms.replacements);}
            ClickteamSurfaceLayoutRule.Result surface=apply?ClickteamSurfaceLayoutRule.repair(diagnosticInput):ClickteamSurfaceLayoutRule.inspect(diagnosticInput);
            if(surface.status!=CompatibilityEvidence.Status.SKIPPED)report.append(surface.status).append(' ').append(ClickteamSurfaceLayoutRule.ID).append(": ").append(surface.reason).append('\n');
            if(apply){plan.replacements.putAll(surface.replacements);diagnosticInput.putAll(surface.replacements);}
            JavascriptInterfaceRule.Result js=apply?JavascriptInterfaceRule.repair(diagnosticInput):JavascriptInterfaceRule.inspect(diagnosticInput);
            if(js.status!=CompatibilityEvidence.Status.SKIPPED)report.append(js.status).append(' ').append(JavascriptInterfaceRule.ID).append(": ").append(js.reason).append('\n');
            if(apply){plan.replacements.putAll(js.replacements);diagnosticInput.putAll(js.replacements);}
            AirLoaderDiagnosticsRule.Result air=apply?AirLoaderDiagnosticsRule.repair(diagnosticInput):AirLoaderDiagnosticsRule.inspect(diagnosticInput);
            if(air.status!=CompatibilityEvidence.Status.SKIPPED)report.append(air.status).append(' ').append(AirLoaderDiagnosticsRule.ID).append(": ").append(air.reason).append('\n');
            if(apply)plan.replacements.putAll(air.replacements);
        }
        report.append("SUPPORTED is inspection evidence; VERIFIED names only local edit checks. Native code relocation, missing services and gameplay may remain unsupported. No purchase changes or additional helper DEXs are added by these rules.");
        plan.report=report.toString();return plan;
    }
    private SharedCompatibilityRepairs(){}
}
