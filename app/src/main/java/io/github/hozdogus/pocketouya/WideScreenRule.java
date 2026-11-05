package io.github.hozdogus.pocketouya;

/**
 * android.wide-screen: Android letterboxes apps built for Android 7.1 or older (PocketOuya's
 * converted games target 6.0) to an aspect ratio of at most 1.86:1 unless they say otherwise. On
 * a 19.5:9 phone that leaves bars at the sides, and games that read raw screen touch positions
 * then register taps offset by the bar's width (N.O.V.A. 3: the window sat at x = 134..2143 of
 * 2340 px, so the fire button only reacted when tapped to its left).
 *
 * Every &lt;activity&gt; without its own android:maxAspectRatio gets maxAspectRatio="3.0", so games
 * fill any current phone. Activities that set a ratio keep it.
 */
public final class WideScreenRule {
    public static final String ID = "android.wide-screen";
    static final int ATTR_MAX_ASPECT_RATIO = 0x01010521, TYPE_FLOAT = 0x04;
    static final float RATIO = 3.0f;

    private WideScreenRule() {}
    public static TvLandscapeRule.Result inspect(byte[] axml) { return run(axml, false); }
    public static TvLandscapeRule.Result repair(byte[] axml) { return run(axml, true); }

    private static TvLandscapeRule.Result run(byte[] axml, boolean apply) {
        TvLandscapeRule.Result r = TvLandscapeRule.addToActivities(axml, apply, ATTR_MAX_ASPECT_RATIO, "maxAspectRatio", TYPE_FLOAT, Float.floatToIntBits(RATIO));
        if (r.status == CompatibilityEvidence.Status.SKIPPED)
            r.reason = r.kept > 0 ? "all " + r.kept + " activities set their own aspect ratio; unchanged" : "no activities";
        else if (r.status != CompatibilityEvidence.Status.UNKNOWN)
            r.reason = r.added + " activit" + (r.added == 1 ? "y" : "ies") + " may now fill wide phone screens (maxAspectRatio 3.0) instead of Android's 1.86:1 letterbox for old apps,"
                    + " which also keeps raw-position touch input lined up" + (r.kept > 0 ? "; " + r.kept + " kept their own ratio" : "") + ".";
        return r;
    }
}
