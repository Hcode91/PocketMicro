package com.hcode91.pocketmicro.bridge;

/**
 * Touch pad state for PocketMicro's Shizuku virtual controller. Two layouts, because stock
 * Android has no OUYA key layout file (the virtual pad always gets Generic.kl):
 *
 * - OUYA scan order, for Unity games: Unity 4 numbers joystick buttons by Linux scan code, and
 *   OUYA-era Unity scripts (Soul Fjord's OuyaInput) were built on a real OUYA pad, whose raw
 *   buttons are 0x130 O, 0x131 U, 0x132 Y, 0x133 A, 0x134 L1, 0x135 R1, 0x136 L3, 0x137 R3,
 *   0x138-0x13b d-pad up/down/left/right, 0x13c L2, 0x13d R2, 0x13e Home/Menu (the OUYA's own
 *   Vendor_2836_Product_0001.kl). Bit n of the report is scan code 0x130 + n.
 * - Android key order, for other engines that read key codes: O = BUTTON_A, A = BUTTON_B,
 *   U = BUTTON_X, Y = BUTTON_Y (Generic.kl), d-pad as DPAD_* keys.
 *
 * L2/R2 set their button and the analogue trigger axis (Brake/Accelerator) together.
 * Menu is also sent as the MENU key in both layouts (the OUYA delivered its menu press to games
 * as KEYCODE_MENU). Left stick X/Y, right (look) stick Z/RZ. Pure Java, tested on the host.
 */
public final class VirtualPadState {
    static final int KEY_RIGHT = 1, KEY_LEFT = 2, KEY_DOWN = 4, KEY_UP = 8, KEY_MENU = 16; // UhidGamepad.KEY_*
    private final boolean ouyaScanOrder;
    public int buttons, keys;
    public float lx, ly, rx, ry, lt, rt;

    public VirtualPadState(boolean ouyaScanOrder) { this.ouyaScanOrder = ouyaScanOrder; }
    public VirtualPadState() { this(false); }
    public boolean ouyaScanOrder() { return ouyaScanOrder; }

    /** Report bit (scan code 0x130 + bit) for an Android key code, or -1. */
    int buttonBit(int key) {
        if (ouyaScanOrder) switch (key) {
            case VirtualPad.O: return 0;
            case VirtualPad.U: return 1;
            case VirtualPad.Y: return 2;
            case VirtualPad.A: return 3;
            case VirtualPad.L1: return 4;
            case VirtualPad.R1: return 5;
            case VirtualPad.UP: return 8;
            case VirtualPad.DOWN: return 9;
            case VirtualPad.LEFT: return 10;
            case VirtualPad.RIGHT: return 11;
            case VirtualPad.L2: return 12;
            case VirtualPad.R2: return 13;
            case VirtualPad.MENU: case VirtualPad.START: return 14; // OUYA Home/Menu button
            case VirtualPad.SELECT: return 15;
            default: return -1;
        }
        switch (key) {
            case VirtualPad.O: return 0;      // BTN_A -> BUTTON_A
            case VirtualPad.A: return 1;      // BTN_B -> BUTTON_B
            case VirtualPad.U: return 3;      // BTN_X -> BUTTON_X
            case VirtualPad.Y: return 4;      // BTN_Y -> BUTTON_Y
            case VirtualPad.L1: return 6;     // BTN_TL -> BUTTON_L1
            case VirtualPad.R1: return 7;     // BTN_TR -> BUTTON_R1
            case VirtualPad.L2: return 8;     // BTN_TL2 -> BUTTON_L2
            case VirtualPad.R2: return 9;     // BTN_TR2 -> BUTTON_R2
            case VirtualPad.SELECT: return 10;
            case VirtualPad.START: return 11;
            default: return -1;
        }
    }

    int keyBit(int key) {
        if (key == VirtualPad.MENU) return KEY_MENU;
        if (ouyaScanOrder) return 0; // d-pad is buttons 8-11 in OUYA order
        switch (key) {
            case VirtualPad.RIGHT: return KEY_RIGHT;
            case VirtualPad.LEFT: return KEY_LEFT;
            case VirtualPad.DOWN: return KEY_DOWN;
            case VirtualPad.UP: return KEY_UP;
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
        // L2/R2 are analogue triggers on the OUYA pad: fully pressed while held, as well as the button.
        if (key == VirtualPad.L2) lt = down ? 1f : 0f;
        if (key == VirtualPad.R2) rt = down ? 1f : 0f;
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
