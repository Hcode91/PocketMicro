package com.hcode91.pocketmicro;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.MethodImplementationBuilder;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;

/**
 * Wraps the six static controller-polling methods of GameMaker 1.x's
 * com.yoyogames.runner.RunnerJNILib so the PocketOuya touch pad can appear as a connected
 * controller (bridge class GameMakerPad). Each original is kept as a private
 * pocketouyaOriginal_* method and is still used for every slot the touch pad does not own.
 */
final class GameMakerPadPatcher {
    static final String RUNNER = "Lcom/yoyogames/runner/RunnerJNILib;";
    static final String PAD = "Lio/github/hcode91/pocketouya/bridge/GameMakerPad;";
    static final String PREFIX = "pocketouyaOriginal_";
    /** name -> signature */
    static final Map<String, String> METHODS = new LinkedHashMap<>();
    static {
        METHODS.put("GamepadsCount", "()I");
        METHODS.put("GamepadConnected", "(I)Z");
        METHODS.put("GamepadDescription", "(I)Ljava/lang/String;");
        METHODS.put("GamepadButtonValues", "(I)[F");
        METHODS.put("GamepadAxesValues", "(I)[F");
        METHODS.put("GamepadGMLMapping", "(II)I");
    }

    private GameMakerPadPatcher() {}

    static String signature(Method m) { return "(" + String.join("", m.getParameterTypes()) + ")" + m.getReturnType(); }

    /** All six methods present as concrete public static methods, plus the native registration hook; not already wrapped. */
    static boolean matches(ClassDef cls) {
        if (cls == null || !cls.getType().equals(RUNNER)) return false;
        Set<String> found = new HashSet<>(); boolean register = false;
        for (Method m : cls.getMethods()) {
            if (m.getName().startsWith(PREFIX)) return false;
            String sig = METHODS.get(m.getName());
            if (sig != null && sig.equals(signature(m)) && AccessFlags.STATIC.isSet(m.getAccessFlags()) && m.getImplementation() != null) found.add(m.getName());
            if (m.getName().equals("registerGamepadConnected") && signature(m).equals("(III)V") && AccessFlags.NATIVE.isSet(m.getAccessFlags()) && AccessFlags.STATIC.isSet(m.getAccessFlags())) register = true;
        }
        return register && found.equals(METHODS.keySet());
    }

    static ClassDef rewrite(ClassDef cls) {
        List<Method> methods = new ArrayList<>();
        for (Method m : cls.getMethods()) {
            if (!METHODS.containsKey(m.getName()) || !METHODS.get(m.getName()).equals(signature(m))) { methods.add(m); continue; }
            int flags = (m.getAccessFlags() & ~(AccessFlags.PUBLIC.getValue() | AccessFlags.PROTECTED.getValue())) | AccessFlags.PRIVATE.getValue() | AccessFlags.SYNTHETIC.getValue();
            methods.add(new ImmutableMethod(cls.getType(), PREFIX + m.getName(), m.getParameters(), m.getReturnType(), flags, m.getAnnotations(), m.getHiddenApiRestrictions(), m.getImplementation()));
            methods.add(new ImmutableMethod(cls.getType(), m.getName(), m.getParameters(), m.getReturnType(), m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), wrapper(m)));
        }
        return new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(), cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods);
    }

    private static ImmutableMethodReference ref(String owner, String name, List<String> params, String ret) { return new ImmutableMethodReference(owner, name, params, ret); }
    private static ImmutableMethodReference original(Method m) { return ref(RUNNER, PREFIX + m.getName(), stringList(m.getParameterTypes()), m.getReturnType()); }
    private static List<String> stringList(List<? extends CharSequence> in) { List<String> out = new ArrayList<>(); for (CharSequence c : in) out.add(c.toString()); return out; }

    private static org.jf.dexlib2.iface.MethodImplementation wrapper(Method m) {
        String name = m.getName();
        if (name.equals("GamepadsCount")) {
            MethodImplementationBuilder b = new MethodImplementationBuilder(1);
            b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, original(m)));
            b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
            b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, ref(PAD, "count", List.of("I"), "I")));
            b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
            b.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 0));
            return b.getMethodImplementation();
        }
        boolean mapping = name.equals("GamepadGMLMapping");
        // registers: v0 local, v1 = index (p0), v2 = gml (p1, mapping only)
        MethodImplementationBuilder b = new MethodImplementationBuilder(mapping ? 3 : 2);
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0, ref(PAD, "owns", List.of("I"), "Z")));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 0, b.getLabel("game")));
        boolean object = !m.getReturnType().equals("I") && !m.getReturnType().equals("Z");
        switch (name) {
            case "GamepadConnected": b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 1)); break;
            case "GamepadDescription": b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, ref(PAD, "description", List.of(), "Ljava/lang/String;"))); b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0)); break;
            case "GamepadButtonValues": b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, ref(PAD, "buttons", List.of(), "[F"))); b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0)); break;
            case "GamepadAxesValues": b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, ref(PAD, "axes", List.of(), "[F"))); b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0)); break;
            default: b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0, ref(PAD, "mapping", List.of("I"), "I"))); b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 0)); break;
        }
        b.addInstruction(new BuilderInstruction11x(object ? Opcode.RETURN_OBJECT : Opcode.RETURN, 0));
        b.addLabel("game");
        if (mapping) b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, 1, 2, 0, 0, 0, original(m)));
        else b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0, original(m)));
        b.addInstruction(new BuilderInstruction11x(object ? Opcode.MOVE_RESULT_OBJECT : Opcode.MOVE_RESULT, 0));
        b.addInstruction(new BuilderInstruction11x(object ? Opcode.RETURN_OBJECT : Opcode.RETURN, 0));
        return b.getMethodImplementation();
    }
}
