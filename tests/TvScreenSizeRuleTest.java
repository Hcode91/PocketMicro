package io.github.hozdogus.pocketouya;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;

/** Word Shaker's AFKLib b.e(): (screenLayout & 15) == LARGE || == XLARGE. */
public final class TvScreenSizeRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String GAME = "Lcom/afksoft/AFKLib/b;", OURS = "Lio/github/hcode91/pocketouya/bridge/Probe;";
    static final ImmutableFieldReference LAYOUT = new ImmutableFieldReference("Landroid/content/res/Configuration;", "screenLayout", "I");

    /** p0 = Configuration (register 2 of 3). */
    static Method isLarge(String owner) {
        var body = new ImmutableMethodImplementation(3, List.of(
                new ImmutableInstruction22c(Opcode.IGET, 0, 2, LAYOUT),
                new ImmutableInstruction22b(Opcode.AND_INT_LIT8, 0, 0, 15),
                new ImmutableInstruction11n(Opcode.CONST_4, 1, 3),
                new ImmutableInstruction22t(Opcode.IF_EQ, 0, 1, 7),
                new ImmutableInstruction11n(Opcode.CONST_4, 1, 4),
                new ImmutableInstruction22t(Opcode.IF_EQ, 0, 1, 4),
                new ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                new ImmutableInstruction11x(Opcode.RETURN, 0),
                new ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                new ImmutableInstruction11x(Opcode.RETURN, 0)), List.of(), List.of());
        return new ImmutableMethod(owner, "e", List.of(new ImmutableMethodParameter("Landroid/content/res/Configuration;", Set.of(), null)), "Z", 9, Set.of(), Set.of(), body);
    }

    /** Small interpreter for the opcodes above; returns e(screenLayout). */
    static int run(Method m, int screenLayout) {
        Map<Integer, Instruction> code = new HashMap<>(); int at = 0;
        for (Instruction i : m.getImplementation().getInstructions()) { code.put(at, i); at += i.getCodeUnits(); }
        int[] r = new int[3]; int pc = 0;
        for (int step = 0; step < 50; step++) {
            Instruction i = code.get(pc); check(i != null, "branch into the middle of an instruction at " + pc);
            int next = pc + i.getCodeUnits(), a = ((OneRegisterInstruction) i).getRegisterA();
            switch (i.getOpcode()) {
                case IGET: r[a] = screenLayout; break;
                case AND_INT_LIT8: r[a] = r[((TwoRegisterInstruction) i).getRegisterB()] & ((NarrowLiteralInstruction) i).getNarrowLiteral(); break;
                case OR_INT_LIT8: r[a] = r[((TwoRegisterInstruction) i).getRegisterB()] | ((NarrowLiteralInstruction) i).getNarrowLiteral(); break;
                case CONST_4: r[a] = ((NarrowLiteralInstruction) i).getNarrowLiteral(); break;
                case IF_EQ: if (r[a] == r[((TwoRegisterInstruction) i).getRegisterB()]) next = pc + ((OffsetInstruction) i).getCodeOffset(); break;
                case RETURN: return r[a];
                default: throw new AssertionError("unexpected " + i.getOpcode());
            }
            pc = next;
        }
        throw new AssertionError("no return");
    }

    static Method find(byte[] dex, String owner) {
        for (ClassDef c : new DexBackedDexFile(null, dex).getClasses()) if (c.getType().equals(owner)) for (Method m : c.getMethods()) return m;
        throw new AssertionError("missing " + owner);
    }

    public static void main(String[] args) throws Exception {
        byte[] original = GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls(GAME, "Ljava/lang/Object;", List.of(isLarge(GAME))),
                GeneralCompatibilityTest.cls(OURS, "Ljava/lang/Object;", List.of(isLarge(OURS))));
        int phone = 0x12 /* normal, long=no */ | 0x20 /* long=yes */;
        check(run(find(original, GAME), phone) == 0, "a phone is not large before repair");
        Map<String, byte[]> input = new LinkedHashMap<>(Map.of("classes.dex", original));

        TvScreenSizeRule.Result inspect = TvScreenSizeRule.inspect(input);
        check(inspect.status == CompatibilityEvidence.Status.SUPPORTED && inspect.replacements.isEmpty(), "inspect is read-only: " + inspect.reason);
        check(inspect.reason.startsWith("1 "), "PocketOuya's own classes are not counted: " + inspect.reason);

        TvScreenSizeRule.Result r = TvScreenSizeRule.repair(input);
        check(r.status == CompatibilityEvidence.Status.VERIFIED, r.reason);
        byte[] repaired = r.replacements.get("classes.dex");
        Method game = find(repaired, GAME);
        check(run(game, phone) == 1, "phone now reads as large");
        check(run(game, 0x11) == 1 && run(game, 0x14) == 1, "small and xlarge also read as large");
        check(run(find(repaired, OURS), phone) == 0, "PocketOuya's own code still sees the real size");

        List<Instruction> code = new ArrayList<>(); for (Instruction i : game.getImplementation().getInstructions()) code.add(i);
        check(code.get(1).getOpcode() == Opcode.AND_INT_LIT8 && ((NarrowLiteralInstruction) code.get(1)).getNarrowLiteral() == -16, "size bits cleared");
        check(code.get(2).getOpcode() == Opcode.OR_INT_LIT8 && ((NarrowLiteralInstruction) code.get(2)).getNarrowLiteral() == 3, "size set to large");

        Map<String, byte[]> again = new LinkedHashMap<>(Map.of("classes.dex", repaired));
        check(TvScreenSizeRule.repair(again).status == CompatibilityEvidence.Status.SKIPPED, "second pass unchanged");
        System.out.println("TvScreenSizeRuleTest OK");
    }
}
