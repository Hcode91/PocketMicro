package com.hcode91.pocketmicro;

import com.hcode91.pocketmicro.bridge.SurfaceDrawGuard;
import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;

/** Unity 4 shape: ac extends GLSurfaceView and skips its detach; UnityPlayer$24 extends ac. */
public final class SurfaceDetachRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String GL = "Landroid/opengl/GLSurfaceView;", VIEW = "Lcom/unity3d/player/ac;", INNER = "Lcom/unity3d/player/UnityPlayer$24;";
    static final String MAIN = GeneralCompatibilityTest.LAUNCHER;

    static Method detach(String owner, String superType) {
        List<Instruction> code = new ArrayList<>();
        if (superType != null) code.add(new ImmutableInstruction35c(Opcode.INVOKE_SUPER, 1, 0, 0, 0, 0, 0, new ImmutableMethodReference(superType, "onDetachedFromWindow", List.of(), "V")));
        code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        return new ImmutableMethod(owner, "onDetachedFromWindow", List.of(), "V", AccessFlags.PROTECTED.getValue(), Set.of(), Set.of(), new ImmutableMethodImplementation(1, code, List.of(), List.of()));
    }

    static NullPointerException npe(String cls, String method) {
        NullPointerException e = new NullPointerException("requestTransparentRegion on null");
        e.setStackTrace(new StackTraceElement[]{new StackTraceElement(cls, method, "X.java", 1), new StackTraceElement("android.os.Handler", "handleCallback", "Handler.java", 2)});
        return e;
    }

    public static void main(String[] args) throws Exception {
        // Detection.
        Map<String, byte[]> dex = new LinkedHashMap<>();
        dex.put("classes.dex", GeneralCompatibilityTest.dex(
                GeneralCompatibilityTest.cls(VIEW, GL, List.of(detach(VIEW, null))),
                GeneralCompatibilityTest.cls(INNER, VIEW, List.of(detach(INNER, VIEW))),
                GeneralCompatibilityTest.cls("Lorg/example/Fine;", "Landroid/view/SurfaceView;", List.of(detach("Lorg/example/Fine;", "Landroid/view/SurfaceView;"))),
                GeneralCompatibilityTest.cls("Lorg/example/NotAView;", "Ljava/lang/Object;", List.of(detach("Lorg/example/NotAView;", null))),
                GeneralCompatibilityTest.cls(MAIN, "Landroid/app/Activity;", List.of())));
        check(SurfaceDetachRule.find(dex).equals(List.of(VIEW)), "only the view that skips SurfaceView's detach: " + SurfaceDetachRule.find(dex));

        SurfaceDetachRule.Result r = SurfaceDetachRule.apply(dex, Set.of(MAIN));
        check(r.status == CompatibilityEvidence.Status.VERIFIED && r.needsBridge, r.line());
        ClassDef main = null; for (ClassDef c : new DexBackedDexFile(null, r.replacements.get("classes.dex")).getClasses()) if (c.getType().equals(MAIN)) main = c;
        Instruction first = null; for (Method m : main.getMethods()) if (m.getName().equals("onCreate")) first = m.getImplementation().getInstructions().iterator().next();
        check(first != null && ((MethodReference) ((ReferenceInstruction) first).getReference()).getDefiningClass().equals(SurfaceDetachRule.GUARD), "guard installed first in onCreate");
        // The view's own detach override is left exactly as it was.
        for (ClassDef c : new DexBackedDexFile(null, r.replacements.get("classes.dex")).getClasses()) if (c.getType().equals(VIEW))
            for (Method m : c.getMethods()) if (m.getName().equals("onDetachedFromWindow")) check(SurfaceDetachRule.skipsSuper(m), "view detach unchanged");

        Map<String, byte[]> again = new LinkedHashMap<>(dex); again.putAll(r.replacements);
        check(SurfaceDetachRule.apply(again, Set.of(MAIN)).replacements.isEmpty(), "second pass adds nothing");
        Map<String, byte[]> clean = new LinkedHashMap<>(); clean.put("classes.dex", GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls(MAIN, "Landroid/app/Activity;", List.of()),
                GeneralCompatibilityTest.cls("Lorg/example/Fine;", GL, List.of(detach("Lorg/example/Fine;", GL)))));
        SurfaceDetachRule.Result none = SurfaceDetachRule.apply(clean, Set.of(MAIN));
        check(none.status == CompatibilityEvidence.Status.SKIPPED && none.replacements.isEmpty() && !none.needsBridge, "games whose views call super are untouched");
        check(SurfaceDetachRule.apply(dex, Set.of("Lmissing/Main;")).status == CompatibilityEvidence.Status.UNSUPPORTED, "no launcher reported");

        // Guard behaviour: ignores only SurfaceView.performDrawFinished NPEs.
        check(SurfaceDrawGuard.detachedSurfaceDraw(npe("android.view.SurfaceView", "performDrawFinished")), "target failure recognised");
        check(!SurfaceDrawGuard.detachedSurfaceDraw(npe("android.view.SurfaceView", "onAttachedToWindow")), "other SurfaceView failures not ignored");
        check(!SurfaceDrawGuard.detachedSurfaceDraw(npe("com.unity3d.player.UnityPlayer", "performDrawFinished")), "game failures not ignored");
        check(!SurfaceDrawGuard.detachedSurfaceDraw(new IllegalStateException()), "other exception types not ignored");
        android.os.Looper.script.clear();
        android.os.Looper.script.add(npe("android.view.SurfaceView", "performDrawFinished"));
        android.os.Looper.script.add(npe("android.view.SurfaceView", "performDrawFinished"));
        SurfaceDrawGuard.loop(); // survives both and returns when the loop ends
        android.os.Looper.script.add(npe("android.view.SurfaceView", "performDrawFinished"));
        IllegalArgumentException real = new IllegalArgumentException("game bug");
        android.os.Looper.script.add(real);
        try { SurfaceDrawGuard.loop(); throw new AssertionError("real failure swallowed"); } catch (IllegalArgumentException expected) { check(expected == real, "same failure rethrown"); }
        System.out.println("SurfaceDetachRuleTest OK");
    }
}
