package com.hcode91.pocketmicro;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Copies an APK with a patched manifest and without its old signature files. */
public final class ApkRebuilder {
    private ApkRebuilder() {}

    /** All app install/export preparation paths request controls; low-level APIs remain explicit. */
    public static ManifestPatcher.Result prepareForInstall(File in,File out,byte[] bridge,boolean runtimeRepair,boolean experimental,int minimumTarget)throws IOException {
        if(experimental&&runtimeRepair)throw new IOException("Experimental runtime preparation must be selected separately from ordinary startup repairs");
        return rebuild(in,out,bridge,true,runtimeRepair,minimumTarget,experimental,null,null);
    }
    /** As above; a non-null lowAddressLibrary also applies the opt-in pocketouya.low-addresses experiment. */
    public static ManifestPatcher.Result prepareForInstall(File in,File out,byte[] bridge,boolean runtimeRepair,boolean experimental,int minimumTarget,byte[] lowAddressLibrary)throws IOException {
        return prepareForInstall(in,out,bridge,runtimeRepair,experimental,minimumTarget,lowAddressLibrary,null);
    }
    /** As above; glesShim (libpogles.so) lets startup repairs apply ouya.gles-vertex-attribs. */
    public static ManifestPatcher.Result prepareForInstall(File in,File out,byte[] bridge,boolean runtimeRepair,boolean experimental,int minimumTarget,byte[] lowAddressLibrary,byte[] glesShim)throws IOException {
        return prepareForInstall(in,out,bridge,runtimeRepair,experimental,minimumTarget,lowAddressLibrary,glesShim,false);
    }
    /** As above; fullScreen applies the opt-in android.wide-screen manifest change. */
    public static ManifestPatcher.Result prepareForInstall(File in,File out,byte[] bridge,boolean runtimeRepair,boolean experimental,int minimumTarget,byte[] lowAddressLibrary,byte[] glesShim,boolean fullScreen)throws IOException {
        return prepareForInstall(in,out,bridge,runtimeRepair,experimental,minimumTarget,lowAddressLibrary,glesShim,fullScreen,null,null);
    }
    /**
     * As above; non-null cheat helper libraries (32-bit and 64-bit ARM) add the opt-in in-game cheat
     * menu: a marker asset read by the touch controls plus libpocketouya_cheatmem.so in the game's ARM ABIs.
     */
    public static ManifestPatcher.Result prepareForInstall(File in,File out,byte[] bridge,boolean runtimeRepair,boolean experimental,int minimumTarget,byte[] lowAddressLibrary,byte[] glesShim,boolean fullScreen,byte[] cheat32,byte[] cheat64)throws IOException {
        byte[][] cheats=cheat32==null||cheat64==null?null:new byte[][]{cheat32,cheat64};
        if(experimental&&runtimeRepair)throw new IOException("Experimental runtime preparation must be selected separately from ordinary startup repairs");
        return rebuild(in,out,bridge,true,runtimeRepair,minimumTarget,experimental,lowAddressLibrary,glesShim,fullScreen,cheats);
    }
    public static ManifestPatcher.Result rebuild(File in, File out) throws IOException {
        return rebuild(in,out,null,false,false);
    }
    public static ManifestPatcher.Result rebuild(File in, File out, byte[] bridge, boolean touch, boolean runtimeRepair) throws IOException {
        return rebuild(in,out,bridge,touch,runtimeRepair,ManifestPatcher.MIN_TARGET);
    }
    public static ManifestPatcher.Result rebuild(File in,File out,byte[] bridge,boolean touch,boolean runtimeRepair,int minimumTarget)throws IOException {
        return rebuild(in,out,bridge,touch,runtimeRepair,minimumTarget,false,null,null);
    }
    public static ManifestPatcher.Result rebuildExperimental(File in,File out)throws IOException {
        return rebuildExperimental(in,out,ManifestPatcher.MIN_TARGET);
    }
    public static ManifestPatcher.Result rebuildExperimental(File in,File out,int minimumTarget)throws IOException {
        return rebuildExperimental(in,out,null,false,minimumTarget);
    }
    /** Explicit experiment with optional existing touch bridge. */
    public static ManifestPatcher.Result rebuildExperimental(File in,File out,byte[] bridge,boolean touch,int minimumTarget)throws IOException {
        return rebuild(in,out,bridge,touch,false,minimumTarget,true,null,null);
    }
    private static ManifestPatcher.Result rebuild(File in,File out,byte[] bridge,boolean touch,boolean runtimeRepair,int minimumTarget,boolean experimental,byte[] lowAddressLibrary,byte[] glesShim)throws IOException {
        return rebuild(in,out,bridge,touch,runtimeRepair,minimumTarget,experimental,lowAddressLibrary,glesShim,false,null);
    }
    private static ManifestPatcher.Result rebuild(File in,File out,byte[] bridge,boolean touch,boolean runtimeRepair,int minimumTarget,boolean experimental,byte[] lowAddressLibrary,byte[] glesShim,boolean fullScreen)throws IOException {
        return rebuild(in,out,bridge,touch,runtimeRepair,minimumTarget,experimental,lowAddressLibrary,glesShim,fullScreen,null);
    }
    /** Path of the marker asset that switches on the in-game cheat menu (CheatMenu.MARKER in the bridge). */
    static final String CHEAT_MARKER="assets/pocketouya/cheats";
    static final String CHEAT_LIBRARY="libpocketouya_cheatmem.so";

    /**
     * Where the cheat helper goes: one copy in each ARM ABI folder the game already ships, so the game
     * keeps running in the same 32/64-bit mode. A game with no native libraries runs as 64-bit, so it
     * gets both copies (Android picks arm64-v8a, or armeabi-v7a on a 32-bit-only phone).
     */
    static java.util.Map<String,byte[]> cheatLibraries(ZipFile zip,byte[] v7a,byte[] arm64){
        java.util.Set<String> abis=new java.util.TreeSet<>();boolean anyNative=false;
        for(ZipEntry e:java.util.Collections.list(zip.entries())){
            String n=e.getName();
            if(!n.startsWith("lib/")||!n.endsWith(".so")||n.endsWith("/"+CHEAT_LIBRARY))continue;
            String[] parts=n.split("/");if(parts.length!=3)continue;
            anyNative=true;abis.add(parts[1]);
        }
        java.util.Map<String,byte[]> out=new java.util.LinkedHashMap<>();
        if(!anyNative){out.put("lib/armeabi-v7a/"+CHEAT_LIBRARY,v7a);out.put("lib/arm64-v8a/"+CHEAT_LIBRARY,arm64);return out;}
        if(abis.contains("arm64-v8a"))out.put("lib/arm64-v8a/"+CHEAT_LIBRARY,arm64);
        if(abis.contains("armeabi-v7a"))out.put("lib/armeabi-v7a/"+CHEAT_LIBRARY,v7a);
        if(abis.contains("armeabi"))out.put("lib/armeabi/"+CHEAT_LIBRARY,v7a);
        return out;
    }
    private static ManifestPatcher.Result rebuild(File in,File out,byte[] bridge,boolean touch,boolean runtimeRepair,int minimumTarget,boolean experimental,byte[] lowAddressLibrary,byte[] glesShim,boolean fullScreen,byte[][] cheats)throws IOException {
        boolean lowAddress=lowAddressLibrary!=null;
        try (ZipFile zip = new ZipFile(in)) {
            ZipEntry manifestEntry=zip.getEntry("AndroidManifest.xml");
            if(manifestEntry==null)throw new IOException("Not an Android app (no AndroidManifest.xml).");
            ManifestPatcher.Result result=ManifestPatcher.patch(readBounded(zip.getInputStream(manifestEntry),4*1024*1024),minimumTarget);
            java.util.Map<String,byte[]> replacements=new java.util.LinkedHashMap<>();
            String wideLine=null;
            if(fullScreen){
                TvLandscapeRule.Result wide=WideScreenRule.repair(result.manifest);
                if(wide.manifest!=null)result.manifest=wide.manifest;
                wideLine=wide.status+" "+WideScreenRule.ID+": "+wide.reason;
            }
            if(touch){
                // PocketMicro's virtual controller connects while the game runs; see InputConfigRule.
                TvLandscapeRule.Result input=InputConfigRule.repair(result.manifest);
                if(input.manifest!=null)result.manifest=input.manifest;
                if(input.status!=CompatibilityEvidence.Status.SKIPPED)wideLine=(wideLine==null?"":wideLine+"\n")+input.status+" "+InputConfigRule.ID+": "+input.reason;
            }
            if(runtimeRepair){
                SharedCompatibilityRepairs.Plan repair=SharedCompatibilityRepairs.plan(zip,true,result.manifest);
                replacements.putAll(repair.replacements);result.compatibilityReport=repair.report;
                if(repair.manifest!=null)result.manifest=repair.manifest;
                java.util.Map<String,byte[]> scaleform=new java.util.LinkedHashMap<>();java.util.List<String> all=new java.util.ArrayList<>();
                for(ZipEntry e:java.util.Collections.list(zip.entries())){
                    all.add(e.getName());
                    if(!e.getName().matches("lib/[^/]+/"+ScaleformGlesRule.TARGET.replace(".","\\.")))continue;
                    byte[] current=replacements.get(e.getName());
                    scaleform.put(e.getName(),current!=null?current:readBounded(zip.getInputStream(e),32*1024*1024));
                }
                ScaleformGlesRule.Result gles=ScaleformGlesRule.apply(scaleform,all,glesShim);
                replacements.putAll(gles.replacements);
                if(gles.status!=CompatibilityEvidence.Status.SKIPPED)result.compatibilityReport+="\n"+gles.line();
            }
            if(experimental){
                ExperimentalUnityRuntimeRepair.Plan repair=ExperimentalUnityRuntimeRepair.plan(zip);
                replacements.putAll(repair.replacements);result.compatibilityReport=repair.report;
                if(!repair.replacements.isEmpty()){
                    java.util.Map<String,byte[]> dexInput=new java.util.LinkedHashMap<>();long budget=64L*1024*1024;
                    for(ZipEntry e:java.util.Collections.list(zip.entries()))if(e.getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")){
                        if(dexInput.size()>=64||e.getSize()<0||e.getSize()>32L*1024*1024||e.getSize()>budget)throw new IOException("Experimental DEX budget exceeded");
                        byte[] bytes=readBounded(zip.getInputStream(e),32*1024*1024);budget-=bytes.length;dexInput.put(e.getName(),bytes);
                    }
                    UnityCpuStringRule.Result cpu=UnityCpuStringRule.repair(dexInput);replacements.putAll(cpu.replacements);result.compatibilityReport+="\n\n"+cpu.report;
                }
            }
            String nativeWarnings=NativeStartupChecks.inspect(zip,replacements);
            boolean appendBridge=false,touchHooked=false;int nextDex=2;
            if(touch||lowAddress){
                if(bridge==null||bridge.length==0)throw new IOException("Converter compatibility bridge is missing");
                java.util.Map<String,byte[]> input=new java.util.LinkedHashMap<>();long total=0;
                Enumeration<? extends ZipEntry> entries=zip.entries();
                while(entries.hasMoreElements()){
                    ZipEntry entry=entries.nextElement();String name=entry.getName();
                    if(!name.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex"))continue;
                    int number=name.equals("classes.dex")?1:Integer.parseInt(name.substring(7,name.length()-4));
                    if(number>1000 || input.size()>=64)throw new IOException("Too many game DEX files");
                    byte[] bytes=replacements.get(name);if(bytes==null)bytes=readBounded(zip.getInputStream(entry),32*1024*1024);total+=bytes.length;
                    if(total>64L*1024*1024)throw new IOException("DEX analysis exceeds the 64 MiB limit; try normal conversion");
                    if(input.put(name,bytes)!=null)throw new IOException("Duplicate DEX entry");
                    nextDex=Math.max(nextDex,number+1);
                }
                if(!input.containsKey("classes.dex"))throw new IOException("Missing primary DEX; compatibility cannot be inspected");
                if(touch){
                    GameCompatibilityPatcher.Result plan=GameCompatibilityPatcher.patch(input,result.launcherActivities,touch);
                    replacements.putAll(plan.dex);input.putAll(plan.dex);appendBridge=plan.needsBridge();touchHooked=plan.touchEnabled;result.compatibilityReport+=(result.compatibilityReport.isEmpty()?"":"\n\n")+plan.report;
                }
                java.util.List<String> names=new java.util.ArrayList<>();for(ZipEntry e:java.util.Collections.list(zip.entries()))names.add(e.getName());
                if(runtimeRepair){
                    SurfaceDetachRule.Result guard=SurfaceDetachRule.apply(input,result.launcherActivities);
                    replacements.putAll(guard.replacements);input.putAll(guard.replacements);appendBridge|=guard.needsBridge;
                    if(guard.status!=CompatibilityEvidence.Status.SKIPPED||!guard.views.isEmpty())result.compatibilityReport+=(result.compatibilityReport.isEmpty()?"":"\n\n")+guard.line();
                }
                if(lowAddress){
                    LowAddressRule.Result low=LowAddressRule.apply(input,result.launcherActivities,names,lowAddressLibrary);
                    replacements.putAll(low.replacements);appendBridge|=low.needsBridge;
                    result.compatibilityReport+=(result.compatibilityReport.isEmpty()?"":"\n\n")+low.line();
                }
            }
            boolean cheatMenu=cheats!=null&&touchHooked;
            if(cheats!=null)wideLine=(wideLine==null?"":wideLine+"\n")+(cheatMenu?"Cheat menu: added (Cheats button at the top right of the touch controls).":"Cheat menu: not added, because the touch controls couldn't be attached to this game.");
            if(wideLine!=null)result.compatibilityReport+=(result.compatibilityReport.isEmpty()?"":"\n")+wideLine;
            if(!nativeWarnings.isEmpty())result.compatibilityReport+=(result.compatibilityReport.isEmpty()?"":"\n\n")+nativeWarnings;
            byte[] buffer=new byte[65536];
            try(ZipOutputStream output=new ZipOutputStream(new FileOutputStream(out))){
                Enumeration<? extends ZipEntry> entries=zip.entries();
                while(entries.hasMoreElements()){
                    ZipEntry entry=entries.nextElement();String name=entry.getName();
                    if(isOldSignature(name)||entry.isDirectory()||name.equals(ConversionRecord.ENTRY)||(name.equals(CHEAT_MARKER)&&!cheatMenu)||name.endsWith("/"+CHEAT_LIBRARY))continue;
                    byte[] replacement=name.equals("AndroidManifest.xml")?result.manifest:replacements.get(name);
                    ZipEntry target=new ZipEntry(name);target.setTime(entry.getTime());
                    if(entry.getMethod()==ZipEntry.STORED){
                        target.setMethod(ZipEntry.STORED);
                        if(replacement!=null){CRC32 crc=new CRC32();crc.update(replacement);target.setSize(replacement.length);target.setCompressedSize(replacement.length);target.setCrc(crc.getValue());}
                        else {target.setSize(entry.getSize());target.setCompressedSize(entry.getSize());target.setCrc(entry.getCrc());}
                    }else target.setMethod(ZipEntry.DEFLATED);
                    output.putNextEntry(target);
                    if(replacement!=null)output.write(replacement);
                    else try(InputStream input=zip.getInputStream(entry)){for(int n;(n=input.read(buffer))!=-1;)output.write(buffer,0,n);}
                    output.closeEntry();
                }
                if(appendBridge){output.putNextEntry(new ZipEntry("classes"+nextDex+".dex"));output.write(bridge);output.closeEntry();}
                // New files from repairs (e.g. placeholder native libraries), stored uncompressed like native libs.
                for(java.util.Map.Entry<String,byte[]> added:replacements.entrySet()){
                    if(zip.getEntry(added.getKey())!=null||!added.getKey().matches("lib/[^/]+/[^/]+\\.so"))continue;
                    ZipEntry target=new ZipEntry(added.getKey());target.setMethod(ZipEntry.STORED);CRC32 crc=new CRC32();crc.update(added.getValue());
                    target.setSize(added.getValue().length);target.setCompressedSize(added.getValue().length);target.setCrc(crc.getValue());
                    output.putNextEntry(target);output.write(added.getValue());output.closeEntry();
                }
                if(cheatMenu&&zip.getEntry(CHEAT_MARKER)==null){output.putNextEntry(new ZipEntry(CHEAT_MARKER));output.write(new byte[]{'1'});output.closeEntry();}
                if(cheatMenu)for(java.util.Map.Entry<String,byte[]> lib:cheatLibraries(zip,cheats[0],cheats[1]).entrySet()){
                    ZipEntry target=new ZipEntry(lib.getKey());target.setMethod(ZipEntry.STORED);CRC32 crc=new CRC32();crc.update(lib.getValue());
                    target.setSize(lib.getValue().length);target.setCompressedSize(lib.getValue().length);target.setCrc(crc.getValue());
                    output.putNextEntry(target);output.write(lib.getValue());output.closeEntry();
                }
                output.putNextEntry(new ZipEntry(ConversionRecord.ENTRY));output.write(ConversionRecord.build(runtimeRepair,touch,experimental,result.compatibilityReport));output.closeEntry();
            }
            return result;
        }catch(NumberFormatException e){throw new IOException("Unsupported DEX filename",e);}
    }

    static boolean isOldSignature(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String n = name.toUpperCase(java.util.Locale.ROOT);
        return n.equals("META-INF/MANIFEST.MF") || n.endsWith(".SF") || n.endsWith(".RSA")
                || n.endsWith(".DSA") || n.endsWith(".EC");
    }

    static byte[] readBounded(InputStream in, int maximum) throws IOException {
        try(InputStream input=in; ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int total=0;
            for(int n;(n=input.read(buffer))!=-1;) {
                total+=n; if(total>maximum)throw new IOException("Game DEX exceeds limit");
                output.write(buffer,0,n);
            }
            return output.toByteArray();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /** Small helper for copying a stream to a file. */
    static void copy(InputStream in, File out) throws IOException {
        try (InputStream is = in; OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }
}
