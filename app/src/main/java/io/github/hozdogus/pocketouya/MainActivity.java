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
        LaunchLogging.finish(this);
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
        Button logs=new Button(this);logs.setText("GLOBAL LOG / EXPORT");logs.setOnClickListener(v->startActivity(new Intent(this,GlobalLogActivity.class)));root.addView(logs);

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
                        "Play with GameMaker engine + touch (beta)", "Capture crash log" }, (d, which) -> {
                    if (which == 0) launch(g);
                    else if (which == 3) engineTest(g);
                    else if (which == 4) playWithEngine(g);
                    else if (which == 5) startActivity(new Intent(this,CrashCaptureActivity.class).putExtra("package",g.pkg));
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
            chooseImportMode(data.getData());
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

    private void chooseImportMode(Uri uri) {
        new AlertDialog.Builder(this).setTitle("Game conversion")
                .setItems(new String[]{"Android install compatibility only", "Touch controls — OUYA / Android input", "Offline purchases — OUYA Unity SDK", "Touch + OUYA Unity offline purchases", "Unity / Marmalade — startup repairs", "Unity / Marmalade — startup repairs + touch"}, (d,which)->{
                    if(which==0)importApk(uri,false,false);
                    else if(which==4||which==5)new AlertDialog.Builder(this).setTitle("Unity / Marmalade startup repairs")
                        .setMessage("Unity / Mono 4.1.5f1: attempts to fix text-relocation library-loading crashes (matching builds inspected in Muffin Knight).\n\nMarmalade / Vector 1.0.7: fixes text-relocation loading and the null JNI surface-buffer release. Launch and touch input are confirmed with the inspected Vector 1.0.7 build.\n\nOnly exact supported library builds are changed; the report names each matched fix. This is not a repair for every Unity or Marmalade game. Adobe AIR and Final Fantasy III startup are not covered. Use the original complete APK.\n\n"+(which==5?"This also embeds the OUYA touch pad into matching game activities; native repair and touch compatibility are reported separately. ":"This mode does not add controls. ")+"A successful launch or input response is not guaranteed. No purchase changes are included.")
                        .setPositiveButton("Convert",(confirm,w)->importApk(uri,which==5,false,true)).setNegativeButton("Cancel",null).show();
                    else new AlertDialog.Builder(this).setTitle("Best-effort compatibility")
                        .setMessage("Touch input: adds an on-screen OUYA layout. Uses OUYA Unity native handlers or Marmalade key callbacks when available, otherwise Android gamepad/keyboard events. Games requiring physical or analog input may ignore the fallback.\n\nOffline purchases: only the matching OUYA Unity SDK can receive local receipts; no payment or real account purchase occurs. Other billing systems and custom/native checks are unsupported.\n\nThese options do not repair native startup crashes. The report identifies the supported input/purchase route. Use the original complete APK.")
                        .setPositiveButton("Inspect and convert",(confirm,w)->importApk(uri,which==1||which==3,which==2||which==3))
                        .setNegativeButton("Cancel",null).show();
                }).setNegativeButton("Cancel",null).show();
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

    private void importApk(Uri uri, boolean touch, boolean offlinePurchases) {
        importApk(uri,touch,offlinePurchases,false);
    }
    private void importApk(Uri uri, boolean touch, boolean offlinePurchases, boolean runtimeRepair) {
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
                if(touch||offlinePurchases)try(java.io.InputStream input=getResources().openRawResource(R.raw.ouya_purchase_bridge)) {
                    bridge=ApkRebuilder.readAll(input);
                }
                ManifestPatcher.Result r = ApkRebuilder.rebuild(original, patched, bridge, touch, offlinePurchases,runtimeRepair);
                original.delete();

                showProgress("Signing…");
                GameSigner.sign(this, patched, signed);
                patched.delete();

                hideProgress();
                if(!r.compatibilityReport.isEmpty())ui.post(()->new AlertDialog.Builder(this)
                    .setTitle("Conversion compatibility report").setMessage(r.compatibilityReport)
                    .setPositiveButton("Install",(dialog,which)->installConverted(signed,r.packageName))
                    .setNegativeButton("Cancel",(dialog,which)->{signed.delete();dir.delete();}).setOnCancelListener(dialog->{signed.delete();dir.delete();}).show());
                else installConverted(signed,r.packageName);
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
