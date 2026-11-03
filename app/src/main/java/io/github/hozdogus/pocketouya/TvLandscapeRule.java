package io.github.hozdogus.pocketouya;

import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ouya.tv-layouts.landscape: companion to {@link TvLayoutRule}. That rule makes the OUYA's
 * large-landscape (TV) resources apply to landscape phones, but a game that never declared an
 * orientation (the OUYA was always a landscape TV) opens in portrait on a phone, still gets the
 * default resources and crashes the same way (Word Shaker: WordShakerOuyaActivity.j() calls
 * setImageResource on an ImageView that only exists in layout-large-land/main.xml).
 *
 * In the binary AndroidManifest.xml, every &lt;activity&gt; without its own
 * android:screenOrientation gets screenOrientation="sensorLandscape". Activities that already
 * declare an orientation keep it. Only used when the TV-layout rule changed resources.
 */
public final class TvLandscapeRule {
    public static final String ID = "ouya.tv-layouts.landscape";
    static final int XML = 0x0003, POOL = 0x0001, RES_MAP = 0x0180;
    static final int NS_START = 0x0100, NS_END = 0x0101, START = 0x0102, END = 0x0103, CDATA = 0x0104;
    static final int ATTR_SCREEN_ORIENTATION = 0x0101001e, SENSOR_LANDSCAPE = 6, TYPE_STRING = 0x03, TYPE_INT_DEC = 0x10;
    static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "no activities";
        public byte[] manifest;
    }

    private TvLandscapeRule() {}
    public static Result inspect(byte[] axml) { return run(axml, false); }
    public static Result repair(byte[] axml) { return run(axml, true); }

    private static Result run(byte[] axml, boolean apply) {
        Result r = new Result();
        try {
            ByteBuffer b = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
            if (axml.length < 8 || b.getShort(0) != XML || b.getInt(4) != axml.length) throw new IllegalStateException("manifest header not recognised");
            int headerSize = b.getShort(2) & 0xffff;
            int poolAt = -1, mapAt = -1; List<Integer> nodes = new ArrayList<>();
            for (int at = headerSize; at < axml.length; ) {
                if (at + 8 > axml.length) throw new IllegalStateException("truncated chunk at " + at);
                int type = b.getShort(at) & 0xffff, size = b.getInt(at + 4);
                if (size < 8 || at + size > axml.length) throw new IllegalStateException("bad chunk at " + at);
                if (type == POOL && poolAt < 0) poolAt = at;
                else if (type == RES_MAP && mapAt < 0) mapAt = at;
                else if (type >= NS_START && type <= CDATA) nodes.add(at);
                else throw new IllegalStateException("unsupported manifest chunk 0x" + Integer.toHexString(type));
                at += size;
            }
            if (poolAt < 0) throw new IllegalStateException("no string pool");
            List<String> strings = decodePool(b, poolAt);
            boolean utf8 = (b.getInt(poolAt + 16) & 0x100) != 0;
            int[] ids = new int[0];
            if (mapAt >= 0) { ids = new int[(b.getInt(mapAt + 4) - 8) / 4]; for (int i = 0; i < ids.length; i++) ids[i] = b.getInt(mapAt + 8 + 4 * i); }

            int attrName = -1;
            for (int i = 0; i < ids.length && i < strings.size(); i++) if (ids[i] == ATTR_SCREEN_ORIENTATION) { attrName = i; break; }
            int ns = strings.indexOf(ANDROID_NS);
            if (ns < 0) throw new IllegalStateException("no android namespace");

            // Activities that need the attribute: start-element offset -> insertion slot.
            Map<Integer, Integer> targets = new LinkedHashMap<>(); int kept = 0;
            for (int at : nodes) {
                if ((b.getShort(at) & 0xffff) != START || !"activity".equals(get(strings, b.getInt(at + 20)))) continue;
                int attrStart = b.getShort(at + 24) & 0xffff, attrSize = b.getShort(at + 26) & 0xffff, count = b.getShort(at + 28) & 0xffff;
                if (attrSize != 20) throw new IllegalStateException("unexpected attribute size " + attrSize);
                boolean has = false; int slot = count;
                for (int i = 0; i < count; i++) {
                    int a = at + 16 + attrStart + 20 * i, name = b.getInt(a + 4);
                    int id = name >= 0 && name < ids.length ? ids[name] : 0;
                    if (id == ATTR_SCREEN_ORIENTATION) has = true;
                    // aapt keeps resource attributes sorted by ID, unresolved names after them.
                    if (slot == count && (id == 0 || (id & 0xffffffffL) > ATTR_SCREEN_ORIENTATION)) slot = i;
                }
                if (has) kept++; else targets.put(at, slot);
            }
            if (targets.isEmpty()) {
                r.reason = kept > 0 ? "all " + kept + " activities already set their own orientation; unchanged" : "no activities";
                return r;
            }
            r.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
            r.reason = targets.size() + " activit" + (targets.size() == 1 ? "y" : "ies") + " now open in landscape (sensorLandscape), as on the OUYA's TV, so the TV screens apply"
                    + (kept > 0 ? "; " + kept + " kept their own orientation" : "") + ".";
            if (!apply) return r;

            // A new attribute-name string must sit inside the resource-mapped prefix of the pool,
            // so it goes at index ids.length and every later string reference shifts by one.
            final int insert = attrName >= 0 ? -1 : ids.length;
            if (attrName < 0) {
                if (strings.size() < ids.length) throw new IllegalStateException("resource map longer than string pool");
                if (b.getInt(poolAt + 12) != 0) throw new IllegalStateException("styled manifest string pool");
                strings.add(insert, "screenOrientation");
                ids = Arrays.copyOf(ids, ids.length + 1); ids[insert] = ATTR_SCREEN_ORIENTATION;
                attrName = insert; if (ns >= insert) ns++;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(axml, 0, headerSize);
            if (insert >= 0) out.write(encodePool(strings, utf8)); else out.write(axml, poolAt, b.getInt(poolAt + 4));
            ByteBuffer map = ByteBuffer.allocate(8 + 4 * ids.length).order(ByteOrder.LITTLE_ENDIAN);
            map.putShort((short) RES_MAP).putShort((short) 8).putInt(8 + 4 * ids.length); for (int id : ids) map.putInt(id);
            out.write(map.array());
            for (int at : nodes) {
                int size = b.getInt(at + 4); Integer slot = targets.get(at);
                byte[] node = Arrays.copyOfRange(axml, at, at + size);
                ByteBuffer n = ByteBuffer.wrap(node).order(ByteOrder.LITTLE_ENDIAN);
                int type = n.getShort(0) & 0xffff;
                shift(n, 12, insert);
                if (type == NS_START || type == NS_END || type == START || type == END) { shift(n, 16, insert); shift(n, 20, insert); }
                if (type == CDATA) { shift(n, 16, insert); if ((node[23] & 0xff) == TYPE_STRING) shift(n, 24, insert); }
                if (type == START) {
                    int attrStart = n.getShort(24) & 0xffff, count = n.getShort(28) & 0xffff;
                    for (int i = 0; i < count; i++) {
                        int a = 16 + attrStart + 20 * i;
                        shift(n, a, insert); shift(n, a + 4, insert); shift(n, a + 8, insert);
                        if ((node[a + 15] & 0xff) == TYPE_STRING) shift(n, a + 16, insert);
                    }
                    if (slot != null) {
                        int cut = 16 + attrStart + 20 * slot;
                        ByteBuffer attr = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
                        attr.putInt(ns).putInt(attrName).putInt(-1).putShort((short) 8).put((byte) 0).put((byte) TYPE_INT_DEC).putInt(SENSOR_LANDSCAPE);
                        byte[] grown = new byte[size + 20];
                        System.arraycopy(node, 0, grown, 0, cut); System.arraycopy(attr.array(), 0, grown, cut, 20); System.arraycopy(node, cut, grown, cut + 20, size - cut);
                        ByteBuffer g = ByteBuffer.wrap(grown).order(ByteOrder.LITTLE_ENDIAN);
                        g.putInt(4, size + 20).putShort(28, (short) (count + 1));
                        for (int f = 30; f <= 34; f += 2) { int idx = g.getShort(f) & 0xffff; if (idx > slot) g.putShort(f, (short) (idx + 1)); }
                        node = grown;
                    }
                }
                out.write(node);
            }
            byte[] result = out.toByteArray();
            ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.length);
            r.manifest = result;
        } catch (Exception e) {
            r.manifest = null; r.status = CompatibilityEvidence.Status.UNKNOWN; r.reason = e.getMessage() + "; original manifest retained";
        }
        return r;
    }

    private static void shift(ByteBuffer b, int at, int insert) {
        if (insert < 0) return; int v = b.getInt(at); if (v >= insert) b.putInt(at, v + 1);
    }
    private static String get(List<String> s, int i) { return i >= 0 && i < s.size() ? s.get(i) : ""; }

    static List<String> decodePool(ByteBuffer b, int pool) {
        int header = b.getShort(pool + 2) & 0xffff, size = b.getInt(pool + 4), count = b.getInt(pool + 8), flags = b.getInt(pool + 16), start = b.getInt(pool + 20);
        if (count < 0 || count > 100000 || header + 4L * count > size) throw new IllegalStateException("bad string pool");
        boolean utf8 = (flags & 0x100) != 0; List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int at = pool + start + b.getInt(pool + header + 4 * i);
            if (at < pool || at >= pool + size) throw new IllegalStateException("bad string offset");
            if (utf8) {
                at += (b.get(at) & 0x80) != 0 ? 2 : 1;
                int len = b.get(at) & 0xff; if ((len & 0x80) != 0) { len = ((len & 0x7f) << 8) | (b.get(at + 1) & 0xff); at += 2; } else at++;
                if (at + len > pool + size) throw new IllegalStateException("bad string length");
                byte[] s = new byte[len]; for (int k = 0; k < len; k++) s[k] = b.get(at + k);
                out.add(new String(s, StandardCharsets.UTF_8));
            } else {
                int len = b.getShort(at) & 0xffff; at += 2;
                if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | (b.getShort(at) & 0xffff); at += 2; }
                if (at + 2L * len > pool + size) throw new IllegalStateException("bad string length");
                char[] s = new char[len]; for (int k = 0; k < len; k++) s[k] = b.getChar(at + 2 * k);
                out.add(new String(s));
            }
        }
        return out;
    }

    static byte[] encodePool(List<String> strings, boolean utf8) {
        ByteArrayOutputStream data = new ByteArrayOutputStream(); int[] offsets = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offsets[i] = data.size(); String s = strings.get(i);
            if (utf8) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                if (s.length() > 0x7fff || bytes.length > 0x7fff) throw new IllegalStateException("string too long");
                length8(data, s.length()); length8(data, bytes.length); data.write(bytes, 0, bytes.length); data.write(0);
            } else {
                if (s.length() > 0x7fff) throw new IllegalStateException("string too long");
                data.write(s.length() & 0xff); data.write(s.length() >> 8);
                for (char c : s.toCharArray()) { data.write(c & 0xff); data.write(c >> 8); }
                data.write(0); data.write(0);
            }
        }
        while (data.size() % 4 != 0) data.write(0);
        int header = 28, start = header + 4 * strings.size();
        ByteBuffer out = ByteBuffer.allocate(start + data.size()).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) POOL).putShort((short) header).putInt(start + data.size()).putInt(strings.size()).putInt(0)
                .putInt(utf8 ? 0x100 : 0).putInt(start).putInt(0);
        for (int o : offsets) out.putInt(o);
        out.put(data.toByteArray());
        return out.array();
    }
    private static void length8(ByteArrayOutputStream out, int len) {
        if (len > 0x7f) { out.write(0x80 | (len >> 8)); out.write(len & 0xff); } else out.write(len);
    }
}
