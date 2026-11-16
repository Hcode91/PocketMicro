package com.hcode91.pocketmicro;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * android.removed-private-libs: old games link directly against private Android system
 * libraries (libstagefright, libui, libutils, ...) and import C++ symbols that later Android
 * versions removed (OMXClient, the old GraphicBuffer, MetaData, ...). Android binds every import
 * at load time, so one missing symbol stops the whole engine library from loading (Adobe AIR's
 * libCore.so on Android 14).
 *
 * For each ARM ABI directory, when a game library DT_NEEDs one of those private libraries and the
 * APK does not bundle it, this rule adds a placeholder with that name, built by
 * {@link StubLibraryWriter}, exporting every android:: symbol the game's libraries import. The
 * app's own library directory is searched first, so the placeholder is used. Placeholder
 * functions return their first argument, so status checks fail and engines take their fallback
 * paths (e.g. no hardware video decoding). Nothing else in the APK changes.
 */
public final class PrivateLibraryStubs {
    public static final String ID = "android.removed-private-libs";
    /** Private platform libraries old NDK games linked against (not part of the public NDK). */
    static final Set<String> PRIVATE = Set.of("libstagefright.so", "libui.so", "libutils.so", "libcutils.so", "libgui.so", "libmedia.so",
            "libbinder.so", "libskia.so", "libandroid_runtime.so", "libnativehelper.so", "libsurfaceflinger_client.so",
            "libstagefright_foundation.so", "libhardware.so", "libpixelflinger.so", "libcamera_client.so", "libstagefright_omx.so");
    static final String[] ABIS = {"armeabi-v7a", "armeabi"};

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "No removed private Android libraries needed";
        /** New APK entries (path -> bytes); empty when only inspecting. */
        public final Map<String, byte[]> additions = new LinkedHashMap<>();
    }

    static final class Imports { final Set<String> needed = new LinkedHashSet<>(); final Set<String> functions = new TreeSet<>(); final Map<String, Integer> objects = new TreeMap<>(); }

    private PrivateLibraryStubs() {}

    /** @param libraries APK path -> bytes for the native libraries (lib/ABI/name.so) */
    public static Result plan(Map<String, byte[]> libraries, boolean apply) {
        Result r = new Result();
        List<String> lines = new ArrayList<>();
        int symbols = 0;
        try {
            for (String abi : ABIS) {
                String dir = "lib/" + abi + "/";
                Set<String> bundled = new HashSet<>(); Imports all = new Imports();
                for (var e : libraries.entrySet()) {
                    if (!e.getKey().startsWith(dir) || e.getKey().indexOf('/', dir.length()) >= 0) continue;
                    bundled.add(e.getKey().substring(dir.length()));
                    Imports im = imports(e.getValue());
                    if (im == null) continue;
                    all.needed.addAll(im.needed); all.functions.addAll(im.functions);
                    for (var o : im.objects.entrySet()) all.objects.merge(o.getKey(), o.getValue(), Math::max);
                }
                List<String> stubs = new ArrayList<>();
                for (String n : all.needed) if (PRIVATE.contains(n) && !bundled.contains(n)) stubs.add(n);
                if (stubs.isEmpty() || (all.functions.isEmpty() && all.objects.isEmpty())) continue;
                symbols = Math.max(symbols, all.functions.size() + all.objects.size());
                for (String n : stubs) if (apply) r.additions.put(dir + n, StubLibraryWriter.write(n, all.functions, all.objects));
                lines.add(abi + ": " + String.join(", ", stubs));
            }
        } catch (RuntimeException e) {
            r.additions.clear(); r.status = CompatibilityEvidence.Status.UNKNOWN; r.reason = "library parse failed (" + e + "); nothing added"; return r;
        }
        if (lines.isEmpty()) return r;
        r.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
        r.reason = (apply ? "added placeholder libraries " : "would add placeholder libraries ") + String.join("; ", lines) + " exporting " + symbols
                + " removed Android symbols. Placeholder calls report failure, so engines skip features such as hardware video decoding.";
        return r;
    }

    /** NEEDED names and undefined, non-weak android:: imports of an ELF32 ARM shared library; null if not one. */
    static Imports imports(byte[] lib) {
        if (lib.length < 52 || lib[0] != 0x7f || lib[1] != 'E' || lib[2] != 'L' || lib[3] != 'F' || lib[4] != 1 || lib[5] != 1) return null;
        ByteBuffer b = ByteBuffer.wrap(lib).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getShort(18) != 40) return null;
        int shOff = b.getInt(32), shEnt = b.getShort(46) & 0xffff, shNum = b.getShort(48) & 0xffff;
        if (shOff <= 0 || shEnt != 40 || shNum == 0 || (long) shOff + 40L * shNum > lib.length) return null;
        Imports im = new Imports();
        for (int i = 0; i < shNum; i++) {
            int at = shOff + 40 * i, type = b.getInt(at + 4), off = b.getInt(at + 16), size = b.getInt(at + 20), link = b.getInt(at + 24);
            if (link < 0 || link >= shNum) continue;
            int strAt = shOff + 40 * link, strOff = b.getInt(strAt + 16), strSize = b.getInt(strAt + 20);
            if (off < 0 || size < 0 || (long) off + size > lib.length || strOff < 0 || (long) strOff + strSize > lib.length) continue;
            if (type == 6) { // SHT_DYNAMIC
                for (int d = off; d + 8 <= off + size; d += 8) { int tag = b.getInt(d); if (tag == 0) break; if (tag == 1) im.needed.add(str(lib, strOff, strSize, b.getInt(d + 4))); }
            } else if (type == 11) { // SHT_DYNSYM
                for (int s = off + 16; s + 16 <= off + size; s += 16) {
                    int shndx = b.getShort(s + 14) & 0xffff, info = lib[s + 12] & 0xff, bind = info >>> 4, stype = info & 15;
                    if (shndx != 0 || bind != 1) continue;   // undefined, STB_GLOBAL only (weak imports may stay missing)
                    String name = str(lib, strOff, strSize, b.getInt(s));
                    if (!name.startsWith("_Z") || !name.contains("7android")) continue;
                    if (stype == 1) im.objects.merge(name, b.getInt(s + 8), Math::max); else im.functions.add(name);
                }
            }
        }
        return im;
    }

    private static String str(byte[] lib, int base, int size, int index) {
        if (index < 0 || index >= size) return "";
        int start = base + index, end = start;
        while (end < base + size && lib[end] != 0) end++;
        return new String(lib, start, end - start, StandardCharsets.US_ASCII);
    }
}
