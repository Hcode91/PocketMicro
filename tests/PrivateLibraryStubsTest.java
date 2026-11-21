package com.hcode91.pocketmicro;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class PrivateLibraryStubsTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    /** Minimal ELF32 ARM library with DT_NEEDED entries and undefined dynamic symbols (section headers only). */
    static byte[] gameLibrary(List<String> needed, Map<String, Integer> undefined /* name -> 1 object / 2 func / 0x22 weak func */) {
        ByteArrayOutputStream str = new ByteArrayOutputStream(); str.write(0);
        Map<String, Integer> off = new HashMap<>();
        for (String s : concat(needed, new ArrayList<>(undefined.keySet()))) { off.put(s, str.size()); byte[] b = s.getBytes(StandardCharsets.US_ASCII); str.write(b, 0, b.length); str.write(0); }
        byte[] strtab = str.toByteArray();
        int strOff = 52, symOff = align(strOff + strtab.length), nsym = 1 + undefined.size(), dynOff = symOff + 16 * nsym, dynSize = 8 * (needed.size() + 1), shOff = align(dynOff + dynSize);
        ByteBuffer b = ByteBuffer.allocate(shOff + 40 * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{0x7f, 'E', 'L', 'F', 1, 1, 1, 0}); b.putShort(16, (short) 3).putShort(18, (short) 40).putInt(20, 1).putInt(32, shOff).putShort(46, (short) 40).putShort(48, (short) 4);
        b.position(strOff); b.put(strtab);
        int i = 1;
        for (var e : undefined.entrySet()) { int at = symOff + 16 * i++; int kind = e.getValue(); b.putInt(at, off.get(e.getKey())).putInt(at + 8, kind == 1 ? 4 : 0).put(at + 12, (byte) (kind == 0x22 ? 0x22 : (0x10 | kind))).putShort(at + 14, (short) 0); }
        for (int n = 0; n < needed.size(); n++) b.putInt(dynOff + 8 * n, 1).putInt(dynOff + 8 * n + 4, off.get(needed.get(n)));
        sh(b, shOff, 1, 3, strOff, strtab.length, 0);   // .dynstr
        sh(b, shOff, 2, 11, symOff, 16 * nsym, 1);      // .dynsym -> 1
        sh(b, shOff, 3, 6, dynOff, dynSize, 1);         // .dynamic -> 1
        return b.array();
    }
    static void sh(ByteBuffer b, int shOff, int i, int type, int off, int size, int link) { int at = shOff + 40 * i; b.putInt(at + 4, type).putInt(at + 16, off).putInt(at + 20, size).putInt(at + 24, link); }
    static int align(int v) { return (v + 3) & ~3; }
    @SafeVarargs static List<String> concat(List<String>... l) { List<String> o = new ArrayList<>(); for (List<String> x : l) o.addAll(x); return o; }

    /** SysV hash lookup in a generated placeholder, as the dynamic linker does it. */
    static boolean exports(byte[] so, String name, int expectedType) {
        ByteBuffer b = ByteBuffer.wrap(so).order(ByteOrder.LITTLE_ENDIAN);
        int shOff = b.getInt(32), shNum = b.getShort(48);
        int hash = -1, sym = -1, str = -1;
        for (int i = 0; i < shNum; i++) { int at = shOff + 40 * i, t = b.getInt(at + 4); if (t == 5) hash = b.getInt(at + 16); if (t == 11) { sym = b.getInt(at + 16); str = b.getInt(shOff + 40 * b.getInt(at + 24) + 16); } }
        int nb = b.getInt(hash), idx = b.getInt(hash + 8 + 4 * (int) (StubLibraryWriter.elfHash(name) % nb));
        while (idx != 0) {
            int at = sym + 16 * idx, n = str + b.getInt(at), e = n; while (so[e] != 0) e++;
            if (new String(so, n, e - n, StandardCharsets.US_ASCII).equals(name)) return (so[at + 12] & 15) == expectedType && b.getShort(at + 14) != 0;
            idx = b.getInt(hash + 8 + 4 * nb + 4 * idx);
        }
        return false;
    }

    public static void main(String[] args) {
        String omx = "_ZN7android9OMXClientC1Ev", connect = "_ZN7android9OMXClient7connectEv", mime = "_ZN7android24MEDIA_MIMETYPE_AUDIO_AACE", weak = "_ZN7android4WeakEv";
        Map<String, Integer> syms = new LinkedHashMap<>(); syms.put(omx, 2); syms.put(connect, 2); syms.put(mime, 1); syms.put(weak, 0x22); syms.put("memcpy", 2); syms.put("_Znwj", 2);
        Map<String, byte[]> libs = new LinkedHashMap<>();
        libs.put("lib/armeabi-v7a/libCore.so", gameLibrary(List.of("libc.so", "libstagefright.so", "libutils.so", "libGLESv2.so", "libbundled.so"), syms));
        libs.put("lib/armeabi-v7a/libbundled.so", gameLibrary(List.of("libc.so"), Map.of()));
        libs.put("lib/x86/libCore.so", gameLibrary(List.of("libstagefright.so"), Map.of(omx, 2)));

        var scan = PrivateLibraryStubs.plan(libs, false);
        check(scan.status == CompatibilityEvidence.Status.SUPPORTED && scan.additions.isEmpty(), "inspect only: " + scan.reason);
        var plan = PrivateLibraryStubs.plan(libs, true);
        check(plan.status == CompatibilityEvidence.Status.VERIFIED, plan.reason);
        check(plan.additions.keySet().equals(Set.of("lib/armeabi-v7a/libstagefright.so", "lib/armeabi-v7a/libutils.so")), "placeholders only for needed, unbundled private libs in ARM dirs: " + plan.additions.keySet());
        byte[] so = plan.additions.get("lib/armeabi-v7a/libstagefright.so");
        check(exports(so, omx, 2) && exports(so, connect, 2), "function symbols resolvable through the hash table");
        check(exports(so, mime, 1), "data object resolvable and typed OBJECT");
        check(!exports(so, weak, 2) && !exports(so, "memcpy", 2) && !exports(so, "_Znwj", 2), "weak imports and public/libc++ symbols not exported");
        check(!NativeStartupChecks.hasTextRelocations(so), "placeholder has no text relocations");
        check(PrivateLibraryStubs.imports(so) != null, "placeholder parses as an ARM ELF library");

        // No android:: imports or nothing private needed: no placeholders.
        check(PrivateLibraryStubs.plan(Map.of("lib/armeabi-v7a/libgame.so", gameLibrary(List.of("libc.so", "liblog.so"), Map.of("memcpy", 2))), true).additions.isEmpty(), "ordinary game library untouched");
        check(PrivateLibraryStubs.plan(Map.of("lib/armeabi-v7a/libbad.so", new byte[]{1, 2, 3}), true).status == CompatibilityEvidence.Status.SKIPPED, "non-ELF ignored");

        // Keep-original-target policy leaves the manifest version alone; normal policy still raises it.
        byte[] manifest;
        try { manifest = GeneralCompatibilityTest.manifest(false); } catch (Exception e) { throw new AssertionError(e); }
        var kept = ManifestPatcher.patch(manifest, ManifestPatcher.KEEP_TARGET);
        check(kept.oldTarget == 16 && kept.newTarget == 16, "original target kept: " + kept.newTarget);
        check(ManifestPatcher.patch(manifest, 23).newTarget == 23, "normal conversion still raises the target");
        try { ManifestPatcher.patch(manifest, 7); throw new AssertionError("odd policy accepted"); } catch (IllegalArgumentException expected) {}
        System.out.println("Removed private libraries: NEEDED/unbundled detection, function/object/weak symbols, hash-table lookup, no TEXTREL, keep-original-target policy pass");
    }
}
