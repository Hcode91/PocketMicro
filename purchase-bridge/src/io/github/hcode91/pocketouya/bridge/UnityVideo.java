package io.github.hcode91.pocketouya.bridge;

import android.util.Log;

/** unity.scaleform-skip-video: records each full-screen Unity video PocketOuya skipped. */
public final class UnityVideo {
    private UnityVideo() {}

    public static void skipped(String file) {
        Log.w("PocketOuyaUnityVideo", "Skipped full-screen video " + file + ": pausing Unity for it crashes this game's Scaleform plugin on modern Android", null);
    }
}
