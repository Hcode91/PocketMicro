package io.github.hozdogus.pocketouya;
import java.util.*;import org.jf.dexlib2.*;import org.jf.dexlib2.dexbacked.DexBackedDexFile;import org.jf.dexlib2.iface.*;import org.jf.dexlib2.iface.instruction.*;import org.jf.dexlib2.immutable.*;import org.jf.dexlib2.immutable.instruction.*;import org.jf.dexlib2.immutable.reference.*;
public final class UnityCpuStringRuleTest {
 static void check(boolean b){if(!b)throw new AssertionError("CPU string rule regression");}
 static byte[] fixture(int reg,boolean extra)throws Exception{
  String type=UnityCpuStringRule.TYPE,provider="Lexample/CpuSource;";List<Instruction> code=new ArrayList<>();
  code.add(new ImmutableInstruction21c(Opcode.SGET_OBJECT,reg,new ImmutableFieldReference(type,"source",provider)));
  code.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,1,reg,0,0,0,0,new ImmutableMethodReference(provider,"describe",List.of(),"Ljava/lang/String;")));
  code.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,reg));if(extra)code.add(new ImmutableInstruction10x(Opcode.NOP));code.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT,reg));
  Method method=new ImmutableMethod(type,"getCPUType",List.of(),"Ljava/lang/String;",1,Set.of(),Set.of(),new ImmutableMethodImplementation(reg+2,code,List.of(),List.of()));
  return GeneralCompatibilityTest.dex(new ImmutableClassDef(type,1,"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(new ImmutableField(type,"source",provider,9,null,Set.of(),Set.of())),List.of(method)));
 }
 static String execute(byte[] bytes,String providerResult){
  for(ClassDef c:new DexBackedDexFile(null,bytes).getClasses())for(Method m:c.getMethods())if(m.getName().equals("getCPUType")){
   Map<Integer,Instruction> code=new HashMap<>();int at=0;for(Instruction i:m.getImplementation().getInstructions()){code.put(at,i);at+=i.getCodeUnits();}
   String value=null;at=0;for(int budget=0;budget<10;budget++){Instruction i=code.get(at);check(i!=null);switch(i.getOpcode()){
    case SGET_OBJECT:case INVOKE_VIRTUAL:at+=i.getCodeUnits();break;
    case MOVE_RESULT_OBJECT:value=providerResult;at+=i.getCodeUnits();break;
    case IF_NEZ:at+=value!=null?((OffsetInstruction)i).getCodeOffset():i.getCodeUnits();break;
    case CONST_STRING:value=((org.jf.dexlib2.iface.reference.StringReference)((ReferenceInstruction)i).getReference()).getString();at+=i.getCodeUnits();break;
    case RETURN_OBJECT:return value;
    default:throw new AssertionError("Unexpected getter opcode");
   }}
  }throw new AssertionError("Getter missing or did not return");
 }
 public static void main(String[]args)throws Exception{
  for(int reg:new int[]{0,3,9}){
   byte[] src=fixture(reg,false),copy=src.clone();var r=UnityCpuStringRule.repair(Map.of("classes.dex",src));check(r.replacements.size()==1&&Arrays.equals(src,copy));byte[] out=r.replacements.get("classes.dex");
   check(execute(out,null).equals("Unknown CPU"));for(String value:new String[]{"ARMv7 Processor","","CPU Ω"})check(execute(out,value)==value);
   check(UnityCpuStringRule.repair(Map.of("classes.dex",out)).replacements.isEmpty());check(UnityCpuStringRule.repair(Map.of("classes.dex",fixture(reg,true))).replacements.isEmpty());
  }
  check(UnityCpuStringRule.repair(Map.of("classes.dex",new byte[]{1})).replacements.isEmpty());byte[] dup=fixture(0,false);check(UnityCpuStringRule.repair(Map.of("classes.dex",dup,"classes2.dex",dup)).replacements.isEmpty());
  System.out.println("Unity CPU string: null fallback, unchanged non-null/empty/Unicode values, register layouts, idempotence and unsupported/malformed/duplicate rejection pass");
 }
}
