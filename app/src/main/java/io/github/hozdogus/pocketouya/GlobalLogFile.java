package io.github.hozdogus.pocketouya;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** One append-only journal shared by the launcher and engine process. */
final class GlobalLogFile {
    private static final Object LOCK=new Object();
    private final File directory;
    GlobalLogFile(File directory){this.directory=directory;}
    private File log(){return new File(directory,"global-launch.log");}
    void append(String game,String source,String text)throws IOException{
        if(text==null||text.isEmpty())return;
        synchronized(LOCK){
            if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create global log folder");
            try(RandomAccessFile lock=new RandomAccessFile(new File(directory,"journal.lock"),"rw");FileLock held=lock.getChannel().lock();FileOutputStream out=new FileOutputStream(log(),true)){
                String time=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z",Locale.US).format(new Date());
                String heading="\n"+clean(game)+" — "+time+" ["+clean(source)+"]\n";
                out.write(heading.getBytes(StandardCharsets.UTF_8));out.write(text.getBytes(StandardCharsets.UTF_8));if(!text.endsWith("\n"))out.write('\n');out.flush();
            }
        }
    }
    String tail(int limit)throws IOException{
        synchronized(LOCK){if(!log().exists())return "No game launches logged yet.";
            try(RandomAccessFile file=new RandomAccessFile(log(),"r")){long start=Math.max(0,file.length()-limit);file.seek(start);if(start>0)file.readLine();byte[] bytes=new byte[(int)(file.length()-file.getFilePointer())];file.readFully(bytes);return (start>0?"[Showing the latest portion. Export full log includes all saved content.]\n\n":"")+new String(bytes,StandardCharsets.UTF_8);}
        }
    }
    void export(OutputStream destination)throws IOException{
        synchronized(LOCK){
            if(!directory.exists()||!log().exists()){destination.write("No game launches logged yet.\n".getBytes(StandardCharsets.UTF_8));return;}
            try(RandomAccessFile lock=new RandomAccessFile(new File(directory,"journal.lock"),"rw");FileLock held=lock.getChannel().lock();InputStream in=new FileInputStream(log())){
                byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)destination.write(buffer,0,n);
            }
        }
    }
    void clear()throws IOException{
        synchronized(LOCK){if(!directory.exists())return;try(RandomAccessFile lock=new RandomAccessFile(new File(directory,"journal.lock"),"rw");FileLock held=lock.getChannel().lock();FileOutputStream out=new FileOutputStream(log(),false)){} }
    }
    private static String clean(String text){return text==null?"Unknown game":text.replace('\r',' ').replace('\n',' ');}
}
