package io.github.hozdogus.pocketouya;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.InputStream;
import java.util.concurrent.*;
import rikka.shizuku.Shizuku;

/** Keeps the single journal capture alive while an external game is in front. */
public final class LaunchLogService extends Service {
    private static final int NOTIFICATION=301;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile IBinder remote;private volatile Session current;private volatile boolean destroyed;
    private Shizuku.UserServiceArgs args;private boolean bound,local,busy;private int latestStartId;
    private static final class Session {String game,target,previousCrash="";long since;int startId;IBinder binder;boolean started,inactiveLogged,localCapture,finishRequested;}
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){if(destroyed)return;remote=binder;startReader();}
        public void onServiceDisconnected(ComponentName name){remote=null;Session s=current;if(s!=null){LaunchLogging.append(LaunchLogService.this,s.game,"Capture interrupted","Shizuku log service disconnected; later output may be missing.");finalizeSession(s.startId);}}
    };
    @Override public void onCreate(){
        super.onCreate();NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("game_logs","Game log recording",NotificationManager.IMPORTANCE_LOW));
        Intent view=new Intent(this,GlobalLogActivity.class);PendingIntent open=PendingIntent.getActivity(this,0,view,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,LaunchLogService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(NOTIFICATION,new Notification.Builder(this,"game_logs").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("PocketOuya global game log").setContentText("Recording game output locally. Tap to view or export.").setContentIntent(open).addAction(new Notification.Action.Builder(null,"Stop recording",stop).build()).setOngoing(true).build());
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        latestStartId=startId;
        if(intent==null){end(startId);return START_NOT_STICKY;}
        if("stop".equals(intent.getAction())){end(startId);return START_NOT_STICKY;}
        Session next=new Session();next.game=intent.getStringExtra("game");next.target=intent.getStringExtra("target");next.since=intent.getLongExtra("since",System.currentTimeMillis());next.startId=startId;
        if(next.game==null||!CrashLogFilter.validPackage(next.target)){end(startId);return START_NOT_STICKY;}
        ui.removeCallbacks(poll);
        Session old=current;current=next;if(old!=null&&!old.started)LaunchLogging.append(this,old.game,"Capture unavailable","A new launch replaced this session before its log reader connected; startup output may be missing.");if(old!=null&&old.binder!=null)worker.execute(()->{try{drain(old);Parcel r=call(old.binder,GameLogReader.STOP,null);r.recycle();drain(old);}catch(Exception error){note(old,error);}});
        if(local&&!next.target.equals(getPackageName())){remote=null;local=false;}
        if(remote!=null){startReader();return START_NOT_STICKY;}
        try{
            if(Shizuku.pingBinder()&&Shizuku.getVersion()>=13&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED){
                if(!bound){int version=(int)getPackageManager().getPackageInfo(getPackageName(),0).getLongVersionCode();args=new Shizuku.UserServiceArgs(new ComponentName(this,GameLogReader.class)).daemon(false).tag("global_game_logs").processNameSuffix("global_log_reader").version(version).debuggable(false);bound=true;Shizuku.bindUserService(args,connection);}
                ui.postDelayed(()->{if(current==next&&remote==null){LaunchLogging.append(this,next.game,"Capture unavailable","Timed out connecting to Shizuku. Launch entry is saved, but game output may be missing.");finalizeSession(startId);}},8000);
            }else fallback(next,startId);
        }catch(Exception error){note(next,error);fallback(next,startId);}
        return START_NOT_STICKY;
    }
    private void fallback(Session session,int startId){
        if(session.target.equals(getPackageName())){local=true;remote=new GameLogReader(this);LaunchLogging.append(this,session.game,"Log source","Shizuku unavailable; capturing PocketOuya's own engine output. Native system tombstones may only be available after returning.");startReader();}
        else{LaunchLogging.append(this,session.game,"Game output unavailable","Shizuku is not running or access is not granted. This launch is recorded, but Android prevents reading this other app's runtime/crash logs. Enable Shizuku access from the global log screen for future launches.");end(startId);}
    }
    private Parcel call(IBinder binder,int code,Session start)throws RemoteException{
        if(binder==null)throw new RemoteException("Log reader disconnected");Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken(GameLogReader.DESCRIPTOR);if(start!=null){data.writeString(start.target);data.writeLong(start.since);}if(!binder.transact(code,data,reply,0))throw new RemoteException("Unsupported reader operation");reply.readException();return reply;}
        catch(RuntimeException|RemoteException error){reply.recycle();throw error;}finally{data.recycle();}
    }
    private void startReader(){Session s=current;if(s==null||s.started)return;s.started=true;s.binder=remote;s.localCapture=local;
        worker.execute(()->{try{Parcel result=call(s.binder,GameLogReader.START,s);result.recycle();LaunchLogging.append(this,s.game,"Capture started",s.localCapture?"Source: this app's Android runtime logs.":"Source: game UID main/system logs and package-filtered crash buffer via Shizuku.");ui.post(()->{if(current==s){ui.removeCallbacks(poll);ui.post(poll);if(s.finishRequested){LaunchLogging.append(this,s.game,"Startup log recovery","The game returned before capture connected; reading buffered startup output before stopping.");ui.postDelayed(()->{if(current==s)finalizeSession(s.startId);},750);}}});}catch(Exception error){note(s,error);ui.post(()->{if(current==s)finalizeSession(s.startId);});}});
    }
    private final Runnable poll=new Runnable(){public void run(){if(current==null||destroyed)return;if(!busy){busy=true;Session s=current;worker.execute(()->{try{if(s==current)drain(s);}catch(Exception error){note(s,error);ui.post(()->{if(current==s)finalizeSession(s.startId);});}finally{ui.post(()->busy=false);}});}ui.postDelayed(this,250);}};
    private void drain(Session s)throws RemoteException{
        Parcel result=call(s.binder,GameLogReader.READ,null);String normal,crash,status;boolean active;
        try{active=result.readInt()!=0;normal=result.readString();crash=result.readString();status=result.readString();}finally{result.recycle();}
        LaunchLogging.append(this,s.game,"Runtime output",normal);
        if(crash!=null)crash=crash.replaceFirst("[\\r\\n]+$","");
        if(crash!=null&&!crash.isEmpty()&&!crash.equals(s.previousCrash)){String fresh=crash.startsWith(s.previousCrash)?crash.substring(s.previousCrash.length()):crash;LaunchLogging.append(this,s.game,"Crash output",fresh);s.previousCrash=crash;}
        LaunchLogging.append(this,s.game,"Capture status",status);
        if(!active&&!s.inactiveLogged){s.inactiveLogged=true;LaunchLogging.append(this,s.game,"Capture ended","Reader stopped. No later output is captured in this session.");ui.post(()->{if(current==s)finalizeSession(s.startId);});}
    }
    /**
     * After the game ends, asks Android (through the Shizuku reader) how its
     * process exited. A native crash comes with the full crash dump, which is
     * decoded to text and added to the global log. Android writes that dump a
     * moment after the crash, so a missing dump is retried briefly.
     */
    private void exitRecord(Session s){
        if(s.binder==null||s.localCapture||s.target.equals(getPackageName())||Build.VERSION.SDK_INT<30)return;
        String last=null;
        for(int attempt=0;attempt<4;attempt++){
            if(attempt>0)SystemClock.sleep(1500);
            Parcel reply=null;ParcelFileDescriptor pipe=null;
            try{
                reply=call(s.binder,GameLogReader.EXIT_TRACE,s);
                if(reply.readInt()==0){last=reply.readString();continue;}
                int reason=reply.readInt();long time=reply.readLong();String process=reply.readString(),description=reply.readString();
                if(reply.readInt()!=0)pipe=ParcelFileDescriptor.CREATOR.createFromParcel(reply);
                reply.recycle();reply=null;
                byte[] trace=null;
                if(pipe!=null){try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pipe)){pipe=null;trace=readCapped(in,GameLogReader.MAX_TRACE);}}
                if(reason==ApplicationExitInfo.REASON_CRASH_NATIVE&&(trace==null||trace.length==0)&&attempt<3){last="Android recorded a native crash but its crash dump is not ready yet.";continue;}
                StringBuilder text=new StringBuilder();
                text.append("Process: ").append(process).append("\nReason: ").append(reasonName(reason)).append("\nTime: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z",java.util.Locale.US).format(new java.util.Date(time))).append("\nDescription: ").append(description).append("\n\n");
                text.append(TombstoneText.logText(trace));
                LaunchLogging.append(this,s.game,"Android crash record",text.toString());
                return;
            }catch(Exception error){note(s,error);return;}
            finally{if(reply!=null)reply.recycle();if(pipe!=null)try{pipe.close();}catch(java.io.IOException ignored){}}
        }
        if(last!=null)LaunchLogging.append(this,s.game,"Android crash record",last);
    }
    private static String reasonName(int reason){
        switch(reason){case ApplicationExitInfo.REASON_CRASH_NATIVE:return "native crash";case ApplicationExitInfo.REASON_CRASH:return "Java crash";case ApplicationExitInfo.REASON_ANR:return "not responding (ANR)";default:return "code "+reason;}
    }
    private static byte[] readCapped(InputStream in,long max)throws java.io.IOException{
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[65536];long left=max;
        for(int n;left>0&&(n=in.read(buffer,0,(int)Math.min(buffer.length,left)))!=-1;){out.write(buffer,0,n);left-=n;}
        return out.toByteArray();
    }
    private void note(Session s,Exception error){if(s!=null)LaunchLogging.append(this,s.game,"Capture error",error.toString());}
    private void end(int startId){
        Session s=current;
        // A very fast crash can return before Shizuku binds. Keep the service
        // alive so START can read logcat from the original launch timestamp.
        if(s!=null&&!s.started&&remote==null&&bound){
            if(!s.finishRequested){s.finishRequested=true;ui.postDelayed(()->{if(current==s&&!s.started){LaunchLogging.append(this,s.game,"Capture unavailable","Startup log reader did not connect before the shutdown deadline.");finalizeSession(startId);}},8500);}
            return;
        }
        // START may be queued but not finished when return/Stop arrives.
        if(s!=null&&s.started&&!s.finishRequested){s.finishRequested=true;ui.postDelayed(()->{if(current==s)finalizeSession(startId);},750);return;}
        if(s!=null&&s.finishRequested)return;
        finalizeSession(startId);
    }
    private void finalizeSession(int startId){
        Session ended=current;current=null;ui.removeCallbacks(poll);
        worker.execute(()->{if(ended!=null&&ended.binder!=null)try{drain(ended);Parcel reply=call(ended.binder,GameLogReader.STOP,null);reply.recycle();drain(ended);}catch(Exception error){note(ended,error);}
            if(ended!=null)exitRecord(ended);
            ui.post(()->{if(current==null&&stopSelfResult(latestStartId))stopForeground(STOP_FOREGROUND_REMOVE);});
        });
    }
    @Override public void onTimeout(int startId,int fgsType){Session s=current;current=null;ui.removeCallbacks(poll);if(s!=null)worker.execute(()->LaunchLogging.append(this,s.game,"Capture interrupted","Android's foreground-service time limit was reached. Later output is not captured."));stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){destroyed=true;ui.removeCallbacksAndMessages(null);Session interrupted=current;current=null;if(interrupted!=null)worker.execute(()->LaunchLogging.append(this,interrupted.game,"Capture interrupted","The capture service was stopped; later output may be missing."));IBinder binder=remote;
        if(local&&binder!=null)worker.execute(()->{try{Parcel r=call(binder,GameLogReader.STOP,null);r.recycle();}catch(Exception ignored){}});
        if(bound)try{Shizuku.unbindUserService(args,connection,true);}catch(RuntimeException ignored){}
        worker.shutdown();super.onDestroy();
    }
}
