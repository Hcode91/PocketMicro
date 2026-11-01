package io.github.hozdogus.pocketouya;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import java.io.*;
import rikka.shizuku.Shizuku;

/** One combined on-device journal. Export streams the entire file, not the preview. */
public final class GlobalLogActivity extends Activity {
    private static final int EXPORT=71,ACCESS=72;
    private final Handler ui=new Handler(Looper.getMainLooper());private TextView output,status;
    private final Shizuku.OnRequestPermissionResultListener permission=(request,result)->{if(request==ACCESS){status.setText(result==PackageManager.PERMISSION_GRANTED?"Shizuku access granted. Future game launches will capture runtime and crash output automatically.":"Shizuku access denied. Launch entries and this app's engine output remain available.");}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(20,16,20,12);
        TextView title=new TextView(this);title.setText("Global game log");title.setTextSize(22);root.addView(title);
        TextView info=new TextView(this);info.setText("One saved log for every game launched through PocketOuya. Each entry starts with the game name. Normal output and identified crashes are recorded when access is available. External game output needs Shizuku running with access granted. Logs remain on this device and may contain personal information. The preview shows the latest 256 KiB; Export full log saves everything retained in the file. Saved until you clear it or uninstall PocketOuya.");root.addView(info);
        status=new TextView(this);root.addView(status);
        row(root,new String[]{"Refresh","Export full log"},new Runnable[]{this::refresh,()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"PocketOuya-global-game-log.txt"),EXPORT)});
        row(root,new String[]{"Shizuku access","Setup guide","Clear log"},new Runnable[]{this::access,()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/guide/setup/"))),()->new AlertDialog.Builder(this).setTitle("Clear saved global log?").setMessage("Deletes the saved history. A running capture can continue adding new output.").setPositiveButton("Clear",(d,w)->new Thread(()->{try{LaunchLogging.file(this).clear();ui.post(this::refresh);}catch(IOException e){message(e.toString());}},"clear-global-log").start()).setNegativeButton("Cancel",null).show()});
        output=new TextView(this);output.setTextIsSelectable(true);output.setTextSize(11);output.setTypeface(Typeface.MONOSPACE);ScrollView scroll=new ScrollView(this);scroll.addView(output);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        Shizuku.addRequestPermissionResultListener(permission,ui);refresh();
    }
    private void row(LinearLayout parent,String[] names,Runnable[] actions){LinearLayout row=new LinearLayout(this);for(int i=0;i<names.length;i++){Button b=new Button(this);b.setText(names[i]);b.setTextSize(11);Runnable action=actions[i];b.setOnClickListener(v->action.run());row.addView(b,new LinearLayout.LayoutParams(0,-2,1));}parent.addView(row);}
    private void message(String text){ui.post(()->{if(!isDestroyed())status.setText(text);});}
    private void refresh(){new Thread(()->{try{String text=LaunchLogging.file(this).tail(256*1024);ui.post(()->{if(!isDestroyed())output.setText(text);});}catch(IOException error){message("Cannot read log: "+error);}},"global-log-preview").start();}
    private void access(){try{if(!Shizuku.pingBinder()){message("Start Shizuku first. The setup guide supports phone-only wireless debugging.");return;}if(Shizuku.getVersion()<13){message("Shizuku 13 or later is required.");return;}if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED)message("Shizuku access is ready. Future launches will be logged automatically.");else Shizuku.requestPermission(ACCESS);}catch(RuntimeException error){message(error.toString());}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=EXPORT||result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();message("Exporting full saved log…");new Thread(()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Cannot open destination");LaunchLogging.file(this).export(out);message("Full saved log exported.");}catch(Exception error){message("Export failed: "+error);}},"export-full-global-log").start();}
    @Override protected void onDestroy(){Shizuku.removeRequestPermissionResultListener(permission);super.onDestroy();}
}
