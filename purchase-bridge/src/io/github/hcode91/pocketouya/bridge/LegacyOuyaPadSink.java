package io.github.hcode91.pocketouya.bridge;

import android.app.Activity;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Opted into only by the converter's legacy OUYA JSON contract check. No OS device injection. */
public final class LegacyOuyaPadSink implements VirtualPad.Sink {
    public static final int DEVICE_ID=-20559;
    private final Activity activity;
    private final Method devices,send;
    private final Field inputEnabled,unityEnabled;
    private float x,y,z,rz;
    public LegacyOuyaPadSink(Activity activity)throws Exception {
        this.activity=activity;Class<?> owner=activity.getClass();Method snapshot=null;
        while(owner!=null&&owner!=Activity.class){try{snapshot=owner.getDeclaredMethod("checkDevices");break;}catch(NoSuchMethodException missing){owner=owner.getSuperclass();}}
        if(snapshot==null||snapshot.getReturnType()!=ArrayList.class||Modifier.isStatic(snapshot.getModifiers()))throw new IllegalArgumentException("Legacy input device snapshot unavailable");
        devices=snapshot;devices.setAccessible(true);
        inputEnabled=owner.getDeclaredField("m_enableInput");unityEnabled=owner.getDeclaredField("m_enableUnity");
        if(inputEnabled.getType()!=Boolean.class||unityEnabled.getType()!=Boolean.class)throw new IllegalArgumentException("Unsupported legacy input gates");
        inputEnabled.setAccessible(true);unityEnabled.setAccessible(true);
        Class<?> player=Class.forName("com.unity3d.player.UnityPlayer",false,activity.getClass().getClassLoader());
        send=player.getMethod("UnitySendMessage",String.class,String.class,String.class);
        if(!Modifier.isStatic(send.getModifiers())||send.getReturnType()!=void.class)throw new IllegalArgumentException("Unity message route unavailable");
    }
    private boolean enabled()throws IllegalAccessException{return Boolean.TRUE.equals(inputEnabled.get(activity))&&Boolean.TRUE.equals(unityEnabled.get(activity));}
    private static Object field(Object item,String name,Class<?> type)throws Exception {
        Field f=item.getClass().getDeclaredField(name);if(f.getType()!=type||Modifier.isStatic(f.getModifiers()))throw new IllegalArgumentException("Unsupported device descriptor");f.setAccessible(true);return f.get(item);
    }
    private void announce()throws Exception {
        Object snapshot=devices.invoke(activity);if(!(snapshot instanceof ArrayList)||((ArrayList<?>)snapshot).size()>64)throw new IllegalStateException("Unsupported device snapshot");
        JSONArray all=new JSONArray();for(Object device:(ArrayList<?>)snapshot){
            if(device==null)throw new IllegalStateException("Null device descriptor");
            int id=(Integer)field(device,"id",int.class);if(id==DEVICE_ID)throw new IllegalStateException("Virtual input device ID collision");
            all.put(new JSONObject().put("id",id).put("name",field(device,"name",String.class)).put("player",field(device,"player",int.class)).put("playerNum",field(device,"playerNum",int.class)));
        }
        // Legacy managed OuyaPlayer starts at 1; Android OUYA playerNum starts at 0.
        all.put(new JSONObject().put("id",DEVICE_ID).put("name","OUYA Game Controller").put("player",1).put("playerNum",0));
        // Reannounce before input: the managed GameObject may not exist when Android resumes.
        message("onSetDevices",all.toString());
    }
    private JSONObject input()throws JSONException{return new JSONObject().put("DeviceId",DEVICE_ID).put("DeviceName","OUYA Game Controller");}
    public void key(int key,int action){
        if(action!=0&&action!=1)throw new IllegalArgumentException("Unsupported key action");
        try{if(!enabled())return;announce();JSONObject event=new JSONObject().put("mDeviceId",DEVICE_ID).put("mKeyCode",key).put("mAction",action).put("mRepeatCount",0);
            message(action==0?"onKeyDown":"onKeyUp",input().put("ActionCode",action).put("KeyCode",key).put("KeyEvent",event).toString());
        }catch(Exception error){throw new IllegalStateException("Legacy OUYA JSON key dispatch failed",error);}
    }
    public void axis(int axis,float value){
        if(Float.isNaN(value)||Float.isInfinite(value)||value < -1 ||value > 1)throw new IllegalArgumentException("Invalid axis value");
        if(axis==0)x=value;else if(axis==1)y=value;else if(axis==11)z=value;else if(axis==14)rz=value;else throw new IllegalArgumentException("Unsupported stick axis");
        try{if(!enabled())return;announce();message("onGenericMotionEvent",input().put("ActionCode",2).put("AxisX",x).put("AxisY",y).put("AxisZ",z).put("AxisRZ",rz).toString());}
        catch(Exception error){throw new IllegalStateException("Legacy OUYA JSON motion dispatch failed",error);}
    }
    private void message(String callback,String json)throws Exception{send.invoke(null,"OuyaGameObject",callback,json);}
}
