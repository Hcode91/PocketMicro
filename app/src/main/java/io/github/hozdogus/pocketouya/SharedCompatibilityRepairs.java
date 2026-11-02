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
        Map<String,byte[]> dex=new LinkedHashMap<>();long nativeBudget=64L*1024*1024,dexBudget=64L*1024*1024;int nativeCount=0;boolean dexSkipped=false;
        Set<String> names=new HashSet<>();
        Enumeration<? extends ZipEntry> entries=zip.entries();
        while(entries.hasMoreElements()){
            ZipEntry e=entries.nextElement();String name=e.getName();if(name.length()>2048||names.size()>=50000)throw new IOException("APK exceeds shared-rule entry budget");if(!names.add(name))throw new IOException("Duplicate APK entry; shared repair is ambiguous");
            if(name.matches("lib/[^/]+/[^/]+\\.so")&&!existing.replacements.containsKey(name)){
                long size=e.getSize();if(++nativeCount>128||size<0||size>24L*1024*1024||size>nativeBudget){report.append("SKIPPED ").append(WritableRelocationRule.ID).append(": native inspection budget\n");continue;}
                nativeBudget-=size;byte[] bytes=ApkRebuilder.readBounded(zip.getInputStream(e),24*1024*1024);
                WritableRelocationRule.Result result=apply?WritableRelocationRule.repair(bytes):WritableRelocationRule.inspect(bytes);
                if(result.status!=CompatibilityEvidence.Status.SKIPPED)report.append(result.status).append(' ').append(WritableRelocationRule.ID).append(" [").append(name).append("]: ").append(result.reason).append('\n');
                byte[] changed=result.replacement();if(changed!=null)plan.replacements.put(name,changed);
            }
            if(name.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")){
                long size=e.getSize();if(dex.size()>=64||size<0||size>32L*1024*1024||size>dexBudget){dexSkipped=true;continue;}
                dexBudget-=size;dex.put(name,ApkRebuilder.readBounded(zip.getInputStream(e),32*1024*1024));
            }
        }
        if(dexSkipped)report.append("SKIPPED ").append(OuyaServiceCleanupRule.ID).append(": DEX budget; all original DEX preserved\n");
        else{
            OuyaServiceCleanupRule.Result result=apply?OuyaServiceCleanupRule.repair(dex):OuyaServiceCleanupRule.inspect(dex);
            report.append(result.status).append(' ').append(OuyaServiceCleanupRule.ID).append(": ").append(result.reason).append('\n');
            if(apply)plan.replacements.putAll(result.replacements);
        }
        report.append("SUPPORTED is inspection evidence; VERIFIED names only local edit checks. Native code relocation, missing services and gameplay may remain unsupported. No purchase changes or helpers are added by these rules.");
        plan.report=report.toString();return plan;
    }
    private SharedCompatibilityRepairs(){}
}
