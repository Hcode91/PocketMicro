package io.github.hozdogus.pocketouya;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.*;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21t;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/** SDK-level cleanup rule shared by APKs; no package IDs, purchase edits or injected helper. */
public final class OuyaServiceCleanupRule {
    public static final String ID="ouya.content.null-context-cleanup";
    private static final String TYPE="Ltv/ouya/console/api/content/OuyaContent;";
    public static final class Result {
        public final CompatibilityEvidence.Status status;public final String reason;
        public final Map<String,byte[]> replacements;
        Result(CompatibilityEvidence.Status status,String reason,Map<String,byte[]> replacements){this.status=status;this.reason=reason;this.replacements=Collections.unmodifiableMap(replacements);}
    }
    private static boolean shutdown(Method m){return m.getName().equals("shutdown")&&m.getParameterTypes().isEmpty()&&m.getReturnType().equals("V");}
    private static boolean unbind(Instruction i){
        if(i.getOpcode()!=Opcode.INVOKE_VIRTUAL||!(i instanceof FiveRegisterInstruction)||!(i instanceof ReferenceInstruction))return false;
        Object ref=((ReferenceInstruction)i).getReference();if(!(ref instanceof MethodReference))return false;MethodReference m=(MethodReference)ref;
        return m.getDefiningClass().equals("Landroid/content/Context;")&&m.getName().equals("unbindService")&&m.getParameterTypes().equals(Collections.singletonList("Landroid/content/ServiceConnection;"))&&m.getReturnType().equals("V")&&((FiveRegisterInstruction)i).getRegisterCount()==2;
    }
    // Straight-line cleanup: this instance's Context and a connection field or typed self receiver.
    private static int site(Method m,ClassDef owner){
        if(m.getImplementation()==null||AccessFlags.STATIC.isSet(m.getAccessFlags())||!m.getImplementation().getTryBlocks().isEmpty())return -1;
        List<Instruction> code=new ArrayList<>();for(Instruction i:m.getImplementation().getInstructions())code.add(i);
        Instruction guard=null;
        int beforeCall=code.size()-3;
        if(beforeCall>=1&&beforeCall<=2&&code.get(beforeCall).getOpcode()==Opcode.IF_EQZ&&((OffsetInstruction)code.get(beforeCall)).getCodeOffset()==5){guard=code.remove(beforeCall);}
        int loads=code.size()-2;
        if(loads<1||loads>2||!unbind(code.get(loads))||code.get(loads+1).getOpcode()!=Opcode.RETURN_VOID)return -1;
        FiveRegisterInstruction call=(FiveRegisterInstruction)code.get(loads);int self=m.getImplementation().getRegisterCount()-1;
        if(loads==1&&(call.getRegisterD()!=self||call.getRegisterC()==self||!owner.getInterfaces().contains("Landroid/content/ServiceConnection;")))return -1;
        if(loads==2&&call.getRegisterD()==self)return -1;
        Set<Integer> registers=new HashSet<>();
        for(int n=0;n<loads;n++){
            Instruction i=code.get(n);if(i.getOpcode()!=Opcode.IGET_OBJECT||!(i instanceof TwoRegisterInstruction)||!(i instanceof ReferenceInstruction))return -1;
            TwoRegisterInstruction r=(TwoRegisterInstruction)i;Object ref=((ReferenceInstruction)i).getReference();if(!(ref instanceof FieldReference))return -1;FieldReference f=(FieldReference)ref;
            if(r.getRegisterB()!=self||r.getRegisterA()==self||!registers.add(r.getRegisterA())||!f.getDefiningClass().equals(TYPE))return -1;
            if(r.getRegisterA()==call.getRegisterC()){if(!f.getType().equals("Landroid/content/Context;"))return -1;}
            else if(r.getRegisterA()==call.getRegisterD()){if(!f.getType().equals("Landroid/content/ServiceConnection;"))return -1;}
            else return -1;
        }
        if(guard!=null)return ((OneRegisterInstruction)guard).getRegisterA()==call.getRegisterC()?-2:-1;
        return call.getRegisterC();
    }
    public static Result inspect(Map<String,byte[]> input){return run(input,false);}
    public static Result repair(Map<String,byte[]> input){return run(input,true);}
    private static Result result(CompatibilityEvidence.Status status,String reason){return new Result(status,reason,Collections.emptyMap());}
    private static Result run(Map<String,byte[]> input,boolean apply){
        try{
            Map<String,DexBackedDexFile> files=new LinkedHashMap<>();Set<String> types=new HashSet<>();ClassDef owner=null;Method target=null;String file=null;
            long total=0;for(Map.Entry<String,byte[]> entry:input.entrySet()){
                total+=entry.getValue().length;if(total>64L*1024*1024||input.size()>64)return result(CompatibilityEvidence.Status.SKIPPED,"DEX analysis budget exceeded");
                DexBackedDexFile dex=new DexBackedDexFile(null,entry.getValue());files.put(entry.getKey(),dex);
                for(ClassDef cls:dex.getClasses()){
                    if(!types.add(cls.getType()))return result(CompatibilityEvidence.Status.MALFORMED,"Duplicate DEX class; original DEX preserved");
                    for(Method m:cls.getMethods())if(m.getImplementation()!=null)for(Instruction i:m.getImplementation().getInstructions())
                        if(i instanceof org.jf.dexlib2.iface.instruction.formats.UnknownInstruction)return result(CompatibilityEvidence.Status.UNSUPPORTED,"Reserved DEX instruction; all original DEX preserved");
                    if(cls.getType().equals(TYPE)){owner=cls;file=entry.getKey();for(Method m:cls.getMethods())if(shutdown(m)){if(target!=null)return result(CompatibilityEvidence.Status.MALFORMED,"Duplicate shutdown method");target=m;}}
                }
            }
            if(owner==null)return result(CompatibilityEvidence.Status.SKIPPED,"OUYA content SDK absent");
            if(target==null)return result(CompatibilityEvidence.Status.UNSUPPORTED,"OUYA content shutdown API absent");
            int context=site(target,owner);
            if(context==-2)return result(CompatibilityEvidence.Status.SKIPPED,"Supported cleanup already has the verified null-context guard");
            if(context<0)return result(CompatibilityEvidence.Status.UNKNOWN,"Shutdown differs from the supported straight-line field-load/unbind/return contract; unchanged");
            for(Instruction i:target.getImplementation().getInstructions())if(i.getOpcode()==Opcode.IGET_OBJECT){
                FieldReference ref=(FieldReference)((ReferenceInstruction)i).getReference();boolean declared=false;
                for(Field field:owner.getFields())if(field.getName().equals(ref.getName())&&field.getType().equals(ref.getType())&&!AccessFlags.STATIC.isSet(field.getAccessFlags()))declared=true;
                if(!declared)return result(CompatibilityEvidence.Status.UNKNOWN,"Context/connection field definition missing or static; unchanged");
            }
            String proof="SDK instance Context field and a verified connection field or ServiceConnection receiver feed the single unbindService call; no branches/handlers or other effects";
            if(!apply)return result(CompatibilityEvidence.Status.SUPPORTED,proof);
            MutableMethodImplementation body=new MutableMethodImplementation(target.getImplementation());
            int callIndex=body.getInstructions().size()-2;
            Label done=body.newLabelForIndex(callIndex+1);body.addInstruction(callIndex,new BuilderInstruction21t(Opcode.IF_EQZ,context,done));
            List<Method> methods=new ArrayList<>();for(Method m:owner.getMethods())methods.add(shutdown(m)?new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),m.getAnnotations(),m.getHiddenApiRestrictions(),body):m);
            ClassDef changed=new ImmutableClassDef(owner.getType(),owner.getAccessFlags(),owner.getSuperclass(),owner.getInterfaces(),owner.getSourceFile(),owner.getAnnotations(),owner.getFields(),methods);
            DexPool pool=new DexPool(files.get(file).getOpcodes());for(ClassDef c:files.get(file).getClasses())pool.internClass(c.getType().equals(TYPE)?changed:c);
            MemoryDataStore output=new MemoryDataStore();pool.writeTo(output);byte[] bytes=output.getData();
            boolean verified=false;for(ClassDef c:new DexBackedDexFile(null,bytes).getClasses())if(c.getType().equals(TYPE))for(Method m:c.getMethods())if(shutdown(m)){
                List<Instruction> code=new ArrayList<>();for(Instruction i:m.getImplementation().getInstructions())code.add(i);
                verified=site(m,c)==-2&&code.size()==callIndex+3;
            }
            if(!verified)return result(CompatibilityEvidence.Status.UNKNOWN,"Serialized guard verification failed; original DEX preserved");
            Map<String,byte[]> replacements=new LinkedHashMap<>();replacements.put(file,bytes);
            return new Result(CompatibilityEvidence.Status.VERIFIED,proof+"; serialized null guard verified. Only null-context cleanup is skipped; unavailable services, failed binding and gameplay remain unresolved",replacements);
        }catch(Exception bad){return result(CompatibilityEvidence.Status.MALFORMED,"DEX inspection failed ("+bad.getClass().getSimpleName()+"); all original DEX preserved");}
    }
    private OuyaServiceCleanupRule(){}
}
