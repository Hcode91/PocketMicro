package com.hcode91.pocketmicro;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Streaming, byte-preserving installed APK export. No conversion or signing. */
public final class GameSupportExport {
    // Decimal MB, deliberately below a 20 MiB attachment limit too.
    public static final long PART_BYTES=20_000_000;
    public interface Destination { OutputStream create(String name,String mime)throws IOException; }
    public static final class Source {
        final String name;final File file;
        public Source(String name,File file){this.name=name;this.file=file;}
    }
    private GameSupportExport(){}
    public static void write(String log,List<Source> sources,Destination destination)throws IOException {
        write(log,sources,destination,PART_BYTES);
    }
    static void write(String log,List<Source> sources,Destination destination,long limit)throws IOException {
        if(limit<=0||sources.isEmpty())throw new IOException("No APK sources or invalid part limit");
        Set<String> names=new HashSet<>();
        for(Source source:sources){
            if(!source.name.matches("[A-Za-z0-9_-]+\\.apk")||!names.add(source.name))throw new IOException("Invalid or duplicate APK name");
            if(!source.file.isFile()||!source.file.canRead()||source.file.length()==0)throw new IOException("Installed APK is missing or unreadable: "+source.name);
        }
        text(destination,"crash-log.txt",log);
        StringBuilder instructions=new StringBuilder("PocketOuya game support export\n\nThe APKs below are copies of the installed files, including any previous conversion. They are not the original APK if the installed game was modified. No APK bytes, signatures or assets were changed by this export. External OBB files, downloaded assets, saves and private app data are not included.\n\nParts contain at most "+limit+" bytes each. For every APK with numbered parts, concatenate .001, .002, .003, ... in numeric order into the original .apk name. Do not extract each part as a ZIP. Keep all parts. A phone archive app that supports joining raw split files can join them; these are raw byte parts, not compressed volumes. Verify the reassembled APK SHA-256 listed below.\n\nFor a split APK installation, keep base.apk and every split-N.apk together. These are separate APKs, not byte parts of base.apk; this export is for diagnosis and is not a one-tap split-APK installer.\n\n");
        byte[] buffer=new byte[65536];
        for(Source source:sources){
            long size=source.file.length(),mtime=source.file.lastModified(),remaining=size;
            boolean split=size>limit;long count=1+(size-1)/limit;
            if(count>99999)throw new IOException("APK requires too many parts");
            MessageDigest digest=sha256();instructions.append(source.name).append(" — ").append(size).append(" bytes\n");
            try(InputStream in=new FileInputStream(source.file)){
                for(int part=1;remaining>0;part++){
                    String name=split?source.name+String.format(Locale.ROOT,".%03d",part):source.name;
                    long piece=Math.min(remaining,limit),left=piece;MessageDigest partDigest=sha256();
                    try(OutputStream out=destination.create(name,split?"application/octet-stream":"application/vnd.android.package-archive")){
                        if(out==null)throw new IOException("Cannot open destination: "+name);
                        while(left>0){int n=in.read(buffer,0,(int)Math.min(left,buffer.length));if(n<0)throw new IOException("APK changed or was truncated during export");out.write(buffer,0,n);digest.update(buffer,0,n);partDigest.update(buffer,0,n);left-=n;}
                    }
                    instructions.append("  ").append(name).append(" — ").append(piece).append(" bytes; SHA-256 ").append(hex(partDigest.digest())).append('\n');remaining-=piece;
                }
                if(in.read()!=-1||source.file.length()!=size||source.file.lastModified()!=mtime)throw new IOException("Installed APK changed during export; retry");
            }
            instructions.append("  Complete APK SHA-256: ").append(hex(digest.digest())).append("\n\n");
        }
        instructions.append("Export complete. Send crash-log.txt, every APK or numbered part, and this file. Nothing was uploaded automatically.\n");
        // Written last: absence means an incomplete export, never a successful bundle.
        text(destination,"EXPORT-COMPLETE.txt",instructions.toString());
    }
    private static void text(Destination destination,String name,String value)throws IOException {
        try(OutputStream out=destination.create(name,"text/plain")){if(out==null)throw new IOException("Cannot open destination: "+name);out.write(value.getBytes(StandardCharsets.UTF_8));}
    }
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static String hex(byte[] data){StringBuilder out=new StringBuilder();for(byte b:data)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
}
