package com.hcode91.pocketmicro.bridge;

import java.lang.reflect.Method;

/**
 * Touch pad route for Unreal Engine 3 Android games (UE3JavaApp). Their Java input code drops
 * events that have no physical InputDevice, but behind it the engine exposes two native entry
 * points on the activity: NativeCallback_JoystickAxisEvent(deviceId, action, axis, value, time)
 * and NativeCallback_JoystickButtonEvent(deviceId, action, keyCode, time). This sink calls them
 * directly with Android axis constants and key codes, as a separate virtual controller.
 */
public final class Ue3PadSink implements VirtualPad.Sink {
    static final int DEVICE_ID = 1, ACTION_MOVE = 2;
    private final Object activity;
    private final Method axisEvent, buttonEvent;
    private final Clock clock;

    public interface Clock { long now(); }

    public Ue3PadSink(Object activity) throws NoSuchMethodException { this(activity, () -> System.nanoTime() / 1_000_000L); }

    public Ue3PadSink(Object activity, Clock clock) throws NoSuchMethodException {
        this.activity = activity;
        this.clock = clock;
        axisEvent = find(activity.getClass(), "NativeCallback_JoystickAxisEvent", int.class, int.class, int.class, float.class, long.class);
        buttonEvent = find(activity.getClass(), "NativeCallback_JoystickButtonEvent", int.class, int.class, int.class, long.class);
    }

    private static Method find(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Method m = c.getDeclaredMethod(name, params); m.setAccessible(true); return m; } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(name + " (not an Unreal Engine 3 activity)");
    }

    @Override public void key(int key, int action) {
        if (action != 0 && action != 1) return;
        call(buttonEvent, DEVICE_ID, action, key, clock.now());
    }

    @Override public void axis(int axis, float value) {
        if (axis != 0 && axis != 1 && axis != 11 && axis != 14) return;
        if (Float.isNaN(value) || Float.isInfinite(value)) return;
        call(axisEvent, DEVICE_ID, ACTION_MOVE, axis, Math.max(-1f, Math.min(1f, value)), clock.now());
    }

    private void call(Method m, Object... args) {
        try { m.invoke(activity, args); }
        catch (ReflectiveOperationException | LinkageError e) { throw new IllegalStateException("Unreal Engine 3 input call failed", e); }
    }
}
