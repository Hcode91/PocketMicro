package io.github.hozdogus.pocketouya;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction3rc;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/** Logs a structurally verified, swallowed AIR load error. Never repairs native bytes. */
public final class AirLoaderDiagnosticsRule {
    public static final String ID="air.loader.report-hidden-error", TAG="PocketOuyaAIR";
    private static final String TYPE="Lcom/adobe/air/AndroidActivityWrapper;";
    private static final String HELPER="pocketouyaReportAirLoadFailure";
    private static final String ERROR="Ljava/lang/UnsatisfiedLinkError;";
    private static final ImmutableMethodReference REPORT=new ImmutableMethodReference(TYPE,HELPER,List.of("Ljava/lang/Throwable;"),"V");
    public static final class Result {
        public final CompatibilityEvidence.Status status;
        public final String reason;
        public final Map<String,byte[]> replacements;
        Result(CompatibilityEvidence.Status status,String reason,Map<String,byte[]> replacements){
            this.status=status;this.reason=reason;this.replacements=Collections.unmodifiableMap(new LinkedHashMap<>(replacements));
        }
    }
    private static Result result(CompatibilityEvidence.Status status,String reason){return new Result(status,reason,Map.of());}
    private static List<Instruction> code(Method m){List<Instruction> out=new ArrayList<>();if(m.getImplementation()!=null)for(Instruction i:m.getImplementation().getInstructions())out.add(i);return out;}
    private static boolean target(Method m){return m.getName().equals("LoadRuntimeLibraries")&&m.getParameterTypes().isEmpty()&&m.getReturnType().equals("V");}
    private static boolean call(Instruction i,Opcode op,String owner,String name,List<String> params,String returns,int...registers){
        if(i.getOpcode()!=op||!(i instanceof ReferenceInstruction)||!(i instanceof FiveRegisterInstruction))return false;
        Object reference=((ReferenceInstruction)i).getReference();if(!(reference instanceof MethodReference))return false;
        MethodReference m=(MethodReference)reference;FiveRegisterInstruction r=(FiveRegisterInstruction)i;
        if(!m.getDefiningClass().equals(owner)||!m.getName().equals(name)||!m.getParameterTypes().equals(params)||!m.getReturnType().equals(returns)||r.getRegisterCount()!=registers.length)return false;
        int[] args={r.getRegisterC(),r.getRegisterD(),r.getRegisterE(),r.getRegisterF(),r.getRegisterG()};for(int n=0;n<registers.length;n++)if(args[n]!=registers[n])return false;return true;
    }
    private static FieldReference field(Instruction i){if(!(i instanceof ReferenceInstruction))return null;Object r=((ReferenceInstruction)i).getReference();return r instanceof FieldReference?(FieldReference)r:null;}
    private static boolean defined(ClassDef owner,FieldReference ref,boolean isStatic){
        if(ref==null||!ref.getDefiningClass().equals(TYPE))return false;
        for(Field f:owner.getFields())if(f.getName().equals(ref.getName())&&f.getType().equals(ref.getType())&&AccessFlags.STATIC.isSet(f.getAccessFlags())==isStatic)return true;return false;
    }
    private static boolean helperValid(Method m){
        if(!m.getName().equals(HELPER)||!m.getParameterTypes().equals(List.of("Ljava/lang/Throwable;"))||!m.getReturnType().equals("V")||m.getAccessFlags()!=0x100a||m.getImplementation()==null||m.getImplementation().getRegisterCount()!=2)return false;
        List<Instruction> c=code(m);if(c.size()!=5||c.get(0).getOpcode()!=Opcode.CONST_STRING||((OneRegisterInstruction)c.get(0)).getRegisterA()!=0||!((StringReference)((ReferenceInstruction)c.get(0)).getReference()).getString().equals(TAG)
            ||!call(c.get(1),Opcode.INVOKE_STATIC,"Landroid/util/Log;","w",List.of("Ljava/lang/String;","Ljava/lang/Throwable;"),"I",0,1)
            ||c.get(2).getOpcode()!=Opcode.RETURN_VOID||c.get(3).getOpcode()!=Opcode.MOVE_EXCEPTION||((OneRegisterInstruction)c.get(3)).getRegisterA()!=0||c.get(4).getOpcode()!=Opcode.RETURN_VOID)return false;
        var tries=m.getImplementation().getTryBlocks();if(tries.size()!=1)return false;var t=tries.get(0);if(t.getStartCodeAddress()!=0||t.getCodeUnitCount()!=5||t.getExceptionHandlers().size()!=1)return false;
        var h=t.getExceptionHandlers().get(0);return "Ljava/lang/Throwable;".equals(h.getExceptionType())&&h.getHandlerCodeAddress()==6;
    }
    // The original method has one guarded pair of loads, one success flag, and a
    // terminal empty catch. Addresses below are derived from instruction widths.
    private static int site(Method m,ClassDef owner,boolean patched){
        if(m.getImplementation()==null||AccessFlags.STATIC.isSet(m.getAccessFlags())||AccessFlags.SYNCHRONIZED.isSet(m.getAccessFlags()))return -1;
        List<Instruction> c=code(m);if(c.size()!=(patched?15:14))return -1;
        Opcode[] ops={Opcode.SGET_BOOLEAN,Opcode.IF_NEZ,Opcode.IGET_OBJECT,Opcode.INVOKE_STATIC,Opcode.MOVE_RESULT_OBJECT,Opcode.INVOKE_STATIC,Opcode.INVOKE_VIRTUAL,Opcode.MOVE_RESULT_OBJECT,Opcode.INVOKE_STATIC,Opcode.CONST_4,Opcode.SPUT_BOOLEAN,Opcode.RETURN_VOID,Opcode.MOVE_EXCEPTION};
        for(int n=0;n<ops.length;n++)if(c.get(n).getOpcode()!=ops[n])return -1;
        int temp=((OneRegisterInstruction)c.get(0)).getRegisterA(),self=m.getImplementation().getRegisterCount()-1,error=((OneRegisterInstruction)c.get(12)).getRegisterA();
        if(temp==self||temp>=self||error>=m.getImplementation().getRegisterCount())return -1;
        FieldReference flag=field(c.get(0)),app=field(c.get(2));
        if(!defined(owner,flag,true)||!flag.getType().equals("Z")||!flag.equals(field(c.get(10)))||!defined(owner,app,false)||!app.getType().equals("Landroid/app/Application;"))return -1;
        if(((OneRegisterInstruction)c.get(1)).getRegisterA()!=temp||((TwoRegisterInstruction)c.get(2)).getRegisterA()!=temp||((TwoRegisterInstruction)c.get(2)).getRegisterB()!=self
            ||!call(c.get(3),Opcode.INVOKE_STATIC,"Lcom/adobe/air/utils/Utils;","GetLibSTLPath",List.of("Landroid/content/Context;"),"Ljava/lang/String;",temp)
            ||!call(c.get(5),Opcode.INVOKE_STATIC,"Ljava/lang/System;","load",List.of("Ljava/lang/String;"),"V",temp)
            ||!call(c.get(6),Opcode.INVOKE_VIRTUAL,TYPE,"GetLibCorePath",List.of(),"Ljava/lang/String;",self)
            ||!call(c.get(8),Opcode.INVOKE_STATIC,"Ljava/lang/System;","load",List.of("Ljava/lang/String;"),"V",temp))return -1;
        for(int n:new int[]{4,7,9,10})if(((OneRegisterInstruction)c.get(n)).getRegisterA()!=temp)return -1;
        if(((NarrowLiteralInstruction)c.get(9)).getNarrowLiteral()!=1)return -1;
        int[] pc=new int[c.size()+1];for(int n=0;n<c.size();n++)pc[n+1]=pc[n]+c.get(n).getCodeUnits();
        int last=c.size()-1;if(c.get(last).getOpcode()!=Opcode.GOTO||pc[last]+((OffsetInstruction)c.get(last)).getCodeOffset()!=pc[11]||pc[1]+((OffsetInstruction)c.get(1)).getCodeOffset()!=pc[11])return -1;
        var tries=m.getImplementation().getTryBlocks();if(tries.size()!=1)return -1;var t=tries.get(0);
        if(t.getStartCodeAddress()!=pc[2]||t.getCodeUnitCount()!=pc[11]-pc[2]||t.getExceptionHandlers().size()!=1)return -1;
        var h=t.getExceptionHandlers().get(0);if(!ERROR.equals(h.getExceptionType())||h.getHandlerCodeAddress()!=pc[12])return -1;
        if(patched){Instruction i=c.get(13);if(i.getOpcode()!=Opcode.INVOKE_STATIC_RANGE||!((ReferenceInstruction)i).getReference().equals(REPORT)||((RegisterRangeInstruction)i).getStartRegister()!=error||((RegisterRangeInstruction)i).getRegisterCount()!=1)return -1;}
        return error;
    }
    private static Method helper(){
        List<Instruction> c=List.of(new ImmutableInstruction21c(Opcode.CONST_STRING,0,new ImmutableStringReference(TAG)),new ImmutableInstruction35c(Opcode.INVOKE_STATIC,2,0,1,0,0,0,new ImmutableMethodReference("Landroid/util/Log;","w",List.of("Ljava/lang/String;","Ljava/lang/Throwable;"),"I")),new ImmutableInstruction10x(Opcode.RETURN_VOID),new ImmutableInstruction11x(Opcode.MOVE_EXCEPTION,0),new ImmutableInstruction10x(Opcode.RETURN_VOID));
        return new ImmutableMethod(TYPE,HELPER,List.of(new ImmutableMethodParameter("Ljava/lang/Throwable;",Set.of(),null)),"V",0x100a,Set.of(),Set.of(),new ImmutableMethodImplementation(2,c,List.of(new ImmutableTryBlock(0,5,List.of(new ImmutableExceptionHandler("Ljava/lang/Throwable;",6)))),List.of()));
    }
    public static Result inspect(Map<String,byte[]> input){return run(input,false);}
    public static Result repair(Map<String,byte[]> input){return run(input,true);}
    private static Result run(Map<String,byte[]> input,boolean apply){
        try{
            Map<String,DexBackedDexFile> files=new LinkedHashMap<>();Set<String> seen=new HashSet<>();ClassDef owner=null;String file=null;long size=0;
            if(input.size()>64)return result(CompatibilityEvidence.Status.SKIPPED,"DEX count budget; original bytes retained");
            for(var e:input.entrySet()){
                size+=e.getValue().length;if(size>64L*1024*1024||e.getValue().length>32*1024*1024)return result(CompatibilityEvidence.Status.SKIPPED,"DEX size budget; original bytes retained");
                var dex=new DexBackedDexFile(null,e.getValue());files.put(e.getKey(),dex);
                for(ClassDef c:dex.getClasses()){
                    if(!seen.add(c.getType()))return result(CompatibilityEvidence.Status.MALFORMED,"Duplicate class; original DEX retained");
                    for(Method m:c.getMethods())if(m.getImplementation()!=null)for(Instruction i:m.getImplementation().getInstructions())if(i instanceof org.jf.dexlib2.iface.instruction.formats.UnknownInstruction)return result(CompatibilityEvidence.Status.UNSUPPORTED,"Reserved instruction; all original DEX retained");
                    if(c.getType().equals(TYPE)){owner=c;file=e.getKey();}
                }
            }
            if(owner==null)return result(CompatibilityEvidence.Status.SKIPPED,"AIR loader absent");
            Method target=null,existing=null;for(Method m:owner.getMethods()){
                if(target(m)){if(target!=null)return result(CompatibilityEvidence.Status.MALFORMED,"Duplicate load method");target=m;}
                if(m.getName().equals(HELPER)){if(existing!=null)return result(CompatibilityEvidence.Status.UNKNOWN,"Diagnostic helper collision");existing=m;}
            }
            if(target==null)return result(CompatibilityEvidence.Status.UNKNOWN,"AIR load method unavailable; no diagnostic edit");
            if(existing!=null)return helperValid(existing)&&site(target,owner,true)>=0?result(CompatibilityEvidence.Status.SKIPPED,"Hidden loader error logging already present"):result(CompatibilityEvidence.Status.UNKNOWN,"Diagnostic helper/layout collision; original DEX retained");
            int error=site(target,owner,false);if(error<0)return result(CompatibilityEvidence.Status.UNKNOWN,"AIR loader/catch/register shape not supported; original DEX retained");
            String proof="Two System.load calls, load-success flag and terminal swallowed UnsatisfiedLinkError handler verified";
            if(!apply)return result(CompatibilityEvidence.Status.SUPPORTED,proof+"; diagnostic logging available, not a native repair");
            MutableMethodImplementation body=new MutableMethodImplementation(target.getImplementation());body.addInstruction(13,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,error,1,REPORT));
            List<Method> methods=new ArrayList<>();for(Method m:owner.getMethods())methods.add(target(m)?new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),m.getAnnotations(),m.getHiddenApiRestrictions(),body):m);methods.add(helper());
            ClassDef changed=new ImmutableClassDef(owner.getType(),owner.getAccessFlags(),owner.getSuperclass(),owner.getInterfaces(),owner.getSourceFile(),owner.getAnnotations(),owner.getFields(),methods);
            DexPool pool=new DexPool(files.get(file).getOpcodes());for(ClassDef c:files.get(file).getClasses())pool.internClass(c.getType().equals(TYPE)?changed:c);MemoryDataStore out=new MemoryDataStore();pool.writeTo(out);byte[] bytes=out.getData();
            Map<String,byte[]> verify=new LinkedHashMap<>(input);verify.put(file,bytes);Result checked=inspect(verify);
            if(checked.status!=CompatibilityEvidence.Status.SKIPPED||!checked.reason.equals("Hidden loader error logging already present"))return result(CompatibilityEvidence.Status.UNKNOWN,"Serialized diagnostic verification failed; original DEX retained");
            return new Result(CompatibilityEvidence.Status.VERIFIED,proof+"; logging hook/handler/helper verified after serialization. Log tag "+TAG+" reports the original loading exception. Original load, success flag and catch continuation retained. Native loading and gameplay remain unresolved.",Map.of(file,bytes));
        }catch(Exception bad){return result(CompatibilityEvidence.Status.MALFORMED,"DEX inspection failed ("+bad.getClass().getSimpleName()+"); original bytes retained");}
    }
    private AirLoaderDiagnosticsRule(){}
}
