package io.github.hozdogus.pocketouya;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** Store: browses a stouyapi community server's Discover pages (rows of games and categories). */
public class StoreActivity extends Activity {
    static final String EXTRA_PAGE = "page", EXTRA_TITLE = "title";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView status;
    private ProgressBar spinner;
    private StoreClient client;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("store", MODE_PRIVATE); }
    static StoreClient client(Context c) {
        try { return new StoreClient(prefs(c).getString("server", StoreClient.DEFAULT_SERVER)); }
        catch (IllegalArgumentException bad) { return new StoreClient(StoreClient.DEFAULT_SERVER); }
    }

    int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(23, 23, 26)); root.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        String pageTitle = getIntent().getStringExtra(EXTRA_TITLE);
        title.setText(pageTitle == null ? "OUYA STORE" : pageTitle); title.setTextColor(Color.WHITE); title.setTextSize(22); title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (pageTitle == null) {
            Button server = new Button(this); server.setText("SERVER"); server.setOnClickListener(v -> editServer());
            header.addView(server);
        }
        root.addView(header);
        status = new TextView(this); status.setTextColor(Color.LTGRAY); status.setPadding(0, dp(4), 0, dp(4)); root.addView(status);
        spinner = new ProgressBar(this); root.addView(spinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        load();
    }

    private void load() {
        client = client(this);
        String page = getIntent().getStringExtra(EXTRA_PAGE);
        content.removeAllViews(); spinner.setVisibility(View.VISIBLE);
        status.setText("Loading from " + client.server() + "…");
        new Thread(() -> {
            try {
                StoreClient.Page result = client.page(page == null ? "discover" : page);
                ui.post(() -> { if (!isFinishing()) show(result); });
            } catch (Exception e) {
                ui.post(() -> { if (isFinishing()) return; spinner.setVisibility(View.GONE);
                    status.setText("Could not load the store from " + client.server() + ":\n" + e.getMessage() + "\n\nCheck your internet connection, or the server address (SERVER)."); });
            }
        }, "store-page").start();
    }

    private void show(StoreClient.Page page) {
        spinner.setVisibility(View.GONE);
        int games = 0; for (StoreClient.Row r : page.rows) games += r.tiles.size();
        status.setText(page.rows.isEmpty() ? "No games on this page." : "Community server: " + client.server());
        for (StoreClient.Row row : page.rows) {
            TextView heading = new TextView(this);
            heading.setText(row.title.isEmpty() ? " " : row.title); heading.setTextColor(Color.WHITE); heading.setTextSize(17); heading.setTypeface(Typeface.DEFAULT_BOLD);
            heading.setPadding(0, dp(14), 0, dp(6));
            content.addView(heading);
            HorizontalScrollView strip = new HorizontalScrollView(this);
            LinearLayout cards = new LinearLayout(this);
            for (StoreClient.Tile tile : row.tiles) cards.addView(card(tile));
            strip.addView(cards); content.addView(strip);
        }
    }

    private View card(StoreClient.Tile tile) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(176), ViewGroup.LayoutParams.WRAP_CONTENT); lp.setMargins(0, 0, dp(10), 0);
        card.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(40, 40, 46)); bg.setCornerRadius(dp(8)); card.setBackground(bg);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        boolean category = tile.page != null;
        if (category) {
            TextView box = new TextView(this); box.setText(tile.title); box.setGravity(Gravity.CENTER); box.setTextColor(Color.WHITE); box.setTextSize(16);
            box.setBackgroundColor(Color.rgb(193, 39, 45));
            card.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)));
        } else {
            ImageView cover = new ImageView(this); cover.setScaleType(ImageView.ScaleType.CENTER_CROP); cover.setBackgroundColor(Color.rgb(30, 30, 34));
            card.addView(cover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)));
            StoreImages.load(client, tile.image, cover, dp(176));
            TextView name = new TextView(this); name.setText(tile.title); name.setTextColor(Color.WHITE); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(0, dp(4), 0, 0); card.addView(name);
            if (tile.inAppPurchases || tile.premium) {
                TextView tag = new TextView(this); tag.setText(tile.premium ? "Paid game" : "In-app purchases"); tag.setTextColor(Color.rgb(255, 180, 90)); tag.setTextSize(11); card.addView(tag);
            }
        }
        card.setOnClickListener(v -> open(tile));
        return card;
    }

    private void open(StoreClient.Tile tile) {
        if (tile.page != null) startActivity(new Intent(this, StoreActivity.class).putExtra(EXTRA_PAGE, tile.page).putExtra(EXTRA_TITLE, tile.title));
        else if (tile.pkg != null && !tile.pkg.isEmpty()) startActivity(new Intent(this, StoreGameActivity.class).putExtra(StoreGameActivity.EXTRA_PKG, tile.pkg));
        else if (!tile.bundleApps.isEmpty()) {
            String[] apps = tile.bundleApps.toArray(new String[0]);
            new AlertDialog.Builder(this).setTitle(tile.title).setItems(apps, (d, which) ->
                    startActivity(new Intent(this, StoreGameActivity.class).putExtra(StoreGameActivity.EXTRA_PKG, apps[which]))).setNegativeButton("Close", null).show();
        }
    }

    private void editServer() {
        EditText input = new EditText(this); input.setInputType(InputType.TYPE_TEXT_VARIATION_URI); input.setSingleLine(true);
        input.setText(prefs(this).getString("server", StoreClient.DEFAULT_SERVER));
        new AlertDialog.Builder(this).setTitle("Community server")
                .setMessage("Address of a stouyapi OUYA server. Default: " + StoreClient.DEFAULT_SERVER)
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    try { prefs(this).edit().putString("server", StoreClient.validateServer(input.getText().toString())).apply(); load(); }
                    catch (IllegalArgumentException bad) { status.setText(bad.getMessage()); }
                })
                .setNeutralButton("Default", (d, w) -> { prefs(this).edit().remove("server").apply(); load(); })
                .setNegativeButton("Cancel", null).show();
    }
}
