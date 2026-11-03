package io.github.hozdogus.pocketouya;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;

public final class CallGuardRulesTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String CTX = CallGuardRules.CONTEXT, CONN = CallGuardRules.CONN, LIST = "Ljava/util/List;", IT = "Ljava/util/Iterator;";

    static Method unbind(String owner, String name, String receiver, boolean range) {
        var ref = new ImmutableMethodReference(receiver, "unbindService", List.of(CONN), "V");
        Instruction call = range ? new ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 100, 2, ref) : new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, ref);
        return new ImmutableMethod(owner, name, GeneralCompatibilityTest.params(receiver, CONN), "V", 9, Set.of(), Set.of(),
                new ImmutableMethodImplementation(range ? 102 : 2, List.of(call, new ImmutableInstruction10x(Opcode.RETURN_VOID)), List.of(), List.of()));
    }
    /** check(List receipts): iterates receipts; optionally references an OUYA store type. */
    static Method receipts(String owner, String name, boolean ouya) {
        List<Instruction> code = new ArrayList<>();
        if (ouya) code.add(new ImmutableInstruction21c(Opcode.CONST_CLASS, 0, new ImmutableTypeReference("Ltv/ouya/console/api/Receipt;")));
        code.add(new ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 1, 0, 0, 0, 0, new ImmutableMethodReference(LIST, "iterator", List.of(), IT)));
        code.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        code.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
        return new ImmutableMethod(owner, name, GeneralCompatibilityTest.params(LIST), IT, 9, Set.of(), Set.of(), new ImmutableMethodImplementation(2, code, List.of(), List.of()));
    }
    static Method find(byte[] dex, String name) {
        for (ClassDef c : new DexBackedDexFile(null, dex).getClasses()) for (Method m : c.getMethods()) if (m.getName().equals(name)) return m;
        throw new AssertionError("missing " + name);
    }
    static MethodReference firstCall(Method m) {
        for (Instruction i : m.getImplementation().getInstructions()) if (i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof MethodReference) return (MethodReference) ((ReferenceInstruction) i).getReference();
        throw new AssertionError("no call");
    }

    public static void main(String[] args) throws Exception {
        String sdk = "Lcom/bda/controller/Controller;", game = "Lcom/example/game/RunnerActivity;";
        byte[] input = GeneralCompatibilityTest.dex(
                GeneralCompatibilityTest.cls(sdk, "Ljava/lang/Object;", List.of(unbind(sdk, "exit", CTX, false), unbind(sdk, "exitRange", "Landroid/content/ContextWrapper;", true),
                        unbind(sdk, "notContext", "Lcom/example/Other;", false))),
                GeneralCompatibilityTest.cls(game, "Landroid/app/Activity;", List.of(unbind(game, "selfUnbind", game, false), receipts(game, "checkReceipts", true), receipts(game, "levels", false))));
        byte[] copy = input.clone();
        var scan = CallGuardRules.inspect(Map.of("classes.dex", input));
        check(scan.replacements.isEmpty() && scan.report().contains("SUPPORTED " + CallGuardRules.UNBIND_ID + ": 3 ") && scan.report().contains("SUPPORTED " + CallGuardRules.RECEIPTS_ID + ": 1 "), "read-only: " + scan.report());
        var fixed = CallGuardRules.repair(Map.of("classes.dex", input));
        check(fixed.replacements.size() == 1 && fixed.report().contains("VERIFIED"), "repair: " + fixed.report());
        check(Arrays.equals(copy, input), "input unchanged");
        byte[] out = fixed.replacements.get("classes.dex");
        for (String n : new String[]{"exit", "exitRange", "selfUnbind"}) check(firstCall(find(out, n)).getName().equals(CallGuardRules.UNBIND_HELPER), n + " guarded");
        check(firstCall(find(out, "notContext")).getName().equals("unbindService"), "non-Context unbindService untouched");
        check(firstCall(find(out, "checkReceipts")).getName().equals(CallGuardRules.ITER_HELPER), "OUYA receipt iteration guarded");
        check(firstCall(find(out, "levels")).getName().equals("iterator"), "iteration outside OUYA store code untouched");
        Method u = find(out, CallGuardRules.UNBIND_HELPER);
        var tryBlocks = u.getImplementation().getTryBlocks();
        check(tryBlocks.size() == 1 && "Ljava/lang/IllegalArgumentException;".equals(tryBlocks.get(0).getExceptionHandlers().get(0).getExceptionType()), "unbind helper ignores only IllegalArgumentException");
        Method it = find(out, CallGuardRules.ITER_HELPER);
        check(it.getImplementation().getInstructions().iterator().next().getOpcode() == Opcode.IF_EQZ, "iterator helper checks for null first");
        check(CallGuardRules.repair(Map.of("classes.dex", out)).replacements.isEmpty(), "idempotent");
        check(CallGuardRules.repair(Map.of("classes.dex", new byte[]{1, 2})).report().contains("UNKNOWN"), "malformed retained");
        System.out.println("Call guards: Context/ContextWrapper/subclass unbindService (direct+range), OUYA-store-only null-safe iteration, non-Context and non-store calls untouched, helper shapes, idempotence pass");
    }
}
