package io.github.hcode91.pocketouya.bridge;

/**
 * Touch pad state for PocketOuya's Shizuku virtual controller, laid out as a real OUYA pad:
 * O = BUTTON_A, A = BUTTON_B, U = BUTTON_X, Y = BUTTON_Y (Linux BTN_A + n bits), L1/R1,
 * Select/Start, d-pad and Menu as keys, left stick X/Y and right (look) stick Z/RZ.
 * Pure Java: no Android types, so the mapping is tested on the host.
 */
public final class VirtualPadState {
    static final int KEY_RIGHT = 1, KEY_LEFT = 2, KEY_DOWN = 4, KEY_UP = 8, KEY_MENU = 16; // UhidGamepad.KEY_*
    public int buttons, keys;
    public float lx, ly, rx, ry, lt, rt;

    /** HID button bit for an Android key code, or -1. Bits follow Linux BTN_A + n: A B C X Y Z TL TR TL2 TR2 SELECT START MODE THUMBL THUMBR. */
    static int buttonBit(int key) {
        switch (key) {
            case VirtualPad.O: return 0;      // BUTTON_A
            case VirtualPad.A: return 1;      // BUTTON_B
            case VirtualPad.U: return 3;      // BUTTON_X
            case VirtualPad.Y: return 4;      // BUTTON_Y
            case VirtualPad.L1: return 6;     // BUTTON_L1
            case VirtualPad.R1: return 7;     // BUTTON_R1
            case VirtualPad.SELECT: return 10;
            case VirtualPad.START: return 11;
            default: return -1;
        }
    }

    static int keyBit(int key) {
        switch (key) {
            case VirtualPad.RIGHT: return KEY_RIGHT;
            case VirtualPad.LEFT: return KEY_LEFT;
            case VirtualPad.DOWN: return KEY_DOWN;
            case VirtualPad.UP: return KEY_UP;
            case VirtualPad.MENU: return KEY_MENU;
            default: return 0;
        }
    }

    /** @param action 0 = down, 1 = up. Returns false for keys the controller does not have. */
    public boolean key(int key, int action) {
        int bit = buttonBit(key), k = keyBit(key);
        if (bit < 0 && k == 0) return false;
        boolean down = action == 0;
        if (bit >= 0) buttons = down ? buttons | (1 << bit) : buttons & ~(1 << bit);
        if (k != 0) keys = down ? keys | k : keys & ~k;
        return true;
    }

    /** VirtualPad axes: 0 X, 1 Y (left stick, also driven by the d-pad), 11 Z, 14 RZ (look stick). */
    public boolean axis(int axis, float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return false;
        float v = Math.max(-1f, Math.min(1f, value));
        switch (axis) {
            case 0: lx = v; return true;
            case 1: ly = v; return true;
            case 11: rx = v; return true;
            case 14: ry = v; return true;
            default: return false;
        }
    }
}
