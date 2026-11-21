package com.hcode91.pocketmicro;

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
 * ouya.account-placeholder: OUYA games look up the console's OUYA account with
 * AccountManager.getAccountsByType("tv.ouya.account.v1") and take element 0, for example to key
 * a locally cached receipt list (Zombie Driver HD's store-failure path). A phone has no OUYA
 * account, the array is empty and the game crashes with ArrayIndexOutOfBoundsException.
 *
 * Each getAccountsByType call goes through a helper that returns the real result, unless it is
 * empty and the type asked for is "tv.ouya.account.v1": then it returns one local placeholder
 * Account("OUYA", "tv.ouya.account.v1"). Nothing is registered with Android, and the name only keys
 * data the game itself stores; no receipts or purchases exist for it.
 */
public final class OuyaAccountRule {
    public static final String ID = "ouya.account-placeholder";
    static final String AM = "Landroid/accounts/AccountManager;", ACCOUNT = "Landroid/accounts/Account;", STRING = "Ljava/lang/String;",
            TYPE = "tv.ouya.account.v1", NAME = "OUYA", HELPER = "pocketouyaOuyaAccounts";

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "no OUYA account lookups";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
    }

    private OuyaAccountRule() {}
    public static Result inspect(Map<String, byte[]> dex) { return run(dex, false); }
    public static Result repair(Map<String, byte[]> dex) { return run(dex, true); }

    static boolean target(Instruction i) {
        if (i.getOpcode() != Opcode.INVOKE_VIRTUAL || !(i instanceof ReferenceInstruction) || !(((ReferenceInstruction) i).getReference() instanceof MethodReference)) return false;
        MethodReference r = (MethodReference) ((ReferenceInstruction) i).getReference();
        return r.getDefiningClass().equals(AM) && r.getName().equals("getAccountsByType") && r.getReturnType().equals("[" + ACCOUNT)
                && r.getParameterTypes().size() == 1 && r.getParameterTypes().get(0).toString().equals(STRING);
    }

    /** Whether the DEX mentions the OUYA account type at all (only those games are changed). */
    static boolean mentionsOuyaAccount(DexBackedDexFile dex) {
        for (ClassDef c : dex.getClasses()) for (Method m : c.getMethods()) if (m.getImplementation() != null)
            for (Instruction i : m.getImplementation().getInstructions())
                if (i.getOpcode() == Opcode.CONST_STRING && ((org.jf.dexlib2.iface.reference.StringReference) ((ReferenceInstruction) i).getReference()).getString().equals(TYPE)) return true;
        return false;
    }

    /** static Account[] pocketouyaOuyaAccounts(AccountManager am, String type): v0..v3 scratch, p0 = v4, p1 = v5. */
    static Method helper(String owner) {
        MethodImplementationBuilder b = new MethodImplementationBuilder(6);
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 4, 5, 0, 0, 0, new ImmutableMethodReference(AM, "getAccountsByType", List.of(STRING), "[" + ACCOUNT)));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 0, b.getLabel("check")));
        b.addInstruction(new BuilderInstruction12x(Opcode.ARRAY_LENGTH, 1, 0));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_GTZ, 1, b.getLabel("done")));
        b.addLabel("check");
        b.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 1, new ImmutableStringReference(TYPE)));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 5, 0, 0, 0, new ImmutableMethodReference(STRING, "equals", List.of("Ljava/lang/Object;"), "Z")));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 1));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 1, b.getLabel("done")));
        b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 1, 1));
        b.addInstruction(new BuilderInstruction22c(Opcode.NEW_ARRAY, 0, 1, new ImmutableTypeReference("[" + ACCOUNT)));
        b.addInstruction(new BuilderInstruction21c(Opcode.NEW_INSTANCE, 2, new ImmutableTypeReference(ACCOUNT)));
        b.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 3, new ImmutableStringReference(NAME)));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_DIRECT, 3, 2, 3, 5, 0, 0, new ImmutableMethodReference(ACCOUNT, "<init>", List.of(STRING, STRING), "V")));
        b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 1, 0));
        b.addInstruction(new BuilderInstruction23x(Opcode.APUT_OBJECT, 2, 0, 1));
        b.addLabel("done");
        b.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        return new ImmutableMethod(owner, HELPER, List.of(new ImmutableMethodParameter(AM, Set.of(), null), new ImmutableMethodParameter(STRING, Set.of(), null)),
                "[" + ACCOUNT, 0x100a, Set.of(), Set.of(), b.getMethodImplementation());
    }

    private static boolean ours(String type) { return type.startsWith("Lio/github/hcode91/pocketouya/") || type.startsWith("Lcom/hcode91/pocketmicro/"); }

    private static Result run(Map<String, byte[]> input, boolean apply) {
        Result r = new Result();
        int sites = 0;
        try {
            boolean any = false;
            Map<String, DexBackedDexFile> files = new LinkedHashMap<>();
            for (var e : input.entrySet()) { var d = new DexBackedDexFile(null, e.getValue()); files.put(e.getKey(), d); any |= mentionsOuyaAccount(d); }
            if (!any) return r;
            for (var file : files.entrySet()) {
                DexBackedDexFile dex = file.getValue();
                DexPool pool = apply ? new DexPool(dex.getOpcodes()) : null; boolean changed = false;
                for (ClassDef c : dex.getClasses()) {
                    if (ours(c.getType()) || AccessFlags.INTERFACE.isSet(c.getAccessFlags())) { if (apply) pool.internClass(c); continue; }
                    boolean helperPresent = false; for (Method m : c.getMethods()) if (m.getName().equals(HELPER)) helperPresent = true;
                    List<Method> methods = new ArrayList<>(); boolean classChanged = false;
                    for (Method m : c.getMethods()) {
                        if (m.getImplementation() == null || helperPresent) { methods.add(m); continue; }
                        List<Instruction> original = new ArrayList<>(); List<Integer> hits = new ArrayList<>();
                        for (Instruction i : m.getImplementation().getInstructions()) { if (target(i)) hits.add(original.size()); original.add(i); }
                        sites += hits.size();
                        if (!apply || hits.isEmpty()) { methods.add(m); continue; }
                        MutableMethodImplementation body = new MutableMethodImplementation(m.getImplementation());
                        var ref = new ImmutableMethodReference(c.getType(), HELPER, List.of(AM, STRING), "[" + ACCOUNT);
                        for (int at : hits) {
                            var f = (FiveRegisterInstruction) original.get(at);
                            body.replaceInstruction(at, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, f.getRegisterC(), f.getRegisterD(), 0, 0, 0, ref));
                        }
                        methods.add(new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(), m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), body));
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
            if (sites == 0) return r;
            if (apply) {
                Map<String, byte[]> verify = new LinkedHashMap<>(input); verify.putAll(r.replacements);
                if (inspect(verify).status != CompatibilityEvidence.Status.SKIPPED) throw new IllegalStateException("Postcondition: account lookups remain after repair");
            }
            r.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
            r.reason = sites + " AccountManager.getAccountsByType call(s). With no OUYA account on the phone, a lookup of " + TYPE
                    + " returns one local placeholder account named \"" + NAME + "\" instead of an empty array; it is not registered with Android and carries no purchases.";
        } catch (Exception e) {
            r.replacements.clear(); r.status = CompatibilityEvidence.Status.UNKNOWN; r.reason = e + "; original DEX retained";
        }
        return r;
    }
}
