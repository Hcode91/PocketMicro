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
                // These fingerprinted proxies refer specifically to this embedded ARMv7 path.
                String asset="assets/libs/armeabi-v7a/"+name;ZipEntry payload=zip.getEntry(asset);
                if(payload==null){lines.add("Known proxy, missing "+asset+"; unchanged: "+path);unsupported++;continue;}
                source=read(zip,payload,budget);
                if(source==null){lines.add("Embedded runtime exceeds inspection limit; unchanged: "+path);unsupported++;continue;}
                hash=ArmRuntimePicRepair.sha(source);
            }
            for(Profile candidate:catalog)if(candidate.name.equals(name)&&(!proxy||installedHash.equals(candidate.proxyHash))&&(hash.equals(candidate.sourceHash)||hash.equals(candidate.outputHash))){profile=candidate;break;}
            if(profile==null){
                lines.add("Unrecognized "+(proxy?"embedded payload":"runtime")+", unchanged: "+path+"\n  SHA-256: "+hash);unsupported++;continue;
            }
            if(!proxy&&hash.equals(profile.outputHash)){lines.add("Already repaired, unchanged: "+path);continue;}
            byte[] fixed=repaired.get(profile.outputHash);
            if(fixed==null){
                fixed=hash.equals(profile.outputHash)?source:profile.repair.apply(source);
                ArmRuntimePicRepair.require(ArmRuntimePicRepair.sha(fixed).equals(profile.outputHash),"Runtime repair output did not match its verified catalog entry: "+path);
                repaired.put(profile.outputHash,fixed);
            }
            plan.replacements.put(path,fixed);lines.add((proxy?"Repaired/promoted embedded payload: ":"Repaired installed runtime: ")+path+" ("+profile.label+")");
        }
        StringBuilder out=new StringBuilder("Native runtime repair — Unity / Marmalade (experimental)\n\n");
        out.append("Runtime files changed: ").append(plan.replacements.size()).append(". Unsupported candidates: ").append(unsupported).append(".\n");
        if(candidates==0)out.append("No cataloged native library paths were found. No native libraries were changed.\n");
        else if(plan.replacements.isEmpty())out.append("No runtime repair was applied. Unrecognized/unsupported libraries remain unchanged.\n");
        for(String line:lines)out.append('\n').append(line).append('\n');
        out.append("\nMatching uses exact library fingerprints, independent of game/package name. Only cataloged ARM32 runtime builds are supported; an engine/version name alone is insufficient. Unknown files are never patched with guessed offsets.\n\nThis repair step changes native runtime files only; assets and unrelated libraries are unchanged. Separately requested touch/DEX changes are reported below. Remaining native warnings still apply: a partial repair may leave another startup blocker, including libjni.so. No purchase changes are included. Matching a fix does not guarantee a successful launch. Vector launch and touch input were confirmed by the owner; other game/build combinations remain unverified. Use normal Play and capture a fresh crash log if it fails.");
        plan.report=out.toString();return plan;
    }
    private static byte[] read(ZipFile zip,ZipEntry entry,long[] budget)throws IOException{
        long size=entry.getSize();if(size<=0||size>12L*1024*1024||size>budget[0])return null;
        budget[0]-=size;byte[] bytes=new byte[(int)size];int count=0;
        try(InputStream in=zip.getInputStream(entry)){while(count<bytes.length){int n=in.read(bytes,count,bytes.length-count);if(n<0)break;count+=n;}ArmRuntimePicRepair.require(count==bytes.length&&in.read()==-1,"Truncated runtime: "+entry.getName());}
        return bytes;
    }
}
