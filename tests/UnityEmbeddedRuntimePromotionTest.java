package io.github.hozdogus.pocketouya;

import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class UnityEmbeddedRuntimePromotionTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static byte[] lib(String soname) { return StubLibraryWriter.write(soname, List.of("f"), Map.of()); }
    static Path apk(Map<String, byte[]> entries) throws Exception {
        Path p = Files.createTempFile("unity-embedded", ".apk");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) { for (var e : entries.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue()); z.closeEntry(); } }
        return p;
    }
    public static void main(String[] args) throws Exception {
        byte[] monoStub = lib("libmono_proxy.so"), unityStub = lib("libunity_proxy.so"), mono = lib("libmono.so"), unity = lib("libunity.so");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("lib/armeabi-v7a/libmain.so", lib("libmain.so"));
        files.put("lib/armeabi-v7a/libmono.so", monoStub); files.put("lib/armeabi-v7a/libunity.so", unityStub);
        files.put("assets/libs/armeabi-v7a/libmono.so", mono); files.put("assets/libs/armeabi-v7a/libunity.so", unity);
        Path p = apk(files);
        try (ZipFile z = new ZipFile(p.toFile())) {
            var scan = UnityEmbeddedRuntimePromotion.plan(z, Map.of(), false);
            check(scan.status == CompatibilityEvidence.Status.SUPPORTED && scan.replacements.isEmpty(), "inspect only");
            var r = UnityEmbeddedRuntimePromotion.plan(z, Map.of(), true);
            check(r.status == CompatibilityEvidence.Status.VERIFIED && Arrays.equals(r.replacements.get("lib/armeabi-v7a/libmono.so"), mono)
                    && Arrays.equals(r.replacements.get("lib/armeabi-v7a/libunity.so"), unity) && !r.replacements.containsKey("lib/armeabi-v7a/libmain.so"), "both real runtimes promoted, libmain untouched");
            // Catalogue repaired only the stand-in (still a proxy): promotion supersedes it.
            check(UnityEmbeddedRuntimePromotion.plan(z, Map.of("lib/armeabi-v7a/libunity.so", lib("libunity_proxy.so")), true).replacements.containsKey("lib/armeabi-v7a/libunity.so"), "stand-in-only catalogue repair superseded");
            // Catalogue already promoted a real runtime: left to the catalogue.
            check(!UnityEmbeddedRuntimePromotion.plan(z, Map.of("lib/armeabi-v7a/libunity.so", unity), true).replacements.containsKey("lib/armeabi-v7a/libunity.so"), "catalogue promotion kept");
        }
        // Ordinary Unity build (no stand-in) untouched; wrong payload rejected.
        files.put("lib/armeabi-v7a/libmono.so", mono); files.put("lib/armeabi-v7a/libunity.so", unity);
        try (ZipFile z = new ZipFile(apk(files).toFile())) { check(UnityEmbeddedRuntimePromotion.plan(z, Map.of(), true).status == CompatibilityEvidence.Status.SKIPPED, "real runtimes already in lib/: nothing to do"); }
        files.put("lib/armeabi-v7a/libmono.so", monoStub); files.put("assets/libs/armeabi-v7a/libmono.so", lib("libother.so")); files.remove("lib/armeabi-v7a/libunity.so");
        try (ZipFile z = new ZipFile(apk(files).toFile())) { var r = UnityEmbeddedRuntimePromotion.plan(z, Map.of(), true); check(r.status == CompatibilityEvidence.Status.UNSUPPORTED && r.replacements.isEmpty(), "mismatched payload left alone"); }
        System.out.println("Unity embedded runtime promotion: stand-in detection by SONAME, both runtimes, catalogue precedence/supersession, ordinary builds and mismatched payloads pass");
    }
}
