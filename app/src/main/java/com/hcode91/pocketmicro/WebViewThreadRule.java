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
 * android.webview-main-thread: old SDKs (Google AdMob 4.x, 2011-2013) load their ad pages with
 * WebView.loadDataWithBaseURL/loadUrl from a background AsyncTask. Android only warned about that
 * for apps built for old versions, but today's WebView (updated through the Play Store) throws
 * "Calling methods on another thread than the UI thread" for every app, whatever its target, so
 * the game crashes as soon as its ad banner loads (Grave Defense Holidays).
 *
 * Every such call becomes a static call to a small class added to the game's DEX, which makes
 * the same call at once when it is already on the main thread (behaviour unchanged) and
 * otherwise posts it to the main thread. Ads are not removed or blocked.
 */
public final class WebViewThreadRule {
    public static final String ID = "android.webview-main-thread";
    static final String WEBVIEW = "Landroid/webkit/WebView;", S = "Ljava/lang/String;", MAP = "Ljava/util/Map;";
    static final String HELPER = "Lcom/hcode91/pocketmicro/compat/WebViewOnMain;";

    /** name, parameter types, operation number. */
    static final Object[][] CALLS = {
        {"loadUrl", List.of(S), 1},
        {"loadUrl", List.of(S, MAP), 2},
        {"loadDataWithBaseURL", List.of(S, S, S, S, S), 3},
        {"loadData", List.of(S, S, S), 4},
        {"stopLoading", List.of(), 5},
    };
    private static final String[] ARGS = {"a", "b", "c", "d", "e"};

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "no WebView page loads";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
        public int calls;
    }

    private WebViewThreadRule() {}
    public static Result inspect(Map<String, byte[]> dex) { return run(dex, false); }
    public static Result repair(Map<String, byte[]> dex) { return run(dex, true); }

    static boolean webView(String type, Map<String, String> supers) {
        for (int d = 0; type != null && d < 32; d++) { if (type.equals(WEBVIEW)) return true; type = supers.get(type); }
        return false;
    }

    /** Operation number of a WebView call this rule moves to the main thread, or 0. */
    static int operation(Instruction i, Map<String, String> supers) {
        if (i.getOpcode() != Opcode.INVOKE_VIRTUAL && i.getOpcode() != Opcode.INVOKE_VIRTUAL_RANGE) return 0;
        if (!(i instanceof ReferenceInstruction) || !(((ReferenceInstruction) i).getReference() instanceof MethodReference)) return 0;
        MethodReference r = (MethodReference) ((ReferenceInstruction) i).getReference();
        if (!r.getReturnType().equals("V") || !webView(r.getDefiningClass(), supers)) return 0;
        List<String> params = new ArrayList<>(); for (CharSequence p : r.getParameterTypes()) params.add(p.toString());
        for (Object[] c : CALLS) if (c[0].equals(r.getName()) && c[1].equals(params)) return (Integer) c[2];
        return 0;
    }

    static ImmutableMethodReference staticRef(int op) {
        for (Object[] c : CALLS) if ((Integer) c[2] == op) {
            List<String> params = new ArrayList<>(); params.add(WEBVIEW);
            for (Object p : (List<?>) c[1]) params.add((String) p);
            return new ImmutableMethodReference(HELPER, (String) c[0], params, "V");
        }
        throw new IllegalArgumentException("operation " + op);
    }

    private static ImmutableFieldReference field(String name, String type) { return new ImmutableFieldReference(HELPER, name, type); }
    private static ImmutableMethodReference method(String owner, String name, List<String> params, String ret) { return new ImmutableMethodReference(owner, name, params, ret); }

    /** The helper class: one static entry per call, a dispatcher and Runnable.run(). */
    static ClassDef helperClass() {
        List<Field> fields = new ArrayList<>();
        fields.add(new ImmutableField(HELPER, "view", WEBVIEW, AccessFlags.PRIVATE.getValue(), null, Set.of(), Set.of()));
        fields.add(new ImmutableField(HELPER, "op", "I", AccessFlags.PRIVATE.getValue(), null, Set.of(), Set.of()));
        for (String a : ARGS) fields.add(new ImmutableField(HELPER, a, S, AccessFlags.PRIVATE.getValue(), null, Set.of(), Set.of()));
        fields.add(new ImmutableField(HELPER, "m", MAP, AccessFlags.PRIVATE.getValue(), null, Set.of(), Set.of()));
        List<Method> methods = new ArrayList<>();

        // <init>()
        MethodImplementationBuilder init = new MethodImplementationBuilder(1);
        init.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0, method("Ljava/lang/Object;", "<init>", List.of(), "V")));
        init.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        methods.add(new ImmutableMethod(HELPER, "<init>", List.of(), "V", AccessFlags.PUBLIC.getValue() | AccessFlags.CONSTRUCTOR.getValue(), Set.of(), Set.of(), init.getMethodImplementation()));

        // static entries: new helper, store view/op/args, go(helper)
        for (Object[] c : CALLS) {
            List<?> types = (List<?>) c[1]; int op = (Integer) c[2];
            int params = 1 + types.size(), locals = 2;
            MethodImplementationBuilder b = new MethodImplementationBuilder(locals + params);
            b.addInstruction(new BuilderInstruction21c(Opcode.NEW_INSTANCE, 0, new ImmutableTypeReference(HELPER)));
            b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0, method(HELPER, "<init>", List.of(), "V")));
            b.addInstruction(new BuilderInstruction22c(Opcode.IPUT_OBJECT, locals, 0, field("view", WEBVIEW)));
            b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 1, op));
            b.addInstruction(new BuilderInstruction22c(Opcode.IPUT, 1, 0, field("op", "I")));
            int stringIndex = 0;
            for (int p = 0; p < types.size(); p++) {
                String t = (String) types.get(p);
                if (t.equals(S)) b.addInstruction(new BuilderInstruction22c(Opcode.IPUT_OBJECT, locals + 1 + p, 0, field(ARGS[stringIndex++], S)));
                else b.addInstruction(new BuilderInstruction22c(Opcode.IPUT_OBJECT, locals + 1 + p, 0, field("m", MAP)));
            }
            b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, method(HELPER, "go", List.of(HELPER), "V")));
            b.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            List<MethodParameter> ps = new ArrayList<>(); ps.add(new ImmutableMethodParameter(WEBVIEW, Set.of(), null));
            for (Object t : types) ps.add(new ImmutableMethodParameter((String) t, Set.of(), null));
            methods.add(new ImmutableMethod(HELPER, (String) c[0], ps, "V", AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue(), Set.of(), Set.of(), b.getMethodImplementation()));
        }

        // go(helper): run now on the main thread, else post to it
        MethodImplementationBuilder go = new MethodImplementationBuilder(3); // v0, v1, p0 = v2
        go.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, method("Landroid/os/Looper;", "myLooper", List.of(), "Landroid/os/Looper;")));
        go.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        go.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, method("Landroid/os/Looper;", "getMainLooper", List.of(), "Landroid/os/Looper;")));
        go.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1));
        go.addInstruction(new BuilderInstruction22t(Opcode.IF_NE, 0, 1, go.getLabel("post")));
        go.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 2, 0, 0, 0, 0, method(HELPER, "run", List.of(), "V")));
        go.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        go.addLabel("post");
        go.addInstruction(new BuilderInstruction21c(Opcode.NEW_INSTANCE, 0, new ImmutableTypeReference("Landroid/os/Handler;")));
        go.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_DIRECT, 2, 0, 1, 0, 0, 0, method("Landroid/os/Handler;", "<init>", List.of("Landroid/os/Looper;"), "V")));
        go.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 2, 0, 0, 0, method("Landroid/os/Handler;", "post", List.of("Ljava/lang/Runnable;"), "Z")));
        go.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        methods.add(new ImmutableMethod(HELPER, "go", List.of(new ImmutableMethodParameter(HELPER, Set.of(), null)), "V",
                AccessFlags.PRIVATE.getValue() | AccessFlags.STATIC.getValue(), Set.of(), Set.of(), go.getMethodImplementation()));

        // run(): v0..v5 call registers, v6 op, v7 this
        MethodImplementationBuilder run = new MethodImplementationBuilder(8);
        run.addInstruction(new BuilderInstruction22c(Opcode.IGET, 6, 7, field("op", "I")));
        for (Object[] c : CALLS) {
            List<?> types = (List<?>) c[1]; int op = (Integer) c[2]; String next = "next" + op;
            run.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, op));
            run.addInstruction(new BuilderInstruction22t(Opcode.IF_NE, 6, 0, run.getLabel(next)));
            run.addInstruction(new BuilderInstruction22c(Opcode.IGET_OBJECT, 0, 7, field("view", WEBVIEW)));
            int stringIndex = 0;
            for (int p = 0; p < types.size(); p++) {
                String t = (String) types.get(p);
                run.addInstruction(new BuilderInstruction22c(Opcode.IGET_OBJECT, 1 + p, 7, t.equals(S) ? field(ARGS[stringIndex++], S) : field("m", MAP)));
            }
            List<String> params = new ArrayList<>(); for (Object t : types) params.add((String) t);
            ImmutableMethodReference target = method(WEBVIEW, (String) c[0], params, "V");
            int count = 1 + types.size();
            if (count <= 5) run.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, count, 0, 1, 2, 3, 4, target));
            else run.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 0, count, target));
            run.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            run.addLabel(next);
        }
        run.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        methods.add(new ImmutableMethod(HELPER, "run", List.of(), "V", AccessFlags.PUBLIC.getValue(), Set.of(), Set.of(), run.getMethodImplementation()));

        return new ImmutableClassDef(HELPER, AccessFlags.PUBLIC.getValue() | AccessFlags.FINAL.getValue(), "Ljava/lang/Object;",
                List.of("Ljava/lang/Runnable;"), null, Set.of(), fields, methods);
    }

    private static boolean ours(String type) { return type.startsWith("Lcom/hcode91/pocketmicro/") || type.startsWith("Lcom/hcode91/pocketmicro/"); }

    private static BuilderInstruction toStatic(Instruction i, ImmutableMethodReference target) {
        if (i instanceof FiveRegisterInstruction) {
            FiveRegisterInstruction f = (FiveRegisterInstruction) i;
            return new BuilderInstruction35c(Opcode.INVOKE_STATIC, f.getRegisterCount(), f.getRegisterC(), f.getRegisterD(), f.getRegisterE(), f.getRegisterF(), f.getRegisterG(), target);
        }
        RegisterRangeInstruction r = (RegisterRangeInstruction) i;
        return new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, r.getStartRegister(), r.getRegisterCount(), target);
    }

    private static Result run(Map<String, byte[]> input, boolean apply) {
        Result result = new Result();
        try {
            long total = 0; for (byte[] d : input.values()) total += d.length;
            if (input.size() > 64 || total > 64L * 1024 * 1024) { result.reason = "DEX budget; no edits"; return result; }
            Map<String, DexBackedDexFile> files = new LinkedHashMap<>(); Map<String, String> supers = new HashMap<>();
            boolean helperPresent = false;
            for (Map.Entry<String, byte[]> e : input.entrySet()) {
                DexBackedDexFile d = new DexBackedDexFile(null, e.getValue()); files.put(e.getKey(), d);
                for (ClassDef c : d.getClasses()) { supers.put(c.getType(), c.getSuperclass()); if (c.getType().equals(HELPER)) helperPresent = true; }
            }
            int calls = 0; boolean helperAdded = helperPresent;
            for (Map.Entry<String, DexBackedDexFile> file : files.entrySet()) {
                DexPool pool = new DexPool(file.getValue().getOpcodes()); boolean changed = false;
                for (ClassDef c : file.getValue().getClasses()) {
                    if (ours(c.getType()) || AccessFlags.INTERFACE.isSet(c.getAccessFlags())) { pool.internClass(c); continue; }
                    List<Method> methods = new ArrayList<>(); boolean classChanged = false;
                    for (Method m : c.getMethods()) {
                        if (m.getImplementation() == null) { methods.add(m); continue; }
                        List<Instruction> code = new ArrayList<>(); Map<Integer, Integer> at = new LinkedHashMap<>(); int n = 0;
                        for (Instruction i : m.getImplementation().getInstructions()) {
                            if (i instanceof org.jf.dexlib2.iface.instruction.formats.UnknownInstruction) throw new UnsupportedOperationException("Reserved DEX instruction in " + c.getType());
                            code.add(i); int op = operation(i, supers); if (op != 0) at.put(n, op); n++;
                        }
                        calls += at.size();
                        if (!apply || at.isEmpty()) { methods.add(m); continue; }
                        MutableMethodImplementation body = new MutableMethodImplementation(m.getImplementation());
                        for (Map.Entry<Integer, Integer> e : at.entrySet()) body.replaceInstruction(e.getKey(), toStatic(code.get(e.getKey()), staticRef(e.getValue())));
                        methods.add(new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(), m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), body));
                        classChanged = true;
                    }
                    if (classChanged) {
                        pool.internClass(new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getFields(), methods));
                        changed = true;
                    } else pool.internClass(c);
                }
                if (apply && changed) {
                    if (!helperAdded) { pool.internClass(helperClass()); helperAdded = true; }
                    MemoryDataStore out = new MemoryDataStore(); pool.writeTo(out); result.replacements.put(file.getKey(), out.getData());
                }
            }
            result.calls = calls;
            if (calls == 0) return result;
            if (apply) {
                Map<String, byte[]> verify = new LinkedHashMap<>(input); verify.putAll(result.replacements);
                if (inspect(verify).calls != 0) throw new IllegalStateException("Postcondition: WebView calls remain after repair");
            }
            result.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
            result.reason = calls + " WebView page-load call(s) (loadUrl/loadData/loadDataWithBaseURL/stopLoading) run on the main thread: called from a background thread "
                    + "(old AdMob SDKs) they are posted to it instead of crashing today's WebView; on the main thread nothing changes. Ads are not removed.";
        } catch (UnsupportedOperationException e) {
            result.replacements.clear(); result.status = CompatibilityEvidence.Status.UNSUPPORTED; result.reason = e.getMessage() + "; original DEX retained";
        } catch (Exception e) {
            result.replacements.clear(); result.status = CompatibilityEvidence.Status.UNKNOWN; result.reason = e + "; original DEX retained";
        }
        return result;
    }
}
