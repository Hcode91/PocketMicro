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
        ConversionRecord.converterVersion=LaunchLogging.appVersion(this);
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
        Button doctor=new Button(this);doctor.setText("APK COMPATIBILITY DOCTOR");doctor.setOnClickListener(v->startActivity(new Intent(this,ApkDoctorActivity.class)));root.addView(doctor);
        Button purchases=new Button(this);purchases.setText("OFFLINE PURCHASE OPTIONS");purchases.setOnClickListener(v->showPurchaseOptions());root.addView(purchases);
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

    private static final String OFFLINE_DEFAULT = "offline_purchases_default_v1";

    private void showPurchaseOptions() {
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));
        android.widget.CheckBox enabled=new android.widget.CheckBox(this);
        enabled.setText("Try offline purchases by default for Add game conversions");
        enabled.setChecked(prefs().getBoolean(OFFLINE_DEFAULT,false));content.addView(enabled);
        TextView info=new TextView(this);
        info.setText("This preselects offline purchases whenever you use Add game, including conversions with engine startup repairs and touch controls. The APK doctor adds controls and startup repairs but does not use this purchase default. You can turn it off for an individual conversion.\n\nMatching OUYA Unity games receive local product and purchase responses. Use the game's own purchase/unlock button; successful local purchases are remembered in that game's data. No payment or server is used by this compatibility feature.\n\nOther billing APIs, server-only content and custom checks may not work. The conversion report says whether support was added or the purchase checks stayed unchanged. This does not make every game fully offline; original game code may still access the network.\n\nAlready installed games need reconverting from their original complete APKs. Changing this setting does not alter existing games. Clearing game data or uninstalling deletes local receipts.");content.addView(info);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("Global offline purchase default").setView(scroll)
            .setPositiveButton("Save",(dialog,which)->{
                if(prefs().edit().putBoolean(OFFLINE_DEFAULT,enabled.isChecked()).commit())Toast.makeText(this,"Default saved. Reconvert original APKs to apply it.",Toast.LENGTH_LONG).show();
                else Toast.makeText(this,"Could not save purchase default",Toast.LENGTH_LONG).show();
            }).setNegativeButton("Cancel",null).show();
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
                else confirmImport(uri,false,assessment.suggested==ApkRepairAssessment.Route.STARTUP);
            }).setNeutralButton("All conversion options",(d,w)->chooseImportMode(uri));
        }
        dialog.show();
    }

    private void chooseImportMode(Uri uri) {
        new AlertDialog.Builder(this).setTitle("Game conversion")
            .setItems(new String[]{"Android install compatibility + touch", "Offline purchases + touch — OUYA Unity SDK", "Startup repairs + touch — native engines / legacy SDKs", "Experimental Unity embedded-runtime repair + touch"},(dialog,which)-> {
                if(which==3)confirmExperimentalImport(uri);
                else confirmImport(uri,which==1,which==2);})
            .setNegativeButton("Cancel",null).show();
    }

    private void confirmExperimentalImport(Uri uri) {
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView info=new TextView(this);info.setText("Use the complete original APK. This attempts the ARM-only embedded-runtime repair tested locally with Monocle Man: replace a structurally matched proxy pair with its real runtimes and repair Unity's ARM finalizer. Other APKs with the same supported structure can match.\n\nLocal checks do not prove whole-program safety or phone startup. Unsupported layouts and Thumb-dependent repairs remain unchanged. The report explains the result before installation. Assets are preserved. A matching Unity CPU-description getter receives a null-string fallback.\n\nEvery conversion attempts to add the existing OUYA pad to matching game activities. Input compatibility is reported separately; some games may ignore fallback events. Offline purchase hooks are off for this experiment. Existing saves may matter when replacing an installed game.\n\nOrdinary startup repairs and the APK doctor do not select this experiment.");content.addView(info);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("Experimental Unity runtime repair").setView(scroll)
            .setPositiveButton("Inspect and prepare test",(dialog,which)->importApk(uri,false,false,true))
            .setNegativeButton("Cancel",null).show();
    }

    private void confirmImport(Uri uri,boolean offlineRequested,boolean runtimeRepair) {
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));
        android.widget.CheckBox offline=new android.widget.CheckBox(this);
        offline.setText("Try offline purchases — supported OUYA Unity SDK");
        offline.setChecked(offlineRequested||prefs().getBoolean(OFFLINE_DEFAULT,false));content.addView(offline);
        TextView explanation=new TextView(this);
        String common="Offline purchases are a best-effort compatibility attempt, not a guarantee of an unlock or fully offline gameplay. Compatible APIs use local receipts without a payment or server. Unsupported purchase checks stay unchanged. Use the game's own purchase/unlock button after conversion. Local receipts survive app restarts but are removed by clearing game data or uninstalling.\n\nThe checkbox applies only to this conversion; your global default stays the same. Use the original complete APK.\n\n";
        String details;
        if(runtimeRepair)details="Unity 4.3.4f1 / NativeActivity (AD2442 inspected build): repairs text-relocation loading in libmain, Mono and the player. The report should show three matching libraries repaired. Phone startup is not yet verified.\n\nUnity 4.1.1f4 / Abbigale: repairs the inspected real Mono/player runtimes. When found under assets/libs, promotes them to normal Android loading to bypass the legacy embedded loader implicated by the recursive string-error crash. Requires the original complete APK. Launch with these exact runtimes is confirmed by the owner; purchases remain a separate compatibility attempt.\n\nUnity / Mono 4.1.5f1: attempts to fix text-relocation library-loading crashes (matching builds inspected in Muffin Knight).\n\nKnown Unity loader proxies (including Abbigale): fixes text-relocation loading in the small Mono/player loaders. Their embedded runtimes may still need a separate repair; the report identifies loader-only results.\n\nMarmalade / Vector 1.0.7: fixes text-relocation loading and the null JNI surface-buffer release. Launch and touch input are confirmed with the inspected Vector 1.0.7 build.\n\nFrima / Space Shooter 1.1.3: repairs its relocated initializer.\n\nAdobe AIR: repairs code-literal relocations in the inspected Core/native-extension builds. Old private Android media dependencies may still block startup. Zombie Toaster must match these library fingerprints to receive this repair.\n\nNative engine rewrites still require exact supported library builds. Shared rules also attempt stale TEXTREL declaration cleanup when all supported loader writes are proven writable, and a null-context shutdown guard for matching OUYA content SDK methods. Actual code relocations and unavailable services remain unresolved by those rules. The report names each matched fix. This is not a universal engine repair. Final Fantasy III startup is not covered. Unity loader proxies require their real runtimes under assets/libs in the original APK. Use the original complete APK.\n\n"+"Every conversion attempts to embed the OUYA pad into matching game activities; native repair and touch compatibility are reported separately. "+"A successful launch or input response is not guaranteed. Offline purchases are controlled by the checkbox above and reported separately.";
        else details="Every conversion attempts to add the on-screen OUYA pad using native Unity/Marmalade input when available, otherwise Android gamepad/keyboard events. Games requiring a physical controller may ignore the fallback.\n\n"+"No native startup repair selected. The conversion report identifies supported input and purchase routes.";
        if(runtimeRepair)details+="\n\nAdobe AIR diagnostics: matching loaders also log their original hidden library-loading error. This adds information, not native compatibility. After a failed launch, export the full global game log; the crash-only report may contain just the later nativeSurfaceCreated error.";
        if(runtimeRepair)details+="\n\nShared startup repairs also cover supported legacy MOGA/Google Analytics service intents, null-cursor OUYA game-data cleanup and public NDK library build paths. The conversion report identifies matches and remaining unsupported problems.";
        explanation.setText(common+details);content.addView(explanation);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(content);
        new AlertDialog.Builder(this).setTitle(runtimeRepair?"Startup repairs":"Conversion options").setView(scroll)
            .setPositiveButton("Inspect and convert",(dialog,which)->importApk(uri,offline.isChecked(),runtimeRepair,false))
            .setNegativeButton("Cancel",null).show();
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

    private void importApk(Uri uri, boolean offlinePurchases, boolean runtimeRepair, boolean experimental) {
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
                ManifestPatcher.Result r = ApkRebuilder.prepareForInstall(original,patched,bridge,offlinePurchases,runtimeRepair,experimental,ModernApkDoctor.minimumTarget(android.os.Build.VERSION.SDK_INT));
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
