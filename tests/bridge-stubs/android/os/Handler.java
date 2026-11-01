package android.os;
public final class Handler {
 public static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> queue=new java.util.concurrent.ConcurrentLinkedQueue<>();
 public Handler(Looper looper){}
 public boolean post(Runnable task){queue.add(task);return true;}
 public static void drain(){while(!queue.isEmpty())queue.remove().run();}
}
