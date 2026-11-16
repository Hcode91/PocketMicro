package com.hcode91.pocketmicro.bridge;

import android.app.Activity;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Calls the game's own Activity key callbacks; no physical InputDevice is required. */
public final class MarmaladePadSink implements VirtualPad.Sink {
    private final Activity activity;
    private final boolean keyboard;
    private final Method down,up;
    private final Map<Integer,Long> downTimes=new HashMap<>();
    public MarmaladePadSink(Activity activity,boolean keyboard)throws Exception{
        Class<?> loader=Class.forName("com.ideaworks3d.marmalade.LoaderActivity",false,activity.getClass().getClassLoader());
        if(!loader.isInstance(activity))throw new IllegalArgumentException("Not a Marmalade activity");
        this.activity=activity;this.keyboard=keyboard;
        down=activity.getClass().getMethod("onKeyDown",int.class,KeyEvent.class);
        up=activity.getClass().getMethod("onKeyUp",int.class,KeyEvent.class);
    }
    public void key(int key,int action){
        if(action!=0&&action!=1)throw new IllegalArgumentException("Invalid key action");
        int mapped=keyboard?KeyboardPadMap.key(key):key;long now=SystemClock.uptimeMillis();
        if(action==0)downTimes.put(mapped,now);Long began=downTimes.get(mapped);
        KeyEvent event=new KeyEvent(began==null?now:began,now,action,mapped,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,
                keyboard?InputDevice.SOURCE_KEYBOARD:InputDevice.SOURCE_GAMEPAD);
        try{(action==0?down:up).invoke(activity,mapped,event);}
        catch(Exception error){throw new IllegalStateException("Marmalade key callback failed",error);}
        finally{if(action==1)downTimes.remove(mapped);}
    }
    // VirtualPad already emits directional key-down/up events. This route provides
    // digital directions; it does not invent a physical controller or analog axes.
    public void axis(int axis,float value){}
}
