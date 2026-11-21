package com.hcode91.pocketmicro;

import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.util.*;

/** Hand-built aapt-style binary manifests: resource-mapped attribute names first, then other strings. */
public final class TvLandscapeRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final int CONFIG = 0x0101001f, NAME = 0x01010003, MIN_SDK = 0x0101020c, ORIENT = 0x0101001e, THEME = 0x01010000, LABEL = 0x01010001;

    static final class Axml {
        final List<String> strings = new ArrayList<>(); final List<Integer> ids = new ArrayList<>();
        final ByteArrayOutputStream nodes = new ByteArrayOutputStream();
        int s(String v) { int i = strings.indexOf(v); if (i < 0) { strings.add(v); i = strings.size() - 1; } return i; }
        int attr(String v, int id) { int i = s(v); while (ids.size() <= i) ids.add(0); ids.set(i, id); return i; }
        void chunk(int type, int... ints) {
            ByteBuffer b = ByteBuffer.allocate(8 + 4 * ints.length).order(ByteOrder.LITTLE_ENDIAN);
            b.putShort((short) type).putShort((short) 16).putInt(8 + 4 * ints.length); for (int v : ints) b.putInt(v);
            nodes.write(b.array(), 0, b.capacity());
        }
        /** attrs: {name, rawValue, type, data}. */
        void start(String tag, int[]... attrs) {
            int ns = s(TvLandscapeRule.ANDROID_NS);
            ByteBuffer b = ByteBuffer.allocate(36 + 20 * attrs.length).order(ByteOrder.LITTLE_ENDIAN);
            b.putShort((short) 0x0102).putShort((short) 16).putInt(36 + 20 * attrs.length).putInt(1).putInt(-1)
                    .putInt(-1).putInt(s(tag)).putShort((short) 20).putShort((short) 20).putShort((short) attrs.length)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0);
            for (int[] a : attrs) b.putInt(a[0] == s("package") ? -1 : ns).putInt(a[0]).putInt(a[1]).putShort((short) 8).put((byte) 0).put((byte) a[2]).putInt(a[3]);
            nodes.write(b.array(), 0, b.capacity());
        }
        void end(String tag) { chunk(0x0103, 1, -1, -1, s(tag)); }
        byte[] build(boolean utf8) {
            byte[] pool = TvLandscapeRule.encodePool(strings, utf8);
            ByteBuffer map = ByteBuffer.allocate(8 + 4 * ids.size()).order(ByteOrder.LITTLE_ENDIAN);
            map.putShort((short) 0x0180).putShort((short) 8).putInt(8 + 4 * ids.size()); for (int id : ids) map.putInt(id);
            int size = 8 + pool.length + map.capacity() + nodes.size();
            ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
            out.putShort((short) 3).putShort((short) 8).putInt(size).put(pool).put(map.array()).put(nodes.toByteArray());
            return out.array();
        }
    }

    static int[] str(Axml x, int name, String v) { int i = x.s(v); return new int[]{name, i, 3, i}; }

    /** Word Shaker-like: splash launcher + main activity, a third-party activity with its own portrait orientation. */
    static byte[] manifest(boolean utf8, boolean orientationStringExists) {
        Axml x = new Axml();
        int theme = x.attr("theme", THEME), label = x.attr("label", LABEL), name = x.attr("name", NAME), min = x.attr("minSdkVersion", MIN_SDK);
        int config = x.attr("configChanges", CONFIG);
        int orient = orientationStringExists ? x.attr("screenOrientation", ORIENT) : -1;
        x.s(TvLandscapeRule.ANDROID_NS); x.s("android"); int pkg = x.s("package");
        x.chunk(0x0100, 1, -1, x.s("android"), x.s(TvLandscapeRule.ANDROID_NS));
        x.start("manifest", str(x, pkg, "com.afksoft.WordShakerOuya"));
        x.start("uses-sdk", new int[]{min, -1, 0x10, 8}); x.end("uses-sdk");
        x.start("application", str(x, label, "Word Shaker"));
        x.start("activity", new int[]{theme, -1, 0x01, 0x7f0b0000}, str(x, label, "Word Shaker"), str(x, name, "com.afksoft.WordShakerBase.ShakerSplashActivity"));
        x.start("intent-filter");
        x.start("action", str(x, name, "android.intent.action.MAIN")); x.end("action");
        x.start("category", str(x, name, "tv.ouya.intent.category.GAME")); x.end("category");
        x.end("intent-filter"); x.end("activity");
        x.start("activity", str(x, name, ".WordShakerOuyaActivity"), new int[]{config, -1, 0x11, 0xa0}); x.end("activity");
        if (orient >= 0) x.start("activity", str(x, name, "com.flurry.Ads"), new int[]{orient, -1, 0x10, 1});
        else x.start("activity", str(x, name, "com.flurry.Ads"));
        x.end("activity");
        x.end("application"); x.end("manifest");
        x.chunk(0x0101, 1, -1, x.s("android"), x.s(TvLandscapeRule.ANDROID_NS));
        return x.build(utf8);
    }

    /** Walks the result: per activity, its attribute (name, resId, data) triples in order. */
    static List<List<String>> activities(byte[] axml) {
        ByteBuffer b = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        check(b.getInt(4) == axml.length, "outer size");
        List<String> strings = null; int[] ids = new int[0]; List<List<String>> out = new ArrayList<>();
        for (int at = 8; at < axml.length; at += b.getInt(at + 4)) {
            int type = b.getShort(at) & 0xffff;
            if (type == 1) strings = TvLandscapeRule.decodePool(b, at);
            if (type == 0x0180) { ids = new int[(b.getInt(at + 4) - 8) / 4]; for (int i = 0; i < ids.length; i++) ids[i] = b.getInt(at + 8 + 4 * i); }
            if (type == 0x0102 && strings.get(b.getInt(at + 20)).equals("activity")) {
                List<String> attrs = new ArrayList<>(); int count = b.getShort(at + 28);
                check(b.getInt(at + 4) == 36 + 20 * count, "element size matches attribute count");
                for (int i = 0; i < count; i++) {
                    int a = at + 36 + 20 * i, n = b.getInt(a + 4);
                    check(strings.get(b.getInt(a)).equals(TvLandscapeRule.ANDROID_NS), "android namespace");
                    int t = axml[a + 15] & 0xff, d = b.getInt(a + 16);
                    attrs.add(strings.get(n) + "#" + Integer.toHexString(ids[n]) + "=" + (t == 3 ? strings.get(d) : String.valueOf(d)));
                }
                out.add(attrs);
            }
        }
        return out;
    }

    static void roundTrip(boolean utf8, boolean orientationStringExists) {
        byte[] in = manifest(utf8, orientationStringExists);
        TvLandscapeRule.Result inspect = TvLandscapeRule.inspect(in);
        check(inspect.status == CompatibilityEvidence.Status.SUPPORTED && inspect.manifest == null, "inspect is read-only: " + inspect.reason);
        TvLandscapeRule.Result r = TvLandscapeRule.repair(in);
        check(r.status == CompatibilityEvidence.Status.VERIFIED, r.reason);
        int expected = orientationStringExists ? 2 : 3;
        check(r.reason.startsWith(expected + " activities"), r.reason);
        List<List<String>> acts = activities(r.manifest);
        String land = "screenOrientation#101001e=6";
        check(acts.get(0).equals(List.of("theme#1010000=" + 0x7f0b0000, "label#1010001=Word Shaker", "name#1010003=com.afksoft.WordShakerBase.ShakerSplashActivity", land)), "splash sorted by id: " + acts.get(0));
        check(acts.get(1).equals(List.of("name#1010003=.WordShakerOuyaActivity", land, "configChanges#101001f=160")), "main: " + acts.get(1));
        if (orientationStringExists) check(acts.get(2).equals(List.of("name#1010003=com.flurry.Ads", "screenOrientation#101001e=1")), "declared orientation kept: " + acts.get(2));
        else check(acts.get(2).equals(List.of("name#1010003=com.flurry.Ads", land)), "third: " + acts.get(2));
        // The existing manifest parser still sees package, target and launcher after the strings shifted.
        ManifestPatcher.Result m = ManifestPatcher.patch(r.manifest);
        check(m.packageName.equals("com.afksoft.WordShakerOuya"), "package " + m.packageName);
        check(m.ouyaGame && m.newTarget == 23, "ouya game, target raised");
        check(m.launcherActivities.equals(Set.of("Lcom/afksoft/WordShakerBase/ShakerSplashActivity;")), "launcher " + m.launcherActivities);
        // Idempotent: a second pass finds nothing left to do.
        check(TvLandscapeRule.repair(r.manifest).status == CompatibilityEvidence.Status.SKIPPED, "second pass unchanged");
    }

    public static void main(String[] args) {
        roundTrip(false, false); roundTrip(true, false); roundTrip(false, true); roundTrip(true, true);
        // android.wide-screen reuses the same writer with maxAspectRatio (0x01010521, float 3.0), after the landscape pass too.
        for (boolean utf8 : new boolean[]{false, true}) {
            byte[] in = manifest(utf8, false);
            TvLandscapeRule.Result wide = WideScreenRule.repair(in);
            check(wide.status == CompatibilityEvidence.Status.VERIFIED && wide.reason.startsWith("3 activities"), "wide: " + wide.reason);
            String ratio = "maxAspectRatio#1010521=" + Float.floatToIntBits(3.0f);
            for (List<String> a : activities(wide.manifest)) check(a.get(a.size() - 1).equals(ratio) || a.contains(ratio), "ratio added: " + a);
            check(activities(wide.manifest).get(0).get(activities(wide.manifest).get(0).size() - 1).equals(ratio), "highest id sorts last");
            TvLandscapeRule.Result both = WideScreenRule.repair(TvLandscapeRule.repair(in).manifest);
            List<String> splash = activities(both.manifest).get(0);
            check(splash.indexOf("screenOrientation#101001e=6") >= 0 && splash.indexOf(ratio) > splash.indexOf("screenOrientation#101001e=6"), "both attributes, sorted: " + splash);
            check(ManifestPatcher.patch(both.manifest).packageName.equals("com.afksoft.WordShakerOuya"), "manifest still parses");
            check(WideScreenRule.repair(wide.manifest).status == CompatibilityEvidence.Status.SKIPPED, "wide second pass unchanged");
        }
        // android.input-config-changes: keyboard|keyboardHidden|navigation (0x70) OR-ed into an existing configChanges, added elsewhere.
        for (boolean utf8 : new boolean[]{false, true}) {
            byte[] in = manifest(utf8, false);
            TvLandscapeRule.Result input = InputConfigRule.repair(in);
            check(input.status == CompatibilityEvidence.Status.VERIFIED && input.reason.startsWith("3 activities") && input.reason.contains("1 widened") && input.reason.contains("2 new"), "input: " + input.reason);
            List<List<String>> acts = activities(input.manifest);
            check(acts.get(1).contains("configChanges#101001f=" + (0xa0 | 0x70)), "existing value widened: " + acts.get(1));
            check(acts.get(0).contains("configChanges#101001f=" + 0x70) && acts.get(2).contains("configChanges#101001f=" + 0x70), "added: " + acts);
            check(ManifestPatcher.patch(input.manifest).launcherActivities.equals(Set.of("Lcom/afksoft/WordShakerBase/ShakerSplashActivity;")), "manifest still parses");
            check(InputConfigRule.repair(input.manifest).status == CompatibilityEvidence.Status.SKIPPED, "input second pass unchanged");
            check(Arrays.equals(in, manifest(utf8, false)), "input array untouched");
        }
        TvLandscapeRule.Result bad = TvLandscapeRule.repair(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        check(bad.status == CompatibilityEvidence.Status.UNKNOWN && bad.manifest == null, "malformed manifest left alone");
        System.out.println("TvLandscapeRuleTest OK");
    }
}
