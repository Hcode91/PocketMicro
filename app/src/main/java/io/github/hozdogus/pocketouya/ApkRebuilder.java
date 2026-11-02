package io.github.hozdogus.pocketouya;

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

    public static ManifestPatcher.Result rebuild(File in, File out) throws IOException {
        return rebuild(in,out,null,false,false);
    }
    public static ManifestPatcher.Result rebuild(File in, File out, byte[] bridge) throws IOException {
        return rebuild(in,out,bridge,bridge!=null,bridge!=null);
    }
    public static ManifestPatcher.Result rebuild(File in, File out, byte[] bridge, boolean touch, boolean offline) throws IOException {
        return rebuild(in,out,bridge,touch,offline,false);
    }
    public static ManifestPatcher.Result rebuild(File in, File out, byte[] bridge, boolean touch, boolean offline, boolean runtimeRepair) throws IOException {
        return rebuild(in,out,bridge,touch,offline,runtimeRepair,ManifestPatcher.MIN_TARGET);
    }
    public static ManifestPatcher.Result rebuild(File in,File out,byte[] bridge,boolean touch,boolean offline,boolean runtimeRepair,int minimumTarget)throws IOException {
        return rebuild(in,out,bridge,touch,offline,runtimeRepair,minimumTarget,false);
    }
    public static ManifestPatcher.Result rebuildExperimental(File in,File out)throws IOException {
        return rebuild(in,out,null,false,false,false,ManifestPatcher.MIN_TARGET,true);
    }
    private static ManifestPatcher.Result rebuild(File in,File out,byte[] bridge,boolean touch,boolean offline,boolean runtimeRepair,int minimumTarget,boolean experimental)throws IOException {
        try (ZipFile zip = new ZipFile(in)) {
            ZipEntry manifestEntry=zip.getEntry("AndroidManifest.xml");
            if(manifestEntry==null)throw new IOException("Not an Android app (no AndroidManifest.xml).");
            ManifestPatcher.Result result=ManifestPatcher.patch(readBounded(zip.getInputStream(manifestEntry),4*1024*1024),minimumTarget);
            java.util.Map<String,byte[]> replacements=new java.util.LinkedHashMap<>();
            if(runtimeRepair){
                SharedCompatibilityRepairs.Plan repair=SharedCompatibilityRepairs.plan(zip,true);
                replacements.putAll(repair.replacements);result.compatibilityReport=repair.report;
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
            boolean appendBridge=false;int nextDex=2;
            if(touch||offline){
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
                GameCompatibilityPatcher.Result plan=GameCompatibilityPatcher.patch(input,result.launcherActivities,touch,offline);
                replacements.putAll(plan.dex);appendBridge=plan.needsBridge();result.compatibilityReport+=(result.compatibilityReport.isEmpty()?"":"\n\n")+plan.report;
            }
            if(!nativeWarnings.isEmpty())result.compatibilityReport+=(result.compatibilityReport.isEmpty()?"":"\n\n")+nativeWarnings;
            byte[] buffer=new byte[65536];
            try(ZipOutputStream output=new ZipOutputStream(new FileOutputStream(out))){
                Enumeration<? extends ZipEntry> entries=zip.entries();
                while(entries.hasMoreElements()){
                    ZipEntry entry=entries.nextElement();String name=entry.getName();
                    if(isOldSignature(name)||entry.isDirectory())continue;
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
