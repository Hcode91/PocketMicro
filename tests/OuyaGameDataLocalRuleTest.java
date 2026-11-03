package io.github.hozdogus.pocketouya;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;

public final class OuyaGameDataLocalRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String F = OuyaGameDataLocalRule.FACADE, S = "Ljava/lang/String;";

    static Method method(String name, List<String> params, String ret, int regs, Instruction... code) {
        List<ImmutableMethodParameter> p = new ArrayList<>(); for (String t : params) p.add(new ImmutableMethodParameter(t, Set.of(), null));
        return new ImmutableMethod(F, name, p, ret, 1, Set.of(), Set.of(), new ImmutableMethodImplementation(regs, List.of(code), List.of(), List.of()));
    }

    static Map<String, byte[]> input(int getRegs) throws Exception {
        Field ctx = new ImmutableField(F, "context", "Landroid/content/Context;", 2, null, Set.of(), Set.of());
        Method get = method("getGameData", List.of(S), S, getRegs, new ImmutableInstruction11n(Opcode.CONST_4, 0, 0), new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
        Method put = method("putGameData", List.of(S, S), "V", 6, new ImmutableInstruction10x(Opcode.RETURN_VOID));
        Method other = method("getGameUuid", List.of(), S, 1, new ImmutableInstruction11n(Opcode.CONST_4, 0, 0), new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
        ClassDef facade = new ImmutableClassDef(F, 1, "Ljava/lang/Object;", List.of(), null, Set.of(), List.of(ctx), List.of(get, put, other));
        return new LinkedHashMap<>(Map.of("classes.dex", GeneralCompatibilityTest.dex(facade)));
    }

    static Method find(byte[] dex, String name) {
        for (ClassDef c : new DexBackedDexFile(null, dex).getClasses()) for (Method m : c.getMethods()) if (m.getName().equals(name)) return m;
        throw new AssertionError("missing " + name);
    }

    static List<Instruction> code(Method m) { List<Instruction> l = new ArrayList<>(); for (Instruction i : m.getImplementation().getInstructions()) l.add(i); return l; }
    static String call(Instruction i) { return i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof MethodReference ? ((MethodReference) ((ReferenceInstruction) i).getReference()).getName() : ""; }

    public static void main(String[] args) throws Exception {
        check(OuyaGameDataLocalRule.inspect(Map.of("classes.dex", GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls("Lx/A;", "Ljava/lang/Object;", List.of())))).status
                == CompatibilityEvidence.Status.SKIPPED, "no OUYA SDK: skipped");
        var in = input(6);
        check(OuyaGameDataLocalRule.inspect(in).status == CompatibilityEvidence.Status.SUPPORTED, "game data API detected");
        var r = OuyaGameDataLocalRule.repair(in);
        check(r.status == CompatibilityEvidence.Status.VERIFIED, "repair verified: " + r.reason);
        byte[] out = r.replacements.get("classes.dex");

        Method helper = find(out, OuyaGameDataLocalRule.HELPER);
        check(helper.getAccessFlags() == 0x100a, "private static synthetic helper");
        List<String> hc = new ArrayList<>(); for (Instruction i : code(helper)) hc.add(call(i));
        check(hc.contains("acquireContentProviderClient") && hc.contains("release") && hc.contains("getSharedPreferences"), "helper probes the provider then opens local storage: " + hc);
        boolean authority = false, file = false;
        for (Instruction i : code(helper)) if (i.getOpcode() == Opcode.CONST_STRING) {
            String s = ((StringReference) ((ReferenceInstruction) i).getReference()).getString();
            authority |= s.equals("tv.ouya.userdata"); file |= s.equals("pocketouya_ouya_gamedata");
        }
        check(authority && file, "provider authority and local file name");

        List<Instruction> get = code(find(out, "getGameData"));
        check(call(get.get(0)).equals(OuyaGameDataLocalRule.HELPER) && get.get(2).getOpcode() == Opcode.IF_EQZ, "getGameData starts with the provider check");
        int target = 0, at = 0; for (int k = 0; k < 2; k++) target += get.get(k).getCodeUnits();
        target += ((OffsetInstruction) get.get(2)).getCodeOffset();
        int origIndex = -1; for (int k = 0; k < get.size(); k++) { if (at == target) origIndex = k; at += get.get(k).getCodeUnits(); }
        check(origIndex > 0 && get.get(origIndex).getOpcode() == Opcode.CONST_4 && get.get(origIndex + 1).getOpcode() == Opcode.RETURN_OBJECT, "provider present: original body runs");
        check(call(get.get(4)).equals("getString"), "provider missing: value read from local storage");
        List<String> put = new ArrayList<>(); for (Instruction i : code(find(out, "putGameData"))) put.add(call(i));
        check(put.containsAll(List.of("edit", "putString", "apply")), "putGameData writes local storage: " + put);
        check(code(find(out, "getGameUuid")).size() == 2, "other SDK methods untouched");

        var again = new LinkedHashMap<>(in); again.putAll(r.replacements);
        check(OuyaGameDataLocalRule.repair(again).status == CompatibilityEvidence.Status.SKIPPED, "idempotent");
        check(OuyaGameDataLocalRule.repair(input(3)).status == CompatibilityEvidence.Status.UNSUPPORTED, "too few scratch registers: unchanged");
        System.out.println("OuyaGameDataLocalRuleTest: PASS");
    }
}
