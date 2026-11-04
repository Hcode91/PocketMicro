package io.github.hozdogus.pocketouya;

import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;

public final class LowAddressRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String MAIN = GeneralCompatibilityTest.LAUNCHER, OTHER = "Lorg/example/Settings;", BASE = "Lcom/unity3d/player/UnityPlayerActivity;";
    static final byte[] LIB = {0x7f, 'E', 'L', 'F', 1, 1, 1};
    static final List<String> V7 = List.of("classes.dex", "lib/armeabi-v7a/libunity.so", "assets/x");

    static Method create(String owner) {
        var body = new ImmutableMethodImplementation(2, List.of(
                new ImmutableInstruction35c(Opcode.INVOKE_SUPER, 2, 0, 1, 0, 0, 0, new ImmutableMethodReference(BASE, "onCreate", List.of("Landroid/os/Bundle;"), "V")),
                new ImmutableInstruction10x(Opcode.RETURN_VOID)), List.of(), List.of());
        return new ImmutableMethod(owner, "onCreate", GeneralCompatibilityTest.params("Landroid/os/Bundle;"), "V", AccessFlags.PROTECTED.getValue(), Set.of(), Set.of(), body);
    }

    static ClassDef find(byte[] dex, String type) {
        for (ClassDef c : new DexBackedDexFile(null, dex).getClasses()) if (c.getType().equals(type)) return c;
        throw new AssertionError("missing " + type);
    }

    static List<String> calls(ClassDef c) {
        List<String> out = new ArrayList<>();
        for (Method m : c.getMethods()) if (m.getName().equals("onCreate")) for (Instruction i : m.getImplementation().getInstructions()) {
            if (i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof MethodReference) {
                MethodReference r = (MethodReference) ((ReferenceInstruction) i).getReference();
                out.add(i.getOpcode().name + " " + r.getDefiningClass() + "->" + r.getName());
            } else out.add(i.getOpcode().name);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        String reserve = "invoke-static " + LowAddressRule.HELPER + "->reserve";
        // Launcher with its own onCreate, another activity, and a launcher in another DEX without onCreate.
        Map<String, byte[]> dex = new LinkedHashMap<>();
        dex.put("classes.dex", GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls(MAIN, BASE, List.of(create(MAIN))), GeneralCompatibilityTest.cls(OTHER, BASE, List.of(create(OTHER)))));
        dex.put("classes2.dex", GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls("Lorg/example/Second;", BASE, List.of())));
        Set<String> launchers = Set.of(MAIN, "Lorg/example/Second;");

        LowAddressRule.Result r = LowAddressRule.apply(dex, launchers, V7, LIB);
        check(r.status == CompatibilityEvidence.Status.VERIFIED && r.needsBridge, r.line());
        check(r.reason.contains("2 launch activities"), r.reason);
        check(Arrays.equals(r.replacements.get("lib/armeabi-v7a/libpocketouya_lowaddr.so"), LIB), "helper library added beside the game's v7a libraries");
        check(calls(find(r.replacements.get("classes.dex"), MAIN)).equals(List.of(reserve, "invoke-super " + BASE + "->onCreate", "return-void")), "reserve runs first, original body follows");
        check(calls(find(r.replacements.get("classes.dex"), OTHER)).equals(List.of("invoke-super " + BASE + "->onCreate", "return-void")), "other activities untouched");
        check(calls(find(r.replacements.get("classes2.dex"), "Lorg/example/Second;")).equals(List.of(reserve, "invoke-super " + BASE + "->onCreate", "return-void")), "missing onCreate added, calling super");

        // Reconverting an already converted game is refused rather than stacked.
        Map<String, byte[]> again = new LinkedHashMap<>(dex); r.replacements.forEach((k, v) -> { if (k.endsWith(".dex")) again.put(k, v); });
        LowAddressRule.Result twice = LowAddressRule.apply(again, launchers, V7, LIB);
        check(twice.status == CompatibilityEvidence.Status.SKIPPED && twice.replacements.isEmpty(), twice.line());
        List<String> withLib = new ArrayList<>(V7); withLib.add("lib/armeabi-v7a/libpocketouya_lowaddr.so");
        check(LowAddressRule.apply(dex, launchers, withLib, LIB).status == CompatibilityEvidence.Status.UNKNOWN, "existing helper library refused");

        // ABI checks.
        check(LowAddressRule.apply(dex, launchers, List.of("lib/armeabi/libgame.so"), LIB).replacements.containsKey("lib/armeabi/libpocketouya_lowaddr.so"), "armeabi-only games get the helper in lib/armeabi");
        LowAddressRule.Result mixed = LowAddressRule.apply(dex, launchers, List.of("lib/armeabi-v7a/a.so", "lib/arm64-v8a/a.so"), LIB);
        check(mixed.status == CompatibilityEvidence.Status.UNSUPPORTED && mixed.replacements.isEmpty() && !mixed.needsBridge, "64-bit libraries refused: " + mixed.reason);
        check(LowAddressRule.apply(dex, launchers, List.of("classes.dex"), LIB).status == CompatibilityEvidence.Status.UNSUPPORTED, "Java-only games refused");
        check(LowAddressRule.apply(dex, Set.of("Lmissing/Activity;"), V7, LIB).status == CompatibilityEvidence.Status.UNSUPPORTED, "no launcher found");
        check(LowAddressRule.apply(dex, launchers, V7, null).status == CompatibilityEvidence.Status.UNKNOWN, "missing library reported");

        // End to end through the app's install path: touch hook and low-address hook combine.
        Path temp = Files.createTempDirectory("low-address-");
        try {
            Path original = temp.resolve("original.apk"), converted = temp.resolve("converted.apk");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(original))) {
                out.putNextEntry(new ZipEntry("AndroidManifest.xml")); out.write(GeneralCompatibilityTest.manifest(false)); out.closeEntry();
                out.putNextEntry(new ZipEntry("classes.dex")); out.write(GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls(MAIN, "Landroid/app/Activity;", List.of(create(MAIN))))); out.closeEntry();
                out.putNextEntry(new ZipEntry("lib/armeabi-v7a/libunity.so")); out.write(new byte[]{1, 2, 3}); out.closeEntry();
            }
            byte[] helper = GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls("Lio/github/hcode91/pocketouya/bridge/TestFixture;", "Ljava/lang/Object;", List.of()));
            ManifestPatcher.Result plain = ApkRebuilder.prepareForInstall(original.toFile(), converted.toFile(), helper, false, false, 23);
            check(!plain.compatibilityReport.contains(LowAddressRule.ID), "not applied unless asked");
            try (ZipFile zip = new ZipFile(converted.toFile())) { check(zip.getEntry("lib/armeabi-v7a/libpocketouya_lowaddr.so") == null, "no helper library unless asked"); }

            ManifestPatcher.Result low = ApkRebuilder.prepareForInstall(original.toFile(), converted.toFile(), helper, false, false, 23, LIB);
            check(low.compatibilityReport.contains("VERIFIED " + LowAddressRule.ID), low.compatibilityReport);
            try (ZipFile zip = new ZipFile(converted.toFile())) {
                ZipEntry lib = zip.getEntry("lib/armeabi-v7a/libpocketouya_lowaddr.so");
                check(lib != null && lib.getMethod() == ZipEntry.STORED && Arrays.equals(LIB, ApkRebuilder.readAll(zip.getInputStream(lib))), "helper library stored uncompressed");
                check(Arrays.equals(helper, ApkRebuilder.readAll(zip.getInputStream(zip.getEntry("classes2.dex")))), "PocketOuya helper DEX appended");
                List<String> body = calls(find(ApkRebuilder.readAll(zip.getInputStream(zip.getEntry("classes.dex"))), MAIN));
                check(body.get(0).equals(reserve), "reserve is the first call even with the touch wrapper: " + body);
                check(body.stream().anyMatch(s -> s.contains("TouchControls;->watch")), "touch hook still present: " + body);
            }
        } finally {
            try (var walk = Files.walk(temp)) { walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
        System.out.println("LowAddressRuleTest OK");
    }
}
