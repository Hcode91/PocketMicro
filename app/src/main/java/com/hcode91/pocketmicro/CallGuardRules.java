package com.hcode91.pocketmicro;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.*;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/**
 * Small, general "guarded call" repairs for platform behaviour that changed since the OUYA:
 *
 * android.legacy-unbind — Context.unbindService(conn) throws "Service not registered" when the
 *   bind never succeeded (e.g. a MOGA controller service that no longer exists; Abbaye des
 *   Morts crashed on rotate/exit). The helper makes the original call and ignores only
 *   IllegalArgumentException.
 *
 * ouya.store-offline.null-receipts — the OUYA store is gone, so receipt lists that games
 *   expected the store to fill stay null, and iterating them crashes (Wraithborne
 *   CheckReceipts). Inside methods that use the OUYA store API (tv.ouya.console.api types),
 *   Collection.iterator() on null yields an empty iterator: no receipts, so the game treats the
 *   content as not purchased. Nothing is unlocked; purchase checks still run.
 *
 * gms.plus-one-missing-drawable — Google+ is gone and today's Play Services no longer ships the
 *   +1 button artwork, so the bundled Play Services library's PlusOneButton fallback asks for
 *   resource 0 and crashes with Resources$NotFoundException (N.O.V.A. 3 Freedom Edition). Inside
 *   com.google.android.gms.plus classes, Resources.getDrawable(int) returns null when the
 *   resource is missing, the same result the library already uses when Play Services is absent.
 *
 * Each rewritten call keeps its registers and becomes invoke-static of a private helper added
 * to the calling class.
 */
public final class CallGuardRules {
    public static final String UNBIND_ID = "android.legacy-unbind", RECEIPTS_ID = "ouya.store-offline.null-receipts", PLUS_ID = "gms.plus-one-missing-drawable";
    static final String RES = "Landroid/content/res/Resources;", DRAWABLE = "Landroid/graphics/drawable/Drawable;", DRAW_HELPER = "pocketouyaSafe_getDrawable";
    static final String CONTEXT = "Landroid/content/Context;", CONN = "Landroid/content/ServiceConnection;";
    static final String UNBIND_HELPER = "pocketouyaSafe_unbindService", ITER_HELPER = "pocketouyaSafe_iterator";
    private static final Set<String> FRAMEWORK_CONTEXTS = Set.of(CONTEXT, "Landroid/content/ContextWrapper;", "Landroid/view/ContextThemeWrapper;", "Landroid/app/Activity;",
            "Landroid/app/Application;", "Landroid/app/Service;", "Landroid/app/IntentService;", "Landroid/app/NativeActivity;", "Landroid/app/ListActivity;",
            "Landroid/app/TabActivity;", "Landroid/app/ActivityGroup;", "Landroid/app/ExpandableListActivity;", "Landroid/preference/PreferenceActivity;");
    private static final Set<String> COLLECTIONS = Set.of("Ljava/util/List;", "Ljava/util/Collection;", "Ljava/util/ArrayList;", "Ljava/util/LinkedList;", "Ljava/util/Set;", "Ljava/util/HashSet;", "Ljava/lang/Iterable;");

    public static final class Result {
        public final Map<String, String> reports = new LinkedHashMap<>();   // rule id -> "STATUS reason"
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
        public String report() { StringBuilder s = new StringBuilder(); for (var e : reports.entrySet()) s.append(e.getValue().replaceFirst(" ", " " + e.getKey() + ": ")).append('\n'); return s.toString(); }
    }

    private CallGuardRules() {}
    public static Result inspect(Map<String, byte[]> dex) { return run(dex, false); }
    public static Result repair(Map<String, byte[]> dex) { return run(dex, true); }

    private static boolean contextType(String type, Map<String, String> supers) {
        for (int d = 0; type != null && d < 32; d++) { if (FRAMEWORK_CONTEXTS.contains(type)) return true; if (!supers.containsKey(type)) return false; type = supers.get(type); }
        return false;
    }
    private static MethodReference ref(Instruction i) {
        return i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof MethodReference ? (MethodReference) ((ReferenceInstruction) i).getReference() : null;
    }
    private static int regs(Instruction i) {
        return i instanceof FiveRegisterInstruction ? ((FiveRegisterInstruction) i).getRegisterCount() : i instanceof RegisterRangeInstruction ? ((RegisterRangeInstruction) i).getRegisterCount() : -1;
    }
    static boolean isUnbind(Instruction i, Map<String, String> supers) {
        if (i.getOpcode() != Opcode.INVOKE_VIRTUAL && i.getOpcode() != Opcode.INVOKE_VIRTUAL_RANGE) return false;
        MethodReference r = ref(i);
        return r != null && r.getName().equals("unbindService") && r.getReturnType().equals("V") && r.getParameterTypes().size() == 1
                && r.getParameterTypes().get(0).toString().equals(CONN) && regs(i) == 2 && contextType(r.getDefiningClass(), supers);
    }
    static boolean isIterator(Instruction i) {
        Opcode o = i.getOpcode();
        if (o != Opcode.INVOKE_INTERFACE && o != Opcode.INVOKE_INTERFACE_RANGE && o != Opcode.INVOKE_VIRTUAL && o != Opcode.INVOKE_VIRTUAL_RANGE) return false;
        MethodReference r = ref(i);
        return r != null && r.getName().equals("iterator") && r.getParameterTypes().isEmpty() && r.getReturnType().equals("Ljava/util/Iterator;")
                && COLLECTIONS.contains(r.getDefiningClass()) && regs(i) == 1;
    }
    static boolean isPlusDrawable(Instruction i, String owner) {
        if (!owner.startsWith("Lcom/google/android/gms/plus/")) return false;
        if (i.getOpcode() != Opcode.INVOKE_VIRTUAL && i.getOpcode() != Opcode.INVOKE_VIRTUAL_RANGE) return false;
        MethodReference r = ref(i);
        return r != null && r.getName().equals("getDrawable") && r.getDefiningClass().equals(RES) && r.getReturnType().equals(DRAWABLE)
                && r.getParameterTypes().size() == 1 && r.getParameterTypes().get(0).toString().equals("I") && regs(i) == 2;
    }
    static boolean usesOuyaStore(Method m) {
        if (m.getImplementation() == null) return false;
        for (Instruction i : m.getImplementation().getInstructions()) {
            if (!(i instanceof ReferenceInstruction)) continue;
            Object r = ((ReferenceInstruction) i).getReference();
            String owner = r instanceof MethodReference ? ((MethodReference) r).getDefiningClass() : r instanceof FieldReference ? ((FieldReference) r).getType() + ((FieldReference) r).getDefiningClass()
                    : r instanceof TypeReference ? ((TypeReference) r).getType() : "";
            if (owner.contains("Ltv/ouya/console/api/")) return true;
        }
        return false;
    }

    static Method unbindHelper(String owner) {
        MethodImplementationBuilder b = new MethodImplementationBuilder(2); // p0 context, p1 connection
        b.addLabel("start");
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, new ImmutableMethodReference(CONTEXT, "unbindService", List.of(CONN), "V")));
        b.addLabel("end");
        b.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        b.addLabel("notRegistered");
        b.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        b.addCatch(new ImmutableTypeReference("Ljava/lang/IllegalArgumentException;"), b.getLabel("start"), b.getLabel("end"), b.getLabel("notRegistered"));
        return new ImmutableMethod(owner, UNBIND_HELPER, List.of(new ImmutableMethodParameter(CONTEXT, Set.of(), null), new ImmutableMethodParameter(CONN, Set.of(), null)),
                "V", 0x100a, Set.of(), Set.of(), b.getMethodImplementation());
    }

    static Method iteratorHelper(String owner) {
        MethodImplementationBuilder b = new MethodImplementationBuilder(2); // v0 local, p0 = Iterable (may be null)
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 1, b.getLabel("empty")));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 1, 0, 0, 0, 0, new ImmutableMethodReference("Ljava/lang/Iterable;", "iterator", List.of(), "Ljava/util/Iterator;")));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        b.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        b.addLabel("empty");
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, new ImmutableMethodReference("Ljava/util/Collections;", "emptyList", List.of(), "Ljava/util/List;")));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 0, 0, 0, 0, 0, new ImmutableMethodReference("Ljava/util/List;", "iterator", List.of(), "Ljava/util/Iterator;")));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        b.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        return new ImmutableMethod(owner, ITER_HELPER, List.of(new ImmutableMethodParameter("Ljava/lang/Iterable;", Set.of(), null)),
                "Ljava/util/Iterator;", 0x100a, Set.of(), Set.of(), b.getMethodImplementation());
    }

    static Method drawableHelper(String owner) {
        MethodImplementationBuilder b = new MethodImplementationBuilder(3); // v0 local, p0 resources = v1, p1 id = v2
        b.addLabel("start");
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 2, 0, 0, 0, new ImmutableMethodReference(RES, "getDrawable", List.of("I"), DRAWABLE)));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        b.addLabel("end");
        b.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        b.addLabel("missing");
        b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 0));
        b.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        b.addCatch(new ImmutableTypeReference("Landroid/content/res/Resources$NotFoundException;"), b.getLabel("start"), b.getLabel("end"), b.getLabel("missing"));
        return new ImmutableMethod(owner, DRAW_HELPER, List.of(new ImmutableMethodParameter(RES, Set.of(), null), new ImmutableMethodParameter("I", Set.of(), null)),
                DRAWABLE, 0x100a, Set.of(), Set.of(), b.getMethodImplementation());
    }

    private static boolean ours(String type) { return type.startsWith("Lcom/hcode91/pocketmicro/") || type.startsWith("Lcom/hcode91/pocketmicro/"); }

    private static BuilderInstruction toStatic(Instruction i, ImmutableMethodReference helper) {
        if (i instanceof FiveRegisterInstruction) {
            var f = (FiveRegisterInstruction) i;
            return new BuilderInstruction35c(Opcode.INVOKE_STATIC, f.getRegisterCount(), f.getRegisterC(), f.getRegisterD(), 0, 0, 0, helper);
        }
        var r = (RegisterRangeInstruction) i;
        return new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, r.getStartRegister(), r.getRegisterCount(), helper);
    }

    private static Result run(Map<String, byte[]> input, boolean apply) {
        Result result = new Result();
        int unbinds = 0, iterators = 0, drawables = 0;
        try {
            long total = 0; for (byte[] d : input.values()) total += d.length;
            if (input.size() > 64 || total > 64L * 1024 * 1024) { result.reports.put(UNBIND_ID, "SKIPPED DEX budget; no edits"); return result; }
            Map<String, DexBackedDexFile> files = new LinkedHashMap<>(); Map<String, String> supers = new HashMap<>();
            for (var e : input.entrySet()) { var d = new DexBackedDexFile(null, e.getValue()); files.put(e.getKey(), d); for (ClassDef c : d.getClasses()) supers.put(c.getType(), c.getSuperclass()); }
            for (var file : files.entrySet()) {
                DexPool pool = new DexPool(file.getValue().getOpcodes()); boolean changed = false;
                for (ClassDef c : file.getValue().getClasses()) {
                    if (ours(c.getType()) || AccessFlags.INTERFACE.isSet(c.getAccessFlags())) { pool.internClass(c); continue; }
                    boolean hasUnbindHelper = false, hasIterHelper = false, hasDrawHelper = false;
                    for (Method m : c.getMethods()) { if (m.getName().equals(UNBIND_HELPER)) hasUnbindHelper = true; if (m.getName().equals(ITER_HELPER)) hasIterHelper = true; if (m.getName().equals(DRAW_HELPER)) hasDrawHelper = true; }
                    List<Method> methods = new ArrayList<>(); boolean classChanged = false, needUnbind = false, needIter = false, needDraw = false;
                    for (Method m : c.getMethods()) {
                        if (m.getImplementation() == null || m.getName().startsWith("pocketouyaSafe_")) { methods.add(m); continue; }
                        boolean store = usesOuyaStore(m);
                        List<Integer> u = new ArrayList<>(), it = new ArrayList<>(), dr = new ArrayList<>(); List<Instruction> code = new ArrayList<>();
                        int n = 0;
                        for (Instruction i : m.getImplementation().getInstructions()) {
                            if (i instanceof org.jf.dexlib2.iface.instruction.formats.UnknownInstruction) throw new UnsupportedOperationException("Reserved DEX instruction in " + c.getType());
                            code.add(i);
                            // OUYA SDK classes are handled by ouya.content.null-context-cleanup.
                            if (!hasUnbindHelper && !c.getType().startsWith("Ltv/ouya/") && isUnbind(i, supers)) u.add(n);
                            if (store && !hasIterHelper && isIterator(i)) it.add(n);
                            if (!hasDrawHelper && isPlusDrawable(i, c.getType())) dr.add(n);
                            n++;
                        }
                        unbinds += u.size(); iterators += it.size(); drawables += dr.size();
                        if (!apply || (u.isEmpty() && it.isEmpty() && dr.isEmpty())) { methods.add(m); continue; }
                        MutableMethodImplementation body = new MutableMethodImplementation(m.getImplementation());
                        var unbindRef = new ImmutableMethodReference(c.getType(), UNBIND_HELPER, List.of(CONTEXT, CONN), "V");
                        var iterRef = new ImmutableMethodReference(c.getType(), ITER_HELPER, List.of("Ljava/lang/Iterable;"), "Ljava/util/Iterator;");
                        for (int at : u) body.replaceInstruction(at, toStatic(code.get(at), unbindRef));
                        for (int at : it) body.replaceInstruction(at, toStatic(code.get(at), iterRef));
                        var drawRef = new ImmutableMethodReference(c.getType(), DRAW_HELPER, List.of(RES, "I"), DRAWABLE);
                        for (int at : dr) body.replaceInstruction(at, toStatic(code.get(at), drawRef));
                        needUnbind |= !u.isEmpty(); needIter |= !it.isEmpty(); needDraw |= !dr.isEmpty();
                        methods.add(new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(), m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), body));
                        classChanged = true;
                    }
                    if (classChanged) {
                        if (needUnbind) methods.add(unbindHelper(c.getType()));
                        if (needIter) methods.add(iteratorHelper(c.getType()));
                        if (needDraw) methods.add(drawableHelper(c.getType()));
                        pool.internClass(new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getFields(), methods));
                        changed = true;
                    } else pool.internClass(c);
                }
                if (apply && changed) { MemoryDataStore out = new MemoryDataStore(); pool.writeTo(out); result.replacements.put(file.getKey(), out.getData()); }
            }
            if (apply && !result.replacements.isEmpty()) {
                Map<String, byte[]> verify = new LinkedHashMap<>(input); verify.putAll(result.replacements);
                Result again = inspect(verify);
                if (!again.reports.isEmpty()) throw new IllegalStateException("Postcondition: guarded calls remain after repair");
            }
            String s = apply ? "VERIFIED " : "SUPPORTED ";
            if (unbinds > 0) result.reports.put(UNBIND_ID, s + unbinds + " Context.unbindService call(s); \"Service not registered\" (bind never succeeded) is ignored instead of crashing.");
            if (drawables > 0) result.reports.put(PLUS_ID, s + drawables + " Resources.getDrawable call(s) in the bundled Google+ library; the +1 button artwork today's Play Services no longer ships returns null (the library's own \"not available\" result) instead of crashing.");
            if (iterators > 0) result.reports.put(RECEIPTS_ID, s + iterators + " collection iteration(s) in OUYA store code; a list the closed store never filled iterates as empty (no receipts, nothing unlocked) instead of crashing.");
        } catch (UnsupportedOperationException e) {
            result.replacements.clear(); result.reports.clear(); result.reports.put(UNBIND_ID, "UNSUPPORTED " + e.getMessage() + "; original DEX retained");
        } catch (Exception e) {
            result.replacements.clear(); result.reports.clear(); result.reports.put(UNBIND_ID, "UNKNOWN " + e + "; original DEX retained");
        }
        return result;
    }
}
