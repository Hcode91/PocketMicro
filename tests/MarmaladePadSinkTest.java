import io.github.hcode91.pocketouya.bridge.*;
import com.ideaworks3d.marmalade.LoaderActivity;
import android.view.KeyEvent;
public final class MarmaladePadSinkTest {
 public static final class Custom extends LoaderActivity {int overrides;@Override public boolean onKeyDown(int key,KeyEvent event){overrides++;return super.onKeyDown(key,event);}}
 public static final class Unready extends LoaderActivity {@Override public boolean onKeyDown(int key,KeyEvent event){throw new UnsatisfiedLinkError("test-only JNI not initialized");}}
 static void check(boolean ok){if(!ok)throw new AssertionError("Marmalade key forwarding regression");}
 public static void main(String[] args)throws Exception{
  Custom activity=new Custom();MarmaladePadSink sink=new MarmaladePadSink(activity,false);VirtualPad pad=new VirtualPad(sink);Object finger=new Object();
  pad.hold(VirtualPad.O,finger,true);pad.hold(VirtualPad.O,finger,true);pad.hold(VirtualPad.LEFT,finger,true);pad.releaseAll();
  check(activity.overrides==2&&activity.events.size()==4);
  KeyEvent first=activity.events.get(0),up=activity.events.get(2);
  check(first.getKeyCode()==VirtualPad.O&&first.getAction()==0&&first.getSource()==0x401&&first.getDeviceId()==-1);
  check(up.getAction()==1&&up.getKeyCode()==VirtualPad.O&&up.getDownTime()==first.getDownTime()&&up.getEventTime()>=first.getEventTime());
  LoaderActivity keyboard=new LoaderActivity();MarmaladePadSink keys=new MarmaladePadSink(keyboard,true);keys.key(VirtualPad.O,0);keys.key(VirtualPad.O,1);keys.axis(0,1f);
  check(keyboard.events.size()==2&&keyboard.events.get(0).getKeyCode()==62&&keyboard.events.get(1).getAction()==1&&keyboard.events.get(0).getSource()==0x101);
  Unready unready=new Unready();MarmaladePadSink delayed=new MarmaladePadSink(unready,false);
  try{delayed.key(VirtualPad.O,0);throw new AssertionError("Linkage failure escaped");}catch(IllegalStateException expected){check(expected.getCause()!=null);}
  delayed.key(VirtualPad.O,1);check(unready.events.size()==1);
  try{new MarmaladePadSink(new tv.ouya.sdk.OuyaUnityActivity(),false);throw new AssertionError("accepted unrelated activity");}catch(IllegalArgumentException expected){}
  System.out.println("Marmalade callback override, key/down-time/source mapping, release, keyboard and JNI-error wrapping tests pass");
 }
}
