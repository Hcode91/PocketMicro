package com.hcode91.pocketmicro;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;

public final class OuyaStoreOfflineRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String F = OuyaStoreOfflineRule.FACADE, R = OuyaStoreOfflineRule.REQUEST;

    static Method m(String owner, String name, List<String> params, int regs) {
        List<ImmutableMethodParameter> p = new ArrayList<>(); for (String t : params) p.add(new ImmutableMethodParameter(t, Set.of(), null));
        return new ImmutableMethod(owner, name, p, "V", 1, Set.of(), Set.of(), new ImmutableMethodImplementation(regs, List.of(new ImmutableInstruction10x(Opcode.RETURN_VOID)), List.of(), List.of()));
    }
    static Field f(String owner, String name, String type) { return new ImmutableField(owner, name, type, 2, null, Set.of(), Set.of()); }

    static Map<String, byte[]> input() throws Exception {
        ClassDef facade = new ImmutableClassDef(F, 1, "Ljava/lang/Object;", List.of(), null, Set.of(),
                List.of(f(F, "context", "Landroid/content/Context;"), f(F, "remoteService", "Ltv/ouya/console/internal/IIapServiceDefinition;"), f(F, "pendingRequests", "Ljava/util/List;")),
                List.of(m(F, "runRequest", List.of("Ljava/lang/Runnable;"), 2), m(F, "ensureServiceIsBound", List.of(), 1)));
        ClassDef request = new ImmutableClassDef(R, 1025, "Ljava/lang/Object;", List.of("Ljava/lang/Runnable;"), null, Set.of(), List.of(f(R, "listener", OuyaStoreOfflineRule.LISTENER)), List.of());
        return new LinkedHashMap<>(Map.of("classes.dex", GeneralCompatibilityTest.dex(facade, request)));
    }

    static List<String> calls(Method m) {
        List<String> out = new ArrayList<>();
        for (Instruction i : m.getImplementation().getInstructions())
            if (i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof MethodReference) {
                MethodReference r = (MethodReference) ((ReferenceInstruction) i).getReference(); out.add(r.getDefiningClass() + "->" + r.getName());
            }
        return out;
    }

    public static void main(String[] args) throws Exception {
        var in = input();
        var r = OuyaStoreOfflineRule.repair(in);
        check(r.status == CompatibilityEvidence.Status.VERIFIED, "store rule verified: " + r.reason);
        Map<String, ClassDef> out = new HashMap<>(); for (ClassDef c : new DexBackedDexFile(null, r.replacements.get("classes.dex")).getClasses()) out.put(c.getType(), c);
        Method helper = null; for (Method mm : out.get(F).getMethods()) if (mm.getName().equals(OuyaStoreOfflineRule.HELPER)) helper = mm;
        List<String> hc = calls(helper);
        check(hc.contains("Landroid/os/Looper;->getMainLooper") && hc.contains("Landroid/os/Handler;->post"), "failure posted to the main thread: " + hc);
        check(!hc.contains(OuyaStoreOfflineRule.LISTENER + "->onFailure"), "listener not called on the requesting thread");
        ClassDef failure = out.get(OuyaStoreOfflineRule.FAILURE);
        check(failure != null && failure.getInterfaces().contains("Ljava/lang/Runnable;"), "failure runnable added");
        Method run = null; for (Method mm : failure.getMethods()) if (mm.getName().equals("run")) run = mm;
        check(calls(run).contains(OuyaStoreOfflineRule.LISTENER + "->onFailure"), "runnable delivers onFailure");
        boolean code = false; for (Instruction i : run.getImplementation().getInstructions()) if (i.getOpcode() == Opcode.CONST_16 && ((NarrowLiteralInstruction) i).getNarrowLiteral() == 3005) code = true;
        check(code, "SERVER_OFFLINE 3005");
        var again = new LinkedHashMap<>(in); again.putAll(r.replacements);
        check(OuyaStoreOfflineRule.repair(again).status == CompatibilityEvidence.Status.SKIPPED, "idempotent");
        System.out.println("OuyaStoreOfflineRuleTest: PASS");
    }
}
