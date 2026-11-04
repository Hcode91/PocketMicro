package io.github.hozdogus.pocketouya;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c;
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
 * Shared by opt-in helpers that must run first in a game: prepends one argument-less static call
 * to each launch activity's onCreate(Bundle), adding onCreate (calling super) when missing.
 */
final class LaunchHook {
    static final List<String> BUNDLE = List.of("Landroid/os/Bundle;");

    static final class Result {
        final Map<String, byte[]> dex = new LinkedHashMap<>();
        int hooked, already;
    }

    private LaunchHook() {}

    static boolean calls(Instruction i, MethodReference target) {
        if (i.getOpcode() != Opcode.INVOKE_STATIC || !(i instanceof ReferenceInstruction)) return false;
        if (!(((ReferenceInstruction) i).getReference() instanceof MethodReference)) return false;
        MethodReference r = (MethodReference) ((ReferenceInstruction) i).getReference();
        return r.getDefiningClass().equals(target.getDefiningClass()) && r.getName().equals(target.getName());
    }

    static Method onCreate(List<Method> methods) {
        for (Method m : methods) {
            if (!m.getName().equals("onCreate") || !m.getReturnType().equals("V") || AccessFlags.STATIC.isSet(m.getAccessFlags())) continue;
            List<String> p = new ArrayList<>(); for (CharSequence t : m.getParameterTypes()) p.add(t.toString());
            if (p.equals(BUNDLE)) return m;
        }
        return null;
    }

    /** Calls already present anywhere in a launcher's onCreate count as "already" (reconversion). */
    static Result prepend(Map<String, byte[]> dex, Set<String> launchers, ImmutableMethodReference call) throws java.io.IOException {
        Result result = new Result();
        for (var file : dex.entrySet()) {
            DexBackedDexFile d = new DexBackedDexFile(null, file.getValue());
            List<ClassDef> classes = new ArrayList<>(); boolean touched = false;
            for (ClassDef c : d.getClasses()) {
                if (!launchers.contains(c.getType()) || AccessFlags.INTERFACE.isSet(c.getAccessFlags())) { classes.add(c); continue; }
                List<Method> original = new ArrayList<>(); for (Method m : c.getMethods()) original.add(m);
                Method create = onCreate(original);
                if (create != null && create.getImplementation() == null) { classes.add(c); continue; }
                if (create != null) {
                    boolean present = false;
                    for (Instruction i : create.getImplementation().getInstructions()) if (calls(i, call)) present = true;
                    if (present) { result.already++; classes.add(c); continue; }
                }
                if (create == null && c.getSuperclass() == null) { classes.add(c); continue; }
                List<Method> methods = new ArrayList<>();
                for (Method m : original) {
                    if (m != create) { methods.add(m); continue; }
                    MutableMethodImplementation body = new MutableMethodImplementation(m.getImplementation());
                    body.addInstruction(0, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, call));
                    methods.add(new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(), m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), body));
                }
                if (create == null) {
                    var body = new ImmutableMethodImplementation(2, List.of(
                            new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, call),
                            new ImmutableInstruction35c(Opcode.INVOKE_SUPER, 2, 0, 1, 0, 0, 0, new ImmutableMethodReference(c.getSuperclass(), "onCreate", BUNDLE, "V")),
                            new ImmutableInstruction10x(Opcode.RETURN_VOID)), List.of(), List.of());
                    methods.add(new ImmutableMethod(c.getType(), "onCreate", List.of(new ImmutableMethodParameter("Landroid/os/Bundle;", Set.of(), null)), "V", AccessFlags.PROTECTED.getValue(), Set.of(), Set.of(), body));
                }
                classes.add(new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getFields(), methods));
                touched = true; result.hooked++;
            }
            if (!touched) continue;
            // Rewriting re-serialises the whole file; deliberately invalid bytecode (old protection) would not survive.
            for (ClassDef c : d.getClasses()) for (Method m : c.getMethods()) if (m.getImplementation() != null) for (Instruction i : m.getImplementation().getInstructions())
                if (i instanceof org.jf.dexlib2.iface.instruction.formats.UnknownInstruction) throw new IllegalStateException(file.getKey() + " contains unknown instructions (" + c.getType() + "); DEX left unchanged");
            DexPool pool = new DexPool(d.getOpcodes()); for (ClassDef c : classes) pool.internClass(c);
            MemoryDataStore out = new MemoryDataStore(); pool.writeTo(out); result.dex.put(file.getKey(), out.getData());
        }
        return result;
    }
}
