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
 * pocketouya.low-addresses (opt-in experiment): the OUYA's 32-bit kernel kept a game's memory
 * below 3 GB; on a 64-bit phone a 32-bit game also gets addresses up to 4 GB, which some old
 * engines' own memory managers may mishandle (Soul Fjord's Scaleform crash is the case under
 * test; unproven). The game's launch activities call the helper's LowAddress.reserve() before
 * anything else in onCreate; that loads libpocketouya_lowaddr.so, which reserves the free
 * address space above 2 GB (3 GB if room below is short). No game code or library is patched
 * beyond that one call; the helper library is added next to the game's own 32-bit libraries.
 */
public final class LowAddressRule {
    public static final String ID = "pocketouya.low-addresses";
    static final String HELPER = "Lio/github/hcode91/pocketouya/bridge/LowAddress;";
    static final String LIBRARY_FILE = "libpocketouya_lowaddr.so";
    static final ImmutableMethodReference RESERVE = new ImmutableMethodReference(HELPER, "reserve", List.of(), "V");
    static final List<String> BUNDLE = List.of("Landroid/os/Bundle;");

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "not requested";
        public final Map<String, byte[]> replacements = new LinkedHashMap<>();
        /** True when the converted APK needs the PocketOuya helper DEX appended. */
        public boolean needsBridge;
        String line() { return status + " " + ID + ": " + reason; }
    }

    private LowAddressRule() {}

    /** Library folder for the helper: the game's 32-bit ARM folder, or null with a reason. */
    static String libraryDir(Collection<String> entries, StringBuilder why) {
        boolean v7 = false, v5 = false;
        for (String n : entries) {
            if (!n.startsWith("lib/") || !n.endsWith(".so")) continue;
            String abi = n.substring(4, Math.max(4, n.indexOf('/', 4)));
            if (abi.equals("armeabi-v7a")) v7 = true;
            else if (abi.equals("armeabi")) v5 = true;
            else { why.append("the APK also ships ").append(abi).append(" libraries, so Android may not run it as a 32-bit ARM game"); return null; }
        }
        if (v7) return "lib/armeabi-v7a/";
        if (v5) return "lib/armeabi/";
        why.append("no 32-bit ARM native libraries; Java-only games keep Android's normal layout");
        return null;
    }

    static boolean calls(Instruction i) {
        return i.getOpcode() == Opcode.INVOKE_STATIC && i instanceof ReferenceInstruction
                && ((ReferenceInstruction) i).getReference() instanceof MethodReference
                && ((MethodReference) ((ReferenceInstruction) i).getReference()).getDefiningClass().equals(HELPER)
                && ((MethodReference) ((ReferenceInstruction) i).getReference()).getName().equals("reserve");
    }

    static Method onCreate(List<Method> methods) {
        for (Method m : methods) {
            if (!m.getName().equals("onCreate") || !m.getReturnType().equals("V") || AccessFlags.STATIC.isSet(m.getAccessFlags())) continue;
            List<String> p = new ArrayList<>(); for (CharSequence t : m.getParameterTypes()) p.add(t.toString());
            if (p.equals(BUNDLE)) return m;
        }
        return null;
    }

    /**
     * @param dex       game DEX files (after any other DEX repairs)
     * @param launchers launch activity types ("Lcom/example/Main;") from the manifest
     * @param entries   all APK entry names
     * @param library   the helper library bytes (armeabi-v7a)
     */
    public static Result apply(Map<String, byte[]> dex, Set<String> launchers, Collection<String> entries, byte[] library) {
        Result result = new Result();
        try {
            StringBuilder why = new StringBuilder();
            String dir = libraryDir(entries, why);
            if (dir == null) { result.status = CompatibilityEvidence.Status.UNSUPPORTED; result.reason = why + "; not applied"; return result; }
            if (library == null || library.length == 0) throw new IllegalStateException("helper library missing from this PocketOuya build");
            if (entries.contains(dir + LIBRARY_FILE)) throw new IllegalStateException("APK already contains " + LIBRARY_FILE + "; reimport the original APK");
            int hooked = 0, already = 0;
            for (var file : dex.entrySet()) {
                DexBackedDexFile d = new DexBackedDexFile(null, file.getValue());
                List<ClassDef> classes = new ArrayList<>(); boolean touched = false;
                for (ClassDef c : d.getClasses()) {
                    if (!launchers.contains(c.getType()) || AccessFlags.INTERFACE.isSet(c.getAccessFlags())) { classes.add(c); continue; }
                    List<Method> original = new ArrayList<>(); for (Method m : c.getMethods()) original.add(m);
                    Method create = onCreate(original);
                    if (create != null && (create.getImplementation() == null)) { classes.add(c); continue; }
                    if (create != null) {
                        Instruction first = create.getImplementation().getInstructions().iterator().next();
                        if (calls(first)) { already++; classes.add(c); continue; }
                    }
                    List<Method> methods = new ArrayList<>();
                    for (Method m : original) {
                        if (m != create) { methods.add(m); continue; }
                        MutableMethodImplementation body = new MutableMethodImplementation(m.getImplementation());
                        body.addInstruction(0, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, RESERVE));
                        methods.add(new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(), m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), body));
                    }
                    if (create == null) {
                        if (c.getSuperclass() == null) { classes.add(c); continue; }
                        var body = new ImmutableMethodImplementation(2, List.of(
                                new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, RESERVE),
                                new ImmutableInstruction35c(Opcode.INVOKE_SUPER, 2, 0, 1, 0, 0, 0, new ImmutableMethodReference(c.getSuperclass(), "onCreate", BUNDLE, "V")),
                                new ImmutableInstruction10x(Opcode.RETURN_VOID)), List.of(), List.of());
                        methods.add(new ImmutableMethod(c.getType(), "onCreate", List.of(new ImmutableMethodParameter("Landroid/os/Bundle;", Set.of(), null)), "V", AccessFlags.PROTECTED.getValue(), Set.of(), Set.of(), body));
                    }
                    classes.add(new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getFields(), methods));
                    touched = true; hooked++;
                }
                if (!touched) continue;
                // Rewriting re-serialises the whole file; deliberately invalid bytecode (old protection) would not survive.
                for (ClassDef c : d.getClasses()) for (Method m : c.getMethods()) if (m.getImplementation() != null) for (Instruction i : m.getImplementation().getInstructions())
                    if (i instanceof org.jf.dexlib2.iface.instruction.formats.UnknownInstruction) throw new IllegalStateException(file.getKey() + " contains unknown instructions (" + c.getType() + "); DEX left unchanged");
                DexPool pool = new DexPool(d.getOpcodes()); for (ClassDef c : classes) pool.internClass(c);
                MemoryDataStore out = new MemoryDataStore(); pool.writeTo(out); result.replacements.put(file.getKey(), out.getData());
            }
            if (hooked == 0 && already == 0) { result.status = CompatibilityEvidence.Status.UNSUPPORTED; result.reason = "no launch activity found in the game's DEX; not applied"; result.replacements.clear(); return result; }
            if (hooked == 0) { result.status = CompatibilityEvidence.Status.SKIPPED; result.reason = "launch activities already call the helper; reimport the original APK"; return result; }
            result.replacements.put(dir + LIBRARY_FILE, library);
            result.needsBridge = true;
            result.status = CompatibilityEvidence.Status.VERIFIED;
            result.reason = "experimental: " + hooked + " launch activit" + (hooked == 1 ? "y loads" : "ies load") + " " + LIBRARY_FILE + " (added to " + dir
                    + ") before anything else, reserving free address space above 2 GB (3 GB if room below is short) so later allocations land low as on the OUYA. "
                    + "Its result is logged under PocketOuyaLowAddr.";
        } catch (Exception e) {
            result.replacements.clear(); result.needsBridge = false;
            result.status = CompatibilityEvidence.Status.UNKNOWN; result.reason = e.getMessage() + "; not applied";
        }
        return result;
    }
}
