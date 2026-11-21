package com.hcode91.pocketmicro;

import java.util.*;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;

/**
 * android.surfaceview-detached-draw (startup repair): a game view class that extends
 * SurfaceView (directly or through GLSurfaceView/VideoView) and overrides onDetachedFromWindow()
 * without calling the parent's version leaves SurfaceView thinking it is still attached. Android
 * 7+ then crashes the main thread in SurfaceView.performDrawFinished after the view is removed
 * (Unity 4's GL view when a video starts; Soul Fjord). The override is kept as it is, because
 * calling GLSurfaceView's detach would stop the engine's GL thread. Instead each launch activity
 * first installs the helper's SurfaceDrawGuard, which ignores only that exact failure.
 */
public final class SurfaceDetachRule {
    public static final String ID = "android.surfaceview-detached-draw";
    static final String GUARD = "Lio/github/hcode91/pocketouya/bridge/SurfaceDrawGuard;";
    static final ImmutableMethodReference INSTALL = new ImmutableMethodReference(GUARD, "install", List.of(), "V");
    static final Set<String> SURFACES = Set.of("Landroid/view/SurfaceView;", "Landroid/opengl/GLSurfaceView;", "Landroid/widget/VideoView;");

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "no SurfaceView subclass skips SurfaceView's detach";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
        public boolean needsBridge;
        public final List<String> views = new ArrayList<>();
        String line() { return status + " " + ID + ": " + reason; }
    }

    private SurfaceDetachRule() {}

    static boolean surface(String type, Map<String, String> parents) {
        Set<String> seen = new HashSet<>();
        for (String t = type; t != null && seen.add(t); t = parents.get(t)) if (SURFACES.contains(t)) return true;
        return false;
    }

    static boolean skipsSuper(Method m) {
        if (m.getImplementation() == null) return false;
        for (Instruction i : m.getImplementation().getInstructions()) {
            if ((i.getOpcode() == Opcode.INVOKE_SUPER || i.getOpcode() == Opcode.INVOKE_SUPER_RANGE) && i instanceof ReferenceInstruction
                    && ((ReferenceInstruction) i).getReference() instanceof MethodReference
                    && ((MethodReference) ((ReferenceInstruction) i).getReference()).getName().equals("onDetachedFromWindow")) return false;
        }
        return true;
    }

    /** Game view types whose onDetachedFromWindow() never reaches SurfaceView's. */
    static List<String> find(Map<String, byte[]> dex) {
        Map<String, String> parents = new HashMap<>(); List<ClassDef> all = new ArrayList<>();
        for (byte[] bytes : dex.values()) for (ClassDef c : new DexBackedDexFile(null, bytes).getClasses()) { parents.put(c.getType(), c.getSuperclass()); all.add(c); }
        List<String> out = new ArrayList<>();
        for (ClassDef c : all) {
            if (c.getType().startsWith("Lio/github/hcode91/pocketouya/") || c.getType().startsWith("Lcom/hcode91/pocketmicro/") || !surface(c.getSuperclass(), parents)) continue;
            for (Method m : c.getMethods())
                if (m.getName().equals("onDetachedFromWindow") && m.getReturnType().equals("V") && !m.getParameterTypes().iterator().hasNext() && skipsSuper(m)) out.add(c.getType());
        }
        Collections.sort(out);
        return out;
    }

    public static Result apply(Map<String, byte[]> dex, Set<String> launchers) {
        Result result = new Result();
        try {
            result.views.addAll(find(dex));
            if (result.views.isEmpty()) return result;
            LaunchHook.Result hook = LaunchHook.prepend(dex, launchers, INSTALL);
            if (hook.hooked == 0 && hook.already == 0) { result.status = CompatibilityEvidence.Status.UNSUPPORTED; result.reason = result.views + " skip SurfaceView's detach, but no launch activity was found to install the guard"; return result; }
            if (hook.hooked == 0) { result.status = CompatibilityEvidence.Status.SKIPPED; result.reason = "launch activities already install the guard; reimport the original APK"; return result; }
            result.replacements.putAll(hook.dex);
            result.needsBridge = true;
            result.status = CompatibilityEvidence.Status.VERIFIED;
            result.reason = result.views.size() + " view class(es) " + result.views + " skip SurfaceView's detach (kept as is, so the engine's GL thread survives). "
                    + hook.hooked + " launch activit" + (hook.hooked == 1 ? "y installs" : "ies install") + " a main-thread guard that ignores only Android 7+'s "
                    + "SurfaceView.performDrawFinished null-parent failure after such a view is removed; other failures still crash. Logged as PocketOuyaSurfaceGuard.";
        } catch (Exception e) {
            result.replacements.clear(); result.needsBridge = false;
            result.status = CompatibilityEvidence.Status.UNKNOWN; result.reason = e.getMessage() + "; not applied";
        }
        return result;
    }
}
