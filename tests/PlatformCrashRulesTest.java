package io.github.hozdogus.pocketouya;

import java.util.*;import java.nio.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.util.zip.*;
import org.jf.dexlib2.*;import org.jf.dexlib2.builder.*;import org.jf.dexlib2.builder.instruction.*;import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;import org.jf.dexlib2.iface.instruction.*;import org.jf.dexlib2.iface.reference.*;import org.jf.dexlib2.immutable.*;import org.jf.dexlib2.immutable.instruction.*;import org.jf.dexlib2.immutable.reference.*;

public final class PlatformCrashRulesTest {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static final String CTX="Landroid/content/Context;",INTENT="Landroid/content/Intent;",CONN="Landroid/content/ServiceConnection;",CURSOR="Landroid/database/Cursor;",STRING="Ljava/lang/String;",HELPER="pocketouyaBindLegacyService",START_HELPER="pocketouyaStartLegacyService";
 static byte[] elf(String path,int shift){
  byte[] bytes=new byte[0x900];ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0,0x464c457f);bytes[4]=1;bytes[5]=1;bytes[6]=1;
  b.putShort(16,(short)3).putShort(18,(short)40).putInt(20,1).putInt(28,52).putShort(40,(short)52).putShort(42,(short)32).putShort(44,(short)3);
  int file=0x200+shift,va=0x2000+shift*16;
  SharedCompatibilityRulesTest.header(b,52,1,0,0,0x200,0x200,5);SharedCompatibilityRulesTest.header(b,84,1,file,va,0x500,0x600,6);SharedCompatibilityRulesTest.header(b,116,2,file+0x40,va+0x40,0x80,0x80,6);
  byte[] text=("\0"+path+"\0").getBytes(StandardCharsets.US_ASCII);System.arraycopy(text,0,bytes,file+0x100,text.length);
  int at=file+0x40;for(int[] t:new int[][]{{1,1},{5,va+0x100},{10,text.length},{14,1},{0,0}}){b.putInt(at,t[0]).putInt(at+4,t[1]);at+=8;}
  b.putInt(0x180,0xe1a00000);return bytes;
 }
 static String needed(byte[] bytes,int shift){ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);int index=b.getInt(0x200+shift+0x44),start=0x200+shift+0x100+index,end=start;while(bytes[end]!=0)end++;return new String(bytes,start,end-start,StandardCharsets.US_ASCII);}
 static ClassDef cls(String type,List<Method> methods){return GeneralCompatibilityTest.cls(type,"Ljava/lang/Object;",methods);}
 static byte[] service(String type,boolean range,String near)throws Exception{
  List<String> params=List.of(CTX,INTENT,CONN,"I");int start=range?300:0;
  var ref=new ImmutableMethodReference(near.equals("receiver")?"Lexample/MyContext;":CTX,"bindService",List.of(INTENT,CONN,"I"),near.equals("signature")?"I":"Z");
  Instruction call=range?new ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,start,4,ref):new ImmutableInstruction35c(near.equals("super")?Opcode.INVOKE_SUPER:Opcode.INVOKE_VIRTUAL,4,0,1,2,3,0,ref);
  Method connect=new ImmutableMethod(type,"connect",GeneralCompatibilityTest.params(CTX,INTENT,CONN,"I"),"Z",9,Set.of(),Set.of(),new ImmutableMethodImplementation(start+4,List.of(call,new ImmutableInstruction11x(Opcode.MOVE_RESULT,0),new ImmutableInstruction11x(Opcode.RETURN,0)),List.of(),List.of()));
  List<Method> methods=new ArrayList<>();methods.add(connect);
  if(near.equals("collision"))methods.add(new ImmutableMethod(type,HELPER,List.of(),"V",9,Set.of(),Set.of(),new ImmutableMethodImplementation(0,List.of(new ImmutableInstruction10x(Opcode.RETURN_VOID)),List.of(),List.of())));
  return GeneralCompatibilityTest.dex(cls(type,methods));
 }
 static byte[] cursorDex()throws Exception{
  String owner="Ltv/ouya/console/api/OuyaFacade;";MethodImplementationBuilder b=new MethodImplementationBuilder(4);
  b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4,0,0));b.addLabel("begin");
  b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC,0,0,0,0,0,0,new ImmutableMethodReference("Lfixture/Provider;","query",List.of(),CURSOR)));b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));
  b.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ,0,b.getLabel("end")));
  b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE,1,0,0,0,0,0,new ImmutableMethodReference(CURSOR,"moveToFirst",List.of(),"Z")));
  b.addLabel("end");b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE,1,0,0,0,0,0,new ImmutableMethodReference(CURSOR,"close",List.of(),"V")));
  b.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING,0,new ImmutableStringReference("existing-data-or-default")));b.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT,0));
  b.addLabel("handler");b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_EXCEPTION,1));b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE,1,0,0,0,0,0,new ImmutableMethodReference(CURSOR,"close",List.of(),"V")));b.addInstruction(new BuilderInstruction11x(Opcode.THROW,1));b.addCatch(b.getLabel("begin"),b.getLabel("end"),b.getLabel("handler"));
  Method data=new ImmutableMethod(owner,"getGameData",GeneralCompatibilityTest.params(STRING),STRING,1,Set.of(),Set.of(),b.getMethodImplementation());
  Method purchase=new ImmutableMethod(owner,"requestPurchase",List.of(),"V",1,Set.of(),Set.of(),new ImmutableMethodImplementation(1,List.of(new ImmutableInstruction10x(Opcode.RETURN_VOID)),List.of(),List.of()));
  return GeneralCompatibilityTest.dex(cls(owner,List.of(data,purchase)));
 }
 static Method method(byte[] bytes,String name){for(ClassDef c:new DexBackedDexFile(null,bytes).getClasses())for(Method m:c.getMethods())if(m.getName().equals(name))return m;throw new AssertionError(name);}
 static final class IntentModel {String action,pkg,component;Map<String,String> extras=new HashMap<>();IntentModel(String a){action=a;}}
 static final class ServiceModel {String packageName="fixture.moga.provider",name="fixture.MogaService";}
 static final class ResolveModel {ServiceModel serviceInfo=new ServiceModel();}
 static final class Platform {Object receivedContext,receivedConnection,startResult=new Object();int flags,calls,closes,queries;List<ResolveModel> providers=List.of(new ResolveModel());IntentModel received;boolean result,denied,cursorPresent,queryFails;RuntimeException startError,queryError=new RuntimeException("query failed");}
 // Bounded semantics model for the emitted helpers and cleanup paths. No vendor code executes.
 static Object execute(Method m,Platform platform,IntentModel intent){
  Map<Integer,Instruction> code=new HashMap<>();int at=0;for(Instruction i:m.getImplementation().getInstructions()){code.put(at,i);at+=i.getCodeUnits();}
  Object[] regs=new Object[m.getImplementation().getRegisterCount()];Object ctx=new Object(),connection=new Object();if(m.getName().equals(HELPER)||m.getName().equals(START_HELPER)){regs[3]=ctx;regs[4]=intent;if(m.getName().equals(HELPER)){regs[5]=connection;regs[6]=77;}}
  Object result=null,pending=null;int pc=0;
  for(int budget=0;budget<80;budget++){
   Instruction i=code.get(pc);check(i!=null,"branch/handler lands at instruction boundary");int next=pc+i.getCodeUnits();
   try{switch(i.getOpcode()){
    case CONST_4:regs[((OneRegisterInstruction)i).getRegisterA()]=((NarrowLiteralInstruction)i).getNarrowLiteral();break;
    case CONST_STRING:regs[((OneRegisterInstruction)i).getRegisterA()]=((StringReference)((ReferenceInstruction)i).getReference()).getString();break;
    case NEW_INSTANCE:regs[((OneRegisterInstruction)i).getRegisterA()]=((TypeReference)((ReferenceInstruction)i).getReference()).getType().equals(INTENT)?new IntentModel(null):new IllegalArgumentException("Ambiguous or invalid MOGA service metadata");break;
    case CHECK_CAST:check(regs[((OneRegisterInstruction)i).getRegisterA()] instanceof ResolveModel,"registry resolve type");break;
    case IGET_OBJECT:{var r=(TwoRegisterInstruction)i;var f=(FieldReference)((ReferenceInstruction)i).getReference();Object receiver=regs[r.getRegisterB()];regs[r.getRegisterA()]=f.getName().equals("serviceInfo")?((ResolveModel)receiver).serviceInfo:f.getName().equals("packageName")?((ServiceModel)receiver).packageName:((ServiceModel)receiver).name;break;}
    case MOVE_OBJECT:regs[((TwoRegisterInstruction)i).getRegisterA()]=regs[((TwoRegisterInstruction)i).getRegisterB()];break;
    case MOVE_RESULT:case MOVE_RESULT_OBJECT:regs[((OneRegisterInstruction)i).getRegisterA()]=result;break;
    case MOVE_EXCEPTION:regs[((OneRegisterInstruction)i).getRegisterA()]=pending;pending=null;break;
    case IF_NEZ:case IF_EQZ:{Object value=regs[((OneRegisterInstruction)i).getRegisterA()];boolean zero=value==null||Boolean.FALSE.equals(value)||Integer.valueOf(0).equals(value);if(zero==(i.getOpcode()==Opcode.IF_EQZ))next=pc+((OffsetInstruction)i).getCodeOffset();break;}
    case IF_NE:{var r=(TwoRegisterInstruction)i;if(!Objects.equals(regs[r.getRegisterA()],regs[r.getRegisterB()]))next=pc+((OffsetInstruction)i).getCodeOffset();break;}
    case GOTO:case GOTO_16:next=pc+((OffsetInstruction)i).getCodeOffset();break;
    case RETURN:case RETURN_OBJECT:{Object value=regs[((OneRegisterInstruction)i).getRegisterA()];return i.getOpcode()==Opcode.RETURN_OBJECT?(Integer.valueOf(0).equals(value)?null:value):(value instanceof Integer?(int)value!=0:value);}
    case THROW:throw (RuntimeException)regs[((OneRegisterInstruction)i).getRegisterA()];
    case INVOKE_VIRTUAL:case INVOKE_DIRECT:case INVOKE_INTERFACE:case INVOKE_STATIC:{var call=(FiveRegisterInstruction)i;var ref=(MethodReference)((ReferenceInstruction)i).getReference();Object receiver=regs[call.getRegisterC()];
     switch(ref.getName()){
      case "getComponent":result=((IntentModel)receiver).component;break;case "getPackage":result=((IntentModel)receiver).pkg;break;case "getAction":result=((IntentModel)receiver).action;break;
      case "equals":result=receiver.equals(regs[call.getRegisterD()]);break;
      case "<init>":{if(receiver instanceof IntentModel){var original=(IntentModel)regs[call.getRegisterD()];var copy=(IntentModel)receiver;copy.action=original.action;copy.pkg=original.pkg;copy.component=original.component;copy.extras.putAll(original.extras);}break;}
      case "setPackage":((IntentModel)receiver).pkg=(String)regs[call.getRegisterD()];result=receiver;break;
      case "setClassName":((IntentModel)receiver).pkg=(String)regs[call.getRegisterD()];((IntentModel)receiver).component=(String)regs[call.getRegisterE()];result=receiver;break;
      case "getPackageManager":check(receiver==ctx,"registry Context retained");result=platform;break;
      case "queryIntentServices":check(receiver==platform&&regs[call.getRegisterD()]==intent&&Integer.valueOf(0).equals(regs[call.getRegisterE()]),"registry query uses original Intent and zero flags");platform.queries++;result=platform.providers;break;
      case "size":result=((List<?>)receiver).size();break;
      case "get":result=((List<?>)receiver).get((int)regs[call.getRegisterD()]);break;
      case "bindService":platform.calls++;platform.receivedContext=receiver;platform.received=(IntentModel)regs[call.getRegisterD()];platform.receivedConnection=regs[call.getRegisterE()];platform.flags=(int)regs[call.getRegisterF()];check(receiver==ctx&&platform.receivedConnection==connection&&platform.flags==77,"context/connection/flags retained");if(platform.denied)throw new SecurityException("permission denied");if(platform.received==null)throw new NullPointerException("platform rejected null intent");if(platform.received.pkg==null&&platform.received.component==null)throw new IllegalArgumentException("Service Intent must be explicit");result=platform.result;break;
      case "startService":platform.calls++;platform.receivedContext=receiver;platform.received=(IntentModel)regs[call.getRegisterD()];check(receiver==ctx,"start context retained");if(platform.denied)throw new SecurityException("permission denied");if(platform.received==null)throw new NullPointerException("platform rejected null intent");if(platform.received.pkg==null&&platform.received.component==null)throw new IllegalArgumentException("Service Intent must be explicit");if(platform.startError!=null)throw platform.startError;result=platform.startResult;break;
      case "query":if(platform.queryFails)throw platform.queryError;result=platform.cursorPresent?new Object():null;break;
      case "moveToFirst":check(receiver!=null,"query null check retained");result=true;break;
      case "close":check(receiver!=null,"null cursor close must be guarded, including incoming branch and exception path");platform.closes++;break;
      default:throw new AssertionError(ref);
     }break;}
    default:throw new AssertionError("unexpected modeled instruction "+i.getOpcode());
   }}catch(RuntimeException error){boolean handled=false;for(var t:m.getImplementation().getTryBlocks())if(pc>=t.getStartCodeAddress()&&pc<t.getStartCodeAddress()+t.getCodeUnitCount())for(var h:t.getExceptionHandlers())if(h.getExceptionType()==null){pending=error;next=h.getHandlerCodeAddress();handled=true;}if(!handled)throw error;}
   pc=next;
  }throw new AssertionError("execution budget exceeded");
 }
 public static void main(String[] args)throws Exception{
  for(int shift:new int[]{0,0x100})for(String path:new String[]{"D:/NVPACK/android-ndk-r7c/platforms/android-9/arch-arm/usr/lib/libEGL.so","/build/android-ndk-r9/platforms/android-9/arch-arm/usr/lib/libGLESv2.so","C:\\NDK\\android-ndk-r8\\platforms\\android-9\\arch-arm\\usr\\lib\\liblog.so"}){
   byte[] original=elf(path,shift),copy=original.clone();var scan=NdkDependencyPathRule.inspect(original);check(scan.status==CompatibilityEvidence.Status.SUPPORTED&&scan.replacement()==null,"read-only NDK detection");var fixed=NdkDependencyPathRule.repair(original);check(fixed.status==CompatibilityEvidence.Status.VERIFIED,"NDK repair "+fixed.reason);byte[] out=fixed.replacement();
   check(Arrays.equals(original,copy)&&out.length==original.length,"source immutable and length unchanged");check(needed(out,shift).equals(path.substring(Math.max(path.lastIndexOf('/'),path.lastIndexOf('\\'))+1)),"soname suffix derived");
   for(int n=0;n<original.length;n++)if(n<0x200+shift+0x44||n>=0x200+shift+0x48)check(original[n]==out[n],"only DT_NEEDED index changes");check(NdkDependencyPathRule.repair(out).replacement()==null,"NDK idempotence");
  }
  for(String path:new String[]{"libEGL.so","relative/android-ndk-r7/platforms/android-9/arch-arm/usr/lib/libEGL.so","/system/lib/libEGL.so","/build/android-ndk-r7/platforms/android-9/arch-x86/usr/lib/libEGL.so"})check(NdkDependencyPathRule.inspect(elf(path,0)).status==CompatibilityEvidence.Status.SKIPPED,"reject nonmatching dependency path");
  check(NdkDependencyPathRule.repair(elf("D:/android-ndk-r7/platforms/android-9/arch-arm/usr/lib/libstagefright.so",0)).status==CompatibilityEvidence.Status.UNSUPPORTED,"private API path retained");
  check(NdkDependencyPathRule.repair(new byte[12]).status==CompatibilityEvidence.Status.MALFORMED,"truncated ELF");byte[] bad=elf("libEGL.so",0);ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(0x244,0x7fffffff);check(NdkDependencyPathRule.repair(bad).status==CompatibilityEvidence.Status.MALFORMED,"invalid string index");
  String host="D:/android-ndk-r7/platforms/android-9/arch-arm/usr/lib/libEGL.so";
  bad=elf(host,0);ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(84+4,0x100);check(NdkDependencyPathRule.repair(bad).replacement()==null,"aliased load bytes rejected");
  bad=elf(host,0);ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(84+28,0x1000);check(NdkDependencyPathRule.repair(bad).status==CompatibilityEvidence.Status.MALFORMED,"invalid alignment rejected");
  bad=elf(host,0);ByteBuffer b=ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0x260,17).putInt(0x264,0x2200).putInt(0x268,18).putInt(0x26c,8).putInt(0x270,19).putInt(0x274,8).putInt(0x278,0);b.putInt(0x400,0x2044).putInt(0x404,23);
  check(NdkDependencyPathRule.repair(bad).status==CompatibilityEvidence.Status.UNSUPPORTED,"dynamic metadata relocation rejected");b.putInt(0x400,0x2550);check(NdkDependencyPathRule.repair(bad).status==CompatibilityEvidence.Status.VERIFIED,"BSS relocation does not require file-backed target");
  b.putInt(0x404,160);check(NdkDependencyPathRule.repair(bad).status==CompatibilityEvidence.Status.UNKNOWN,"resolver relocation rejected");
  bad=elf(host,0);b=ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0x260,0x6000000f).putInt(0x264,0x2200).putInt(0x268,0);check(NdkDependencyPathRule.repair(bad).status==CompatibilityEvidence.Status.UNKNOWN,"packed relocation layout rejected");
  bad=Arrays.copyOf(elf(host,0),0x4000);b=ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN);b.putInt(84+16,0x3000).putInt(84+20,0x3000).putInt(116+16,0x1000).putInt(116+20,0x1000);Arrays.fill(bad,0x240,0x1240,(byte)0);int tag=0x240;for(int n=0;n<257;n++){b.putInt(tag,1).putInt(tag+4,1);tag+=8;}b.putInt(tag,5).putInt(tag+4,0x3800).putInt(tag+8,10).putInt(tag+12,host.length()+2);byte[] paths=("\0"+host+"\0").getBytes(StandardCharsets.US_ASCII);System.arraycopy(paths,0,bad,0x1a00,paths.length);check(NdkDependencyPathRule.repair(bad).status==CompatibilityEvidence.Status.SKIPPED,"dependency count budget not a passing repair");
  for(String type:new String[]{"Lcom/bda/controller/Controller;","Lcom/google/analytics/tracking/android/AnalyticsGmsCoreClient;","Lcom/example/game/Anything;"})for(boolean range:new boolean[]{false,true}){
   byte[] input=service(type,range,"");var r=LegacySdkCrashRules.repair(Map.of("classes.dex",input));check(r.supported()&&r.replacements.size()==1,"service call rewrite "+r.report());byte[] out=r.replacements.get("classes.dex");check(!LegacySdkCrashRules.repair(Map.of("classes.dex",out)).supported(),"service idempotence");
   var call=method(out,"connect").getImplementation().getInstructions().iterator().next();check(call.getOpcode()==(range?Opcode.INVOKE_STATIC_RANGE:Opcode.INVOKE_STATIC),"invoke format retained");if(range)check(((RegisterRangeInstruction)call).getStartRegister()==300,"high register range retained");
   for(String action:new String[]{LegacySdkCrashRules.MOGA_ACTION,LegacySdkCrashRules.ANALYTICS_ACTION,"unrelated.action",null})for(int explicit=0;explicit<3;explicit++)for(boolean result:new boolean[]{false,true}){
    var intent=new IntentModel(action);intent.extras.put("keep","data");if(explicit==1)intent.pkg="existing.provider";if(explicit==2)intent.component="existing.component";var p=new Platform();p.result=result;
    Object returned=execute(method(out,HELPER),p,intent);check(Boolean.valueOf(result).equals(returned),"real bind result retained");
    check(p.calls==1&&p.received.extras.equals(intent.extras)&&Objects.equals(intent.action,p.received.action),"single platform call, action and extras retained");
    if(explicit==0){check(p.received!=intent&&intent.pkg==null&&intent.component==null,"original intent immutable");check(p.received.pkg.equals("fixture.moga.provider")&&p.received.component.equals("fixture.MogaService")&&p.queries==1,"any implicit action resolved to its unique registered service");}else check(p.received==intent&&p.queries==0,"explicit intent identity retained without registry query");
   }
   var p=new Platform();p.denied=true;try{execute(method(out,HELPER),p,new IntentModel(LegacySdkCrashRules.MOGA_ACTION));throw new AssertionError("permission denial swallowed");}catch(SecurityException expected){}
   p=new Platform();try{execute(method(out,HELPER),p,null);throw new AssertionError("null intent accepted");}catch(NullPointerException expected){check(p.calls==1,"null intent passed to original platform call");}
   for(List<ResolveModel> providers:Arrays.asList(Collections.<ResolveModel>emptyList(),null)){p=new Platform();p.providers=providers;check(Boolean.FALSE.equals(execute(method(out,HELPER),p,new IntentModel(LegacySdkCrashRules.MOGA_ACTION)))&&p.calls==0&&p.queries==1,"no visible MOGA service returns false, never bound");}
   p=new Platform();p.providers=List.of(new ResolveModel(),new ResolveModel());check(Boolean.FALSE.equals(execute(method(out,HELPER),p,new IntentModel(LegacySdkCrashRules.MOGA_ACTION)))&&p.calls==0,"several matching services: unavailable, none bound");
   p=new Platform();ResolveModel invalid=new ResolveModel();invalid.serviceInfo=null;p.providers=List.of(invalid);check(Boolean.FALSE.equals(execute(method(out,HELPER),p,new IntentModel("any.action")))&&p.calls==0,"invalid metadata: unavailable");
  }
  for(String near:new String[]{"receiver","signature","super","collision"})check(LegacySdkCrashRules.repair(Map.of("classes.dex",service("Lcom/bda/controller/Controller;",false,near))).replacements.isEmpty(),"service near-match retained: "+near);
  check(LegacySdkCrashRules.repair(Map.of("classes.dex",service("Lunrelated/Service;",false,""))).replacements.isEmpty(),"unrelated SDK untouched");
  byte[] input=cursorDex(),copy=input.clone();var cursor=LegacySdkCrashRules.repair(Map.of("classes2.dex",input));check(cursor.supported()&&cursor.replacements.size()==1,"cursor rewrite "+cursor.report());byte[] out=cursor.replacements.get("classes2.dex");check(Arrays.equals(input,copy),"cursor source immutable");check(!LegacySdkCrashRules.repair(Map.of("classes2.dex",out)).supported(),"cursor idempotence");
  for(boolean present:new boolean[]{false,true}){var p=new Platform();p.cursorPresent=present;check(execute(method(out,"getGameData"),p,null).equals("existing-data-or-default"),"original data/default return preserved");check(p.closes==(present?1:0),"non-null cursor closes once");}
  var p=new Platform();p.queryFails=true;try{execute(method(out,"getGameData"),p,null);throw new AssertionError("query error swallowed");}catch(RuntimeException expected){check(expected==p.queryError&&p.closes==0,"original query exception retained after null-safe cleanup");}
  check(method(out,"requestPurchase").getImplementation().getInstructions().iterator().next().getOpcode()==Opcode.RETURN_VOID,"purchase method untouched");
  check(LegacySdkCrashRules.repair(Map.of("classes.dex",new byte[12])).replacements.isEmpty(),"malformed DEX retained");check(LegacySdkCrashRules.repair(Map.of("classes.dex",input,"classes2.dex",input)).replacements.isEmpty(),"duplicate class retained");
  byte[] reserved=GeneralCompatibilityTest.dex(cls("Lfixture/Protected;",List.of(GeneralCompatibilityTest.method("Lfixture/Protected;","probe",List.of(),9,false,List.of(new ImmutableInstruction10x(Opcode.RETURN_VOID))))));
  for(ClassDef c:new DexBackedDexFile(null,reserved).getClasses())for(Method m:c.getMethods())for(Instruction i:m.getImplementation().getInstructions())reserved[((org.jf.dexlib2.dexbacked.instruction.DexBackedInstruction)i).instructionStart]=0x3e;
  var rejected=LegacySdkCrashRules.repair(Map.of("classes.dex",input,"classes2.dex",reserved));check(rejected.replacements.isEmpty()&&rejected.findings.get(0).status==CompatibilityEvidence.Status.UNSUPPORTED,"reserved instructions in any DEX prevent edits");
  Path dir=Files.createTempDirectory("platform-crash-rules"),apk=dir.resolve("original.apk"),converted=dir.resolve("converted.apk");List<ClassDef> all=new ArrayList<>();for(byte[] dex:new byte[][]{cursorDex(),service("Lcom/bda/controller/Controller;",false,"")})for(ClassDef c:new DexBackedDexFile(null,dex).getClasses())all.add(c);
  byte[] combined=GeneralCompatibilityTest.dex(all.toArray(new ClassDef[0]));byte[] nativeInput=elf("D:/android-ndk-r7/platforms/android-9/arch-arm/usr/lib/libEGL.so",0),asset={9,8,7};
  ModernApkDoctorTest.apk(apk,GeneralCompatibilityTest.manifest(false),Map.of("classes.dex",combined,"lib/armeabi-v7a/libgame.so",nativeInput,"assets/keep",asset));
  check(ApkRepairAssessment.inspect(apk.toFile(),34,new String[]{"armeabi-v7a"}).suggested==ApkRepairAssessment.Route.STARTUP,"shared repair recommendation");
  try(var z=new ZipFile(apk.toFile())){check(SharedCompatibilityRepairs.plan(z,false).replacements.isEmpty(),"coordinator preview read-only");}
  ApkRebuilder.rebuild(apk.toFile(),converted.toFile(),null,false,false,true);try(var z=new ZipFile(converted.toFile())){check(Arrays.equals(z.getInputStream(z.getEntry("assets/keep")).readAllBytes(),asset),"asset preserved");check(needed(z.getInputStream(z.getEntry("lib/armeabi-v7a/libgame.so")).readAllBytes(),0).equals("libEGL.so"),"dependency conversion composed");check(!LegacySdkCrashRules.inspect(Map.of("classes.dex",z.getInputStream(z.getEntry("classes.dex")).readAllBytes())).supported(),"service/cursor serialized edits composed");{var rec=z.getEntry(ConversionRecord.ENTRY);check(rec!=null,"conversion record written");String sum=ConversionRecord.summarize(new String(z.getInputStream(rec).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));check(sum.startsWith("Converted by PocketOuya ")&&sum.contains("startup repairs=yes, touch=no")&&sum.contains(LegacySdkCrashRules.SERVICE_ID),"record summary lists options and rule results: "+sum);}check(z.getEntry("classes2.dex")==null,"no helper DEX added");}
  System.out.println("Platform crash rules: NDK path layouts/allowlist/bounds, MOGA/Analytics direct/range calls, platform results/security/Intent identity, cursor null/exception/incoming branches and combined conversion pass. Actual affected APK fixtures remain unavailable.");
 }
}
