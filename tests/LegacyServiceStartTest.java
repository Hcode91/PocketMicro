package io.github.hozdogus.pocketouya;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;
import static io.github.hozdogus.pocketouya.PlatformCrashRulesTest.*;

public final class LegacyServiceStartTest {
 static final String COMPONENT="Landroid/content/ComponentName;";
 static Method startMethod(String owner,boolean range,String near){
  int base=range?300:0;var ref=new ImmutableMethodReference(near.equals("receiver")?"Lfixture/CustomContext;":CTX,near.equals("foreground")?"startForegroundService":"startService",List.of(INTENT),near.equals("signature")?"Z":COMPONENT);
  Instruction call=range?new ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,base,2,ref):new ImmutableInstruction35c(near.equals("super")?Opcode.INVOKE_SUPER:Opcode.INVOKE_VIRTUAL,2,0,1,0,0,0,ref);
  return new ImmutableMethod(owner,"start",GeneralCompatibilityTest.params(CTX,INTENT),COMPONENT,9,Set.of(),Set.of(),new ImmutableMethodImplementation(base+2,List.of(call,new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,0),new ImmutableInstruction11x(Opcode.RETURN_OBJECT,0)),List.of(),List.of()));
 }
 static byte[] fixture(String owner,boolean range,String near)throws Exception{
  List<Method> methods=new ArrayList<>();methods.add(startMethod(owner,range,near));
  if(near.equals("collision"))methods.add(new ImmutableMethod(owner,START_HELPER,List.of(),"V",9,Set.of(),Set.of(),new ImmutableMethodImplementation(0,List.of(new ImmutableInstruction10x(Opcode.RETURN_VOID)),List.of(),List.of())));
  return GeneralCompatibilityTest.dex(cls(owner,methods));
 }
 static List<String> keys(Method method){
  List<String> out=new ArrayList<>();for(Instruction i:method.getImplementation().getInstructions()){
   String s=i.getOpcode()+"/"+i.getCodeUnits();if(i instanceof OneRegisterInstruction)s+="/a"+((OneRegisterInstruction)i).getRegisterA();if(i instanceof TwoRegisterInstruction)s+="/b"+((TwoRegisterInstruction)i).getRegisterB();
   if(i instanceof FiveRegisterInstruction){var r=(FiveRegisterInstruction)i;s+="/args"+r.getRegisterCount()+","+r.getRegisterC()+","+r.getRegisterD()+","+r.getRegisterE()+","+r.getRegisterF()+","+r.getRegisterG();}
   if(i instanceof OffsetInstruction)s+="/off"+((OffsetInstruction)i).getCodeOffset();if(i instanceof ReferenceInstruction)s+="/ref"+((ReferenceInstruction)i).getReference();out.add(s);
  }return out;
 }
 public static void main(String[] args)throws Exception{
  for(String owner:new String[]{"Lcom/bda/controller/Controller;","Lcom/google/analytics/tracking/android/AnalyticsGmsCoreClient;"})for(boolean range:new boolean[]{false,true}){
   byte[] input=fixture(owner,range,"");byte[] copy=input.clone();var scan=LegacySdkCrashRules.inspect(Map.of("classes.dex",input));check(scan.supported()&&scan.replacements.isEmpty()&&scan.report().contains("1 startService"),"read-only start detection");
   var repair=LegacySdkCrashRules.repair(Map.of("classes.dex",input));check(repair.supported()&&repair.replacements.size()==1,"start rewrite "+repair.report());byte[] output=repair.replacements.get("classes.dex");check(Arrays.equals(input,copy),"original source immutable");
   var call=method(output,"start").getImplementation().getInstructions().iterator().next();check(call.getOpcode()==(range?Opcode.INVOKE_STATIC_RANGE:Opcode.INVOKE_STATIC),"start invoke form");
   var callRef=(MethodReference)((ReferenceInstruction)call).getReference();check(callRef.getName().equals(START_HELPER)&&callRef.getReturnType().equals(COMPONENT)&&callRef.getParameterTypes().equals(List.of(CTX,INTENT)),"object return and receiver/Intent descriptor retained");
   if(range)check(((RegisterRangeInstruction)call).getStartRegister()==300&&((RegisterRangeInstruction)call).getRegisterCount()==2,"high start registers retained");
   Method helper=method(output,START_HELPER);check(helper.getImplementation().getRegisterCount()==5,"start parameter register positions");
   check(!LegacySdkCrashRules.repair(Map.of("classes.dex",output)).supported(),"start idempotence");
   for(String action:new String[]{LegacySdkCrashRules.MOGA_ACTION,LegacySdkCrashRules.ANALYTICS_ACTION,"unknown.action",null})for(int explicit=0;explicit<3;explicit++)for(boolean found:new boolean[]{false,true}){
    var intent=new IntentModel(action);intent.extras.put("keep","value");if(explicit==1)intent.pkg="existing.provider";if(explicit==2)intent.component="existing.component";
    var p=new Platform();if(!found)p.startResult=null;Object expected=p.startResult;
    boolean recognized=Objects.equals(action,LegacySdkCrashRules.MOGA_ACTION)||Objects.equals(action,LegacySdkCrashRules.ANALYTICS_ACTION);
    try{Object result=execute(helper,p,intent);check(explicit!=0||recognized,"unknown implicit start retains rejection");check(result==expected,"original ComponentName/null identity retained");}
    catch(IllegalArgumentException rejected){check(explicit==0&&!recognized,"known SDK implicit start must be package scoped");}
    check(p.calls==1&&p.received.extras.equals(intent.extras)&&Objects.equals(p.received.action,intent.action),"single original start call, action/extras retained");
    if(explicit==0&&recognized){check(p.received!=intent&&intent.pkg==null,"start original Intent unchanged");check(p.received.pkg.equals(Objects.equals(action,LegacySdkCrashRules.MOGA_ACTION)?"fixture.moga.provider":"com.google.android.gms"),"start provider package");if(Objects.equals(action,LegacySdkCrashRules.MOGA_ACTION))check(p.received.component.equals("fixture.MogaService")&&p.queries==1,"resolved start component");}else check(p.received==intent&&p.queries==0,"explicit/unknown start Intent identity retained");
   }
   var p=new Platform();p.denied=true;try{execute(helper,p,new IntentModel(LegacySdkCrashRules.MOGA_ACTION));throw new AssertionError("permission failure suppressed");}catch(SecurityException expected){}
   p=new Platform();p.startError=new IllegalStateException("background start disallowed");try{execute(helper,p,new IntentModel(LegacySdkCrashRules.MOGA_ACTION));throw new AssertionError("start restriction suppressed");}catch(IllegalStateException expected){check(expected==p.startError,"original background-start exception retained");}
   p=new Platform();try{execute(helper,p,null);throw new AssertionError("null start Intent accepted");}catch(NullPointerException expected){check(p.calls==1,"null start Intent reaches original platform call");}
   for(List<ResolveModel> providers:Arrays.asList(Collections.<ResolveModel>emptyList(),null)){p=new Platform();p.providers=providers;check(execute(helper,p,new IntentModel(LegacySdkCrashRules.MOGA_ACTION))==null&&p.calls==0&&p.queries==1,"no visible MOGA service returns null, never started");}
   p=new Platform();p.providers=List.of(new ResolveModel(),new ResolveModel());try{execute(helper,p,new IntentModel(LegacySdkCrashRules.MOGA_ACTION));throw new AssertionError("ambiguous service started");}catch(IllegalArgumentException expected){check(p.calls==0,"ambiguous registry rejected before start");}
   p=new Platform();ResolveModel invalid=new ResolveModel();invalid.serviceInfo=null;p.providers=List.of(invalid);try{execute(helper,p,new IntentModel(LegacySdkCrashRules.MOGA_ACTION));throw new AssertionError("invalid provider metadata started");}catch(IllegalArgumentException expected){check(p.calls==0,"invalid metadata rejected");}
  }
  for(String near:new String[]{"receiver","signature","super","foreground","collision"})check(LegacySdkCrashRules.repair(Map.of("classes.dex",fixture("Lcom/bda/controller/Controller;",false,near))).replacements.isEmpty(),"start near-match retained: "+near);
  check(LegacySdkCrashRules.repair(Map.of("classes.dex",fixture("Lunrelated/Caller;",false,""))).replacements.isEmpty(),"unrelated start call untouched");
  String owner="Lcom/bda/controller/Controller;";byte[] bound=LegacySdkCrashRules.repair(Map.of("classes.dex",service(owner,false,""))).replacements.get("classes.dex");
  ClassDef original=new DexBackedDexFile(null,bound).getClasses().iterator().next();List<Method> methods=new ArrayList<>();for(Method m:original.getMethods())methods.add(m);methods.add(startMethod(owner,true,""));
  // A matching bind helper can coexist with newly encountered start sites.
  byte[] mixed=GeneralCompatibilityTest.dex(new ImmutableClassDef(original.getType(),original.getAccessFlags(),original.getSuperclass(),original.getInterfaces(),original.getSourceFile(),original.getAnnotations(),original.getFields(),methods));
  var upgraded=LegacySdkCrashRules.repair(Map.of("classes.dex",mixed));check(upgraded.supported()&&upgraded.report().contains("0 bindService and 1 startService"),"existing binding helper upgraded independently");byte[] output=upgraded.replacements.get("classes.dex");
  check(keys(method(bound,HELPER)).equals(keys(method(output,HELPER))),"existing bind helper instructions preserved");check(keys(method(bound,"connect")).equals(keys(method(output,"connect"))),"existing binding call preserved");check(!LegacySdkCrashRules.inspect(Map.of("classes.dex",output)).supported(),"both helpers excluded from recursion/repeated rewriting");
  ClassDef stamped=new DexBackedDexFile(null,output).getClasses().iterator().next();List<Method> altered=new ArrayList<>();for(Method m:stamped.getMethods()){
   if(!m.getName().equals(START_HELPER)){altered.add(m);continue;}var body=new org.jf.dexlib2.builder.MutableMethodImplementation(m.getImplementation());for(int n=0;n<body.getInstructions().size();n++){Instruction i=body.getInstructions().get(n);if(i.getOpcode()==Opcode.CONST_4&&((NarrowLiteralInstruction)i).getNarrowLiteral()==1){body.replaceInstruction(n,new org.jf.dexlib2.builder.instruction.BuilderInstruction11n(Opcode.CONST_4,((OneRegisterInstruction)i).getRegisterA(),2));break;}}
   altered.add(new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),m.getAnnotations(),m.getHiddenApiRestrictions(),body));
  }
  byte[] tampered=GeneralCompatibilityTest.dex(new ImmutableClassDef(stamped.getType(),stamped.getAccessFlags(),stamped.getSuperclass(),stamped.getInterfaces(),stamped.getSourceFile(),stamped.getAnnotations(),stamped.getFields(),altered));var reject=LegacySdkCrashRules.repair(Map.of("classes.dex",tampered));check(!reject.supported()&&reject.replacements.isEmpty()&&reject.findings.get(0).status==CompatibilityEvidence.Status.UNKNOWN,"tampered registry cardinality literal is not a recognized helper");
  List<Instruction> init=List.of(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,2,1,2,0,0,0,new ImmutableMethodReference(CTX,"startService",List.of(INTENT),COMPONENT)),new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,4,1,2,3,4,0,new ImmutableMethodReference(CTX,"bindService",List.of(INTENT,CONN,"I"),"Z")),new ImmutableInstruction11x(Opcode.MOVE_RESULT,0),new ImmutableInstruction11x(Opcode.RETURN,0));
  Method entry=new ImmutableMethod(owner,"init",GeneralCompatibilityTest.params(CTX,INTENT,CONN,"I"),"Z",9,Set.of(),Set.of(),new ImmutableMethodImplementation(5,init,List.of(),List.of()));
  byte[] combined=GeneralCompatibilityTest.dex(cls(owner,List.of(entry)));var fixed=LegacySdkCrashRules.repair(Map.of("classes.dex",combined));check(fixed.supported()&&fixed.report().contains("1 bindService and 1 startService"),"both init paths matched");
  List<String> targets=new ArrayList<>();for(Instruction i:method(fixed.replacements.get("classes.dex"),"init").getImplementation().getInstructions())if(i instanceof ReferenceInstruction)targets.add(((MethodReference)((ReferenceInstruction)i).getReference()).getName());check(targets.equals(List.of(START_HELPER,HELPER)),"MOGA init start-then-bind order retained");
  System.out.println("Legacy service starts: SDK/direct/range cases, ComponentName/null results, registry absence/ambiguity/invalid metadata, Intent identity/extras, security/background errors, near-matches, matching bind-helper retention, start-then-bind ordering and idempotence pass; no phone boot established.");
 }
}
