package io.github.hozdogus.pocketouya;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Normalizes host NDK stub paths to public Android sonames. No code/string edits. */
public final class NdkDependencyPathRule {
    public static final String ID="elf.arm32.ndk-dependency-path";
    private static final Set<String> PUBLIC=new HashSet<>(Arrays.asList("libc.so","libdl.so","libm.so","liblog.so","libz.so","libandroid.so","libEGL.so","libGLESv1_CM.so","libGLESv2.so","libOpenSLES.so","libjnigraphics.so"));
    private static final Pattern HOST=Pattern.compile("(?:[A-Za-z]:[/\\\\]|/)(?:[^/\\\\]+[/\\\\])*android-ndk[^/\\\\]*[/\\\\]platforms[/\\\\]android-[0-9]+[/\\\\]arch-arm[/\\\\]usr[/\\\\]lib[/\\\\]([^/\\\\]+)");
    public static final class Result {
        public final CompatibilityEvidence.Status status;public final String reason;private final byte[] replacement;
        Result(CompatibilityEvidence.Status status,String reason,byte[] replacement){this.status=status;this.reason=reason;this.replacement=replacement;}
        public byte[] replacement(){return replacement==null?null:replacement.clone();}
    }
    private static final class Stop extends RuntimeException {
        final CompatibilityEvidence.Status status;Stop(CompatibilityEvidence.Status status,String reason){super(reason);this.status=status;}
    }
    private static void need(boolean yes,CompatibilityEvidence.Status status,String reason){if(!yes)throw new Stop(status,reason);}
    private static final class Elf {
        final byte[] bytes;final ByteBuffer b;final List<long[]> loads=new ArrayList<>();final Map<Long,Long> tags=new HashMap<>();final List<Integer> needed=new ArrayList<>();int dynamic,size;long dynamicAddress;
        Elf(byte[] bytes){
            this.bytes=bytes;b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);range(0,52);
            need(u(0)==0x464c457fL,CompatibilityEvidence.Status.MALFORMED,"ELF magic missing");
            need(bytes[4]==1&&bytes[5]==1&&bytes[6]==1&&(b.getShort(16)&65535)==3&&(b.getShort(18)&65535)==40,CompatibilityEvidence.Status.UNSUPPORTED,"Only little-endian ARM32 ET_DYN supported");
            need(u(20)==1&&(b.getShort(40)&65535)==52,CompatibilityEvidence.Status.MALFORMED,"Invalid ELF header");
            long ph=u(28);int stride=b.getShort(42)&65535,count=b.getShort(44)&65535;
            need(stride==32&&count>0&&count<=256,CompatibilityEvidence.Status.UNKNOWN,"Unsupported program headers");range(ph,(long)stride*count);long dynVa=-1;
            for(int n=0;n<count;n++){
                int p=(int)ph+n*stride;long type=u(p),off=u(p+4),va=u(p+8),file=u(p+16),mem=u(p+20),flags=u(p+24);
                if(type!=1&&type!=2)continue;range(off,file);need(file<=mem&&va+mem<=0x100000000L,CompatibilityEvidence.Status.MALFORMED,"Invalid load extent");
                if(type==1){long align=u(p+28);need(align<=1||((align&(align-1))==0&&off%align==va%align),CompatibilityEvidence.Status.MALFORMED,"Invalid load alignment");need((flags&3)!=3,CompatibilityEvidence.Status.UNSUPPORTED,"Writable executable mapping");
                    for(long[] old:loads){need(va+mem<=old[1]||old[1]+old[3]<=va,CompatibilityEvidence.Status.UNKNOWN,"Overlapping loads");need(off+file<=old[0]||old[0]+old[2]<=off,CompatibilityEvidence.Status.UNKNOWN,"Aliased load bytes");}loads.add(new long[]{off,va,file,mem,flags});}
                else {need(dynVa<0,CompatibilityEvidence.Status.UNKNOWN,"Multiple dynamic tables");dynamic=(int)off;size=(int)file;dynVa=va;}
            }
            need(dynVa>=0&&size>=8&&size<=65536&&size%8==0&&dynamic%4==0&&dynVa%4==0,CompatibilityEvidence.Status.UNKNOWN,"Missing or unsupported dynamic table");need(offset(dynVa,size)==dynamic,CompatibilityEvidence.Status.MALFORMED,"Dynamic mapping disagreement");
            need(dynamic>=52&&(dynamic+size<=ph||ph+(long)stride*count<=dynamic),CompatibilityEvidence.Status.MALFORMED,"Dynamic/header overlap");
            dynamicAddress=dynVa;boolean mapped=false;for(long[] l:loads)if(dynVaIn(l,dynVa,size))mapped=true;need(mapped,CompatibilityEvidence.Status.UNKNOWN,"Dynamic table is not wholly writable mapped metadata");
            boolean end=false;for(int at=dynamic;at<dynamic+size;at+=8){long tag=u(at),value=u(at+4);if(tag==0){end=true;break;}if(tag==1)needed.add(at);else need(tags.put(tag,value)==null,CompatibilityEvidence.Status.UNKNOWN,"Duplicate dynamic descriptor");}
            need(end,CompatibilityEvidence.Status.MALFORMED,"Unterminated dynamic table");
            need(needed.size()<=256,CompatibilityEvidence.Status.SKIPPED,"Dependency count budget");
        }
        static boolean dynVaIn(long[] load,long va,int size){return va>=load[1]&&va+size<=load[1]+load[3]&&(load[4]&2)!=0;}
        void range(long at,long size){need(at>=0&&size>=0&&at<=bytes.length&&size<=bytes.length-at,CompatibilityEvidence.Status.MALFORMED,"Truncated ELF range");}
        long u(int at){range(at,4);return b.getInt(at)&0xffffffffL;}
        int offset(long va,int size){for(long[] l:loads)if(va>=l[1]&&va-l[1]<=l[2]&&size<=l[2]-(va-l[1]))return (int)(l[0]+va-l[1]);throw new Stop(CompatibilityEvidence.Status.MALFORMED,"Metadata not file backed");}
        void relocationEvidence(){
            for(long tag:new long[]{7,8,9,35,36,37,0x6000000fL,0x60000010L,0x60000011L,0x60000012L,0x6fffe000L,0x6fffe001L,0x6fffe003L})need(!tags.containsKey(tag),CompatibilityEvidence.Status.UNKNOWN,"Unsupported relocation descriptors; dependency edits withheld");
            if(tags.containsKey(17L)||tags.containsKey(18L)){need(tags.containsKey(17L)&&tags.containsKey(18L)&&Objects.equals(tags.get(19L),8L),CompatibilityEvidence.Status.UNKNOWN,"Incomplete REL descriptors");scan(tags.get(17L),tags.get(18L));}
            if(tags.containsKey(23L)||tags.containsKey(2L)||tags.containsKey(20L)){need(tags.containsKey(23L)&&tags.containsKey(2L)&&Objects.equals(tags.get(20L),17L),CompatibilityEvidence.Status.UNKNOWN,"Incomplete/non-REL PLT descriptors");scan(tags.get(23L),tags.get(2L));}
        }
        void scan(long va,long size){
            need(size%8==0&&size<=8L*262144,CompatibilityEvidence.Status.MALFORMED,"Relocation table budget/layout");int start=offset(va,(int)size);
            need(start+size<=dynamic||start>=dynamic+this.size,CompatibilityEvidence.Status.UNKNOWN,"Relocation table aliases dynamic metadata");
            for(int at=start;at<start+size;at+=8){long info=u(at+4),target=u(at);int type=(int)(info&255);if(type==0)continue;
                need(type==2||type==3||type==21||type==22||type==23,CompatibilityEvidence.Status.UNKNOWN,"Unsupported relocation kind; dependency edits withheld");
                boolean mapped=false;for(long[] l:loads)if(target>=l[1]&&target+4<=l[1]+l[3])mapped=true;need(mapped,CompatibilityEvidence.Status.MALFORMED,"Unmapped relocation target");
                need(target+4<=dynamicAddress||target>=dynamicAddress+this.size,CompatibilityEvidence.Status.UNSUPPORTED,"Relocation overwrites dynamic metadata");
            }
        }
    }
    public static Result inspect(byte[] bytes){return run(bytes,false);}
    public static Result repair(byte[] bytes){return run(bytes,true);}
    private static Result run(byte[] bytes,boolean apply){
        try{
            need(bytes!=null,CompatibilityEvidence.Status.MALFORMED,"Missing ELF");need(bytes.length<=24*1024*1024,CompatibilityEvidence.Status.SKIPPED,"ELF byte budget");Elf e=new Elf(bytes);
            if(e.needed.isEmpty())return new Result(CompatibilityEvidence.Status.SKIPPED,"No DT_NEEDED dependencies",null);
            need(e.tags.containsKey(5L)&&e.tags.containsKey(10L),CompatibilityEvidence.Status.UNKNOWN,"Missing dynamic string table");long strVa=e.tags.get(5L),strSize=e.tags.get(10L);
            need(strSize>0&&strSize<=4*1024*1024,CompatibilityEvidence.Status.UNKNOWN,"String table budget/layout");int strings=e.offset(strVa,(int)strSize);
            need(strings+strSize<=e.dynamic||e.dynamic+e.size<=strings,CompatibilityEvidence.Status.UNKNOWN,"String and dynamic table overlap");
            Map<Integer,Integer> edits=new LinkedHashMap<>();List<String> names=new ArrayList<>();
            for(int at:e.needed){long index=e.u(at+4);need(index<strSize,CompatibilityEvidence.Status.MALFORMED,"DT_NEEDED string index out of range");int end=(int)index;
                while(end<strSize&&bytes[strings+end]!=0){need(end-index<4096,CompatibilityEvidence.Status.UNKNOWN,"Dependency string budget");int c=bytes[strings+end]&255;need(c>=32&&c<127,CompatibilityEvidence.Status.UNKNOWN,"Non-ASCII dependency name");end++;}
                need(end<strSize,CompatibilityEvidence.Status.MALFORMED,"Unterminated dependency name");String name=new String(bytes,strings+(int)index,end-(int)index,StandardCharsets.US_ASCII);Matcher m=HOST.matcher(name);
                if(!m.matches())continue;String soname=m.group(1);if(!PUBLIC.contains(soname))return new Result(CompatibilityEvidence.Status.UNSUPPORTED,"Host path references non-allowlisted/private library "+soname+"; all dependencies retained",null);
                int suffix=end-soname.length();edits.put(at+4,suffix);names.add(soname);
            }
            if(edits.isEmpty())return new Result(CompatibilityEvidence.Status.SKIPPED,"No supported host NDK dependency path",null);
            e.relocationEvidence();
            String proof=edits.size()+" DT_NEEDED host paths reference public NDK stub sonames "+names+"; existing basename suffixes derived from bounded DT_STRTAB";
            if(!apply)return new Result(CompatibilityEvidence.Status.SUPPORTED,proof,null);
            byte[] out=bytes.clone();ByteBuffer b=ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);for(var edit:edits.entrySet())b.putInt(edit.getKey(),edit.getValue());
            for(int n=0;n<bytes.length;n++)if(bytes[n]!=out[n]){boolean allowed=false;for(int at:edits.keySet())if(n>=at&&n<at+4)allowed=true;need(allowed,CompatibilityEvidence.Status.UNKNOWN,"Out-of-scope edit");}
            Elf verified=new Elf(out);need(verified.needed.equals(e.needed),CompatibilityEvidence.Status.UNKNOWN,"Dependency table shape changed");
            for(var edit:edits.entrySet())need(verified.u(edit.getKey())==edit.getValue(),CompatibilityEvidence.Status.UNKNOWN,"Dependency index verification failed");
            need(inspect(out).status==CompatibilityEvidence.Status.SKIPPED,CompatibilityEvidence.Status.UNKNOWN,"Dependency postcondition failed");
            return new Result(CompatibilityEvidence.Status.VERIFIED,proof+"; only dependency indices changed. Native code, relocations, permissions and strings retained. Android namespace resolution and game startup remain unverified.",out);
        }catch(Stop stop){return new Result(stop.status,stop.getMessage(),null);}catch(IllegalArgumentException|IndexOutOfBoundsException bad){return new Result(CompatibilityEvidence.Status.MALFORMED,"Invalid ELF metadata",null);}
    }
    private NdkDependencyPathRule(){}
}
