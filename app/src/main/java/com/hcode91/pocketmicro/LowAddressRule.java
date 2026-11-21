package com.hcode91.pocketmicro;

import java.util.*;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;

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
            LaunchHook.Result hook = LaunchHook.prepend(dex, launchers, RESERVE);
            int hooked = hook.hooked, already = hook.already;
            result.replacements.putAll(hook.dex);
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
