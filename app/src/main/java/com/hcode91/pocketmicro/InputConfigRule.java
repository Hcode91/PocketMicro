package com.hcode91.pocketmicro;

import java.nio.*;
import java.util.*;

/**
 * android.input-config-changes: on an OUYA the controllers were connected before a game started.
 * On a phone, PocketMicro's virtual controller (or a Bluetooth pad) appears while the game is
 * already running, which changes Android's keyboard/navigation configuration. An activity that
 * doesn't declare it handles that is destroyed and recreated, and games never written for that
 * crash in their own clean-up (Whack'em: MainActivity.onStop -> ResourceManager.unloadMenuTextures
 * on textures not loaded yet, 0.3 s after launch).
 *
 * Every &lt;activity&gt; gets keyboard|keyboardHidden|navigation added to android:configChanges (OR-ed
 * into an existing value, or added). Rotation and every other configuration change behave as before.
 */
public final class InputConfigRule {
    public static final String ID = "android.input-config-changes";
    static final int ATTR_CONFIG_CHANGES = 0x0101001f, INPUT_FLAGS = 0x10 | 0x20 | 0x40, TYPE_INT_HEX = 0x11;

    private InputConfigRule() {}
    public static TvLandscapeRule.Result inspect(byte[] axml) { return run(axml, false); }
    public static TvLandscapeRule.Result repair(byte[] axml) { return run(axml, true); }

    private static TvLandscapeRule.Result run(byte[] axml, boolean apply) {
        TvLandscapeRule.Result r = new TvLandscapeRule.Result();
        try {
            byte[] work = axml.clone();
            int widened = widenExisting(work, apply);
            TvLandscapeRule.Result add = TvLandscapeRule.addToActivities(apply ? work : axml, apply, ATTR_CONFIG_CHANGES, "configChanges", TYPE_INT_HEX, INPUT_FLAGS);
            if (add.status == CompatibilityEvidence.Status.UNKNOWN) return add;
            int added = add.added;
            if (widened == 0 && added == 0) { r.reason = "every activity already handles keyboard and navigation changes"; return r; }
            r.status = apply ? CompatibilityEvidence.Status.VERIFIED : CompatibilityEvidence.Status.SUPPORTED;
            r.reason = (widened + added) + " activit" + (widened + added == 1 ? "y" : "ies") + " now keep running when a controller connects (keyboard|keyboardHidden|navigation"
                    + " added to configChanges" + (widened > 0 ? "; " + widened + " widened" : "") + (added > 0 ? "; " + added + " new" : "")
                    + ") instead of being destroyed and recreated mid-start.";
            if (apply) r.manifest = add.manifest != null ? add.manifest : work;
        } catch (Exception e) {
            r.manifest = null; r.status = CompatibilityEvidence.Status.UNKNOWN; r.reason = e.getMessage() + "; original manifest retained";
        }
        return r;
    }

    /** ORs the input flags into existing integer configChanges attributes on activities, in place. */
    static int widenExisting(byte[] axml, boolean apply) {
        ByteBuffer b = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        if (axml.length < 8 || b.getShort(0) != TvLandscapeRule.XML || b.getInt(4) != axml.length) throw new IllegalStateException("manifest header not recognised");
        int headerSize = b.getShort(2) & 0xffff;
        List<String> strings = null; int[] ids = new int[0]; int widened = 0;
        for (int at = headerSize; at + 8 <= axml.length; ) {
            int type = b.getShort(at) & 0xffff, size = b.getInt(at + 4);
            if (size < 8 || at + size > axml.length) throw new IllegalStateException("bad chunk at " + at);
            if (type == TvLandscapeRule.POOL && strings == null) strings = TvLandscapeRule.decodePool(b, at);
            else if (type == TvLandscapeRule.RES_MAP) { ids = new int[(size - 8) / 4]; for (int i = 0; i < ids.length; i++) ids[i] = b.getInt(at + 8 + 4 * i); }
            else if (type == TvLandscapeRule.START && strings != null) {
                int name = b.getInt(at + 20);
                if (name >= 0 && name < strings.size() && strings.get(name).equals("activity")) {
                    int attrStart = b.getShort(at + 24) & 0xffff, count = b.getShort(at + 28) & 0xffff;
                    for (int i = 0; i < count; i++) {
                        int a = at + 16 + attrStart + 20 * i, n = b.getInt(a + 4), dataType = axml[a + 15] & 0xff;
                        if (n < 0 || n >= ids.length || ids[n] != ATTR_CONFIG_CHANGES) continue;
                        if (dataType < 0x10 || dataType > 0x1f) continue; // not an integer value; left alone
                        int value = b.getInt(a + 16);
                        if ((value & INPUT_FLAGS) == INPUT_FLAGS) continue;
                        widened++;
                        if (apply) b.putInt(a + 16, value | INPUT_FLAGS);
                    }
                }
            }
            at += size;
        }
        return widened;
    }
}
