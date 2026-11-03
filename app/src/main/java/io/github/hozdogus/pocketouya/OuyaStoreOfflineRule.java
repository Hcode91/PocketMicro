package io.github.hozdogus.pocketouya;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.MethodImplementationBuilder;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/**
 * ouya.store-offline.answer-requests: the OUYA SDK (OuyaFacade) queues every store request in
 * pendingRequests until the OUYA IapService connects. The store app no longer exists, so the
 * queue is never drained and no listener is ever called: games wait at their splash screen
 * forever (ShadowGun, ODK 51).
 *
 * OuyaFacade.runRequest is replaced with the same logic plus one step: when a request would be
 * queued and the OUYA store package ("tv.ouya") is not installed, the request's listener gets
 * onFailure(OuyaErrorCodes.SERVER_OFFLINE = 3005, "OUYA store unavailable", Bundle) instead, the
 * SDK's own failure path. Nothing is purchased or unlocked; where the store exists, behaviour is
 * unchanged.
 */
public final class OuyaStoreOfflineRule {
    public static final String ID = "ouya.store-offline.answer-requests";
    static final String FACADE = "Ltv/ouya/console/api/OuyaFacade;", REQUEST = "Ltv/ouya/console/api/RequestRunnable;",
            LISTENER = "Ltv/ouya/console/api/OuyaResponseListener;", RUNNABLE = "Ljava/lang/Runnable;", HELPER = "pocketouyaStoreOffline";
    static final int SERVER_OFFLINE = 3005;

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "OUYA SDK request queue absent";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
    }

    private OuyaStoreOfflineRule() {}
    public static Result inspect(Map<String, byte[]> dex) { return run(dex, false); }
    public static Result repair(Map<String, byte[]> dex) { return run(dex, true); }

    private static boolean hasField(ClassDef c, String name, String type) {
        for (Field f : c.getFields()) if (f.getName().equals(name) && f.getType().equals(type) && !AccessFlags.STATIC.isSet(f.getAccessFlags())) return true;
        return false;
    }
    private static Method method(ClassDef c, String name, String params, String ret) {
        for (Method m : c.getMethods()) if (m.getName().equals(name) && String.join("", m.getParameterTypes()).equals(params) && m.getReturnType().equals(ret)) return m;
        return null;
    }

    /** The ODK layout this rule understands. */
    static boolean matches(ClassDef facade, ClassDef request) {
        if (facade == null || request == null) return false;
        Method run = method(facade, "runRequest", RUNNABLE, "V"), ensure = method(facade, "ensureServiceIsBound", "", "V");
        return run != null && run.getImplementation() != null && !AccessFlags.STATIC.isSet(run.getAccessFlags()) && ensure != null && !AccessFlags.STATIC.isSet(ensure.getAccessFlags())
                && method(facade, HELPER, FACADE + RUNNABLE, "Z") == null
                && hasField(facade, "remoteService", "Ltv/ouya/console/internal/IIapServiceDefinition;") && hasField(facade, "pendingRequests", "Ljava/util/List;")
                && hasField(facade, "context", "Landroid/content/Context;") && hasField(request, "listener", LISTENER);
    }

    private static ImmutableMethodReference m(String owner, String name, List<String> params, String ret) { return new ImmutableMethodReference(owner, name, params, ret); }

    /** runRequest(Runnable): this = v2, request = v3; v0/v1 scratch. */
    static Method runRequest(Method original) {
        MethodImplementationBuilder b = new MethodImplementationBuilder(4);
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 2, 0, 0, 0, 0, m(FACADE, "ensureServiceIsBound", List.of(), "V")));
        b.addInstruction(new BuilderInstruction22c(Opcode.IGET_OBJECT, 0, 2, new ImmutableFieldReference(FACADE, "remoteService", "Ltv/ouya/console/internal/IIapServiceDefinition;")));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 0, b.getLabel("queue")));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 3, 0, 0, 0, 0, m(RUNNABLE, "run", List.of(), "V")));
        b.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        b.addLabel("queue");
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, 2, 3, 0, 0, 0, m(FACADE, HELPER, List.of(FACADE, RUNNABLE), "Z")));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_NEZ, 0, b.getLabel("done")));
        b.addInstruction(new BuilderInstruction22c(Opcode.IGET_OBJECT, 0, 2, new ImmutableFieldReference(FACADE, "pendingRequests", "Ljava/util/List;")));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 2, 0, 3, 0, 0, 0, m("Ljava/util/List;", "add", List.of("Ljava/lang/Object;"), "Z")));
        b.addLabel("done");
        b.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        return new ImmutableMethod(FACADE, original.getName(), original.getParameters(), "V", original.getAccessFlags(), original.getAnnotations(), original.getHiddenApiRestrictions(), b.getMethodImplementation());
    }

    /**
     * static boolean pocketouyaStoreOffline(OuyaFacade facade, Runnable request): v0..v3 scratch, p0 = v4, p1 = v5.
     * true (and listener.onFailure(3005, ...)) when the request has a listener and "tv.ouya" is not installed.
     */
    static Method helper() {
        MethodImplementationBuilder b = new MethodImplementationBuilder(6);
        String pm = "Landroid/content/pm/PackageManager;";
        b.addInstruction(new BuilderInstruction22c(Opcode.IGET_OBJECT, 0, 4, new ImmutableFieldReference(FACADE, "context", "Landroid/content/Context;")));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 0, b.getLabel("keep")));
        b.addInstruction(new BuilderInstruction22c(Opcode.INSTANCE_OF, 1, 5, new ImmutableTypeReference(REQUEST)));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 1, b.getLabel("keep")));
        b.addLabel("tryStart");
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, m("Landroid/content/Context;", "getPackageManager", List.of(), pm)));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        b.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 1, new ImmutableStringReference("tv.ouya")));
        b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 2, 0));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 0, 1, 2, 0, 0, m(pm, "getPackageInfo", List.of("Ljava/lang/String;", "I"), "Landroid/content/pm/PackageInfo;")));
        b.addLabel("tryEnd");
        b.addInstruction(new BuilderInstruction20t(Opcode.GOTO_16, b.getLabel("keep")));   // store installed: keep the original queueing
        b.addLabel("missing");
        b.addInstruction(new BuilderInstruction21c(Opcode.CHECK_CAST, 5, new ImmutableTypeReference(REQUEST)));
        b.addInstruction(new BuilderInstruction22c(Opcode.IGET_OBJECT, 0, 5, new ImmutableFieldReference(REQUEST, "listener", LISTENER)));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 0, b.getLabel("keep")));
        b.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, 1, SERVER_OFFLINE));
        b.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 2, new ImmutableStringReference("OUYA store unavailable")));
        b.addInstruction(new BuilderInstruction21c(Opcode.NEW_INSTANCE, 3, new ImmutableTypeReference("Landroid/os/Bundle;")));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_DIRECT, 1, 3, 0, 0, 0, 0, m("Landroid/os/Bundle;", "<init>", List.of(), "V")));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 4, 0, 1, 2, 3, 0, m(LISTENER, "onFailure", List.of("I", "Ljava/lang/String;", "Landroid/os/Bundle;"), "V")));
        b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 1));
        b.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 0));
        b.addLabel("keep");
        b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 0));
        b.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 0));
        b.addCatch(new ImmutableTypeReference("Landroid/content/pm/PackageManager$NameNotFoundException;"), b.getLabel("tryStart"), b.getLabel("tryEnd"), b.getLabel("missing"));
        return new ImmutableMethod(FACADE, HELPER, List.of(new ImmutableMethodParameter(FACADE, Set.of(), null), new ImmutableMethodParameter(RUNNABLE, Set.of(), null)),
                "Z", 0x100a, Set.of(), Set.of(), b.getMethodImplementation());
    }

    private static Result run(Map<String, byte[]> input, boolean apply) {
        Result r = new Result();
        try {
            Map<String, ClassDef> classes = new HashMap<>(); Map<String, DexBackedDexFile> files = new LinkedHashMap<>();
            for (var e : input.entrySet()) { var d = new DexBackedDexFile(null, e.getValue()); files.put(e.getKey(), d); for (ClassDef c : d.getClasses()) classes.put(c.getType(), c); }
            ClassDef facade = classes.get(FACADE);
            if (facade == null) return r;
            if (!matches(facade, classes.get(REQUEST))) {
                if (method(facade, HELPER, FACADE + RUNNABLE, "Z") != null) { r.reason = "already answers offline store requests"; return r; }
                r.status = CompatibilityEvidence.Status.UNSUPPORTED; r.reason = "OuyaFacade request queue layout not recognised; unchanged"; return r;
            }
            if (apply) for (var file : files.entrySet()) {
                boolean has = false; for (ClassDef c : file.getValue().getClasses()) if (c.getType().equals(FACADE)) has = true;
                if (!has) continue;
                DexPool pool = new DexPool(file.getValue().getOpcodes());
                for (ClassDef c : file.getValue().getClasses()) {
                    if (!c.getType().equals(FACADE)) { pool.internClass(c); continue; }
                    List<Method> methods = new ArrayList<>();
                    for (Method mm : c.getMethods()) methods.add(mm.getName().equals("runRequest") && String.join("", mm.getParameterTypes()).equals(RUNNABLE) ? runRequest(mm) : mm);
                    methods.add(helper());
                    pool.internClass(new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getFields(), methods));
                }
                MemoryDataStore out = new MemoryDataStore(); pool.writeTo(out); r.replacements.put(file.getKey(), out.getData());
            }
            r.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
            r.reason = "OUYA store requests that would wait forever for the missing store service now get the SDK's SERVER_OFFLINE (3005) failure when the OUYA store is not installed. Nothing is purchased or unlocked.";
        } catch (Exception e) {
            r.replacements.clear(); r.status = CompatibilityEvidence.Status.UNKNOWN; r.reason = e + "; original DEX retained";
        }
        return r;
    }
}
