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

public final class DeviceIdRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String TM = DeviceIdRule.TM, S = DeviceIdRule.STRING, OWNER = "Lcom/example/game/UE3JavaApp;";

    static Method caller(String name, String method, boolean slot, boolean range, String definingClass) {
        var ref = new ImmutableMethodReference(definingClass, method, slot ? List.of("I") : List.of(), S);
        int base = range ? 200 : 0, count = slot ? 2 : 1;
        Instruction call = range ? new ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, base, count, ref)
                : new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, count, 1, 2, 0, 0, 0, ref);
        var params = slot ? GeneralCompatibilityTest.params(TM, "I") : GeneralCompatibilityTest.params(TM);
        int regs = range ? base + count : 1 + count;
        return new ImmutableMethod(OWNER, name, params, S, 9, Set.of(), Set.of(), new ImmutableMethodImplementation(regs,
                List.of(call, new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), List.of(), List.of()));
    }

    static Method find(byte[] dex, String name) {
        for (ClassDef c : new DexBackedDexFile(null, dex).getClasses()) for (Method m : c.getMethods()) if (m.getName().equals(name)) return m;
        throw new AssertionError("missing " + name);
    }

    /** Minimal model of the emitted helper: platform call, SecurityException handler, returns. */
    static Object run(Method helper, boolean refused, String value) {
        var impl = helper.getImplementation();
        Map<Integer, Instruction> code = new HashMap<>(); int at = 0;
        for (Instruction i : impl.getInstructions()) { code.put(at, i); at += i.getCodeUnits(); }
        Object[] regs = new Object[impl.getRegisterCount()]; Object result = null; int pc = 0;
        for (int step = 0; step < 20; step++) {
            Instruction i = code.get(pc); int next = pc + i.getCodeUnits();
            try {
                switch (i.getOpcode()) {
                    case INVOKE_VIRTUAL: {
                        var r = (MethodReference) ((ReferenceInstruction) i).getReference();
                        check(r.getDefiningClass().equals(TM), "helper calls the original TelephonyManager method");
                        if (refused) throw new SecurityException("getDeviceId: The uid does not meet the requirements");
                        result = value; break;
                    }
                    case MOVE_RESULT_OBJECT: regs[((OneRegisterInstruction) i).getRegisterA()] = result; break;
                    case CONST_STRING: regs[((OneRegisterInstruction) i).getRegisterA()] = ((StringReference) ((ReferenceInstruction) i).getReference()).getString(); break;
                    case CONST_4: regs[((OneRegisterInstruction) i).getRegisterA()] = null; break;
                    case RETURN_OBJECT: return regs[((OneRegisterInstruction) i).getRegisterA()];
                    default: throw new AssertionError("unexpected " + i.getOpcode());
                }
            } catch (SecurityException e) {
                boolean handled = false;
                for (var t : impl.getTryBlocks()) if (pc >= t.getStartCodeAddress() && pc < t.getStartCodeAddress() + t.getCodeUnitCount())
                    for (var h : t.getExceptionHandlers()) if ("Ljava/lang/SecurityException;".equals(h.getExceptionType())) { next = h.getHandlerCodeAddress(); handled = true; }
                if (!handled) throw e;
            }
            pc = next;
        }
        throw new AssertionError("budget");
    }

    public static void main(String[] args) throws Exception {
        List<Method> methods = List.of(
                caller("deviceId", "getDeviceId", false, false, TM),
                caller("deviceIdRange", "getDeviceId", false, true, TM),
                caller("imeiSlot", "getImei", true, false, TM),
                caller("subscriber", "getSubscriberId", false, false, TM),
                caller("unrelated", "getDeviceId", false, false, "Lcom/example/OtherManager;"),
                caller("operator", "getNetworkOperatorName", false, false, TM));
        byte[] input = GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls(OWNER, "Landroid/app/Activity;", methods)), copy = input.clone();

        var scan = DeviceIdRule.inspect(Map.of("classes.dex", input));
        check(scan.status == CompatibilityEvidence.Status.SUPPORTED && scan.replacements.isEmpty() && scan.reason.startsWith("4 "), "read-only detection: " + scan.reason);
        var fixed = DeviceIdRule.repair(Map.of("classes.dex", input));
        check(fixed.status == CompatibilityEvidence.Status.VERIFIED && fixed.replacements.size() == 1, "repair: " + fixed.reason);
        check(Arrays.equals(input, copy), "source DEX unchanged");
        byte[] out = fixed.replacements.get("classes.dex");

        for (String name : new String[]{"deviceId", "deviceIdRange", "imeiSlot", "subscriber"}) {
            Instruction call = find(out, name).getImplementation().getInstructions().iterator().next();
            var ref = (MethodReference) ((ReferenceInstruction) call).getReference();
            check((call.getOpcode() == Opcode.INVOKE_STATIC || call.getOpcode() == Opcode.INVOKE_STATIC_RANGE) && ref.getDefiningClass().equals(OWNER) && ref.getName().startsWith(DeviceIdRule.PREFIX), name + " routed to helper");
        }
        Instruction range = find(out, "deviceIdRange").getImplementation().getInstructions().iterator().next();
        check(((RegisterRangeInstruction) range).getStartRegister() == 200, "high register range retained");
        for (String name : new String[]{"unrelated", "operator"}) {
            var ref = (MethodReference) ((ReferenceInstruction) find(out, name).getImplementation().getInstructions().iterator().next()).getReference();
            check(!ref.getName().startsWith(DeviceIdRule.PREFIX), name + " left alone");
        }

        Method deviceHelper = find(out, DeviceIdRule.PREFIX + "getDeviceId"), imeiHelper = find(out, DeviceIdRule.PREFIX + "getImeiSlot"), subHelper = find(out, DeviceIdRule.PREFIX + "getSubscriberId");
        check(imeiHelper.getParameterTypes().size() == 2 && imeiHelper.getImplementation().getRegisterCount() == 3, "slot helper takes (TelephonyManager, int)");
        check("358240051111110".equals(run(deviceHelper, false, "358240051111110")), "permitted read returns the real value");
        check(DeviceIdRule.ZERO_ID.equals(run(deviceHelper, true, null)), "refused device ID returns zeros");
        check(DeviceIdRule.ZERO_ID.equals(run(imeiHelper, true, null)), "refused IMEI returns zeros");
        check(run(subHelper, true, null) == null, "refused subscriber ID returns null like a phone without SIM");
        check("2340".equals(run(subHelper, false, "2340")), "permitted subscriber read unchanged");

        check(DeviceIdRule.repair(Map.of("classes.dex", out)).status == CompatibilityEvidence.Status.SKIPPED, "idempotent");
        check(DeviceIdRule.repair(Map.of("classes.dex", GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls(OWNER, "Landroid/app/Activity;", List.of(caller("operator", "getNetworkOperatorName", false, false, TM)))))).status == CompatibilityEvidence.Status.SKIPPED, "no identifier calls: skipped");
        check(DeviceIdRule.repair(Map.of("classes.dex", new byte[]{1, 2, 3})).status == CompatibilityEvidence.Status.UNKNOWN, "malformed DEX retained");
        System.out.println("Device ID rule: getDeviceId/IMEI slot/subscriber, direct and range calls, permitted vs refused reads, fallbacks, near-misses, idempotence and malformed input pass");
    }
}
