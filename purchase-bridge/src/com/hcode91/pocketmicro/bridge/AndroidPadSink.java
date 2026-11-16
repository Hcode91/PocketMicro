package com.hcode91.pocketmicro.bridge;

import android.app.Activity;
import android.os.SystemClock;
import android.view.*;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.*;

/** Best-effort in-process input fallback. No OS input injection is performed. */
public final class AndroidPadSink implements VirtualPad.Sink {
    private final Activity activity;
    private final boolean keyboard;
    private final Map<Integer,Long> downTimes=new HashMap<>();
    private float x,y,z,rz;
    private WeakReference<View> unity=new WeakReference<>(null);
    private Method inject;
    public AndroidPadSink(Activity activity,boolean keyboard){this.activity=activity;this.keyboard=keyboard;}
    public void key(int key,int action){
        int mapped=keyboard?KeyboardPadMap.key(key):key;long now=SystemClock.uptimeMillis();
        if(action==0)downTimes.put(mapped,now);
        Long down=downTimes.get(mapped);
        KeyEvent event=new KeyEvent(down==null?now:down,now,action,mapped,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,
                keyboard?InputDevice.SOURCE_KEYBOARD:InputDevice.SOURCE_GAMEPAD);
        if(!injectUnity(event))try{activity.dispatchKeyEvent(event);}catch(LinkageError error){throw new IllegalStateException("Game key callback unavailable",error);}
        if(action==1)downTimes.remove(mapped);
    }
    public void axis(int axis,float value){
        if(keyboard)return;
        if(axis==0)x=value;else if(axis==1)y=value;else if(axis==11)z=value;else if(axis==14)rz=value;else return;
        MotionEvent.PointerProperties property=new MotionEvent.PointerProperties();property.id=0;property.toolType=MotionEvent.TOOL_TYPE_UNKNOWN;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.setAxisValue(MotionEvent.AXIS_X,x);coords.setAxisValue(MotionEvent.AXIS_Y,y);
        coords.setAxisValue(MotionEvent.AXIS_Z,z);coords.setAxisValue(MotionEvent.AXIS_RZ,rz);
        coords.setAxisValue(MotionEvent.AXIS_HAT_X,x);coords.setAxisValue(MotionEvent.AXIS_HAT_Y,y);
        long now=SystemClock.uptimeMillis();
        MotionEvent event=MotionEvent.obtain(now,now,MotionEvent.ACTION_MOVE,1,new MotionEvent.PointerProperties[]{property},new MotionEvent.PointerCoords[]{coords},0,0,1,1,KeyCharacterMap.VIRTUAL_KEYBOARD,0,InputDevice.SOURCE_JOYSTICK,0);
        try{if(!injectUnity(event))try{activity.dispatchGenericMotionEvent(event);}catch(LinkageError error){throw new IllegalStateException("Game motion callback unavailable",error);}}finally{event.recycle();}
    }
    private boolean injectUnity(InputEvent event){
        try{
            View player=unity.get();
            if(player==null||player.getWindowToken()==null){
                Class<?> type=Class.forName("com.unity3d.player.UnityPlayer",false,activity.getClass().getClassLoader());
                player=find(activity.getWindow().getDecorView(),type,0);
                if(player==null)return false;
                inject=type.getMethod("injectEvent",InputEvent.class);unity=new WeakReference<>(player);
            }
            Object accepted=inject.invoke(player,event);return !Boolean.FALSE.equals(accepted);
        }catch(ClassNotFoundException|NoSuchMethodException|LinkageError absent){return false;}
        catch(Exception error){throw new IllegalStateException("Game input dispatch failed",error);}
    }
    private static View find(View view,Class<?> type,int depth){
        if(type.isInstance(view))return view;
        if(depth>=64)return null;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),type,depth+1);if(found!=null)return found;}}
        return null;
    }
}
