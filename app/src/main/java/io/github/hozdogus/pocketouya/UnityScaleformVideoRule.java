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
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/**
 * unity.scaleform-skip-video (startup repair): Unity 4 plays full-screen videos
 * (Handheld.PlayFullScreenMovie) by pausing the player, removing its GL view, playing the video
 * in Android's MediaPlayer and resuming. Scaleform's Unity plugin (libgfxunity3d.so) does not
 * survive that cycle on modern Android: Soul Fjord's manager loses its render thread and the next
 * UnityRenderEvent crashes in RTCommandQueue::PopCommand (both before the intro video and, with the
 * pause fixed, 0.4 s after it). For Unity games that ship that plugin only,
 * UnityPlayer.showVideoPlayer returns at once after logging the file, so the player is never
 * paused for a video. Cost: those videos (logos, cut-scenes) are not shown.
 */
public final class UnityScaleformVideoRule {
    public static final String ID = "unity.scaleform-skip-video";
    static final String PLAYER = "Lcom/unity3d/player/UnityPlayer;";
    static final List<String> PARAMS = List.of("Ljava/lang/String;", "I", "I", "I", "Z", "I", "I");
    static final ImmutableMethodReference SKIPPED = new ImmutableMethodReference("Lio/github/hcode91/pocketouya/bridge/UnityVideo;", "skipped", List.of("Ljava/lang/String;"), "V");

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "not a Unity game with Scaleform's Unity plugin";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
        public boolean needsBridge;
        String line() { return status + " " + ID + ": " + reason; }
    }

    private UnityScaleformVideoRule() {}

    static boolean scaleform(Collection<String> entries) {
        for (String n : entries) if (n.matches("lib/[^/]+/libgfxunity3d\\.so")) return true;
        return false;
    }

    static boolean target(Method m) {
        if (!m.getDefiningClass().equals(PLAYER) || !m.getName().equals("showVideoPlayer") || !m.getReturnType().equals("V") || m.getImplementation() == null) return false;
        List<String> p = new ArrayList<>(); for (CharSequence t : m.getParameterTypes()) p.add(t.toString());
        return p.equals(PARAMS) && !AccessFlags.STATIC.isSet(m.getAccessFlags());
    }

    static boolean alreadySkips(Method m) {
        for (Instruction i : m.getImplementation().getInstructions())
            if (i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof MethodReference
                    && ((MethodReference) ((ReferenceInstruction) i).getReference()).getDefiningClass().equals(SKIPPED.getDefiningClass())) return true;
        return false;
    }

    public static Result apply(Map<String, byte[]> dex, Collection<String> entries) {
        Result result = new Result();
        try {
            if (!scaleform(entries)) return result;
            int found = 0, already = 0;
            for (var file : dex.entrySet()) {
                DexBackedDexFile d = new DexBackedDexFile(null, file.getValue());
                List<ClassDef> classes = new ArrayList<>(); boolean touched = false;
                for (ClassDef c : d.getClasses()) {
                    if (!c.getType().equals(PLAYER)) { classes.add(c); continue; }
                    List<Method> methods = new ArrayList<>(); boolean changed = false;
                    for (Method m : c.getMethods()) {
                        if (!target(m)) { methods.add(m); continue; }
                        found++;
                        if (alreadySkips(m)) { already++; methods.add(m); continue; }
                        int regs = m.getImplementation().getRegisterCount(), ins = 1 + PARAMS.size(); // this + 7 single-word params
                        if (regs < ins) throw new IllegalStateException("showVideoPlayer has fewer registers than parameters");
                        int file0 = regs - ins + 1; // p1: the video file name
                        var body = new ImmutableMethodImplementation(regs, List.of(
                                new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, file0, 0, 0, 0, 0, SKIPPED),
                                new ImmutableInstruction10x(Opcode.RETURN_VOID)), List.of(), List.of());
                        methods.add(new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(), m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), body));
                        changed = true;
                    }
                    if (changed) { classes.add(new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getFields(), methods)); touched = true; }
                    else classes.add(c);
                }
                if (!touched) continue;
                for (ClassDef c : d.getClasses()) for (Method m : c.getMethods()) if (m.getImplementation() != null) for (Instruction i : m.getImplementation().getInstructions())
                    if (i instanceof org.jf.dexlib2.iface.instruction.formats.UnknownInstruction) throw new IllegalStateException(file.getKey() + " contains unknown instructions (" + c.getType() + "); DEX left unchanged");
                DexPool pool = new DexPool(d.getOpcodes()); for (ClassDef c : classes) pool.internClass(c);
                MemoryDataStore out = new MemoryDataStore(); pool.writeTo(out); result.replacements.put(file.getKey(), out.getData());
            }
            if (found == 0) { result.status = CompatibilityEvidence.Status.UNSUPPORTED; result.reason = "Scaleform's Unity plugin found, but UnityPlayer.showVideoPlayer was not recognised; videos unchanged"; return result; }
            if (result.replacements.isEmpty()) { result.reason = "videos already skipped; reimport the original APK"; return result; }
            result.needsBridge = true;
            result.status = CompatibilityEvidence.Status.VERIFIED;
            result.reason = "Scaleform's Unity plugin (libgfxunity3d.so) crashes when Unity pauses for a full-screen video on modern Android, so "
                    + "UnityPlayer.showVideoPlayer now returns at once and the player keeps running. Intro/cut-scene videos are not shown; each skip is logged as PocketOuyaUnityVideo.";
        } catch (Exception e) {
            result.replacements.clear(); result.needsBridge = false;
            result.status = CompatibilityEvidence.Status.UNKNOWN; result.reason = e.getMessage() + "; videos unchanged";
        }
        return result;
    }
}
