package android.view;
public final class InputDevice {
 public static final int SOURCE_GAMEPAD=0x401,SOURCE_KEYBOARD=0x101;
 public static InputDevice getDevice(int id){throw new AssertionError("Physical device lookup must not be used");}
}
