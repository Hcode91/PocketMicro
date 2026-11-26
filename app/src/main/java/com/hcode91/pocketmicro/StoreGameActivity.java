package com.hcode91.pocketmicro;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.Locale;

/** Store game page: details from the community server, download, then PocketMicro's normal conversion/install. */
public class StoreGameActivity extends Activity {
    static final String EXTRA_PKG = "pkg";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private StoreClient client;
    private StoreClient.Game game;
    private LinearLayout content;
    private TextView status;
    private ProgressBar bar;
    private Button action;
    private volatile boolean downloading, cancel;

    static File downloads(Context c) { File d = new File(c.getFilesDir(), "store"); d.mkdirs(); return d; }
    static File apkFile(Context c, StoreClient.Game g) {
        String safe = g.pkg.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(downloads(c), safe + "-" + g.versionCode + ".apk");
    }

    int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sizeSp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4)); return t;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        client = StoreActivity.client(this);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(Color.rgb(23, 23, 26));
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(14), dp(14), dp(14), dp(14));
        scroll.addView(content); setContentView(scroll);
        status = text("Loading…", 14, Color.LTGRAY, false); content.addView(status);
        String pkg = getIntent().getStringExtra(EXTRA_PKG);
        new Thread(() -> {
            try {
                StoreClient.Game g = client.game(pkg);
                ui.post(() -> { if (!isFinishing()) show(g); });
            } catch (Exception e) { ui.post(() -> status.setText("Could not load this game: " + e.getMessage())); }
        }, "store-game").start();
    }

    private void show(StoreClient.Game g) {
        game = g; content.removeAllViews();
        ImageView hero = new ImageView(this); hero.setAdjustViewBounds(true); hero.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(hero, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));
        StoreImages.load(client, g.tileImage, hero, dp(400));
        content.addView(text(g.title, 22, Color.WHITE, true));
        StringBuilder meta = new StringBuilder();
        if (!g.developer.isEmpty()) meta.append(g.developer);
        if (!g.genres.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append(g.genres);
        if (g.players > 1) meta.append(" · 1-").append(g.players).append(" players");
        if (!g.age.isEmpty()) meta.append(" · ").append(g.age);
        content.addView(text(meta.toString(), 14, Color.LTGRAY, false));
        String size = g.fileSize > 0 ? String.format(Locale.US, "%.1f MB", g.fileSize / 1048576.0) : "size unknown";
        String rating = g.ratingCount > 0 ? String.format(Locale.US, " · ★ %.1f (%d)", g.rating, g.ratingCount) : "";
        content.addView(text("Version " + (g.versionName.isEmpty() ? "?" : g.versionName) + " · " + size + rating
                + (g.premium ? " · Paid game" : g.inAppPurchases ? " · In-app purchases" : " · Free"), 14, Color.LTGRAY, false));

        action = new Button(this); content.addView(action);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(1000); bar.setVisibility(View.GONE); content.addView(bar);
        status = text("", 13, Color.LTGRAY, false); content.addView(status);
        refreshAction();

        if (!g.media.isEmpty()) {
            HorizontalScrollView strip = new HorizontalScrollView(this); LinearLayout shots = new LinearLayout(this);
            for (StoreClient.Media m : g.media) {
                ImageView shot = new ImageView(this); shot.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(240), dp(135)); lp.setMargins(0, dp(8), dp(8), dp(8));
                shots.addView(shot, lp); StoreImages.load(client, m.thumbnail, shot, dp(240));
            }
            strip.addView(shots); content.addView(strip);
        }
        content.addView(text(g.description, 15, Color.WHITE, false));
        content.addView(text("From the community server " + client.server() + ". Downloaded games are converted by PocketMicro before installing, like an APK you add yourself.", 12, Color.GRAY, false));
    }

    private boolean installed() {
        try { getPackageManager().getPackageInfo(game.pkg, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private void refreshAction() {
        File apk = apkFile(this, game);
        boolean have = apk.isFile() && (game.fileSize <= 0 || apk.length() == game.fileSize);
        if (downloading) { action.setText("CANCEL DOWNLOAD"); action.setOnClickListener(v -> cancel = true); return; }
        action.setText(have ? "CONVERT & INSTALL (downloaded)" : installed() ? "DOWNLOAD & REINSTALL" : "DOWNLOAD & INSTALL");
        action.setOnClickListener(v -> { if (have) convert(apk); else download(apk); });
    }

    private void download(File apk) {
        downloading = true; cancel = false; refreshAction();
        bar.setVisibility(View.VISIBLE); bar.setProgress(0); status.setText("Downloading…");
        new Thread(() -> {
            long[] last = {0};
            try {
                client.download(game, apk, new StoreClient.Progress() {
                    @Override public void update(long done, long total) {
                        long now = System.currentTimeMillis(); if (now - last[0] < 200) return; last[0] = now;
                        ui.post(() -> {
                            if (total > 0) bar.setProgress((int) (done * 1000 / total));
                            status.setText(String.format(Locale.US, "Downloading… %.1f / %s MB", done / 1048576.0, total > 0 ? String.format(Locale.US, "%.1f", total / 1048576.0) : "?"));
                        });
                    }
                    @Override public boolean cancelled() { return cancel; }
                });
                ui.post(() -> { downloading = false; bar.setVisibility(View.GONE); status.setText("Downloaded and checked."); refreshAction(); convert(apk); });
            } catch (Exception e) {
                ui.post(() -> { downloading = false; bar.setVisibility(View.GONE); status.setText(cancel ? "Download cancelled." : "Download failed: " + e.getMessage()); refreshAction(); });
            }
        }, "store-download").start();
    }

    /** Hands the APK to PocketMicro's main screen, which assesses, converts and installs it. */
    private void convert(File apk) {
        startActivity(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_IMPORT_STORE).putExtra("apk", apk.getPath())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    @Override protected void onResume() { super.onResume(); if (game != null && action != null) refreshAction(); }
    @Override protected void onDestroy() { cancel = true; super.onDestroy(); }
}
