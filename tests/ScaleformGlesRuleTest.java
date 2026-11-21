package com.hcode91.pocketmicro;

import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ScaleformGlesRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String LIB = "lib/armeabi-v7a/libgfxunity3d.so";
    static final byte[] SHIM = {0x7f, 'E', 'L', 'F', 9};

    /** Minimal ELF32 ARM library: one PT_LOAD, PT_DYNAMIC, DT_HASH, undefined imports. */
    static byte[] elf(List<String> needed, List<String> imports, boolean versioned, String extraName, int extraOffsetIntoNeeded) {
        ByteArrayOutputStream strs = new ByteArrayOutputStream(); strs.write(0);
        Map<String, Integer> off = new LinkedHashMap<>();
        for (String s : concat(needed, imports, extraName == null ? List.of() : List.of(extraName))) { off.put(s, strs.size()); strs.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1)); strs.write(0); }
        byte[] str = strs.toByteArray();
        int nsym = 1 + imports.size() + (extraOffsetIntoNeeded >= 0 ? 1 : 0);
        int ehdr = 52, ph = ehdr, strOff = ph + 2 * 32, symOff = (strOff + str.length + 3) & ~3, hashOff = symOff + nsym * 16;
        int dynOff = hashOff + 4 * (2 + 1 + nsym);
        List<int[]> dyn = new ArrayList<>();
        for (String n : needed) dyn.add(new int[]{1, off.get(n)});
        dyn.add(new int[]{5, strOff}); dyn.add(new int[]{10, str.length}); dyn.add(new int[]{6, symOff}); dyn.add(new int[]{11, 16}); dyn.add(new int[]{4, hashOff});
        if (versioned) dyn.add(new int[]{0x6ffffffe, 0});
        dyn.add(new int[]{0, 0});
        int size = dynOff + dyn.size() * 8;
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, 0x464c457f); b.put(4, (byte) 1); b.put(5, (byte) 1); b.put(6, (byte) 1);
        b.putShort(16, (short) 3); b.putShort(18, (short) 40); b.putInt(20, 1); b.putInt(28, ph); b.putShort(40, (short) 52); b.putShort(42, (short) 32); b.putShort(44, (short) 2);
        b.putInt(ph, 1); b.putInt(ph + 4, 0); b.putInt(ph + 8, 0); b.putInt(ph + 16, size); b.putInt(ph + 20, size);
        b.putInt(ph + 32, 2); b.putInt(ph + 36, dynOff); b.putInt(ph + 40, dynOff); b.putInt(ph + 48, dyn.size() * 8);
        b.position(strOff); b.put(str);
        int s = symOff + 16;
        for (String i : imports) { b.putInt(s, off.get(i)); b.put(s + 12, (byte) 0x12); b.putShort(s + 14, (short) 0); s += 16; }
        if (extraOffsetIntoNeeded >= 0) { b.putInt(s, off.get(needed.get(needed.indexOf("libGLESv2.so"))) + extraOffsetIntoNeeded); s += 16; }
        b.putInt(hashOff, 1); b.putInt(hashOff + 4, nsym);
        for (int i = 0; i < dyn.size(); i++) { b.putInt(dynOff + 8 * i, dyn.get(i)[0]); b.putInt(dynOff + 8 * i + 4, dyn.get(i)[1]); }
        return b.array();
    }
    @SafeVarargs static List<String> concat(List<String>... l) { List<String> o = new ArrayList<>(); for (List<String> x : l) o.addAll(x); return o; }

    static byte[] scaleform() { return elf(List.of("libz.so", "libGLESv2.so", "liblog.so"), List.of("glGetIntegerv", "glClear"), false, null, -1); }

    public static void main(String[] args) {
        byte[] original = scaleform();
        List<String> entries = List.of(LIB, "lib/armeabi-v7a/libunity.so");
        ScaleformGlesRule.Result r = ScaleformGlesRule.apply(Map.of(LIB, original), entries, SHIM);
        check(r.status == CompatibilityEvidence.Status.VERIFIED, r.line());
        byte[] fixed = r.replacements.get(LIB);
        check(Arrays.equals(r.replacements.get("lib/armeabi-v7a/libpogles.so"), SHIM), "shim added beside the library");
        ScaleformGlesRule.Elf e = new ScaleformGlesRule.Elf(fixed);
        List<String> needed = new ArrayList<>(); for (int o : e.dyn.get(1)) needed.add(e.str(o));
        check(needed.equals(List.of("libz.so", "libpogles.so", "liblog.so")), "only the GL dependency renamed, order kept: " + needed);
        int diff = 0; for (int i = 0; i < original.length; i++) if (original[i] != fixed[i]) diff++;
        check(fixed.length == original.length && diff == 6, "same size, 6 name bytes changed (" + diff + ")");
        check(e.imports("glGetIntegerv") && e.imports("glClear"), "imports unchanged");

        // Reconverting an already patched library is reported, not stacked.
        ScaleformGlesRule.Result twice = ScaleformGlesRule.apply(Map.of(LIB, fixed), entries, SHIM);
        check(twice.status == CompatibilityEvidence.Status.SKIPPED && twice.replacements.isEmpty(), twice.line());
        // Refusals leave everything unchanged.
        check(ScaleformGlesRule.apply(Map.of(), entries, SHIM).status == CompatibilityEvidence.Status.SKIPPED, "no Scaleform plugin, nothing to do");
        check(ScaleformGlesRule.apply(Map.of(LIB, original), entries, null).status == CompatibilityEvidence.Status.UNKNOWN, "missing shim reported");
        ScaleformGlesRule.Result noGl = ScaleformGlesRule.apply(Map.of(LIB, elf(List.of("libz.so"), List.of("glClear"), false, null, -1)), entries, SHIM);
        check(noGl.status == CompatibilityEvidence.Status.UNSUPPORTED && noGl.replacements.isEmpty(), "library without libGLESv2 dependency: " + noGl.reason);
        ScaleformGlesRule.Result noQuery = ScaleformGlesRule.apply(Map.of(LIB, elf(List.of("libGLESv2.so"), List.of("glClear"), false, null, -1)), entries, SHIM);
        check(noQuery.status == CompatibilityEvidence.Status.UNSUPPORTED, "library that never asks the limit: " + noQuery.reason);
        ScaleformGlesRule.Result versioned = ScaleformGlesRule.apply(Map.of(LIB, elf(List.of("libGLESv2.so"), List.of("glGetIntegerv"), true, null, -1)), entries, SHIM);
        check(versioned.status == CompatibilityEvidence.Status.UNSUPPORTED, "symbol versioning refused: " + versioned.reason);
        ScaleformGlesRule.Result shared = ScaleformGlesRule.apply(Map.of(LIB, elf(List.of("libGLESv2.so"), List.of("glGetIntegerv"), false, "x", 3)), entries, SHIM);
        check(shared.status == CompatibilityEvidence.Status.UNSUPPORTED && shared.reason.contains("shares"), "a symbol name inside the dependency string is protected: " + shared.reason);
        ScaleformGlesRule.Result clash = ScaleformGlesRule.apply(Map.of(LIB, original), List.of(LIB, "lib/armeabi-v7a/libpogles.so"), SHIM);
        check(clash.status == CompatibilityEvidence.Status.UNKNOWN && clash.replacements.isEmpty(), "existing shim name refused");
        ScaleformGlesRule.Result arm64 = ScaleformGlesRule.apply(Map.of("lib/arm64-v8a/libgfxunity3d.so", original), entries, SHIM);
        check(arm64.status == CompatibilityEvidence.Status.UNSUPPORTED, "64-bit folders not touched");
        System.out.println("ScaleformGlesRuleTest OK");
    }
}
