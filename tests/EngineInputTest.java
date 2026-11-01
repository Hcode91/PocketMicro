package io.github.hozdogus.pocketouya;
import java.util.*;
public final class EngineInputTest {
 static void check(boolean value){if(!value)throw new AssertionError("Engine input ownership regression");}
 public static void main(String[]args){
  List<String> events=new ArrayList<>();EngineInput input=new EngineInput((action,key)->events.add(action+":"+key));
  Object touch=new Object(),stick=new Object(),physical=new Object();
  input.hold(touch,21,true);input.hold(touch,21,true);input.hold(stick,21,true);input.hold(physical,21,true);
  input.release(touch);input.hold(stick,21,false);check(events.equals(Arrays.asList("0:21")));
  input.release(physical);check(events.equals(Arrays.asList("0:21","1:21")));
  events.clear();input.hold(touch,90,true);input.hold(stick,90,true);input.hold(touch,19,true);input.hold(physical,88,true);
  input.release(touch);check(events.equals(Arrays.asList("0:90","0:19","0:88","1:19")));
  input.releaseAll();input.releaseAll();input.release(stick);check(events.equals(Arrays.asList("0:90","0:19","0:88","1:19","1:90","1:88")));
  events.clear();input.hold(touch,90,true);input.releaseAll();input.hold(touch,90,true);input.hold(touch,90,false);
  check(events.equals(Arrays.asList("0:90","1:90","0:90","1:90")));
  System.out.println("GameMaker engine touch/controller overlap, repeat suppression, multi-key ownership, pause cleanup and repress tests pass");
 }
}
