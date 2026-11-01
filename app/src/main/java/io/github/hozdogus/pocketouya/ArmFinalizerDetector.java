package io.github.hozdogus.pocketouya;

import java.nio.*;
import java.util.*;
import static io.github.hozdogus.pocketouya.CompatibilityEvidence.Status.*;

/** Restricted read-only ELF recognizer. No catalog, hashes, filename tests or binary writer.
 * Constants describe ELF fields and instruction forms, never inspected-build addresses.
 * SUPPORTED means this local pattern contract, not global reference safety or repair eligibility.
 */
public final class ArmFinalizerDetector {
    private ArmFinalizerDetector(){}
    private static final class Rejection extends RuntimeException {
        final CompatibilityEvidence.Status status;final String code;
        Rejection(CompatibilityEvidence.Status s,String c,String m){super(m);status=s;code=c;}
    }
    private static void require(boolean test,CompatibilityEvidence.Status s,String code,String message){if(!test)throw new Rejection(s,code,message);}
    private static final class Segment {long address,offset,file,memory,flags;}
    private static final class Section {long type,address,offset,size,link,stride,name,flags;String label;}
    private static final class Symbol {String name;long value,size;int info,section;}
    private static final class Relocation {long address,info;int kind(){return (int)(info&255);}long symbol(){return info>>>8;}}
    private static final class Elf {
        final ByteBuffer b;final int length;
        final List<Segment> loads=new ArrayList<>();final List<Section> sections=new ArrayList<>();
        final List<Symbol> symbols=new ArrayList<>();final Map<Long,Long> dynamic=new HashMap<>();
        final Map<Long,Relocation> relocations=new HashMap<>(), jumps=new HashMap<>();
        final Map<Long,Long> unwind=new TreeMap<>();final Map<Long,Character> modes=new TreeMap<>();
        Section plt,dynsym;long symbolCount;
        Elf(byte[] input){
            length=input.length;require(length<=24*1024*1024,SKIPPED,"SIZE_LIMIT","ELF exceeds detector byte budget");
            b=ByteBuffer.wrap(input).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
            range(0,16);require(u32(0)==0x464c457fL,MALFORMED,"ELF_MAGIC","Not an ELF image");
            require(u8(4)==1&&u8(5)==1,UNSUPPORTED,"ELF_VARIANT","Only little-endian ELF32 is supported");
            range(0,52);require(u8(6)==1&&u16(40)==52,MALFORMED,"ELF_HEADER","Invalid ELF header version/size");require(u16(18)==40,UNSUPPORTED,"MACHINE","Only EM_ARM is supported");
            require(u16(16)==3&&u32(20)==1,UNSUPPORTED,"ELF_KIND","Expected ET_DYN version 1");
            long ph=u32(28),stride=u16(42),count=u16(44);require(count>0&&count<=128,UNSUPPORTED,"PH_COUNT","Unsupported program header count");
            require(stride==32,UNSUPPORTED,"PH_STRIDE","Unsupported program header stride");range(ph,stride*count);
            long dynOffset=-1,dynSize=0,dynAddress=-1;
            for(int i=0;i<count;i++){
                long at=ph+i*stride,type=u32(at);long offset=u32(at+4),address=u32(at+8),file=u32(at+16),memory=u32(at+20);range(offset,file);
                if(type==1){require(file<=memory&&address+memory<=0x100000000L,MALFORMED,"LOAD_RANGE","Invalid load range");Segment s=new Segment();s.offset=offset;s.address=address;s.file=file;s.memory=memory;s.flags=u32(at+24);require((s.flags&3)!=3,UNSUPPORTED,"WX_LOAD","Writable executable load unsupported");loads.add(s);}
                if(type==2){require(dynOffset<0,MALFORMED,"DUP_DYNAMIC","Multiple dynamic segments");dynOffset=offset;dynSize=file;dynAddress=address;}
            }
            for(int i=0;i<loads.size();i++)for(int j=i+1;j<loads.size();j++){Segment a=loads.get(i),c=loads.get(j);require(a.address+a.memory<=c.address||c.address+c.memory<=a.address,MALFORMED,"OVERLAPPING_LOADS","Ambiguous virtual mapping");}
            require(dynOffset>=0&&dynSize%8==0,MALFORMED,"DYNAMIC_LAYOUT","Missing/misaligned dynamic segment");
            require(offset(dynAddress,(int)dynSize)==dynOffset&&memory(dynAddress,(int)dynSize,2),MALFORMED,"DYNAMIC_MAPPING","Dynamic table is not in its declared writable load mapping");
            boolean ended=false;
            for(long at=dynOffset;at<dynOffset+dynSize;at+=8){long tag=u32(at),value=u32(at+4);if(tag==0){ended=true;break;}if(tag!=1)require(dynamic.put(tag,value)==null,MALFORMED,"DUP_TAG","Duplicate dynamic tag");}
            require(ended,MALFORMED,"DYNAMIC_END","Unterminated dynamic table");
            require(!dynamic.containsKey(7L),UNSUPPORTED,"RELA","RELA is not supported");
            long sh=u32(32),shStride=u16(46),shCount=u16(48),names=u16(50);
            require(shCount>0&&shCount<10000&&shStride==40,UNKNOWN,"SECTIONS_MISSING","Section evidence is required by this prototype");range(sh,shStride*shCount);
            for(int i=0;i<shCount;i++){long at=sh+i*shStride;Section s=new Section();s.name=u32(at);s.type=u32(at+4);s.flags=u32(at+8);s.address=u32(at+12);s.offset=u32(at+16);s.size=u32(at+20);s.link=u32(at+24);s.stride=u32(at+36);if(s.type!=8)range(s.offset,s.size);sections.add(s);}
            require(names>0,UNKNOWN,"SECTION_NAMES_MISSING","Named section evidence unavailable");require(names<sections.size(),MALFORMED,"SECTION_NAMES","Invalid section-name table");Section strings=sections.get((int)names);
            for(Section s:sections){s.label=string(strings,s.name);if(s.label.equals(".plt")){require(plt==null,UNKNOWN,"MULTIPLE_PLT","Multiple PLT sections");plt=s;}if(s.type==11&&s.address==tag(6)){require(dynsym==null,MALFORMED,"DYNSYM_DUP","Multiple dynamic symbols");dynsym=s;}}
            require(dynsym!=null&&dynsym.stride==16&&tag(11)==16,MALFORMED,"DYNSYM","Missing/invalid dynamic symbol table");require(dynsym.link<sections.size(),MALFORMED,"DYNSYM_LINK","Invalid dynamic string-table link");
            Section stringsForSymbols=sections.get((int)dynsym.link);
            require(offset(dynsym.address,(int)dynsym.size)==dynsym.offset&&stringsForSymbols.address==tag(5)&&stringsForSymbols.size>=tag(10),MALFORMED,"DYNAMIC_SECTIONS","Dynamic tables disagree with sections");
            if(plt!=null)require((plt.flags&6)==6&&offset(plt.address,(int)plt.size)==plt.offset,UNKNOWN,"PLT_MAPPING","PLT section is not consistent executable data");
            symbolCount=dynsym.size/16;require(dynsym.size%16==0&&symbolCount<1000000,MALFORMED,"DYNSYM_SIZE","Invalid symbol table");
            for(Section s:sections)if(s.type==2||s.type==11){require(s.stride==16&&s.size%16==0&&s.link<sections.size(),MALFORMED,"SYMBOL_LAYOUT","Invalid symbol table layout");Section namesTable=sections.get((int)s.link);
                for(long at=s.offset;at<s.offset+s.size;at+=16){Symbol symbol=new Symbol();symbol.name=string(namesTable,u32(at));symbol.value=u32(at+4);symbol.size=u32(at+8);symbol.info=u8(at+12);symbol.section=u16(at+14);symbols.add(symbol);require(symbols.size()<=100000,SKIPPED,"SYMBOL_BUDGET","Symbol budget exceeded");
                    if(symbol.name.matches("\\$[atd](?:\\..*)?")&&definedCode(symbol)){char mode=symbol.name.charAt(1);Character prior=modes.put(symbol.value,mode);require(prior==null||prior==mode,UNKNOWN,"MODE_CONFLICT","Conflicting mapping symbols");}}
            }
            readRelocations(17,18,relocations);readRelocations(23,2,jumps);
            for(long address:jumps.keySet())require(!relocations.containsKey(address),UNKNOWN,"RELOCATION_TABLE_OVERLAP","Dynamic and PLT relocation targets overlap");
            for(Section s:sections)if(s.type==0x70000001L){require(s.size%8==0,MALFORMED,"UNWIND_SIZE","Invalid exidx table");long prev=-1;for(long n=0;n<s.size;n+=8){long word=u32(s.offset+n),target=(s.address+n+((int)(word<<1)>>1))&0xffffffffL;target&=~1L;require(target>prev,MALFORMED,"UNWIND_ORDER","Unsorted unwind table");require(unwind.put(target,u32(s.offset+n+4))==null,UNKNOWN,"UNWIND_DUP","Duplicate unwind boundary");prev=target;require(unwind.size()<=100000,SKIPPED,"UNWIND_BUDGET","Unwind budget exceeded");}}
        }
        boolean definedCode(Symbol symbol){
            if(symbol.section==0||symbol.section>=sections.size())return false;
            Section s=sections.get(symbol.section);long value=symbol.value&~1L;
            return (s.flags&6)==6&&value>=s.address&&value<s.address+s.size&&memory(value,1,1);
        }
        void range(long at,long size){require(at>=0&&size>=0&&at<=length&&size<=length-at,MALFORMED,"FILE_RANGE","Truncated/out-of-bounds ELF data");}
        int u8(long at){range(at,1);return b.get((int)at)&255;}
        int u16(long at){range(at,2);return b.getShort((int)at)&65535;}
        long u32(long at){range(at,4);return Integer.toUnsignedLong(b.getInt((int)at));}
        long tag(long tag){Long v=dynamic.get(tag);require(v!=null,UNKNOWN,"TAG_MISSING","Required dynamic tag missing: "+tag);return v;}
        long offset(long va,int n){for(Segment s:loads)if(va>=s.address&&va+n<=s.address+s.file)return s.offset+va-s.address;throw new Rejection(MALFORMED,"VIRTUAL_RANGE","Address is not file-backed: "+Long.toHexString(va));}
        long word(long va){return u32(offset(va,4));}
        boolean memory(long va,int n,int flags){for(Segment s:loads)if(va>=s.address&&va+n<=s.address+s.memory&&(s.flags&flags)==flags)return true;return false;}
        String string(Section s,long index){require(index<s.size,MALFORMED,"STRING_INDEX","Invalid string index");long at=s.offset+index,end=at;while(end<s.offset+s.size&&end-at<=2048&&u8(end)!=0)end++;require(end<s.offset+s.size&&end-at<=2048,MALFORMED,"STRING_END","Unterminated/oversized string");byte[] value=new byte[(int)(end-at)];for(int i=0;i<value.length;i++)value[i]=b.get((int)at+i);return new String(value,java.nio.charset.StandardCharsets.UTF_8);}
        String symbol(long index){require(index>0&&index<symbolCount,MALFORMED,"RELOC_SYMBOL","Invalid relocation symbol");return string(sections.get((int)dynsym.link),u32(dynsym.offset+16*index));}
        void readRelocations(long tableTag,long sizeTag,Map<Long,Relocation> result){if(!dynamic.containsKey(tableTag))return;long bytes=tag(sizeTag);require(bytes%8==0&&bytes/8<=1000000,MALFORMED,"REL_SIZE","Invalid REL table");if(tableTag==17)require(tag(19)==8,UNSUPPORTED,"REL_STRIDE","Only ELF32 REL supported");long start=offset(tag(tableTag),(int)bytes);for(long at=start;at<start+bytes;at+=8){Relocation r=new Relocation();r.address=u32(at);r.info=u32(at+4);if(r.kind()==0)continue;require(memory(r.address,4,0),MALFORMED,"REL_TARGET","Relocation outside load memory");require(result.put(r.address,r)==null,MALFORMED,"REL_DUP","Duplicate relocation target");if(r.symbol()>0)symbol(r.symbol());}}
        boolean rel(long address,int kind,String name){Relocation r=relocations.get(address);if(r==null)return false;return r.kind()==kind&&(name==null?r.symbol()==0:name.equals(symbol(r.symbol())));}
        char mode(long address){long best=-1;char mode='?';for(Map.Entry<Long,Character> e:modes.entrySet())if(e.getKey()<=address&&e.getKey()>best){best=e.getKey();mode=e.getValue();}return mode;}
        long pltGot(long stub){require(memory(stub,12,1),UNSUPPORTED,"PLT_EXEC","PLT is not executable");long a=word(stub),c=word(stub+4),d=word(stub+8);require((a&0xfffff000L)==0xe28fc000L&&(c&0xfffff000L)==0xe28cc000L&&(d&0xfffff000L)==0xe5bcf000L,UNSUPPORTED,"PLT_SHAPE","Unrecognized ARM PLT shape");return (stub+8+immediate(a)+immediate(c)+(d&4095))&0xffffffffL;}
        static long immediate(long op){return Integer.toUnsignedLong(Integer.rotateRight((int)op&255,((int)(op>>>8)&15)*2));}
        boolean binds(long stub,String name){if(plt==null||stub<plt.address||stub+12>plt.address+plt.size)return false;long got=pltGot(stub);Relocation r=jumps.get(got);return memory(got,4,2)&&r!=null&&r.kind()==22&&name.equals(symbol(r.symbol()));}
        long findPlt(String name){require(plt!=null,UNKNOWN,"PLT_MISSING","PLT section unavailable");List<Long> matches=new ArrayList<>();for(long stub=(plt.address+3)&~3L;stub+12<=plt.address+plt.size;stub+=4){long a=word(stub),c=word(stub+4),d=word(stub+8);if((a&0xfffff000L)==0xe28fc000L&&(c&0xfffff000L)==0xe28cc000L&&(d&0xfffff000L)==0xe5bcf000L&&binds(stub,name))matches.add(stub);}require(matches.size()==1,UNKNOWN,"PLT_AMBIGUOUS","Expected exactly one relocation-bound PLT for "+name);return matches.get(0);}
    }
    public static CompatibilityEvidence inspect(byte[] input){
        List<CompatibilityEvidence.Finding> evidence=new ArrayList<>();List<CompatibilityEvidence.Candidate> candidates=new ArrayList<>();
        try{
            require(input!=null,MALFORMED,"NULL_INPUT","No ELF bytes supplied");
            Elf e=new Elf(input);List<Relocation> bad=new ArrayList<>();for(Relocation r:e.relocations.values())if(!e.memory(r.address,4,2))bad.add(r);for(Relocation r:e.jumps.values())require(e.memory(r.address,4,2),UNSUPPORTED,"JUMP_TEXTREL","Non-writable jump relocation unsupported");
            require(!bad.isEmpty(),UNSUPPORTED,"NO_CODE_RELOCATION","No non-writable relocation requiring this pattern");
            require(e.dynamic.containsKey(26L)&&e.dynamic.containsKey(28L),UNKNOWN,"FINI_ROOTS_MISSING","DT_FINI_ARRAY evidence required");long array=e.tag(26),size=e.tag(28);require(size%4==0&&size<=4096,MALFORMED,"FINI_SIZE","Invalid finalizer array");e.offset(array,(int)size);
            Set<Long> roots=new HashSet<>();for(long at=array;at<array+size;at+=4){long value=e.word(at);if(value!=0&&value!=0xffffffffL){require(e.rel(at,23,null),UNKNOWN,"FINI_RELOCATION","Finalizer pointer needs RELATIVE relocation");roots.add(value);}}
            List<Long> matches=new ArrayList<>();
            for(long root:roots){if((root&3)!=0)continue;if(!e.memory(root,16,1))continue;
                if(e.word(root)==0xe28f0004L&&e.word(root+4)==0xe5900000L&&(e.word(root+8)&0xff000000L)==0xea000000L&&e.rel(root+12,23,null))matches.add(root);}
            require(matches.size()==1,UNSUPPORTED,"FINALIZER_MATCH_COUNT","Expected one metadata-rooted ARM address-load finalizer; found "+matches.size());
            long f=matches.get(0),target=e.word(f+12);
            for(long root:roots)require((root&~1L)<=f||(root&~1L)>=f+16,UNKNOWN,"OVERLAPPING_ROOT","Another finalizer entry overlaps code/literal");
            for(Symbol symbol:e.symbols)if((symbol.info&15)==2)require((symbol.value&~1L)<=f||(symbol.value&~1L)>=f+16,UNKNOWN,"OVERLAPPING_SYMBOL","Function symbol overlaps finalizer body/data");
            for(long boundary:e.unwind.keySet())require(boundary<=f||boundary>=f+16,UNKNOWN,"OVERLAPPING_UNWIND","Unwind function begins inside finalizer body/data");require(e.memory(target,4,2),UNSUPPORTED,"TARGET_NOT_RW","Finalizer target is not writable load memory");
            require(e.mode(f)!='t'&&e.mode(f)!='d',UNKNOWN,"ARM_MODE_CONFLICT","Mapping symbols contradict finalizer ARM code");require(e.mode(f+12)!='a'&&e.mode(f+12)!='t',UNKNOWN,"LITERAL_MODE_CONFLICT","Literal overlaps mapped instructions");
            long branch=e.word(f+8),destination=(f+16+(((int)(branch<<8))>>6))&0xffffffffL;
            require(e.binds(destination,"__cxa_finalize"),UNSUPPORTED,"FINALIZE_BINDING","Tail branch lacks __cxa_finalize jump relocation binding");
            evidence.add(new CompatibilityEvidence.Finding(VERIFIED,"ROOTED_ARM_FINALIZER","local instruction contract","DT_FINI_ARRAY ARM entry; three decoded instructions; tail branch precedes relocated data literal; symbol-bound __cxa_finalize",f));
            Set<Long> consumed=new HashSet<>();consumed.add(f+12);long thunk=-1,atexit=-1;
            for(Relocation r:bad)if(r.address!=f+12){
                require(thunk<0&&r.kind()==2&&r.symbol()>0&&e.symbol(r.symbol()).equals("__cxa_atexit"),UNSUPPORTED,"EXTRA_CODE_RELOCATIONS","Unsupported non-writable relocation outside finalizer/atexit family");
                long t=r.address-20;require(t>=0&&e.memory(t,24,1),UNSUPPORTED,"THUNK_RANGE","Thunk extent not executable/file-backed");e.offset(t,24);
                int[] halves={0xb510,0x4b03,0x2100,0x447b,0x681a,0x4b02,0x4798,0xbd10};for(int i=0;i<halves.length;i++)require(e.u16(e.offset(t+2*i,2))==halves[i],UNSUPPORTED,"THUNK_INSTRUCTIONS","Unsupported Thumb thunk instruction form");
                require(e.word(t+16)==((target-(t+10))&0xffffffffL)&&e.word(t+20)==0,UNSUPPORTED,"THUNK_LITERALS","Thunk literals do not reference same target and unresolved symbol");
                require(Objects.equals(e.unwind.get(t),0x80a8b0b0L)&&e.unwind.containsKey(t+24),UNKNOWN,"THUNK_BOUNDARY","Need exact exidx extent and compatible stack recipe");
                thunk=t;atexit=e.findPlt("__cxa_atexit");candidates.add(new CompatibilityEvidence.Candidate(f,target,thunk,atexit));
                evidence.add(new CompatibilityEvidence.Finding(VERIFIED,"THUNK_SHAPE","bytes/relocation/unwind only","Eight Thumb-shaped halfwords, two data loads, matching target and symbol-bound PLT; instruction mode still requires independent evidence",t));
                boolean typed=false;for(Symbol s:e.symbols)if(e.definedCode(s)&&(s.info&15)==2&&s.value==(t|1)&&s.size>=16&&s.size<=24)typed=true;
                boolean mapped=e.mode(t)=='t'&&e.mode(t+14)=='t'&&e.mode(t+16)=='d'&&e.mode(t+23)=='d';
                require(typed||mapped,UNKNOWN,"THUMB_MODE_UNPROVEN","No Thumb STT_FUNC entry or consistent $t/$d mapping; exidx alone does not prove instruction mode");
                require(e.mode(t)!='a'&&e.mode(t)!='d'&&e.mode(t+16)!='a'&&e.mode(t+16)!='t',UNKNOWN,"THUMB_MODE_CONFLICT","Mapping contradicts Thumb entry");consumed.add(r.address);
            }
            require(consumed.size()==bad.size(),UNSUPPORTED,"COVERAGE","Not every non-writable relocation covered");
            if(candidates.isEmpty())candidates.add(new CompatibilityEvidence.Candidate(f,target,thunk,atexit));
            evidence.add(new CompatibilityEvidence.Finding(VERIFIED,"LOCAL_PATTERN_CONTRACT","read-only recognition","All non-writable relocation targets belong to the recognized family. Global use/reference safety and transformation eligibility are NOT established.",f));
            return new CompatibilityEvidence(SUPPORTED,evidence,candidates);
        }catch(Rejection r){evidence.add(new CompatibilityEvidence.Finding(r.status,r.code,"read-only detector",r.getMessage(),-1));return new CompatibilityEvidence(r.status,evidence,candidates);}
        catch(IndexOutOfBoundsException|IllegalArgumentException r){evidence.add(new CompatibilityEvidence.Finding(MALFORMED,"PARSE_RANGE","ELF parser",r.toString(),-1));return new CompatibilityEvidence(MALFORMED,evidence,candidates);}
    }
}
