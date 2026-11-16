package com.hcode91.pocketmicro;

import java.util.*;
import java.util.regex.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.MethodImplementationBuilder;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/**
 * android.gles-uniform-count: the array forms of GLES20/GLES30 glUniform*v and glUniformMatrix*fv
 * take a count of vectors/matrices. Android up to 4.x only checked the array against count, so
 * games that passed the number of floats ("count * 4") worked; Android 5+ checks count * size and
 * throws IllegalArgumentException("length - offset < count*N < needed") on the first frame
 * (XY Cannon). GL itself never reads more uniforms than the shader declares.
 *
 * Each such call is routed through a helper in the calling class that lowers count to the number
 * of whole vectors/matrices the array holds after offset, then makes the original call. Calls whose
 * count already fits are unchanged; null arrays and bad offsets still reach Android's own checks.
 */
public final class GlUniformCountRule {
    public static final String ID = "android.gles-uniform-count";
    static final String PREFIX = "pocketouyaGl_";
    static final Pattern VECTOR = Pattern.compile("glUniform([1-4])(f|i)v"), MATRIX = Pattern.compile("glUniformMatrix([2-4])fv");

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "no GLES uniform array calls";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
    }

    private GlUniformCountRule() {}
    public static Result inspect(Map<String, byte[]> dex) { return run(dex, false); }
    public static Result repair(Map<String, byte[]> dex) { return run(dex, true); }

    /** Components per counted element for a supported call, else 0. */
    static int size(MethodReference r) {
        String owner = r.getDefiningClass();
        if (!owner.equals("Landroid/opengl/GLES20;") && !owner.equals("Landroid/opengl/GLES30;")) return 0;
        List<String> p = new ArrayList<>(); for (CharSequence c : r.getParameterTypes()) p.add(c.toString());
        if (!r.getReturnType().equals("V")) return 0;
        Matcher v = VECTOR.matcher(r.getName()), m = MATRIX.matcher(r.getName());
        if (v.matches() && p.equals(List.of("I", "I", v.group(2).equals("f") ? "[F" : "[I", "I"))) return Integer.parseInt(v.group(1));
        if (m.matches() && p.equals(List.of("I", "I", "Z", "[F", "I"))) { int n = Integer.parseInt(m.group(1)); return n * n; }
        return 0;
    }

    static MethodReference target(Instruction i) {
        if (i.getOpcode() != Opcode.INVOKE_STATIC || !(i instanceof ReferenceInstruction) || !(((ReferenceInstruction) i).getReference() instanceof MethodReference)) return null;
        MethodReference r = (MethodReference) ((ReferenceInstruction) i).getReference();
        return size(r) > 0 ? r : null;
    }

    static String helperName(MethodReference r) { return PREFIX + r.getName() + (r.getDefiningClass().endsWith("GLES30;") ? "_30" : ""); }
    static List<String> params(MethodReference r) { List<String> p = new ArrayList<>(); for (CharSequence c : r.getParameterTypes()) p.add(c.toString()); return p; }

    /** Helper: v0 scratch; parameters follow (count = p1, array/offset = last two). */
    static Method helper(String owner, MethodReference r) {
        List<String> p = params(r); int n = size(r), args = p.size(), regs = 1 + args;
        int count = 2, array = regs - 2, offset = regs - 1;
        MethodImplementationBuilder b = new MethodImplementationBuilder(regs);
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, array, b.getLabel("call")));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_LTZ, offset, b.getLabel("call")));
        b.addInstruction(new BuilderInstruction12x(Opcode.ARRAY_LENGTH, 0, array));
        b.addInstruction(new BuilderInstruction23x(Opcode.SUB_INT, 0, 0, offset));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_LTZ, 0, b.getLabel("call")));
        if (n > 1) b.addInstruction(new BuilderInstruction22b(Opcode.DIV_INT_LIT8, 0, 0, n));
        b.addInstruction(new BuilderInstruction22t(Opcode.IF_LE, count, 0, b.getLabel("call")));
        b.addInstruction(new BuilderInstruction12x(Opcode.MOVE, count, 0));
        b.addLabel("call");
        int[] reg = new int[5]; for (int k = 0; k < args; k++) reg[k] = 1 + k;
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, args, reg[0], reg[1], reg[2], reg[3], reg[4], new ImmutableMethodReference(r.getDefiningClass(), r.getName(), p, "V")));
        b.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        List<ImmutableMethodParameter> mp = new ArrayList<>(); for (String t : p) mp.add(new ImmutableMethodParameter(t, Set.of(), null));
        return new ImmutableMethod(owner, helperName(r), mp, "V", 0x100a, Set.of(), Set.of(), b.getMethodImplementation());
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
                    if (ours(c.getType()) || AccessFlags.INTERFACE.isSet(c.getAccessFlags())) { if (apply) pool.internClass(c); continue; }
                    Set<String> existing = new HashSet<>(); for (Method m : c.getMethods()) existing.add(m.getName());
                    List<Method> methods = new ArrayList<>(); Map<String, MethodReference> needed = new LinkedHashMap<>(); boolean classChanged = false;
                    for (Method m : c.getMethods()) {
                        if (m.getImplementation() == null || m.getName().startsWith(PREFIX)) { methods.add(m); continue; }
                        List<Instruction> original = new ArrayList<>(); List<Integer> hits = new ArrayList<>();
                        for (Instruction i : m.getImplementation().getInstructions()) { if (target(i) != null) hits.add(original.size()); original.add(i); }
                        sites += hits.size();
                        if (!apply || hits.isEmpty()) { methods.add(m); continue; }
                        MutableMethodImplementation body = new MutableMethodImplementation(m.getImplementation());
                        for (int at : hits) {
                            Instruction i = original.get(at); MethodReference r = target(i); String name = helperName(r);
                            if (existing.contains(name) && !needed.containsKey(name)) throw new IllegalStateException("Helper name collision in " + c.getType());
                            needed.put(name, r);
                            var ref = new ImmutableMethodReference(c.getType(), name, params(r), "V");
                            if (i instanceof FiveRegisterInstruction) {
                                var f = (FiveRegisterInstruction) i;
                                body.replaceInstruction(at, new BuilderInstruction35c(Opcode.INVOKE_STATIC, f.getRegisterCount(), f.getRegisterC(), f.getRegisterD(), f.getRegisterE(), f.getRegisterF(), f.getRegisterG(), ref));
                            } else {
                                var rr = (RegisterRangeInstruction) i;
                                body.replaceInstruction(at, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, rr.getStartRegister(), rr.getRegisterCount(), ref));
                            }
                        }
                        methods.add(new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(), m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), body));
                        classChanged = true;
                    }
                    if (!apply) continue;
                    if (classChanged) {
                        for (var e : needed.entrySet()) methods.add(helper(c.getType(), e.getValue()));
                        pool.internClass(new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getFields(), methods));
                        changed = true;
                    } else pool.internClass(c);
                }
                if (apply && changed) { MemoryDataStore out = new MemoryDataStore(); pool.writeTo(out); result.replacements.put(file.getKey(), out.getData()); }
            }
            if (sites == 0) return result;
            if (apply) {
                Map<String, byte[]> verify = new LinkedHashMap<>(input); verify.putAll(result.replacements);
                if (inspect(verify).status != CompatibilityEvidence.Status.SKIPPED) throw new IllegalStateException("Postcondition: uniform array calls remain after repair");
            }
            result.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
            result.reason = sites + " GLES glUniform*v/glUniformMatrix*fv array call(s). A count larger than the array holds (Android 4.x accepted a float count) "
                    + "is lowered to the whole vectors/matrices available instead of throwing on Android 5+; fitting counts are unchanged.";
        } catch (Exception e) {
            result.replacements.clear(); result.status = CompatibilityEvidence.Status.UNKNOWN; result.reason = e + "; original DEX retained";
        }
        return result;
    }
}
