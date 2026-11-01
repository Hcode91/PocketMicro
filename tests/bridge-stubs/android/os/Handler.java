package android.os;
public final class Handler {
 public static final java.util.ArrayDeque<Runnable> queue=new java.util.ArrayDeque<>();
 public Handler(Looper looper){}
 public boolean post(Runnable task){queue.add(task);return true;}
 public static void drain(){while(!queue.isEmpty())queue.remove().run();}
}
