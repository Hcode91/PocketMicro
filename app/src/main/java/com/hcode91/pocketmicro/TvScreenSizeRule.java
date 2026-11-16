package com.hcode91.pocketmicro;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction22b;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/**
 * ouya.tv-layouts.screen-size: third part of the TV-layout repair. The OUYA reported its TV as a
 * "large" screen; games that kept TV-only screens often also branch on that size in code. Word
 * Shaker's AFKLib b.e() returns true only for large/xlarge screenLayout, and when it is false
 * WordShakerActivity.onCreate calls setRequestedOrientation(PORTRAIT) - overriding the landscape
 * manifest, recreating the activity in portrait with the phone layout, and crashing on the
 * missing TV ImageView.
 *
 * Only used when {@link TvLayoutRule} changed resources. After every game-code read of
 * Configuration.screenLayout the size bits are set to LARGE (what the OUYA reported), so the
 * game's own size checks agree with the TV resources it now receives. Other screenLayout bits
 * (long, layout direction, round) are unchanged.
 */
public final class TvScreenSizeRule {
    public static final String ID = "ouya.tv-layouts.screen-size";
    static final String CONFIGURATION = "Landroid/content/res/Configuration;";
    static final int SIZE_MASK_CLEAR = -16 /* ~0x0f */, LARGE = 3;

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "no Configuration.screenLayout reads";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
    }

    private TvScreenSizeRule() {}
    public static Result inspect(Map<String, byte[]> dex) { return run(dex, false); }
    public static Result repair(Map<String, byte[]> dex) { return run(dex, true); }

    static boolean read(Instruction i) {
        if (i.getOpcode() != Opcode.IGET || !(i instanceof ReferenceInstruction)) return false;
        if (!(((ReferenceInstruction) i).getReference() instanceof FieldReference)) return false;
        FieldReference f = (FieldReference) ((ReferenceInstruction) i).getReference();
        return f.getDefiningClass().equals(CONFIGURATION) && f.getName().equals("screenLayout") && f.getType().equals("I");
    }

    /** True when the instruction after a read already forces the size (a previous repair). */
    static boolean forced(List<Instruction> code, int at) {
        if (at + 2 >= code.size()) return false;
        Instruction a = code.get(at + 1), b = code.get(at + 2);
        int reg = ((OneRegisterInstruction) code.get(at)).getRegisterA();
        return a.getOpcode() == Opcode.AND_INT_LIT8 && ((OneRegisterInstruction) a).getRegisterA() == reg && ((NarrowLiteralInstruction) a).getNarrowLiteral() == SIZE_MASK_CLEAR
                && b.getOpcode() == Opcode.OR_INT_LIT8 && ((OneRegisterInstruction) b).getRegisterA() == reg && ((NarrowLiteralInstruction) b).getNarrowLiteral() == LARGE;
    }

    private static boolean ours(String type) { return type.startsWith("Lcom/hcode91/pocketmicro/") || type.startsWith("Lcom/hcode91/pocketmicro/"); }

    private static Result run(Map<String, byte[]> input, boolean apply) {
        Result result = new Result();
        int sites = 0;
        try {
            for (var file : input.entrySet()) {
                DexBackedDexFile dex = new DexBackedDexFile(null, file.getValue());
                DexPool pool = apply ? new DexPool(dex.getOpcodes()) : null;
                boolean changed = false;
                for (ClassDef c : dex.getClasses()) {
                    if (ours(c.getType())) { if (apply) pool.internClass(c); continue; }
                    List<Method> methods = new ArrayList<>(); boolean classChanged = false;
                    for (Method m : c.getMethods()) {
                        if (m.getImplementation() == null) { methods.add(m); continue; }
                        List<Instruction> code = new ArrayList<>(); for (Instruction i : m.getImplementation().getInstructions()) code.add(i);
                        List<Integer> hits = new ArrayList<>();
                        for (int k = 0; k < code.size(); k++) if (read(code.get(k)) && !forced(code, k)) hits.add(k);
                        sites += hits.size();
                        if (!apply || hits.isEmpty()) { methods.add(m); continue; }
                        MutableMethodImplementation body = new MutableMethodImplementation(m.getImplementation());
                        Collections.reverse(hits);
                        for (int at : hits) {
                            int reg = ((OneRegisterInstruction) code.get(at)).getRegisterA();
                            body.addInstruction(at + 1, new BuilderInstruction22b(Opcode.OR_INT_LIT8, reg, reg, LARGE));
                            body.addInstruction(at + 1, new BuilderInstruction22b(Opcode.AND_INT_LIT8, reg, reg, SIZE_MASK_CLEAR));
                        }
                        methods.add(new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(), m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), body));
                        classChanged = true;
                    }
                    if (!apply) continue;
                    if (classChanged) {
                        pool.internClass(new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getFields(), methods));
                        changed = true;
                    } else pool.internClass(c);
                }
                if (apply && changed) { MemoryDataStore out = new MemoryDataStore(); pool.writeTo(out); result.replacements.put(file.getKey(), out.getData()); }
            }
            if (sites == 0) return result;
            if (apply) {
                Map<String, byte[]> verify = new LinkedHashMap<>(input); verify.putAll(result.replacements);
                if (inspect(verify).status != CompatibilityEvidence.Status.SKIPPED) throw new IllegalStateException("Postcondition: unforced screenLayout reads remain after repair");
            }
            result.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
            result.reason = sites + " Configuration.screenLayout read(s) now report a large screen, as the OUYA's TV did, so the game's own size checks "
                    + "(e.g. forcing portrait on phones) agree with the TV screens it now gets. Other layout bits unchanged.";
        } catch (Exception e) {
            result.replacements.clear(); result.status = CompatibilityEvidence.Status.UNKNOWN; result.reason = e + "; original DEX retained";
        }
        return result;
    }
}
