import io.github.hcode91.pocketouya.bridge.OuyaPadSink;
import tv.ouya.sdk.OuyaUnityActivity;
public final class OuyaPadSinkTest {
    public static void main(String[] args)throws Exception{
        OuyaUnityActivity activity=new OuyaUnityActivity();OuyaPadSink sink=new OuyaPadSink(activity);
        sink.key(96,0);sink.key(96,1);sink.key(99,0);sink.axis(0,-1);sink.axis(1,1);sink.axis(11,0.5f);sink.axis(14,-1);
        if(!activity.events.equals(java.util.Arrays.asList("key:0:96:0","key:0:96:1","key:0:99:0","axis:0:0:-1.0","axis:0:1:1.0","axis:0:11:0.5","axis:0:14:-1.0")))throw new AssertionError("Wrong player/key/axis dispatch");
        System.out.println("OUYA private input handler reflection and player-1 argument tests pass");
    }
}
