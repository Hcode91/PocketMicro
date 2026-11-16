package com.hcode91.pocketmicro;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.*;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/** Platform contracts broken by old game code (implicit service Intents, OUYA null cursor). No game IDs, purchases or input edits. */
public final class LegacySdkCrashRules {
    public static final String SERVICE_ID="android.legacy-sdk.explicit-service",CURSOR_ID="ouya.game-data.null-cursor-close";
    public static final String MOGA_ACTION="com.bda.controller.IControllerService",ANALYTICS_ACTION="com.google.android.gms.analytics.service.START";
    private static final String HELPER="pocketouyaBindLegacyService",START_HELPER="pocketouyaStartLegacyService",CONTEXT="Landroid/content/Context;",INTENT="Landroid/content/Intent;",CONNECTION="Landroid/content/ServiceConnection;",STRING="Ljava/lang/String;",COMPONENT="Landroid/content/ComponentName;";
    private static final List<String> BIND_PARAMS=List.of(INTENT,CONNECTION,"I"),HELPER_PARAMS=List.of(CONTEXT,INTENT,CONNECTION,"I");
    private static final List<String> START_PARAMS=List.of(INTENT),START_HELPER_PARAMS=List.of(CONTEXT,INTENT);
    // Framework classes that are android.content.Context. Game classes count when their DEX
    // superclass chain reaches one of these; unknown external types are left alone.
    private static final Set<String> FRAMEWORK_CONTEXTS=Set.of(CONTEXT,"Landroid/content/ContextWrapper;","Landroid/view/ContextThemeWrapper;","Landroid/app/Activity;","Landroid/app/Application;","Landroid/app/Service;","Landroid/app/IntentService;",
        "Landroid/app/NativeActivity;","Landroid/app/ListActivity;","Landroid/app/TabActivity;","Landroid/app/ActivityGroup;","Landroid/app/ExpandableListActivity;","Landroid/app/AliasActivity;","Landroid/app/LauncherActivity;","Landroid/preference/PreferenceActivity;");
    private static final ThreadLocal<Map<String,String>> SUPERS=ThreadLocal.withInitial(HashMap::new);
    private static boolean contextType(String type){
        Map<String,String> supers=SUPERS.get();for(int depth=0;type!=null&&depth<32;depth++){if(FRAMEWORK_CONTEXTS.contains(type))return true;if(!supers.containsKey(type))return false;type=supers.get(type);}return false;
    }
    private static final class Unverified extends RuntimeException {Unverified(String reason){super(reason);}}
    public static final class Finding {
        public final CompatibilityEvidence.Status status;public final String id,detail;
        Finding(CompatibilityEvidence.Status status,String id,String detail){this.status=status;this.id=id;this.detail=detail;}
    }
    public static final class Result {
        public final List<Finding> findings=new ArrayList<>();public final Map<String,byte[]> replacements=new LinkedHashMap<>();
        public boolean supported(){return findings.stream().anyMatch(f->f.status==CompatibilityEvidence.Status.SUPPORTED||f.status==CompatibilityEvidence.Status.VERIFIED);}
        public String report(){StringBuilder s=new StringBuilder();for(var f:findings)s.append(f.status).append(' ').append(f.id).append(": ").append(f.detail).append('\n');return s.toString();}
    }
    private static List<Instruction> code(Method m){List<Instruction> out=new ArrayList<>();if(m.getImplementation()!=null)for(Instruction i:m.getImplementation().getInstructions())out.add(i);return out;}
    private static MethodReference ref(Instruction i){if(!(i instanceof ReferenceInstruction))return null;Object r=((ReferenceInstruction)i).getReference();return r instanceof MethodReference?(MethodReference)r:null;}
    // Only a game-supplied copy of a platform type this rule relies on is a real conflict. Bundled
    // libraries and stubs under android/ (android.support.*, android.annotation.*, android.UnusedStub)
    // are ordinary game code and must not block the rule.
    private static final Set<String> RELIED_ON=Set.of(INTENT,CONNECTION,COMPONENT,"Landroid/content/pm/PackageManager;","Landroid/content/pm/ResolveInfo;","Landroid/content/pm/ServiceInfo;",
        "Landroid/content/pm/PackageItemInfo;","Landroid/database/Cursor;","Ljava/util/List;","Ljava/lang/Object;",STRING);
    private static boolean shadowsPlatform(String type){return FRAMEWORK_CONTEXTS.contains(type)||RELIED_ON.contains(type)||type.startsWith("Ljava/lang/");}
    private static boolean ours(String type){return type.startsWith("Lcom/hcode91/pocketmicro/")||type.startsWith("Lcom/hcode91/pocketmicro/");}
    private static boolean bind(Instruction i){
        MethodReference r=ref(i);return r!=null&&contextType(r.getDefiningClass())&&r.getName().equals("bindService")&&r.getParameterTypes().equals(BIND_PARAMS)&&r.getReturnType().equals("Z")
            &&((i.getOpcode()==Opcode.INVOKE_VIRTUAL&&((FiveRegisterInstruction)i).getRegisterCount()==4)||(i.getOpcode()==Opcode.INVOKE_VIRTUAL_RANGE&&((RegisterRangeInstruction)i).getRegisterCount()==4));
    }
    private static boolean start(Instruction i){
        MethodReference r=ref(i);return r!=null&&contextType(r.getDefiningClass())&&r.getName().equals("startService")&&r.getParameterTypes().equals(START_PARAMS)&&r.getReturnType().equals(COMPONENT)
            &&((i.getOpcode()==Opcode.INVOKE_VIRTUAL&&((FiveRegisterInstruction)i).getRegisterCount()==2)||(i.getOpcode()==Opcode.INVOKE_VIRTUAL_RANGE&&((RegisterRangeInstruction)i).getRegisterCount()==2));
    }
    private static int cursor(Instruction i){
        MethodReference r=ref(i);if(r==null||!r.getDefiningClass().equals("Landroid/database/Cursor;")||!r.getName().equals("close")||!r.getParameterTypes().isEmpty()||!r.getReturnType().equals("V"))return -1;
        if(i.getOpcode()==Opcode.INVOKE_INTERFACE&&((FiveRegisterInstruction)i).getRegisterCount()==1)return ((FiveRegisterInstruction)i).getRegisterC();
        if(i.getOpcode()==Opcode.INVOKE_INTERFACE_RANGE&&((RegisterRangeInstruction)i).getRegisterCount()==1)return ((RegisterRangeInstruction)i).getStartRegister();return -1;
    }
    private static boolean gameData(Method m){return m.getDefiningClass().equals("Ltv/ouya/console/api/OuyaFacade;")&&m.getName().equals("getGameData")&&m.getParameterTypes().equals(List.of(STRING))&&m.getReturnType().equals(STRING)&&m.getImplementation()!=null;}
    private static ImmutableMethodReference method(String owner,String name,List<String> params,String returns){return new ImmutableMethodReference(owner,name,params,returns);}
    private static void invoke(MethodImplementationBuilder b,Opcode opcode,String owner,String name,List<String> params,String returns,int...regs){
        int[] r=new int[5];System.arraycopy(regs,0,r,0,regs.length);b.addInstruction(new BuilderInstruction35c(opcode,regs.length,r[0],r[1],r[2],r[3],r[4],method(owner,name,params,returns)));
    }
    private static void string(MethodImplementationBuilder b,int reg,String text){b.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING,reg,new ImmutableStringReference(text)));}
    // Generic resolver: an implicit service Intent (no component, no package) is resolved through
    // PackageManager on a copy. Exactly one visible match is called explicitly; none or several
    // return the platform's "unavailable" value (false / null) instead of the Android 5+
    // IllegalArgumentException. Explicit and null inputs reach the original platform call unchanged.
    private static Method helper(String owner,boolean start){
        MethodImplementationBuilder b=new MethodImplementationBuilder(start?5:7);
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ,3,b.getLabel("call")));b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ,4,b.getLabel("call")));
        invoke(b,Opcode.INVOKE_VIRTUAL,INTENT,"getComponent",List.of(),"Landroid/content/ComponentName;",4);b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));b.addInstruction(new BuilderInstruction21t(Opcode.IF_NEZ,0,b.getLabel("call")));
        invoke(b,Opcode.INVOKE_VIRTUAL,INTENT,"getPackage",List.of(),STRING,4);b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));b.addInstruction(new BuilderInstruction21t(Opcode.IF_NEZ,0,b.getLabel("call")));
        invoke(b,Opcode.INVOKE_VIRTUAL,CONTEXT,"getPackageManager",List.of(),"Landroid/content/pm/PackageManager;",3);b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ,0,b.getLabel("unavailable")));
        b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4,1,0));invoke(b,Opcode.INVOKE_VIRTUAL,"Landroid/content/pm/PackageManager;","queryIntentServices",List.of(INTENT,"I"),"Ljava/util/List;",0,4,1);b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ,0,b.getLabel("unavailable")));invoke(b,Opcode.INVOKE_INTERFACE,"Ljava/util/List;","size",List.of(),"I",0);b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT,1));
        b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4,2,1));b.addInstruction(new BuilderInstruction22t(Opcode.IF_NE,1,2,b.getLabel("unavailable")));
        b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4,1,0));invoke(b,Opcode.INVOKE_INTERFACE,"Ljava/util/List;","get",List.of("I"),"Ljava/lang/Object;",0,1);b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ,0,b.getLabel("unavailable")));b.addInstruction(new BuilderInstruction21c(Opcode.CHECK_CAST,0,new ImmutableTypeReference("Landroid/content/pm/ResolveInfo;")));
        b.addInstruction(new BuilderInstruction22c(Opcode.IGET_OBJECT,0,0,new ImmutableFieldReference("Landroid/content/pm/ResolveInfo;","serviceInfo","Landroid/content/pm/ServiceInfo;")));b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ,0,b.getLabel("unavailable")));
        b.addInstruction(new BuilderInstruction22c(Opcode.IGET_OBJECT,1,0,new ImmutableFieldReference("Landroid/content/pm/PackageItemInfo;","packageName",STRING)));b.addInstruction(new BuilderInstruction22c(Opcode.IGET_OBJECT,2,0,new ImmutableFieldReference("Landroid/content/pm/PackageItemInfo;","name",STRING)));
        b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ,1,b.getLabel("unavailable")));b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ,2,b.getLabel("unavailable")));
        b.addInstruction(new BuilderInstruction21c(Opcode.NEW_INSTANCE,0,new ImmutableTypeReference(INTENT)));invoke(b,Opcode.INVOKE_DIRECT,INTENT,"<init>",List.of(INTENT),"V",0,4);invoke(b,Opcode.INVOKE_VIRTUAL,INTENT,"setClassName",List.of(STRING,STRING),INTENT,0,1,2);b.addInstruction(new BuilderInstruction12x(Opcode.MOVE_OBJECT,4,0));b.addInstruction(new BuilderInstruction20t(Opcode.GOTO_16,b.getLabel("call")));
        b.addLabel("unavailable");b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4,0,0));b.addInstruction(new BuilderInstruction11x(start?Opcode.RETURN_OBJECT:Opcode.RETURN,0));
        b.addLabel("call");
        if(start){invoke(b,Opcode.INVOKE_VIRTUAL,CONTEXT,"startService",START_PARAMS,COMPONENT,3,4);b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));b.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT,0));}
        else {invoke(b,Opcode.INVOKE_VIRTUAL,CONTEXT,"bindService",BIND_PARAMS,"Z",3,4,5,6);b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT,0));b.addInstruction(new BuilderInstruction11x(Opcode.RETURN,0));}
        List<ImmutableMethodParameter> params=new ArrayList<>();for(String type:start?START_HELPER_PARAMS:HELPER_PARAMS)params.add(new ImmutableMethodParameter(type,Set.of(),null));
        return new ImmutableMethod(owner,start?START_HELPER:HELPER,params,start?COMPONENT:"Z",0x100a,Set.of(),Set.of(),b.getMethodImplementation());
    }
    private static String instructionKey(Instruction i){
        StringBuilder s=new StringBuilder(i.getOpcode().name()).append('/').append(i.getCodeUnits());
        if(i instanceof OneRegisterInstruction)s.append(" a").append(((OneRegisterInstruction)i).getRegisterA());if(i instanceof TwoRegisterInstruction)s.append(" b").append(((TwoRegisterInstruction)i).getRegisterB());
        if(i instanceof FiveRegisterInstruction){var r=(FiveRegisterInstruction)i;int[] args={r.getRegisterC(),r.getRegisterD(),r.getRegisterE(),r.getRegisterF(),r.getRegisterG()};s.append(" args");for(int n=0;n<r.getRegisterCount();n++)s.append('/').append(args[n]);}
        if(i instanceof OffsetInstruction)s.append(" off").append(((OffsetInstruction)i).getCodeOffset());if(i instanceof ReferenceInstruction)s.append(" ref").append(((ReferenceInstruction)i).getReference());
        if(i instanceof NarrowLiteralInstruction)s.append(" literal").append(((NarrowLiteralInstruction)i).getNarrowLiteral());else if(i instanceof WideLiteralInstruction)s.append(" literal").append(((WideLiteralInstruction)i).getWideLiteral());return s.toString();
    }
    private static boolean helperValid(Method m,String owner,boolean start){
        Method expected=helper(owner,start);if(!m.getParameterTypes().equals(start?START_HELPER_PARAMS:HELPER_PARAMS)||!m.getReturnType().equals(start?COMPONENT:"Z")||m.getAccessFlags()!=0x100a||m.getImplementation()==null||m.getImplementation().getRegisterCount()!=(start?5:7)||!m.getImplementation().getTryBlocks().isEmpty())return false;
        List<Instruction> a=code(m),b=code(expected);if(a.size()!=b.size())return false;for(int n=0;n<a.size();n++)if(!instructionKey(a.get(n)).equals(instructionKey(b.get(n))))return false;return true;
    }
    private static boolean guarded(List<Instruction> c,int n,int reg){
        if(n==0||c.get(n-1).getOpcode()!=Opcode.IF_EQZ||((OneRegisterInstruction)c.get(n-1)).getRegisterA()!=reg)return false;
        return ((OffsetInstruction)c.get(n-1)).getCodeOffset()==c.get(n-1).getCodeUnits()+c.get(n).getCodeUnits();
    }
    private static Method copy(Method m,MethodImplementation body){return new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),m.getAnnotations(),m.getHiddenApiRestrictions(),body);}
    public static Result inspect(Map<String,byte[]> input){return run(input,false);}
    public static Result repair(Map<String,byte[]> input){return run(input,true);}
    private static Result run(Map<String,byte[]> input,boolean apply){
        Result result=new Result();boolean parsed=false;try{
            if(input.size()>64){result.findings.add(new Finding(CompatibilityEvidence.Status.SKIPPED,SERVICE_ID,"DEX count budget; no edits"));return result;}
            Map<String,DexBackedDexFile> files=new LinkedHashMap<>();Set<String> types=new HashSet<>();long total=0;Map<String,String> supers=SUPERS.get();supers.clear();
            for(var e:input.entrySet()){
                total+=e.getValue().length;if(total>64L*1024*1024||e.getValue().length>32*1024*1024){result.findings.add(new Finding(CompatibilityEvidence.Status.SKIPPED,SERVICE_ID,"DEX byte budget; no edits"));return result;}
                var d=new DexBackedDexFile(null,e.getValue());files.put(e.getKey(),d);
                for(ClassDef c:d.getClasses()){
                    if(!types.add(c.getType()))throw new IllegalArgumentException("Duplicate class");supers.put(c.getType(),c.getSuperclass());
                    if(shadowsPlatform(c.getType()))throw new UnsupportedOperationException("Shadowed platform class");
                    for(Method m:c.getMethods())if(m.getImplementation()!=null)for(Instruction i:m.getImplementation().getInstructions())if(i instanceof org.jf.dexlib2.iface.instruction.formats.UnknownInstruction)throw new UnsupportedOperationException("Reserved DEX instruction");
                }
            }
            parsed=true;
            int serviceCount=0,startCount=0,cursorCount=0;boolean already=false;
            for(var file:files.entrySet()){
                DexPool pool=new DexPool(file.getValue().getOpcodes());boolean changed=false;
                for(ClassDef c:file.getValue().getClasses()){
                    boolean service=!ours(c.getType()),facade=c.getType().equals("Ltv/ouya/console/api/OuyaFacade;");if(!service&&!facade){pool.internClass(c);continue;}
                    if(AccessFlags.INTERFACE.isSet(c.getAccessFlags())){pool.internClass(c);continue;}
                    Method existing=null,existingStart=null;boolean collision=false;for(Method m:c.getMethods()){
                        if(m.getName().equals(HELPER)){if(existing!=null)collision=true;existing=m;}
                        if(m.getName().equals(START_HELPER)){if(existingStart!=null)collision=true;existingStart=m;}
                    }
                    if(existing!=null&&!helperValid(existing,c.getType(),false)||existingStart!=null&&!helperValid(existingStart,c.getType(),true))collision=true;
                    if(collision){result.findings.add(new Finding(CompatibilityEvidence.Status.UNKNOWN,SERVICE_ID,c.getType()+": helper collision; class retained"));service=false;}
                    if((existing!=null||existingStart!=null)&&!collision)already=true;
                    List<Method> methods=new ArrayList<>();boolean classChanged=false,addHelper=false,addStart=false;
                    for(Method m:c.getMethods()){
                        if(m.getName().equals(HELPER)||m.getName().equals(START_HELPER)||m.getImplementation()==null){methods.add(m);continue;}
                        List<Instruction> instructions=code(m);List<Integer> binds=new ArrayList<>(),starts=new ArrayList<>(),closes=new ArrayList<>();boolean unsafeCursor=false;
                        for(int n=0;n<instructions.size();n++){
                            Instruction i=instructions.get(n);if(service&&bind(i))binds.add(n);
                            if(service&&start(i))starts.add(n);
                            int reg=facade&&gameData(m)?cursor(i):-1;if(reg>=0){if(guarded(instructions,n,reg))already=true;else if(reg>255||n+1>=instructions.size()||instructions.get(n+1).getOpcode()==Opcode.MOVE_EXCEPTION)unsafeCursor=true;else closes.add(n);}
                        }
                        if(unsafeCursor){closes.clear();result.findings.add(new Finding(CompatibilityEvidence.Status.UNKNOWN,CURSOR_ID,"Unsupported cursor register/continuation; method retained"));}
                        serviceCount+=binds.size();startCount+=starts.size();cursorCount+=closes.size();
                        if(!apply||binds.isEmpty()&&starts.isEmpty()&&closes.isEmpty()){methods.add(m);continue;}
                        MutableMethodImplementation body=new MutableMethodImplementation(m.getImplementation());
                        for(int n:binds){Instruction i=instructions.get(n);var helper=method(c.getType(),HELPER,HELPER_PARAMS,"Z");
                            if(i.getOpcode()==Opcode.INVOKE_VIRTUAL){var r=(FiveRegisterInstruction)i;body.replaceInstruction(n,new BuilderInstruction35c(Opcode.INVOKE_STATIC,4,r.getRegisterC(),r.getRegisterD(),r.getRegisterE(),r.getRegisterF(),0,helper));}
                            else {var r=(RegisterRangeInstruction)i;body.replaceInstruction(n,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,r.getStartRegister(),4,helper));}addHelper=existing==null;
                        }
                        for(int n:starts){Instruction i=instructions.get(n);var helper=method(c.getType(),START_HELPER,START_HELPER_PARAMS,COMPONENT);
                            if(i.getOpcode()==Opcode.INVOKE_VIRTUAL){var r=(FiveRegisterInstruction)i;body.replaceInstruction(n,new BuilderInstruction35c(Opcode.INVOKE_STATIC,2,r.getRegisterC(),r.getRegisterD(),0,0,0,helper));}
                            else {var r=(RegisterRangeInstruction)i;body.replaceInstruction(n,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,r.getStartRegister(),2,helper));}addStart=existingStart==null;
                        }
                        for(int k=closes.size()-1;k>=0;k--){
                            int n=closes.get(k);Label next=body.newLabelForIndex(n+1);MethodLocation original=body.getInstructions().get(n).getLocation();
                            List<Label> incoming=new ArrayList<>(original.getLabels());original.getLabels().clear();
                            body.addInstruction(n,new BuilderInstruction21t(Opcode.IF_EQZ,cursor(instructions.get(n)),next));
                            // Existing branches and try boundaries must include the guard,
                            // rather than jumping directly to the old close instruction.
                            body.getInstructions().get(n).getLocation().getLabels().addAll(incoming);
                        }
                        methods.add(copy(m,body));classChanged=true;
                    }
                    if(classChanged){if(addHelper)methods.add(helper(c.getType(),false));if(addStart)methods.add(helper(c.getType(),true));pool.internClass(new ImmutableClassDef(c.getType(),c.getAccessFlags(),c.getSuperclass(),c.getInterfaces(),c.getSourceFile(),c.getAnnotations(),c.getFields(),methods));changed=true;}else pool.internClass(c);
                }
                if(apply&&changed){MemoryDataStore out=new MemoryDataStore();pool.writeTo(out);result.replacements.put(file.getKey(),out.getData());}
            }
            if(apply&&!result.replacements.isEmpty()){
                Map<String,byte[]> verify=new LinkedHashMap<>(input);verify.putAll(result.replacements);Result checked=inspect(verify);
                if(checked.supported()||checked.findings.stream().anyMatch(f->f.status==CompatibilityEvidence.Status.MALFORMED||f.status==CompatibilityEvidence.Status.UNSUPPORTED||f.status==CompatibilityEvidence.Status.UNKNOWN))throw new Unverified("Serialized rule postcondition failed");
            }
            var status=apply?CompatibilityEvidence.Status.VERIFIED:CompatibilityEvidence.Status.SUPPORTED;
            if(serviceCount+startCount>0)result.findings.add(new Finding(status,SERVICE_ID,serviceCount+" bindService and "+startCount+" startService sites. Implicit service Intents (any game or SDK, e.g. MOGA, Google Analytics) resolve a unique visible Android service on an Intent copy; no match, several matches or invalid metadata return unavailable (false/null) instead of crashing. Explicit/null inputs, flags, connections, actual Boolean/ComponentName/null results and platform exceptions are retained. No service success is fabricated; phone startup unverified."));
            if(cursorCount>0)result.findings.add(new Finding(status,CURSOR_ID,cursorCount+" Cursor.close calls in OUYA getGameData(String) guarded only for null. Non-null cleanup, original query/data/exception paths and purchase methods retained; absent provider/data is not fabricated. Phone startup unverified."));
            if(serviceCount+startCount+cursorCount==0&&result.findings.isEmpty())result.findings.add(new Finding(CompatibilityEvidence.Status.SKIPPED,SERVICE_ID,already?"Existing service guards; no new edits":"No Context bindService/startService call sites"));
        }catch(Exception bad){SUPERS.get().clear();result.replacements.clear();result.findings.clear();result.findings.add(new Finding(bad instanceof UnsupportedOperationException?CompatibilityEvidence.Status.UNSUPPORTED:bad instanceof Unverified||parsed?CompatibilityEvidence.Status.UNKNOWN:CompatibilityEvidence.Status.MALFORMED,SERVICE_ID,bad.getMessage()+"; original DEX retained"));}
        return result;
    }
    private LegacySdkCrashRules(){}
}
