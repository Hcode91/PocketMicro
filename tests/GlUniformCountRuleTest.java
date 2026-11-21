package com.hcode91.pocketmicro;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;

public final class GlUniformCountRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String GAME = "Lcom/example/Renderer;", GL = "Landroid/opengl/GLES20;";
    static final ImmutableMethodReference VEC4 = new ImmutableMethodReference(GL, "glUniform4fv", List.of("I", "I", "[F", "I"), "V"),
            MAT4 = new ImmutableMethodReference(GL, "glUniformMatrix4fv", List.of("I", "I", "Z", "[F", "I"), "V"),
            OTHER = new ImmutableMethodReference(GL, "glUniform4f", List.of("I", "F", "F", "F", "F"), "V");

    static Map<String, byte[]> input() throws Exception {
        var body = new ImmutableMethodImplementation(6, List.of(
                new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 4, 0, 1, 2, 3, 0, VEC4),
                new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 5, 0, 1, 4, 2, 3, MAT4),
                new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 5, 0, 1, 1, 1, 1, OTHER),
                new ImmutableInstruction10x(Opcode.RETURN_VOID)), List.of(), List.of());
        Method draw = new ImmutableMethod(GAME, "onDrawFrame", List.of(), "V", 1, Set.of(), Set.of(), body);
        return new LinkedHashMap<>(Map.of("classes.dex", GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls(GAME, "Ljava/lang/Object;", List.of(draw)))));
    }

    static Method find(byte[] dex, String name) {
        for (ClassDef c : new DexBackedDexFile(null, dex).getClasses()) for (Method m : c.getMethods()) if (m.getName().equals(name)) return m;
        throw new AssertionError("missing " + name);
    }

    /** Runs the helper; returns the arguments it passes to GLES. */
    static Object[] run(Method helper, Object... args) {
        var impl = helper.getImplementation();
        Map<Integer, Instruction> code = new HashMap<>(); int at = 0;
        for (Instruction i : impl.getInstructions()) { code.put(at, i); at += i.getCodeUnits(); }
        Object[] r = new Object[impl.getRegisterCount()];
        for (int k = 0; k < args.length; k++) r[r.length - args.length + k] = args[k];
        Object[] called = null; int pc = 0;
        for (int step = 0; step < 50; step++) {
            Instruction i = code.get(pc); int next = pc + i.getCodeUnits();
            int a = i instanceof OneRegisterInstruction ? ((OneRegisterInstruction) i).getRegisterA() : -1;
            switch (i.getOpcode()) {
                case IF_EQZ: if (r[a] == null) next = pc + ((OffsetInstruction) i).getCodeOffset(); break;
                case IF_LTZ: if ((int) r[a] < 0) next = pc + ((OffsetInstruction) i).getCodeOffset(); break;
                case IF_LE: if ((int) r[a] <= (int) r[((TwoRegisterInstruction) i).getRegisterB()]) next = pc + ((OffsetInstruction) i).getCodeOffset(); break;
                case ARRAY_LENGTH: r[a] = ((float[]) r[((TwoRegisterInstruction) i).getRegisterB()]).length; break;
                case SUB_INT: { var t = (ThreeRegisterInstruction) i; r[a] = (int) r[t.getRegisterB()] - (int) r[t.getRegisterC()]; break; }
                case DIV_INT_LIT8: r[a] = (int) r[((TwoRegisterInstruction) i).getRegisterB()] / ((NarrowLiteralInstruction) i).getNarrowLiteral(); break;
                case MOVE: r[a] = r[((TwoRegisterInstruction) i).getRegisterB()]; break;
                case INVOKE_STATIC: {
                    var f = (FiveRegisterInstruction) i; int[] regs = {f.getRegisterC(), f.getRegisterD(), f.getRegisterE(), f.getRegisterF(), f.getRegisterG()};
                    check(((MethodReference) ((ReferenceInstruction) i).getReference()).getDefiningClass().equals(GL), "helper calls GLES");
                    called = new Object[f.getRegisterCount()]; for (int k = 0; k < called.length; k++) called[k] = r[regs[k]]; break;
                }
                case RETURN_VOID: return called;
                default: throw new AssertionError("unexpected " + i.getOpcode());
            }
            pc = next;
        }
        throw new AssertionError("no return");
    }

    public static void main(String[] args) throws Exception {
        var in = input();
        check(GlUniformCountRule.inspect(in).status == CompatibilityEvidence.Status.SUPPORTED, "uniform array calls detected");
        var r = GlUniformCountRule.repair(in);
        check(r.status == CompatibilityEvidence.Status.VERIFIED && r.reason.startsWith("2 "), "two array calls repaired: " + r.reason);
        byte[] out = r.replacements.get("classes.dex");
        List<Instruction> draw = new ArrayList<>(); for (Instruction i : find(out, "onDrawFrame").getImplementation().getInstructions()) draw.add(i);
        String n0 = ((MethodReference) ((ReferenceInstruction) draw.get(0)).getReference()).getName(), n2 = ((MethodReference) ((ReferenceInstruction) draw.get(2)).getReference()).getName();
        check(n0.equals("pocketouyaGl_glUniform4fv") && n2.equals("glUniform4f"), "array call routed, scalar call untouched");
        check(((FiveRegisterInstruction) draw.get(1)).getRegisterCount() == 5 && ((FiveRegisterInstruction) draw.get(1)).getRegisterE() == 4, "matrix call keeps registers");

        Method vec = find(out, "pocketouyaGl_glUniform4fv"), mat = find(out, "pocketouyaGl_glUniformMatrix4fv");
        check(vec.getAccessFlags() == 0x100a, "private static synthetic helper");
        float[] rip = new float[32];                                          // MAX_RIP = 8 vec4s, game passes 8*4 = 32
        check((int) run(vec, 5, 32, rip, 0)[1] == 8, "float count lowered to vec4 count");
        check((int) run(vec, 5, 8, rip, 0)[1] == 8, "fitting count unchanged");
        check((int) run(vec, 5, 3, rip, 4)[1] == 3, "smaller count unchanged");
        check((int) run(vec, 5, 32, rip, 8)[1] == 6, "offset respected");
        check((int) run(vec, 5, 32, null, 0)[1] == 32, "null array left to Android's own check");
        check((int) run(vec, 5, 32, rip, 40)[1] == 32, "bad offset left to Android's own check");
        Object[] m = run(mat, 2, 4, false, new float[16], 0);
        check((int) m[1] == 1 && m[2].equals(false) && m.length == 5, "matrix count lowered, transpose kept");

        var again = new LinkedHashMap<>(in); again.putAll(r.replacements);
        check(GlUniformCountRule.repair(again).status == CompatibilityEvidence.Status.SKIPPED, "idempotent");
        System.out.println("GlUniformCountRuleTest: PASS");
    }
}
