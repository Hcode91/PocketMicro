package com.hcode91.pocketmicro;

import java.nio.*;import java.nio.file.*;import java.util.*;import java.util.zip.*;
import org.jf.dexlib2.*;import org.jf.dexlib2.dexbacked.DexBackedDexFile;import org.jf.dexlib2.iface.*;import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.immutable.*;import org.jf.dexlib2.immutable.instruction.*;import org.jf.dexlib2.immutable.reference.*;

public final class SharedCompatibilityRulesTest {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static byte[] elf(int layout){
  byte[] data=new byte[0x800];ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
  b.putInt(0,0x464c457f);data[4]=1;data[5]=1;data[6]=1;b.putShort(16,(short)3).putShort(18,(short)40).putInt(20,1).putInt(28,52).putShort(40,(short)52).putShort(42,(short)32).putShort(44,(short)3);
  int file=0x200+layout*0x100,va=0x2000+layout*0x1000;
  header(b,52,1,0,0,0x200,0x200,5);header(b,84,1,file,va,0x400,0x400,6);header(b,116,2,file+0x100,va+0x100,64,64,6);
  int d=file+0x100;int[][] tags={{17,va+0x180},{18,8},{19,8},{22,0},{30,12},{0,0}};for(int[] t:tags){b.putInt(d,t[0]).putInt(d+4,t[1]);d+=8;}
  b.putInt(file+0x180,va+0x200).putInt(file+0x184,23);b.putInt(file+0x200,0x1234);
  b.putInt(0x180,0x4b03b510);return data;
 }
 static void header(ByteBuffer b,int p,int type,int file,int va,int size,int mem,int flags){b.putInt(p,type).putInt(p+4,file).putInt(p+8,va).putInt(p+16,size).putInt(p+20,mem).putInt(p+24,flags).putInt(p+28,4);}
 static String type="Ltv/ouya/console/api/content/OuyaContent;";
 static byte[] dex(int layout,boolean extra)throws Exception{
  int self=layout==0?2:4,context=layout==0?0:3,connection=layout==0?1:1;
  List<org.jf.dexlib2.iface.instruction.Instruction> code=new ArrayList<>();
  code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,context,self,new ImmutableFieldReference(type,"context"+layout,"Landroid/content/Context;")));
  code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,connection,self,new ImmutableFieldReference(type,"connection"+layout,"Landroid/content/ServiceConnection;")));
  code.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,2,context,connection,0,0,0,new ImmutableMethodReference("Landroid/content/Context;","unbindService",List.of("Landroid/content/ServiceConnection;"),"V")));
  if(extra)code.add(new ImmutableInstruction10x(Opcode.NOP));
  code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
  Method m=new ImmutableMethod(type,"shutdown",List.of(),"V",1,Set.of(),Set.of(),new ImmutableMethodImplementation(self+1,code,List.of(),List.of()));
  List<Field> fields=List.of(new ImmutableField(type,"context"+layout,"Landroid/content/Context;",2,null,Set.of(),Set.of()),new ImmutableField(type,"connection"+layout,"Landroid/content/ServiceConnection;",2,null,Set.of(),Set.of()));
  ClassDef cls=new ImmutableClassDef(type,1,"Ljava/lang/Object;",List.of(),null,Set.of(),fields,List.of(m));
  return GeneralCompatibilityTest.dex(cls,GeneralCompatibilityTest.cls("Lexample/Game"+layout+";","Ljava/lang/Object;",List.of()));
 }
 // Execute the resulting bounded cleanup instruction path for both context states.
 static int unbindCalls(byte[] dex,boolean contextPresent){
  for(ClassDef c:new DexBackedDexFile(null,dex).getClasses())if(c.getType().equals(type))for(Method m:c.getMethods())if(m.getName().equals("shutdown")){
   int calls=0,pc=0;Map<Integer,Instruction> code=new HashMap<>();for(Instruction i:m.getImplementation().getInstructions()){code.put(pc,i);pc+=i.getCodeUnits();}
   pc=0;for(int budget=0;budget<10;budget++){Instruction i=code.get(pc);check(i!=null,"branch lands on instruction boundary");switch(i.getOpcode()){
    case IGET_OBJECT:pc+=i.getCodeUnits();break;
    case IF_EQZ:pc+=contextPresent?i.getCodeUnits():((OffsetInstruction)i).getCodeOffset();break;
    case INVOKE_VIRTUAL:check(contextPresent,"null receiver must not be invoked");calls++;pc+=i.getCodeUnits();break;
    case RETURN_VOID:return calls;
    default:throw new AssertionError("unexpected cleanup opcode");
   }}throw new AssertionError("cleanup did not terminate");
  }throw new AssertionError("shutdown missing");
 }
 static byte[] selfConnection(boolean typed)throws Exception{
  ClassDef owner=null;for(ClassDef c:new DexBackedDexFile(null,dex(0,false)).getClasses())if(c.getType().equals(type))owner=c;
  List<Method> methods=new ArrayList<>();for(Method m:owner.getMethods()){
   List<Instruction> code=new ArrayList<>();code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,0,1,new ImmutableFieldReference(type,"context0","Landroid/content/Context;")));
   code.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,2,0,1,0,0,0,new ImmutableMethodReference("Landroid/content/Context;","unbindService",List.of("Landroid/content/ServiceConnection;"),"V")));
   code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
   methods.add(new ImmutableMethod(type,"shutdown",List.of(),"V",1,Set.of(),Set.of(),new ImmutableMethodImplementation(2,code,List.of(),List.of())));
  }
  return GeneralCompatibilityTest.dex(new ImmutableClassDef(type,1,"Ljava/lang/Object;",typed?List.of("Landroid/content/ServiceConnection;"):List.of(),null,Set.of(),owner.getFields(),methods));
 }
 public static void main(String[]args)throws Exception{
  for(int layout=0;layout<2;layout++){
   byte[] original=elf(layout),before=original.clone();var scan=WritableRelocationRule.inspect(original);check(scan.status==CompatibilityEvidence.Status.SUPPORTED&&scan.replacement()==null,"read-only native scan");
   var repair=WritableRelocationRule.repair(original);check(repair.status==CompatibilityEvidence.Status.VERIFIED,"writable relocations accepted");byte[] changed=repair.replacement();
   check(Arrays.equals(original,before)&&!NativeStartupChecks.hasTextRelocations(changed),"input immutable and declaration removed");check(WritableRelocationRule.repair(changed).replacement()==null,"native idempotence");
   check(ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).getInt(0x200+layout*0x100+0x11c)==8,"other DF flags preserved after compaction");
   byte[] actual=original.clone();ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN).putInt(0x200+layout*0x100+0x180,0x180);check(WritableRelocationRule.repair(actual).status==CompatibilityEvidence.Status.UNSUPPORTED,"actual code write rejected");
   byte[] unknown=original.clone();ByteBuffer.wrap(unknown).order(ByteOrder.LITTLE_ENDIAN).putInt(0x200+layout*0x100+0x184,250);check(WritableRelocationRule.repair(unknown).replacement()==null,"unknown relocation rejected");
   byte[] packed=original.clone();ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN).putInt(0x200+layout*0x100+0x118,36);check(WritableRelocationRule.repair(packed).status==CompatibilityEvidence.Status.UNSUPPORTED,"packed relocation rejected");
   for(int kind:new int[]{20,160}){byte[] unsupported=original.clone();ByteBuffer.wrap(unsupported).order(ByteOrder.LITTLE_ENDIAN).putInt(0x200+layout*0x100+0x184,kind);check(WritableRelocationRule.repair(unsupported).status==CompatibilityEvidence.Status.UNSUPPORTED,"COPY/resolver writes rejected");}
   byte[] badSymbol=original.clone();ByteBuffer.wrap(badSymbol).order(ByteOrder.LITTLE_ENDIAN).putInt(0x200+layout*0x100+0x184,0x117);check(WritableRelocationRule.repair(badSymbol).status==CompatibilityEvidence.Status.MALFORMED,"invalid RELATIVE symbol rejected");
   byte[] prefix=original.clone();ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).putInt(0x200+layout*0x100+0x128,0x6ffffffa).putInt(0x200+layout*0x100+0x12c,2);check(WritableRelocationRule.repair(prefix).status==CompatibilityEvidence.Status.MALFORMED,"RELCOUNT overflow rejected");
   byte[] metadata=original.clone();ByteBuffer.wrap(metadata).order(ByteOrder.LITTLE_ENDIAN).putInt(0x200+layout*0x100+0x180,0x2100+layout*0x1000);check(WritableRelocationRule.repair(metadata).status==CompatibilityEvidence.Status.UNSUPPORTED,"self-modifying metadata rejected");
   byte[] incomplete=original.clone();ByteBuffer.wrap(incomplete).order(ByteOrder.LITTLE_ENDIAN).putInt(0x200+layout*0x100+0x110,1);check(WritableRelocationRule.repair(incomplete).status==CompatibilityEvidence.Status.UNKNOWN,"incomplete evidence rejected");
   byte[] input=dex(layout,false),copy=input.clone();Map<String,byte[]> files=Map.of("classes2.dex",input);
   check(OuyaServiceCleanupRule.inspect(files).status==CompatibilityEvidence.Status.SUPPORTED,"shared SDK scan");
   var fixed=OuyaServiceCleanupRule.repair(files);check(fixed.status==CompatibilityEvidence.Status.VERIFIED,"cleanup guard emitted");byte[] output=fixed.replacements.get("classes2.dex");
   check(Arrays.equals(input,copy),"DEX input immutable");check(unbindCalls(output,false)==0&&unbindCalls(output,true)==1,"null/non-null cleanup behavior");
   check(OuyaServiceCleanupRule.repair(Map.of("classes2.dex",output)).status==CompatibilityEvidence.Status.SKIPPED,"already guarded status and no repeated edits");
   check(OuyaServiceCleanupRule.repair(Map.of("classes.dex",dex(layout,true))).status==CompatibilityEvidence.Status.UNKNOWN,"different shutdown effects rejected");
  }
  byte[] self=selfConnection(true),copy=self.clone();var selfFixed=OuyaServiceCleanupRule.repair(Map.of("classes.dex",self));
  check(selfFixed.status==CompatibilityEvidence.Status.VERIFIED,"typed ServiceConnection receiver supported");
  byte[] guarded=selfFixed.replacements.get("classes.dex");check(Arrays.equals(self,copy)&&unbindCalls(guarded,false)==0&&unbindCalls(guarded,true)==1,"self connection behavior and input preservation");
  check(OuyaServiceCleanupRule.repair(Map.of("classes.dex",guarded)).status==CompatibilityEvidence.Status.SKIPPED,"self guard idempotence");
  check(OuyaServiceCleanupRule.repair(Map.of("classes.dex",selfConnection(false))).status==CompatibilityEvidence.Status.UNKNOWN,"untyped self receiver rejected");
  for(int n:new int[]{0,1,51,52,100,0x310,0x384})check(WritableRelocationRule.repair(Arrays.copyOf(elf(0),n)).replacement()==null,"truncation never repaired");
  check(OuyaServiceCleanupRule.repair(Map.of("classes.dex",new byte[]{1})).status==CompatibilityEvidence.Status.MALFORMED,"malformed DEX preserved");
  byte[] duplicate=dex(0,false);check(OuyaServiceCleanupRule.repair(Map.of("classes.dex",duplicate,"classes2.dex",duplicate)).status==CompatibilityEvidence.Status.MALFORMED,"duplicate SDK classes rejected");
  Path dir=Files.createTempDirectory("shared-rules-");try{
   Path in=dir.resolve("input.apk"),out=dir.resolve("out.apk");byte[] payload={4,2};
   ModernApkDoctorTest.apk(in,GeneralCompatibilityTest.manifest(false),Map.of("lib/armeabi-v7a/libUnseenName.so",elf(0),"classes.dex",dex(1,false),"assets/preserve",payload));
   byte[] original=Files.readAllBytes(in);String preview=ModernApkDoctor.inspect(in.toFile(),34,new String[]{"armeabi-v7a"});check(preview.contains(WritableRelocationRule.ID)&&preview.contains(OuyaServiceCleanupRule.ID),"both rule IDs in preview");check(Arrays.equals(original,Files.readAllBytes(in)),"preview preserves original APK");
   ApkRebuilder.rebuild(in.toFile(),out.toFile(),null,false,true);try(ZipFile z=new ZipFile(out.toFile())){
    check(!NativeStartupChecks.hasTextRelocations(z.getInputStream(z.getEntry("lib/armeabi-v7a/libUnseenName.so")).readAllBytes()),"native rule integrated for unseen filename");
    check(unbindCalls(z.getInputStream(z.getEntry("classes.dex")).readAllBytes(),false)==0,"DEX rule integrated without helper");
    check(z.getEntry("classes2.dex")==null&&Arrays.equals(payload,z.getInputStream(z.getEntry("assets/preserve")).readAllBytes()),"no helper injection; assets preserved");
   }
   ApkRebuilder.rebuild(in.toFile(),out.toFile());try(ZipFile z=new ZipFile(out.toFile())){check(Arrays.equals(elf(0),z.getInputStream(z.getEntry("lib/armeabi-v7a/libUnseenName.so")).readAllBytes()),"normal conversion remains isolated");}
   byte[] helper=GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls("Lcom/hcode91/pocketmicro/bridge/TestFixture;","Ljava/lang/Object;",List.of()));
   ApkRebuilder.rebuild(in.toFile(),out.toFile(),helper,true,true);try(ZipFile z=new ZipFile(out.toFile())){check(unbindCalls(z.getInputStream(z.getEntry("classes.dex")).readAllBytes(),false)==0,"subsequent touch analysis preserves cleanup guard");}
  }finally{try(var paths=Files.list(dir)){for(Path p:paths.toArray(Path[]::new))Files.delete(p);}Files.delete(dir);}
  System.out.println("Shared rules: relocated layouts/unseen filenames, null/non-null cleanup, immutable inspection, code-write/unknown/packed/truncated rejection, ZIP preservation and option isolation pass");
 }
}
