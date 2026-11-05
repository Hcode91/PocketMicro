package io.github.hozdogus.pocketouya;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows Phone games, run by the WPR runtime built into the Windows Phone edition of PocketOuya
 * (see winphone/README.md). The C# side is WinPhoneBridge (winphone/Bridge/WinPhoneBridgeActivity.cs):
 * it installs .xap files, launches games in WPR's own game process, writes the game list read here,
 * and exports WPR's logs. In the plain PocketOuya build the bridge is absent and all of this hides.
 */
final class WinPhone {
    static final String BRIDGE = "io.github.hozdogus.pocketouya.winphone.WinPhoneBridge";
    static final String ACTION_INSTALL = "io.github.hozdogus.pocketouya.winphone.INSTALL";
    static final String ACTION_LAUNCH = "io.github.hozdogus.pocketouya.winphone.LAUNCH";
    static final String ACTION_UNINSTALL = "io.github.hozdogus.pocketouya.winphone.UNINSTALL";
    static final String ACTION_EXPORT_LOGS = "io.github.hozdogus.pocketouya.winphone.EXPORT_LOGS";
    static final String EXTRA_PRODUCT = "productId";
    static final String EXTRA_LOGGING = "logging";
    /** Set by the bridge on its result: what happened, for PocketOuya's global log. */
    static final String EXTRA_OUTCOME = "outcome", EXTRA_DETAIL = "detail";

    static final class Game {
        String productId, name, type;
        Drawable tile;
    }

    private WinPhone() {}

    /** Whether this build contains the Windows Phone runtime. */
    static boolean available(Context context) {
        try {
            context.getPackageManager().getActivityInfo(new ComponentName(context, BRIDGE), 0);
            return true;
        } catch (PackageManager.NameNotFoundException absent) {
            return false;
        }
    }

    static Intent bridge(Context context, String action) {
        return new Intent(action).setComponent(new ComponentName(context, BRIDGE));
    }

    /** Folder shared with the bridge: games.json plus a tile image per game. */
    static File folder(Context context) {
        return new File(context.getFilesDir(), "winphone");
    }

    static List<Game> games(Context context) {
        List<Game> out = new ArrayList<>();
        File index = new File(folder(context), "games.json");
        if (!index.isFile()) return out;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(index.toPath());
            JSONArray list = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                Game g = new Game();
                g.productId = o.getString("productId");
                g.name = o.optString("name", g.productId);
                g.type = o.optString("type", "");
                String tile = o.optString("tile", "");
                if (!tile.isEmpty()) {
                    File f = new File(folder(context), tile);
                    if (f.isFile()) {
                        android.graphics.Bitmap bitmap = BitmapFactory.decodeFile(f.getPath());
                        if (bitmap != null) g.tile = new BitmapDrawable(context.getResources(), bitmap);
                    }
                }
                out.add(g);
            }
        } catch (Exception | OutOfMemoryError error) {
            android.util.Log.w("PocketOuyaWinPhone", "Game list unreadable", error);
        }
        return out;
    }

    static void install(Activity host, int requestCode) {
        host.startActivityForResult(bridge(host, ACTION_INSTALL), requestCode);
    }

    static void launch(Activity host, Game game, boolean logging, int requestCode) {
        LaunchLogging.begin(host, game.name, game.productId, host.getPackageName(),
                "Windows Phone (WPR" + (game.type.isEmpty() ? "" : ", " + game.type) + (logging ? ", with logging" : "") + ")");
        host.startActivityForResult(bridge(host, ACTION_LAUNCH).putExtra(EXTRA_PRODUCT, game.productId).putExtra(EXTRA_LOGGING, logging), requestCode);
    }

    static void uninstall(Activity host, Game game, int requestCode) {
        host.startActivityForResult(bridge(host, ACTION_UNINSTALL).putExtra(EXTRA_PRODUCT, game.productId), requestCode);
    }

    static void exportLogs(Activity host, Game game) {
        Intent i = bridge(host, ACTION_EXPORT_LOGS);
        if (game != null) i.putExtra(EXTRA_PRODUCT, game.productId);
        host.startActivity(i);
    }

    /** Records a launch result from the bridge in PocketOuya's global log. */
    static void logResult(Context context, Game game, int resultCode, Intent data) {
        String outcome = data == null ? null : data.getStringExtra(EXTRA_OUTCOME);
        String detail = data == null ? null : data.getStringExtra(EXTRA_DETAIL);
        if (outcome == null) outcome = resultCode == Activity.RESULT_OK ? "Game exited" : "Game stopped unexpectedly";
        LaunchLogging.append(context, game == null ? "Windows Phone game" : game.name, outcome,
                detail == null || detail.isEmpty() ? "No further detail from WPR. Use \"Export Windows Phone logs\"." : detail);
    }
}
