package com.hcode91.pocketmicro;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.GridView;

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

/**
 * Renders the tutorial screenshots in docs/screenshots off-device. The "games" are made-up
 * placeholder packages with drawn icons, not real titles.
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

    private static BitmapDrawable icon(int color, String letter) {
        Bitmap b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawRoundRect(0, 0, 192, 192, 36, 36, p);
        p.setColor(Color.WHITE);
        p.setTextSize(110);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(letter, 96, 134, p);
        return new BitmapDrawable(RuntimeEnvironment.getApplication().getResources(), b);
    }

    private static void fakeGame(String pkg, String label, int color) {
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.packageName = pkg;
        pi.applicationInfo.nonLocalizedLabel = label;
        android.content.pm.PackageManager pm = RuntimeEnvironment.getApplication().getPackageManager();
        shadowOf(pm).installPackage(pi);
        shadowOf(pm).setApplicationIcon(pkg, icon(color, label.substring(0, 1)));
    }

    @Test
    public void screens() throws Exception {
        // Empty library first.
        Activity first = Robolectric.buildActivity(MainActivity.class).setup().get();
        save(first.findViewById(android.R.id.content), "1-empty-library", 1080, 2340);

        fakeGame("com.example.shooter", "Example Shooter", 0xFF2E86DE);
        fakeGame("com.example.platformer", "Sample Platformer", 0xFF10AC84);
        fakeGame("com.example.racer", "Demo Racer", 0xFFEE5253);
        RuntimeEnvironment.getApplication().getSharedPreferences("library", 0).edit()
                .putStringSet("imported", new HashSet<>(Arrays.asList("com.example.shooter", "com.example.platformer", "com.example.racer")))
                .commit();

        MainActivity act = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = act.findViewById(android.R.id.content);
        save(root, "2-library", 1080, 2340);

        java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("gameOptions", MainActivity.Game.class);
        m.setAccessible(true);
        java.lang.reflect.Field gf = MainActivity.class.getDeclaredField("games");
        gf.setAccessible(true);
        Object game = ((java.util.List<?>) gf.get(act)).get(0);
        m.invoke(act, game);
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        save(d.getWindow().getDecorView(), "3-game-options", 1000, 1080);
    }
}
