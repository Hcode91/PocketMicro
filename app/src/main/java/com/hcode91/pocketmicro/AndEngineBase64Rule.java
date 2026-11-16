package com.hcode91.pocketmicro;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction22s;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/**
 * andengine.base64-unsigned-read: AndEngine's Base64InputStream.read() returns the decoded byte
 * as a signed Java byte (0x8B comes back as -117) instead of 0..255. Android 4's GZIPInputStream
 * read its header in bulk and never saw it; Android 5+ reads single bytes and rejects values
 * outside -1..255, so gzip+base64 TMX maps fail to load ("Base64InputStream.read() returned value
 * out of range -1..255: -117") and the game later crashes drawing a layer with no texture
 * (Zombie Outbreak).
 *
 * After each byte-array read in Base64InputStream.read()I, the value is masked with 0xFF, which is
 * what the InputStream contract requires. The end-of-stream -1 path is a separate constant and
 * stays -1.
 */
public final class AndEngineBase64Rule {
    public static final String ID = "andengine.base64-unsigned-read";
    static final String STREAM = "Lorg/andengine/util/base64/Base64InputStream;";

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "AndEngine Base64InputStream absent";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
    }

    private AndEngineBase64Rule() {}
    public static Result inspect(Map<String, byte[]> dex) { return run(dex, false); }
    public static Result repair(Map<String, byte[]> dex) { return run(dex, true); }

    static boolean readByte(Method m) { return m.getName().equals("read") && m.getParameterTypes().isEmpty() && m.getReturnType().equals("I") && m.getImplementation() != null; }

    /** Indexes of AGET_BYTE not already followed by an 0xFF mask of the same register. */
    static List<Integer> unmasked(Method m) {
        List<Instruction> code = new ArrayList<>(); for (Instruction i : m.getImplementation().getInstructions()) code.add(i);
        List<Integer> out = new ArrayList<>();
        for (int k = 0; k < code.size(); k++) {
            if (code.get(k).getOpcode() != Opcode.AGET_BYTE) continue;
            int reg = ((OneRegisterInstruction) code.get(k)).getRegisterA();
            Instruction next = k + 1 < code.size() ? code.get(k + 1) : null;
            boolean masked = next != null && (next.getOpcode() == Opcode.AND_INT_LIT16 || next.getOpcode() == Opcode.AND_INT_LIT8)
                    && ((TwoRegisterInstruction) next).getRegisterA() == reg && ((TwoRegisterInstruction) next).getRegisterB() == reg
                    && ((NarrowLiteralInstruction) next).getNarrowLiteral() == 0xff;
            if (!masked) out.add(k);
        }
        return out;
    }

    private static Result run(Map<String, byte[]> input, boolean apply) {
        Result r = new Result();
        try {
            for (var file : input.entrySet()) {
                DexBackedDexFile dex = new DexBackedDexFile(null, file.getValue());
                ClassDef stream = null;
                for (ClassDef c : dex.getClasses()) if (c.getType().equals(STREAM)) stream = c;
                if (stream == null) continue;
                Method read = null;
                for (Method m : stream.getMethods()) if (readByte(m)) read = m;
                if (read == null) { r.status = CompatibilityEvidence.Status.UNSUPPORTED; r.reason = "Base64InputStream.read() not found; unchanged"; return r; }
                List<Integer> hits = unmasked(read);
                if (hits.isEmpty()) { r.reason = "Base64InputStream.read() already returns unsigned bytes"; return r; }
                if (apply) {
                    MutableMethodImplementation body = new MutableMethodImplementation(read.getImplementation());
                    List<Instruction> code = new ArrayList<>(); for (Instruction i : read.getImplementation().getInstructions()) code.add(i);
                    for (int k = hits.size() - 1; k >= 0; k--) {
                        int reg = ((OneRegisterInstruction) code.get(hits.get(k))).getRegisterA();
                        body.addInstruction(hits.get(k) + 1, new BuilderInstruction22s(Opcode.AND_INT_LIT16, reg, reg, 0xff));
                    }
                    Method fixed = new ImmutableMethod(read.getDefiningClass(), read.getName(), read.getParameters(), read.getReturnType(), read.getAccessFlags(), read.getAnnotations(), read.getHiddenApiRestrictions(), body);
                    DexPool pool = new DexPool(dex.getOpcodes());
                    for (ClassDef c : dex.getClasses()) {
                        if (!c.getType().equals(STREAM)) { pool.internClass(c); continue; }
                        List<Method> methods = new ArrayList<>(); for (Method m : c.getMethods()) methods.add(readByte(m) ? fixed : m);
                        pool.internClass(new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getFields(), methods));
                    }
                    MemoryDataStore out = new MemoryDataStore(); pool.writeTo(out); r.replacements.put(file.getKey(), out.getData());
                }
                r.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
                r.reason = "AndEngine Base64InputStream.read() returned signed bytes; " + hits.size() + " read(s) now masked to 0..255 so Android 5+ gzip accepts base64 TMX map data.";
                return r;
            }
        } catch (Exception e) {
            r.replacements.clear(); r.status = CompatibilityEvidence.Status.UNKNOWN; r.reason = e + "; original DEX retained";
        }
        return r;
    }
}
