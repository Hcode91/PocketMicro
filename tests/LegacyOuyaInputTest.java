package com.hcode91.pocketmicro;

import com.hcode91.pocketmicro.bridge.*;
import java.util.*;
import org.json.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;

/** Synthetic SDK contracts and in-process message consumer; no proprietary assemblies. */
public final class LegacyOuyaInputTest {
    static void check(boolean b){if(!b)throw new AssertionError("Legacy OUYA input regression");}
    static ImmutableField field(String owner,String name,String type){return new ImmutableField(owner,name,type,1,null,Set.of(),Set.of());}
    static Method method(String owner,String name,List<String> params,String result,int regs,List<Instruction> code){List<ImmutableMethodParameter> p=new ArrayList<>();for(String type:params)p.add(new ImmutableMethodParameter(type,Set.of(),null));return new ImmutableMethod(owner,name,p,result,1,Set.of(),Set.of(),new ImmutableMethodImplementation(regs,code,List.of(),List.of()));}
    static ImmutableClassDef cls(String type,String parent,List<Field> fields,List<Method> methods){return new ImmutableClassDef(type,1,parent,List.of(),null,Set.of(),fields,methods);}
    static Method sender(String owner,String argument,String target){return method(owner,"GenericSendMessage",List.of("Ljava/lang/String;",argument),"V",4,List.of(
        new ImmutableInstruction21c(Opcode.NEW_INSTANCE,0,new ImmutableTypeReference("Lcom/google/gson/Gson;")),
        new ImmutableInstruction35c(Opcode.INVOKE_DIRECT,1,0,0,0,0,0,new ImmutableMethodReference("Lcom/google/gson/Gson;","<init>",List.of(),"V")),
        new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,2,0,3,0,0,0,new ImmutableMethodReference("Lcom/google/gson/Gson;","toJson",List.of("Ljava/lang/Object;"),"Ljava/lang/String;")),
        new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,0),new ImmutableInstruction21c(Opcode.CONST_STRING,1,new ImmutableStringReference(target)),
        new ImmutableInstruction35c(Opcode.INVOKE_STATIC,3,1,2,0,0,0,new ImmutableMethodReference("Lcom/unity3d/player/UnityPlayer;","UnitySendMessage",List.of("Ljava/lang/String;","Ljava/lang/String;","Ljava/lang/String;"),"V")),new ImmutableInstruction10x(Opcode.RETURN_VOID)));}
    static byte[] fixture(String owner,boolean wrongField,boolean wrongCallback,boolean wrongTarget)throws Exception {
        String prefix=owner.substring(0,owner.length()-1),input=prefix+"$InputContainer;",device=prefix+"$Device;";
        List<Field> inputs=new ArrayList<>();for(String n:List.of("AxisX","AxisY","AxisZ","AxisRZ"))inputs.add(field(input,n,wrongField&&n.equals("AxisZ")?"I":"F"));for(String n:List.of("DeviceId","ActionCode"))inputs.add(field(input,n,"I"));inputs.add(field(input,"DeviceName","Ljava/lang/String;"));inputs.add(field(input,"KeyEvent","Landroid/view/KeyEvent;"));
        List<Field> devices=new ArrayList<>();for(String n:List.of("id","player","playerNum"))devices.add(field(device,n,"I"));devices.add(field(device,"name","Ljava/lang/String;"));
        List<Method> methods=new ArrayList<>();methods.add(sender(owner,input,wrongTarget?"AnotherObject":"OuyaGameObject"));methods.add(sender(owner,"Ljava/util/ArrayList;","OuyaGameObject"));
        methods.add(method(owner,"checkDevices",List.of(),"Ljava/util/ArrayList;",2,List.of(new ImmutableInstruction35c(Opcode.INVOKE_STATIC,0,0,0,0,0,0,new ImmutableMethodReference("Landroid/view/InputDevice;","getDeviceIds",List.of(),"[I")),new ImmutableInstruction21c(Opcode.NEW_INSTANCE,0,new ImmutableTypeReference("Ljava/util/ArrayList;")),new ImmutableInstruction35c(Opcode.INVOKE_DIRECT,1,0,0,0,0,0,new ImmutableMethodReference("Ljava/util/ArrayList;","<init>",List.of(),"V")),new ImmutableInstruction11x(Opcode.RETURN_OBJECT,0))));
        for(String name:List.of("onKeyDown","onKeyUp","onGenericMotionEvent","sendDevices")){
            boolean snapshot=name.equals("sendDevices"),motion=name.equals("onGenericMotionEvent");List<String> params=snapshot?List.of():motion?List.of("Landroid/view/MotionEvent;"):List.of("I","Landroid/view/KeyEvent;");int receiver=2;
            List<Instruction> code=new ArrayList<>();if(snapshot){code.add(new ImmutableInstruction35c(Opcode.INVOKE_DIRECT,1,receiver,0,0,0,0,new ImmutableMethodReference(owner,"checkDevices",List.of(),"Ljava/util/ArrayList;")));code.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));}else code.add(new ImmutableInstruction11n(Opcode.CONST_4,0,0));code.add(new ImmutableInstruction21c(Opcode.CONST_STRING,1,new ImmutableStringReference(snapshot?"onSetDevices":wrongCallback&&name.equals("onKeyDown")?"different":name)));
            code.add(new ImmutableInstruction35c(Opcode.INVOKE_DIRECT,3,receiver,1,0,0,0,new ImmutableMethodReference(owner,"GenericSendMessage",List.of("Ljava/lang/String;",snapshot?"Ljava/util/ArrayList;":input),"V")));
            if(snapshot)code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));else{code.add(new ImmutableInstruction11n(Opcode.CONST_4,0,1));code.add(new ImmutableInstruction11x(Opcode.RETURN,0));}
            methods.add(method(owner,name,params,snapshot?"V":"Z",3+params.size(),code));
        }
        var activity=cls(owner,"Landroid/app/Activity;",List.of(field(owner,"m_enableInput","Ljava/lang/Boolean;"),field(owner,"m_enableUnity","Ljava/lang/Boolean;")),methods);
        var player=cls("Lcom/unity3d/player/UnityPlayer;","Ljava/lang/Object;",List.of(),List.of(GeneralCompatibilityTest.method("Lcom/unity3d/player/UnityPlayer;","UnitySendMessage",GeneralCompatibilityTest.params("Ljava/lang/String;","Ljava/lang/String;","Ljava/lang/String;"),9,false,List.of(new ImmutableInstruction10x(Opcode.RETURN_VOID)))));
        return GeneralCompatibilityTest.dex(activity,cls(input,"Ljava/lang/Object;",inputs,List.of()),cls(device,"Ljava/lang/Object;",devices,List.of()),player);
    }
    public static final class Device {public int id=17,player=2,playerNum=1;public String name="Physical pad Ω \"quoted\"";}
    public static final class ActivityFixture extends android.app.Activity {
        public Boolean m_enableInput=true,m_enableUnity=true;final ArrayList<Device> physical=new ArrayList<>();
        ActivityFixture(){physical.add(new Device());}
        private ArrayList<Device> checkDevices(){return new ArrayList<>(physical);}
        public String getPackageName(){return "example.other";}public android.content.SharedPreferences getSharedPreferences(String name,int mode){return null;}
    }
    public static void main(String[]args)throws Exception {
        for(String owner:List.of("Lother/studio/LegacyGame;","Lunseen/project/MainActivity;")){
            byte[] before=fixture(owner,false,false,false);var result=GameCompatibilityPatcher.patch(Map.of("classes.dex",before),Set.of(owner),true);
            check(result.report.contains("1 use the legacy OUYA JSON")&&result.touchEnabled);
            Set<String> calls=new HashSet<>();for(ClassDef c:new DexBackedDexFile(null,result.dex.get("classes.dex")).getClasses())for(Method m:c.getMethods())if(m.getImplementation()!=null)for(Instruction i:m.getImplementation().getInstructions())if(i instanceof ReferenceInstruction&&((ReferenceInstruction)i).getReference() instanceof org.jf.dexlib2.iface.reference.MethodReference){var r=(org.jf.dexlib2.iface.reference.MethodReference)((ReferenceInstruction)i).getReference();if(r.getDefiningClass().endsWith("/TouchControls;"))calls.add(r.getName());}
            check(calls.containsAll(Set.of("watchLegacy","installLegacy","release")));
            for(int bad=0;bad<3;bad++){byte[] near=fixture(owner,bad==0,bad==1,bad==2);var rejected=GameCompatibilityPatcher.patch(Map.of("classes.dex",near),Set.of(owner),true);check(!rejected.report.contains("1 use the legacy OUYA JSON"));}
        }
        com.unity3d.player.UnityPlayer.messages.clear();ActivityFixture activity=new ActivityFixture();LegacyOuyaPadSink sink=new LegacyOuyaPadSink(activity);VirtualPad pad=new VirtualPad(sink);Object owner=new Object();pad.hold(VirtualPad.O,owner,true);
        var messages=com.unity3d.player.UnityPlayer.messages;check(messages.size()==2&&messages.get(0)[0].equals("onSetDevices")&&messages.get(1)[0].equals("onKeyDown"));
        JSONArray devices=new JSONArray(messages.get(0)[1]);check(devices.length()==2&&devices.getJSONObject(0).getString("name").equals(activity.physical.get(0).name));check(devices.getJSONObject(1).getInt("player")==1&&devices.getJSONObject(1).getInt("playerNum")==0&&devices.getJSONObject(1).getInt("id")==LegacyOuyaPadSink.DEVICE_ID);check(activity.physical.size()==1);
        JSONObject key=new JSONObject(messages.get(1)[1]);check(key.getInt("DeviceId")==LegacyOuyaPadSink.DEVICE_ID&&key.getString("DeviceName").equals("OUYA Game Controller"));check(key.getJSONObject("KeyEvent").getInt("mKeyCode")==96&&key.getJSONObject("KeyEvent").getInt("mRepeatCount")==0);
        pad.hold(VirtualPad.RIGHT,owner,true);pad.rightStick(0.5f,-0.75f);JSONObject axis=new JSONObject(messages.get(messages.size()-1)[1]);check(axis.getDouble("AxisX")==1&&axis.getDouble("AxisZ")==0.5&&axis.getDouble("AxisRZ")==-0.75);
        pad.releaseAll();axis=new JSONObject(messages.get(messages.size()-1)[1]);check(axis.getDouble("AxisX")==0&&axis.getDouble("AxisY")==0&&axis.getDouble("AxisZ")==0&&axis.getDouble("AxisRZ")==0);
        int count=messages.size();activity.m_enableInput=false;sink.key(96,0);check(messages.size()==count);activity.m_enableInput=true;activity.m_enableUnity=false;sink.axis(0,1);check(messages.size()==count);
        activity.m_enableUnity=true;activity.physical.get(0).id=LegacyOuyaPadSink.DEVICE_ID;try{sink.key(96,0);throw new AssertionError("Device collision accepted");}catch(IllegalStateException expected){}
        messages.clear();
        System.out.println("Legacy OUYA JSON: renamed activity contracts, wrong-field/callback/target rejection, lifecycle route, device announcement, physical preservation, player numbering, keys/sticks, cleanup, disabled gates and collision rejection pass");
    }
}
