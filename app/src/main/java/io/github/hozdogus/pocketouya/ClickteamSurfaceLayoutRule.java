package io.github.hozdogus.pocketouya;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.MethodImplementationBuilder;
import org.jf.dexlib2.builder.MutableMethodImplementation;
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
 * clickteam.surface-layout: the Clickteam Fusion (MMF) Android runtime creates its game SurfaceView
 * from MainView.onSizeChanged, i.e. while the window is in the middle of a layout pass, and then
 * calls setLayoutParams on it. Android 4.x answered that with a fresh layout pass. Android 4.3+
 * drops a layout request made by a view whose parent is still laying out, so the SurfaceView is
 * never measured or placed ("SurfaceView updateSurface: has no frame"), its surface is never
 * created, the GL renderer never starts and the game shows a blank screen forever.
 *
 * Each SurfaceView.setLayoutParams call in the runtime is replaced with a helper that makes the
 * same call and then, only if the SurfaceView has never been placed and its parent already has a
 * size, measures and places it exactly as MainView.onLayout would (its LayoutParams width/height,
 * MATCH_PARENT meaning the parent's size, at its x/y). Later layout passes are unchanged.
 */
public final class ClickteamSurfaceLayoutRule {
    public static final String ID = "clickteam.surface-layout";
    static final String RUNTIME = "LRuntime/MMFRuntime;", SURFACE = "LRuntime/SurfaceView;", MAIN_LP = "LRuntime/MainView$LayoutParams;",
            VIEW = "Landroid/view/View;", LP = "Landroid/view/ViewGroup$LayoutParams;", HELPER = "pocketouyaPlaceSurface";

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "Clickteam Fusion runtime absent";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
    }

    private ClickteamSurfaceLayoutRule() {}
    public static Result inspect(Map<String, byte[]> dex) { return run(dex, false); }
    public static Result repair(Map<String, byte[]> dex) { return run(dex, true); }

    static boolean target(Instruction i) {
        if (i.getOpcode() != Opcode.INVOKE_VIRTUAL || !(i instanceof ReferenceInstruction)) return false;
        if (!(((ReferenceInstruction) i).getReference() instanceof MethodReference)) return false;
        MethodReference r = (MethodReference) ((ReferenceInstruction) i).getReference();
        return r.getDefiningClass().equals(SURFACE) && r.getName().equals("setLayoutParams") && r.getReturnType().equals("V")
                && r.getParameterTypes().size() == 1 && r.getParameterTypes().get(0).toString().equals(LP);
    }

    private static boolean intField(ClassDef c, String name) {
        for (Field f : c.getFields()) if (f.getName().equals(name) && f.getType().equals("I") && !AccessFlags.STATIC.isSet(f.getAccessFlags())) return true;
        return false;
    }

    static boolean runtime(Map<String, ClassDef> classes) {
        ClassDef surface = classes.get(SURFACE), lp = classes.get(MAIN_LP);
        return classes.containsKey(RUNTIME) && surface != null && "Landroid/view/SurfaceView;".equals(surface.getSuperclass())
                && lp != null && LP.equals(lp.getSuperclass()) && intField(lp, "x") && intField(lp, "y");
    }

    private static ImmutableMethodReference m(String name, List<String> params, String ret) { return new ImmutableMethodReference(VIEW, name, params, ret); }

    /** static void pocketouyaPlaceSurface(SurfaceView view, ViewGroup.LayoutParams lp): v0..v7 scratch, p0 = v8, p1 = v9. */
    static Method helper(String owner) {
        MethodImplementationBuilder b = new MethodImplementationBuilder(10);
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 8, 9, 0, 0, 0, m("setLayoutParams", List.of(LP), "V")));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 8, 0, 0, 0, 0, m("getWidth", List.of(), "I")));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_GTZ, 0, b.getLabel("done")));       // already placed: Android's own layout works
        b.addInstruction(new BuilderInstruction21c(Opcode.CHECK_CAST, 9, new ImmutableTypeReference(LP)));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 8, 0, 0, 0, 0, m("getParent", List.of(), "Landroid/view/ViewParent;")));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        b.addInstruction(new BuilderInstruction22c(Opcode.INSTANCE_OF, 1, 0, new ImmutableTypeReference(VIEW)));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 1, b.getLabel("done")));
        b.addInstruction(new BuilderInstruction21c(Opcode.CHECK_CAST, 0, new ImmutableTypeReference(VIEW)));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, m("getWidth", List.of(), "I")));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 1));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_LEZ, 1, b.getLabel("done")));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, m("getHeight", List.of(), "I")));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 2));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_LEZ, 2, b.getLabel("done")));       // parent not sized yet: nothing to copy
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 9, b.getLabel("done")));
        b.addInstruction(new BuilderInstruction22c(Opcode.IGET, 3, 9, new ImmutableFieldReference(LP, "width", "I")));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_GEZ, 3, b.getLabel("hasWidth")));
        b.addInstruction(new BuilderInstruction12x(Opcode.MOVE, 3, 1));
        b.addLabel("hasWidth");
        b.addInstruction(new BuilderInstruction22c(Opcode.IGET, 4, 9, new ImmutableFieldReference(LP, "height", "I")));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_GEZ, 4, b.getLabel("hasHeight")));
        b.addInstruction(new BuilderInstruction12x(Opcode.MOVE, 4, 2));
        b.addLabel("hasHeight");
        b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 5, 0));
        b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 6, 0));
        b.addInstruction(new BuilderInstruction22c(Opcode.INSTANCE_OF, 7, 9, new ImmutableTypeReference(MAIN_LP)));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 7, b.getLabel("measure")));
        b.addInstruction(new BuilderInstruction21c(Opcode.CHECK_CAST, 9, new ImmutableTypeReference(MAIN_LP)));
        b.addInstruction(new BuilderInstruction22c(Opcode.IGET, 5, 9, new ImmutableFieldReference(MAIN_LP, "x", "I")));
        b.addInstruction(new BuilderInstruction22c(Opcode.IGET, 6, 9, new ImmutableFieldReference(MAIN_LP, "y", "I")));
        b.addLabel("measure");
        b.addInstruction(new BuilderInstruction21ih(Opcode.CONST_HIGH16, 7, 0x40000000));        // MeasureSpec.EXACTLY
        b.addInstruction(new BuilderInstruction23x(Opcode.OR_INT, 0, 3, 7));
        b.addInstruction(new BuilderInstruction23x(Opcode.OR_INT, 7, 4, 7));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 8, 0, 7, 0, 0, m("measure", List.of("I", "I"), "V")));
        b.addInstruction(new BuilderInstruction23x(Opcode.ADD_INT, 3, 3, 5));
        b.addInstruction(new BuilderInstruction23x(Opcode.ADD_INT, 4, 4, 6));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 5, 8, 5, 6, 3, 4, m("layout", List.of("I", "I", "I", "I"), "V")));
        b.addLabel("done");
        b.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        return new ImmutableMethod(owner, HELPER, List.of(new ImmutableMethodParameter(SURFACE, Set.of(), null), new ImmutableMethodParameter(LP, Set.of(), null)),
                "V", 0x100a, Set.of(), Set.of(), b.getMethodImplementation());
    }

    private static Result run(Map<String, byte[]> input, boolean apply) {
        Result r = new Result();
        int sites = 0;
        try {
            Map<String, ClassDef> classes = new HashMap<>(); Map<String, DexBackedDexFile> files = new LinkedHashMap<>();
            for (var e : input.entrySet()) { var d = new DexBackedDexFile(null, e.getValue()); files.put(e.getKey(), d); for (ClassDef c : d.getClasses()) classes.put(c.getType(), c); }
            if (!runtime(classes)) return r;
            for (var file : files.entrySet()) {
                DexPool pool = apply ? new DexPool(file.getValue().getOpcodes()) : null;
                boolean changed = false;
                for (ClassDef c : file.getValue().getClasses()) {
                    boolean helperPresent = false;
                    for (Method mm : c.getMethods()) if (mm.getName().equals(HELPER)) helperPresent = true;
                    List<Method> methods = new ArrayList<>(); boolean classChanged = false;
                    for (Method mm : c.getMethods()) {
                        if (mm.getImplementation() == null || helperPresent) { methods.add(mm); continue; }
                        List<Integer> hits = new ArrayList<>(); List<Instruction> original = new ArrayList<>();
                        for (Instruction i : mm.getImplementation().getInstructions()) { if (target(i)) hits.add(original.size()); original.add(i); }
                        sites += hits.size();
                        if (!apply || hits.isEmpty()) { methods.add(mm); continue; }
                        MutableMethodImplementation body = new MutableMethodImplementation(mm.getImplementation());
                        var ref = new ImmutableMethodReference(c.getType(), HELPER, List.of(SURFACE, LP), "V");
                        for (int at : hits) {
                            var f = (FiveRegisterInstruction) original.get(at);
                            body.replaceInstruction(at, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, f.getRegisterC(), f.getRegisterD(), 0, 0, 0, ref));
                        }
                        methods.add(new ImmutableMethod(mm.getDefiningClass(), mm.getName(), mm.getParameters(), mm.getReturnType(), mm.getAccessFlags(), mm.getAnnotations(), mm.getHiddenApiRestrictions(), body));
                        classChanged = true;
                    }
                    if (!apply) continue;
                    if (classChanged) {
                        methods.add(helper(c.getType()));
                        pool.internClass(new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getFields(), methods));
                        changed = true;
                    } else pool.internClass(c);
                }
                if (apply && changed) { MemoryDataStore out = new MemoryDataStore(); pool.writeTo(out); r.replacements.put(file.getKey(), out.getData()); }
            }
            if (sites == 0) {
                r.reason = "Clickteam runtime found but no SurfaceView.setLayoutParams call (already repaired or different runtime)"; return r;
            }
            if (apply) {
                Map<String, byte[]> verify = new LinkedHashMap<>(input); verify.putAll(r.replacements);
                if (inspect(verify).status != CompatibilityEvidence.Status.SKIPPED) throw new IllegalStateException("Postcondition: SurfaceView.setLayoutParams calls remain after repair");
            }
            r.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
            r.reason = sites + " Clickteam SurfaceView.setLayoutParams call(s). The game surface is created during a layout pass, which Android 4.3+ no longer re-runs; "
                    + "an unplaced SurfaceView is now measured and placed like MainView.onLayout would, so its GL surface can be created instead of a blank screen.";
        } catch (Exception e) {
            r.replacements.clear(); r.status = CompatibilityEvidence.Status.UNKNOWN; r.reason = e + "; original DEX retained";
        }
        return r;
    }
}
