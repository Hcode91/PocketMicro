package com.hcode91.pocketmicro;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Edits an APK's compiled (binary XML) AndroidManifest.xml in place so that
 * Android 14+ will install it: raises the target SDK of old Ouya-era apps to 23.
 * Only fixed-size integer values are changed, so the file layout stays intact.
 */
public final class ManifestPatcher {
    /** Android 14 refuses to install apps that target anything below this. */
    public static final int MIN_TARGET = 23;
    public static final int KEEP_TARGET = 0;

    private static final int RES_STRING_POOL = 0x0001;
    private static final int RES_XML_RESOURCE_MAP = 0x0180;
    private static final int RES_XML_START_ELEMENT = 0x0102;
    private static final int ATTR_MIN_SDK = 0x0101020c;
    private static final int ATTR_TARGET_SDK = 0x01010270;
    private static final int TYPE_INT_DEC = 0x10;

    public static final class Result {
        public byte[] manifest;
        public String packageName = "";
        public int minSdk = -1;
        public int oldTarget = -1;   // -1 = not declared
        public int newTarget = -1;
        public boolean changed;
        public final java.util.Set<String> launcherActivities = new java.util.LinkedHashSet<>();
        public String compatibilityReport = "";
        public boolean ouyaGame;     // declares tv.ouya.intent.category.GAME
    }

    private ManifestPatcher() {}

    public static Result patch(byte[] axml) { return patch(axml,MIN_TARGET); }

    public static Result patch(byte[] axml,int minimumTarget) {
        // 0 = keep the game's original target (installed via Shizuku with the low-target bypass).
        if(minimumTarget!=KEEP_TARGET&&(minimumTarget<23||minimumTarget>24))throw new IllegalArgumentException("Unsupported target policy");
        byte[] data = axml.clone();
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        List<String> strings = new ArrayList<>();
        int[] resIds = new int[0];
        Result r = new Result();
        int minSdkValueOffset = -1;
        int targetValueOffset = -1;

        String activity = null;
        boolean inFilter=false, mainAction=false, launchCategory=false;
        int pos = 8;
        while (pos + 8 <= data.length) {
            int type = b.getShort(pos) & 0xffff;
            int size = b.getInt(pos + 4);
            if (size <= 0) break;
            if (type == RES_STRING_POOL) {
                readStrings(b, pos, strings);
            } else if (type == RES_XML_RESOURCE_MAP) {
                int n = (size - 8) / 4;
                resIds = new int[n];
                for (int i = 0; i < n; i++) resIds[i] = b.getInt(pos + 8 + i * 4);
            } else if (type == RES_XML_START_ELEMENT) {
                int name = b.getInt(pos + 20);
                int attrStart = b.getShort(pos + 24) & 0xffff;
                int attrSize = b.getShort(pos + 26) & 0xffff;
                int attrCount = b.getShort(pos + 28) & 0xffff;
                String tag = str(strings, name);
                if ("activity".equals(tag) || "activity-alias".equals(tag)) activity=null;
                if ("intent-filter".equals(tag)) {inFilter=true; mainAction=false; launchCategory=false;}
                for (int i = 0; i < attrCount; i++) {
                    int a = pos + 16 + attrStart + i * attrSize;
                    int attrName = b.getInt(a + 4);
                    int raw = b.getInt(a + 8);
                    int dataType = data[a + 15] & 0xff;
                    int value = b.getInt(a + 16);
                    int resId = attrName >= 0 && attrName < resIds.length ? resIds[attrName] : 0;
                    String an = str(strings, attrName);
                    String text = raw != -1 ? str(strings,raw) : dataType == 3 ? str(strings,value) : "";
                    if ("manifest".equals(tag) && "package".equals(an)) r.packageName=text;
                    if (("activity".equals(tag) && "name".equals(an)) || ("activity-alias".equals(tag) && "targetActivity".equals(an))) activity=text;
                    if (inFilter && "action".equals(tag) && "name".equals(an) && "android.intent.action.MAIN".equals(text)) mainAction=true;
                    if (inFilter && "category".equals(tag) && "name".equals(an)
                            && ("android.intent.category.LAUNCHER".equals(text)||"tv.ouya.intent.category.GAME".equals(text))) launchCategory=true;
                    if ("uses-sdk".equals(tag) && dataType == TYPE_INT_DEC) {
                        if (resId == ATTR_MIN_SDK || "minSdkVersion".equals(an)) {
                            r.minSdk = value;
                            minSdkValueOffset = a + 16;
                        } else if (resId == ATTR_TARGET_SDK || "targetSdkVersion".equals(an)) {
                            r.oldTarget = value;
                            targetValueOffset = a + 16;
                        }
                    }
                    if ("category".equals(tag) && "tv.ouya.intent.category.GAME".equals(text))
                        r.ouyaGame = true;
                }
            }
            if (type == 0x0103) {
                String tag=str(strings,b.getInt(pos+20));
                if ("intent-filter".equals(tag)) {
                    if (inFilter && mainAction && launchCategory && activity!=null && !activity.isEmpty()) {
                        String full=activity.startsWith(".")?r.packageName+activity:activity.indexOf('.')<0?r.packageName+"."+activity:activity;
                        r.launcherActivities.add("L"+full.replace('.','/')+";");
                    }
                    inFilter=false;
                }
                if ("activity".equals(tag)||"activity-alias".equals(tag)) activity=null;
            }
            pos += size;
        }

        if (targetValueOffset >= 0) {
            r.newTarget = r.oldTarget;
            if (r.oldTarget < minimumTarget) {
                b.putInt(targetValueOffset, minimumTarget);
                r.newTarget = minimumTarget;
                r.changed = true;
            }
        } else if (minSdkValueOffset >= 0) {
            // No targetSdkVersion: Android treats minSdkVersion as the target.
            r.newTarget = r.minSdk;
            if (r.minSdk < minimumTarget) {
                b.putInt(minSdkValueOffset, minimumTarget);
                r.newTarget = minimumTarget;
                r.changed = true;
            }
        } else {
            throw new IllegalArgumentException("This APK has no <uses-sdk> entry PocketOuya can update.");
        }
        r.manifest = data;
        return r;
    }

    private static String str(List<String> s, int i) {
        return (i >= 0 && i < s.size()) ? s.get(i) : "";
    }

    private static void readStrings(ByteBuffer b, int pos, List<String> out) {
        int count = b.getInt(pos + 8);
        int flags = b.getInt(pos + 16);
        int stringsStart = b.getInt(pos + 20);
        boolean utf8 = (flags & 0x100) != 0;
        int base = pos + stringsStart;
        byte[] data = b.array();
        for (int i = 0; i < count; i++) {
            int p = base + b.getInt(pos + 28 + i * 4);
            try {
                if (utf8) {
                    int l = data[p] & 0xff;
                    p += (l & 0x80) != 0 ? 2 : 1;
                    int len = data[p] & 0xff;
                    if ((len & 0x80) != 0) {
                        len = ((len & 0x7f) << 8) | (data[p + 1] & 0xff);
                        p += 2;
                    } else {
                        p += 1;
                    }
                    out.add(new String(data, p, len, StandardCharsets.UTF_8));
                } else {
                    int len = b.getShort(p) & 0xffff;
                    p += 2;
                    if ((len & 0x8000) != 0) {
                        len = ((len & 0x7fff) << 16) | (b.getShort(p) & 0xffff);
                        p += 2;
                    }
                    out.add(new String(data, p, len * 2, StandardCharsets.UTF_16LE));
                }
            } catch (RuntimeException e) {
                out.add("");
            }
        }
    }
}
