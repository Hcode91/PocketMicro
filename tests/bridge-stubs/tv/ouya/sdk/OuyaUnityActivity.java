package tv.ouya.sdk;
public class OuyaUnityActivity extends android.app.Activity {
    public final java.util.List<String> events=new java.util.ArrayList<>();
    private void dispatchKeyEventNative(int player,int key,int action){events.add("key:"+player+":"+key+":"+action);}
    private void dispatchGenericMotionEventNative(int player,int axis,float value){events.add("axis:"+player+":"+axis+":"+value);}
    public String getPackageName(){return "com.juicybeast.KnightmareTower";}
    public android.content.SharedPreferences getSharedPreferences(String n,int mode){return null;}
}
