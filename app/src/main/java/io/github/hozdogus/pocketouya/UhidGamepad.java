package io.github.hozdogus.pocketouya;

import java.nio.*;
import java.nio.charset.StandardCharsets;

/**
 * Linux /dev/uhid messages for PocketOuya's virtual gamepad (struct uhid_event, packed,
 * little-endian). The device describes itself as a standard HID game pad: 16 buttons,
 * two sticks (X/Y, Z/Rz), two analog triggers (Simulation Brake/Accelerator, which Android
 * reports as BRAKE/GAS and LTRIGGER/RTRIGGER) and a hat switch (HAT_X/HAT_Y). Pure Java so the
 * byte layout is tested on the host; only VirtualPadService touches the device node.
 */
public final class UhidGamepad {
    static final int DESTROY = 1, START = 2, STOP = 3, OPEN = 4, CLOSE = 5, OUTPUT = 6, GET_REPORT = 9, GET_REPORT_REPLY = 10, CREATE2 = 11, INPUT2 = 12, SET_REPORT = 13, SET_REPORT_REPLY = 14;
    static final int EVENT_SIZE = 4 + 128 + 64 + 64 + 2 + 2 + 4 * 4 + 4096; // create2 is the largest union member
    static final int BUS_USB = 0x03, VENDOR = 0x1209, PRODUCT = 0x0f0a; // pid.codes test range; the name is what games see
    public static final String NAME = "OUYA Game Controller";
    static final int REPORT_SIZE = 9; // 2 button bytes, 4 stick bytes, 2 trigger bytes, hat nibble + padding

    static final byte[] DESCRIPTOR = bytes(
            0x05, 0x01, 0x09, 0x05, 0xA1, 0x01,                    // Generic Desktop / Game Pad / Application
            0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x10, 0x81, 0x02, // 16 buttons
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02, // X Y Z Rz
            0x05, 0x02, 0x09, 0xC5, 0x09, 0xC4, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02, // Brake, Accelerator
            0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42, // hat, null state
            0x65, 0x00, 0x75, 0x04, 0x95, 0x01, 0x81, 0x03,         // padding
            0xC0);

    private UhidGamepad() {}

    static byte[] bytes(int... v) { byte[] b = new byte[v.length]; for (int i = 0; i < v.length; i++) b[i] = (byte) v[i]; return b; }

    static ByteBuffer event(int type, int size) { ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); b.putInt(0, type); return b; }

    public static byte[] create(String name) {
        ByteBuffer b = event(CREATE2, EVENT_SIZE);
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        if (n.length >= 128) throw new IllegalArgumentException("name too long");
        b.position(4); b.put(n);
        b.position(132); b.put("pocketouya/virtual-pad".getBytes(StandardCharsets.US_ASCII));
        b.position(196); b.put("pocketouya-1".getBytes(StandardCharsets.US_ASCII));
        b.putShort(260, (short) DESCRIPTOR.length).putShort(262, (short) BUS_USB).putInt(264, VENDOR).putInt(268, PRODUCT).putInt(272, 1).putInt(276, 0);
        b.position(280); b.put(DESCRIPTOR);
        return b.array();
    }

    public static byte[] destroy() { return event(DESTROY, 4).array(); }

    /**
     * @param buttons bit n = HID button n+1 (Linux BTN_A + n: A, B, C, X, Y, Z, TL, TR, TL2, TR2, SELECT, START, MODE, THUMBL, THUMBR)
     * @param lx      sticks and triggers as -1..1 (triggers 0..1)
     * @param hat     -1 centred, else 0 = up, clockwise in eighths
     */
    public static byte[] input(int buttons, float lx, float ly, float rx, float ry, float lt, float rt, int hat) {
        ByteBuffer b = event(INPUT2, 4 + 2 + REPORT_SIZE);
        b.putShort(4, (short) REPORT_SIZE);
        b.put(6, (byte) buttons).put(7, (byte) (buttons >> 8));
        b.put(8, stick(lx)).put(9, stick(ly)).put(10, stick(rx)).put(11, stick(ry));
        b.put(12, trigger(lt)).put(13, trigger(rt));
        b.put(14, (byte) (hat < 0 || hat > 7 ? 0x08 : hat)); // outside 0..7 = null state (centred)
        return b.array();
    }

    /** Reply refusing a GET_REPORT request (the pad has no feature reports), so the kernel does not wait. */
    public static byte[] getReportReply(int id) {
        ByteBuffer b = event(GET_REPORT_REPLY, 4 + 4 + 2 + 2);
        b.putInt(4, id).putShort(8, (short) 5 /* EIO */).putShort(10, (short) 0);
        return b.array();
    }

    /** Reply acknowledging a SET_REPORT request without applying it. */
    public static byte[] setReportReply(int id) {
        ByteBuffer b = event(SET_REPORT_REPLY, 4 + 4 + 2);
        b.putInt(4, id).putShort(8, (short) 0);
        return b.array();
    }

    static byte stick(float v) { float c = Math.max(-1f, Math.min(1f, v)); return (byte) Math.round((c + 1f) * 127.5f); }
    static byte trigger(float v) { float c = Math.max(0f, Math.min(1f, v)); return (byte) Math.round(c * 255f); }

    /** Kernel → PocketOuya events: type, and request id for GET/SET_REPORT. */
    static int type(byte[] e, int length) { return length >= 4 ? ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN).getInt(0) : -1; }
    static int requestId(byte[] e) { return ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN).getInt(4); }
}
