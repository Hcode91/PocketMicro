package com.unity3d.player;
public final class UnityPlayer {
 public static android.app.Activity currentActivity;
 public static final java.util.List<String[]> messages=new java.util.ArrayList<>();
 public static void UnitySendMessage(String object,String method,String json){
  if(!"OuyaGameObject".equals(object))throw new AssertionError("wrong Unity object");
  messages.add(new String[]{method,json});
 }
}
