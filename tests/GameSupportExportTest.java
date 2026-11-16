package com.hcode91.pocketmicro;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class GameSupportExportTest {
    static void check(boolean b){if(!b)throw new AssertionError("Support export regression");}
    static Map<String,byte[]> run(Path source,int size,int limit)throws Exception {
        byte[] data=new byte[size];new Random(size).nextBytes(data);Files.write(source,data);
        Map<String,byte[]> output=new LinkedHashMap<>();
        GameSupportExport.write("Game Ω\nNo verified crash.",List.of(new GameSupportExport.Source("base.apk",source.toFile())),(name,mime)->new ByteArrayOutputStream(){public void close(){output.put(name,toByteArray());}},limit);
        ByteArrayOutputStream rebuilt=new ByteArrayOutputStream();
        for(var e:output.entrySet())if(e.getKey().startsWith("base.apk")){check(e.getValue().length<=limit);rebuilt.write(e.getValue());}
        check(Arrays.equals(data,rebuilt.toByteArray()));check(new String(output.get("crash-log.txt"),java.nio.charset.StandardCharsets.UTF_8).contains("Game Ω"));
        check(output.containsKey("EXPORT-COMPLETE.txt"));check(Arrays.equals(data,Files.readAllBytes(source)));return output;
    }
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("support-export-");
        try {
            Path file=dir.resolve("source.apk");
            for(int n:new int[]{1,19,20}){var out=run(file,n,20);check(out.containsKey("base.apk")&&!out.containsKey("base.apk.001"));}
            for(int n:new int[]{21,40,41,2001}){var out=run(file,n,20);check(out.containsKey("base.apk.001")&&!out.containsKey("base.apk"));}
            // Public path at the actual attachment boundary, streamed to disk.
            byte[] block=new byte[65536];new Random(5).nextBytes(block);
            try(OutputStream out=Files.newOutputStream(file)){long left=GameSupportExport.PART_BYTES+1;while(left>0){int n=(int)Math.min(left,block.length);out.write(block,0,n);left-=n;}}
            Map<String,Path> outputs=new LinkedHashMap<>();
            GameSupportExport.Destination sink=(name,mime)->{Path target=dir.resolve(name);outputs.put(name,target);return Files.newOutputStream(target);};
            GameSupportExport.write("No capture yet.",List.of(new GameSupportExport.Source("base.apk",file.toFile()),new GameSupportExport.Source("split-1.apk",file.toFile())),sink);
            check(Files.size(outputs.get("base.apk.001"))==20_000_000);check(Files.size(outputs.get("base.apk.002"))==1);check(Files.size(outputs.get("split-1.apk.001"))==20_000_000);
            MessageDigest joined=MessageDigest.getInstance("SHA-256");for(String name:List.of("base.apk.001","base.apk.002"))try(InputStream in=Files.newInputStream(outputs.get(name))){for(int n;(n=in.read(block))!=-1;)joined.update(block,0,n);}
            String hash=HexFormat.of().formatHex(joined.digest());check(Files.readString(outputs.get("EXPORT-COMPLETE.txt")).contains("Complete APK SHA-256: "+hash));
            outputs.clear();try{GameSupportExport.write("log",List.of(new GameSupportExport.Source("../bad.apk",file.toFile())),sink);throw new AssertionError();}catch(IOException expected){check(outputs.isEmpty());}
            try{GameSupportExport.write("log",List.of(new GameSupportExport.Source("base.apk",file.toFile()),new GameSupportExport.Source("base.apk",file.toFile())),sink);throw new AssertionError();}catch(IOException expected){check(outputs.isEmpty());}
            try{GameSupportExport.write("log",List.of(new GameSupportExport.Source("base.apk",dir.resolve("missing").toFile())),sink);throw new AssertionError();}catch(IOException expected){check(outputs.isEmpty());}
            // A failed destination must not produce the completion marker.
            try{GameSupportExport.write("log",List.of(new GameSupportExport.Source("base.apk",file.toFile())),(name,mime)->{if(name.startsWith("base"))throw new IOException("disk full");return sink.create(name,mime);});throw new AssertionError();}catch(IOException expected){check(!outputs.containsKey("EXPORT-COMPLETE.txt"));}
            outputs.clear();
            // Updating/truncating the installed APK during a copy is rejected.
            try{GameSupportExport.write("log",List.of(new GameSupportExport.Source("base.apk",file.toFile())),(name,mime)->{if(name.startsWith("base"))Files.write(file,new byte[]{1});return sink.create(name,mime);});throw new AssertionError();}catch(IOException expected){check(!outputs.containsKey("EXPORT-COMPLETE.txt"));}
        }finally{try(var files=Files.list(dir)){for(Path p:files.toArray(Path[]::new))Files.delete(p);}Files.delete(dir);}
        System.out.println("Support export: size boundaries, exact reassembly, SHA-256, split APKs, immutable source, missing/unsafe/duplicate sources, copy failure and source-change rejection pass");
    }
}
