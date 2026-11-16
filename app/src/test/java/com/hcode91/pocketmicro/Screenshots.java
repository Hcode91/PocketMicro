package com.hcode91.pocketmicro;

import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Renders the tutorial screenshots in docs/screenshots off-device. Every game in them is a
 * made-up placeholder with a drawn icon, not a real title.
 *
 *   gradle :app:testDebugUnitTest --tests '*Screenshots*'
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
public class Screenshots {
    private final File out = new File(System.getProperty("screenshots.dir", "../docs/screenshots"));

    private void save(View v, String name, int w, int h) throws Exception {
        out.mkdirs();
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(bmp));
        Bitmap small = Bitmap.createScaledBitmap(bmp, w / 2, h / 2, true);
        try (FileOutputStream f = new FileOutputStream(new File(out, name + ".png"))) {
            small.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
    }

    private static Bitmap art(int w, int h, int color, String text) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawRoundRect(0, 0, w, h, h * 0.12f, h * 0.12f, p);
        p.setColor(Color.WHITE);
        p.setTextSize(h * 0.42f);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, w / 2f, h * 0.65f, p);
        return b;
    }

    private static void androidGame(String pkg, String label, int color) {
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.packageName = pkg;
        pi.applicationInfo.nonLocalizedLabel = label;
        android.content.pm.PackageManager pm = RuntimeEnvironment.getApplication().getPackageManager();
        shadowOf(pm).installPackage(pi);
        shadowOf(pm).setApplicationIcon(pkg, new BitmapDrawable(RuntimeEnvironment.getApplication().getResources(),
                art(192, 192, color, label.substring(0, 1))));
    }

    @Before
    public void library() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        androidGame("com.example.shooter", "Example Shooter", 0xFF2E86DE);
        androidGame("com.example.platformer", "Sample Platformer", 0xFF10AC84);
        app.getSharedPreferences("library", 0).edit()
                .putStringSet("imported", new HashSet<>(Arrays.asList("com.example.shooter", "com.example.platformer")))
                .commit();

        // Pretend this is the Windows Phone edition, with two games installed.
        ActivityInfo bridge = new ActivityInfo();
        bridge.packageName = app.getPackageName();
        bridge.name = WinPhone.BRIDGE;
        shadowOf(app.getPackageManager()).addOrUpdateActivity(bridge);
        File wp = WinPhone.folder(app);
        wp.mkdirs();
        try (FileOutputStream f = new FileOutputStream(new File(wp, "tile1.png"))) {
            art(336, 336, 0xFF8E44AD, "P").compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        try (FileOutputStream f = new FileOutputStream(new File(wp, "tile2.png"))) {
            art(336, 336, 0xFFE67E22, "D").compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        String json = "[{\"productId\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"Puzzle Demo (WP)\",\"type\":\"XNA\",\"tile\":\"tile1.png\",\"achEarned\":3,\"achTotal\":12,\"gsEarned\":40,\"gsTotal\":200},"
                + "{\"productId\":\"22222222-2222-2222-2222-222222222222\",\"name\":\"Drift Test (WP)\",\"type\":\"XNA\",\"tile\":\"tile2.png\"}]";
        java.nio.file.Files.write(new File(wp, "games.json").toPath(), json.getBytes("UTF-8"));
    }

    private MainActivity start() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    private Object game(MainActivity act, String label) throws Exception {
        java.lang.reflect.Field gf = MainActivity.class.getDeclaredField("games");
        gf.setAccessible(true);
        for (Object g : (List<?>) gf.get(act)) {
            java.lang.reflect.Field lf = g.getClass().getDeclaredField("label");
            lf.setAccessible(true);
            if (label.contentEquals((CharSequence) lf.get(g))) return g;
        }
        throw new AssertionError("no game " + label);
    }

    private void options(MainActivity act, String label, String name, int h) throws Exception {
        java.lang.reflect.Method m = null;
        for (java.lang.reflect.Method c : MainActivity.class.getDeclaredMethods())
            if (c.getName().equals("gameOptions") && c.getParameterCount() == 1) m = c;
        m.setAccessible(true);
        m.invoke(act, game(act, label));
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        save(d.getWindow().getDecorView(), name, 1000, h);
    }

    @Test
    public void portrait() throws Exception {
        MainActivity act = start();
        View root = act.findViewById(android.R.id.content);
        save(root, "1-play", 1080, 2340);

        java.lang.reflect.Method sel = MainActivity.class.getDeclaredMethod("selectSection", int.class, boolean.class);
        sel.setAccessible(true);
        sel.invoke(act, 2, false);
        save(root, "2-manage", 1080, 2340);

        options(act, "Example Shooter", "3-game-options", 1260);
        options(act, "Puzzle Demo (WP)", "4-windows-phone-options", 1000);
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void landscape() throws Exception {
        MainActivity act = start();
        save(act.findViewById(android.R.id.content), "5-play-landscape", 2340, 1080);
    }
}
