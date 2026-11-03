package io.github.hozdogus.pocketouya;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/**
 * unity.embedded-runtime-promotion: some Unity 4.x OUYA builds ship small stand-in libraries in
 * lib/ABI/ (libmono.so / libunity.so whose SONAME is libmono_proxy.so / libunity_proxy.so) and the
 * real runtimes in assets/libs/ABI/. The stand-ins load the real runtimes with their own in-memory
 * loader, which recurses until the stack overflows on modern Android (Abigale, Boom Ya).
 *
 * When a stand-in is recognised by that SONAME and the matching real runtime (SONAME libX.so, ARM
 * ELF) is present in assets, the real runtime is put in lib/ in place of the stand-in so Android
 * loads it normally. Exact catalogue repairs run first and take precedence. The real runtimes
 * usually rewrite their own code when loading, so the game should be installed keeping its
 * original Android version (the conversion dialog ticks that automatically).
 */
public final class UnityEmbeddedRuntimePromotion {
    public static final String ID = "unity.embedded-runtime-promotion";
    static final String[] RUNTIMES = {"libmono", "libunity"};
    static final String[] ABIS = {"armeabi-v7a", "armeabi"};

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "No Unity stand-in runtimes";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
    }

    private UnityEmbeddedRuntimePromotion() {}

    /** @param alreadyRepaired catalogue output (path -> bytes); a catalogue result that is still a stand-in loader is superseded. */
    public static Result plan(ZipFile zip, Map<String, byte[]> alreadyRepaired, boolean apply) throws IOException {
        Result r = new Result();
        List<String> done = new ArrayList<>(), problems = new ArrayList<>();
        for (String abi : ABIS) for (String lib : RUNTIMES) {
            String path = "lib/" + abi + "/" + lib + ".so", payloadPath = "assets/libs/" + abi + "/" + lib + ".so";
            byte[] catalogue = alreadyRepaired.get(path);
            if (catalogue != null && !(lib + "_proxy.so").equals(soname(catalogue))) continue;   // catalogue already promoted a real runtime
            ZipEntry stub = zip.getEntry(path), payload = zip.getEntry(payloadPath);
            if (stub == null || payload == null) continue;
            if (stub.getSize() > 4L * 1024 * 1024 || payload.getSize() <= 0 || payload.getSize() > 48L * 1024 * 1024) { problems.add(path + ": size outside limits"); continue; }
            byte[] stubBytes = ApkRebuilder.readBounded(zip.getInputStream(stub), 4 * 1024 * 1024);
            if (!(lib + "_proxy.so").equals(soname(stubBytes))) continue;   // not a stand-in
            byte[] real = ApkRebuilder.readBounded(zip.getInputStream(payload), 48 * 1024 * 1024);
            if (!(lib + ".so").equals(soname(real))) { problems.add(payloadPath + ": not the matching ARM runtime"); continue; }
            if (apply) r.replacements.put(path, real);
            done.add(path + " <- " + payloadPath);
        }
        if (done.isEmpty() && problems.isEmpty()) return r;
        if (done.isEmpty()) { r.status = CompatibilityEvidence.Status.UNSUPPORTED; r.reason = String.join("; ", problems) + "; unchanged"; return r; }
        r.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
        r.reason = (apply ? "real Unity runtimes put in place of the stand-in loaders: " : "would put real Unity runtimes in place of the stand-in loaders: ") + String.join(", ", done)
                + (problems.isEmpty() ? "" : "; skipped " + String.join("; ", problems))
                + ". They rewrite their own code when loading: install keeping the original Android version.";
        return r;
    }

    /** DT_SONAME of an ELF32 little-endian ARM shared library, or null. */
    static String soname(byte[] lib) {
        if (lib.length < 52 || lib[0] != 0x7f || lib[1] != 'E' || lib[2] != 'L' || lib[3] != 'F' || lib[4] != 1 || lib[5] != 1) return null;
        ByteBuffer b = ByteBuffer.wrap(lib).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getShort(18) != 40) return null;
        int shOff = b.getInt(32), shNum = b.getShort(48) & 0xffff;
        if (shOff <= 0 || (long) shOff + 40L * shNum > lib.length) return null;
        for (int i = 0; i < shNum; i++) {
            int at = shOff + 40 * i;
            if (b.getInt(at + 4) != 6) continue;   // SHT_DYNAMIC
            int off = b.getInt(at + 16), size = b.getInt(at + 20), link = b.getInt(at + 24);
            if (link < 0 || link >= shNum || off < 0 || (long) off + size > lib.length) return null;
            int strOff = b.getInt(shOff + 40 * link + 16), strSize = b.getInt(shOff + 40 * link + 20);
            if (strOff < 0 || (long) strOff + strSize > lib.length) return null;
            for (int d = off; d + 8 <= off + size; d += 8) {
                int tag = b.getInt(d), val = b.getInt(d + 4);
                if (tag == 0) break;
                if (tag == 14 && val >= 0 && val < strSize) { int s = strOff + val, e = s; while (e < strOff + strSize && lib[e] != 0) e++; return new String(lib, s, e - s, StandardCharsets.US_ASCII); }
            }
        }
        return null;
    }
}
