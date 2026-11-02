package io.github.hozdogus.pocketouya;

import java.util.*;
import java.nio.file.*;
import java.util.zip.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;

public final class AirLoaderDiagnosticsTest {
    static final String TYPE="Lcom/adobe/air/AndroidActivityWrapper;",ERROR="Ljava/lang/UnsatisfiedLinkError;";
    static void check(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
    static Instruction invoke(Opcode op,String owner,String name,List<String> params,String returns,int r){return new ImmutableInstruction35c(op,1,r,0,0,0,0,new ImmutableMethodReference(owner,name,params,returns));}
    static byte[] fixture(int layout,String mismatch)throws Exception{
        int temp=layout==0?0:3,self=layout==0?1:6,error=layout==0?0:2;
        var flag=new ImmutableFieldReference(TYPE,"loaded"+layout,"Z");var app=new ImmutableFieldReference(TYPE,"application"+layout,"Landroid/app/Application;");
        List<Instruction> c=new ArrayList<>(List.of(
            new ImmutableInstruction21c(Opcode.SGET_BOOLEAN,temp,flag),new ImmutableInstruction21t(Opcode.IF_NEZ,temp,21),
            new ImmutableInstruction22c(Opcode.IGET_OBJECT,temp,mismatch.equals("receiver")?temp:self,app),
            invoke(Opcode.INVOKE_STATIC,"Lcom/adobe/air/utils/Utils;","GetLibSTLPath",List.of("Landroid/content/Context;"),"Ljava/lang/String;",temp),
            new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,temp),invoke(Opcode.INVOKE_STATIC,"Ljava/lang/System;",mismatch.equals("call")?"loadLibrary":"load",List.of("Ljava/lang/String;"),"V",temp),
            invoke(Opcode.INVOKE_VIRTUAL,TYPE,"GetLibCorePath",List.of(),"Ljava/lang/String;",self),new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,temp),
            invoke(Opcode.INVOKE_STATIC,"Ljava/lang/System;","load",List.of("Ljava/lang/String;"),"V",temp),
            new ImmutableInstruction11n(Opcode.CONST_4,temp,mismatch.equals("flag")?0:1),new ImmutableInstruction21c(Opcode.SPUT_BOOLEAN,temp,flag),
            new ImmutableInstruction10x(Opcode.RETURN_VOID),new ImmutableInstruction11x(Opcode.MOVE_EXCEPTION,error),new ImmutableInstruction10t(Opcode.GOTO,-2)));
        if(mismatch.equals("effects"))c.add(new ImmutableInstruction10x(Opcode.NOP));
        var handlers=List.of(new ImmutableExceptionHandler(mismatch.equals("catch")?"Ljava/lang/Exception;":ERROR,24));
        var tries=List.of(new ImmutableTryBlock(4,19,handlers));
        Method loader=new ImmutableMethod(TYPE,"LoadRuntimeLibraries",List.of(),"V",2,Set.of(),Set.of(),new ImmutableMethodImplementation(self+1,c,tries,List.of()));
        List<Method> methods=new ArrayList<>();methods.add(loader);
        methods.add(new ImmutableMethod(TYPE,"GetLibCorePath",List.of(),"Ljava/lang/String;",1,Set.of(),Set.of(),new ImmutableMethodImplementation(2,List.of(new ImmutableInstruction21c(Opcode.CONST_STRING,0,new ImmutableStringReference("core-path")),new ImmutableInstruction11x(Opcode.RETURN_OBJECT,0)),List.of(),List.of())));
        if(mismatch.equals("collision"))methods.add(new ImmutableMethod(TYPE,"pocketouyaReportAirLoadFailure",List.of(),"V",9,Set.of(),Set.of(),new ImmutableMethodImplementation(0,List.of(new ImmutableInstruction10x(Opcode.RETURN_VOID)),List.of(),List.of())));
        List<Field> fields=List.of(new ImmutableField(TYPE,flag.getName(),"Z",8,null,Set.of(),Set.of()),new ImmutableField(TYPE,app.getName(),app.getType(),2,null,Set.of(),Set.of()));
        return GeneralCompatibilityTest.dex(new ImmutableClassDef(TYPE,1,"Ljava/lang/Object;",List.of(),null,Set.of(),fields,methods),GeneralCompatibilityTest.cls("Lunrelated/Keep;","Ljava/lang/Object;",List.of()));
    }
    static ClassDef owner(byte[] bytes){for(ClassDef c:new DexBackedDexFile(null,bytes).getClasses())if(c.getType().equals(TYPE))return c;throw new AssertionError("missing owner");}
    static Method method(ClassDef c,String name){for(Method m:c.getMethods())if(m.getName().equals(name))return m;throw new AssertionError(name);}
    // Bounded execution of the serialized loader, including exceptional branches.
    // The modeled native loader fails independently on either path; it never executes vendor code.
    static final class State {boolean loaded;int loads,logs;Object originalError,loggedError;String failPath;boolean loggerFails;}
    static void execute(Method m,ClassDef owner,State state){
        Map<Integer,Instruction> code=new HashMap<>();int pc=0;for(Instruction i:m.getImplementation().getInstructions()){code.put(pc,i);pc+=i.getCodeUnits();}
        Object[] regs=new Object[m.getImplementation().getRegisterCount()];regs[regs.length-1]=m.getName().equals("LoadRuntimeLibraries")?"this":state.originalError;Object result=null,pending=null;pc=0;
        for(int budget=0;budget<60;budget++){
            Instruction i=code.get(pc);check(i!=null,"execution must land on an instruction boundary");int next=pc+i.getCodeUnits();
            try{
                switch(i.getOpcode()){
                    case SGET_BOOLEAN:regs[((OneRegisterInstruction)i).getRegisterA()]=state.loaded?1:0;break;
                    case IF_NEZ:if((int)regs[((OneRegisterInstruction)i).getRegisterA()]!=0)next=pc+((OffsetInstruction)i).getCodeOffset();break;
                    case IGET_OBJECT:check("this".equals(regs[((TwoRegisterInstruction)i).getRegisterB()]),"application receiver");regs[((OneRegisterInstruction)i).getRegisterA()]="application";break;
                    case MOVE_RESULT_OBJECT:regs[((OneRegisterInstruction)i).getRegisterA()]=result;break;
                    case CONST_4:regs[((OneRegisterInstruction)i).getRegisterA()]=((NarrowLiteralInstruction)i).getNarrowLiteral();break;
                    case CONST_STRING:regs[((OneRegisterInstruction)i).getRegisterA()]=((StringReference)((ReferenceInstruction)i).getReference()).getString();break;
                    case SPUT_BOOLEAN:state.loaded=(int)regs[((OneRegisterInstruction)i).getRegisterA()]!=0;break;
                    case GOTO:next=pc+((OffsetInstruction)i).getCodeOffset();break;
                    case MOVE_EXCEPTION:check(pending!=null,"handler must receive exception");regs[((OneRegisterInstruction)i).getRegisterA()]=pending;pending=null;break;
                    case RETURN_VOID:return;
                    case INVOKE_STATIC_RANGE:
                        state.originalError=regs[((RegisterRangeInstruction)i).getStartRegister()];execute(method(owner,"pocketouyaReportAirLoadFailure"),owner,state);break;
                    case INVOKE_STATIC:case INVOKE_VIRTUAL:
                        MethodReference ref=(MethodReference)((ReferenceInstruction)i).getReference();var call=(FiveRegisterInstruction)i;
                        switch(ref.getName()){
                            case "GetLibSTLPath":check("application".equals(regs[call.getRegisterC()]),"path context preserved");result="stl-path";break;
                            case "GetLibCorePath":check("this".equals(regs[call.getRegisterC()]),"core receiver preserved");result="core-path";break;
                            case "load":state.loads++;if(Objects.equals(state.failPath,regs[call.getRegisterC()]))throw new UnsatisfiedLinkError("model native load failed: "+state.failPath);break;
                            case "w":check(AirLoaderDiagnosticsRule.TAG.equals(regs[call.getRegisterC()]),"log tag");state.logs++;state.loggedError=regs[call.getRegisterD()];if(state.loggerFails)throw new IllegalStateException("model logger failure");break;
                            default:throw new AssertionError("unexpected call "+ref);
                        }break;
                    default:throw new AssertionError("unexpected opcode "+i.getOpcode());
                }
            }catch(UnsatisfiedLinkError|IllegalStateException failure){
                boolean caught=false;for(var t:m.getImplementation().getTryBlocks())if(pc>=t.getStartCodeAddress()&&pc<t.getStartCodeAddress()+t.getCodeUnitCount())for(var h:t.getExceptionHandlers())if(h.getExceptionType().equals("Ljava/lang/Throwable;")||(failure instanceof UnsatisfiedLinkError&&h.getExceptionType().equals(ERROR))){pending=failure;next=h.getHandlerCodeAddress();caught=true;}
                check(caught,"exception should follow original loader/logging handler");
            }
            pc=next;
        }throw new AssertionError("execution budget exceeded");
    }
    public static void main(String[] args)throws Exception{
        for(int layout:new int[]{0,1}){
            byte[] source=fixture(layout,"");byte[] copy=source.clone();Map<String,byte[]> input=Map.of("classes2.dex",source);
            var scan=AirLoaderDiagnosticsRule.inspect(input);check(scan.status==CompatibilityEvidence.Status.SUPPORTED&&scan.replacements.isEmpty(),"read-only detection");
            var repair=AirLoaderDiagnosticsRule.repair(input);check(repair.status==CompatibilityEvidence.Status.VERIFIED,"diagnostic repair: "+repair.reason);byte[] changed=repair.replacements.get("classes2.dex");
            check(Arrays.equals(source,copy),"original DEX immutable");check(AirLoaderDiagnosticsRule.repair(Map.of("classes2.dex",changed)).replacements.isEmpty(),"idempotence");
            ClassDef before=owner(source),after=owner(changed);
            check(method(before,"GetLibCorePath").getImplementation().getRegisterCount()==method(after,"GetLibCorePath").getImplementation().getRegisterCount(),"unrelated getter preserved");
            for(String fail:new String[]{null,"stl-path","core-path"})for(boolean loaded:new boolean[]{false,true})for(boolean loggerFails:new boolean[]{false,true}){
                State a=new State(),b=new State();a.failPath=b.failPath=fail;a.loaded=b.loaded=loaded;b.loggerFails=loggerFails;
                execute(method(before,"LoadRuntimeLibraries"),before,a);execute(method(after,"LoadRuntimeLibraries"),after,b);
                check(a.loaded==b.loaded&&a.loads==b.loads,"original load order and flag outcomes preserved");
                boolean shouldLog=!loaded&&fail!=null;check(b.logs==(shouldLog?1:0),"only native load failures logged");
                if(shouldLog)check(b.loggedError==b.originalError&&b.loggedError instanceof UnsatisfiedLinkError,"original throwable identity retained");
            }
            State retry=new State();retry.failPath="core-path";execute(method(after,"LoadRuntimeLibraries"),after,retry);check(!retry.loaded&&retry.logs==1,"failed load keeps retry flag clear");
            retry.failPath=null;execute(method(after,"LoadRuntimeLibraries"),after,retry);check(retry.loaded&&retry.loads==4&&retry.logs==1,"retry can load both libraries successfully");
            execute(method(after,"LoadRuntimeLibraries"),after,retry);check(retry.loads==4&&retry.logs==1,"loaded retry remains a no-op");
        }
        for(String near:new String[]{"receiver","call","flag","effects","catch","collision"}){
            var r=AirLoaderDiagnosticsRule.repair(Map.of("classes.dex",fixture(0,near)));check(r.status==CompatibilityEvidence.Status.UNKNOWN&&r.replacements.isEmpty(),"reject near-match "+near);
        }
        check(AirLoaderDiagnosticsRule.repair(Map.of("classes.dex",new byte[12])).status==CompatibilityEvidence.Status.MALFORMED,"truncated DEX");
        check(AirLoaderDiagnosticsRule.repair(Map.of("classes.dex",fixture(0,""),"classes2.dex",fixture(1,""))).status==CompatibilityEvidence.Status.MALFORMED,"duplicate class");
        check(AirLoaderDiagnosticsRule.inspect(Map.of()).status==CompatibilityEvidence.Status.SKIPPED,"absent loader");
        byte[] protectedDex=GeneralCompatibilityTest.dex(GeneralCompatibilityTest.cls("Lother/Protected;","Ljava/lang/Object;",List.of(GeneralCompatibilityTest.method("Lother/Protected;","probe",List.of(),9,false,List.of(new ImmutableInstruction10x(Opcode.RETURN_VOID))))));
        for(ClassDef c:new DexBackedDexFile(null,protectedDex).getClasses())for(Method m:c.getMethods())for(Instruction i:m.getImplementation().getInstructions())protectedDex[((org.jf.dexlib2.dexbacked.instruction.DexBackedInstruction)i).instructionStart]=0x3e;
        var protectedResult=AirLoaderDiagnosticsRule.repair(Map.of("classes.dex",fixture(0,""),"classes2.dex",protectedDex));
        check(protectedResult.status==CompatibilityEvidence.Status.UNSUPPORTED&&protectedResult.replacements.isEmpty(),"reserved code in another DEX prevents all edits");
        var dir=Files.createTempDirectory("air-diagnostics");var apk=dir.resolve("input.apk");byte[] nativeBytes={1,2,3,4},asset={5,6};
        try(var z=new ZipOutputStream(Files.newOutputStream(apk))){for(var e:Map.of("classes.dex",fixture(0,""),"lib/armeabi-v7a/libopaque.so",nativeBytes,"assets/keep",asset).entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}}
        try(var z=new ZipFile(apk.toFile())){
            check(SharedCompatibilityRepairs.plan(z,false).replacements.isEmpty(),"coordinator inspect is read-only");var plan=SharedCompatibilityRepairs.plan(z,true);
            check(plan.replacements.keySet().equals(Set.of("classes.dex"))&&plan.report.contains(AirLoaderDiagnosticsRule.ID),"coordinator only replaces diagnostic DEX");
            check(Arrays.equals(z.getInputStream(z.getEntry("lib/armeabi-v7a/libopaque.so")).readAllBytes(),nativeBytes),"native bytes retained");
        }
        ModernApkDoctorTest.apk(apk,GeneralCompatibilityTest.manifest(false),Map.of("classes.dex",fixture(0,""),"assets/keep",asset));
        var assessment=ApkRepairAssessment.inspect(apk.toFile(),34,new String[]{"armeabi-v7a"});
        check(assessment.suggested==ApkRepairAssessment.Route.STANDARD&&assessment.report.contains(AirLoaderDiagnosticsRule.ID),"diagnostic support alone must not recommend a native repair");
        var converted=dir.resolve("diagnostic.apk");var conversion=ApkRebuilder.rebuild(apk.toFile(),converted.toFile(),null,false,false,true);
        check(conversion.compatibilityReport.contains(AirLoaderDiagnosticsRule.ID),"conversion reports diagnostic scope");
        try(var z=new ZipFile(converted.toFile())){
            check(AirLoaderDiagnosticsRule.inspect(Map.of("classes.dex",z.getInputStream(z.getEntry("classes.dex")).readAllBytes())).status==CompatibilityEvidence.Status.SKIPPED,"converted APK has verified diagnostic hook");
            check(Arrays.equals(z.getInputStream(z.getEntry("assets/keep")).readAllBytes(),asset),"conversion preserves asset bytes");
        }
        List<ClassDef> combined=new ArrayList<>();for(ClassDef c:new DexBackedDexFile(null,fixture(1,"")).getClasses())combined.add(c);
        for(ClassDef c:new DexBackedDexFile(null,SharedCompatibilityRulesTest.dex(0,false)).getClasses())combined.add(c);
        byte[] combinedDex=GeneralCompatibilityTest.dex(combined.toArray(new ClassDef[0]));
        ModernApkDoctorTest.apk(apk,GeneralCompatibilityTest.manifest(false),Map.of("classes.dex",combinedDex));
        try(var z=new ZipFile(apk.toFile())){
            var plan=SharedCompatibilityRepairs.plan(z,true);var output=plan.replacements;
            check(output.size()==1&&AirLoaderDiagnosticsRule.inspect(output).status==CompatibilityEvidence.Status.SKIPPED&&OuyaServiceCleanupRule.inspect(output).status==CompatibilityEvidence.Status.SKIPPED,"AIR diagnostic preserves cleanup edit in the same DEX");
        }
        System.out.println("AIR loader diagnostics: two register layouts, load success/failures/retry flags/logger failure, idempotence, six near-matches, malformed/duplicate inputs and coordinator isolation pass; no ART/phone execution claimed.");
    }
}
