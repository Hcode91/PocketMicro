package com.hcode91.pocketmicro;

import java.nio.*;
import java.util.*;

/** Independent ELF rule: remove stale declarations ONLY after proving every loader write is RW.
 * Never repairs instructions, changes load permissions, or uses the finalizer prototype/catalog.
 */
public final class WritableRelocationRule {
    public static final String ID="elf.arm32.stale-textrel";
    public static final class Result {
        public final CompatibilityEvidence.Status status;
        public final String reason;
        private final byte[] replacement;
        Result(CompatibilityEvidence.Status status,String reason,byte[] replacement){this.status=status;this.reason=reason;this.replacement=replacement;}
        public byte[] replacement(){return replacement==null?null:replacement.clone();}
    }
    private static final class Stop extends RuntimeException {
        final CompatibilityEvidence.Status status;
        Stop(CompatibilityEvidence.Status status,String message){super(message);this.status=status;}
    }
    private static void need(boolean yes,CompatibilityEvidence.Status status,String message){if(!yes)throw new Stop(status,message);}
    private static final class Load {long at,va,file,mem,flags;}
    private static final class Elf {
        final byte[] bytes;final ByteBuffer b;final List<Load> loads=new ArrayList<>();
        final Map<Long,Long> tags=new HashMap<>();final List<Integer> entries=new ArrayList<>();
        final Set<Long> writes=new HashSet<>();int dynamic,dynamicSize;boolean declared;
        long u(int at){range(at,4);return b.getInt(at)&0xffffffffL;}
        void range(long at,long size){need(at>=0&&size>=0&&at<=bytes.length&&size<=bytes.length-at,CompatibilityEvidence.Status.MALFORMED,"ELF file range is truncated");}
        int offset(long va,int size){for(Load l:loads)if(va>=l.va&&va-l.va<=l.file&&size<=l.file-(va-l.va))return (int)(l.at+va-l.va);throw new Stop(CompatibilityEvidence.Status.MALFORMED,"Relocation metadata is not file-backed");}
        boolean writable(long va){for(Load l:loads)if(va>=l.va&&va-l.va<=l.mem&&4<=l.mem-(va-l.va))return (l.flags&2)!=0;return false;}
        Elf(byte[] input){this(input,false);}
        Elf(byte[] input,boolean verifyTables){
            need(input!=null,CompatibilityEvidence.Status.MALFORMED,"Missing ELF");bytes=input;b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            range(0,52);need(u(0)==0x464c457fL,CompatibilityEvidence.Status.MALFORMED,"ELF magic missing");
            need(bytes[4]==1&&bytes[5]==1&&bytes[6]==1,CompatibilityEvidence.Status.UNSUPPORTED,"Only ELF32 little-endian is supported by this rule");
            need((b.getShort(16)&65535)==3&&(b.getShort(18)&65535)==40,CompatibilityEvidence.Status.UNSUPPORTED,"Only ARM ET_DYN is supported by this rule");
            need(u(20)==1&&(b.getShort(40)&65535)==52,CompatibilityEvidence.Status.MALFORMED,"Invalid ELF header");
            long table=u(28);int stride=b.getShort(42)&65535,count=b.getShort(44)&65535;
            need(count>0&&count<=256&&stride==32,CompatibilityEvidence.Status.UNSUPPORTED,"Unsupported program header layout");range(table,(long)stride*count);
            long dynamicVa=-1;
            for(int i=0;i<count;i++){
                int p=(int)table+i*stride;long type=u(p),at=u(p+4),va=u(p+8),file=u(p+16),mem=u(p+20),flags=u(p+24);
                if(type!=1&&type!=2)continue;range(at,file);
                need(file<=mem&&va+mem<=0x100000000L,CompatibilityEvidence.Status.MALFORMED,"Invalid load extent");
                if(type==1){long align=u(p+28);need(align<=1||((align&(align-1))==0&&(at%align)==(va%align)),CompatibilityEvidence.Status.MALFORMED,"Invalid load alignment");need((flags&3)!=3,CompatibilityEvidence.Status.UNSUPPORTED,"Writable executable mappings are unsupported");Load l=new Load();l.at=at;l.va=va;l.file=file;l.mem=mem;l.flags=flags;
                    for(Load old:loads){need(va+mem<=old.va||old.va+old.mem<=va,CompatibilityEvidence.Status.UNKNOWN,"Overlapping load mappings");need(at+file<=old.at||old.at+old.file<=at,CompatibilityEvidence.Status.UNKNOWN,"Aliased load file bytes");}loads.add(l);
                }else{need(dynamicVa<0,CompatibilityEvidence.Status.UNKNOWN,"Multiple dynamic tables");dynamic=(int)at;dynamicSize=(int)file;dynamicVa=va;}
            }
            need(dynamicVa>=0&&dynamicSize>=8&&dynamicSize%8==0&&dynamicSize<=65536,CompatibilityEvidence.Status.UNKNOWN,"Missing or unsupported dynamic table");
            need(offset(dynamicVa,dynamicSize)==dynamic,CompatibilityEvidence.Status.MALFORMED,"Dynamic mapping disagreement");
            boolean terminated=false;
            for(int at=dynamic;at<dynamic+dynamicSize;at+=8){long tag=u(at),value=u(at+4);if(tag==0){terminated=true;break;}entries.add(at);
                // DT_NEEDED can repeat; all relocation descriptors must be unique.
                if(tag!=1)need(tags.put(tag,value)==null,CompatibilityEvidence.Status.UNKNOWN,"Duplicate dynamic descriptor");
                if(tag==22||(tag==30&&(value&4)!=0))declared=true;
            }
            need(terminated,CompatibilityEvidence.Status.MALFORMED,"Unterminated dynamic table");
            if(!declared&&!verifyTables)return;
            for(long tag:new long[]{7,8,9,35,36,37,0x6000000fL,0x60000010L,0x60000011L,0x60000012L,0x6fffe000L,0x6fffe001L,0x6fffe003L})
                need(!tags.containsKey(tag),CompatibilityEvidence.Status.UNSUPPORTED,"RELA, packed or RELR relocation descriptors are unsupported");
            need(tags.containsKey(17L)&&tags.containsKey(18L)&&Objects.equals(tags.get(19L),8L),CompatibilityEvidence.Status.UNKNOWN,"Complete DT_REL/DT_RELSZ/DT_RELENT evidence required");
            long rel=tags.get(17L),size=tags.get(18L);scan(rel,size);
            if(tags.containsKey(0x6ffffffaL)){
                long prefix=tags.get(0x6ffffffaL);need(prefix<=size/8,CompatibilityEvidence.Status.MALFORMED,"DT_RELCOUNT exceeds relocation count");
                int p=offset(rel,(int)size);for(int n=0;n<prefix;n++)need(u(p+n*8+4)==23,CompatibilityEvidence.Status.MALFORMED,"DT_RELCOUNT prefix is not RELATIVE");
            }
            boolean jump=tags.containsKey(23L)||tags.containsKey(2L)||tags.containsKey(20L);
            if(jump){need(tags.containsKey(23L)&&tags.containsKey(2L)&&Objects.equals(tags.get(20L),17L),CompatibilityEvidence.Status.UNKNOWN,"Incomplete or non-REL PLT relocations");long j=tags.get(23L),n=tags.get(2L);need(j+n<=rel||rel+size<=j,CompatibilityEvidence.Status.UNKNOWN,"Overlapping relocation tables");scan(j,n);}
            // A relocation may not overwrite the descriptors/tables whose proof we used.
            for(long w:writes){need(w+4<=dynamicVa||w>=dynamicVa+dynamicSize,CompatibilityEvidence.Status.UNSUPPORTED,"Loader writes into dynamic metadata");
                need(w+4<=rel||w>=rel+size,CompatibilityEvidence.Status.UNSUPPORTED,"Loader writes into relocation metadata");
                if(jump){long j=tags.get(23L),n=tags.get(2L);need(w+4<=j||w>=j+n,CompatibilityEvidence.Status.UNSUPPORTED,"Loader writes into PLT relocation metadata");}
            }
        }
        void scan(long va,long size){
            need(size%8==0&&size<=8L*262144,CompatibilityEvidence.Status.MALFORMED,"Invalid or excessive relocation table");int at=offset(va,(int)size);
            for(int i=0;i<size;i+=8){long target=u(at+i),info=u(at+i+4);int kind=(int)(info&255);
                if(kind==0)continue;
                need(kind==2||kind==3||kind==21||kind==22||kind==23,CompatibilityEvidence.Status.UNSUPPORTED,"Unsupported ARM relocation type "+kind);
                need(kind!=23||(info>>>8)==0,CompatibilityEvidence.Status.MALFORMED,"RELATIVE relocation has a symbol index");
                if((info>>>8)!=0){need(tags.containsKey(6L)&&Objects.equals(tags.get(11L),16L),CompatibilityEvidence.Status.UNKNOWN,"Symbol relocation lacks SYMTAB/SYMENT evidence");offset(tags.get(6L)+(info>>>8)*16,16);}
                need((target&3)==0&&writable(target),CompatibilityEvidence.Status.UNSUPPORTED,"Actual relocation writes outside aligned writable memory at 0x"+Long.toHexString(target)+"; declarations must remain");
                need(writes.add(target),CompatibilityEvidence.Status.UNKNOWN,"Duplicate relocation write target");
            }
        }
    }
    public static Result inspect(byte[] input){return run(input,false);}
    public static Result repair(byte[] input){return run(input,true);}
    private static Result run(byte[] input,boolean apply){
        try{
            need(input==null||input.length<=24*1024*1024,CompatibilityEvidence.Status.SKIPPED,"ELF exceeds 24 MiB budget");Elf e=new Elf(input);
            if(!e.declared)return new Result(CompatibilityEvidence.Status.SKIPPED,"No text-relocation declaration; no edit",null);
            String proof=e.writes.size()+" supported REL/PLT loader writes verified in writable load memory; no instruction or permission edits";
            if(!apply)return new Result(CompatibilityEvidence.Status.SUPPORTED,proof,null);
            byte[] out=input.clone();ByteBuffer b=ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);int at=e.dynamic;
            for(int entry:e.entries){long tag=e.u(entry);if(tag==22)continue;b.putInt(at,(int)tag);b.putInt(at+4,(int)(tag==30?e.u(entry+4)&~4L:e.u(entry+4)));at+=8;}
            Arrays.fill(out,at,e.dynamic+(e.entries.size()+1)*8,(byte)0);
            Elf verified=new Elf(out,true);need(!verified.declared&&verified.writes.equals(e.writes),CompatibilityEvidence.Status.UNKNOWN,"Relocation/declaration postcondition failed");
            for(int i=0;i<input.length;i++)if(i<e.dynamic||i>=e.dynamic+e.dynamicSize)need(out[i]==input[i],CompatibilityEvidence.Status.UNKNOWN,"Out-of-scope byte edit");
            return new Result(CompatibilityEvidence.Status.VERIFIED,proof+"; stale declarations removed and byte scope verified",out);
        }catch(Stop stop){return new Result(stop.status,stop.getMessage(),null);}
        catch(IndexOutOfBoundsException|IllegalArgumentException bad){return new Result(CompatibilityEvidence.Status.MALFORMED,"Invalid ELF range: "+bad.getClass().getSimpleName(),null);}
    }
    private WritableRelocationRule(){}
}
