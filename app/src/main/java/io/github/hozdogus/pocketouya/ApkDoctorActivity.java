package io.github.hozdogus.pocketouya;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Scan, review, prepare, export/install. No APK code executes during inspection. */
public final class ApkDoctorActivity extends Activity {
    private static final String PREPARATION_VERSION="platform-crash-rules-v3";
    private static final int PICK=71,SAVE_APK=72,SAVE_REPORT=73;
    private TextView text;private Button pick,prepare,export,install,saveReport;
    private File dir;private volatile boolean busy;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);ConversionRecord.converterVersion=LaunchLogging.appVersion(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(20,16,20,12);
        pick=button(root,"SELECT APK AND SCAN",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),PICK));
        prepare=button(root,"PREPARE SUPPORTED FIXES",this::prepare);
        LinearLayout row=new LinearLayout(this);root.addView(row);
        export=button(row,"EXPORT APK",()->save(SAVE_APK,"application/vnd.android.package-archive","converted.apk"));
        install=button(row,"INSTALL",this::install);
        saveReport=button(root,"EXPORT REPORT",()->save(SAVE_REPORT,"text/plain","APK-compatibility-report.txt"));
        text=new TextView(this);text.setTextIsSelectable(true);text.setTextSize(13);
        ScrollView scroll=new ScrollView(this);scroll.addView(text);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        if(state!=null){String name=state.getString("directory");if(name!=null&&name.matches("apk-doctor-[a-f0-9-]{36}"))dir=new File(getCacheDir(),name);}
        render();
    }
    private Button button(LinearLayout root,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->action.run());root.addView(b);return b;}
    private File file(String name){return new File(dir,name);}
    private boolean scanned(){return dir!=null&&file("original.apk").isFile()&&file("report.txt").isFile();}
    private boolean ready(){try{return scanned()&&file("ready").isFile()&&file("signed.apk").isFile()&&readText(file("ready")).equals(PREPARATION_VERSION);}catch(IOException e){return false;}}
    private void render(){
        pick.setEnabled(!busy);prepare.setEnabled(!busy&&scanned());export.setEnabled(!busy&&ready());install.setEnabled(!busy&&ready());saveReport.setEnabled(!busy&&scanned());
        if(!busy){try{text.setText(scanned()?readText(file("report.txt")):"Select an original APK to inspect it for this phone. Nothing is installed automatically. Supported fixes produce a separately signed APK; unresolved issues stay visible. Scan results do not prove the APK is safe or that it will run. Export results before leaving this screen; working copies use temporary app storage.");}catch(Exception e){text.setText("Saved report unavailable. Select the APK again.");}}
    }
    private interface Work{void run()throws Exception;}
    private void job(String message,Work action){
        if(busy)return;busy=true;render();text.setText(message);
        new Thread(()->{String error=null;try{action.run();}catch(Exception e){error=e.getMessage()==null?e.toString():e.getMessage();}
            final String failure=error;runOnUiThread(()->{busy=false;if(isDestroyed()){if(isFinishing())clean(dir);return;}render();if(failure!=null)new AlertDialog.Builder(this).setTitle("APK doctor").setMessage(failure).setPositiveButton("OK",null).show();});
        },"apk-doctor").start();
    }
    private void scan(Uri uri){job("Copying and inspecting APK…",()->{
        clean(dir);dir=new File(getCacheDir(),"apk-doctor-"+UUID.randomUUID());if(!dir.mkdirs())throw new IOException("Cannot create inspection directory");
        try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file("original.apk"))){copy(in,out,2L*1024*1024*1024);}
        String report=ModernApkDoctor.inspect(file("original.apk"),Build.VERSION.SDK_INT,Build.SUPPORTED_ABIS);
        writeText(file("report.txt"),report);
    });}
    private void prepare(){new AlertDialog.Builder(this).setTitle("Prepare a converted copy?")
        .setMessage("This raises an old target where supported, applies exact native-library repairs, and signs a new APK with PocketOuya's key. It does not resolve every reported issue. The publisher's original signing identity cannot be retained. Existing updates or signature checks may fail. On-screen touch controls are attempted automatically using supported input routes; the report explains any unsupported layouts. No purchase hooks are added.")
        .setPositiveButton("Prepare",(d,w)->job("Preparing and verifying converted APK…",()->{
            String before=ModernApkDoctor.inspect(file("original.apk"),Build.VERSION.SDK_INT,Build.SUPPORTED_ABIS);
            file("ready").delete();file("signed.apk").delete();
            writeText(file("report.txt"),before);
            try{
                byte[] bridge;try(InputStream input=getResources().openRawResource(R.raw.ouya_purchase_bridge)){bridge=ApkRebuilder.readAll(input);}
                ManifestPatcher.Result r=ApkRebuilder.prepareForInstall(file("original.apk"),file("patched.apk"),bridge,true,false,ModernApkDoctor.minimumTarget(Build.VERSION.SDK_INT));
                GameSigner.sign(this,file("patched.apk"),file("signed.apk"));
                if(!new com.android.apksig.ApkVerifier.Builder(file("signed.apk")).build().verify().isVerified())throw new IOException("Converted APK signature verification failed");
                String after=ModernApkDoctor.inspect(file("signed.apk"),Build.VERSION.SDK_INT,Build.SUPPORTED_ABIS);
                writeText(file("report.txt"),"PREPARED COPY — signature verification passed; launch not tested.\nPackage: "+r.packageName+"\n\nApplied conversion results:\n"+r.compatibilityReport+"\n\nPOST-CONVERSION INSPECTION\n"+after+"\n\nORIGINAL INSPECTION\n"+before);
                writeText(file("package.txt"),r.packageName);writeText(file("ready"),PREPARATION_VERSION);
            }catch(Exception e){file("ready").delete();file("signed.apk").delete();throw e;}
            finally{file("patched.apk").delete();}
        })).setNegativeButton("Cancel",null).show();}
    private void save(int request,String type,String name){startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type).putExtra(Intent.EXTRA_TITLE,name),request);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();if(request==PICK){scan(uri);return;}
        if(request!=SAVE_APK&&request!=SAVE_REPORT)return;
        if(!scanned()||(request==SAVE_APK&&!ready()))return;
        File source=file(request==SAVE_APK?"signed.apk":"report.txt");job("Exporting…",()->{
            try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(uri,"wt")){copy(in,out,Long.MAX_VALUE);}
            runOnUiThread(()->Toast.makeText(this,"Export complete",Toast.LENGTH_LONG).show());
        });
    }
    private void install(){
        if(!ready())return;
        if(!getPackageManager().canRequestPackageInstalls()){new AlertDialog.Builder(this).setTitle("Allow APK installation").setMessage("Enable Allow from this source, return here, then tap Install again.").setPositiveButton("Settings",(d,w)->startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())))).setNegativeButton("Cancel",null).show();return;}
        job("Handing prepared APK to Android…",()->{
            String pkg=readText(file("package.txt"));
            GameInstaller.install(this,file("signed.apk"));
            android.content.SharedPreferences p=getSharedPreferences("library",MODE_PRIVATE);Set<String> imported=new HashSet<>(p.getStringSet("imported",Collections.emptySet()));imported.add(pkg);p.edit().putStringSet("imported",imported).apply();
        });
    }
    private static String readText(File source)throws IOException{
        try(InputStream in=new FileInputStream(source);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int total=0;
            for(int n;(n=in.read(b))!=-1;){total+=n;if(total>4*1024*1024)throw new IOException("Saved report exceeds read limit");out.write(b,0,n);}
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static void writeText(File destination,String value)throws IOException{
        try(Writer out=new OutputStreamWriter(new FileOutputStream(destination),StandardCharsets.UTF_8)){out.write(value);}
    }
    private static void copy(InputStream in,OutputStream out,long limit)throws IOException{
        if(in==null||out==null)throw new IOException("Document provider did not open the file");byte[] b=new byte[65536];long total=0;
        for(int n;(n=in.read(b))!=-1;){total+=n;if(total>limit)throw new IOException("APK exceeds the 2 GiB input limit");out.write(b,0,n);}
    }
    private static void clean(File dir){if(dir==null)return;File[] files=dir.listFiles();if(files!=null)for(File f:files)f.delete();dir.delete();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(dir!=null)state.putString("directory",dir.getName());}
    @Override protected void onDestroy(){if(isFinishing()&&!busy)clean(dir);super.onDestroy();}
}
