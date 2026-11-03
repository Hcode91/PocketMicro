package io.github.hozdogus.pocketouya;

import java.io.IOException;
import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/** Best-effort API matching across APKs. No title IDs, receipt invention for unknown protocols, or silent success. */
public final class GameCompatibilityPatcher {
    private static final String BRIDGE="Lio/github/hcode91/pocketouya/bridge/";
    private static final Set<String> ACTIVITY_BASES=new HashSet<>(Arrays.asList("Landroid/app/Activity;","Landroid/app/NativeActivity;","Landroid/app/ListActivity;","Landroid/app/TabActivity;","Landroid/app/ActivityGroup;","Landroid/preference/PreferenceActivity;","Landroid/app/ExpandableListActivity;"));
    public static final class Result {
        public final Map<String,byte[]> dex = new LinkedHashMap<>();
        public boolean touchEnabled;
        public String report;
        public boolean needsBridge(){return touchEnabled;}
    }
    private GameCompatibilityPatcher(){}
    private static String signature(Method m){return "("+String.join("",m.getParameterTypes())+")"+m.getReturnType();}
    private static boolean concrete(Method m){return m.getImplementation()!=null&&!AccessFlags.STATIC.isSet(m.getAccessFlags())&&!AccessFlags.ABSTRACT.isSet(m.getAccessFlags())&&!AccessFlags.NATIVE.isSet(m.getAccessFlags());}
    private static Method method(ClassDef cls,String name,String sig){
        if(cls==null)return null;for(Method m:cls.getMethods())if(m.getName().equals(name)&&signature(m).equals(sig))return m;return null;
    }
    private static boolean activity(String type,Map<String,ClassDef> classes){
        Set<String> seen=new HashSet<>();
        while(type!=null&&seen.add(type)) {if(ACTIVITY_BASES.contains(type))return true;ClassDef cls=classes.get(type);if(cls==null)return false;type=cls.getSuperclass();}
        return false;
    }
    private static boolean overridable(ClassDef cls,String name,String signature,Map<String,ClassDef> classes){
        Set<String> seen=new HashSet<>();String parent=cls.getSuperclass();
        while(parent!=null&&seen.add(parent)) {
            ClassDef ancestor=classes.get(parent);if(ancestor==null)return ACTIVITY_BASES.contains(parent);
            Method m=method(ancestor,name,signature);
            if(m!=null)return !AccessFlags.FINAL.isSet(m.getAccessFlags())&&!AccessFlags.STATIC.isSet(m.getAccessFlags())&&!AccessFlags.PRIVATE.isSet(m.getAccessFlags());
            parent=ancestor.getSuperclass();
        }
        return false;
    }
    private static boolean canHook(ClassDef cls,Map<String,ClassDef> classes){
        if(cls==null||AccessFlags.INTERFACE.isSet(cls.getAccessFlags())||AccessFlags.ABSTRACT.isSet(cls.getAccessFlags())||!activity(cls.getType(),classes))return false;
        for(Method m:cls.getMethods())if(m.getName().startsWith("pocketouyaOriginal_"))return false;
        for(String name:new String[]{"onCreate","onResume","onPause"}) {
            String sig=name.equals("onCreate")?"(Landroid/os/Bundle;)V":"()V";
            Method m=method(cls,name,sig);
            if(m!=null&&!concrete(m))return false;
            if(m==null&&!overridable(cls,name,sig,classes))return false;
        }
        return true;
    }
    private static boolean nativeRoute(ClassDef cls,Map<String,ClassDef> classes){
        Method keys=method(classes.get("Ltv/ouya/sdk/OuyaUnityActivity;"),"dispatchKeyEventNative","(III)V");
        Method axes=method(classes.get("Ltv/ouya/sdk/OuyaUnityActivity;"),"dispatchGenericMotionEventNative","(IIF)V");
        if(keys==null||axes==null||!AccessFlags.NATIVE.isSet(keys.getAccessFlags())||!AccessFlags.NATIVE.isSet(axes.getAccessFlags())||AccessFlags.STATIC.isSet(keys.getAccessFlags())||AccessFlags.STATIC.isSet(axes.getAccessFlags()))return false;
        Set<String> seen=new HashSet<>();String type=cls.getType();
        while(type!=null&&seen.add(type)){if(type.equals("Ltv/ouya/sdk/OuyaUnityActivity;"))return true;ClassDef p=classes.get(type);if(p==null)break;type=p.getSuperclass();}
        return false;
    }
    private static boolean fields(ClassDef cls,String...pairs){
        if(cls==null)return false;for(int n=0;n<pairs.length;n+=2){boolean found=false;for(Field f:cls.getFields())if(f.getName().equals(pairs[n])&&f.getType().equals(pairs[n+1])&&!AccessFlags.STATIC.isSet(f.getAccessFlags()))found=true;if(!found)return false;}return true;
    }
    private static boolean sendsJson(Method m){
        if(m==null||!concrete(m))return false;boolean target=false,json=false,send=false;
        for(Instruction i:m.getImplementation().getInstructions())if(i instanceof ReferenceInstruction){Object r=((ReferenceInstruction)i).getReference();
            if(r instanceof StringReference&&((StringReference)r).getString().equals("OuyaGameObject"))target=true;
            if(r instanceof MethodReference){MethodReference ref=(MethodReference)r;
                if(ref.getDefiningClass().equals("Lcom/google/gson/Gson;")&&ref.getName().equals("toJson")&&ref.getParameterTypes().equals(Collections.singletonList("Ljava/lang/Object;"))&&ref.getReturnType().equals("Ljava/lang/String;"))json=true;
                if(i.getOpcode()==Opcode.INVOKE_STATIC||i.getOpcode()==Opcode.INVOKE_STATIC_RANGE)if(ref.getDefiningClass().equals("Lcom/unity3d/player/UnityPlayer;")&&ref.getName().equals("UnitySendMessage")&&ref.getParameterTypes().equals(Arrays.asList("Ljava/lang/String;","Ljava/lang/String;","Ljava/lang/String;"))&&ref.getReturnType().equals("V"))send=true;
            }
        }return target&&json&&send;
    }
    private static boolean callback(Method m,String owner,String argument,String label){
        if(m==null||!concrete(m))return false;boolean name=false,call=false;
        for(Instruction i:m.getImplementation().getInstructions())if(i instanceof ReferenceInstruction){Object r=((ReferenceInstruction)i).getReference();
            if(r instanceof StringReference&&((StringReference)r).getString().equals(label))name=true;
            if(r instanceof MethodReference){MethodReference ref=(MethodReference)r;if(ref.getDefiningClass().equals(owner)&&ref.getName().equals("GenericSendMessage")&&ref.getParameterTypes().equals(Arrays.asList("Ljava/lang/String;",argument))&&ref.getReturnType().equals("V"))call=true;}
        }return name&&call;
    }
    static boolean legacyJsonRoute(ClassDef cls,Map<String,ClassDef> classes){
        String owner=cls.getType(),prefix=owner.substring(0,owner.length()-1),input=prefix+"$InputContainer;",device=prefix+"$Device;";
        if(!fields(cls,"m_enableInput","Ljava/lang/Boolean;","m_enableUnity","Ljava/lang/Boolean;")
            ||!fields(classes.get(input),"DeviceId","I","DeviceName","Ljava/lang/String;","ActionCode","I","KeyEvent","Landroid/view/KeyEvent;","AxisX","F","AxisY","F","AxisZ","F","AxisRZ","F")
            ||!fields(classes.get(device),"id","I","player","I","playerNum","I","name","Ljava/lang/String;"))return false;
        Method snapshot=method(cls,"checkDevices","()Ljava/util/ArrayList;");
        if(snapshot==null||!concrete(snapshot))return false;boolean enumeration=false;
        for(Instruction i:snapshot.getImplementation().getInstructions())if(i instanceof ReferenceInstruction&&((ReferenceInstruction)i).getReference() instanceof MethodReference){MethodReference ref=(MethodReference)((ReferenceInstruction)i).getReference();if(ref.getDefiningClass().equals("Landroid/view/InputDevice;")&&ref.getName().equals("getDeviceIds")&&ref.getParameterTypes().isEmpty()&&ref.getReturnType().equals("[I"))enumeration=true;}
        Method send=method(classes.get("Lcom/unity3d/player/UnityPlayer;"),"UnitySendMessage","(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        boolean usedSnapshot=false;Method announce=method(cls,"sendDevices","()V");
        if(announce!=null&&announce.getImplementation()!=null)for(Instruction i:announce.getImplementation().getInstructions())if(i instanceof ReferenceInstruction&&((ReferenceInstruction)i).getReference() instanceof MethodReference){MethodReference ref=(MethodReference)((ReferenceInstruction)i).getReference();if(ref.getDefiningClass().equals(owner)&&ref.getName().equals("checkDevices")&&ref.getParameterTypes().isEmpty()&&ref.getReturnType().equals("Ljava/util/ArrayList;"))usedSnapshot=true;}
        return enumeration&&usedSnapshot&&send!=null&&AccessFlags.PUBLIC.isSet(send.getAccessFlags())&&AccessFlags.STATIC.isSet(send.getAccessFlags())
            &&sendsJson(method(cls,"GenericSendMessage","(Ljava/lang/String;"+input+")V"))&&sendsJson(method(cls,"GenericSendMessage","(Ljava/lang/String;Ljava/util/ArrayList;)V"))
            &&callback(method(cls,"onKeyDown","(ILandroid/view/KeyEvent;)Z"),owner,input,"onKeyDown")&&callback(method(cls,"onKeyUp","(ILandroid/view/KeyEvent;)Z"),owner,input,"onKeyUp")
            &&callback(method(cls,"onGenericMotionEvent","(Landroid/view/MotionEvent;)Z"),owner,input,"onGenericMotionEvent")&&callback(method(cls,"sendDevices","()V"),owner,"Ljava/util/ArrayList;","onSetDevices");
    }
    private static boolean marmaladeRoute(ClassDef cls,Map<String,ClassDef> classes){
        String loader="Lcom/ideaworks3d/marmalade/LoaderActivity;";
        Method down=method(classes.get(loader),"onKeyDown","(ILandroid/view/KeyEvent;)Z"),up=method(classes.get(loader),"onKeyUp","(ILandroid/view/KeyEvent;)Z");
        Method input=method(classes.get("Lcom/ideaworks3d/marmalade/LoaderKeyboard;"),"onKeyEventNative","(III)Z");
        if(down==null||up==null||!concrete(down)||!concrete(up)||!AccessFlags.PUBLIC.isSet(down.getAccessFlags())||!AccessFlags.PUBLIC.isSet(up.getAccessFlags())||input==null||!AccessFlags.NATIVE.isSet(input.getAccessFlags())||AccessFlags.STATIC.isSet(input.getAccessFlags()))return false;
        Set<String> seen=new HashSet<>();String type=cls.getType();
        while(type!=null&&seen.add(type)){if(type.equals(loader))return true;ClassDef parent=classes.get(type);if(parent==null)break;type=parent.getSuperclass();}return false;
    }
    public static Result patch(Map<String,byte[]> input,Set<String> launchers,boolean touch)throws IOException {
        try {
            Map<String,DexBackedDexFile> files=new LinkedHashMap<>();Map<String,ClassDef> classes=new HashMap<>();
            for(Map.Entry<String,byte[]> entry:input.entrySet()){
                DexBackedDexFile dex=new DexBackedDexFile(null,entry.getValue());files.put(entry.getKey(),dex);
                for(ClassDef cls:dex.getClasses()) {
                    if(cls.getType().startsWith(BRIDGE))throw new IOException("This APK already contains PocketOuya compatibility code. Reimport the original APK.");
                    if(classes.put(cls.getType(),cls)!=null)throw new IOException("Duplicate DEX class: "+cls.getType());
                }
            }
            Set<String> targets=new HashSet<>(),legacyTargets=new HashSet<>();int nativeTargets=0,marmaladeTargets=0;
            // Packed/legacy DEX can contain deliberate invalid instructions. A
            // serialization round trip may change these and break its loader.
            // Preserve the entire APK's DEX set rather than claim a usable hook.
            for(ClassDef cls:classes.values())for(Method m:cls.getMethods())if(m.getImplementation()!=null)
                for(Instruction instruction:m.getImplementation().getInstructions())
                    if(instruction instanceof org.jf.dexlib2.iface.instruction.formats.UnknownInstruction){
                        Result unsupported=new Result();
                        unsupported.report="Conversion compatibility report\n\nTouch changes skipped: the original DEX contains unknown or reserved instructions ("+cls.getType()+" "+m.getName()+"). This may be legacy runtime protection or malformed bytecode. Rewriting it cannot be treated as ordinary DEX conversion. All original DEX bytes were preserved; no compatibility helper was added.\n\nNormal manifest/signing conversion does not repair this runtime or guarantee the game can launch on modern Android. A compatible original build or a separate runtime investigation is required.";
                        return unsupported;
                    }
            if(touch)for(String launcher:launchers)if(canHook(classes.get(launcher),classes)){targets.add(launcher);if(nativeRoute(classes.get(launcher),classes))nativeTargets++;else if(marmaladeRoute(classes.get(launcher),classes))marmaladeTargets++;else if(legacyJsonRoute(classes.get(launcher),classes))legacyTargets.add(launcher);}
            Result result=new Result();result.touchEnabled=!targets.isEmpty();
            for(Map.Entry<String,DexBackedDexFile> file:files.entrySet()){
                DexPool pool=new DexPool(file.getValue().getOpcodes());boolean changed=false;
                for(ClassDef cls:file.getValue().getClasses()) {
                    if(targets.contains(cls.getType())){pool.internClass(hookActivity(cls,legacyTargets.contains(cls.getType())));changed=true;}
                    else pool.internClass(cls);
                }
                if(changed){MemoryDataStore out=new MemoryDataStore();pool.writeTo(out);result.dex.put(file.getKey(),out.getData());}
            }
            StringBuilder report=new StringBuilder("Conversion compatibility report\n\n");
            if(touch){
                if(result.touchEnabled)report.append("Touch pad: enabled for ").append(targets.size()).append(" launch activity/activities. ").append(nativeTargets).append(" use the OUYA native input route.\n");
                else report.append("Touch pad: unsupported launcher/activity layout; no touch hooks added.\n");
                if(marmaladeTargets>0)report.append(marmaladeTargets).append(" use direct Marmalade Activity key callbacks (including game overrides). Directions are digital keys; no physical controller or analog device is emulated.\n");
                if(!legacyTargets.isEmpty())report.append(legacyTargets.size()).append(" use the legacy OUYA JSON controller route: in-process player-1 device announcement, key callbacks and left/right stick axes. No physical Android device is created; phone input response remains unverified.\n");
                if(targets.size()>nativeTargets+marmaladeTargets+legacyTargets.size())report.append("Other input uses Android gamepad events, with a Keyboard option in the pad menu. Virtual events may be ignored by games that require a physical controller.\n");
                if(targets.size()<launchers.size())report.append("Some launch activities could not be patched; controls may not appear on those screens.\n");
            }
            report.append("\nThis does not fix incompatible native engines, unavailable services, custom/native DRM or billing systems. Reconvert original APKs to update embedded compatibility code.");
            result.report=report.toString();return result;
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Cannot inspect this game's DEX layout. Try normal conversion.",e);}
    }
    private static ClassDef hookActivity(ClassDef cls,boolean legacy){
        List<Method> methods=new ArrayList<>();
        for(Method m:cls.getMethods()) {
            if((m.getName().equals("onCreate")&&signature(m).equals("(Landroid/os/Bundle;)V"))||((m.getName().equals("onResume")||m.getName().equals("onPause"))&&signature(m).equals("()V"))) {
                int flags=(m.getAccessFlags()&~(AccessFlags.PUBLIC.getValue()|AccessFlags.PROTECTED.getValue()))|AccessFlags.PRIVATE.getValue()|AccessFlags.SYNTHETIC.getValue();
                methods.add(new ImmutableMethod(cls.getType(),"pocketouyaOriginal_"+m.getName(),m.getParameters(),"V",flags,m.getAnnotations(),m.getHiddenApiRestrictions(),m.getImplementation()));
            }else methods.add(m);
        }
        for(String name:new String[]{"onCreate","onResume","onPause"}) {
            List<String> params=name.equals("onCreate")?Collections.singletonList("Landroid/os/Bundle;"):Collections.emptyList();
            String sig="("+String.join("",params)+")V";
            Method original=method(cls,name,sig);List<Instruction> code=new ArrayList<>();
            ImmutableMethodReference originalRef=new ImmutableMethodReference(original==null?cls.getSuperclass():cls.getType(),original==null?name:"pocketouyaOriginal_"+name,params,"V");
            if(name.equals("onPause"))code.add(touchCall("release"));
            code.add(new ImmutableInstruction3rc(original==null?Opcode.INVOKE_SUPER_RANGE:Opcode.INVOKE_DIRECT_RANGE,0,1+params.size(),originalRef));
            if(name.equals("onResume"))code.add(touchCall(legacy?"installLegacy":"install"));
            if(name.equals("onCreate"))code.add(touchCall(legacy?"watchLegacy":"watch"));
            code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
            methods.add(new ImmutableMethod(cls.getType(),name,original==null?(params.isEmpty()?Collections.emptyList():Collections.singletonList(new ImmutableMethodParameter("Landroid/os/Bundle;",Collections.emptySet(),null))):original.getParameters(),"V",original==null?AccessFlags.PROTECTED.getValue():original.getAccessFlags(),original==null?Collections.emptySet():original.getAnnotations(),original==null?Collections.emptySet():original.getHiddenApiRestrictions(),new ImmutableMethodImplementation(1+params.size(),code,Collections.emptyList(),Collections.emptyList())));
        }
        return copy(cls,methods);
    }
    private static Instruction touchCall(String method){return new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE,0,1,new ImmutableMethodReference(BRIDGE+"TouchControls;",method,Collections.singletonList("Landroid/app/Activity;"),"V"));}
    private static ClassDef copy(ClassDef cls,List<Method> methods){return new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods);}
}
