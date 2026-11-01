package io.github.hozdogus.pocketouya;

import java.util.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.zip.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.writer.pool.DexPool;
import org.jf.dexlib2.writer.io.MemoryDataStore;

public final class GeneralCompatibilityTest {
 static final String LAUNCHER="Lorg/example/OtherGame;",SDK="Ltv/ouya/sdk/OuyaUnityActivity;";
 static final String[] MARKERS={"OuyaGameObject","ProductListClearListener","ProductListListener","ProductListCompleteListener","ReceiptListClearListener","ReceiptListListener","ReceiptListCompleteListener","PurchaseSuccessListener","FetchGamerInfoSuccessListener"};
 static void check(boolean value){if(!value)throw new AssertionError("General compatibility regression");}
 static List<ImmutableMethodParameter> params(String...types){List<ImmutableMethodParameter> out=new ArrayList<>();for(String t:types)out.add(new ImmutableMethodParameter(t,Collections.emptySet(),null));return out;}
 static ImmutableMethod method(String owner,String name,List<ImmutableMethodParameter> p,int flags,boolean nativeMethod,List<Instruction> instructions){
  return new ImmutableMethod(owner,name,p,"V",flags,Collections.emptySet(),Collections.emptySet(),nativeMethod?null:new ImmutableMethodImplementation(Math.max(1,p.size()+((flags&8)==0?1:0)),instructions,Collections.emptyList(),Collections.emptyList()));
 }
 static ClassDef cls(String type,String parent,List<Method> methods){return new ImmutableClassDef(type,1,parent,Collections.emptyList(),null,Collections.emptySet(),Collections.emptyList(),methods);}
 static byte[] dex(ClassDef...classes)throws Exception{DexPool pool=new DexPool(Opcodes.forApi(23));for(ClassDef c:classes)pool.internClass(c);MemoryDataStore out=new MemoryDataStore();pool.writeTo(out);return out.getData();}
 static Map<String,byte[]> input(boolean missing,boolean callbackMarkers)throws Exception{
  List<Instruction> literals=new ArrayList<>();if(callbackMarkers)for(String s:MARKERS)literals.add(new ImmutableInstruction21c(Opcode.CONST_STRING,0,new ImmutableStringReference(s)));literals.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
  ClassDef facade=cls("Ltv/ouya/sdk/UnityOuyaFacade;","Ljava/lang/Object;",Collections.singletonList(method("Ltv/ouya/sdk/UnityOuyaFacade;","markers",params(),9,false,literals)));
  List<Method> natives=Arrays.asList(method(SDK,"dispatchKeyEventNative",params("I","I","I"),258,true,null),method(SDK,"dispatchGenericMotionEventNative",params("I","I","F"),258,true,null));
  String unity="Lcom/unity3d/player/UnityPlayer;";
  ClassDef player=new ImmutableClassDef(unity,1,"Landroid/widget/FrameLayout;",Collections.emptyList(),null,Collections.emptySet(),Collections.singletonList(new ImmutableField(unity,"currentActivity","Landroid/app/Activity;",9,null,Collections.emptySet(),Collections.emptySet())),Collections.singletonList(method(unity,"UnitySendMessage",params("Ljava/lang/String;","Ljava/lang/String;","Ljava/lang/String;"),9,false,Collections.singletonList(new ImmutableInstruction10x(Opcode.RETURN_VOID)))));
  Map<String,byte[]> out=new LinkedHashMap<>();out.put("classes.dex",OuyaPurchasePatcherTest.fixture(missing));
  out.put("classes2.dex",dex(cls(LAUNCHER,SDK,Collections.emptyList()),cls(SDK,"Landroid/app/Activity;",natives),facade,player));return out;
 }
 static int bridgeCalls(byte[] bytes,String typePrefix){int n=0;for(ClassDef c:new DexBackedDexFile(null,bytes).getClasses())for(Method m:c.getMethods())if(m.getImplementation()!=null)for(Instruction i:m.getImplementation().getInstructions())if(i instanceof ReferenceInstruction&&((ReferenceInstruction)i).getReference() instanceof MethodReference&&((MethodReference)((ReferenceInstruction)i).getReference()).getDefiningClass().startsWith(typePrefix))n++;return n;}
 static void lifecycle(byte[] bytes){
  ClassDef launcher=null;for(ClassDef cls:new DexBackedDexFile(null,bytes).getClasses())if(cls.getType().equals(LAUNCHER))launcher=cls;
  check(launcher!=null);Set<String> methods=new HashSet<>();
  for(Method m:launcher.getMethods()){
   methods.add(m.getName());
   if(m.getName().equals("onCreate")){check(m.getParameterTypes().equals(Collections.singletonList("Landroid/os/Bundle;")));check(m.getImplementation().getRegisterCount()==2);}
  }
  check(methods.containsAll(Arrays.asList("onCreate","onResume","onPause")));
 }
 static byte[] manifest(boolean alias)throws Exception{
  // Minimal AXML with typed-string attributes (rawValue=-1), including an alias.
  List<String> strings=Arrays.asList("manifest","package","org.example","uses-sdk","minSdkVersion","targetSdkVersion","application","activity","activity-alias","name","targetActivity",".OtherGame",".Alias","intent-filter","action","category","android.intent.action.MAIN","android.intent.category.LAUNCHER");
  ByteArrayOutputStream poolBytes=new ByteArrayOutputStream();List<Integer> offsets=new ArrayList<>();
  for(String str:strings){offsets.add(poolBytes.size());byte[] b=str.getBytes(StandardCharsets.UTF_8);poolBytes.write(str.length());poolBytes.write(b.length);poolBytes.write(b);poolBytes.write(0);}
  while(poolBytes.size()%4!=0)poolBytes.write(0);
  int poolSize=28+strings.size()*4+poolBytes.size();ByteBuffer pool=ByteBuffer.allocate(poolSize).order(ByteOrder.LITTLE_ENDIAN);pool.putShort((short)1).putShort((short)28).putInt(poolSize).putInt(strings.size()).putInt(0).putInt(0x100).putInt(28+strings.size()*4).putInt(0);for(int offset:offsets)pool.putInt(offset);pool.put(poolBytes.toByteArray());
  ByteArrayOutputStream body=new ByteArrayOutputStream();body.write(pool.array());
  start(body,strings,"manifest",new String[]{"package"},new Object[]{"org.example"});
  start(body,strings,"uses-sdk",new String[]{"minSdkVersion","targetSdkVersion"},new Object[]{16,16});end(body,strings,"uses-sdk");
  start(body,strings,"application",new String[0],new Object[0]);
  start(body,strings,alias?"activity-alias":"activity",alias?new String[]{"name","targetActivity"}:new String[]{"name"},alias?new Object[]{".Alias",".OtherGame"}:new Object[]{".OtherGame"});
  start(body,strings,"intent-filter",new String[0],new Object[0]);start(body,strings,"action",new String[]{"name"},new Object[]{"android.intent.action.MAIN"});end(body,strings,"action");start(body,strings,"category",new String[]{"name"},new Object[]{"android.intent.category.LAUNCHER"});end(body,strings,"category");end(body,strings,"intent-filter");end(body,strings,alias?"activity-alias":"activity");end(body,strings,"application");end(body,strings,"manifest");
  ByteBuffer all=ByteBuffer.allocate(body.size()+8).order(ByteOrder.LITTLE_ENDIAN);all.putShort((short)3).putShort((short)8).putInt(all.capacity()).put(body.toByteArray());return all.array();
 }
 static void start(ByteArrayOutputStream out,List<String> s,String tag,String[] names,Object[] values)throws Exception{
  ByteBuffer b=ByteBuffer.allocate(36+20*names.length).order(ByteOrder.LITTLE_ENDIAN);b.putShort((short)0x102).putShort((short)16).putInt(b.capacity()).putInt(1).putInt(-1).putInt(-1).putInt(s.indexOf(tag));b.putShort((short)20).putShort((short)20).putShort((short)names.length).putShort((short)0).putShort((short)0).putShort((short)0);
  for(int i=0;i<names.length;i++)b.putInt(-1).putInt(s.indexOf(names[i])).putInt(-1).putShort((short)8).put((byte)0).put((byte)(values[i] instanceof String?3:16)).putInt(values[i] instanceof String?s.indexOf(values[i]):(Integer)values[i]);out.write(b.array());
 }
 static void end(ByteArrayOutputStream out,List<String>s,String tag)throws Exception{ByteBuffer b=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);b.putShort((short)0x103).putShort((short)16).putInt(24).putInt(1).putInt(-1).putInt(-1).putInt(s.indexOf(tag));out.write(b.array());}
 public static void main(String[] args)throws Exception{
  Set<String> launch=Collections.singleton(LAUNCHER);
  ManifestPatcher.Result parsed=ManifestPatcher.patch(manifest(false));check(parsed.packageName.equals("org.example"));check(parsed.launcherActivities.equals(launch));check(parsed.newTarget==23);check(ManifestPatcher.patch(manifest(true)).launcherActivities.equals(launch));
  Map<String,byte[]> source=input(false,true);GameCompatibilityPatcher.Result both=GameCompatibilityPatcher.patch(source,launch,true,true);
  check(both.touchEnabled&&both.purchasesEnabled&&both.dex.size()==2);check(both.report.contains("1 use the OUYA native"));
  lifecycle(both.dex.get("classes2.dex"));check(bridgeCalls(both.dex.get("classes.dex"),"Lio/github/hcode91/pocketouya/bridge/OfflineOuya;")==6);
  GameCompatibilityPatcher.Result onlyTouch=GameCompatibilityPatcher.patch(source,launch,true,false);check(onlyTouch.touchEnabled&&!onlyTouch.purchasesEnabled&&!onlyTouch.dex.containsKey("classes.dex"));
  GameCompatibilityPatcher.Result onlyPurchase=GameCompatibilityPatcher.patch(source,launch,false,true);check(!onlyPurchase.touchEnabled&&onlyPurchase.purchasesEnabled&&onlyPurchase.dex.size()==1);
  for(boolean missing:new boolean[]{true,false}){GameCompatibilityPatcher.Result unsupported=GameCompatibilityPatcher.patch(input(missing,missing),launch,true,true);check(unsupported.touchEnabled&&!unsupported.purchasesEnabled);check(unsupported.report.contains("Original purchase checks were left unchanged"));}
  GameCompatibilityPatcher.Result noLauncher=GameCompatibilityPatcher.patch(source,Collections.singleton("Lmissing/Activity;"),true,false);check(!noLauncher.needsBridge()&&noLauncher.dex.isEmpty());
  Map<String,byte[]> fallback=new LinkedHashMap<>();fallback.put("classes.dex",dex(cls(LAUNCHER,"Landroid/app/Activity;",Collections.emptyList())));
  GameCompatibilityPatcher.Result generic=GameCompatibilityPatcher.patch(fallback,launch,true,true);check(generic.touchEnabled&&!generic.purchasesEnabled&&generic.report.contains("Android gamepad events"));
  Map<String,byte[]> already=new LinkedHashMap<>(source);already.put("classes3.dex",dex(cls("Lio/github/hcode91/pocketouya/bridge/OfflineOuya;","Ljava/lang/Object;",Collections.emptyList())));
  try{GameCompatibilityPatcher.patch(already,launch,true,true);throw new AssertionError("accepted reinjection");}catch(IOException expected){}
  ClassDef finalBase=cls("Lorg/example/Base;","Landroid/app/Activity;",Collections.singletonList(method("Lorg/example/Base;","onResume",params(),17,false,Collections.singletonList(new ImmutableInstruction10x(Opcode.RETURN_VOID)))));
  Map<String,byte[]> finals=new LinkedHashMap<>();finals.put("classes.dex",dex(finalBase,cls(LAUNCHER,"Lorg/example/Base;",Collections.emptyList())));
  check(!GameCompatibilityPatcher.patch(finals,launch,true,false).touchEnabled);
  byte[] packed=dex(cls("Lorg/example/LegacyWrapper;","Ljava/lang/Object;",Collections.singletonList(method("Lorg/example/LegacyWrapper;","probe",params(),9,false,Collections.singletonList(new ImmutableInstruction10x(Opcode.RETURN_VOID))))));
  // Reserved opcode in the input, not an instruction rewritten by the fixture writer.
  for(ClassDef c:new DexBackedDexFile(null,packed).getClasses())for(Method m:c.getMethods())for(Instruction i:m.getImplementation().getInstructions())packed[((org.jf.dexlib2.dexbacked.instruction.DexBackedInstruction)i).instructionStart]=0x3e;
  Map<String,byte[]> protectedSource=new LinkedHashMap<>(source);protectedSource.put("classes3.dex",packed);
  GameCompatibilityPatcher.Result protectedResult=GameCompatibilityPatcher.patch(protectedSource,launch,true,true);
  check(!protectedResult.needsBridge()&&protectedResult.dex.isEmpty()&&protectedResult.report.contains("reserved instructions"));
  String loader="Lcom/ideaworks3d/marmalade/LoaderActivity;",keyboard="Lcom/ideaworks3d/marmalade/LoaderKeyboard;";
  List<Method> callbacks=new ArrayList<>();for(String name:new String[]{"onKeyDown","onKeyUp"})callbacks.add(new ImmutableMethod(loader,name,params("I","Landroid/view/KeyEvent;"),"Z",1,Collections.emptySet(),Collections.emptySet(),new ImmutableMethodImplementation(3,Arrays.asList(new ImmutableInstruction11n(Opcode.CONST_4,0,0),new ImmutableInstruction11x(Opcode.RETURN,0)),Collections.emptyList(),Collections.emptyList())));
  Method nativeKey=new ImmutableMethod(keyboard,"onKeyEventNative",params("I","I","I"),"Z",258,Collections.emptySet(),Collections.emptySet(),null);
  Map<String,byte[]> marmalade=new LinkedHashMap<>();marmalade.put("classes.dex",dex(cls(LAUNCHER,loader,Collections.emptyList()),cls(loader,"Landroid/app/Activity;",callbacks),cls(keyboard,"Ljava/lang/Object;",Collections.singletonList(nativeKey))));
  GameCompatibilityPatcher.Result directKeys=GameCompatibilityPatcher.patch(marmalade,launch,true,false);
  check(directKeys.touchEnabled&&directKeys.report.contains("direct Marmalade Activity key callbacks")&&!directKeys.report.contains("Other input uses Android"));
  marmalade.put("classes.dex",dex(cls(LAUNCHER,loader,Collections.emptyList()),cls(loader,"Landroid/app/Activity;",callbacks)));
  check(GameCompatibilityPatcher.patch(marmalade,launch,true,false).report.contains("Other input uses Android"));
  Path temp=Files.createTempDirectory("general-converter-");
  try{
   Path original=temp.resolve("original.apk"),converted=temp.resolve("converted.apk");
   try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(original))){out.putNextEntry(new ZipEntry("AndroidManifest.xml"));out.write(manifest(false));out.closeEntry();for(Map.Entry<String,byte[]> e:source.entrySet()){out.putNextEntry(new ZipEntry(e.getKey()));out.write(e.getValue());out.closeEntry();}out.putNextEntry(new ZipEntry("assets/test"));out.write(new byte[]{4,5,6});out.closeEntry();}
   byte[] helper=dex(cls("Lio/github/hcode91/pocketouya/bridge/TestFixture;","Ljava/lang/Object;",Collections.emptyList()));
   ManifestPatcher.Result result=ApkRebuilder.rebuild(original.toFile(),converted.toFile(),helper,true,true);check(result.compatibilityReport.contains("enabled"));
   try(ZipFile zip=new ZipFile(converted.toFile())){check(zip.getEntry("classes3.dex")!=null);check(Arrays.equals(helper,ApkRebuilder.readAll(zip.getInputStream(zip.getEntry("classes3.dex")))));check(Arrays.equals(new byte[]{4,5,6},ApkRebuilder.readAll(zip.getInputStream(zip.getEntry("assets/test")))));}
   ApkRebuilder.rebuild(original.toFile(),converted.toFile());try(ZipFile zip=new ZipFile(converted.toFile())){check(zip.getEntry("classes3.dex")==null);check(Arrays.equals(source.get("classes.dex"),ApkRebuilder.readAll(zip.getInputStream(zip.getEntry("classes.dex")))));}
  }finally{try(var paths=Files.list(temp)){for(Path p:paths.toArray(Path[]::new))Files.delete(p);}Files.delete(temp);}
  System.out.println("Cross-game/multidex detection, activity/alias parsing, feature isolation, unsupported reporting, inherited lifecycle, reinjection and ZIP preservation tests pass");
 }
}
