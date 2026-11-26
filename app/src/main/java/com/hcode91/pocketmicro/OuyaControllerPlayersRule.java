package com.hcode91.pocketmicro;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.*;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/**
 * ouya.controller-player-numbers: the OUYA SDK's OuyaController asks the OUYA system's
 * "tv.ouya.controllerdata" provider which player (0-3) each input device is. A phone has no such
 * provider, so every controller, Bluetooth pads and PocketMicro's touch pad alike, is player -1:
 * getControllerByPlayer() stays null, games ignore input ("controller state for player -1",
 * Zombies and Trains!) or crash using the missing controller (Zombie Driver HD OnPadButtonEvent).
 *
 * When that provider is missing, OuyaController.getPlayerNumFromDeviceId and
 * getDeviceIdFromPlayerNum answer from a small added table instead: the first gamepad/joystick
 * device (or PocketMicro's virtual pad, device id -1) to send input becomes player 0, the next
 * player 1, up to 4. Keyboards, touchscreens and phone buttons never take a slot. With the OUYA
 * provider present the SDK code runs unchanged. Button/axis mapping is the SDK's own.
 */
public final class OuyaControllerPlayersRule {
    public static final String ID = "ouya.controller-player-numbers";
    static final String CONTROLLER = "Ltv/ouya/console/api/OuyaController;", PLAYERS = "Ltv/ouya/console/api/PocketouyaPlayers;",
            CONTEXT = "Landroid/content/Context;", AUTHORITY = "tv.ouya.controllerdata";
    static final int EMPTY = 0x80000000;

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "OUYA controller API absent";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
    }

    private OuyaControllerPlayersRule() {}
    public static Result inspect(Map<String, byte[]> dex) { return run(dex, false); }
    public static Result repair(Map<String, byte[]> dex) { return run(dex, true); }

    private static ImmutableMethodReference m(String owner, String name, List<String> params, String ret) { return new ImmutableMethodReference(owner, name, params, ret); }
    private static ImmutableFieldReference f(String name, String type) { return new ImmutableFieldReference(PLAYERS, name, type); }
    private static ImmutableMethodParameter p(String t) { return new ImmutableMethodParameter(t, Set.of(), null); }

    static boolean lookup(Method mm, String name) {
        return mm.getName().equals(name) && String.join("", mm.getParameterTypes()).equals("I") && mm.getReturnType().equals("I")
                && AccessFlags.STATIC.isSet(mm.getAccessFlags()) && mm.getImplementation() != null;
    }

    static boolean matches(ClassDef c) {
        boolean player = false, device = false, ctx = false;
        for (Method mm : c.getMethods()) { player |= lookup(mm, "getPlayerNumFromDeviceId"); device |= lookup(mm, "getDeviceIdFromPlayerNum"); }
        for (Field fl : c.getFields()) ctx |= fl.getName().equals("appContext") && fl.getType().equals(CONTEXT) && AccessFlags.STATIC.isSet(fl.getAccessFlags());
        return player && device && ctx;
    }

    /** Prefix: if PocketouyaPlayers.local(appContext) answer with PocketouyaPlayers.<table>(arg) (v0 scratch; locals are unset at entry). */
    static Method prefixed(Method original, String table) {
        MutableMethodImplementation body = new MutableMethodImplementation(original.getImplementation());
        int arg = body.getRegisterCount() - 1;
        Label start = body.newLabelForIndex(0);
        List<BuilderInstruction> pre = List.of(
                new BuilderInstruction21c(Opcode.SGET_OBJECT, 0, new ImmutableFieldReference(CONTROLLER, "appContext", CONTEXT)),
                new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, m(PLAYERS, "local", List.of(CONTEXT), "Z")),
                new BuilderInstruction11x(Opcode.MOVE_RESULT, 0),
                new BuilderInstruction21t(Opcode.IF_EQZ, 0, start),
                new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, arg, 0, 0, 0, 0, m(PLAYERS, table, List.of("I"), "I")),
                new BuilderInstruction11x(Opcode.MOVE_RESULT, 0),
                new BuilderInstruction11x(Opcode.RETURN, 0));
        for (int i = pre.size() - 1; i >= 0; i--) body.addInstruction(0, pre.get(i));
        return new ImmutableMethod(original.getDefiningClass(), original.getName(), original.getParameters(), original.getReturnType(), original.getAccessFlags(),
                original.getAnnotations(), original.getHiddenApiRestrictions(), body);
    }

    static ClassDef playersClass() {
        String ints = "[I";
        // <clinit>: slots = {EMPTY x4}
        MethodImplementationBuilder ci = new MethodImplementationBuilder(3);
        ci.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 4));
        ci.addInstruction(new BuilderInstruction22c(Opcode.NEW_ARRAY, 0, 0, new ImmutableTypeReference(ints)));
        ci.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 1, 0));
        ci.addLabel("loop");
        ci.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 2, 4));
        ci.addInstruction(new BuilderInstruction22t(Opcode.IF_GE, 1, 2, ci.getLabel("end")));
        ci.addInstruction(new BuilderInstruction21ih(Opcode.CONST_HIGH16, 2, EMPTY));
        ci.addInstruction(new BuilderInstruction23x(Opcode.APUT, 2, 0, 1));
        ci.addInstruction(new BuilderInstruction22b(Opcode.ADD_INT_LIT8, 1, 1, 1));
        ci.addInstruction(new BuilderInstruction10t(Opcode.GOTO, ci.getLabel("loop")));
        ci.addLabel("end");
        ci.addInstruction(new BuilderInstruction21c(Opcode.SPUT_OBJECT, 0, f("slots", ints)));
        ci.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));

        // static boolean local(Context c): cached; true when tv.ouya.controllerdata is unreachable. v0..v2, p0 = v3.
        String cr = "Landroid/content/ContentResolver;", cpc = "Landroid/content/ContentProviderClient;";
        MethodImplementationBuilder lo = new MethodImplementationBuilder(4);
        lo.addInstruction(new BuilderInstruction21c(Opcode.SGET, 0, f("state", "I")));
        lo.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 0, lo.getLabel("probe")));
        lo.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 1, 1));
        lo.addInstruction(new BuilderInstruction22t(Opcode.IF_EQ, 0, 1, lo.getLabel("yes")));
        lo.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 0));
        lo.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 0));
        lo.addLabel("yes");
        lo.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 1));
        lo.addLabel("probe");
        lo.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 3, lo.getLabel("unknown")));
        lo.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 3, 0, 0, 0, 0, m(CONTEXT, "getContentResolver", List.of(), cr)));
        lo.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        lo.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 0, lo.getLabel("unknown")));
        lo.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 1, new ImmutableStringReference(AUTHORITY)));
        lo.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, m(cr, "acquireContentProviderClient", List.of("Ljava/lang/String;"), cpc)));
        lo.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        lo.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 0, lo.getLabel("missing")));
        lo.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, m(cpc, "release", List.of(), "Z")));
        lo.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 1, 2));
        lo.addInstruction(new BuilderInstruction21c(Opcode.SPUT, 1, f("state", "I")));
        lo.addLabel("unknown");
        lo.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 0));
        lo.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 0));
        lo.addLabel("missing");
        lo.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 1, 1));
        lo.addInstruction(new BuilderInstruction21c(Opcode.SPUT, 1, f("state", "I")));
        lo.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 1));

        // static boolean eligible(int id): PocketMicro virtual pad (-1) or a gamepad/joystick InputDevice. v0..v2, p0 = v3.
        String dev = "Landroid/view/InputDevice;";
        MethodImplementationBuilder el = new MethodImplementationBuilder(4);
        el.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, -1));
        el.addInstruction(new BuilderInstruction22t(Opcode.IF_NE, 3, 0, el.getLabel("real")));
        el.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 1));
        el.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 0));
        el.addLabel("real");
        el.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, 3, 0, 0, 0, 0, m(dev, "getDevice", List.of("I"), dev)));
        el.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        el.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 0, el.getLabel("no")));
        el.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, m(dev, "getSources", List.of(), "I")));
        el.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
        el.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, 1, 0x401));                 // SOURCE_GAMEPAD
        el.addInstruction(new BuilderInstruction23x(Opcode.AND_INT, 2, 0, 1));
        el.addInstruction(new BuilderInstruction22t(Opcode.IF_EQ, 2, 1, el.getLabel("yes")));
        el.addInstruction(new BuilderInstruction31i(Opcode.CONST, 1, 0x1000010));               // SOURCE_JOYSTICK
        el.addInstruction(new BuilderInstruction23x(Opcode.AND_INT, 2, 0, 1));
        el.addInstruction(new BuilderInstruction22t(Opcode.IF_EQ, 2, 1, el.getLabel("yes")));
        el.addLabel("no");
        el.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 0));
        el.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 0));
        el.addLabel("yes");
        el.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 1));
        el.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 0));

        // static int playerFor(int id): existing slot, else first free slot for an eligible device, else -1. v0..v4, p0 = v5.
        MethodImplementationBuilder pf = new MethodImplementationBuilder(6);
        pf.addInstruction(new BuilderInstruction21c(Opcode.SGET_OBJECT, 0, f("slots", ints)));
        pf.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 1, 0));
        pf.addLabel("find");
        pf.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 2, 4));
        pf.addInstruction(new BuilderInstruction22t(Opcode.IF_GE, 1, 2, pf.getLabel("assign")));
        pf.addInstruction(new BuilderInstruction23x(Opcode.AGET, 2, 0, 1));
        pf.addInstruction(new BuilderInstruction22t(Opcode.IF_NE, 2, 5, pf.getLabel("next")));
        pf.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 1));
        pf.addLabel("next");
        pf.addInstruction(new BuilderInstruction22b(Opcode.ADD_INT_LIT8, 1, 1, 1));
        pf.addInstruction(new BuilderInstruction10t(Opcode.GOTO, pf.getLabel("find")));
        pf.addLabel("assign");
        pf.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, 5, 0, 0, 0, 0, m(PLAYERS, "eligible", List.of("I"), "Z")));
        pf.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 2));
        pf.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 2, pf.getLabel("none")));
        pf.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 1, 0));
        pf.addLabel("scan");
        pf.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 2, 4));
        pf.addInstruction(new BuilderInstruction22t(Opcode.IF_GE, 1, 2, pf.getLabel("none")));
        pf.addInstruction(new BuilderInstruction23x(Opcode.AGET, 2, 0, 1));
        pf.addInstruction(new BuilderInstruction21ih(Opcode.CONST_HIGH16, 3, EMPTY));
        pf.addInstruction(new BuilderInstruction22t(Opcode.IF_NE, 2, 3, pf.getLabel("snext")));
        pf.addInstruction(new BuilderInstruction23x(Opcode.APUT, 5, 0, 1));
        pf.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 1));
        pf.addLabel("snext");
        pf.addInstruction(new BuilderInstruction22b(Opcode.ADD_INT_LIT8, 1, 1, 1));
        pf.addInstruction(new BuilderInstruction10t(Opcode.GOTO, pf.getLabel("scan")));
        pf.addLabel("none");
        pf.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 1, -1));
        pf.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 1));

        // static int deviceFor(int player): slot's device id, else -1. v0..v1, p0 = v2.
        MethodImplementationBuilder df = new MethodImplementationBuilder(3);
        df.addInstruction(new BuilderInstruction21t(Opcode.IF_LTZ, 2, df.getLabel("none")));
        df.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 4));
        df.addInstruction(new BuilderInstruction22t(Opcode.IF_GE, 2, 0, df.getLabel("none")));
        df.addInstruction(new BuilderInstruction21c(Opcode.SGET_OBJECT, 0, f("slots", ints)));
        df.addInstruction(new BuilderInstruction23x(Opcode.AGET, 1, 0, 2));
        df.addInstruction(new BuilderInstruction21ih(Opcode.CONST_HIGH16, 0, EMPTY));
        df.addInstruction(new BuilderInstruction22t(Opcode.IF_EQ, 1, 0, df.getLabel("none")));
        df.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 1));
        df.addLabel("none");
        df.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 1, -1));
        df.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 1));

        int st = AccessFlags.STATIC.getValue();
        List<Field> fields = List.of(new ImmutableField(PLAYERS, "slots", ints, AccessFlags.PRIVATE.getValue() | st, null, Set.of(), Set.of()),
                new ImmutableField(PLAYERS, "state", "I", AccessFlags.PRIVATE.getValue() | st, null, Set.of(), Set.of()));
        List<Method> methods = List.of(
                new ImmutableMethod(PLAYERS, "<clinit>", List.of(), "V", st | AccessFlags.CONSTRUCTOR.getValue(), Set.of(), Set.of(), ci.getMethodImplementation()),
                new ImmutableMethod(PLAYERS, "local", List.of(p(CONTEXT)), "Z", st, Set.of(), Set.of(), lo.getMethodImplementation()),
                new ImmutableMethod(PLAYERS, "eligible", List.of(p("I")), "Z", st, Set.of(), Set.of(), el.getMethodImplementation()),
                new ImmutableMethod(PLAYERS, "playerFor", List.of(p("I")), "I", st, Set.of(), Set.of(), pf.getMethodImplementation()),
                new ImmutableMethod(PLAYERS, "deviceFor", List.of(p("I")), "I", st, Set.of(), Set.of(), df.getMethodImplementation()));
        return new ImmutableClassDef(PLAYERS, AccessFlags.FINAL.getValue() | AccessFlags.SYNTHETIC.getValue(), "Ljava/lang/Object;", List.of(), null, Set.of(), fields, methods);
    }

    private static Result run(Map<String, byte[]> input, boolean apply) {
        Result r = new Result();
        try {
            for (var file : input.entrySet()) {
                DexBackedDexFile dex = new DexBackedDexFile(null, file.getValue());
                ClassDef controller = null; boolean done = false;
                for (ClassDef c : dex.getClasses()) { if (c.getType().equals(CONTROLLER)) controller = c; if (c.getType().equals(PLAYERS)) done = true; }
                if (controller == null) continue;
                if (done) { r.reason = "OUYA controller players already assigned locally"; return r; }
                if (!matches(controller)) { r.status = CompatibilityEvidence.Status.UNSUPPORTED; r.reason = "OuyaController player lookup layout not recognised; unchanged"; return r; }
                for (Method mm : controller.getMethods()) if ((lookup(mm, "getPlayerNumFromDeviceId") || lookup(mm, "getDeviceIdFromPlayerNum")) && mm.getImplementation().getRegisterCount() < 2) {
                    r.status = CompatibilityEvidence.Status.UNSUPPORTED; r.reason = "no scratch register in OuyaController lookups; unchanged"; return r;
                }
                if (apply) {
                    DexPool pool = new DexPool(dex.getOpcodes());
                    for (ClassDef c : dex.getClasses()) {
                        if (!c.getType().equals(CONTROLLER)) { pool.internClass(c); continue; }
                        List<Method> methods = new ArrayList<>();
                        for (Method mm : c.getMethods())
                            methods.add(lookup(mm, "getPlayerNumFromDeviceId") ? prefixed(mm, "playerFor") : lookup(mm, "getDeviceIdFromPlayerNum") ? prefixed(mm, "deviceFor") : mm);
                        pool.internClass(new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getFields(), methods));
                    }
                    pool.internClass(playersClass());
                    MemoryDataStore out = new MemoryDataStore(); pool.writeTo(out); r.replacements.put(file.getKey(), out.getData());
                }
                r.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
                r.reason = "OuyaController player numbers: without the OUYA controller provider (" + AUTHORITY + "), the first gamepads/joysticks "
                        + "and PocketMicro's touch pad to send input become players 1-4 (keyboards and phone buttons excluded) instead of every controller being player -1.";
                return r;
            }
        } catch (Exception e) {
            r.replacements.clear(); r.status = CompatibilityEvidence.Status.UNKNOWN; r.reason = e + "; original DEX retained";
        }
        return r;
    }
}
