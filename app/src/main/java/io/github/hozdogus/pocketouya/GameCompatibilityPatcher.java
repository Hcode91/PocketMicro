package io.github.hozdogus.pocketouya;

import java.io.IOException;
import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/** Best-effort API matching across APKs. No title IDs, receipt invention for unknown protocols, or silent success. */
public final class GameCompatibilityPatcher {
    private static final String BRIDGE="Lio/github/hcode91/pocketouya/bridge/";
    private static final String PLUGIN="Ltv/ouya/sdk/OuyaUnityPlugin;";
    private static final Set<String> ACTIVITY_BASES=new HashSet<>(Arrays.asList("Landroid/app/Activity;","Landroid/app/NativeActivity;","Landroid/app/ListActivity;","Landroid/app/TabActivity;","Landroid/app/ActivityGroup;","Landroid/preference/PreferenceActivity;","Landroid/app/ExpandableListActivity;"));
    private static final Map<String,String> PURCHASES=new LinkedHashMap<>();
    private static final Set<String> CALLBACKS=new HashSet<>(Arrays.asList("OuyaGameObject","ProductListClearListener","ProductListListener","ProductListCompleteListener","ReceiptListClearListener","ReceiptListListener","ReceiptListCompleteListener","PurchaseSuccessListener","FetchGamerInfoSuccessListener"));
    static {
        PURCHASES.put("addGetProduct","(Ljava/lang/String;)V");PURCHASES.put("clearGetProductList","()V");
        PURCHASES.put("getProductsAsync","()V");PURCHASES.put("getReceiptsAsync","()V");
        PURCHASES.put("fetchGamerInfo","()V");PURCHASES.put("requestPurchaseAsync","(Ljava/lang/String;)Ljava/lang/String;");
    }
    public static final class Result {
        public final Map<String,byte[]> dex = new LinkedHashMap<>();
        public boolean touchEnabled,purchasesEnabled,serverEnabled;
        public String report;
        public boolean needsBridge(){return touchEnabled||purchasesEnabled;}
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
    private static boolean purchases(Map<String,ClassDef> classes){
        ClassDef plugin=classes.get(PLUGIN),unity=classes.get("Lcom/unity3d/player/UnityPlayer;");
        if(plugin==null||unity==null)return false;
        boolean activityField=false;for(Field f:unity.getFields())if(f.getName().equals("currentActivity")&&f.getType().equals("Landroid/app/Activity;")&&AccessFlags.PUBLIC.isSet(f.getAccessFlags())&&AccessFlags.STATIC.isSet(f.getAccessFlags()))activityField=true;
        Method send=method(unity,"UnitySendMessage","(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if(!activityField||send==null||!AccessFlags.PUBLIC.isSet(send.getAccessFlags())||!AccessFlags.STATIC.isSet(send.getAccessFlags()))return false;
        for(Map.Entry<String,String> entry:PURCHASES.entrySet()){
            Method m=method(plugin,entry.getKey(),entry.getValue());
            if(m==null||m.getImplementation()==null||!AccessFlags.STATIC.isSet(m.getAccessFlags())||!AccessFlags.PUBLIC.isSet(m.getAccessFlags()))return false;
        }
        Set<String> strings=new HashSet<>();
        for(ClassDef cls:classes.values())if(cls.getType().startsWith("Ltv/ouya/sdk/UnityOuyaFacade"))for(Method m:cls.getMethods())if(m.getImplementation()!=null)for(Instruction i:m.getImplementation().getInstructions())if(i instanceof ReferenceInstruction&&((ReferenceInstruction)i).getReference() instanceof StringReference)strings.add(((StringReference)((ReferenceInstruction)i).getReference()).getString());
        return strings.containsAll(CALLBACKS);
    }
    private static boolean marmaladeRoute(ClassDef cls,Map<String,ClassDef> classes){
        String loader="Lcom/ideaworks3d/marmalade/LoaderActivity;";
        Method down=method(classes.get(loader),"onKeyDown","(ILandroid/view/KeyEvent;)Z"),up=method(classes.get(loader),"onKeyUp","(ILandroid/view/KeyEvent;)Z");
        Method input=method(classes.get("Lcom/ideaworks3d/marmalade/LoaderKeyboard;"),"onKeyEventNative","(III)Z");
        if(down==null||up==null||!concrete(down)||!concrete(up)||!AccessFlags.PUBLIC.isSet(down.getAccessFlags())||!AccessFlags.PUBLIC.isSet(up.getAccessFlags())||input==null||!AccessFlags.NATIVE.isSet(input.getAccessFlags())||AccessFlags.STATIC.isSet(input.getAccessFlags()))return false;
        Set<String> seen=new HashSet<>();String type=cls.getType();
        while(type!=null&&seen.add(type)){if(type.equals(loader))return true;ClassDef parent=classes.get(type);if(parent==null)break;type=parent.getSuperclass();}return false;
    }
    public static Result patch(Map<String,byte[]> input,Set<String> launchers,boolean touch,boolean offline)throws IOException {
        return patch(input,launchers,touch,offline,false);
    }
    public static Result patch(Map<String,byte[]> input,Set<String> launchers,boolean touch,boolean offline,boolean server)throws IOException {
        if(offline&&server)throw new IOException("Choose offline receipts or the server adapter, not both");
        try {
            Map<String,DexBackedDexFile> files=new LinkedHashMap<>();Map<String,ClassDef> classes=new HashMap<>();
            for(Map.Entry<String,byte[]> entry:input.entrySet()){
                DexBackedDexFile dex=new DexBackedDexFile(null,entry.getValue());files.put(entry.getKey(),dex);
                for(ClassDef cls:dex.getClasses()) {
                    if(cls.getType().startsWith(BRIDGE))throw new IOException("This APK already contains PocketOuya compatibility code. Reimport the original APK.");
                    if(classes.put(cls.getType(),cls)!=null)throw new IOException("Duplicate DEX class: "+cls.getType());
                }
            }
            Set<String> targets=new HashSet<>();int nativeTargets=0,marmaladeTargets=0;
            // Packed/legacy DEX can contain deliberate invalid instructions. A
            // serialization round trip may change these and break its loader.
            // Preserve the entire APK's DEX set rather than claim a usable hook.
            for(ClassDef cls:classes.values())for(Method m:cls.getMethods())if(m.getImplementation()!=null)
                for(Instruction instruction:m.getImplementation().getInstructions())
                    if(instruction instanceof org.jf.dexlib2.iface.instruction.formats.UnknownInstruction){
                        Result unsupported=new Result();
                        unsupported.report="Conversion compatibility report\n\nTouch/purchase changes skipped: the original DEX contains unknown or reserved instructions ("+cls.getType()+" "+m.getName()+"). This may be legacy runtime protection or malformed bytecode. Rewriting it cannot be treated as ordinary DEX conversion. All original DEX bytes were preserved; no compatibility helper was added.\n\nNormal manifest/signing conversion does not repair this runtime or guarantee the game can launch on modern Android. A compatible original build or a separate runtime investigation is required.";
                        return unsupported;
                    }
            if(touch)for(String launcher:launchers)if(canHook(classes.get(launcher),classes)){targets.add(launcher);if(nativeRoute(classes.get(launcher),classes))nativeTargets++;else if(marmaladeRoute(classes.get(launcher),classes))marmaladeTargets++;}
            Result result=new Result();result.touchEnabled=!targets.isEmpty();result.purchasesEnabled=(offline||server)&&purchases(classes);
            if(server){
                boolean developer=false;ClassDef plugin=classes.get(PLUGIN);
                if(plugin!=null)for(Field f:plugin.getFields())if(f.getName().equals("m_developerId")&&f.getType().equals("Ljava/lang/String;")&&AccessFlags.STATIC.isSet(f.getAccessFlags()))developer=true;
                result.purchasesEnabled&=developer;result.serverEnabled=result.purchasesEnabled;
            }
            for(Map.Entry<String,DexBackedDexFile> file:files.entrySet()){
                DexPool pool=new DexPool(file.getValue().getOpcodes());boolean changed=false;
                for(ClassDef cls:file.getValue().getClasses()) {
                    if(targets.contains(cls.getType())){pool.internClass(hookActivity(cls));changed=true;}
                    else if(result.purchasesEnabled&&cls.getType().equals(PLUGIN)){pool.internClass(hookPurchases(cls,server));changed=true;}
                    else pool.internClass(cls);
                }
                if(changed){MemoryDataStore out=new MemoryDataStore();pool.writeTo(out);result.dex.put(file.getKey(),out.getData());}
            }
            StringBuilder report=new StringBuilder("Conversion compatibility report\n\n");
            if(touch){
                if(result.touchEnabled)report.append("Touch pad: enabled for ").append(targets.size()).append(" launch activity/activities. ").append(nativeTargets).append(" use the OUYA native input route.\n");
                else report.append("Touch pad: unsupported launcher/activity layout; no touch hooks added.\n");
                if(marmaladeTargets>0)report.append(marmaladeTargets).append(" use direct Marmalade Activity key callbacks (including game overrides). Directions are digital keys; no physical controller or analog device is emulated.\n");
                if(targets.size()>nativeTargets+marmaladeTargets)report.append("Other input uses Android gamepad events, with a Keyboard option in the pad menu. Virtual events may be ignored by games that require a physical controller.\n");
                if(targets.size()<launchers.size())report.append("Some launch activities could not be patched; controls may not appear on those screens.\n");
            }
            if(offline)report.append(result.purchasesEnabled?"Offline purchases: matching OUYA Unity SDK detected; local receipt bridge enabled. No payment is made. Game-specific validation may still reject it.\n":"Offline purchases: no matching protocol found. Original purchase checks were left unchanged; no unlock is claimed.\n");
            if(server)report.append(result.serverEnabled?"Community server: matching OUYA Unity SDK detected. Profile, products, purchase responses and receipts use PocketOuya’s configured stouyapi server. Enable service and approve this installed game in PocketOuya. No official account or payment is used; server receipts may be static, and purchases may not persist.\n":"Community server: unsupported SDK/developer-ID layout. Original service and purchase checks were left unchanged.\n");
            report.append("\nThis does not fix incompatible native engines, unavailable services, custom/native DRM or unrelated billing systems. Reconvert original APKs to update embedded compatibility code.");
            result.report=report.toString();return result;
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Cannot inspect this game's DEX layout. Try normal conversion.",e);}
    }
    private static ClassDef hookActivity(ClassDef cls){
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
            if(name.equals("onResume"))code.add(touchCall("install"));
            if(name.equals("onCreate"))code.add(touchCall("watch"));
            code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
            methods.add(new ImmutableMethod(cls.getType(),name,original==null?(params.isEmpty()?Collections.emptyList():Collections.singletonList(new ImmutableMethodParameter("Landroid/os/Bundle;",Collections.emptySet(),null))):original.getParameters(),"V",original==null?AccessFlags.PROTECTED.getValue():original.getAccessFlags(),original==null?Collections.emptySet():original.getAnnotations(),original==null?Collections.emptySet():original.getHiddenApiRestrictions(),new ImmutableMethodImplementation(1+params.size(),code,Collections.emptyList(),Collections.emptyList())));
        }
        return copy(cls,methods);
    }
    private static Instruction touchCall(String method){return new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE,0,1,new ImmutableMethodReference(BRIDGE+"TouchControls;",method,Collections.singletonList("Landroid/app/Activity;"),"V"));}
    private static ClassDef hookPurchases(ClassDef cls,boolean server){
        List<Method> methods=new ArrayList<>();
        for(Method m:cls.getMethods()){
            if(!signature(m).equals(PURCHASES.get(m.getName()))){methods.add(m);continue;}
            int count=m.getParameterTypes().size();List<Instruction> code=new ArrayList<>();
            code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC,count,0,0,0,0,0,new ImmutableMethodReference(BRIDGE+(server?"ServiceOuya;":"OfflineOuya;"),m.getName(),m.getParameterTypes(),m.getReturnType())));
            boolean returned=!m.getReturnType().equals("V");
            if(returned){code.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));code.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT,0));}else code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
            methods.add(new ImmutableMethod(cls.getType(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),m.getAnnotations(),m.getHiddenApiRestrictions(),new ImmutableMethodImplementation(Math.max(count,returned?1:0),code,Collections.emptyList(),Collections.emptyList())));
        }
        return copy(cls,methods);
    }
    private static ClassDef copy(ClassDef cls,List<Method> methods){return new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods);}
}
