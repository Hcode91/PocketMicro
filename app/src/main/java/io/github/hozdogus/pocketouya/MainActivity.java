package io.github.hozdogus.pocketouya;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The game library: Ouya games on this phone, plus "Add game" to import an APK. */
public class MainActivity extends Activity {
    static final String ACTION_REFRESH = "io.github.hozdogus.pocketouya.REFRESH";
    private static final String OUYA_GAME = "tv.ouya.intent.category.GAME";
    private static final int PICK_APK = 1;

    static final int BG = 0xFF161616;
    static final int CARD = 0xFF242424;
    static final int ACCENT = 0xFFE8432E;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Game> games = new ArrayList<>();
    private GamesAdapter adapter;
    private TextView empty;
    private AlertDialog progress;

    static final class Game {
        String pkg;
        CharSequence label;
        android.graphics.drawable.Drawable icon;
    }

    private final BroadcastReceiver refresh = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadGames(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        registerReceiver(refresh, new IntentFilter(ACTION_REFRESH), Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(refresh);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadGames();
    }

    // ---------------------------------------------------------------- UI

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(24), dp(20), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("PocketOuya");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button add = new Button(this);
        add.setText("+  ADD GAME");
        add.setTextColor(Color.WHITE);
        add.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(6));
        add.setBackground(bg);
        add.setPadding(dp(16), 0, dp(16), 0);
        add.setOnClickListener(v -> pickApk());
        header.addView(add);
        root.addView(header);

        TextView section = new TextView(this);
        section.setText("PLAY");
        section.setTextColor(ACCENT);
        section.setTextSize(13);
        section.setLetterSpacing(0.2f);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setPadding(0, dp(18), 0, dp(8));
        root.addView(section);

        empty = new TextView(this);
        empty.setText("No Ouya games yet.\n\nTap ADD GAME and choose an Ouya game's .apk file. "
                + "PocketOuya updates it so it installs on modern Android, then it shows up here.\n\n"
                + "Tip: a Bluetooth controller works best – most Ouya games were made for one.");
        empty.setTextColor(0xFFAAAAAA);
        empty.setTextSize(15);
        empty.setPadding(0, dp(24), 0, 0);
        root.addView(empty);

        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(150));
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(12));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new GamesAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(games.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            gameOptions(games.get(pos));
            return true;
        });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private final class GamesAdapter extends BaseAdapter {
        @Override public int getCount() { return games.size(); }
        @Override public Object getItem(int i) { return games.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Game g = games.get(i);
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(10), dp(14), dp(10), dp(12));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            bg.setCornerRadius(dp(10));
            card.setBackground(bg);

            ImageView icon = new ImageView(MainActivity.this);
            icon.setImageDrawable(g.icon);
            card.addView(icon, new LinearLayout.LayoutParams(dp(84), dp(84)));

            TextView label = new TextView(MainActivity.this);
            label.setText(g.label);
            label.setTextColor(Color.WHITE);
            label.setTextSize(14);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setPadding(0, dp(8), 0, 0);
            card.addView(label);
            return card;
        }
    }

    // ------------------------------------------------------------ library

    private SharedPreferences prefs() {
        return getSharedPreferences("library", MODE_PRIVATE);
    }

    private void loadGames() {
        PackageManager pm = getPackageManager();
        Map<String, Game> found = new LinkedHashMap<>();

        Intent ouya = new Intent(Intent.ACTION_MAIN).addCategory(OUYA_GAME);
        for (ResolveInfo ri : pm.queryIntentActivities(ouya, 0))
            addGame(found, pm, ri.activityInfo.packageName);

        Set<String> imported = new HashSet<>(prefs().getStringSet("imported", new HashSet<>()));
        for (String pkg : imported) addGame(found, pm, pkg);

        games.clear();
        games.addAll(found.values());
        games.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        adapter.notifyDataSetChanged();
        empty.setVisibility(games.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void addGame(Map<String, Game> found, PackageManager pm, String pkg) {
        if (found.containsKey(pkg) || pkg.equals(getPackageName())) return;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            Game g = new Game();
            g.pkg = pkg;
            g.label = pm.getApplicationLabel(ai);
            g.icon = pm.getApplicationIcon(ai);
            found.put(pkg, g);
        } catch (PackageManager.NameNotFoundException ignored) {
            // uninstalled
        }
    }

    private void launch(Game g) {
        Intent i = getPackageManager().getLaunchIntentForPackage(g.pkg);
        if (i == null) {
            Intent ouya = new Intent(Intent.ACTION_MAIN).addCategory(OUYA_GAME).setPackage(g.pkg);
            List<ResolveInfo> r = getPackageManager().queryIntentActivities(ouya, 0);
            if (!r.isEmpty())
                i = ouya.setClassName(g.pkg, r.get(0).activityInfo.name);
        }
        if (i == null) {
            Toast.makeText(this, "Can't find how to start this game.", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void gameOptions(Game g) {
        new AlertDialog.Builder(this)
                .setTitle(g.label)
                .setItems(new String[] { "Play", "App info", "Uninstall", "Engine test (developer)",
                        "Play with PocketOuya engine (beta)" }, (d, which) -> {
                    if (which == 0) launch(g);
                    else if (which == 3) engineTest(g);
                    else if (which == 4) playWithEngine(g);
                    else if (which == 1)
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + g.pkg)));
                    else
                        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + g.pkg)));
                })
                .show();
    }

    // -------------------------------------------------------- engine test

    /** Runs the game's 32-bit engine through PocketOuya's translator (game screen). */
    private void playWithEngine(Game g) {
        try {
            String apk = getPackageManager().getApplicationInfo(g.pkg, 0).sourceDir;
            startActivity(new Intent(this, GameActivity.class)
                    .putExtra(GameActivity.EXTRA_APK, apk)
                    .putExtra(GameActivity.EXTRA_PKG, g.pkg)
                    .putExtra(GameActivity.EXTRA_NAME, g.label.toString()));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    /** Runs the 32-bit engine boot test on this phone and shows the log. */
    private void engineTest(Game g) {
        String apk;
        try {
            apk = getPackageManager().getApplicationInfo(g.pkg, 0).sourceDir;
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        File save = new File(getFilesDir(), "saves/" + g.pkg);
        save.mkdirs();
        showProgress("Running the engine test…\n(this can take up to a minute)");
        new Thread(() -> {
            String report;
            long t0 = System.currentTimeMillis();
            try {
                report = Engine.bootTest(apk, save.getAbsolutePath());
            } catch (Throwable t) {
                report = "Engine failed to start: " + t;
            }
            final String text = "Took " + (System.currentTimeMillis() - t0) / 1000.0 + " s\n\n" + report;
            hideProgress();
            ui.post(() -> {
                TextView tv = new TextView(this);
                tv.setText(text);
                tv.setTextIsSelectable(true);
                tv.setTextSize(11);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setPadding(dp(16), dp(12), dp(16), dp(12));
                android.widget.ScrollView sv = new android.widget.ScrollView(this);
                sv.addView(tv);
                new AlertDialog.Builder(this).setTitle("Engine test: " + g.label).setView(sv)
                        .setPositiveButton("Copy", (d, w) -> {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("engine log", text));
                            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Close", null).show();
            });
        }, "engine-test").start();
    }

    // ------------------------------------------------------------- import

    private void pickApk() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Allow installing games")
                    .setMessage("PocketOuya needs permission to install apps so it can install your "
                            + "updated games. Turn on \"Allow from this source\" on the next screen, then come back.")
                    .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                        "application/vnd.android.package-archive", "application/octet-stream" });
        startActivityForResult(pick, PICK_APK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_APK && resultCode == RESULT_OK && data != null && data.getData() != null)
            importApk(data.getData());
    }

    private void showProgress(String msg) {
        ui.post(() -> {
            if (progress == null) {
                progress = new AlertDialog.Builder(this).setTitle("Adding game").setMessage(msg)
                        .setCancelable(false).create();
                progress.show();
            } else {
                progress.setMessage(msg);
            }
        });
    }

    private void hideProgress() {
        ui.post(() -> {
            if (progress != null) progress.dismiss();
            progress = null;
        });
    }

    private void importApk(Uri uri) {
        showProgress("Copying the game…");
        new Thread(() -> {
            File dir = new File(getCacheDir(), "import");
            File original = new File(dir, "original.apk");
            File patched = new File(dir, "patched.apk");
            File signed = new File(dir, "signed.apk");
            try {
                dir.mkdirs();
                ApkRebuilder.copy(getContentResolver().openInputStream(uri), original);

                showProgress("Updating it for this version of Android…");
                ManifestPatcher.Result r = ApkRebuilder.rebuild(original, patched);
                original.delete();

                showProgress("Signing…");
                GameSigner.sign(this, patched, signed);
                patched.delete();

                Set<String> imported = new HashSet<>(prefs().getStringSet("imported", new HashSet<>()));
                imported.add(r.packageName);
                prefs().edit().putStringSet("imported", imported).apply();

                showProgress("Installing…");
                GameInstaller.install(this, signed);
                hideProgress();
            } catch (Throwable t) {
                hideProgress();
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                ui.post(() -> new AlertDialog.Builder(this).setTitle("Couldn't add this game")
                        .setMessage(msg).setPositiveButton(android.R.string.ok, null).show());
            } finally {
                original.delete();
                patched.delete();
            }
        }, "import").start();
    }
}
