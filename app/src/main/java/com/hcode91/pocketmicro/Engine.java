package com.hcode91.pocketmicro;

/** The native PocketMicro engine (runs 32-bit Ouya game code on 64-bit phones). */
final class Engine {
    static {
        System.loadLibrary("pocketouya_engine");
    }

    private Engine() {}

    /** Boots a GameMaker game's engine until its first graphics call; returns a report + log. */
    static native String bootTest(String apkPath, String saveDir);

    /** Loads and starts the game (call on the GL thread). */
    static native boolean start(String apkPath, String saveDir, String packageName);
    /** Runs one game frame (GL thread). Returns false if the engine stopped. */
    static native boolean frame(int width, int height);
    /** Queues a key press: action 0 = down, 1 = up; Android key code (controller buttons too). */
    static native void key(int action, int keyCode);
    /** Status + log so far. */
    static native String log();
}
