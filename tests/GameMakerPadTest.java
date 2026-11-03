package io.github.hozdogus.pocketouya;

import io.github.hcode91.pocketouya.bridge.*;
import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;

public final class GameMakerPadTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String R = GameMakerPadPatcher.RUNNER;

    static Method stub(String name, String params, String ret) {
        List<ImmutableMethodParameter> p = new ArrayList<>(); int regs = 1;
        for (char c : params.toCharArray()) { p.add(new ImmutableMethodParameter(String.valueOf(c), Set.of(), null)); regs++; }
        Instruction body;
        List<Instruction> code = new ArrayList<>();
        if (ret.equals("I") || ret.equals("Z")) { code.add(new ImmutableInstruction11n(Opcode.CONST_4, 0, 0)); code.add(new ImmutableInstruction11x(Opcode.RETURN, 0)); }
        else { code.add(new ImmutableInstruction11n(Opcode.CONST_4, 0, 0)); code.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)); }
        return new ImmutableMethod(R, name, p, ret, AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue(), Set.of(), Set.of(), new ImmutableMethodImplementation(regs, code, List.of(), List.of()));
    }
    static ClassDef runner(boolean withRegister) {
        List<Method> m = new ArrayList<>(List.of(stub("GamepadsCount", "", "I"), stub("GamepadConnected", "I", "Z"), stub("GamepadDescription", "I", "Ljava/lang/String;"),
                stub("GamepadButtonValues", "I", "[F"), stub("GamepadAxesValues", "I", "[F"), stub("GamepadGMLMapping", "II", "I"), stub("KeyEvent", "II", "Z")));
        if (withRegister) m.add(new ImmutableMethod(R, "registerGamepadConnected", GeneralCompatibilityTest.params("I", "I", "I"), "V",
                AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue() | AccessFlags.NATIVE.getValue(), Set.of(), Set.of(), null));
        return GeneralCompatibilityTest.cls(R, "Ljava/lang/Object;", m);
    }

    public static void main(String[] args) throws Exception {
        // Converter: wraps all six polling methods, keeps originals, leaves other methods alone.
        check(GameMakerPadPatcher.matches(runner(true)), "runner recognised");
        check(!GameMakerPadPatcher.matches(runner(false)), "runner without native registration hook rejected");
        ClassDef wrapped = new DexBackedDexFile(null, GeneralCompatibilityTest.dex(GameMakerPadPatcher.rewrite(runner(true)))).getClasses().iterator().next();
        Map<String, Method> byName = new HashMap<>(); for (Method m : wrapped.getMethods()) byName.put(m.getName(), m);
        for (String n : GameMakerPadPatcher.METHODS.keySet()) {
            Method w = byName.get(n), o = byName.get(GameMakerPadPatcher.PREFIX + n);
            check(w != null && o != null && AccessFlags.PUBLIC.isSet(w.getAccessFlags()) && AccessFlags.PRIVATE.isSet(o.getAccessFlags()), n + " wrapped with private original");
            boolean pad = false, orig = false;
            for (Instruction i : w.getImplementation().getInstructions()) if (i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof MethodReference) {
                MethodReference r = (MethodReference) ((ReferenceInstruction) i).getReference();
                if (r.getDefiningClass().equals(GameMakerPadPatcher.PAD)) pad = true;
                if (r.getName().equals(GameMakerPadPatcher.PREFIX + n)) orig = true;
            }
            check(pad && orig, n + " consults the touch pad and falls back to the game's own code");
        }
        check(byName.containsKey("KeyEvent") && !byName.containsKey(GameMakerPadPatcher.PREFIX + "KeyEvent"), "unrelated methods untouched");
        check(!GameMakerPadPatcher.matches(wrapped), "already wrapped: not wrapped twice");

        // Bridge: slot allocation, registration, button/axis values in GameMaker's gp_* order.
        com.yoyogames.runner.RunnerJNILib.realControllers = 1;
        List<String> forwarded = new ArrayList<>();
        VirtualPad.Sink keys = new VirtualPad.Sink() { public void key(int k, int a) { forwarded.add(k + ":" + a); } public void axis(int x, float v) {} };
        GameMakerPadSink sink = new GameMakerPadSink(GameMakerPadTest.class.getClassLoader(), keys);
        VirtualPad pad = new VirtualPad(sink);
        check(!GameMakerPad.owns(1), "inactive until first input");
        pad.hold(VirtualPad.O, "finger", true);
        check(GameMakerPad.slot() == 1 && com.yoyogames.runner.RunnerJNILib.registeredSlot == 1 && com.yoyogames.runner.RunnerJNILib.registeredButtons == 16 && com.yoyogames.runner.RunnerJNILib.registeredAxes == 4, "registered in first free slot");
        check(GameMakerPad.owns(1) && !GameMakerPad.owns(0) && com.yoyogames.runner.RunnerJNILib.GamepadsCount() == 2, "count includes touch pad; slot 0 still the game's");
        check(GameMakerPad.buttons()[0] == 1f && GameMakerPad.mapping(32769) == 0, "O = gp_face1 pressed");
        pad.hold(VirtualPad.O, "finger", false); check(GameMakerPad.buttons()[0] == 0f, "released");
        pad.hold(VirtualPad.LEFT, "thumb", true);
        check(GameMakerPad.buttons()[14] == 1f && GameMakerPad.axes()[0] == -1f && GameMakerPad.mapping(32785) == 0, "d-pad left = gp_padl and left stick -1");
        pad.rightStick(0.5f, -0.5f);
        check(GameMakerPad.axes()[2] == 0.5f && GameMakerPad.axes()[3] == -0.5f && GameMakerPad.mapping(32787) == 2, "look stick = right stick axes");
        check(forwarded.contains(VirtualPad.O + ":0") && forwarded.contains(VirtualPad.LEFT + ":0"), "keys also forwarded for keyboard menus");
        check(GameMakerPad.mapping(1) == -1 && GameMakerPad.mapping(32789) == -1, "unknown GML codes unmapped");
        pad.releaseAll(); check(GameMakerPad.axes()[0] == 0f && GameMakerPad.buttons()[14] == 0f, "release clears state");
        System.out.println("GameMaker controller route: six runner methods wrapped with fallback, idempotence, slot registration, gp_* buttons, left/right stick axes, key forwarding pass");
    }
}
