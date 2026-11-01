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
    static final int START=1,READ=2,STOP=3,DESTROY=16777115;
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
        if(code==READ){String output,raw,pkg,details;boolean active;
            synchronized(lock){int available=normal.length();if(available>0&&Character.isHighSurrogate(normal.charAt(available-1)))available--;output=normal.substring(0,available);normal.delete(0,available);raw=crashes.toString();pkg=target;details=status;status="";active=running;}
            String crash="";try{if(!raw.isEmpty())crash=CrashLogFilter.extract(new StringReader(raw),pkg,512*1024);if(crash.startsWith("No identified"))crash="";}catch(IOException error){details+="\nCrash filter: "+error;}
            reply.writeNoException();reply.writeInt(active?1:0);reply.writeString(output);reply.writeString(crash);reply.writeString(details);return true;
        }
        return super.onTransact(code,data,reply,flags);
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
            }catch(Exception error){synchronized(lock){if(id==generation)status+=error+"\n";}}
            finally{child.destroy();synchronized(lock){children.remove(child);if(id==generation&&children.isEmpty())running=false;}}
        },crash?"global-crash-reader":"global-runtime-reader");
        synchronized(lock){readers.add(readerThread);}readerThread.start();
    }
    private void stop(){List<java.lang.Process> copy;List<Thread> threads;synchronized(lock){running=false;copy=new ArrayList<>(children);children.clear();threads=new ArrayList<>(readers);readers.clear();}for(java.lang.Process p:copy)p.destroy();for(Thread thread:threads)if(thread!=Thread.currentThread())try{thread.join(750);}catch(InterruptedException error){Thread.currentThread().interrupt();break;}}
}
