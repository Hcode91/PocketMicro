package io.github.hozdogus.pocketouya;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Game-independent catalog matching. No runtime bytes or offsets are guessed. */
public final class NativeRuntimeRepair {
    interface Repair {byte[] apply(byte[] source)throws IOException;}
    static final class Profile {
        final String name,sourceHash,outputHash,proxyHash,label;
        final Repair repair;
        Profile(String name,String source,String output,String proxy,String label,int init,int target,int thunk,int plt){
            this(name,source,output,proxy,label,bytes->ArmRuntimePicRepair.repairLayout(bytes,init,target,thunk,plt));
        }
        Profile(String name,String source,String output,String proxy,String label,Repair repair){
            this.name=name;sourceHash=source;outputHash=output;proxyHash=proxy;this.label=label;this.repair=repair;
        }
    }
    private static final List<Profile> CATALOG=Arrays.asList(
        new Profile("libmono.so","9816e13ed0ce4d690c9c99ca5187168e08dfacfb4387002becf072153868970c","e888ab00e407ab46d67d6ab32e7573ca0f571fcc9c3fe57d214c336e7e53498e","a280a957e102a2b0e075f339ddff6a648a27af8d8ecfb2a994a43905d38cf15c","Unity 4.1.1f4 / Mono — real runtime text-relocation repair; normal Android loading when promoted",0x15a60,0x3bc2d0,-1,0),
        new Profile("libunity.so","2442a0cffee5f77854895316151573bceabcb2b6278e472aff74b3236d0d3c2a","15b92db63f6869bd1f34f13db7a12a0e89bea9765f87d7f08b114cf490482746","aee89345929061f1e52fe72a7874796a8d3cdba8a663bf370a14cea896b05961","Unity 4.1.1f4 player — real runtime text-relocation repair; bypass legacy embedded loader when promoted",0x64910,0x8bd000,0x64920,0x64028),
        new Profile("libmono.so","a280a957e102a2b0e075f339ddff6a648a27af8d8ecfb2a994a43905d38cf15c","857d1f4e4f107dbcc429dcddb5a3bd6fa2f10fdbb42b953da141d4ac3d8d4e06",null,"Unity Mono loader-only — text-relocation fix; embedded runtime not validated",0x3af8,0x17000,0x3b08,0x3a14),
        new Profile("libunity.so","aee89345929061f1e52fe72a7874796a8d3cdba8a663bf370a14cea896b05961","cdbe592192dc48608ca8d908d4879f120ee33eb0cd2bfa128fd7169baa598ab5",null,"Unity player loader-only — text-relocation fix; embedded runtime not validated",0x1760,0xc000,0x1770,0x167c),
        new Profile("libSpaceShooter.so","92a31b66bf1127993764a29481866c6dfa0b02889f1d850ceca06a74b3f3fe33","f242435f27d97c46cbe4e26d5b6baf2ce41e6ef72bca1985425af3699f77870e",null,"Frima / Space Shooter 1.1.3 — text-relocation initializer fix",0x74210,0x1ed620,-1,0),
        new Profile("libCore.so","6cc57698961c0dab6bf2d32d443a1c07c5f6befcd552dc25738893f77104c22c","b6f2b60be0bd662bf62a1afb4f23deb7d33e715c20fc04b66d322927acd20813",null,"Adobe AIR Core — 17 code relocations / 18 ARM loads repaired; legacy media dependencies remain",bytes->ArmLiteralPicRepair.repair(bytes,17,0,18)),
        new Profile("libysshared.so","b943b7e7631b4ef047f2eeeef6c50ff77dd6756c66f9eb7954a3e846f387b69c","92ee9b5a46bf4bf1092bfc73cc747d8cc102312fdad3631d74bc2812765b96a2",null,"Adobe AIR native extension — relative code literals and symbolic relocation repaired",bytes->ArmLiteralPicRepair.repair(bytes,2,1,3)),
        new Profile("libmono.so","cf6e3b14a342ea3a6d5f064dfb7d8314aa46adb784c5d98e42b1d32a5864029a","f366859ac255785e660ff9af9a46cd146d460d4037496d39128530ffc3eb87b1","a280a957e102a2b0e075f339ddff6a648a27af8d8ecfb2a994a43905d38cf15c","Unity 4.1.5f1 / Mono — text-relocation loading fix",0x15a60,0x3bc2d0,-1,0),
        new Profile("libunity.so","e827ed895c38e3753c132ba1e0b95207c55cc16b8474fb4e26f1d7f5c719170e","aa37e7afd709c19469ed2e7f63f4452a6d6b0275e0801a8c263c14ddcad0268a","aee89345929061f1e52fe72a7874796a8d3cdba8a663bf370a14cea896b05961","Unity 4.1.5f1 runtime — text-relocation loading fix",0x64720,0x8bd000,0x64730,0x63e38),
        new Profile("libvector.so","cf4cd687b0c3c8e1d87c995a96c21c15cdb0c598d59ff35faea620de524c57fe","d0c1dfc8fc4c5cd447b7e1aef90c74eaa7cac3b06c7b74bb0f9684d6ffbfd58e",null,"Marmalade / Vector 1.0.7 — text-relocation loading + null JNI surface-buffer fix",MarmaladeRuntimeRepair::repair),
        new Profile("libvector.so","3aa7134392604f6f2f0877729550862e19f44c5c486743d97cf45a64de40f18b","d0c1dfc8fc4c5cd447b7e1aef90c74eaa7cac3b06c7b74bb0f9684d6ffbfd58e",null,"Marmalade / Vector 1.0.7 — add null JNI surface-buffer fix to prior loading repair",MarmaladeRuntimeRepair::upgrade),
        new Profile("libs3edialog_ext.so","74f31fd2bec42cb0ccdf4a910d366eb98e27460ef6cb753a8184a21c96d2a22a","f100ed0c715e1592d4cacb2475c6e1e9b04d8e8578c409c671c22604ed7716b3",null,"Marmalade dialog — text-relocation loading fix",0x5c4,0x1d40,-1,0),
        new Profile("libs3ewebview_ext.so","75070fd39ffca484ee68a55916656d0f8a966e0f05cea2dc80d2e52e5e3c8ec8","33c18ceddc44b8c8ec02d309a36ebab70e07e7304f4692b75fbce7526d276634",null,"Marmalade webview — text-relocation loading fix",0x764,0x2d70,-1,0),
        new Profile("libs3eandroidmarketbilling_ext.so","7e83e8e141245455d6f39c6c751a58323ffb01b4a5427c5c7c6f79537663d367","b4ac21ffaeb1845d434f0b8cf5130d68606e9d67edec3bcb5cdca9dd70a99f43",null,"Marmalade billing — text-relocation loading fix (purchase behavior unchanged)",0x4a94,0x11e50,-1,0)
    );
    public static final class Plan {
        public final Map<String,byte[]> replacements=new LinkedHashMap<>();
        public String report;
    }
    private NativeRuntimeRepair(){}
    public static Plan plan(ZipFile zip)throws IOException{return plan(zip,CATALOG);}
    static Plan plan(ZipFile zip,List<Profile> catalog)throws IOException {
        Plan plan=new Plan();List<String> lines=new ArrayList<>();Map<String,byte[]> repaired=new HashMap<>();
        long[] budget={64L*1024*1024};int candidates=0,unsupported=0;
        Set<String> names=new LinkedHashSet<>();for(Profile profile:catalog)names.add(profile.name);
        for(String abi:new String[]{"armeabi-v7a","armeabi","arm64-v8a","x86","x86_64"})for(String name:names){
            String path="lib/"+abi+"/"+name;ZipEntry entry=zip.getEntry(path);if(entry==null)continue;candidates++;
            if(!abi.equals("armeabi-v7a")&&!abi.equals("armeabi")){lines.add("Unsupported ABI, unchanged: "+path);unsupported++;continue;}
            byte[] installed=read(zip,entry,budget);
            if(installed==null){lines.add("Inspection size limit; unchanged: "+path);unsupported++;continue;}
            String installedHash=ArmRuntimePicRepair.sha(installed),hash=installedHash;
            byte[] source=installed;boolean proxy=false;Profile profile=null;
            for(Profile candidate:catalog)if(candidate.name.equals(name)&&installedHash.equals(candidate.proxyHash))proxy=true;
            if(proxy){
                // Prefer a recognized real runtime. If it is unavailable or not
                // cataloged, independently repair only an exact known loader.
                String asset="assets/libs/armeabi-v7a/"+name;ZipEntry payload=zip.getEntry(asset);String reason;
                if(payload==null)reason="Known Unity loader proxy, missing embedded runtime "+asset;
                else {
                    source=read(zip,payload,budget);
                    if(source==null)reason="Embedded runtime exceeds inspection limit: "+asset;
                    else {hash=ArmRuntimePicRepair.sha(source);reason="Unrecognized embedded payload "+asset+"\n  SHA-256: "+hash;
                        if(NativeStartupChecks.hasTextRelocations(source))reason+="\nThe embedded runtime itself still declares text relocations and needs its own repair.";
                        for(Profile candidate:catalog)if(candidate.name.equals(name)&&installedHash.equals(candidate.proxyHash)&&(hash.equals(candidate.sourceHash)||hash.equals(candidate.outputHash))){profile=candidate;break;}
                    }
                }
                if(profile==null){
                    Profile loader=null;for(Profile candidate:catalog)if(candidate.name.equals(name)&&candidate.proxyHash==null&&installedHash.equals(candidate.sourceHash)){loader=candidate;break;}
                    if(loader==null){lines.add(reason+"; unchanged: "+path);unsupported++;continue;}
                    lines.add(reason+"\nLoader-only repair selected for "+path+". Embedded assets are unchanged and not repaired/validated; startup may still fail there.");unsupported++;
                    profile=loader;source=installed;hash=installedHash;proxy=false;
                }
            }else for(Profile candidate:catalog)if(candidate.name.equals(name)&&(hash.equals(candidate.sourceHash)||hash.equals(candidate.outputHash))){profile=candidate;break;}
            if(profile==null){
                lines.add("Unrecognized "+(proxy?"embedded payload":"runtime")+", unchanged: "+path+"\n  SHA-256: "+hash);unsupported++;continue;
            }
            if(!proxy&&hash.equals(profile.outputHash)){lines.add("Already repaired, unchanged: "+path+" ("+profile.label+")");continue;}
            byte[] fixed=repaired.get(profile.outputHash);
            if(fixed==null){
                fixed=hash.equals(profile.outputHash)?source:profile.repair.apply(source);
                ArmRuntimePicRepair.require(ArmRuntimePicRepair.sha(fixed).equals(profile.outputHash),"Runtime repair output did not match its verified catalog entry: "+path);
                repaired.put(profile.outputHash,fixed);
            }
            plan.replacements.put(path,fixed);lines.add((proxy?"Repaired/promoted embedded payload: ":"Repaired installed runtime: ")+path+" ("+profile.label+")");
        }
        StringBuilder out=new StringBuilder("Native runtime repair — Unity / Marmalade / AIR / Frima (experimental)\n\n");
        out.append("Runtime files changed: ").append(plan.replacements.size()).append(". Unsupported candidates: ").append(unsupported).append(".\n");
        if(candidates==0)out.append("No cataloged native library paths were found. No native libraries were changed.\n");
        else if(plan.replacements.isEmpty())out.append("No runtime repair was applied. Unrecognized/unsupported libraries remain unchanged.\n");
        for(String line:lines)out.append('\n').append(line).append('\n');
        out.append("\nMatching uses exact library fingerprints, independent of game/package name. Only cataloged ARM32 runtime builds are supported; an engine/version name alone is insufficient. Unknown files are never patched with guessed offsets.\n\nThis repair step changes native runtime files only; assets and unrelated libraries are unchanged. Separately requested touch/offline purchase changes are reported below. Remaining native warnings still apply: a partial repair may leave another startup blocker, including libjni.so. Adobe AIR may still fail on obsolete private Android media dependencies after code relocation repair. This native repair step does not change purchases; any selected offline SDK compatibility has its own report below. Matching a fix does not guarantee a successful launch. Vector launch/touch and Abbigale launch were confirmed by the owner for the inspected builds; other game/build combinations remain unverified. Use normal Play and capture a fresh crash log if it fails.");
        plan.report=out.toString();return plan;
    }
    private static byte[] read(ZipFile zip,ZipEntry entry,long[] budget)throws IOException{
        long size=entry.getSize();if(size<=0||size>24L*1024*1024||size>budget[0])return null;
        budget[0]-=size;byte[] bytes=new byte[(int)size];int count=0;
        try(InputStream in=zip.getInputStream(entry)){while(count<bytes.length){int n=in.read(bytes,count,bytes.length-count);if(n<0)break;count+=n;}ArmRuntimePicRepair.require(count==bytes.length&&in.read()==-1,"Truncated runtime: "+entry.getName());}
        return bytes;
    }
}
