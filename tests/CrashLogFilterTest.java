package io.github.hozdogus.pocketouya;
import java.io.*;
public final class CrashLogFilterTest {
 static final String PKG="com.example.game";
 static String line(int pid,String tag,String msg){return "11-01 12:00:00.123 "+pid+" "+pid+" E "+tag+": "+msg+"\n";}
 static String extract(String text)throws Exception{return CrashLogFilter.extract(new StringReader(text),PKG,1024*1024);}
 static void check(boolean ok){if(!ok)throw new AssertionError("Crash filtering regression");}
 public static void main(String[] args)throws Exception{
  String input=line(20,"AndroidRuntime","FATAL EXCEPTION: main")+line(20,"AndroidRuntime","Process: com.other.app, PID: 20")+line(20,"AndroidRuntime","PRIVATE_OTHER_APP")+
   line(30,"AndroidRuntime","FATAL EXCEPTION: main")+line(30,"AndroidRuntime","Process: "+PKG+", PID: 30")+line(30,"AndroidRuntime","java.lang.UnsatisfiedLinkError: dlopen failed")+line(30,"AndroidRuntime","at Game.init(Game.java:1)")+line(30,"OtherTag","PRIVATE_UNRELATED_TAG");
  String result=extract(input);check(result.contains("FATAL EXCEPTION")&&result.contains("dlopen failed")&&result.contains("Game.java:1")&&!result.contains("PRIVATE"));
  String reused=input+line(30,"AndroidRuntime","FATAL EXCEPTION: unrelated_private_thread")+line(30,"AndroidRuntime","Process: com.other.app, PID: 30")+line(30,"AndroidRuntime","PRIVATE_REUSED_PID");
  check(!extract(reused).contains("PRIVATE")&&!extract(reused).contains("unrelated_private_thread"));
  String reverse=line(30,"AndroidRuntime","Process: com.other.app, PID: 30")+line(30,"AndroidRuntime","PRIVATE_EARLIER_CRASH")+line(30,"AndroidRuntime","FATAL EXCEPTION: main")+line(30,"AndroidRuntime","Process: "+PKG+":remote, PID: 30")+line(30,"AndroidRuntime","target subprocess failure");
  check(!extract(reverse).contains("PRIVATE")&&extract(reverse).contains("target subprocess failure"));
  String nativeCrash=line(100,"DEBUG","*** *** *** ***")+line(100,"DEBUG","Cmdline: "+PKG)+line(100,"DEBUG","pid: 30, tid: 31, name: main >>> "+PKG+" <<<")+line(100,"DEBUG","signal 11 (SIGSEGV)")+line(100,"DEBUG","#00 pc 123 libgame.so");
  check(extract(nativeCrash).contains("SIGSEGV")&&extract(nativeCrash).contains("libgame.so"));
  check(extract(input.replace(PKG,PKG+"extra")).contains("No identified"));
  check(extract(line(20,"AndroidRuntime","Exception mentions Process: "+PKG)).contains("No identified"));
  check(extract("2026-11-01 12:00:00.123 u0_a20 22 22 E AndroidRuntime: Process: "+PKG+", PID: 22\n").contains("Process:"));
  check(CrashLogFilter.extract(new StringReader(input),PKG,20).contains("limit reached"));
  check(extract("x".repeat(100000)+"\n").contains("Oversized"));
  check(CrashLogFilter.validPackage("com.square_enix.android_OUYA.FFIII"));
  try{CrashLogFilter.extract(new StringReader(input),"com.example;bad",100);throw new AssertionError();}catch(IllegalArgumentException expected){}
  System.out.println("Java/native crash grouping, subprocesses, PID reuse, unrelated-data exclusion, UID formats and input bounds tests pass");
 }
}
