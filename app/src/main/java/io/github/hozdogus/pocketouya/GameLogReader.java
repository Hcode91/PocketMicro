package io.github.hozdogus.pocketouya;

import android.content.Context;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Fixed logcat commands for a selected installed app; optional Shizuku user service. */
public final class GameLogReader extends Binder {
    static final String DESCRIPTOR="io.github.hozdogus.pocketouya.GLOBAL_LOG_READER";
    static final int START=1,READ=2,STOP=3,EXIT_TRACE=4,DESTROY=16777115;
    static final long MAX_TRACE=16L*1024*1024;
    private final Context context;private final int owner;
    private final Object lock=new Object();
    private final List<java.lang.Process> children=new ArrayList<>();
    private final List<Thread> readers=new ArrayList<>();
    private final StringBuilder normal=new StringBuilder(),crashes=new StringBuilder();
    private String target="",status="";private int generation;private boolean running;
    public GameLogReader(Context context){this.context=context;owner=context.getApplicationInfo().uid;attachInterface(null,DESCRIPTOR);}
    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
        if(code==INTERFACE_TRANSACTION){reply.writeString(DESCRIPTOR);return true;}
        if(code==PING_TRANSACTION)return true;
        if(code==DESTROY){if(Binder.getCallingUid()!=owner&&Binder.getCallingUid()!=android.os.Process.myUid())throw new SecurityException("Log belongs to PocketOuya");stop();System.exit(0);return true;}
        if(Binder.getCallingUid()!=owner)throw new SecurityException("Log belongs to PocketOuya");data.enforceInterface(DESCRIPTOR);
        if(code==START){String pkg=data.readString();long since=data.readLong();try{start(pkg,since);}catch(Exception error){throw new IllegalArgumentException("Cannot start game log: "+error);}reply.writeNoException();return true;}
        if(code==STOP){stop();reply.writeNoException();return true;}
        if(code==EXIT_TRACE){String pkg=data.readString();long since=data.readLong();reply.writeNoException();exitTrace(pkg,since,reply);return true;}
        if(code==READ){String output,raw,pkg,details;boolean active;
            synchronized(lock){int available=normal.length();if(available>0&&Character.isHighSurrogate(normal.charAt(available-1)))available--;output=normal.substring(0,available);normal.delete(0,available);raw=crashes.toString();pkg=target;details=status;status="";active=running;}
            String crash="";try{if(!raw.isEmpty())crash=CrashLogFilter.extract(new StringReader(raw),pkg,512*1024);if(crash.startsWith("No identified"))crash="";}catch(IOException error){details+="\nCrash filter: "+error;}
            reply.writeNoException();reply.writeInt(active?1:0);reply.writeString(output);reply.writeString(crash);reply.writeString(details);return true;
        }
        return super.onTransact(code,data,reply,flags);
    }
    /**
     * Android's own record of how the game's last process ended. Reading another
     * app's record needs the DUMP permission, which the Shizuku shell identity
     * has. For a native crash the trace is the tombstone (crash dump) file; it
     * is streamed back through a pipe because it can exceed the Binder limit.
     * Reply: found(int); if found: reason(int), timestamp(long), process,
     * description, hasTrace(int)[, pipe]. If not found: message.
     */
    private void exitTrace(String pkg,long since,Parcel reply){
        try{
            if(!CrashLogFilter.validPackage(pkg))throw new IllegalArgumentException("Invalid package");
            long now=System.currentTimeMillis();if(since<now-6*60*60*1000L||since>now+1000)throw new IllegalArgumentException("Invalid launch time");
            if(Build.VERSION.SDK_INT<30){reply.writeInt(0);reply.writeString("Android 11 or later is needed for exit records.");return;}
            android.app.ActivityManager manager=context.getSystemService(android.app.ActivityManager.class);
            android.app.ApplicationExitInfo best=null;
            for(android.app.ApplicationExitInfo exit:manager.getHistoricalProcessExitReasons(pkg,0,16)){
                int reason=exit.getReason();
                if(exit.getTimestamp()+2000<since)continue;
                if(reason!=android.app.ApplicationExitInfo.REASON_CRASH_NATIVE&&reason!=android.app.ApplicationExitInfo.REASON_CRASH&&reason!=android.app.ApplicationExitInfo.REASON_ANR)continue;
                if(best==null||exit.getTimestamp()>best.getTimestamp())best=exit;
            }
            if(best==null){reply.writeInt(0);reply.writeString("Android has no crash or ANR record for this launch.");return;}
            final InputStream in=best.getTraceInputStream();
            final ParcelFileDescriptor[] pipe=in==null?null:ParcelFileDescriptor.createPipe();
            reply.writeInt(1);reply.writeInt(best.getReason());reply.writeLong(best.getTimestamp());reply.writeString(best.getProcessName());reply.writeString(String.valueOf(best.getDescription()));
            if(pipe==null){reply.writeInt(0);return;}
            reply.writeInt(1);pipe[0].writeToParcel(reply,Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            new Thread(()->{try(InputStream source=in;OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){byte[] buffer=new byte[65536];long left=MAX_TRACE;for(int n;left>0&&(n=source.read(buffer,0,(int)Math.min(buffer.length,left)))!=-1;){out.write(buffer,0,n);left-=n;}}catch(IOException ignored){}},"exit-trace-pipe").start();
        }catch(Exception error){reply.writeInt(0);reply.writeString("Exit record unavailable: "+error);}
    }
    private void start(String pkg,long since)throws Exception{
        if(!CrashLogFilter.validPackage(pkg))throw new IllegalArgumentException("Invalid package");
        int uid=context.getPackageManager().getApplicationInfo(pkg,0).uid;
        long now=System.currentTimeMillis();if(since<now-60000||since>now+1000)throw new IllegalArgumentException("Invalid capture start time");
        stop();final int id; synchronized(lock){target=pkg;normal.setLength(0);crashes.setLength(0);status="";running=true;id=++generation;}
        String time=new SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.US).format(new Date(since-100));
        open(id,false,new String[]{"/system/bin/logcat","-b","main","-b","system","-v","threadtime","--uid="+uid,"-T",time,"*:V"});
        open(id,true,new String[]{"/system/bin/logcat","-b","crash","-v","threadtime","-T",time,"*:V"});
    }
    private void open(int id,boolean crash,String[] command)throws IOException{
        java.lang.Process child=new ProcessBuilder(command).redirectErrorStream(true).start();
        synchronized(lock){if(id!=generation||!running){child.destroy();return;}children.add(child);}
        Thread readerThread=new Thread(()->{
            try(Reader reader=new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8)){
                char[] buffer=new char[4096];for(int n;(n=reader.read(buffer))!=-1;){synchronized(lock){
                    if(id!=generation)break;StringBuilder out=crash?crashes:normal;int max=crash?256*1024:128*1024;
                    if(crash&&out.length()+n>max){int cut=out.indexOf("\n",out.length()/2);out.delete(0,cut<0?out.length()/2:cut+1);String warning="Older crash-buffer input was trimmed; previously saved journal output is retained.";if(!status.contains(warning))status+=warning+"\n";}
                    int keep=Math.min(n,max-out.length());if(keep>0)out.append(buffer,0,keep);
                    if(keep<n){String warning=crash?"Crash-buffer capture limit reached; later crash output may be missing.":"Live-log queue overflow; some output was dropped.";if(!status.contains(warning))status+=warning+"\n";}
                }}
                int exit=child.waitFor();synchronized(lock){if(id==generation&&running)status+=(crash?"Crash":"Runtime")+" logcat ended ("+exit+"). Log access or stream may be unavailable.\n";}
            }catch(Exception error){synchronized(lock){if(id==generation&&running)status+=error+"\n";}}
            finally{child.destroy();synchronized(lock){children.remove(child);if(id==generation&&children.isEmpty())running=false;}}
        },crash?"global-crash-reader":"global-runtime-reader");
        synchronized(lock){readers.add(readerThread);}readerThread.start();
    }
    private void stop(){List<java.lang.Process> copy;List<Thread> threads;synchronized(lock){running=false;copy=new ArrayList<>(children);children.clear();threads=new ArrayList<>(readers);readers.clear();}for(java.lang.Process p:copy)p.destroy();for(Thread thread:threads)if(thread!=Thread.currentThread())try{thread.join(750);}catch(InterruptedException error){Thread.currentThread().interrupt();break;}}
}
