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

public final class OuyaAccountAndEngineRulesTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    static Method find(byte[] dex, String cls, String name) {
        for (ClassDef c : new DexBackedDexFile(null, dex).getClasses()) if (c.getType().equals(cls)) for (Method m : c.getMethods()) if (m.getName().equals(name)) return m;
        throw new AssertionError("missing " + name);
    }
    static List<Instruction> code(Method m) { List<Instruction> l = new ArrayList<>(); for (Instruction i : m.getImplementation().getInstructions()) l.add(i); return l; }

    static Map<String, byte[]> accountGame(boolean ouyaType) throws Exception {
        String L = "Lcom/example/Listener;";
        var lookup = new ImmutableMethodReference(OuyaAccountRule.AM, "getAccountsByType", List.of("Ljava/lang/String;"), "[Landroid/accounts/Account;");
        var body = new ImmutableMethodImplementation(3, List.of(
                new ImmutableInstruction21c(Opcode.CONST_STRING, 1, new ImmutableStringReference(ouyaType ? "tv.ouya.account.v1" : "com.google")),
                new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, lookup),
                new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2), new ImmutableInstruction10x(Opcode.RETURN_VOID)), List.of(), List.of());
        Method m = new ImmutableMethod(L, "onFailure", List.of(), "V", 9, Set.of(), Set.of(), body);
        return new LinkedHashMap<>(Map.of("classes.dex", GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls(L, "Ljava/lang/Object;", List.of(m)))));
    }

    static Map<String, byte[]> base64Stream() throws Exception {
        String S = AndEngineBase64Rule.STREAM;
        var body = new ImmutableMethodImplementation(4, List.of(
                new ImmutableInstruction22c(Opcode.IGET, 0, 3, new ImmutableFieldReference(S, "outputStart", "I")),
                new ImmutableInstruction21t(Opcode.IF_GEZ, 0, 4),
                new ImmutableInstruction11n(Opcode.CONST_4, 0, -1),
                new ImmutableInstruction11x(Opcode.RETURN, 0),
                new ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 3, new ImmutableFieldReference(S, "buf", "[B")),
                new ImmutableInstruction23x(Opcode.AGET_BYTE, 0, 1, 0),
                new ImmutableInstruction11x(Opcode.RETURN, 0)), List.of(), List.of());
        Method read = new ImmutableMethod(S, "read", List.of(), "I", 1, Set.of(), Set.of(), body);
        return new LinkedHashMap<>(Map.of("classes.dex", GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls(S, "Ljava/io/InputStream;", List.of(read)))));
    }

    public static void main(String[] args) throws Exception {
        // OUYA account placeholder
        check(OuyaAccountRule.inspect(accountGame(false)).status == CompatibilityEvidence.Status.SKIPPED, "games without the OUYA account type untouched");
        var in = accountGame(true);
        var r = OuyaAccountRule.repair(in);
        check(r.status == CompatibilityEvidence.Status.VERIFIED, "account lookup repaired: " + r.reason);
        byte[] out = r.replacements.get("classes.dex");
        Instruction call = code(find(out, "Lcom/example/Listener;", "onFailure")).get(1);
        check(call.getOpcode() == Opcode.INVOKE_STATIC && ((MethodReference) ((ReferenceInstruction) call).getReference()).getName().equals(OuyaAccountRule.HELPER), "lookup routed through helper");
        Method helper = find(out, "Lcom/example/Listener;", OuyaAccountRule.HELPER);
        check(helper.getAccessFlags() == 0x100a, "private static synthetic helper");
        List<String> calls = new ArrayList<>(); boolean type = false, name = false;
        for (Instruction i : code(helper)) {
            if (i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof MethodReference) calls.add(((MethodReference) ((ReferenceInstruction) i).getReference()).getName());
            if (i.getOpcode() == Opcode.CONST_STRING) { String s = ((StringReference) ((ReferenceInstruction) i).getReference()).getString(); type |= s.equals("tv.ouya.account.v1"); name |= s.equals("OUYA"); }
        }
        check(calls.equals(List.of("getAccountsByType", "equals", "<init>")) && type && name, "real lookup first, placeholder only for the OUYA type: " + calls);
        var again = new LinkedHashMap<>(in); again.putAll(r.replacements);
        check(OuyaAccountRule.repair(again).status == CompatibilityEvidence.Status.SKIPPED, "account rule idempotent");

        // AndEngine Base64InputStream
        check(AndEngineBase64Rule.inspect(accountGame(true)).status == CompatibilityEvidence.Status.SKIPPED, "non-AndEngine games untouched");
        var b = base64Stream();
        var br = AndEngineBase64Rule.repair(b);
        check(br.status == CompatibilityEvidence.Status.VERIFIED, "base64 read repaired: " + br.reason);
        List<Instruction> read = code(find(br.replacements.get("classes.dex"), AndEngineBase64Rule.STREAM, "read"));
        int at = -1; for (int k = 0; k < read.size(); k++) if (read.get(k).getOpcode() == Opcode.AGET_BYTE) at = k;
        Instruction mask = read.get(at + 1);
        check(mask.getOpcode() == Opcode.AND_INT_LIT16 && ((NarrowLiteralInstruction) mask).getNarrowLiteral() == 0xff && ((TwoRegisterInstruction) mask).getRegisterA() == 0
                && read.get(at + 2).getOpcode() == Opcode.RETURN, "byte masked to 0..255 before return");
        check(read.get(2).getOpcode() == Opcode.CONST_4 && ((NarrowLiteralInstruction) read.get(2)).getNarrowLiteral() == -1, "end-of-stream -1 unchanged");
        var b2 = new LinkedHashMap<>(b); b2.putAll(br.replacements);
        check(AndEngineBase64Rule.repair(b2).status == CompatibilityEvidence.Status.SKIPPED, "base64 rule idempotent");
        System.out.println("OuyaAccountAndEngineRulesTest: PASS");
    }
}
