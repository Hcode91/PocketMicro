package android.view;
public final class KeyEvent {
 private final long down,time;private final int action,key,device,source;
 public KeyEvent(long down,long time,int action,int key,int repeat,int meta,int device,int scan,int flags,int source){this.down=down;this.time=time;this.action=action;this.key=key;this.device=device;this.source=source;}
 public long getDownTime(){return down;}public long getEventTime(){return time;}
 public int getAction(){return action;}public int getKeyCode(){return key;}public int getDeviceId(){return device;}public int getSource(){return source;}
}
