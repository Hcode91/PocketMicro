package io.github.hozdogus.pocketouya;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;

public final class JavascriptInterfaceRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String WV = "Lorg/apache/cordova/CordovaWebView;", API = "Lorg/apache/cordova/ExposedJsApi;", BASE = "Lcom/example/BaseBridge;",
            OTHER = "Lcom/example/GameBridge;", S = "Ljava/lang/String;", ANN = JavascriptInterfaceRule.ANNOTATION;
    static final ImmutableMethodReference ADD = new ImmutableMethodReference(WV, "addJavascriptInterface", List.of("Ljava/lang/Object;", S), "V");

    static Method body(String owner, String name, int flags, List<String> params, String ret, int regs, Instruction... code) {
        List<ImmutableMethodParameter> p = new ArrayList<>(); for (String t : params) p.add(new ImmutableMethodParameter(t, Set.of(), null));
        return new ImmutableMethod(owner, name, p, ret, flags, Set.of(), Set.of(), new ImmutableMethodImplementation(regs, List.of(code), List.of(), List.of()));
    }
    static Instruction ret() { return new ImmutableInstruction10x(Opcode.RETURN_VOID); }
    static Instruction retObj() { return new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0); }
    static Instruction name(int reg) { return new ImmutableInstruction21c(Opcode.CONST_STRING, reg, new ImmutableStringReference("_cordovaNative")); }

    static Map<String, byte[]> input(boolean unresolved) throws Exception {
        // Cordova 2.x: this.exposedJsApi (field) is passed to addJavascriptInterface.
        Field f = new ImmutableField(WV, "exposedJsApi", API, 2, null, Set.of(), Set.of());
        List<Method> wv = new ArrayList<>(List.of(
                body(WV, "exposeJsInterface", 2, List.of(), "V", 3,
                        new ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 2, new ImmutableFieldReference(WV, "exposedJsApi", API)), name(1),
                        new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 2, 0, 1, 0, 0, ADD), ret()),
                // new-instance route and a parameter route (p1 = v2 in a 3-register static... instance method: this = v1, p1 = v2).
                body(WV, "addGame", 1, List.of(), "V", 3,
                        new ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, new ImmutableTypeReference(OTHER)), name(1),
                        new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 2, 0, 1, 0, 0, ADD), ret()),
                body(WV, "addParam", 1, List.of(OTHER), "V", 3, name(0),
                        new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 1, 2, 0, 0, 0, ADD), ret())));
        if (unresolved) wv.add(body(WV, "addUnknown", 1, List.of(), "V", 3,
                new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 2, 0, 0, 0, 0, new ImmutableMethodReference(WV, "toString", List.of(), S)),
                new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), name(1), new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 2, 0, 1, 0, 0, ADD), ret()));
        ClassDef webView = new ImmutableClassDef(WV, 1, "Landroid/webkit/WebView;", List.of(), null, Set.of(), List.of(f), wv);
        ClassDef api = GeneralCompatibilityTest.cls(API, BASE, List.of(
                body(API, "<init>", 0x10001, List.of(), "V", 1, ret()),
                body(API, "exec", 1, List.of(S, S, S, S), S, 6, new ImmutableInstruction11n(Opcode.CONST_4, 0, 0), retObj()),
                body(API, "setNativeToJsBridgeMode", 1, List.of("I"), "V", 2, ret()),
                body(API, "secret", 2, List.of(), "V", 1, ret()),
                body(API, "util", 9, List.of(), "V", 0, ret())));
        ClassDef base = GeneralCompatibilityTest.cls(BASE, "Ljava/lang/Object;", List.of(body(BASE, "retrieveJsMessages", 1, List.of(), S, 2, new ImmutableInstruction11n(Opcode.CONST_4, 0, 0), retObj())));
        ClassDef other = GeneralCompatibilityTest.cls(OTHER, "Ljava/lang/Object;", List.of(body(OTHER, "score", 1, List.of("I"), "V", 2, ret())));
        ClassDef plain = GeneralCompatibilityTest.cls("Lcom/example/Unrelated;", "Ljava/lang/Object;", List.of(body("Lcom/example/Unrelated;", "run", 1, List.of(), "V", 1, ret())));
        return new LinkedHashMap<>(Map.of("classes.dex", GeneralCompatibilityTest.dex(webView, api, base, other, plain)));
    }

    static Method find(byte[] dex, String cls, String name) {
        for (ClassDef c : new DexBackedDexFile(null, dex).getClasses()) if (c.getType().equals(cls)) for (Method m : c.getMethods()) if (m.getName().equals(name)) return m;
        throw new AssertionError("missing " + cls + "." + name);
    }
    static boolean marked(Method m) {
        for (Annotation a : m.getAnnotations()) if (a.getType().equals(ANN)) return a.getVisibility() == AnnotationVisibility.RUNTIME;
        return false;
    }

    public static void main(String[] args) throws Exception {
        check(JavascriptInterfaceRule.inspect(Map.of("classes.dex", GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls("Lx/A;", "Ljava/lang/Object;", List.of()))))
                .status == CompatibilityEvidence.Status.SKIPPED, "no WebView interface: skipped");
        var in = input(false);
        check(JavascriptInterfaceRule.inspect(in).status == CompatibilityEvidence.Status.SUPPORTED, "Cordova bridge detected");
        var r = JavascriptInterfaceRule.repair(in);
        check(r.status == CompatibilityEvidence.Status.VERIFIED, "repair verified: " + r.reason);
        byte[] out = r.replacements.get("classes.dex");
        check(marked(find(out, API, "exec")) && marked(find(out, API, "setNativeToJsBridgeMode")), "field route: public bridge methods marked");
        check(marked(find(out, BASE, "retrieveJsMessages")), "app superclass public methods marked");
        check(!marked(find(out, API, "secret")) && !marked(find(out, API, "util")) && !marked(find(out, API, "<init>")), "private, static and constructors untouched");
        check(marked(find(out, OTHER, "score")), "new-instance and parameter routes resolved");
        check(!marked(find(out, "Lcom/example/Unrelated;", "run")), "unrelated classes untouched");
        var again = new LinkedHashMap<>(in); again.putAll(r.replacements);
        check(JavascriptInterfaceRule.repair(again).status == CompatibilityEvidence.Status.SKIPPED, "idempotent");
        var partial = JavascriptInterfaceRule.repair(input(true));
        check(partial.status == CompatibilityEvidence.Status.VERIFIED && partial.reason.contains("Object type not found at " + WV + ".addUnknown"), "unresolved site reported: " + partial.reason);
        // An Activity passed as the bridge (Flurry-style) is not opened up.
        String ACT = "Lcom/example/AdActivity;";
        ClassDef act = GeneralCompatibilityTest.cls(ACT, "Landroid/app/Activity;", List.of(body(ACT, "onClick", 1, List.of(), "V", 1, ret()),
                body(ACT, "show", 1, List.of(), "V", 3, name(1), new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 0, 2, 1, 0, 0, ADD), ret())));
        var activity = JavascriptInterfaceRule.repair(Map.of("classes.dex", GeneralCompatibilityTest.dex(act)));
        check(activity.status == CompatibilityEvidence.Status.SKIPPED && activity.replacements.isEmpty() && activity.reason.contains("Android components"), "Activity bridge left alone: " + activity.reason);
        System.out.println("JavascriptInterfaceRuleTest: PASS");
    }
}
