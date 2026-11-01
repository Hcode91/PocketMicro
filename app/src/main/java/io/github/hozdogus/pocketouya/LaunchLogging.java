package io.github.hozdogus.pocketouya;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.util.Log;
import java.io.*;
import java.util.*;

final class LaunchLogging {
    private LaunchLogging(){}
    static GlobalLogFile file(Context context){return new GlobalLogFile(new File(context.getFilesDir(),"logs"));}
    static void append(Context context,String game,String source,String text){try{file(context).append(game,source,text);}catch(IOException error){Log.e("PocketOuyaJournal","Cannot save global log",error);}}
    static SharedPreferences state(Context context){return context.getSharedPreferences("global_launch_log",Context.MODE_PRIVATE);}
    static void begin(Context context,String game,String pkg,String target,String mode){
        finish(context);
        long since=System.currentTimeMillis();state(context).edit().putBoolean("active",true).putString("game",game).putString("package",pkg).putString("target",target).putString("mode",mode).putLong("since",since).commit();
        append(context,game,"Launch","Package: "+pkg+"\nMode: "+mode+"\nLaunch requested. This does not confirm successful gameplay.");
        try{context.startForegroundService(new Intent(context,LaunchLogService.class).putExtra("game",game).putExtra("target",target).putExtra("since",since));}
        catch(RuntimeException error){append(context,game,"Capture unavailable",error.toString());}
    }
    static void finish(Context context){
        SharedPreferences p=state(context);if(!p.getBoolean("active",false))return;
        String game=p.getString("game","Unknown game");long since=p.getLong("since",0);String target=p.getString("target","");
        p.edit().putBoolean("active",false).commit();
        try{context.startService(new Intent(context,LaunchLogService.class).setAction("stop"));}catch(RuntimeException error){append(context,game,"Capture status",error.toString());}
        append(context,game,"Launch session","Returned to PocketOuya or session ended. Missing crash output does not prove the game succeeded.");
        // Android permits this for our own :game process, not arbitrary installed games.
        if(Build.VERSION.SDK_INT>=30&&target.equals(context.getPackageName()))new Thread(()->{
            try{
                ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
                for(ApplicationExitInfo exit:manager.getHistoricalProcessExitReasons(null,0,12)){
                    if(exit.getTimestamp()<since||!exit.getProcessName().endsWith(":game"))continue;
                    if(exit.getReason()!=ApplicationExitInfo.REASON_CRASH&&exit.getReason()!=ApplicationExitInfo.REASON_CRASH_NATIVE&&exit.getReason()!=ApplicationExitInfo.REASON_ANR)continue;
                    append(context,game,"Android engine exit","Process: "+exit.getProcessName()+"\nReason: "+exit.getReason()+"\nDescription: "+exit.getDescription());
                    try(InputStream in=exit.getTraceInputStream()){
                        if(in!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int remaining=512*1024;for(int n;remaining>0&&(n=in.read(buffer,0,Math.min(buffer.length,remaining)))!=-1;){out.write(buffer,0,n);remaining-=n;}byte[] bytes=out.toByteArray();boolean binary=false;for(int j=0;j<Math.min(1024,bytes.length);j++)if(bytes[j]==0||(bytes[j]>0&&bytes[j]<9)){binary=true;break;}String trace=binary?"Binary Android exit trace (base64):\n"+Base64.getMimeEncoder(76,new byte[]{10}).encodeToString(bytes):out.toString("UTF-8");append(context,game,"Engine exit trace",trace+(remaining==0?"\n[Trace limited to 512 KiB]\n":""));}
                    }
                }
            }catch(Exception error){append(context,game,"Engine exit details unavailable",error.toString());}
        },"engine-exit-log").start();
    }
}
