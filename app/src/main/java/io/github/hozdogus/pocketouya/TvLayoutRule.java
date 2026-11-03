package io.github.hozdogus.pocketouya;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ouya.tv-layouts: OUYA games were built for a TV, which Android reports as a large landscape
 * screen, and many keep their real screens only in "-large-land" resources (layout-large-land,
 * values-large-land, ...). A phone is a normal-size screen, so it gets the default resources
 * instead; when those lack views the game expects, it crashes (Word Shaker: findViewById returns
 * null for an ImageView that only exists in layout-large-land/main.xml).
 *
 * In resources.arsc, each resource group whose configuration is "large + landscape" has its
 * screen-size requirement removed, so a phone held in landscape uses the same resources the OUYA
 * did. Only the size bits change. A group is left alone when the game already has a plain
 * landscape version of it (or two large groups would become the same), so games with their own
 * phone landscape layouts are unaffected. Resource files and IDs are unchanged.
 */
public final class TvLayoutRule {
    public static final String ID = "ouya.tv-layouts";
    static final int TABLE = 0x0002, POOL = 0x0001, PACKAGE = 0x0200, TYPE = 0x0201;
    static final int ORIENTATION = 12, SDK = 24, MINOR = 26, SCREEN_LAYOUT = 28, LAND = 2, SIZE_MASK = 0x0f, LARGE = 3;

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "no large-landscape (TV) resources";
        public byte[] replacement;
    }

    private TvLayoutRule() {}
    public static Result inspect(byte[] arsc) { return run(arsc, false); }
    public static Result repair(byte[] arsc) { return run(arsc, true); }

    static final class TypeChunk { int offset, typeId, configSize; byte[] config; }

    /** Configuration with SDK/minor version zeroed (aapt adds -v4/-v13 to size qualifiers) and, optionally, size bits cleared. */
    static String key(int typeId, byte[] config, boolean clearSize) {
        byte[] c = config.clone();
        if (c.length > MINOR + 1) { c[SDK] = c[SDK + 1] = c[MINOR] = c[MINOR + 1] = 0; }
        if (clearSize && c.length > SCREEN_LAYOUT) c[SCREEN_LAYOUT] &= (byte) ~SIZE_MASK;
        return typeId + ":" + Base64.getEncoder().encodeToString(Arrays.copyOfRange(c, 4, c.length));
    }

    static boolean largeLand(byte[] config) {
        return config.length > SCREEN_LAYOUT && (config[SCREEN_LAYOUT] & SIZE_MASK) == LARGE && config[ORIENTATION] == LAND;
    }

    static List<String> typeNames(ByteBuffer b, int pool) {
        List<String> out = new ArrayList<>();
        if (b.getShort(pool) != POOL) return out;
        int header = b.getShort(pool + 2) & 0xffff, count = b.getInt(pool + 8), flags = b.getInt(pool + 16), strings = b.getInt(pool + 20);
        boolean utf8 = (flags & 0x100) != 0;
        if (count < 0 || count > 4096) return out;
        for (int i = 0; i < count; i++) {
            int at = pool + strings + b.getInt(pool + header + 4 * i);
            if (utf8) {
                int u = b.get(at) & 0xff; at += (u & 0x80) != 0 ? 2 : 1;
                int len = b.get(at) & 0xff; if ((len & 0x80) != 0) { len = ((len & 0x7f) << 8) | (b.get(at + 1) & 0xff); at += 2; } else at++;
                byte[] s = new byte[len]; for (int k = 0; k < len; k++) s[k] = b.get(at + k);
                out.add(new String(s, StandardCharsets.UTF_8));
            } else {
                int len = b.getShort(at) & 0xffff; at += 2;
                if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | (b.getShort(at) & 0xffff); at += 2; }
                char[] s = new char[len]; for (int k = 0; k < len; k++) s[k] = b.getChar(at + 2 * k);
                out.add(new String(s));
            }
        }
        return out;
    }

    private static Result run(byte[] arsc, boolean apply) {
        Result r = new Result();
        try {
            ByteBuffer b = ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN);
            if (arsc.length < 12 || b.getShort(0) != TABLE || b.getInt(4) != arsc.length) { r.status = CompatibilityEvidence.Status.UNSUPPORTED; r.reason = "resources.arsc header not recognised; unchanged"; return r; }
            byte[] out = apply ? arsc.clone() : null;
            int changed = 0, kept = 0; Set<String> names = new TreeSet<>();
            int at = b.getShort(2) & 0xffff;
            while (at + 8 <= arsc.length) {
                int type = b.getShort(at) & 0xffff, size = b.getInt(at + 4);
                if (size < 8 || at + size > arsc.length) throw new IllegalStateException("bad chunk at " + at);
                if (type == PACKAGE) {
                    int header = b.getShort(at + 2) & 0xffff;
                    List<String> typeStrings = typeNames(b, at + b.getInt(at + 268));
                    List<TypeChunk> chunks = new ArrayList<>();
                    for (int c = at + header; c + 8 <= at + size; ) {
                        int ct = b.getShort(c) & 0xffff, cs = b.getInt(c + 4);
                        if (cs < 8 || c + cs > at + size) throw new IllegalStateException("bad package chunk at " + c);
                        if (ct == TYPE) {
                            TypeChunk t = new TypeChunk(); t.offset = c; t.typeId = b.get(c + 8) & 0xff; t.configSize = b.getInt(c + 20);
                            if (t.configSize < 32 || c + 20 + t.configSize > c + cs) throw new IllegalStateException("bad config at " + c);
                            t.config = Arrays.copyOfRange(arsc, c + 20, c + 20 + t.configSize); chunks.add(t);
                        }
                        c += cs;
                    }
                    Set<String> existing = new HashSet<>();
                    for (TypeChunk t : chunks) if (!largeLand(t.config)) existing.add(key(t.typeId, t.config, false));
                    Map<String, Integer> targets = new HashMap<>();
                    for (TypeChunk t : chunks) if (largeLand(t.config)) targets.merge(key(t.typeId, t.config, true), 1, Integer::sum);
                    for (TypeChunk t : chunks) {
                        if (!largeLand(t.config)) continue;
                        String k = key(t.typeId, t.config, true);
                        String name = t.typeId - 1 < typeStrings.size() && t.typeId > 0 ? typeStrings.get(t.typeId - 1) : "type" + t.typeId;
                        if (existing.contains(k) || targets.get(k) > 1) { kept++; continue; }
                        names.add(name); changed++;
                        if (apply) out[t.offset + 20 + SCREEN_LAYOUT] &= (byte) ~SIZE_MASK;
                    }
                }
                at += size;
            }
            if (changed == 0) {
                if (kept > 0) r.reason = kept + " large-landscape resource group(s) already have a landscape version; unchanged";
                return r;
            }
            r.replacement = out;
            r.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
            r.reason = changed + " TV (large-landscape) resource group(s) [" + String.join(", ", names) + "] now also apply to landscape phones, so the game gets the screens it had on the OUYA"
                    + (kept > 0 ? "; " + kept + " kept because the game has its own landscape version" : "") + ". Resource files and IDs unchanged.";
        } catch (Exception e) {
            r.replacement = null; r.status = CompatibilityEvidence.Status.UNKNOWN; r.reason = e + "; original resources.arsc retained";
        }
        return r;
    }
}
