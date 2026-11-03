package io.github.hozdogus.pocketouya;

import android.content.Context;
import android.os.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Optional Shizuku user service. Fixed operations only (crash-buffer capture, install one APK streamed by PocketOuya), never arbitrary commands. */
public final class CrashLogService extends Binder {
    static final String DESCRIPTOR="io.github.hozdogus.pocketouya.CRASH_CAPTURE";
    static final int START=1,STOP=2,READ=3,INSTALL=4,DESTROY=16777115;
    private final int owner;
    private final Object lock=new Object();
    private String pkg="",failure="";
    private StringBuilder raw=new StringBuilder();
    private java.lang.Process process;
    private boolean running;
    private int generation;
    public CrashLogService(Context context){owner=context.getApplicationInfo().uid;attachInterface(null,DESCRIPTOR);}
    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
        if(code==INTERFACE_TRANSACTION){reply.writeString(DESCRIPTOR);return true;}
        if(code==PING_TRANSACTION)return true;
        if(code==DESTROY){if(Binder.getCallingUid()!=owner&&Binder.getCallingUid()!=android.os.Process.myUid())throw new SecurityException("Capture belongs to PocketOuya");stop();System.exit(0);return true;}
        if(Binder.getCallingUid()!=owner)throw new SecurityException("Capture belongs to PocketOuya");
        data.enforceInterface(DESCRIPTOR);
        if(code==START){start(data.readString());reply.writeNoException();return true;}
        if(code==STOP){stop();reply.writeNoException();return true;}
        if(code==INSTALL){ParcelFileDescriptor apk=data.readFileDescriptor();long size=data.readLong();String out=install(apk,size);reply.writeNoException();reply.writeString(out);return true;}
        if(code==READ){
            String snapshot,target,error;boolean active;
            synchronized(lock){snapshot=raw.toString();target=pkg;error=failure;active=running;}
            String report;
            try{report=target.isEmpty()?"No capture started.":"Crash capture for "+target+"\nSource: Android crash buffer via Shizuku\n\n"+CrashLogFilter.extract(new StringReader(snapshot),target,512*1024);}
            catch(IOException errorReading){report="Cannot read captured report: "+errorReading;}
            if(!error.isEmpty())report+="\nCapture status: "+error;
            reply.writeNoException();reply.writeInt(active?1:0);reply.writeString(report);return true;
        }
        return super.onTransact(code,data,reply,flags);
    }
    private void start(String target){
        if(!CrashLogFilter.validPackage(target))throw new IllegalArgumentException("Invalid game package");
        stop();final int id;
        synchronized(lock){pkg=target;raw=new StringBuilder();failure="";running=true;id=++generation;}
        String since=new SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.US).format(new Date(System.currentTimeMillis()-1000));
        new Thread(()->{
            java.lang.Process child=null;
            try{
                child=new ProcessBuilder("/system/bin/logcat","-b","crash","-v","threadtime","-T",since).redirectErrorStream(true).start();
                synchronized(lock){if(id!=generation||!running){child.destroy();return;}process=child;}
                final java.lang.Process timerProcess=child;
                new Thread(()->{try{Thread.sleep(60000);}catch(InterruptedException ignored){}synchronized(lock){if(id==generation){running=false;if(process==timerProcess)timerProcess.destroy();}}},"crash-capture-timeout").start();
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(child.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))){
                    for(String line;(line=reader.readLine())!=null;){synchronized(lock){if(id!=generation)break;if(raw.length()+line.length()>256*1024){failure="Capture size limit reached.";break;}raw.append(line).append('\n');}}
                }
                int status=child.waitFor();synchronized(lock){if(id==generation&&running&&status!=0)failure="logcat exited with status "+status+". Shizuku/log access may be unavailable.";}
            }catch(Exception error){synchronized(lock){if(id==generation)failure=error.toString();}}
            finally{if(child!=null)child.destroy();synchronized(lock){if(id==generation){running=false;process=null;}}}
        },"game-crash-capture").start();
    }
    private void stop(){synchronized(lock){running=false;if(process!=null)process.destroy();}}
    /** pm install of one APK streamed from PocketOuya. Keeps the game's original target SDK
     *  (Android 14+ otherwise refuses very old targets), which lets old engines load. */
    static List<String> installCommand(long size,int sdk){
        List<String> cmd=new ArrayList<>(Arrays.asList("/system/bin/pm","install","-r"));
        if(sdk>=34)cmd.add("--bypass-low-target-sdk-block");
        cmd.add("-S");cmd.add(Long.toString(size));return cmd;
    }
    private String install(ParcelFileDescriptor apk,long size){
        if(apk==null||size<=0||size>(4L<<30))return "FAILED: invalid APK";
        java.lang.Process child=null;
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(apk)){
            child=new ProcessBuilder(installCommand(size,Build.VERSION.SDK_INT)).redirectErrorStream(true).start();
            try(OutputStream out=child.getOutputStream()){byte[] buffer=new byte[1<<16];long left=size;for(int n;left>0&&(n=in.read(buffer,0,(int)Math.min(buffer.length,left)))>0;){out.write(buffer,0,n);left-=n;}}
            StringBuilder text=new StringBuilder();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(child.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))){for(String line;(line=reader.readLine())!=null&&text.length()<8192;)text.append(line).append('\n');}
            if(!child.waitFor(5,java.util.concurrent.TimeUnit.MINUTES)){child.destroy();return "FAILED: pm install timed out";}
            return (child.exitValue()==0?"":"FAILED (exit "+child.exitValue()+"): ")+text.toString().trim();
        }catch(Exception error){return "FAILED: "+error;}
        finally{if(child!=null)child.destroy();}
    }
}
