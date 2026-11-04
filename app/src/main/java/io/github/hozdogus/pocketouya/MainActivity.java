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
    static final String ACTION_IMPORT_STORE = "io.github.hozdogus.pocketouya.IMPORT_STORE_APK";
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
        /** The game's own OUYA tile (drawable "ouya_icon"), else its Android TV banner, else null. */
        android.graphics.drawable.Drawable tile;
    }

    private final BroadcastReceiver refresh = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadGames(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ConversionRecord.converterVersion=LaunchLogging.appVersion(this);
        setContentView(buildUi());
        registerReceiver(refresh, new IntentFilter(ACTION_REFRESH), Context.RECEIVER_NOT_EXPORTED);
        handleStoreImport(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleStoreImport(intent);
    }

    /** A game downloaded by the Store: only files inside PocketOuya's own store folder are accepted. */
    private void handleStoreImport(Intent intent) {
        if (intent == null || !ACTION_IMPORT_STORE.equals(intent.getAction())) return;
        String path = intent.getStringExtra("apk");
        intent.setAction(null);
        if (path == null) return;
        try {
            File file = new File(path).getCanonicalFile(), dir = StoreGameActivity.downloads(this).getCanonicalFile();
            if (!file.getPath().startsWith(dir.getPath() + File.separator) || !file.isFile()) return;
            ui.post(() -> assessImport(Uri.fromFile(file)));
        } catch (java.io.IOException ignored) {}
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
        LaunchLogging.finish(this);
    }

    // ---------------------------------------------------------------- UI

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // OUYA-console-style home: PLAY / DISCOVER / MANAGE menu, wide game tiles, controller navigation.
    private static final int PLAY = 0, DISCOVER = 1, MANAGE = 2;
    private int section = PLAY;
    private final TextView[] menuItems = new TextView[3];
    private View playPage, managePage;
    private GridView grid;

    private View buildUi() {
        boolean landscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(18), dp(20), dp(12));

        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        String[] names = { "PLAY", "DISCOVER", "MANAGE" };
        for (int i = 0; i < names.length; i++) {
            final int which = i;
            TextView item = new TextView(this);
            item.setText(names[i]);
            item.setTextSize(landscape ? 30 : 22);
            item.setLetterSpacing(0.08f);
            item.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            item.setPadding(dp(10), dp(6), dp(14), dp(6));
            item.setFocusable(true);
            item.setClickable(true);
            item.setOnClickListener(v -> selectSection(which, true));
            item.setOnFocusChangeListener((v, focused) -> { if (focused && which != DISCOVER) selectSection(which, false); else updateMenu(); });
            menuItems[i] = item;
            menu.addView(item);
        }
        if (landscape) {
            View spacer = new View(this);
            menu.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
            TextView brand = new TextView(this);
            brand.setText("PocketOuya");
            brand.setTextColor(0xFF8A8A8A);
            brand.setTextSize(13);
            brand.setPadding(dp(12), 0, 0, dp(4));
            menu.addView(brand);
            root.addView(menu, new LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(menu);
            root.addView(scroll);
        }

        android.widget.FrameLayout content = new android.widget.FrameLayout(this);
        content.setPadding(landscape ? dp(8) : 0, landscape ? dp(6) : dp(12), 0, 0);
        root.addView(content, landscape ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout play = new LinearLayout(this);
        play.setOrientation(LinearLayout.VERTICAL);
        empty = new TextView(this);
        empty.setText("No OUYA games yet. Choose Add game and pick an OUYA game's original .apk file. "
                + "A Bluetooth controller works best: most OUYA games were made for one.");
        empty.setTextColor(0xFFAAAAAA);
        empty.setTextSize(14);
        empty.setPadding(dp(4), 0, 0, dp(12));
        play.addView(empty);
        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(landscape ? 210 : 160));
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(12));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(dp(3), dp(3), dp(3), dp(3));
        grid.setClipToPadding(false);
        GradientDrawable selector = new GradientDrawable();
        selector.setColor(Color.TRANSPARENT);
        selector.setStroke(dp(3), ACCENT);
        selector.setCornerRadius(dp(6));
        grid.setSelector(selector);
        grid.setDrawSelectorOnTop(true);
        adapter = new GamesAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> { if (pos < games.size()) launch(games.get(pos)); else pickApk(); });
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < games.size()) gameOptions(games.get(pos));
            return true;
        });
        play.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        playPage = play;
        content.addView(play);

        android.widget.ScrollView manageScroll = new android.widget.ScrollView(this);
        LinearLayout manage = new LinearLayout(this);
        manage.setOrientation(LinearLayout.VERTICAL);
        manageRow(manage, "Add game", "Convert an OUYA game's .apk so it installs on this phone", this::pickApk);
        manageRow(manage, "APK compatibility doctor", "Scan an APK, see known fixes and prepare a repaired copy",
                () -> startActivity(new Intent(this, ApkDoctorActivity.class)));
        manageRow(manage, "Virtual controller test", "Check the Shizuku virtual controller on this phone",
                () -> startActivity(new Intent(this, VirtualPadTestActivity.class)));
        manageRow(manage, "Global log and export", "Every game launch and crash, saved on this phone",
                () -> startActivity(new Intent(this, GlobalLogActivity.class)));
        TextView version = new TextView(this);
        version.setText("PocketOuya " + ConversionRecord.converterVersion);
        version.setTextColor(0xFF777777);
        version.setTextSize(12);
        version.setPadding(dp(14), dp(14), 0, dp(8));
        manage.addView(version);
        manageScroll.addView(manage);
        managePage = manageScroll;
        content.addView(manageScroll);

        selectSection(section == DISCOVER ? PLAY : section, false);
        return root;
    }

    private void manageRow(LinearLayout parent, String title, String detail, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setFocusable(true);
        row.setClickable(true);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(CARD);
        focused.setStroke(dp(2), ACCENT);
        focused.setCornerRadius(dp(6));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(CARD);
        pressed.setCornerRadius(dp(6));
        states.addState(new int[] { android.R.attr.state_focused }, focused);
        states.addState(new int[] { android.R.attr.state_pressed }, pressed);
        states.addState(new int[0], new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        row.setBackground(states);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        row.addView(t);
        TextView d = new TextView(this);
        d.setText(detail);
        d.setTextColor(0xFF9A9A9A);
        d.setTextSize(13);
        row.addView(d);
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(4));
        parent.addView(row, lp);
    }

    /** DISCOVER opens the store; PLAY and MANAGE switch the page. */
    private void selectSection(int which, boolean clicked) {
        if (which == DISCOVER) {
            if (clicked) startActivity(new Intent(this, StoreActivity.class));
            updateMenu();
            return;
        }
        section = which;
        if (playPage != null) playPage.setVisibility(which == PLAY ? View.VISIBLE : View.GONE);
        if (managePage != null) managePage.setVisibility(which == MANAGE ? View.VISIBLE : View.GONE);
        updateMenu();
    }

    private void updateMenu() {
        for (int i = 0; i < menuItems.length; i++) {
            TextView item = menuItems[i];
            if (item == null) continue;
            item.setTextColor(i == section ? ACCENT : 0xFFCFCFCF);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(6));
            bg.setColor(item.isFocused() ? 0x22FFFFFF : Color.TRANSPARENT);
            item.setBackground(bg);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        setContentView(buildUi());
        loadGames();
    }

    /** OUYA controller: O (BUTTON_A) selects, A (BUTTON_B) goes back, Y or Menu opens a game's options. */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        int key = e.getKeyCode();
        if (key == android.view.KeyEvent.KEYCODE_BUTTON_A)
            return super.dispatchKeyEvent(new android.view.KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(),
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER, e.getRepeatCount(), e.getMetaState(), e.getDeviceId(),
                    e.getScanCode(), e.getFlags(), e.getSource()));
        if (key == android.view.KeyEvent.KEYCODE_BUTTON_B) {
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) onBackPressed();
            return true;
        }
        if ((key == android.view.KeyEvent.KEYCODE_BUTTON_Y || key == android.view.KeyEvent.KEYCODE_MENU) && grid != null && grid.hasFocus()) {
            int pos = grid.getSelectedItemPosition();
            if (e.getAction() == android.view.KeyEvent.ACTION_UP && pos >= 0 && pos < games.size()) gameOptions(games.get(pos));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Keeps the OUYA tile shape (732 x 412) at any column width. */
    private static final class TileFrame extends android.widget.FrameLayout {
        TileFrame(Context c) { super(c); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int w = View.MeasureSpec.getSize(widthSpec);
            int h = w * 412 / 732;
            super.onMeasure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        }
    }

    private final class GamesAdapter extends BaseAdapter {
        @Override public int getCount() { return games.size() + 1; }
        @Override public Object getItem(int i) { return i < games.size() ? games.get(i) : null; }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            TileFrame tile = new TileFrame(MainActivity.this);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            bg.setCornerRadius(dp(6));
            tile.setBackground(bg);
            tile.setClipToOutline(true);
            if (i >= games.size()) {
                GradientDrawable dashed = new GradientDrawable();
                dashed.setColor(0xFF1E1E1E);
                dashed.setCornerRadius(dp(6));
                dashed.setStroke(dp(1), 0xFF666666, dp(6), dp(4));
                tile.setBackground(dashed);
                TextView add = new TextView(MainActivity.this);
                add.setText("+  Add game");
                add.setTextColor(0xFFCFCFCF);
                add.setTextSize(16);
                add.setGravity(Gravity.CENTER);
                tile.addView(add, new android.widget.FrameLayout.LayoutParams(-1, -1));
                return tile;
            }
            Game g = games.get(i);
            ImageView image = new ImageView(MainActivity.this);
            if (g.tile != null) {
                image.setImageDrawable(g.tile);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                tile.addView(image, new android.widget.FrameLayout.LayoutParams(-1, -1));
            } else {
                image.setImageDrawable(g.icon);
                tile.addView(image, new android.widget.FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER_HORIZONTAL | Gravity.TOP));
                ((android.widget.FrameLayout.LayoutParams) image.getLayoutParams()).topMargin = dp(12);
            }
            TextView label = new TextView(MainActivity.this);
            label.setText(g.label);
            label.setTextColor(Color.WHITE);
            label.setTextSize(13);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setPadding(dp(8), dp(4), dp(8), dp(5));
            label.setBackgroundColor(0xAA000000);
            tile.addView(label, new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            return tile;
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
            try {
                android.content.res.Resources res = pm.getResourcesForApplication(ai);
                int id = res.getIdentifier("ouya_icon", "drawable", pkg);
                if (id != 0) g.tile = res.getDrawable(id, null);
            } catch (Exception | OutOfMemoryError ignored) {}
            if (g.tile == null) g.tile = pm.getApplicationBanner(ai);
            found.put(pkg, g);
        } catch (PackageManager.NameNotFoundException ignored) {
            // uninstalled
        }
    }

    private void launch(Game g) {
        LaunchLogging.begin(this,g.label.toString(),g.pkg,g.pkg,"Installed Android game");
        Intent i = getPackageManager().getLaunchIntentForPackage(g.pkg);
        if (i == null) {
            Intent ouya = new Intent(Intent.ACTION_MAIN).addCategory(OUYA_GAME).setPackage(g.pkg);
            List<ResolveInfo> r = getPackageManager().queryIntentActivities(ouya, 0);
            if (!r.isEmpty())
                i = ouya.setClassName(g.pkg, r.get(0).activityInfo.name);
        }
        if (i == null) {
            LaunchLogging.append(this,g.label.toString(),"Launch failed","No launch activity found.");LaunchLogging.finish(this);
            Toast.makeText(this, "Can't find how to start this game.", Toast.LENGTH_LONG).show();
            return;
        }
        try{startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));LaunchLogging.append(this,g.label.toString(),"Launch dispatched","Android accepted the launch request; gameplay success is not established.");}
        catch(RuntimeException error){LaunchLogging.append(this,g.label.toString(),"Launch failed",error.toString());LaunchLogging.finish(this);Toast.makeText(this,"Could not launch game: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private void gameOptions(Game g) {
        new AlertDialog.Builder(this)
                .setTitle(g.label)
                .setItems(new String[] { "Play", "App info", "Uninstall", "Engine test (developer)",
                        "Play with GameMaker engine + touch (beta)", "Capture log + export APK" }, (d, which) -> {
                    if (which == 0) launch(g);
                    else if (which == 3) engineTest(g);
                    else if (which == 4) playWithEngine(g);
                    else if (which == 5) startActivity(new Intent(this,CrashCaptureActivity.class).putExtra("package",g.pkg).putExtra("capture",true));
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
        LaunchLogging.begin(this,g.label.toString(),g.pkg,getPackageName(),"Translated GameMaker engine");
        try {
            String apk = getPackageManager().getApplicationInfo(g.pkg, 0).sourceDir;
            startActivity(new Intent(this, GameActivity.class)
                    .putExtra(GameActivity.EXTRA_APK, apk)
                    .putExtra(GameActivity.EXTRA_PKG, g.pkg)
                    .putExtra(GameActivity.EXTRA_NAME, g.label.toString()));
        } catch (RuntimeException|PackageManager.NameNotFoundException error) {
            LaunchLogging.append(this,g.label.toString(),"Engine launch failed",error.toString());LaunchLogging.finish(this);
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
        LaunchLogging.begin(this,g.label.toString(),g.pkg,getPackageName(),"Engine inspection / boot test");
        showProgress("Running the engine test…\n(this can take up to a minute)");
        new Thread(() -> {
            String report;
            long t0 = System.currentTimeMillis();
            try {
                ApkCompatibility compatibility = ApkCompatibility.inspect(new File(apk));
                report = compatibility.report;
                if (compatibility.canRunEngine) report += "\nGuest engine trace:\n" + Engine.bootTest(apk, save.getAbsolutePath());
            } catch (Throwable t) {
                report = "Engine failed to start: " + t;
            }
            final String text = "Took " + (System.currentTimeMillis() - t0) / 1000.0 + " s\n\n" + report;
            LaunchLogging.append(this,g.label.toString(),"Engine test result",text);
            ui.post(()->LaunchLogging.finish(this));
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
            assessImport(data.getData());
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

    private boolean assessingApk;
    private void assessImport(Uri uri) {
        if(assessingApk)return;
        assessingApk=true;showProgress("Assessing APK and supported fixes…");
        new Thread(()->{
            File cached=new File(getCacheDir(),"assessment-"+java.util.UUID.randomUUID()+".apk");
            try {
                try(java.io.InputStream in=getContentResolver().openInputStream(uri);java.io.OutputStream out=new java.io.FileOutputStream(cached)){
                    byte[] buffer=new byte[65536];long total=0;int count;
                    if(in==null)throw new java.io.IOException("Cannot open selected APK");
                    while((count=in.read(buffer))!=-1){total+=count;if(total>2L*1024*1024*1024)throw new java.io.IOException("APK exceeds 2 GiB assessment limit");out.write(buffer,0,count);}
                }
                ApkRepairAssessment.Result assessment=ApkRepairAssessment.inspect(cached,android.os.Build.VERSION.SDK_INT,android.os.Build.SUPPORTED_ABIS);
                ui.post(()->{if(!isFinishing()&&!isDestroyed())showApkAssessment(uri,assessment);});
            }catch(Exception error){ui.post(()->{if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("APK assessment unavailable").setMessage(error.toString()+"\n\nNo compatibility recommendation or installation was performed. Choose the original complete APK.").setPositiveButton("OK",null).show();});}
            finally {cached.delete();hideProgress();ui.post(()->assessingApk=false);}
        },"apk-repair-assessment").start();
    }
    private void showApkAssessment(Uri uri,ApkRepairAssessment.Result assessment) {
        TextView text=new TextView(this);text.setPadding(dp(20),dp(12),dp(20),dp(12));text.setText(assessment.report);text.setTextIsSelectable(true);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(text);
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("APK assessment and suggested fixes").setView(scroll).setNegativeButton("Cancel",null);
        if(assessment.suggested==ApkRepairAssessment.Route.NONE)dialog.setPositiveButton("All conversion options",(d,w)->chooseImportMode(uri));
        else {
            String label=assessment.suggested==ApkRepairAssessment.Route.EXPERIMENTAL?"Review experiment":assessment.suggested==ApkRepairAssessment.Route.STARTUP?"Review suggested fixes":"Review standard conversion";
            dialog.setPositiveButton(label,(d,w)->{
                if(assessment.suggested==ApkRepairAssessment.Route.EXPERIMENTAL)confirmExperimentalImport(uri);
                else confirmImport(uri,assessment.suggested==ApkRepairAssessment.Route.STARTUP);
            }).setNeutralButton("All conversion options",(d,w)->chooseImportMode(uri));
        }
        dialog.show();
    }

    private void chooseImportMode(Uri uri) {
        new AlertDialog.Builder(this).setTitle("Game conversion")
            .setItems(new String[]{"Android install compatibility + touch", "Startup repairs + touch — native engines / legacy SDKs", "Experimental Unity embedded-runtime repair + touch"},(dialog,which)-> {
                if(which==2)confirmExperimentalImport(uri);
                else confirmImport(uri,which==1);})
            .setNegativeButton("Cancel",null).show();
    }

    private void confirmExperimentalImport(Uri uri) {
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView info=new TextView(this);info.setText("Use the complete original APK. This attempts the ARM-only embedded-runtime repair tested locally with Monocle Man: replace a structurally matched proxy pair with its real runtimes and repair Unity's ARM finalizer. Other APKs with the same supported structure can match.\n\nLocal checks do not prove whole-program safety or phone startup. Unsupported layouts and Thumb-dependent repairs remain unchanged. The report explains the result before installation. Assets are preserved. A matching Unity CPU-description getter receives a null-string fallback.\n\nEvery conversion attempts to add the existing OUYA pad to matching game activities. Input compatibility is reported separately; some games may ignore fallback events. Offline purchase hooks are off for this experiment. Existing saves may matter when replacing an installed game.\n\nOrdinary startup repairs and the APK doctor do not select this experiment.");content.addView(info);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("Experimental Unity runtime repair").setView(scroll)
            .setPositiveButton("Inspect and prepare test",(dialog,which)->importApk(uri,false,true,false,false))
            .setNegativeButton("Cancel",null).show();
    }

    private void confirmImport(Uri uri,boolean runtimeRepair) {
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView explanation=new TextView(this);
        String common="";
        String details;
        if(runtimeRepair)details="Unity 4.3.4f1 / NativeActivity (AD2442 inspected build): repairs text-relocation loading in libmain, Mono and the player. The report should show three matching libraries repaired. Phone startup is not yet verified.\n\nUnity 4.1.1f4 / Abbigale: repairs the inspected real Mono/player runtimes. When found under assets/libs, promotes them to normal Android loading to bypass the legacy embedded loader implicated by the recursive string-error crash. Requires the original complete APK. Launch with these exact runtimes is confirmed by the owner; purchases remain a separate compatibility attempt.\n\nUnity / Mono 4.1.5f1: attempts to fix text-relocation library-loading crashes (matching builds inspected in Muffin Knight).\n\nKnown Unity loader proxies (including Abbigale): fixes text-relocation loading in the small Mono/player loaders. Their embedded runtimes may still need a separate repair; the report identifies loader-only results.\n\nMarmalade / Vector 1.0.7: fixes text-relocation loading and the null JNI surface-buffer release. Launch and touch input are confirmed with the inspected Vector 1.0.7 build.\n\nFrima / Space Shooter 1.1.3: repairs its relocated initializer.\n\nAdobe AIR: repairs code-literal relocations in the inspected Core/native-extension builds. Old private Android media dependencies may still block startup. Zombie Toaster must match these library fingerprints to receive this repair.\n\nNative engine rewrites still require exact supported library builds. Shared rules also attempt stale TEXTREL declaration cleanup when all supported loader writes are proven writable, and a null-context shutdown guard for matching OUYA content SDK methods. Actual code relocations and unavailable services remain unresolved by those rules. The report names each matched fix. This is not a universal engine repair. Final Fantasy III startup is not covered. Unity loader proxies require their real runtimes under assets/libs in the original APK. Use the original complete APK.\n\n"+"Every conversion attempts to embed the OUYA pad into matching game activities; native repair and touch compatibility are reported separately. "+"A successful launch or input response is not guaranteed. Offline purchases are controlled by the checkbox above and reported separately.";
        else details="Every conversion attempts to add the on-screen OUYA pad using native Unity/Marmalade input when available, otherwise Android gamepad/keyboard events. Games requiring a physical controller may ignore the fallback.\n\n"+"No native startup repair selected. The conversion report identifies supported input routes.";
        if(runtimeRepair)details+="\n\nAdobe AIR diagnostics: matching loaders also log their original hidden library-loading error. This adds information, not native compatibility. After a failed launch, export the full global game log; the crash-only report may contain just the later nativeSurfaceCreated error.";
        if(runtimeRepair)details+="\n\nShared startup repairs also cover old implicit service requests (e.g. MOGA, Google Analytics), null-cursor OUYA game-data cleanup and public NDK library build paths. The conversion report identifies matches and remaining unsupported problems.";
        android.widget.CheckBox keep=new android.widget.CheckBox(this);
        keep.setText("Keep the game's original Android version (installs through Shizuku)");
        keep.setChecked(false);content.addView(keep);
        TextView keepInfo=new TextView(this);
        keepInfo.setText("Needed for Adobe AIR and other old engines whose libraries rewrite their own code when loading: Android only allows that for apps built for old versions, but Android 14 installs those only through a shell install. Requires Shizuku running during the install; you can turn Developer options off afterwards.\nChecking this game's libraries…\n");
        content.addView(keepInfo);
        android.widget.CheckBox low=new android.widget.CheckBox(this);
        low.setText("Experiment: keep the game's memory below 2 GB");
        low.setChecked(false);content.addView(low);
        TextView lowInfo=new TextView(this);
        lowInfo.setText("For 32-bit games that crash inside their own engine on modern phones (being tested with Soul Fjord). The OUYA kept a game's memory below 3 GB; on a 64-bit phone it can reach 4 GB, which some old engines mishandle. When ticked, a small PocketOuya helper starts first and blocks the unused memory range above 2 GB (3 GB if room is short). The game's own code and libraries are not changed. Its result appears in the global game log under PocketOuyaLowAddr. Leave unticked unless testing.\n");
        content.addView(lowInfo);
        new Thread(()->{
            String needs=textRelocationLibrary(uri);
            ui.post(()->{
                String base="Needed for Adobe AIR and other old engines whose libraries rewrite their own code when loading: Android only allows that for apps built for old versions, but Android 14 installs those only through a shell install. Requires Shizuku running during the install; you can turn Developer options off afterwards.\n";
                if(needs!=null){keep.setChecked(true);keepInfo.setText(base+"Ticked automatically: "+needs+" rewrites its own code when loading.\n");}
                else keepInfo.setText(base+"Not needed for this game: no such libraries found.\n");
            });
        },"scan-text-relocations").start();
        explanation.setText(common+details);content.addView(explanation);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(content);
        new AlertDialog.Builder(this).setTitle(runtimeRepair?"Startup repairs":"Conversion options").setView(scroll)
            .setPositiveButton("Inspect and convert",(dialog,which)->importApk(uri,runtimeRepair,false,keep.isChecked(),low.isChecked()))
            .setNegativeButton("Cancel",null).show();
    }
    /** First ARM native library in the APK that declares text relocations, or null. */
    private String textRelocationLibrary(Uri uri){
        try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(getContentResolver().openInputStream(uri)))){
            for(java.util.zip.ZipEntry e;(e=zip.getNextEntry())!=null;){
                String n=e.getName();if(!n.matches("lib/armeabi(-v7a)?/[^/]+\\.so"))continue;
                java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1<<16];long total=0;
                for(int r;(r=zip.read(buffer))>0;){total+=r;if(total>32L*1024*1024)break;out.write(buffer,0,r);}
                if(total<=32L*1024*1024&&NativeStartupChecks.hasTextRelocations(out.toByteArray()))return n.substring(n.lastIndexOf('/')+1);
            }
        }catch(Exception ignored){}
        return null;
    }
    private void installConverted(File signed,String pkg,boolean keepTarget){
        if(keepTarget){
            Set<String> imported=new HashSet<>(prefs().getStringSet("imported",new HashSet<>()));
            imported.add(pkg);prefs().edit().putStringSet("imported",imported).apply();
            showProgress("Installing through Shizuku…");
            ShizukuInstaller.install(this,signed,(ok,message)->{
                hideProgress();signed.delete();signed.getParentFile().delete();
                ui.post(()->{
                    if(ok){Toast.makeText(this,"Installed with its original Android version",Toast.LENGTH_LONG).show();sendBroadcast(new Intent(ACTION_REFRESH).setPackage(getPackageName()));}
                    else new AlertDialog.Builder(this).setTitle("Shizuku install failed").setMessage(message+"\n\nIf the game was installed before with a different signature, uninstall it first.").setPositiveButton("OK",null).show();
                });
            });
            return;
        }
        installConverted(signed,pkg);
    }
    private void installConverted(File signed,String pkg){
        new Thread(()->{
            try {
                Set<String> imported=new HashSet<>(prefs().getStringSet("imported",new HashSet<>()));
                imported.add(pkg);prefs().edit().putStringSet("imported",imported).apply();
                showProgress("Installing…");GameInstaller.install(this,signed);hideProgress();
            }catch(Exception error){hideProgress();ui.post(()->new AlertDialog.Builder(this).setTitle("Installation failed").setMessage(error.toString()).setPositiveButton("OK",null).show());}
            finally {signed.delete();signed.getParentFile().delete();}
        },"install-conversion").start();
    }

    private void importApk(Uri uri, boolean runtimeRepair, boolean experimental, boolean keepTarget, boolean lowAddress) {
        showProgress("Copying the game…");
        new Thread(() -> {
            File dir = new File(getCacheDir(), "import-"+java.util.UUID.randomUUID());
            File original = new File(dir, "original.apk");
            File patched = new File(dir, "patched.apk");
            File signed = new File(dir, "signed.apk");
            try {
                dir.mkdirs();
                ApkRebuilder.copy(getContentResolver().openInputStream(uri), original);

                showProgress("Updating it for this version of Android…");
                byte[] bridge=null;
                try(java.io.InputStream input=getResources().openRawResource(R.raw.ouya_purchase_bridge)) {
                    bridge=ApkRebuilder.readAll(input);
                }
                byte[] lowAddressLibrary=null;
                if(lowAddress)try(java.io.InputStream input=getResources().openRawResource(R.raw.pocketouya_lowaddr)) {
                    lowAddressLibrary=ApkRebuilder.readAll(input);
                }
                byte[] glesShim=null;
                if(runtimeRepair)try(java.io.InputStream input=getResources().openRawResource(R.raw.pocketouya_gles)) {
                    glesShim=ApkRebuilder.readAll(input);
                }
                ManifestPatcher.Result r = ApkRebuilder.prepareForInstall(original,patched,bridge,runtimeRepair,experimental,keepTarget?ManifestPatcher.KEEP_TARGET:ModernApkDoctor.minimumTarget(android.os.Build.VERSION.SDK_INT),lowAddressLibrary,glesShim);
                original.delete();

                showProgress("Signing…");
                GameSigner.sign(this, patched, signed);
                patched.delete();

                hideProgress();
                if(!r.compatibilityReport.isEmpty())ui.post(()->new AlertDialog.Builder(this)
                    .setTitle("Conversion compatibility report").setMessage(r.compatibilityReport)
                    .setPositiveButton("Install",(dialog,which)->installConverted(signed,r.packageName,keepTarget))
                    .setNegativeButton("Cancel",(dialog,which)->{signed.delete();dir.delete();}).setOnCancelListener(dialog->{signed.delete();dir.delete();}).show());
                else installConverted(signed,r.packageName,keepTarget);
            } catch (Throwable t) {
                signed.delete();
                hideProgress();
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                ui.post(() -> new AlertDialog.Builder(this).setTitle("Couldn't add this game")
                        .setMessage(msg).setPositiveButton(android.R.string.ok, null).show());
            } finally {
                original.delete();
                patched.delete();
                if(!signed.exists())dir.delete();
            }
        }, "import").start();
    }
}
