package io.github.hcode91.pocketouya.bridge;

import android.util.Log;

/**
 * Opt-in "low memory addresses" experiment: called first thing in the game's launch activity
 * onCreate. Loading the native helper reserves free address space above 2 GB (or 3 GB) so the
 * game's later allocations land low, as they did on the OUYA's 32-bit kernel. The helper logs
 * what it reserved under the tag PocketOuyaLowAddr.
 */
public final class LowAddress {
    static final String LIBRARY = "pocketouya_lowaddr";
    private static boolean attempted;

    private LowAddress() {}

    public static synchronized void reserve() {
        if (attempted) return;
        attempted = true;
        try { System.loadLibrary(LIBRARY); }
        catch (Throwable error) { Log.w("PocketOuyaLowAddr", "Helper library not loaded; addresses unchanged", error); }
    }
}
