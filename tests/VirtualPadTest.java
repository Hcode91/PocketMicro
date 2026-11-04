import io.github.hcode91.pocketouya.bridge.VirtualPad;
import java.util.*;
public final class VirtualPadTest {
 static void check(boolean value){if(!value)throw new AssertionError("Virtual pad state regression");}
 public static void main(String[]args){
  final List<String> events=new ArrayList<>();
  VirtualPad pad=new VirtualPad(new VirtualPad.Sink(){public void key(int k,int a){events.add("k"+k+":"+a);}public void axis(int a,float v){events.add("a"+a+":"+v);}});
  Object first=new Object(),second=new Object();
  pad.hold(VirtualPad.O,first,true);pad.hold(VirtualPad.O,first,true);pad.hold(VirtualPad.O,second,true);
  check(events.equals(Arrays.asList("k96:0")));pad.hold(VirtualPad.O,first,false);check(events.size()==1);
  pad.hold(VirtualPad.O,second,false);check(events.equals(Arrays.asList("k96:0","k96:1")));events.clear();
  pad.hold(VirtualPad.LEFT,first,true);pad.hold(VirtualPad.UP,second,true);
  check(events.contains("a0:-1.0")&&events.contains("a1:-1.0"));
  pad.hold(VirtualPad.RIGHT,second,true);check(events.get(events.size()-1).equals("a0:0.0"));
  pad.hold(VirtualPad.LEFT,first,false);check(events.get(events.size()-1).equals("a0:1.0"));
  pad.hold(VirtualPad.U,first,true);pad.releaseAll();
  check(events.contains("k99:1")&&events.get(events.size()-2).equals("a0:0.0")&&events.get(events.size()-1).equals("a1:0.0"));
  events.clear();pad.hold(VirtualPad.U,first,false);check(events.isEmpty());
  try{pad.hold(3,first,true);throw new AssertionError("Home button allowed");}catch(IllegalArgumentException expected){}
  events.clear();pad.hold(VirtualPad.RIGHT,first,true);pad.rightStick(1,-1);
  check(events.contains("a0:1.0")&&events.stream().anyMatch(e->e.startsWith("a11:0.707"))&&events.stream().anyMatch(e->e.startsWith("a14:-0.707")));
  int previous=events.size();pad.rightStick(1,-1);check(events.size()==previous);pad.rightStick(0.01f,0);check(events.contains("a11:0.0")&&events.contains("a14:0.0"));
  pad.rightStick(0.5f,0.5f);pad.releaseAll();check(events.get(events.size()-2).equals("a11:0.0")&&events.get(events.size()-1).equals("a14:0.0"));
  try{pad.rightStick(Float.NaN,0);throw new AssertionError("NaN stick accepted");}catch(IllegalArgumentException expected){}
  final List<Integer> releases=new ArrayList<>();
  VirtualPad failing=new VirtualPad(new VirtualPad.Sink(){public void key(int k,int a){if(a==1){releases.add(k);if(k==96)throw new IllegalStateException();}}public void axis(int a,float v){}});
  failing.hold(96,first,true);failing.hold(99,second,true);try{failing.releaseAll();throw new AssertionError();}catch(IllegalStateException expected){}
  check(releases.equals(Arrays.asList(96,99)));
  List<Integer> neutral=new ArrayList<>();VirtualPad brokenAxis=new VirtualPad(new VirtualPad.Sink(){public void key(int k,int a){}public void axis(int a,float v){if(v==0){neutral.add(a);if(a==11)throw new IllegalStateException();}}});
  brokenAxis.rightStick(0.5f,0.5f);try{brokenAxis.releaseAll();throw new AssertionError();}catch(IllegalStateException expected){}check(neutral.equals(Arrays.asList(0,1,11,14)));
  // Left analog stick: X/Y axes; a held d-pad direction overrides it, and the stick returns when released.
  events.clear();VirtualPad sticks=new VirtualPad(new VirtualPad.Sink(){public void key(int k,int a){events.add("k"+k+":"+a);}public void axis(int a,float v){events.add("a"+a+":"+v);}});
  sticks.leftStick(0.5f,-0.5f);check(events.equals(Arrays.asList("a0:0.5","a1:-0.5")));
  sticks.hold(VirtualPad.RIGHT,first,true);check(events.contains("a0:1.0")&&events.get(events.size()-1).equals("a1:0.0"));
  sticks.hold(VirtualPad.RIGHT,first,false);check(events.get(events.size()-2).equals("a0:0.5")&&events.get(events.size()-1).equals("a1:-0.5"));
  sticks.leftStick(0.05f,0.05f);check(events.get(events.size()-2).equals("a0:0.0")&&events.get(events.size()-1).equals("a1:0.0"));
  sticks.leftStick(3,4);check(events.get(events.size()-2).startsWith("a0:0.6")&&events.get(events.size()-1).startsWith("a1:0.8"));
  sticks.releaseAll();check(events.get(events.size()-1).equals("a1:0.0"));
  events.clear();sticks.hold(VirtualPad.L2,first,true);sticks.hold(VirtualPad.R2,first,true);check(events.equals(Arrays.asList("k104:0","k105:0")));sticks.releaseAll();
  try{sticks.leftStick(Float.POSITIVE_INFINITY,0);throw new AssertionError("Infinite stick accepted");}catch(IllegalArgumentException expected){}
  check(io.github.hcode91.pocketouya.bridge.KeyboardPadMap.key(VirtualPad.O)==62);
  check(io.github.hcode91.pocketouya.bridge.KeyboardPadMap.key(VirtualPad.START)==66);
  check(io.github.hcode91.pocketouya.bridge.KeyboardPadMap.key(VirtualPad.LEFT)==VirtualPad.LEFT);
  System.out.println("Multi-owner buttons, diagonals, opposite directions, release/cancel and failure cleanup tests pass");
 }
}
