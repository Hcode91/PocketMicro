package com.hcode91.pocketmicro;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Inspects user-supplied APK structure; never loads game classes or executes code. */
public final class ApkCompatibility {
    private static final long DEX_LIMIT = 64L * 1024 * 1024;
    private static final String[] MARKERS = {
        "tv/ouya/console/api/", "OuyaFacade", "OuyaEncryptionHelper", "requestPurchase", "requestReceipts",
        "tv/ouya/sdk/", "UnityOuyaFacade", "PurchaseSuccessListener", "ReceiptListListener"
    };
    public final boolean canRunEngine;
    public final String report;
    private ApkCompatibility(boolean runnable, String text) { canRunEngine=runnable; report=text; }

    public static ApkCompatibility inspect(File apk) throws IOException {
        boolean gameData=false, yoyo32=false, unity=false, air=false;
        boolean partial=false, mono=false;
        Set<String> assemblies=new TreeSet<>();
        Set<String> libs=new TreeSet<>(), markers=new TreeSet<>();
        List<ZipEntry> dex=new ArrayList<>();
        String nativeWarnings;
        try(ZipFile zip=new ZipFile(apk)) {
            nativeWarnings=NativeStartupChecks.inspect(zip);
            Enumeration<? extends ZipEntry> entries=zip.entries(); int count=0;
            while(entries.hasMoreElements()) {
                if(++count>100000) throw new IOException("APK entry count exceeds inspection limit");
                ZipEntry e=entries.nextElement(); String n=e.getName();
                if(n.equals("assets/game.droid"))gameData=true;
                if(n.equals("lib/armeabi-v7a/libyoyo.so") || n.equals("lib/armeabi/libyoyo.so"))yoyo32=true;
                if(n.matches("lib/[^/]+/[^/]+\\.so") && libs.size()<128)libs.add(n);
                if(n.endsWith("/libunity.so"))unity=true;
                if(n.endsWith("/libmono.so"))mono=true;
                if(n.startsWith("assets/bin/Data/Managed/") && n.endsWith(".dll") && assemblies.size()<128)
                    assemblies.add(n.substring(n.lastIndexOf('/')+1));
                if(n.startsWith("assets/META-INF/AIR/"))air=true;
                if(n.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex"))dex.add(e);
            }
            long remaining=DEX_LIMIT;
            for(ZipEntry entry:dex) {
                if(remaining==0){partial=true;break;}
                try(InputStream input=zip.getInputStream(entry)) {
                    byte[] bytes=new byte[8192]; String tail="";
                    while(true) {
                        int n=input.read(bytes,0,(int)Math.min(bytes.length,remaining+1));
                        if(n<0)break;
                        if(n>remaining){partial=true;remaining=0;break;}
                        remaining-=n;
                        String part=tail+new String(bytes,0,n,StandardCharsets.ISO_8859_1);
                        for(String marker:MARKERS)if(part.contains(marker))markers.add(marker);
                        tail=part.substring(Math.max(0,part.length()-64));
                    }
                }
                if(partial)break;
            }
        }
        boolean runnable=gameData && yoyo32;
        StringBuilder out=new StringBuilder("APK compatibility report\n\n");
        out.append("Engine detected: ").append(runnable?"32-bit GameMaker":unity?"Unity":air?"Adobe AIR":"unknown / unsupported").append('\n');
        out.append("GameMaker data: ").append(gameData).append("\n32-bit libyoyo.so: ").append(yoyo32).append('\n');
        out.append("Native libraries (up to 128):\n");
        for(String lib:libs)out.append("  ").append(lib).append('\n');
        if(!nativeWarnings.isEmpty())out.append('\n').append(nativeWarnings).append('\n');
        if(unity) {
            out.append("\nUnity runtime: ").append(mono?"Mono (managed C# assemblies)":"native runtime; inspect APK contents").append('\n');
            out.append("Managed assemblies (up to 128): ").append(assemblies.isEmpty()?"not found":String.join(", ",assemblies)).append('\n');
            out.append("Unity requires a separate execution/Java bridge; the current GameMaker engine cannot execute libunity.so.\n");
        }
        out.append("\nOUYA purchase markers in DEX: ").append(markers.isEmpty()?"none found":String.join(", ",markers)).append('\n');
        if(partial)out.append("DEX scan limited to 64 MiB; absence of a marker is inconclusive.\n");
        out.append("\nEngine detection is structural, not a guarantee of compatibility. DEX markers are hints, not proof that those APIs execute. The engine does not execute the game's Java OUYA SDK.\n");
        if(!runnable)out.append("This APK cannot use the current GameMaker-only Engine test.\n");
        return new ApkCompatibility(runnable,out.toString());
    }
}
