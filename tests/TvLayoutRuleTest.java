package io.github.hozdogus.pocketouya;

import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class TvLayoutRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    static byte[] le(int size) { return new byte[size]; }
    static ByteBuffer buf(byte[] b) { return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); }

    /** UTF-16 string pool. */
    static byte[] pool(String... strings) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offs = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            offs[i] = data.size(); String s = strings[i];
            data.write(s.length() & 0xff); data.write(s.length() >> 8);
            for (char c : s.toCharArray()) { data.write(c & 0xff); data.write(c >> 8); }
            data.write(0); data.write(0);
        }
        while (data.size() % 4 != 0) data.write(0);
        int header = 28, start = header + 4 * strings.length;
        byte[] b = le(start + data.size()); ByteBuffer w = buf(b);
        w.putShort(0, (short) 1).putShort(2, (short) header).putInt(4, b.length).putInt(8, strings.length).putInt(12, 0).putInt(16, 0).putInt(20, start).putInt(24, 0);
        for (int i = 0; i < strings.length; i++) w.putInt(header + 4 * i, offs[i]);
        System.arraycopy(data.toByteArray(), 0, b, start, data.size());
        return b;
    }

    /** Type chunk with one entry and a 56-byte config: orientation, screenLayout size, sdk. */
    static byte[] type(int id, int orientation, int size, int sdk) {
        int cfg = 56, header = 20 + cfg, entries = header + 4;
        byte[] b = le(entries + 8); ByteBuffer w = buf(b);
        w.putShort(0, (short) 0x0201).putShort(2, (short) header).putInt(4, b.length);
        b[8] = (byte) id; w.putInt(12, 1).putInt(16, entries).putInt(20, cfg);
        b[20 + 12] = (byte) orientation; w.putShort(20 + 24, (short) sdk); b[20 + 28] = (byte) size;
        w.putInt(header, 0); w.putShort(entries, (short) 8).putShort(entries + 2, (short) 0).putInt(entries + 4, 0);
        return b;
    }

    static byte[] table(byte[]... types) {
        byte[] typeStrings = pool("drawable", "layout"), keyStrings = pool("main");
        int header = 288; ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(typeStrings); body.writeBytes(keyStrings); for (byte[] t : types) body.writeBytes(t);
        byte[] pkg = le(header + body.size()); ByteBuffer w = buf(pkg);
        w.putShort(0, (short) 0x0200).putShort(2, (short) header).putInt(4, pkg.length).putInt(8, 0x7f);
        w.putInt(268, header).putInt(272, 2).putInt(276, header + typeStrings.length).putInt(280, 1);
        System.arraycopy(body.toByteArray(), 0, pkg, header, body.size());
        byte[] global = pool();
        byte[] t = le(12 + global.length + pkg.length); ByteBuffer tw = buf(t);
        tw.putShort(0, (short) 2).putShort(2, (short) 12).putInt(4, t.length).putInt(8, 1);
        System.arraycopy(global, 0, t, 12, global.length); System.arraycopy(pkg, 0, t, 12 + global.length, pkg.length);
        return t;
    }

    static int sizeBitsOfTypeChunks(byte[] arsc, int typeId, int orientation) {
        // Return the screen-size bits of the first type chunk matching typeId and orientation.
        for (int i = 0; i + 80 < arsc.length; i++)
            if (arsc[i] == 0x01 && arsc[i + 1] == 0x02 && (arsc[i + 8] & 0xff) == typeId && arsc[i + 20 + 12] == orientation) return arsc[i + 20 + 28] & 0x0f;
        return -1;
    }

    public static void main(String[] args) throws Exception {
        // layout: default + large-land(v13) + large-tvdpi-like (orientation any). drawable: default + land + large-land.
        byte[] arsc = table(type(2, 0, 0, 0), type(2, 2, 3, 13), type(1, 0, 0, 0), type(1, 2, 0, 0), type(1, 2, 3, 4));
        var r = TvLayoutRule.repair(arsc);
        check(r.status == CompatibilityEvidence.Status.VERIFIED, "TV layouts repaired: " + r.reason);
        check(r.reason.contains("[layout]") && r.reason.contains("1 kept"), "layout opened, drawable with own land kept: " + r.reason);
        check(r.replacement.length == arsc.length, "same size");
        check(sizeBitsOfTypeChunks(r.replacement, 2, 2) == 0, "layout-large-land now plain land");
        int diff = 0; for (int i = 0; i < arsc.length; i++) if (arsc[i] != r.replacement[i]) diff++;
        check(diff == 1, "only the size bits changed: " + diff);
        check(TvLayoutRule.repair(r.replacement).status == CompatibilityEvidence.Status.SKIPPED, "idempotent");
        check(TvLayoutRule.repair(table(type(2, 0, 0, 0), type(2, 2, 0, 0))).status == CompatibilityEvidence.Status.SKIPPED, "no TV resources: skipped");
        check(TvLayoutRule.repair(new byte[]{1, 2, 3}).status == CompatibilityEvidence.Status.UNSUPPORTED, "malformed table left alone");
        System.out.println("TvLayoutRuleTest: PASS");
    }
}
