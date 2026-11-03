package io.github.hozdogus.pocketouya;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;

public final class OuyaControllerPlayersRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String C = OuyaControllerPlayersRule.CONTROLLER;

    static Map<String, byte[]> input() throws Exception {
        List<Method> ms = new ArrayList<>();
        for (String n : List.of("getPlayerNumFromDeviceId", "getDeviceIdFromPlayerNum"))
            ms.add(new ImmutableMethod(C, n, List.of(new ImmutableMethodParameter("I", Set.of(), null)), "I", 10, Set.of(), Set.of(),
                    new ImmutableMethodImplementation(4, List.of(new ImmutableInstruction11n(Opcode.CONST_4, 0, -1), new ImmutableInstruction11x(Opcode.RETURN, 0)), List.of(), List.of())));
        Field ctx = new ImmutableField(C, "appContext", "Landroid/content/Context;", 10, null, Set.of(), Set.of());
        ClassDef c = new ImmutableClassDef(C, 1, "Ljava/lang/Object;", List.of(), null, Set.of(), List.of(ctx), ms);
        return new LinkedHashMap<>(Map.of("classes.dex", GeneralCompatibilityTest.dex(c)));
    }

    // --- minimal interpreter for the added class ---
    static Map<String, Method> methods = new HashMap<>();
    static Map<String, Object> statics = new HashMap<>();
    static Map<Integer, Integer> deviceSources = new HashMap<>();

    static Object call(String name, Object... args) {
        Method m = methods.get(name); var impl = m.getImplementation();
        Map<Integer, Instruction> code = new HashMap<>(); int at = 0;
        for (Instruction i : impl.getInstructions()) { code.put(at, i); at += i.getCodeUnits(); }
        Object[] r = new Object[impl.getRegisterCount()];
        for (int k = 0; k < args.length; k++) r[r.length - args.length + k] = args[k];
        Object result = null; int pc = 0;
        for (int step = 0; step < 500; step++) {
            Instruction i = code.get(pc); int next = pc + i.getCodeUnits();
            int a = i instanceof OneRegisterInstruction ? ((OneRegisterInstruction) i).getRegisterA() : -1;
            switch (i.getOpcode()) {
                case CONST_4: case CONST_16: case CONST: case CONST_HIGH16: r[a] = (int) ((NarrowLiteralInstruction) i).getNarrowLiteral(); break;
                case NEW_ARRAY: r[a] = new int[(int) r[((TwoRegisterInstruction) i).getRegisterB()]]; break;
                case SGET_OBJECT: case SGET: { String f = ((FieldReference) ((ReferenceInstruction) i).getReference()).getName(); r[a] = statics.getOrDefault(f, i.getOpcode() == Opcode.SGET ? (Object) 0 : null); break; }
                case SPUT_OBJECT: case SPUT: statics.put(((FieldReference) ((ReferenceInstruction) i).getReference()).getName(), r[a]); break;
                case AGET: { var t = (ThreeRegisterInstruction) i; r[a] = ((int[]) r[t.getRegisterB()])[(int) r[t.getRegisterC()]]; break; }
                case APUT: { var t = (ThreeRegisterInstruction) i; ((int[]) r[t.getRegisterB()])[(int) r[t.getRegisterC()]] = (int) r[a]; break; }
                case ADD_INT_LIT8: r[a] = (int) r[((TwoRegisterInstruction) i).getRegisterB()] + ((NarrowLiteralInstruction) i).getNarrowLiteral(); break;
                case AND_INT: { var t = (ThreeRegisterInstruction) i; r[a] = (int) r[t.getRegisterB()] & (int) r[t.getRegisterC()]; break; }
                case IF_EQZ: case IF_LTZ: { Object v = r[a]; int n = v == null ? 0 : v instanceof Integer ? (int) v : (v instanceof Boolean ? ((Boolean) v ? 1 : 0) : 1);
                    if (i.getOpcode() == Opcode.IF_EQZ ? n == 0 : n < 0) next = pc + ((OffsetInstruction) i).getCodeOffset(); break; }
                case IF_GE: case IF_NE: case IF_EQ: { int x = (int) r[a], y = (int) r[((TwoRegisterInstruction) i).getRegisterB()];
                    if (i.getOpcode() == Opcode.IF_GE ? x >= y : i.getOpcode() == Opcode.IF_NE ? x != y : x == y) next = pc + ((OffsetInstruction) i).getCodeOffset(); break; }
                case GOTO: next = pc + ((OffsetInstruction) i).getCodeOffset(); break;
                case INVOKE_STATIC: case INVOKE_VIRTUAL: {
                    var f = (FiveRegisterInstruction) i; MethodReference ref = (MethodReference) ((ReferenceInstruction) i).getReference();
                    if (ref.getName().equals("getDevice")) result = deviceSources.containsKey((int) r[f.getRegisterC()]) ? r[f.getRegisterC()] : null;
                    else if (ref.getName().equals("getSources")) result = deviceSources.get((int) r[f.getRegisterC()]);
                    else { Object res = call(ref.getName(), r[f.getRegisterC()]); result = res instanceof Boolean ? ((Boolean) res ? 1 : 0) : res; }
                    break;
                }
                case MOVE_RESULT: case MOVE_RESULT_OBJECT: r[a] = result; break;
                case RETURN: return m.getReturnType().equals("Z") ? (Object) ((int) r[a] != 0) : r[a];
                case RETURN_VOID: return null;
                default: throw new AssertionError("unexpected " + i.getOpcode() + " in " + name);
            }
            pc = next;
        }
        throw new AssertionError("no return in " + name);
    }

    public static void main(String[] args) throws Exception {
        var in = input();
        check(OuyaControllerPlayersRule.inspect(in).status == CompatibilityEvidence.Status.SUPPORTED, "OUYA controller lookups detected");
        var r = OuyaControllerPlayersRule.repair(in);
        check(r.status == CompatibilityEvidence.Status.VERIFIED, "repair verified: " + r.reason);
        Map<String, ClassDef> out = new HashMap<>(); for (ClassDef c : new DexBackedDexFile(null, r.replacements.get("classes.dex")).getClasses()) out.put(c.getType(), c);
        for (Method m : out.get(C).getMethods()) {
            List<Instruction> code = new ArrayList<>(); for (Instruction i : m.getImplementation().getInstructions()) code.add(i);
            String table = ((MethodReference) ((ReferenceInstruction) code.get(4)).getReference()).getName();
            check(((MethodReference) ((ReferenceInstruction) code.get(1)).getReference()).getName().equals("local") && code.get(3).getOpcode() == Opcode.IF_EQZ, "lookup checks the provider first");
            check(table.equals(m.getName().equals("getPlayerNumFromDeviceId") ? "playerFor" : "deviceFor"), "lookup answered from the local table");
            check(((FiveRegisterInstruction) code.get(4)).getRegisterC() == 3, "argument register passed");
            check(code.get(code.size() - 2).getOpcode() == Opcode.CONST_4, "original body kept after the prefix");
        }
        ClassDef players = out.get(OuyaControllerPlayersRule.PLAYERS);
        check(players != null, "player table class added");
        for (Method m : players.getMethods()) methods.put(m.getName(), m);
        call("<clinit>");
        deviceSources.put(7, 0x2101);          // touchscreen + keyboard: never a player
        deviceSources.put(12, 0x1000511);      // Bluetooth gamepad
        deviceSources.put(13, 0x1000010);      // joystick-only device
        check((int) call("playerFor", 7) == -1, "touchscreen/keyboard not a player");
        check((int) call("playerFor", -1) == 0, "PocketOuya touch pad becomes player 1 (index 0)");
        check((int) call("playerFor", 12) == 1 && (int) call("playerFor", 12) == 1, "gamepad becomes player 2 and keeps it");
        check((int) call("playerFor", -1) == 0, "touch pad keeps its slot");
        check((int) call("deviceFor", 1) == 12 && (int) call("deviceFor", 0) == -1 && (int) call("deviceFor", 3) == -1 && (int) call("deviceFor", 9) == -1, "reverse lookup");
        check((int) call("playerFor", 13) == 2, "joystick accepted");
        check((int) call("playerFor", 99) == -1, "unknown device rejected");
        statics.put("state", 1); check((Boolean) call("local", (Object) null), "cached: provider missing");
        statics.put("state", 2); check(!(Boolean) call("local", (Object) null), "cached: provider present keeps SDK behaviour");
        statics.put("state", 0); check(!(Boolean) call("local", (Object) null), "no context: SDK behaviour");
        var again = new LinkedHashMap<>(in); again.putAll(r.replacements);
        check(OuyaControllerPlayersRule.repair(again).status == CompatibilityEvidence.Status.SKIPPED, "idempotent");
        System.out.println("OuyaControllerPlayersRuleTest: PASS");
    }
}
