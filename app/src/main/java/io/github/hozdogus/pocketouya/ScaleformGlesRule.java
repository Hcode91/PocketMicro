package io.github.hozdogus.pocketouya;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ouya.gles-vertex-attribs (startup repair): Scaleform's Unity plugin (libgfxunity3d.so)
 * stores one "attribute enabled" byte per vertex attribute in a 16-byte array of its manager
 * (SFManagerImpl::SaveStateBlock: glGetIntegerv(GL_MAX_VERTEX_ATTRIBS), then
 * glGetVertexAttribiv(i, GL_VERTEX_ATTRIB_ARRAY_ENABLED) into manager+0x10+i). The OUYA's
 * Tegra 3 reported 16; modern GPUs report 32, so bytes 16-31 overwrite manager+0x20..0x2f
 * (render context and render-thread pointers) and the first frame crashes in
 * RTCommandQueue::PopCommand (Soul Fjord). The library's code is not changed: its
 * "libGLESv2.so" dependency is renamed to the same-length "libpogles.so", a small PocketOuya
 * shim that depends on libGLESv2.so (so every other GL function resolves as before) and only
 * caps the GL_MAX_VERTEX_ATTRIBS answer at the OUYA's 16.
 */
public final class ScaleformGlesRule {
    public static final String ID = "ouya.gles-vertex-attribs";
    static final String TARGET = "libgfxunity3d.so", FROM = "libGLESv2.so", TO = "libpogles.so";
    static final int PT_LOAD = 1, PT_DYNAMIC = 2, DT_NEEDED = 1, DT_STRTAB = 5, DT_SYMTAB = 6, DT_STRSZ = 10, DT_SYMENT = 11, DT_SONAME = 14, DT_RPATH = 15, DT_RUNPATH = 29;
    static final int DT_HASH = 4, DT_GNU_HASH = 0x6ffffef5, DT_VERNEED = 0x6ffffffe, DT_VERDEF = 0x6ffffffc;

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "no Scaleform Unity plugin";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
        String line() { return status + " " + ID + ": " + reason; }
    }

    private ScaleformGlesRule() {}

    static final class Elf {
        final ByteBuffer b; final List<int[]> loads = new ArrayList<>(); final Map<Integer, List<Integer>> dyn = new LinkedHashMap<>();
        int strtab = -1, strsz = -1, symtab = -1, syment = 16, nsyms = 0;
        Elf(byte[] data) {
            b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (data.length < 52 || b.getInt(0) != 0x464c457f || data[4] != 1 || data[5] != 1 || (b.getShort(18) & 0xffff) != 40) throw new IllegalStateException("not a 32-bit little-endian ARM library");
            int phoff = b.getInt(28), phentsize = b.getShort(42) & 0xffff, phnum = b.getShort(44) & 0xffff, dynOff = -1, dynSize = 0;
            for (int i = 0; i < phnum; i++) {
                int p = phoff + i * phentsize; if (p + 32 > data.length) throw new IllegalStateException("bad program header");
                int type = b.getInt(p);
                if (type == PT_LOAD) loads.add(new int[]{b.getInt(p + 4), b.getInt(p + 8), b.getInt(p + 16)});
                if (type == PT_DYNAMIC) { dynOff = b.getInt(p + 4); dynSize = b.getInt(p + 16); }
            }
            if (dynOff < 0) throw new IllegalStateException("no dynamic section");
            for (int p = dynOff; p + 8 <= dynOff + dynSize && p + 8 <= data.length; p += 8) {
                int tag = b.getInt(p), val = b.getInt(p + 4);
                if (tag == 0) break;
                dyn.computeIfAbsent(tag, k -> new ArrayList<>()).add(val);
            }
            strtab = file(first(DT_STRTAB)); strsz = first(DT_STRSZ);
            if (dyn.containsKey(DT_SYMTAB)) symtab = file(first(DT_SYMTAB));
            if (dyn.containsKey(DT_SYMENT)) syment = first(DT_SYMENT);
            if (dyn.containsKey(DT_HASH)) nsyms = b.getInt(file(first(DT_HASH)) + 4);
            if (strtab < 0 || strsz <= 0 || strtab + strsz > data.length) throw new IllegalStateException("bad dynamic string table");
        }
        int first(int tag) { List<Integer> v = dyn.get(tag); if (v == null) throw new IllegalStateException("missing dynamic tag " + tag); return v.get(0); }
        int file(int vaddr) { for (int[] l : loads) if (vaddr >= l[1] && vaddr < l[1] + l[2]) return vaddr - l[1] + l[0]; throw new IllegalStateException("address outside segments"); }
        String str(int off) { int s = strtab + off, e = s; while (e < strtab + strsz && b.get(e) != 0) e++; byte[] a = new byte[e - s]; for (int i = 0; i < a.length; i++) a[i] = b.get(s + i); return new String(a, StandardCharsets.ISO_8859_1); }
        /** Every string-table offset this library uses, so a rename cannot change another name. */
        List<Integer> references() {
            List<Integer> out = new ArrayList<>();
            for (int tag : new int[]{DT_NEEDED, DT_SONAME, DT_RPATH, DT_RUNPATH}) out.addAll(dyn.getOrDefault(tag, List.of()));
            if (symtab >= 0 && nsyms > 0) for (int i = 0; i < nsyms; i++) out.add(b.getInt(symtab + i * syment));
            return out;
        }
        boolean imports(String name) {
            if (symtab < 0 || nsyms <= 0) return false;
            for (int i = 0; i < nsyms; i++) { int s = symtab + i * syment; if ((b.getShort(s + 14) & 0xffff) == 0 && str(b.getInt(s)).equals(name)) return true; }
            return false;
        }
    }

    /** Returns the patched library, or throws with the reason it is not supported. */
    static byte[] patch(byte[] library) {
        Elf elf = new Elf(library);
        if (elf.dyn.containsKey(DT_VERNEED) || elf.dyn.containsKey(DT_VERDEF)) throw new IllegalStateException("symbol versioning present; dependency names left unchanged");
        if (!elf.dyn.containsKey(DT_HASH)) throw new IllegalStateException("no DT_HASH symbol count; dependency names left unchanged");
        Integer at = null;
        for (int off : elf.dyn.getOrDefault(DT_NEEDED, List.of())) { String n = elf.str(off); if (n.equals(TO)) return null; if (n.equals(FROM)) at = off; }
        if (at == null) throw new IllegalStateException("does not depend on " + FROM);
        if (!elf.imports("glGetIntegerv")) throw new IllegalStateException("does not import glGetIntegerv");
        for (int ref : elf.references()) if (ref > at && ref <= at + FROM.length()) throw new IllegalStateException("another name shares the " + FROM + " string");
        byte[] out = library.clone(); byte[] to = TO.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(to, 0, out, elf.strtab + at, to.length);
        return out;
    }

    /**
     * @param libraries current bytes of every lib/(abi)/libgfxunity3d.so (after other repairs)
     * @param entries   all APK entry names
     * @param shim      libpogles.so bytes (armeabi-v7a)
     */
    public static Result apply(Map<String, byte[]> libraries, Collection<String> entries, byte[] shim) {
        Result result = new Result();
        if (libraries.isEmpty()) return result;
        try {
            int patched = 0, already = 0; List<String> notes = new ArrayList<>();
            for (var e : libraries.entrySet()) {
                String dir = e.getKey().substring(0, e.getKey().lastIndexOf('/') + 1);
                if (!dir.equals("lib/armeabi-v7a/") && !dir.equals("lib/armeabi/")) { notes.add(e.getKey() + ": not a 32-bit ARM folder"); continue; }
                byte[] fixed;
                try { fixed = patch(e.getValue()); } catch (IllegalStateException bad) { notes.add(e.getKey() + ": " + bad.getMessage()); continue; }
                if (fixed == null) { already++; continue; }
                if (shim == null || shim.length == 0) throw new IllegalStateException("GPU-limit shim missing from this PocketOuya build");
                if (entries.contains(dir + TO)) throw new IllegalStateException("APK already contains " + dir + TO + "; reimport the original APK");
                result.replacements.put(e.getKey(), fixed);
                result.replacements.put(dir + TO, shim);
                patched++;
            }
            if (patched == 0) {
                result.status = already > 0 ? CompatibilityEvidence.Status.SKIPPED : CompatibilityEvidence.Status.UNSUPPORTED;
                result.reason = already > 0 ? "already uses the GPU-limit shim; reimport the original APK" : String.join("; ", notes);
                return result;
            }
            result.status = CompatibilityEvidence.Status.VERIFIED;
            result.reason = "Scaleform's Unity plugin keeps 16 vertex-attribute flags (the OUYA's GPU limit) but modern GPUs report 32, which overwrote its "
                    + "render-thread pointer on the first frame. " + patched + " " + TARGET + " now loads GL through " + TO
                    + " (same-length dependency rename; its code is unchanged), which caps GL_MAX_VERTEX_ATTRIBS at 16 and passes everything else to "
                    + FROM + "." + (notes.isEmpty() ? "" : " Not changed: " + String.join("; ", notes) + ".");
        } catch (Exception e) {
            result.replacements.clear();
            result.status = CompatibilityEvidence.Status.UNKNOWN; result.reason = e.getMessage() + "; not applied";
        }
        return result;
    }
}
