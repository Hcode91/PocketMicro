package io.github.hozdogus.pocketouya;
import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/** Shared legacy Unity JNI string contract. No processor capabilities or ownership are invented. */
public final class UnityCpuStringRule {
 public static final String ID="unity.cpu-description.non-null",TYPE="Lcom/unity3d/player/UnityPlayer;";
 public static final class Result {
  public final Map<String,byte[]> replacements=new LinkedHashMap<>();
  public String report;
 }
 private static boolean target(Method m){return m.getName().equals("getCPUType")&&m.getParameterTypes().isEmpty()&&m.getReturnType().equals("Ljava/lang/String;");}
 public static Result repair(Map<String,byte[]> input){
  Result result=new Result();result.report="SKIPPED "+ID+": getter absent";
  try{
   Map<String,DexBackedDexFile> dex=new LinkedHashMap<>();Set<String> seen=new HashSet<>();ClassDef owner=null;Method method=null;String file=null;long total=0;
   for(var e:input.entrySet()){
    total+=e.getValue().length;if(total>64L*1024*1024||input.size()>64)throw new IllegalArgumentException("DEX budget exceeded");
    var d=new DexBackedDexFile(null,e.getValue());dex.put(e.getKey(),d);
    for(ClassDef c:d.getClasses()){
     if(!seen.add(c.getType()))throw new IllegalArgumentException("duplicate class");
     for(Method m:c.getMethods()){
      if(m.getImplementation()!=null)for(Instruction i:m.getImplementation().getInstructions())if(i instanceof org.jf.dexlib2.iface.instruction.formats.UnknownInstruction)throw new IllegalArgumentException("reserved instruction");
      if(c.getType().equals(TYPE)&&target(m)){if(method!=null)throw new IllegalArgumentException("duplicate getter");owner=c;method=m;file=e.getKey();}
     }
    }
   }
   if(method==null)return result;
   if(method.getImplementation()==null||AccessFlags.STATIC.isSet(method.getAccessFlags())||!method.getImplementation().getTryBlocks().isEmpty())throw new IllegalArgumentException("unsupported getter implementation");
   List<Instruction> code=new ArrayList<>();for(Instruction i:method.getImplementation().getInstructions())code.add(i);
   if(code.size()==6&&code.get(3).getOpcode()==Opcode.IF_NEZ&&code.get(4).getOpcode()==Opcode.CONST_STRING&&((ReferenceInstruction)code.get(4)).getReference().equals(new ImmutableStringReference("Unknown CPU"))){result.report="SKIPPED "+ID+": existing guard";return result;}
   if(code.size()!=4||code.get(0).getOpcode()!=Opcode.SGET_OBJECT||code.get(1).getOpcode()!=Opcode.INVOKE_VIRTUAL||code.get(2).getOpcode()!=Opcode.MOVE_RESULT_OBJECT||code.get(3).getOpcode()!=Opcode.RETURN_OBJECT)throw new IllegalArgumentException("unsupported getter shape");
   int receiver=((OneRegisterInstruction)code.get(0)).getRegisterA(),reg=((OneRegisterInstruction)code.get(2)).getRegisterA();
   FieldReference field=(FieldReference)((ReferenceInstruction)code.get(0)).getReference();MethodReference call=(MethodReference)((ReferenceInstruction)code.get(1)).getReference();FiveRegisterInstruction invoke=(FiveRegisterInstruction)code.get(1);
   if(!field.getDefiningClass().equals(TYPE)||!field.getType().equals(call.getDefiningClass())||!call.getParameterTypes().isEmpty()||!call.getReturnType().equals("Ljava/lang/String;")||invoke.getRegisterCount()!=1||invoke.getRegisterC()!=receiver||((OneRegisterInstruction)code.get(3)).getRegisterA()!=reg)throw new IllegalArgumentException("getter register/type contract differs");
   List<Instruction> guarded=new ArrayList<>(code.subList(0,3));guarded.add(new ImmutableInstruction21t(Opcode.IF_NEZ,reg,4));guarded.add(new ImmutableInstruction21c(Opcode.CONST_STRING,reg,new ImmutableStringReference("Unknown CPU")));guarded.add(code.get(3));
   List<Method> methods=new ArrayList<>();for(Method m:owner.getMethods())methods.add(target(m)?new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),m.getAnnotations(),m.getHiddenApiRestrictions(),new ImmutableMethodImplementation(m.getImplementation().getRegisterCount(),guarded,Collections.emptyList(),Collections.emptyList())):m);
   var changed=new ImmutableClassDef(owner.getType(),owner.getAccessFlags(),owner.getSuperclass(),owner.getInterfaces(),owner.getSourceFile(),owner.getAnnotations(),owner.getFields(),methods);
   var pool=new DexPool(dex.get(file).getOpcodes());for(ClassDef c:dex.get(file).getClasses())pool.internClass(c.getType().equals(TYPE)?changed:c);MemoryDataStore out=new MemoryDataStore();pool.writeTo(out);byte[] bytes=out.getData();
   boolean verified=false;for(ClassDef c:new DexBackedDexFile(null,bytes).getClasses())if(c.getType().equals(TYPE))for(Method m:c.getMethods())if(target(m)){
    List<Instruction> check=new ArrayList<>();for(Instruction i:m.getImplementation().getInstructions())check.add(i);
    verified=check.size()==6&&check.get(3).getOpcode()==Opcode.IF_NEZ&&((OffsetInstruction)check.get(3)).getCodeOffset()==4&&((OneRegisterInstruction)check.get(3)).getRegisterA()==reg&&check.get(4).getOpcode()==Opcode.CONST_STRING&&((OneRegisterInstruction)check.get(4)).getRegisterA()==reg&&((StringReference)((ReferenceInstruction)check.get(4)).getReference()).getString().equals("Unknown CPU")&&check.get(5).getOpcode()==Opcode.RETURN_OBJECT;
   }
   if(!verified)throw new IllegalArgumentException("serialized guard verification failed");
   result.replacements.put(file,bytes);result.report="VERIFIED "+ID+": original getter retained; null result becomes Unknown CPU. Non-null descriptions unchanged. This addresses the supported null JNI string path; phone startup remains unverified.";
  }catch(Exception error){result.replacements.clear();result.report="UNSUPPORTED "+ID+": "+error.getClass().getSimpleName()+" ("+error.getMessage()+"); original DEX preserved";}
  return result;
 }
 private UnityCpuStringRule(){}
}
