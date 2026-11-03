package com.yoyogames.runner;

/** Host-test stand-in for a converted GameMaker runner (marker method present). */
public final class RunnerJNILib {
    public static int realControllers = 1, registeredSlot = -1, registeredButtons, registeredAxes;
    private static int pocketouyaOriginal_GamepadsCount() { return realControllers; }
    public static int GamepadsCount() { return io.github.hcode91.pocketouya.bridge.GameMakerPad.count(pocketouyaOriginal_GamepadsCount()); }
    public static void registerGamepadConnected(int slot, int buttons, int axes) { registeredSlot = slot; registeredButtons = buttons; registeredAxes = axes; }
}
