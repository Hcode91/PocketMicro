package android.os;
public final class Looper {
 /** Test script: each loop() call throws the next failure, or returns when none are left. */
 public static final java.util.ArrayDeque<Throwable> script=new java.util.ArrayDeque<>();
 public static Looper getMainLooper(){return new Looper();}
 public static void loop(){Throwable t=script.poll();if(t==null)return;if(t instanceof RuntimeException)throw (RuntimeException)t;if(t instanceof Error)throw (Error)t;throw new IllegalStateException(t);}
}
