package io.github.hozdogus.pocketouya;
import java.io.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.*;
public final class GlobalLogFileTest {
 static void check(boolean value){if(!value)throw new AssertionError("Global journal/export regression");}
 public static void main(String[]args)throws Exception{
  Path dir=Files.createTempDirectory("pocket-global-log-");
  try{
   GlobalLogFile log=new GlobalLogFile(dir.toFile());log.append("Maldita Castilla","Launch","Engine start requested");
   log.append("Maldita Castilla","Runtime output","Engine initialization succeeded\n");
   String longOutput="Vector output café 日本語\n".repeat(18000);log.append("Vector","Runtime output",longOutput);
   log.append("A Ball Slapping Good Time!","Crash output","java.lang.UnsatisfiedLinkError: test fixture\n");
   GlobalLogFile reopened=new GlobalLogFile(dir.toFile());String preview=reopened.tail(256*1024);
   check(preview.startsWith("[Showing the latest portion")&&!preview.contains("Engine start requested"));
   ByteArrayOutputStream full=new ByteArrayOutputStream();reopened.export(full);
   byte[] saved=Files.readAllBytes(dir.resolve("global-launch.log"));check(Arrays.equals(saved,full.toByteArray()));
   String text=full.toString("UTF-8");check(text.contains("Maldita Castilla —")&&text.contains("Vector —")&&text.contains("A Ball Slapping Good Time! —")&&text.contains(longOutput)&&text.contains("Engine initialization succeeded")&&text.contains("UnsatisfiedLinkError"));
   ExecutorService writers=Executors.newFixedThreadPool(4);List<Future<?>> jobs=new ArrayList<>();
   for(int i=0;i<4;i++){final int id=i;jobs.add(writers.submit(()->{try{for(int j=0;j<20;j++)new GlobalLogFile(dir.toFile()).append("Game "+id,"Runtime","unique-record-"+id+"-"+j);}catch(IOException error){throw new RuntimeException(error);}}));}
   for(Future<?> job:jobs)job.get();writers.shutdown();
   full.reset();log.export(full);text=full.toString("UTF-8");for(int i=0;i<4;i++)for(int j=0;j<20;j++)check(text.contains("unique-record-"+i+"-"+j+"\n"));
   log.clear();full.reset();log.export(full);check(full.size()==0);log.append("Game after clear","Launch","new session");check(log.tail(4096).contains("new session"));
   System.out.println("Single global game journal, success/crash headings, persistence, concurrent append, Unicode and full export beyond preview tests pass");
  }finally{try(var paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new UncheckedIOException(e);}});}}
 }
}
