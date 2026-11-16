package com.hcode91.pocketmicro;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Explicit experimental conversion only. Never selected by ordinary startup repairs or the doctor. */
public final class ExperimentalUnityRuntimeRepair {
    public static final class Plan {
        public final Map<String,byte[]> replacements=new LinkedHashMap<>();
        public String report;
        public boolean applicable;
    }
    private static final String ROOT="lib/armeabi-v7a/",ASSET="assets/libs/armeabi-v7a/";
    private static byte[] read(ZipFile zip,String name)throws IOException{
        ZipEntry e=zip.getEntry(name);if(e==null)throw new IOException("Required runtime missing: "+name);
        if(e.getSize()<=0||e.getSize()>24L*1024*1024)throw new IOException("Runtime exceeds inspection budget: "+name);
        return ApkRebuilder.readBounded(zip.getInputStream(e),24*1024*1024);
    }
    private static String soname(byte[] data)throws IOException{
        ArmRuntimePicRepair.Elf e=new ArmRuntimePicRepair.Elf(data);var d=e.dynamic();
        int stringAddress=e.value(d,5)+e.value(d,14),limit=e.value(d,10),index=e.value(d,14);
        if(index<0||index>=limit||limit>1024*1024)throw new IOException("Invalid SONAME extent");
        int at=e.offset(stringAddress,1),end=at;while(end<data.length&&end-at<256&&data[end]!=0)end++;
        if(end==data.length||end-at>=256||end-at>=limit-index)throw new IOException("Unterminated SONAME");
        return new String(data,at,end-at,StandardCharsets.US_ASCII);
    }
    private static CompatibilityEvidence recognized(byte[] data)throws IOException{
        var evidence=ArmFinalizerDetector.inspect(data);
        if(evidence.status!=CompatibilityEvidence.Status.SUPPORTED||evidence.candidates.size()!=1||evidence.candidates.get(0).thunk!=-1)
            throw new IOException("Only independently recognized ARM finalizers without Thumb edits are eligible; detector result: "+evidence.status);
        return evidence;
    }
    private static void writableRelocations(byte[] bytes)throws IOException{
        var e=new ArmRuntimePicRepair.Elf(bytes);ArmRuntimePicRepair.validateRelocations(e);
        if(e.b.getShort(16)!=3||bytes[6]!=1)throw new IOException("Expected version-one ARM shared object");
        var d=e.dynamic();for(int tag:new int[]{7,8,9,35,36,37,0x6000000f,0x60000010,0x60000011,0x60000012,0x6fffe000,0x6fffe001,0x6fffe003})
            if(d.containsKey(tag))throw new IOException("Unsupported packed/RELA/RELR format");
        if(d.containsKey(23)&&e.value(d,20)!=17)throw new IOException("PLT relocations are not ordinary REL");
        for(int[] pair:new int[][]{{17,18},{23,2}})if(d.containsKey(pair[0])){
            int size=e.value(d,pair[1]);if(size<0||size%8!=0||size>2*1024*1024)throw new IOException("Invalid relocation extent");
            int at=e.offset(e.value(d,pair[0]),size);for(int p=at;p<at+size;p+=8){int kind=e.b.getInt(p+4)&255;
                if(kind!=0&&kind!=2&&kind!=3&&kind!=21&&kind!=22&&kind!=23)throw new IOException("Unsupported ARM loader write type "+kind);
                if(kind!=0&&(e.b.getInt(p)&3)!=0)throw new IOException("Unaligned loader write");
            }
        }
    }
    public static Plan inspect(ZipFile zip){return evaluate(zip,false);}
    public static Plan plan(ZipFile zip){return evaluate(zip,true);}
    private static Plan evaluate(ZipFile zip,boolean apply){
        Plan plan=new Plan();String prefix="Experimental Unity embedded runtime repair\n\n";
        try{
            Set<String> names=new HashSet<>();Enumeration<? extends ZipEntry> entries=zip.entries();while(entries.hasMoreElements()){
                ZipEntry e=entries.nextElement();if(names.size()>=50000||e.getName().length()>2048)throw new IOException("APK entry budget exceeded");
                if(!names.add(e.getName()))throw new IOException("Duplicate APK entries");
            }
            byte[] monoProxy=read(zip,ROOT+"libmono.so"),unityProxy=read(zip,ROOT+"libunity.so");
            if(!soname(monoProxy).equals("libmono_proxy.so")||!soname(unityProxy).equals("libunity_proxy.so"))throw new IOException("Matching pair of proxy SONAMEs not found (may already use real runtimes)");
            recognized(monoProxy);recognized(unityProxy);
            byte[] mono=read(zip,ASSET+"libmono.so"),unity=read(zip,ASSET+"libunity.so");
            if(!soname(mono).equals("libmono.so")||!soname(unity).equals("libunity.so"))throw new IOException("Embedded SONAME mismatch");
            writableRelocations(mono);if(NativeStartupChecks.hasTextRelocations(mono))throw new IOException("Embedded Mono needs a separate repair");
            var c=recognized(unity).candidates.get(0);
            var sourceElf=new ArmRuntimePicRepair.Elf(unity);var tags=sourceElf.dynamic();
            if(!tags.containsKey(22)||sourceElf.value(tags,0x6ffffffa)<1||(tags.containsKey(30)&&(sourceElf.value(tags,30)&4)!=0))throw new IOException("Unsupported declaration/relative-count preconditions");
            if(!apply){plan.applicable=true;plan.report=prefix+"SUPPORTED EXPERIMENTAL CANDIDATE: proxy/payload SONAMEs, clean Mono and independently rooted ARM-only Unity finalizer match. No modified bytes generated. Whole-program safety and phone startup remain unverified.";return plan;}
            byte[] repaired=ArmRuntimePicRepair.repairLayout(unity,(int)c.finalizer,(int)c.target,-1,0);
            writableRelocations(repaired);if(NativeStartupChecks.hasTextRelocations(repaired))throw new IOException("Unity still declares text relocations");
            // This experiment may not change program mappings or permissions.
            int ph=java.nio.ByteBuffer.wrap(unity).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(28);
            int stride=((unity[43]&255)<<8)|(unity[42]&255),count=((unity[45]&255)<<8)|(unity[44]&255);
            if(ph<0||stride<32||count>128||ph+(long)stride*count>unity.length)throw new IOException("Invalid program headers");
            for(int n=ph;n<ph+stride*count;n++)if(unity[n]!=repaired[n])throw new IOException("Program headers changed");
            plan.applicable=true;plan.replacements.put(ROOT+"libmono.so",mono);plan.replacements.put(ROOT+"libunity.so",repaired);
            plan.report=prefix+"APPLIED (experimental): recognized proxy pair and matching embedded ARM32 runtimes. Promoted Mono unchanged and repaired the metadata-rooted ARM finalizer in Unity. Both output relocation tables write only to writable load memory. Program headers/permissions unchanged. No Thumb edits or catalog fingerprints were used.\n\nLocal verification does not establish whole-program reference safety or successful Android startup/gameplay. Assets remain byte-identical. Use normal Play and export the full log if it fails.";
        }catch(IOException|RuntimeException error){plan.applicable=false;plan.replacements.clear();plan.report=prefix+"UNSUPPORTED / NOT APPLIED: "+error.getMessage()+"\nAll native files preserved. This option requires a complete original APK with the supported proxy/payload structure; uncertain or Thumb-dependent cases are excluded.";}
        return plan;
    }
    private ExperimentalUnityRuntimeRepair(){}
}
