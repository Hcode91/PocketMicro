package io.github.hozdogus.pocketouya;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.provider.DocumentsContract;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import rikka.shizuku.Shizuku;

/** On-phone crash capture and scoped bug-report import. Nothing is uploaded. */
public final class CrashCaptureActivity extends Activity {
    private static final int IMPORT=41,SAVE=42,ACCESS=43,EXPORT=44;
    private String pkg;
    private volatile String report="No capture yet. No crash has been verified.";
    private volatile boolean captureStarted,exporting;
    private TextView output,status;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private IBinder remote;
    private boolean bound,pendingLaunch,journalLaunch;
    private Shizuku.UserServiceArgs args;
    private final Shizuku.OnRequestPermissionResultListener permission=(request,result)->{if(request==ACCESS){if(result==PackageManager.PERMISSION_GRANTED)bind();else message("Shizuku access was denied. Bug-report import remains available.");}};
    private final Shizuku.OnBinderReceivedListener received=()->{if(!isFinishing())bind();};
    private final Shizuku.OnBinderDeadListener dead=()->{remote=null;bound=false;message("Shizuku disconnected. Start it again to capture logs.");};
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){if(isFinishing()||isDestroyed())return;remote=binder;message("Ready to capture this game's crash buffer.");if(pendingLaunch){pendingLaunch=false;startCapture();}}
        public void onServiceDisconnected(ComponentName name){remote=null;bound=false;message("Capture service disconnected.");}
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);pkg=getIntent().getStringExtra("package");
        File[] leftovers=getCacheDir().listFiles((dir,name)->name.startsWith("crash-import-")&&name.endsWith(".tmp"));
        if(leftovers!=null)for(File file:leftovers)file.delete();
        if(!CrashLogFilter.validPackage(pkg)){finish();return;}
        if(state!=null)report=state.getString("report",report);
        int serviceVersion=1;try{serviceVersion=(int)getPackageManager().getPackageInfo(getPackageName(),0).getLongVersionCode();}catch(PackageManager.NameNotFoundException ignored){}
        args=new Shizuku.UserServiceArgs(new ComponentName(this,CrashLogService.class)).daemon(false).tag("game_crash_capture").processNameSuffix("crash_capture").version(serviceVersion).debuggable(false);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(20,16,20,12);
        TextView title=new TextView(this);title.setText("Logs + APK: "+gameName());title.setTextSize(18);root.addView(title);
        TextView explanation=new TextView(this);explanation.setText("Automatic capture uses optional Shizuku access to read Android's crash buffer for up to 60 seconds. Start capture, let the game crash, then return here. Export log + APK saves the displayed capture and installed APK to a folder you choose; files over 20 MB are split into numbered parts. Return after the crash to collect it. Without Shizuku, import a Samsung bug-report ZIP or text file. Review the extracted report before copying or saving; nothing is uploaded.");root.addView(explanation);
        status=new TextView(this);root.addView(status);
        row(root,new String[]{"Shizuku access","Setup guide"},new Runnable[]{this::access,()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/guide/setup/")))});
        row(root,new String[]{"Capture and launch","Stop / show log"},new Runnable[]{this::startCapture,()->snapshot(true)});
        row(root,new String[]{"Import bug report","Copy","Save"},new Runnable[]{this::importReport,()->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Game crash report",report));Toast.makeText(this,"Report copied",Toast.LENGTH_SHORT).show();},this::saveReport});
        row(root,new String[]{"Export log + APK"},new Runnable[]{this::exportSupport});
        output=new TextView(this);output.setText(report);output.setTextSize(11);output.setTypeface(Typeface.MONOSPACE);output.setTextIsSelectable(true);
        ScrollView scroll=new ScrollView(this);scroll.addView(output);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        Shizuku.addRequestPermissionResultListener(permission,ui);Shizuku.addBinderReceivedListenerSticky(received,ui);Shizuku.addBinderDeadListener(dead,ui);
        message("Choose automatic capture or import an Android bug report.");
        if(state==null&&getIntent().getBooleanExtra("capture",false))ui.post(this::startCapture);
    }
    private void row(LinearLayout root,String[] names,Runnable[] actions){LinearLayout row=new LinearLayout(this);for(int i=0;i<names.length;i++){Button b=new Button(this);b.setText(names[i]);b.setTextSize(11);final Runnable action=actions[i];b.setOnClickListener(v->action.run());row.addView(b,new LinearLayout.LayoutParams(0,-2,1));}root.addView(row);}
    private void message(String text){ui.post(()->{if(!isDestroyed())status.setText(text);});}
    private void showReport(String text){report=text;ui.post(()->{if(!isDestroyed())output.setText(text);});}
    private void access(){
        try{
            if(!Shizuku.pingBinder()){message("Install/start Shizuku using wireless debugging, then return here. The Setup guide explains phone-only setup.");return;}
            if(Shizuku.getVersion()<13){message("Update Shizuku to version 13 or later.");return;}
            if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED)bind();else Shizuku.requestPermission(ACCESS);
        }catch(RuntimeException error){message("Shizuku unavailable: "+error.getMessage());}
    }
    private void bind(){
        try{if(bound||!Shizuku.pingBinder()||Shizuku.getVersion()<13||Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)return;bound=true;Shizuku.bindUserService(args,connection);}
        catch(RuntimeException error){bound=false;message("Cannot connect to capture service: "+error.getMessage());}
    }
    private Parcel call(int code,String argument)throws RemoteException{
        IBinder binder=remote;if(binder==null)throw new RemoteException("Shizuku capture service is not connected");
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken(CrashLogService.DESCRIPTOR);if(argument!=null)data.writeString(argument);if(!binder.transact(code,data,reply,0))throw new RemoteException("Unsupported capture operation");reply.readException();return reply;}
        catch(RuntimeException|RemoteException error){reply.recycle();throw error;}finally{data.recycle();}
    }
    private void startCapture(){
        if(exporting){message("Wait for the support export to finish.");return;}
        if(remote==null){pendingLaunch=true;access();return;}
        captureStarted=false;message("Starting a 60-second capture…");
        showReport("Starting a new capture. No crash has been verified for this attempt.");
        new Thread(()->{
            try{Parcel result=call(CrashLogService.START,pkg);result.recycle();captureStarted=true;ui.post(()->{
                if(isFinishing()||isDestroyed())return;
                Intent game=getPackageManager().getLaunchIntentForPackage(pkg);
                if(game==null){game=new Intent(Intent.ACTION_MAIN).addCategory("tv.ouya.intent.category.GAME").setPackage(pkg);if(game.resolveActivity(getPackageManager())==null){message("Capture started, but no game launcher was found.");return;}}
                String title=pkg;try{title=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(PackageManager.NameNotFoundException ignored){}
                LaunchLogging.begin(this,title,pkg,pkg,"Installed game / manual crash capture");journalLaunch=true;
                try{startActivity(game);message("Capture running. Return after the crash, then tap Stop / show log.");}catch(RuntimeException error){LaunchLogging.append(this,title,"Launch failed",error.toString());LaunchLogging.finish(this);journalLaunch=false;message("Could not launch game: "+error.getMessage());}
            });}catch(Exception error){showReport("Capture could not start: "+error+"\nNo crash has been verified for this attempt.");message("Capture could not start: "+error.getMessage());}
        },"start-crash-capture").start();
    }
    private void snapshot(boolean stop){
        if(!captureStarted){message("Start a capture for this game first.");return;}
        if(remote==null){message("No connected capture service. Import a bug report or connect Shizuku.");return;}
        new Thread(()->{try{
            showReport(capturedReport(stop));message(stop?"Capture stopped. Review the report before exporting.":"Latest capture displayed.");
        }catch(Exception error){message("Cannot read capture: "+error.getMessage());}},"read-crash-capture").start();
    }
    private String gameName(){try{return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(PackageManager.NameNotFoundException e){return pkg;}}
    private String capturedReport(boolean stop)throws RemoteException {
        if(stop){Parcel stopped=call(CrashLogService.STOP,null);stopped.recycle();}
        Parcel result=call(CrashLogService.READ,null);
        try{result.readInt();String text=result.readString();if(text==null||!text.startsWith("Crash capture for "+pkg+"\n"))throw new RemoteException("Capture does not belong to this game; capture again");return text;}finally{result.recycle();}
    }
    private void exportSupport(){
        if(exporting)return;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),EXPORT);
    }
    private void exportSupport(Uri tree){
        if(exporting)return;exporting=true;message("Exporting log and installed APKs…");
        final String currentReport=report,title=gameName();
        new Thread(()->{Uri directory=null;try{
            String text=currentReport;
            if(captureStarted){
                try{text=capturedReport(true);showReport(text);}
                catch(Exception e){text=currentReport+"\n\nFinal capture unavailable: "+e+"\nNo additional crash evidence was verified.";}
            }
            ApplicationInfo info=getPackageManager().getApplicationInfo(pkg,0);
            java.util.List<GameSupportExport.Source> sources=new java.util.ArrayList<>();
            sources.add(new GameSupportExport.Source("base.apk",new File(info.sourceDir)));
            if(info.splitSourceDirs!=null)for(int i=0;i<info.splitSourceDirs.length;i++)sources.add(new GameSupportExport.Source("split-"+(i+1)+".apk",new File(info.splitSourceDirs[i])));
            Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
            directory=DocumentsContract.createDocument(getContentResolver(),parent,DocumentsContract.Document.MIME_TYPE_DIR,pkg+"-support-"+System.currentTimeMillis());
            if(directory==null)throw new IOException("Cannot create export folder");
            final Uri folder=directory;
            String header=title+" — "+new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z",java.util.Locale.ROOT).format(new java.util.Date())+"\nPackage: "+pkg+"\n\n";
            GameSupportExport.write(header+text,sources,(name,mime)->{
                Uri file=DocumentsContract.createDocument(getContentResolver(),folder,mime,name);
                if(file==null)throw new IOException("Cannot create "+name);
                OutputStream out=getContentResolver().openOutputStream(file,"wt");if(out==null)throw new IOException("Cannot write "+name);return out;
            });
            message("Export complete. In your chosen folder, send crash-log.txt, every APK/numbered part, and EXPORT-COMPLETE.txt.");
        }catch(Exception error){
            boolean removed=directory==null;if(directory!=null)try{removed=DocumentsContract.deleteDocument(getContentResolver(),directory);}catch(Exception ignored){}
            message("Export failed: "+error.getMessage()+(removed?". Incomplete files removed.":". Delete the incomplete export folder before retrying."));
        }finally{exporting=false;}},"export-game-support").start();
    }
    private void importReport(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/plain","application/zip","application/x-zip-compressed"}),IMPORT);}
    private void saveReport(){startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,pkg+"-crash.txt"),SAVE);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==EXPORT){exportSupport(uri);}
        else if(request==SAVE){final String text=report;new Thread(()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("Cannot open selected file");out.write(text.getBytes(StandardCharsets.UTF_8));message("Report saved.");}catch(Exception error){message("Save failed: "+error.getMessage());}},"save-crash-report").start();}
        else if(request==IMPORT){message("Importing and filtering report…");new Thread(()->{File temp=null;try{
            temp=File.createTempFile("crash-import-",".tmp",getCacheDir());
            try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)){
                if(in==null)throw new IOException("Cannot open report");byte[] buffer=new byte[65536];long total=0;for(int n;(n=in.read(buffer))!=-1;){total+=n;if(total>128L*1024*1024)throw new IOException("Report exceeds 128 MiB import limit");out.write(buffer,0,n);}
            }
            String extracted;
            try(InputStream check=new FileInputStream(temp)){boolean zip=check.read()=='P'&&check.read()=='K';
                if(zip){try(ZipFile archive=new ZipFile(temp)){ZipEntry entry=null;var entries=archive.entries();int count=0;while(entries.hasMoreElements()&&++count<=10000){ZipEntry e=entries.nextElement();String name=e.getName();if(!e.isDirectory()&&name.substring(name.lastIndexOf('/')+1).startsWith("bugreport")&&name.endsWith(".txt")){entry=e;break;}}if(entry==null)throw new IOException("No bugreport*.txt found in ZIP");try(Reader reader=new InputStreamReader(archive.getInputStream(entry),StandardCharsets.UTF_8)){extracted=CrashLogFilter.extract(reader,pkg,64L*1024*1024);}}}
                else try(Reader reader=new InputStreamReader(new FileInputStream(temp),StandardCharsets.UTF_8)){extracted=CrashLogFilter.extract(reader,pkg,64L*1024*1024);}
            }
            captureStarted=false;showReport("Imported crash details for "+pkg+"\nSource: selected Android bug report (bounded scan)\n\n"+extracted);message("Import complete. Only extracted details are displayed; nothing is uploaded.");
        }catch(Exception error){message("Import failed: "+error.getMessage());}finally{if(temp!=null)temp.delete();}},"import-crash-report").start();}
    }
    @Override protected void onResume(){super.onResume();if(journalLaunch){journalLaunch=false;LaunchLogging.finish(this);snapshot(true);}if(args!=null)bind();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("report",report);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){
        Shizuku.removeRequestPermissionResultListener(permission);Shizuku.removeBinderReceivedListener(received);Shizuku.removeBinderDeadListener(dead);
        if(bound)try{Shizuku.unbindUserService(args,connection,true);}catch(RuntimeException ignored){}
        super.onDestroy();
    }
}
