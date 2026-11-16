package com.hcode91.pocketmicro;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/**
 * android.webview-js-interface: since Android 4.2, a WebView only lets JavaScript call Java methods
 * marked @JavascriptInterface once the app targets API 17 or later. Converted games target API 23,
 * so HTML games built on PhoneGap/Cordova 2.x (and other pre-2013 WebView bridges) lose every
 * bridge method: phonegap.js reports "nativeApiProvider.get(...).exec is not a function",
 * deviceready never fires and the game sits on its first page.
 *
 * For each WebView.addJavascriptInterface(object, name) call, the object's class is found from the
 * instruction that last set that register (new-instance, field read, call result, cast or
 * parameter). If it is a dedicated bridge object (only app classes between it and Object), the
 * public instance methods that class and its app superclasses declare get the runtime
 * @JavascriptInterface annotation, restoring the methods the game exposed on the Android it was
 * built for. Activities, Views and other framework subclasses are left alone. No code changes.
 */
public final class JavascriptInterfaceRule {
    public static final String ID = "android.webview-js-interface";
    static final String ANNOTATION = "Landroid/webkit/JavascriptInterface;";

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "no WebView JavaScript interfaces";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
    }

    private JavascriptInterfaceRule() {}
    public static Result inspect(Map<String, byte[]> dex) { return run(dex, false); }
    public static Result repair(Map<String, byte[]> dex) { return run(dex, true); }

    static boolean addInterface(Instruction i) {
        if (!(i instanceof ReferenceInstruction) || !(((ReferenceInstruction) i).getReference() instanceof MethodReference)) return false;
        if (i.getOpcode() != Opcode.INVOKE_VIRTUAL && i.getOpcode() != Opcode.INVOKE_VIRTUAL_RANGE) return false;
        MethodReference r = (MethodReference) ((ReferenceInstruction) i).getReference();
        return r.getName().equals("addJavascriptInterface") && r.getReturnType().equals("V") && r.getParameterTypes().size() == 2
                && r.getParameterTypes().get(0).toString().equals("Ljava/lang/Object;") && r.getParameterTypes().get(1).toString().equals("Ljava/lang/String;");
    }

    static int objectRegister(Instruction i) {
        if (i instanceof FiveRegisterInstruction) return ((FiveRegisterInstruction) i).getRegisterD();
        return ((RegisterRangeInstruction) i).getStartRegister() + 1;
    }

    /** Type held in register reg just before instruction index at, from the last instruction that set it (linear, same method). */
    static String typeOf(Method m, List<Instruction> code, int at, int reg, int depth) {
        if (depth > 8) return null;
        for (int k = at - 1; k >= 0; k--) {
            Instruction i = code.get(k);
            if (!i.getOpcode().setsRegister() || !(i instanceof OneRegisterInstruction) || ((OneRegisterInstruction) i).getRegisterA() != reg) continue;
            switch (i.getOpcode()) {
                case NEW_INSTANCE: case CHECK_CAST: return ((TypeReference) ((ReferenceInstruction) i).getReference()).getType();
                case IGET_OBJECT: case SGET_OBJECT: return ((FieldReference) ((ReferenceInstruction) i).getReference()).getType();
                case MOVE_OBJECT: case MOVE_OBJECT_FROM16: case MOVE_OBJECT_16: return typeOf(m, code, k, ((TwoRegisterInstruction) i).getRegisterB(), depth + 1);
                case MOVE_RESULT_OBJECT: {
                    Instruction call = k > 0 ? code.get(k - 1) : null;
                    if (call instanceof ReferenceInstruction && ((ReferenceInstruction) call).getReference() instanceof MethodReference)
                        return ((MethodReference) ((ReferenceInstruction) call).getReference()).getReturnType();
                    return null;
                }
                default: return null;
            }
        }
        // Never set in the method body: a parameter register (parameters occupy the last registers; p0 = this unless static).
        int regs = m.getImplementation().getRegisterCount(), words = 0;
        boolean isStatic = AccessFlags.STATIC.isSet(m.getAccessFlags());
        for (CharSequence p : m.getParameterTypes()) words += (p.toString().equals("J") || p.toString().equals("D")) ? 2 : 1;
        int first = regs - words - (isStatic ? 0 : 1), r = first;
        if (!isStatic) { if (reg == r) return m.getDefiningClass(); r++; }
        for (CharSequence p : m.getParameterTypes()) {
            if (reg == r) return p.toString();
            r += (p.toString().equals("J") || p.toString().equals("D")) ? 2 : 1;
        }
        return null;
    }

    /**
     * A dedicated bridge object: its superclass chain stays in the app until java.lang.Object. An
     * Activity, View or other framework subclass passed as the bridge is left alone, since marking it
     * would expose far more than a bridge to page JavaScript.
     */
    static boolean dedicated(Map<String, ClassDef> classes, String type) {
        String s = type;
        for (int depth = 0; depth < 32 && classes.containsKey(s); depth++) s = classes.get(s).getSuperclass();
        return "Ljava/lang/Object;".equals(s);
    }

    static boolean exposable(Method m) {
        int f = m.getAccessFlags();
        return AccessFlags.PUBLIC.isSet(f) && !AccessFlags.STATIC.isSet(f) && !AccessFlags.SYNTHETIC.isSet(f) && !AccessFlags.BRIDGE.isSet(f)
                && !m.getName().startsWith("<") && m.getImplementation() != null;
    }

    static boolean annotated(Method m) {
        for (Annotation a : m.getAnnotations()) if (a.getType().equals(ANNOTATION)) return true;
        return false;
    }

    private static Result run(Map<String, byte[]> input, boolean apply) {
        Result r = new Result();
        try {
            Map<String, ClassDef> classes = new HashMap<>(); Map<String, DexBackedDexFile> files = new LinkedHashMap<>();
            for (var e : input.entrySet()) { var d = new DexBackedDexFile(null, e.getValue()); files.put(e.getKey(), d); for (ClassDef c : d.getClasses()) classes.put(c.getType(), c); }
            Set<String> roots = new LinkedHashSet<>(); List<String> unknown = new ArrayList<>(); int sites = 0;
            for (ClassDef c : classes.values()) for (Method m : c.getMethods()) {
                if (m.getImplementation() == null) continue;
                List<Instruction> code = new ArrayList<>(); for (Instruction i : m.getImplementation().getInstructions()) code.add(i);
                for (int k = 0; k < code.size(); k++) {
                    if (!addInterface(code.get(k))) continue;
                    sites++;
                    String t = typeOf(m, code, k, objectRegister(code.get(k)), 0);
                    if (t == null || !classes.containsKey(t)) unknown.add(c.getType() + "." + m.getName());
                    else roots.add(t);
                }
            }
            if (sites == 0) return r;
            // The class, its app superclasses, and for an interface type every app class implementing it.
            Set<String> targets = new LinkedHashSet<>(), framework = new LinkedHashSet<>();
            for (String t : roots) {
                ClassDef rc = classes.get(t);
                List<String> starts = new ArrayList<>();
                if (AccessFlags.INTERFACE.isSet(rc.getAccessFlags())) { for (ClassDef c : classes.values()) if (c.getInterfaces().contains(t)) starts.add(c.getType()); }
                else starts.add(t);
                for (String start : starts) {
                    if (!dedicated(classes, start)) { framework.add(start); continue; }
                    for (String s = start; s != null && classes.containsKey(s); s = classes.get(s).getSuperclass()) targets.add(s);
                }
            }
            int methods = 0;
            for (String t : targets) for (Method m : classes.get(t).getMethods()) if (exposable(m) && !annotated(m)) methods++;
            if (methods == 0) {
                if (unknown.isEmpty() && framework.isEmpty()) { r.reason = sites + " WebView interface(s) already annotated"; return r; }
                if (unknown.isEmpty()) { r.reason = "WebView interface objects are Android components (" + String.join(", ", framework) + "); left unchanged"; return r; }
                r.status = CompatibilityEvidence.Status.UNSUPPORTED; r.reason = "interface object type not found at " + String.join(", ", unknown) + "; unchanged"; return r;
            }
            if (apply) for (var file : files.entrySet()) {
                boolean touches = false;
                for (ClassDef c : file.getValue().getClasses()) if (targets.contains(c.getType())) touches = true;
                if (!touches) continue;
                DexPool pool = new DexPool(file.getValue().getOpcodes());
                for (ClassDef c : file.getValue().getClasses()) {
                    if (!targets.contains(c.getType())) { pool.internClass(c); continue; }
                    List<Method> out = new ArrayList<>();
                    for (Method m : c.getMethods()) {
                        if (!exposable(m) || annotated(m)) { out.add(m); continue; }
                        Set<Annotation> anns = new LinkedHashSet<>(m.getAnnotations());
                        anns.add(new ImmutableAnnotation(AnnotationVisibility.RUNTIME, ANNOTATION, Set.of()));
                        out.add(new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(), m.getAccessFlags(), anns, m.getHiddenApiRestrictions(), m.getImplementation()));
                    }
                    pool.internClass(new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getFields(), out));
                }
                MemoryDataStore store = new MemoryDataStore(); pool.writeTo(store); r.replacements.put(file.getKey(), store.getData());
            }
            r.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
            r.reason = sites + " WebView.addJavascriptInterface call(s); " + methods + " public bridge method(s) in " + String.join(", ", targets)
                    + " marked @JavascriptInterface so JavaScript can call them again under the raised target SDK (Android 4.2+ hides unmarked methods)."
                    + (unknown.isEmpty() ? "" : " Object type not found at " + String.join(", ", unknown) + ".")
                    + (framework.isEmpty() ? "" : " Android component objects left unchanged: " + String.join(", ", framework) + ".");
        } catch (Exception e) {
            r.replacements.clear(); r.status = CompatibilityEvidence.Status.UNKNOWN; r.reason = e + "; original DEX retained";
        }
        return r;
    }
}
