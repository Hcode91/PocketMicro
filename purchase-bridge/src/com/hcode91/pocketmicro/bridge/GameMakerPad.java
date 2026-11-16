package com.hcode91.pocketmicro.bridge;

/**
 * Virtual controller seen by GameMaker 1.x runners. GameMaker only polls controllers through six
 * static Java methods on com.yoyogames.runner.RunnerJNILib (GamepadsCount, GamepadConnected,
 * GamepadDescription, GamepadButtonValues, GamepadAxesValues, GamepadGMLMapping). The converter
 * wraps those methods so that, while the touch pad is active, one extra slot answers from here;
 * every other slot still goes to the game's own code (real controllers keep working).
 *
 * Button array uses GameMaker's gp_* order: face1..face4, shoulderl, shoulderr, shoulderlb,
 * shoulderrb, select, start, stickl, stickr, padu, padd, padl, padr. Axes: lh, lv, rh, rv.
 */
public final class GameMakerPad {
    public static final int GP_FIRST = 32769, GP_LAST_BUTTON = 32784, GP_FIRST_AXIS = 32785, GP_LAST_AXIS = 32788;
    public static final int BUTTONS = 16, AXES = 4;
    private static volatile boolean active;
    private static volatile int slot = -1;
    private static final float[] buttons = new float[BUTTONS], axes = new float[AXES];

    private GameMakerPad() {}

    // ---- called from the rewritten RunnerJNILib methods ----
    public static int count(int original) { return active ? Math.max(original, slot + 1) : original; }
    public static boolean owns(int index) { return active && index == slot; }
    public static String description() { return "PocketMicro touch pad"; }
    public static float[] buttons() { synchronized (buttons) { return buttons.clone(); } }
    public static float[] axes() { synchronized (buttons) { return axes.clone(); } }
    public static int mapping(int gml) {
        if (gml >= GP_FIRST && gml <= GP_LAST_BUTTON) return gml - GP_FIRST;
        if (gml >= GP_FIRST_AXIS && gml <= GP_LAST_AXIS) return gml - GP_FIRST_AXIS;
        return -1;
    }

    // ---- called from the touch pad ----
    /** True when the converter wrapped this runner (marker method present). */
    public static boolean supported(Class<?> runner) {
        try { runner.getDeclaredMethod("pocketouyaOriginal_GamepadsCount"); return true; } catch (NoSuchMethodException e) { return false; }
    }
    /** Picks the first free slot (slot 0 is the runner's iCade slot) and tells the runner it connected. */
    public static synchronized void activate(Class<?> runner) throws ReflectiveOperationException {
        if (active) return;
        int original = (Integer) runner.getMethod("GamepadsCount").invoke(null);
        slot = Math.max(1, original);
        active = true;
        try { runner.getMethod("registerGamepadConnected", int.class, int.class, int.class).invoke(null, slot, BUTTONS, AXES); }
        catch (ReflectiveOperationException | LinkageError e) { active = false; slot = -1; throw e instanceof ReflectiveOperationException ? (ReflectiveOperationException) e : new ReflectiveOperationException(e); }
    }
    public static int slot() { return slot; }
    public static void button(int index, boolean down) { if (index < 0 || index >= BUTTONS) return; synchronized (buttons) { buttons[index] = down ? 1f : 0f; } }
    public static void axis(int index, float value) {
        if (index < 0 || index >= AXES || Float.isNaN(value) || Float.isInfinite(value)) return;
        synchronized (buttons) { axes[index] = Math.max(-1f, Math.min(1f, value)); }
    }
    public static void clear() { synchronized (buttons) { java.util.Arrays.fill(buttons, 0f); java.util.Arrays.fill(axes, 0f); } }
    /** Test hook. */
    static void reset() { active = false; slot = -1; clear(); }
}
