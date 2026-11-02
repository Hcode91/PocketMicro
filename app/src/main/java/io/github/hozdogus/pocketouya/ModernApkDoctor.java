package io.github.hozdogus.pocketouya;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Static preflight only. Does not load APK classes, native code or contact servers. */
public final class ModernApkDoctor {
    private ModernApkDoctor(){}
    public static int minimumTarget(int androidApi){return androidApi>=35?24:23;}
    public static String inspect(File apk,int androidApi,String[] deviceAbis)throws IOException {
        int floor=minimumTarget(androidApi);
        StringBuilder report=new StringBuilder("APK compatibility doctor\n\n");
        try(ZipFile zip=new ZipFile(apk)){
            Set<String> names=new HashSet<>(),abis=new TreeSet<>();int count=0,libs=0;boolean dex=false;
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()){
                ZipEntry e=entries.nextElement();String n=e.getName();
                if(++count>50000||n.length()>2048)throw new IOException("APK structure exceeds inspection limits");
                if(!names.add(n))throw new IOException("Ambiguous APK: duplicate ZIP entry");
                if(n.matches("lib/[^/]+/[^/]+\\.so")){abis.add(n.split("/")[1]);libs++;}
                if(n.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex"))dex=true;
            }
            ZipEntry entry=zip.getEntry("AndroidManifest.xml");if(entry==null)throw new IOException("Not an APK: AndroidManifest.xml missing");
            byte[] manifest=read(zip.getInputStream(entry),4*1024*1024);
            ManifestPatcher.Result m;
            try{m=ManifestPatcher.patch(manifest,floor);}catch(RuntimeException e){throw new IOException("Manifest cannot be inspected/patched by this converter",e);}
            report.append("Package: ").append(m.packageName).append("\nPhone Android API: ").append(androidApi)
                .append("\nPhone ABIs: ").append(String.join(", ",deviceAbis)).append("\nMinimum SDK: ").append(m.minSdk)
                .append("\nDeclared target SDK: ").append(m.oldTarget).append("\n\n");
            if(m.minSdk>androidApi)report.append("UNRESOLVED: APK requires a newer Android version than this phone. Lowering minSdk cannot supply missing APIs.\n");
            if(m.changed)report.append("SUPPORTED FIX: raise the effective target to API ").append(m.newTarget).append(" for this conversion. Target changes can expose runtime permission, storage and native-loader differences.\n");
            else report.append("Manifest target: no target increase selected.\n");
            if(androidApi<34)report.append("This phone predates Android 14's low-target installation block; API 23 is the converter's baseline.\n");
            if(androidApi>36)report.append("UNVERIFIED: installation-policy changes after Android API 36 are not modeled.\n");
            if(m.oldTarget<0)report.append("No explicit target: the existing converter raises minSdk when it is the effective target.\n");
            report.append("\nPackaged native libraries: ").append(libs).append("\nABI directories: ").append(abis.isEmpty()?"none":String.join(", ",abis)).append('\n');
            if(!abis.isEmpty()&&Collections.disjoint(abis,Arrays.asList(deviceAbis)))report.append("UNRESOLVED: no packaged ABI matches the phone. Re-signing and text-relocation repair cannot translate CPU code.\n");
            else if(!abis.isEmpty())report.append("A packaged ABI matches the phone; this does not prove native API/CPU compatibility.\n");
            else report.append("No lib/ libraries found. Native code in assets or downloaded later is not ruled out.\n");
            if(!dex)report.append("CHECK: no primary/application DEX found. This may be a resource/configuration split or a native-only APK; split-set installation is not implemented.\n");
            report.append("\nNative repair preview (matching files only; input unchanged)\n");
            SharedCompatibilityRepairs.Plan plan=SharedCompatibilityRepairs.plan(zip,false);report.append(plan.report);
            // Existing native preview remains the warning override; inspection never enables new edits.
            String warnings=NativeStartupChecks.inspect(zip,NativeRuntimeRepair.plan(zip).replacements);
            if(!warnings.isEmpty())report.append("\n\nRemaining after supported repairs:\n").append(warnings);
            ApkRepairAssessment.Result assessment=ApkRepairAssessment.inspect(apk,androidApi,deviceAbis);
            report.append("\n\nSuggested conversion path\n\n").append(assessment.report);
            if(assessment.suggested==ApkRepairAssessment.Route.EXPERIMENTAL)report.append("\n\nThis doctor's Prepare fixes does not enable the experiment. Use Add game and review Experimental Unity embedded-runtime repair for that candidate.");
            report.append("\n\nNot established by this scan: successful launch, missing Java/private native APIs, server availability, licensing, graphics/audio correctness, split dependencies, 16 KiB page compatibility, permissions at runtime, or APK trust/security. Native inspection has size/count limits; silence is not an all-clear.\n\nPrepare fixes runs manifest conversion, the fingerprinted native repairs and matching shared rules, then signs a separate APK with PocketOuya's game key. Stale TEXTREL declarations are removed only when all supported relocation writes are proven writable; actual code relocations remain unsupported by that rule. Matching OUYA SDK null-context shutdown calls receive a cleanup guard; unavailable services remain unresolved. The original stays unchanged. Signing differs from the original publisher, so updates/signature checks may fail. Do not uninstall an existing app without protecting its saves. No touch/purchase hooks are added by the doctor.\n\nInstall/launch only if you choose, then use PocketOuya's global crash log for runtime evidence.");
        }
        if(report.length()>250000)throw new IOException("Report exceeds display limit");
        return report.toString();
    }
    private static byte[] read(InputStream input,int limit)throws IOException{
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int total=0;
            for(int n;(n=in.read(b))!=-1;){total+=n;if(total>limit)throw new IOException("Manifest too large");out.write(b,0,n);}return out.toByteArray();
        }
    }
}
