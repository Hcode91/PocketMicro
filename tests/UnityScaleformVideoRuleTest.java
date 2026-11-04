package io.github.hozdogus.pocketouya;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;

public final class UnityScaleformVideoRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String P = UnityScaleformVideoRule.PLAYER;

    static Method show() {
        // Original shape: posts a runnable (stand-in: one call) then returns; 10 registers, 8 of them parameters.
        var body = new ImmutableMethodImplementation(10, List.of(
                new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 2, 0, 0, 0, 0, new ImmutableMethodReference(P, "post", List.of(), "V")),
                new ImmutableInstruction10x(Opcode.RETURN_VOID)), List.of(), List.of());
        List<ImmutableMethodParameter> params = new ArrayList<>(); for (String t : UnityScaleformVideoRule.PARAMS) params.add(new ImmutableMethodParameter(t, Set.of(), null));
        return new ImmutableMethod(P, "showVideoPlayer", params, "V", AccessFlags.PROTECTED.getValue(), Set.of(), Set.of(), body);
    }

    static List<Instruction> body(byte[] dex) {
        for (ClassDef c : new DexBackedDexFile(null, dex).getClasses()) for (Method m : c.getMethods()) if (m.getName().equals("showVideoPlayer")) {
            List<Instruction> out = new ArrayList<>(); for (Instruction i : m.getImplementation().getInstructions()) out.add(i); return out;
        }
        throw new AssertionError("missing showVideoPlayer");
    }

    public static void main(String[] args) throws Exception {
        Map<String, byte[]> dex = new LinkedHashMap<>();
        dex.put("classes.dex", GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls(P, "Ljava/lang/Object;", List.of(show()))));
        List<String> sf = List.of("classes.dex", "lib/armeabi-v7a/libunity.so", "lib/armeabi-v7a/libgfxunity3d.so");

        UnityScaleformVideoRule.Result plain = UnityScaleformVideoRule.apply(dex, List.of("classes.dex", "lib/armeabi-v7a/libunity.so"));
        check(plain.status == CompatibilityEvidence.Status.SKIPPED && plain.replacements.isEmpty() && !plain.needsBridge, "Unity games without Scaleform keep their videos");

        UnityScaleformVideoRule.Result r = UnityScaleformVideoRule.apply(dex, sf);
        check(r.status == CompatibilityEvidence.Status.VERIFIED && r.needsBridge, r.line());
        List<Instruction> b = body(r.replacements.get("classes.dex"));
        check(b.size() == 2 && b.get(0).getOpcode() == Opcode.INVOKE_STATIC && b.get(1).getOpcode() == Opcode.RETURN_VOID, "logs then returns");
        MethodReference ref = (MethodReference) ((ReferenceInstruction) b.get(0)).getReference();
        check(ref.getDefiningClass().equals(UnityScaleformVideoRule.SKIPPED.getDefiningClass()), "calls the helper logger");
        check(((FiveRegisterInstruction) b.get(0)).getRegisterC() == 3, "passes p1 (v3 of 10 registers) as the file name");

        Map<String, byte[]> again = new LinkedHashMap<>(r.replacements);
        UnityScaleformVideoRule.Result twice = UnityScaleformVideoRule.apply(again, sf);
        check(twice.replacements.isEmpty() && !twice.needsBridge, "second pass unchanged: " + twice.line());

        Map<String, byte[]> other = new LinkedHashMap<>();
        other.put("classes.dex", GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls("Lorg/example/Game;", "Ljava/lang/Object;", List.of())));
        check(UnityScaleformVideoRule.apply(other, sf).status == CompatibilityEvidence.Status.UNSUPPORTED, "plugin without a recognised player is reported");
        System.out.println("UnityScaleformVideoRuleTest OK");
    }
}
