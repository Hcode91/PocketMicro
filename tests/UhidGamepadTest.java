package com.hcode91.pocketmicro;

import java.nio.*;
import java.nio.charset.StandardCharsets;

public final class UhidGamepadTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static ByteBuffer le(byte[] b) { return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); }

    /** Sums input report bits by walking the HID short items (Report Size x Report Count per Input item). */
    static int inputBits(byte[] d) {
        int size = 0, count = 0, bits = 0;
        for (int i = 0; i < d.length; ) {
            int prefix = d[i] & 0xff, len = prefix & 3; if (len == 3) len = 4;
            int tag = prefix & 0xfc, value = 0;
            for (int k = 0; k < len; k++) value |= (d[i + 1 + k] & 0xff) << (8 * k);
            if (tag == 0x74) size = value; else if (tag == 0x94) count = value; else if (tag == 0x80) bits += size * count;
            i += 1 + len;
        }
        return bits;
    }

    public static void main(String[] args) {
        // struct uhid_event { u32 type; uhid_create2_req { name[128] phys[64] uniq[64] u16 rd_size u16 bus u32 vendor product version country; rd_data[4096] } }
        byte[] c = UhidGamepad.create(UhidGamepad.NAME);
        check(c.length == 4376, "create2 event is the full packed struct: " + c.length);
        ByteBuffer b = le(c);
        check(b.getInt(0) == 11, "UHID_CREATE2");
        check(new String(c, 4, UhidGamepad.NAME.length(), StandardCharsets.UTF_8).equals("OUYA Game Controller") && c[4 + UhidGamepad.NAME.length()] == 0, "NUL-terminated name at offset 4");
        check((b.getShort(260) & 0xffff) == UhidGamepad.DESCRIPTOR.length, "rd_size at 260");
        check(b.getShort(262) == 3 && b.getInt(264) == 0x1209 && b.getInt(268) == 0x0f0a, "bus/vendor/product");
        for (int i = 0; i < UhidGamepad.DESCRIPTOR.length; i++) check(c[280 + i] == UhidGamepad.DESCRIPTOR[i], "descriptor copied at 280");
        check(inputBits(UhidGamepad.DESCRIPTOR) == UhidGamepad.REPORT_SIZE * 8, "descriptor describes exactly the 9-byte report (" + inputBits(UhidGamepad.DESCRIPTOR) + " bits)");

        // struct uhid_input2_req { u16 size; u8 data[] }
        byte[] in = UhidGamepad.input(0x8001, UhidGamepad.KEY_UP | UhidGamepad.KEY_MENU, -1f, 1f, 0f, 0.5f, 0f, 1f);
        ByteBuffer ib = le(in);
        check(ib.getInt(0) == 12 && ib.getShort(4) == 9 && in.length == 6 + 9, "UHID_INPUT2 with 9-byte report");
        check(in[6] == 1 && (in[7] & 0xff) == 0x80, "buttons little-endian bitfield");
        check((in[8] & 0xff) == 0 && (in[9] & 0xff) == 255 && (in[10] & 0xff) == 128 && (in[11] & 0xff) == 191, "sticks scaled to 0..255, centre 128");
        check((in[12] & 0xff) == 0 && (in[13] & 0xff) == 255, "triggers 0..255");
        check(in[14] == (8 | 16), "d-pad/menu key bits in the last byte");
        check(UhidGamepad.input(0, 0xff, 0, 0, 0, 0, 0, 0)[14] == 0x1f, "only the five key bits are sent");
        check((UhidGamepad.input(0, 0, 5f, -5f, 0, 0, -1f, 3f)[8] & 0xff) == 255 && UhidGamepad.input(0, 0, 5f, -5f, 0, 0, -1f, 3f)[9] == 0, "values clamped");

        check(le(UhidGamepad.destroy()).getInt(0) == 1, "UHID_DESTROY");
        ByteBuffer g = le(UhidGamepad.getReportReply(77));
        check(g.getInt(0) == 10 && g.getInt(4) == 77 && g.getShort(8) == 5, "GET_REPORT_REPLY refuses with EIO for the same id");
        ByteBuffer s = le(UhidGamepad.setReportReply(78));
        check(s.getInt(0) == 14 && s.getInt(4) == 78 && s.getShort(8) == 0, "SET_REPORT_REPLY acknowledges");
        System.out.println("UhidGamepadTest OK");
    }
}
