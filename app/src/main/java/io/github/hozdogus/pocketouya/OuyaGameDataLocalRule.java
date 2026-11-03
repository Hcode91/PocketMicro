package io.github.hozdogus.pocketouya;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.*;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/**
 * ouya.game-data.local-store: OuyaFacade.getGameData/putGameData keep a game's saves (high
 * scores, options) in the OUYA system's content provider "tv.ouya.userdata". On a phone that
 * provider does not exist: the query returns null and the SDK crashes on cursor.moveToNext()
 * (X S.E.E.D loading hiscores) or cursor.getCount() when saving.
 *
 * Both methods get a short prefix: when "tv.ouya.userdata" cannot be reached, the value is read
 * from or written to the game's own SharedPreferences file "pocketouya_ouya_gamedata" (same
 * key/value strings, private to the game, kept across launches). Where the provider exists the
 * original SDK code runs unchanged. Nothing here touches purchases or receipts.
 */
public final class OuyaGameDataLocalRule {
    public static final String ID = "ouya.game-data.local-store";
    static final String FACADE = "Ltv/ouya/console/api/OuyaFacade;", STRING = "Ljava/lang/String;", CONTEXT = "Landroid/content/Context;",
            PREFS = "Landroid/content/SharedPreferences;", EDITOR = "Landroid/content/SharedPreferences$Editor;", HELPER = "pocketouyaLocalGameData",
            AUTHORITY = "tv.ouya.userdata", FILE = "pocketouya_ouya_gamedata";

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "OUYA game data API absent";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
    }

    private OuyaGameDataLocalRule() {}
    public static Result inspect(Map<String, byte[]> dex) { return run(dex, false); }
    public static Result repair(Map<String, byte[]> dex) { return run(dex, true); }

    static boolean getter(Method m) { return m.getName().equals("getGameData") && String.join("", m.getParameterTypes()).equals(STRING) && m.getReturnType().equals(STRING); }
    static boolean setter(Method m) { return m.getName().equals("putGameData") && String.join("", m.getParameterTypes()).equals(STRING + STRING) && m.getReturnType().equals("V"); }

    /** Instance method with a body and at least 3 local registers ahead of its parameters (locals are unset at entry, so the prefix may use v0..v2). */
    static boolean editable(Method m) {
        if (m.getImplementation() == null || AccessFlags.STATIC.isSet(m.getAccessFlags())) return false;
        return m.getImplementation().getRegisterCount() - 1 - m.getParameterTypes().size() >= 3;
    }

    private static boolean hasContext(ClassDef c) {
        for (Field f : c.getFields()) if (f.getName().equals("context") && f.getType().equals(CONTEXT) && !AccessFlags.STATIC.isSet(f.getAccessFlags())) return true;
        return false;
    }

    private static ImmutableMethodReference m(String owner, String name, List<String> params, String ret) { return new ImmutableMethodReference(owner, name, params, ret); }

    /** static SharedPreferences pocketouyaLocalGameData(OuyaFacade f): local store when tv.ouya.userdata is unreachable, else null. v0..v2 scratch, p0 = v3. */
    static Method helper() {
        MethodImplementationBuilder b = new MethodImplementationBuilder(4);
        String cpc = "Landroid/content/ContentProviderClient;", cr = "Landroid/content/ContentResolver;";
        b.addInstruction(new BuilderInstruction22c(Opcode.IGET_OBJECT, 0, 3, new ImmutableFieldReference(FACADE, "context", CONTEXT)));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 0, b.getLabel("original")));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, m(CONTEXT, "getContentResolver", List.of(), cr)));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 1, b.getLabel("original")));
        b.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 2, new ImmutableStringReference(AUTHORITY)));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 2, 0, 0, 0, m(cr, "acquireContentProviderClient", List.of(STRING), cpc)));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 1, b.getLabel("local")));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, m(cpc, "release", List.of(), "Z")));
        b.addLabel("original");
        b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 0));
        b.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        b.addLabel("local");
        b.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 1, new ImmutableStringReference(FILE)));
        b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 2, 0));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 0, 1, 2, 0, 0, m(CONTEXT, "getSharedPreferences", List.of(STRING, "I"), PREFS)));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        b.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        return new ImmutableMethod(FACADE, HELPER, List.of(new ImmutableMethodParameter(FACADE, Set.of(), null)), PREFS, 0x100a, Set.of(), Set.of(), b.getMethodImplementation());
    }

    /** Prefix: v0 = helper(this); if null run the original body, else answer from SharedPreferences. */
    static Method prefixed(Method original, boolean get) {
        MutableMethodImplementation body = new MutableMethodImplementation(original.getImplementation());
        int regs = body.getRegisterCount(), self = regs - 1 - original.getParameterTypes().size(), key = self + 1, value = self + 2;
        Label start = body.newLabelForIndex(0);
        List<BuilderInstruction> prefix = new ArrayList<>();
        prefix.add(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, self, 0, 0, 0, 0, m(FACADE, HELPER, List.of(FACADE), PREFS)));
        prefix.add(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        prefix.add(new BuilderInstruction21t(Opcode.IF_EQZ, 0, start));
        if (get) {
            prefix.add(new BuilderInstruction11n(Opcode.CONST_4, 1, 0));
            prefix.add(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 3, 0, key, 1, 0, 0, m(PREFS, "getString", List.of(STRING, STRING), STRING)));
            prefix.add(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
            prefix.add(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        } else {
            prefix.add(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 0, 0, 0, 0, 0, m(PREFS, "edit", List.of(), EDITOR)));
            prefix.add(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
            prefix.add(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 3, 0, key, value, 0, 0, m(EDITOR, "putString", List.of(STRING, STRING), EDITOR)));
            prefix.add(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
            prefix.add(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 0, 0, 0, 0, 0, m(EDITOR, "apply", List.of(), "V")));
            prefix.add(new BuilderInstruction10x(Opcode.RETURN_VOID));
        }
        for (int i = prefix.size() - 1; i >= 0; i--) body.addInstruction(0, prefix.get(i));
        return new ImmutableMethod(original.getDefiningClass(), original.getName(), original.getParameters(), original.getReturnType(), original.getAccessFlags(),
                original.getAnnotations(), original.getHiddenApiRestrictions(), body);
    }

    private static Result run(Map<String, byte[]> input, boolean apply) {
        Result r = new Result();
        try {
            for (var file : input.entrySet()) {
                DexBackedDexFile dex = new DexBackedDexFile(null, file.getValue());
                ClassDef facade = null;
                for (ClassDef c : dex.getClasses()) if (c.getType().equals(FACADE)) facade = c;
                if (facade == null) continue;
                Method get = null, put = null; boolean done = false;
                for (Method mm : facade.getMethods()) { if (getter(mm)) get = mm; if (setter(mm)) put = mm; if (mm.getName().equals(HELPER)) done = true; }
                if (get == null && put == null) return r;
                if (done) { r.reason = "already keeps OUYA game data locally"; return r; }
                if (!hasContext(facade) || (get != null && !editable(get)) || (put != null && !editable(put))) {
                    r.status = CompatibilityEvidence.Status.UNSUPPORTED; r.reason = "OuyaFacade game data layout not recognised; unchanged"; return r;
                }
                if (apply) {
                    DexPool pool = new DexPool(dex.getOpcodes());
                    for (ClassDef c : dex.getClasses()) {
                        if (!c.getType().equals(FACADE)) { pool.internClass(c); continue; }
                        List<Method> methods = new ArrayList<>();
                        for (Method mm : c.getMethods()) methods.add(getter(mm) ? prefixed(mm, true) : setter(mm) ? prefixed(mm, false) : mm);
                        methods.add(helper());
                        pool.internClass(new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getFields(), methods));
                    }
                    MemoryDataStore out = new MemoryDataStore(); pool.writeTo(out); r.replacements.put(file.getKey(), out.getData());
                }
                r.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
                r.reason = (get != null ? "getGameData" : "") + (get != null && put != null ? " and " : "") + (put != null ? "putGameData" : "")
                        + ": when the OUYA save provider (" + AUTHORITY + ") is missing, game data is kept in the game's own private storage (" + FILE
                        + ") instead of crashing on a null cursor; with the provider present the SDK code runs unchanged. Purchases untouched.";
                return r;
            }
        } catch (Exception e) {
            r.replacements.clear(); r.status = CompatibilityEvidence.Status.UNKNOWN; r.reason = e + "; original DEX retained";
        }
        return r;
    }
}
