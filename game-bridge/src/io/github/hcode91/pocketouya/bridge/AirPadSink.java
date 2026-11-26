package io.github.hcode91.pocketouya.bridge;

import android.app.Activity;
import android.os.SystemClock;
import android.view.*;
import java.lang.reflect.*;
import java.util.List;

/**
 * Touch pad route for Adobe AIR games that use AIR's GameInput API. AIR only creates a GameInput
 * device for a physical controller (com.adobe.air.AndroidInputManager.onInputDeviceAdded), so a
 * touch pad is never seen. Once the game has GameInput running (manager listening, native
 * reference set), this sink builds an AndroidInputDevice the same way AIR does (buttons from the
 * game-key list), adds stick axes (X, Y, Z, RZ, HAT_X, HAT_Y), announces it through AIR's own
 * native OnDeviceAdded, and then feeds it key and motion events directly. Keys are also forwarded
 * to a key sink so menus that use the keyboard keep working.
 */
public final class AirPadSink implements VirtualPad.Sink {
    static final int[] AXES = {MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};
    private final Activity activity;
    private final VirtualPad.Sink keys;
    private final Class<?> managerType, deviceType, controlType, typeEnum;
    private Object device;
    private float x, y, z, rz;
    private long lastAttempt;

    public AirPadSink(Activity activity, VirtualPad.Sink keys) throws ReflectiveOperationException {
        this.activity = activity; this.keys = keys;
        ClassLoader loader = activity.getClassLoader();
        managerType = Class.forName("com.adobe.air.AndroidInputManager", false, loader);
        deviceType = Class.forName("com.adobe.air.AndroidInputDevice", false, loader);
        controlType = Class.forName("com.adobe.air.AndroidInputControl", false, loader);
        typeEnum = Class.forName("com.adobe.air.ControlType", false, loader);
        managerType.getDeclaredField("sAndroidInputManager"); managerType.getDeclaredField("mInternalReference"); managerType.getDeclaredField("mListening");
        managerType.getDeclaredMethod("OnDeviceAdded", long.class, deviceType, String.class);
        deviceType.getDeclaredField("mInputControls"); deviceType.getDeclaredField("mNumControls");
        deviceType.getDeclaredConstructor(InputDevice.class);
    }

    private static Object field(Object target, Class<?> type, String name) throws ReflectiveOperationException {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }

    /** Registers the virtual device once AIR's GameInput is active. */
    @SuppressWarnings("unchecked")
    private boolean ensureDevice() {
        if (device != null) return true;
        long now = SystemClock.uptimeMillis();
        if (now - lastAttempt < 500) return false;
        lastAttempt = now;
        try {
            Object manager = field(null, managerType, "sAndroidInputManager");
            if (manager == null) return false;
            long reference = (Long) field(manager, managerType, "mInternalReference");
            if (reference == 0 || !(Boolean) field(manager, managerType, "mListening")) return false;
            InputDevice base = InputDevice.getDevice(KeyCharacterMap.VIRTUAL_KEYBOARD);
            if (base == null) for (int id : InputDevice.getDeviceIds()) { base = InputDevice.getDevice(id); if (base != null) break; }
            if (base == null) return false;
            Constructor<?> make = deviceType.getDeclaredConstructor(InputDevice.class); make.setAccessible(true);   // AIR's classes are package-private
            Object created = make.newInstance(base);
            List<Object> controls = (List<Object>) field(created, deviceType, "mInputControls");
            Object axisKind = null;
            for (Object constant : typeEnum.getEnumConstants()) if ("AXIS".equals(((Enum<?>) constant).name())) axisKind = constant;
            if (axisKind == null) return false;
            Constructor<?> control = controlType.getDeclaredConstructor(typeEnum, int.class, float.class, float.class);
            control.setAccessible(true);
            for (int axis : AXES) controls.add(control.newInstance(axisKind, axis, -1f, 1f));
            Field count = deviceType.getDeclaredField("mNumControls"); count.setAccessible(true); count.setInt(created, controls.size());
            Method added = managerType.getDeclaredMethod("OnDeviceAdded", long.class, deviceType, String.class); added.setAccessible(true);
            added.invoke(manager, reference, created, "PocketMicro touch pad");
            device = created;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            android.util.Log.w("PocketMicroControls", "AIR GameInput device not registered yet", e);
            return false;
        }
    }

    @Override public void key(int key, int action) {
        if (keys != null) keys.key(key, action);
        if (!ensureDevice()) return;
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, action == 0 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, key, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_GAMEPAD);
        invoke("onKeyEvent", KeyEvent.class, event);
    }

    @Override public void axis(int axis, float value) {
        if (axis == 0) x = value; else if (axis == 1) y = value; else if (axis == 11) z = value; else if (axis == 14) rz = value; else return;
        if (!ensureDevice()) return;
        MotionEvent.PointerProperties property = new MotionEvent.PointerProperties(); property.id = 0; property.toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.setAxisValue(MotionEvent.AXIS_X, x); coords.setAxisValue(MotionEvent.AXIS_Y, y);
        coords.setAxisValue(MotionEvent.AXIS_Z, z); coords.setAxisValue(MotionEvent.AXIS_RZ, rz);
        coords.setAxisValue(MotionEvent.AXIS_HAT_X, x); coords.setAxisValue(MotionEvent.AXIS_HAT_Y, y);
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, new MotionEvent.PointerProperties[]{property}, new MotionEvent.PointerCoords[]{coords},
                0, 0, 1, 1, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_JOYSTICK, 0);
        try { invoke("onGenericMotionEvent", MotionEvent.class, event); } finally { event.recycle(); }
    }

    private void invoke(String name, Class<?> type, Object event) {
        try { Method m = deviceType.getDeclaredMethod(name, type); m.setAccessible(true); m.invoke(device, event); }
        catch (ReflectiveOperationException | LinkageError e) { throw new IllegalStateException("AIR GameInput call failed", e); }
    }
}
