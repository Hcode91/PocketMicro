package io.github.hcode91.pocketouya.bridge;

import android.app.Activity;
import java.lang.reflect.Method;

/** Calls the verified game-owned JNI handlers directly; never injects OS input. */
public final class OuyaPadSink implements VirtualPad.Sink {
    private final Activity activity;
    private final Method keys,axes;
    public OuyaPadSink(Activity activity)throws Exception {
        Class<?> sdk=Class.forName("tv.ouya.sdk.OuyaUnityActivity",false,activity.getClass().getClassLoader());
        if(!sdk.isInstance(activity))throw new IllegalArgumentException("Not an OUYA Unity activity");
        this.activity=activity;
        keys=sdk.getDeclaredMethod("dispatchKeyEventNative",int.class,int.class,int.class);
        axes=sdk.getDeclaredMethod("dispatchGenericMotionEventNative",int.class,int.class,float.class);
        keys.setAccessible(true);axes.setAccessible(true);
    }
    public void key(int key,int action){invoke(keys,0,key,action);}
    public void axis(int axis,float value){invoke(axes,0,axis,value);}
    private void invoke(Method method,Object...args){
        try{method.invoke(activity,args);}catch(Exception error){throw new IllegalStateException("OUYA native input dispatch failed",error);}
    }
}
