package com.hcode91.pocketmicro;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.*;
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
 * android.legacy-device-id: Android 10+ throws SecurityException when an ordinary app reads the
 * phone's hardware identifiers (IMEI/MEID/device ID, SIM serial, subscriber ID, phone number).
 * Old games call these at start-up and crash (e.g. Unreal Engine 3 AppInitialStartup).
 *
 * Every such TelephonyManager call in game or SDK code is routed through a small helper added to
 * the calling class. The helper makes the original call; only if Android refuses it with a
 * SecurityException does it return a fallback: "000000000000000" for device-ID style values (what
 * Android emulators traditionally report) and null for SIM/subscriber/phone number (what Android
 * returns when there is no SIM). Other exceptions and successful results are unchanged.
 */
public final class DeviceIdRule {
    public static final String ID = "android.legacy-device-id";
    static final String TM = "Landroid/telephony/TelephonyManager;", STRING = "Ljava/lang/String;";
    static final String ZERO_ID = "000000000000000";
    /** method name -> fallback is a device ID (true) or null (false) */
    private static final Map<String, Boolean> METHODS = new LinkedHashMap<>();
    static {
        METHODS.put("getDeviceId", true); METHODS.put("getImei", true); METHODS.put("getMeid", true);
        METHODS.put("getSubscriberId", false); METHODS.put("getSimSerialNumber", false); METHODS.put("getLine1Number", false);
    }
    static final String PREFIX = "pocketouyaSafe_";

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "No TelephonyManager identifier calls";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
    }

    private DeviceIdRule() {}

    public static Result inspect(Map<String, byte[]> dex) { return run(dex, false); }
    public static Result repair(Map<String, byte[]> dex) { return run(dex, true); }

    /** Matches invoke-virtual TelephonyManager.<id method>() or (I) returning String. */
    static MethodReference target(Instruction i) {
        if (i.getOpcode() != Opcode.INVOKE_VIRTUAL && i.getOpcode() != Opcode.INVOKE_VIRTUAL_RANGE) return null;
        if (!(i instanceof ReferenceInstruction) || !(((ReferenceInstruction) i).getReference() instanceof MethodReference)) return null;
        MethodReference r = (MethodReference) ((ReferenceInstruction) i).getReference();
        if (!r.getDefiningClass().equals(TM) || !METHODS.containsKey(r.getName()) || !r.getReturnType().equals(STRING)) return null;
        List<? extends CharSequence> p = r.getParameterTypes();
        boolean slot = p.size() == 1 && p.get(0).toString().equals("I");
        if (!p.isEmpty() && !slot) return null;
        int regs = i instanceof FiveRegisterInstruction ? ((FiveRegisterInstruction) i).getRegisterCount() : ((RegisterRangeInstruction) i).getRegisterCount();
        return regs == 1 + p.size() ? r : null;
    }

    static String helperName(MethodReference r) { return PREFIX + r.getName() + (r.getParameterTypes().isEmpty() ? "" : "Slot"); }

    static List<String> helperParams(MethodReference r) {
        return r.getParameterTypes().isEmpty() ? List.of(TM) : List.of(TM, "I");
    }

    static Method helper(String owner, MethodReference r) {
        boolean slot = !r.getParameterTypes().isEmpty();
        MethodImplementationBuilder b = new MethodImplementationBuilder(slot ? 3 : 2); // v0 local, p0 = tm, p1 = slot
        b.addLabel("start");
        if (slot) b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 2, 0, 0, 0, new ImmutableMethodReference(TM, r.getName(), List.of("I"), STRING)));
        else b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, new ImmutableMethodReference(TM, r.getName(), List.of(), STRING)));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        b.addLabel("end");
        b.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        b.addLabel("refused");
        if (METHODS.get(r.getName())) b.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference(ZERO_ID)));
        else b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 0));
        b.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        b.addCatch(new ImmutableTypeReference("Ljava/lang/SecurityException;"), b.getLabel("start"), b.getLabel("end"), b.getLabel("refused"));
        List<ImmutableMethodParameter> params = new ArrayList<>();
        for (String t : helperParams(r)) params.add(new ImmutableMethodParameter(t, Set.of(), null));
        return new ImmutableMethod(owner, helperName(r), params, STRING, 0x100a, Set.of(), Set.of(), b.getMethodImplementation());
    }

    private static boolean ours(String type) {
        return type.startsWith("Lio/github/hcode91/pocketouya/") || type.startsWith("Lcom/hcode91/pocketmicro/");
    }

    private static Result run(Map<String, byte[]> input, boolean apply) {
        Result result = new Result();
        int sites = 0;
        try {
            long total = 0;
            for (byte[] d : input.values()) total += d.length;
            if (input.size() > 64 || total > 64L * 1024 * 1024) { result.reason = "DEX budget; no edits"; return result; }
            for (var file : input.entrySet()) {
                DexBackedDexFile dex = new DexBackedDexFile(null, file.getValue());
                DexPool pool = new DexPool(dex.getOpcodes());
                boolean changed = false;
                for (ClassDef c : dex.getClasses()) {
                    if (ours(c.getType()) || AccessFlags.INTERFACE.isSet(c.getAccessFlags())) { pool.internClass(c); continue; }
                    Set<String> existing = new HashSet<>();
                    for (Method m : c.getMethods()) existing.add(m.getName());
                    List<Method> methods = new ArrayList<>();
                    Map<String, MethodReference> needed = new LinkedHashMap<>();
                    boolean classChanged = false;
                    for (Method m : c.getMethods()) {
                        if (m.getImplementation() == null || m.getName().startsWith(PREFIX)) { methods.add(m); continue; }
                        List<Integer> hits = new ArrayList<>();
                        int n = 0;
                        for (Instruction i : m.getImplementation().getInstructions()) {
                            if (i instanceof org.jf.dexlib2.iface.instruction.formats.UnknownInstruction) throw new UnsupportedOperationException("Reserved DEX instruction in " + c.getType());
                            if (target(i) != null) hits.add(n);
                            n++;
                        }
                        sites += hits.size();
                        if (!apply || hits.isEmpty()) { methods.add(m); continue; }
                        MutableMethodImplementation body = new MutableMethodImplementation(m.getImplementation());
                        List<Instruction> original = new ArrayList<>();
                        for (Instruction i : m.getImplementation().getInstructions()) original.add(i);
                        for (int at : hits) {
                            Instruction i = original.get(at);
                            MethodReference r = target(i);
                            String name = helperName(r);
                            if (existing.contains(name) && !needed.containsKey(name)) throw new IllegalStateException("Helper name collision in " + c.getType());
                            needed.put(name, r);
                            var ref = new ImmutableMethodReference(c.getType(), name, helperParams(r), STRING);
                            if (i instanceof FiveRegisterInstruction) {
                                var f = (FiveRegisterInstruction) i;
                                body.replaceInstruction(at, new BuilderInstruction35c(Opcode.INVOKE_STATIC, f.getRegisterCount(), f.getRegisterC(), f.getRegisterD(), 0, 0, 0, ref));
                            } else {
                                var rr = (RegisterRangeInstruction) i;
                                body.replaceInstruction(at, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, rr.getStartRegister(), rr.getRegisterCount(), ref));
                            }
                        }
                        methods.add(new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(), m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), body));
                        classChanged = true;
                    }
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
                if (inspect(verify).status != CompatibilityEvidence.Status.SKIPPED) throw new IllegalStateException("Postcondition: identifier calls remain after repair");
            }
            result.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
            result.reason = sites + " TelephonyManager identifier call(s) (device ID/IMEI/MEID/SIM/subscriber/number). A refused read (Android 10+ SecurityException) returns "
                    + ZERO_ID + " for device IDs or null for SIM/subscriber/number instead of crashing; permitted reads are unchanged.";
        } catch (UnsupportedOperationException e) {
            result.replacements.clear(); result.status = CompatibilityEvidence.Status.UNSUPPORTED; result.reason = e.getMessage() + "; original DEX retained";
        } catch (Exception e) {
            result.replacements.clear(); result.status = CompatibilityEvidence.Status.UNKNOWN; result.reason = e + "; original DEX retained";
        }
        return result;
    }
}
