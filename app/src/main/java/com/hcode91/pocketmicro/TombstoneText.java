package com.hcode91.pocketmicro;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Turns Android's native crash record (the tombstone protobuf that
 * ApplicationExitInfo hands out since Android 12) into readable text, in the
 * same layout as the familiar debuggerd tombstone: signal, registers,
 * backtraces, memory near registers and the memory map. Log buffers and open
 * file lists in the record are left out on purpose: they are not needed to
 * read a crash and may contain more than the user expects to share.
 */
final class TombstoneText {
    private TombstoneText(){}
    static final int MAX_MAPPINGS=6000;

    /** Minimal protobuf reader: varints, 64/32-bit fixed and length-delimited fields. */
    static final class Reader {
        final byte[] b;int p;final int end;
        Reader(byte[] b,int start,int end){this.b=b;this.p=start;this.end=end;}
        boolean more(){return p<end;}
        long varint(){long v=0;int shift=0;while(true){if(p>=end||shift>63)throw new IllegalArgumentException("Bad varint");int x=b[p++]&0xff;v|=(long)(x&0x7f)<<shift;if((x&0x80)==0)return v;shift+=7;}}
        int tag;int field(){return tag>>>3;}int wire(){return tag&7;}
        boolean next(){if(!more())return false;tag=(int)varint();return true;}
        int[] bytes(){int n=(int)varint();if(n<0||n>end-p)throw new IllegalArgumentException("Bad length");int[] r={p,p+n};p+=n;return r;}
        Reader sub(){int[] r=bytes();return new Reader(b,r[0],r[1]);}
        String string(){int[] r=bytes();return new String(b,r[0],r[1]-r[0],StandardCharsets.UTF_8);}
        byte[] raw(){int[] r=bytes();return Arrays.copyOfRange(b,r[0],r[1]);}
        long number(){switch(wire()){case 0:return varint();case 1:{long v=0;for(int i=0;i<8;i++)v|=(long)(b[p++]&0xff)<<(8*i);return v;}case 5:{long v=0;for(int i=0;i<4;i++)v|=(long)(b[p++]&0xff)<<(8*i);return v;}default:skip();return 0;}}
        void skip(){switch(wire()){case 0:varint();break;case 1:p+=8;break;case 2:bytes();break;case 5:p+=4;break;default:throw new IllegalArgumentException("Unsupported wire type "+wire());}if(p>end)throw new IllegalArgumentException("Truncated field");}
    }

    static final class Frame{long relPc,pc,sp,fnOffset,mapOffset;String fn="",file="",buildId="";}
    static final class Dump{String register="",mapping="";long begin;byte[] memory=new byte[0];}
    static final class Thread{int id;String name="";final List<String[]> regs=new ArrayList<>();final List<Long> regValues=new ArrayList<>();final List<Frame> frames=new ArrayList<>();final List<Dump> dumps=new ArrayList<>();final List<String> notes=new ArrayList<>();}
    static final class Mapping{long begin,end,offset,loadBias;boolean r,w,x;String name="",buildId="";}

    /** Text for the global log: decoded dump, plain text trace (ANR), or base64 as a last resort. */
    static String logText(byte[] trace){
        if(trace==null||trace.length==0)return "Android attached no crash dump to this record.";
        String decoded=format(trace);
        if(decoded!=null)return "Full crash dump (decoded by PocketOuya):\n"+decoded;
        boolean binary=false;for(int i=0;i<Math.min(4096,trace.length);i++){int c=trace[i]&0xff;if(c<9){binary=true;break;}}
        if(!binary)return new String(trace,StandardCharsets.UTF_8);
        int keep=Math.min(trace.length,4*1024*1024);
        return "Crash dump in a format PocketOuya could not decode (base64, "+trace.length+" bytes"+(keep<trace.length?", first "+keep+" bytes":"")+"):\n"+Base64.getMimeEncoder().encodeToString(Arrays.copyOf(trace,keep));
    }

    /** Returns readable text, or null if the bytes are not a tombstone record. */
    static String format(byte[] data){
        if(data==null||data.length==0)return null;
        try{return decode(data);}catch(RuntimeException error){return null;}
    }

    private static String decode(byte[] data){
        Reader r=new Reader(data,0,data.length);
        int arch=0,pid=0,tid=0,uid=0,uptime=-1;String fingerprint="",revision="",timestamp="",abort="";
        List<String> command=new ArrayList<>(),causes=new ArrayList<>();
        int sigNumber=-1,sigCode=0;String sigName="",codeName="";boolean hasFault=false;long fault=0;
        Map<Integer,Thread> threads=new LinkedHashMap<>();List<Mapping> maps=new ArrayList<>();int totalMaps=0;boolean sawSignal=false,sawThreads=false;
        while(r.next()){
            switch(r.field()){
                case 1:arch=(int)r.number();break;
                case 2:fingerprint=r.string();break;
                case 3:revision=r.string();break;
                case 4:timestamp=r.string();break;
                case 5:pid=(int)r.number();break;
                case 6:tid=(int)r.number();break;
                case 7:uid=(int)r.number();break;
                case 9:command.add(r.string());break;
                case 10:{sawSignal=true;Reader s=r.sub();while(s.next()){switch(s.field()){case 1:sigNumber=(int)s.number();break;case 2:sigName=s.string();break;case 3:sigCode=(int)s.number();break;case 4:codeName=s.string();break;case 8:hasFault=s.number()!=0;break;case 9:fault=s.number();break;default:s.skip();}}break;}
                case 14:abort=r.string();break;
                case 15:{Reader c=r.sub();while(c.next()){if(c.field()==1&&c.wire()==2)causes.add(c.string());else c.skip();}break;}
                case 16:{sawThreads=true;Reader e=r.sub();Thread t=null;while(e.next()){if(e.field()==2&&e.wire()==2)t=thread(e.sub());else e.skip();}if(t!=null)threads.put(t.id,t);break;}
                case 17:{totalMaps++;if(maps.size()<MAX_MAPPINGS)maps.add(mapping(r.sub()));else r.skip();break;}
                case 20:uptime=(int)r.number();break;
                default:r.skip();
            }
        }
        if(!sawSignal&&!sawThreads)throw new IllegalArgumentException("Not a tombstone");
        boolean wide=arch==1||arch==3||arch==4;
        StringBuilder out=new StringBuilder(64*1024);
        out.append("*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***\n");
        if(!fingerprint.isEmpty())out.append("Build fingerprint: '").append(fingerprint).append("'\n");
        if(!revision.isEmpty())out.append("Revision: '").append(revision).append("'\n");
        out.append("ABI: '").append(archName(arch)).append("'\n");
        if(!timestamp.isEmpty())out.append("Timestamp: ").append(timestamp).append('\n');
        if(uptime>=0)out.append("Process uptime: ").append(uptime).append("s\n");
        if(!command.isEmpty())out.append("Cmdline: ").append(String.join(" ",command)).append('\n');
        Thread main=threads.get(tid);
        out.append("pid: ").append(pid).append(", tid: ").append(tid).append(", name: ").append(main==null?"":main.name).append("\n");
        out.append("uid: ").append(uid).append('\n');
        if(sigNumber>=0){
            out.append("signal ").append(sigNumber).append(" (").append(sigName).append("), code ").append(sigCode).append(" (").append(codeName).append("), fault addr ");
            out.append(hasFault?hex(fault,wide):"--------").append('\n');
        }
        for(String cause:causes)out.append("Cause: ").append(cause).append('\n');
        if(!abort.isEmpty())out.append("Abort message: '").append(abort).append("'\n");
        if(main!=null)thread(out,main,wide,true);
        for(Thread t:threads.values())if(t!=main){out.append("\n--- --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---\n");out.append("pid: ").append(pid).append(", tid: ").append(t.id).append(", name: ").append(t.name).append('\n');thread(out,t,wide,false);}
        if(!maps.isEmpty()){
            out.append("\nmemory map (").append(totalMaps).append(" entries):\n");
            for(Mapping m:maps){
                out.append("    ").append(hex(m.begin,wide)).append('-').append(hex(m.end-1,wide)).append(' ').append(m.r?'r':'-').append(m.w?'w':'-').append(m.x?'x':'-');
                out.append(' ').append(Long.toHexString(m.offset)).append(' ').append(Long.toHexString(m.end-m.begin));
                if(!m.name.isEmpty())out.append("  ").append(m.name);
                if(m.loadBias!=0)out.append(" (load bias 0x").append(Long.toHexString(m.loadBias)).append(')');
                if(!m.buildId.isEmpty())out.append(" (BuildId: ").append(m.buildId).append(')');
                out.append('\n');
            }
            if(totalMaps>maps.size())out.append("    ... ").append(totalMaps-maps.size()).append(" more entries omitted\n");
        }
        return out.toString();
    }

    private static Thread thread(Reader r){
        Thread t=new Thread();
        while(r.next()){
            switch(r.field()){
                case 1:t.id=(int)r.number();break;
                case 2:t.name=r.string();break;
                case 3:{Reader g=r.sub();String name="";long value=0;while(g.next()){if(g.field()==1&&g.wire()==2)name=g.string();else if(g.field()==2)value=g.number();else g.skip();}t.regs.add(new String[]{name});t.regValues.add(value);break;}
                case 4:{Reader f=r.sub();Frame fr=new Frame();while(f.next()){switch(f.field()){case 1:fr.relPc=f.number();break;case 2:fr.pc=f.number();break;case 3:fr.sp=f.number();break;case 4:fr.fn=f.string();break;case 5:fr.fnOffset=f.number();break;case 6:fr.file=f.string();break;case 7:fr.mapOffset=f.number();break;case 8:fr.buildId=f.string();break;default:f.skip();}}t.frames.add(fr);break;}
                case 5:{Reader d=r.sub();Dump dm=new Dump();while(d.next()){switch(d.field()){case 1:dm.register=d.string();break;case 2:dm.mapping=d.string();break;case 3:dm.begin=d.number();break;case 4:dm.memory=d.raw();break;default:d.skip();}}t.dumps.add(dm);break;}
                case 7:t.notes.add(r.string());break;
                default:r.skip();
            }
        }
        return t;
    }
    private static Mapping mapping(Reader r){
        Mapping m=new Mapping();
        while(r.next()){
            switch(r.field()){
                case 1:m.begin=r.number();break;case 2:m.end=r.number();break;case 3:m.offset=r.number();break;
                case 4:m.r=r.number()!=0;break;case 5:m.w=r.number()!=0;break;case 6:m.x=r.number()!=0;break;
                case 7:m.name=r.string();break;case 8:m.buildId=r.string();break;case 9:m.loadBias=r.number();break;
                default:r.skip();
            }
        }
        return m;
    }

    private static void thread(StringBuilder out,Thread t,boolean wide,boolean crashing){
        if(!t.regs.isEmpty()){
            int perLine=wide?4:4;
            for(int i=0;i<t.regs.size();i++){
                if(i%perLine==0)out.append("   ");
                String name=t.regs.get(i)[0];out.append(' ').append(pad(name,wide?3:3)).append(' ').append(hex(t.regValues.get(i),wide));
                if(i%perLine==perLine-1||i==t.regs.size()-1)out.append('\n');
            }
        }
        for(String note:t.notes)out.append("  NOTE: ").append(note).append('\n');
        if(!t.frames.isEmpty()){
            out.append('\n').append(t.frames.size()).append(" total frames\nbacktrace:\n");
            for(int i=0;i<t.frames.size();i++){
                Frame f=t.frames.get(i);
                out.append("      #").append(i<10?"0":"").append(i).append(" pc ").append(hex(f.relPc,wide)).append("  ").append(f.file.isEmpty()?"<unknown>":f.file);
                if(f.mapOffset!=0)out.append(" (offset 0x").append(Long.toHexString(f.mapOffset)).append(')');
                if(!f.fn.isEmpty())out.append(" (").append(f.fn).append('+').append(f.fnOffset).append(')');
                if(!f.buildId.isEmpty())out.append(" (BuildId: ").append(f.buildId).append(')');
                out.append('\n');
            }
        }
        if(crashing)for(Dump d:t.dumps){
            out.append("\nmemory near ").append(d.register);if(!d.mapping.isEmpty())out.append(" (").append(d.mapping).append(')');out.append(":\n");
            hexdump(out,d.begin,d.memory,wide);
        }
    }

    static void hexdump(StringBuilder out,long begin,byte[] memory,boolean wide){
        int word=wide?8:4;
        for(int line=0;line<memory.length;line+=16){
            out.append("    ").append(hex(begin+line,wide));
            StringBuilder ascii=new StringBuilder();
            for(int w=0;w<16;w+=word){
                out.append(' ');
                if(line+w+word>memory.length){for(int k=0;k<word*2;k++)out.append('-');continue;}
                long v=0;for(int k=word-1;k>=0;k--)v=(v<<8)|(memory[line+w+k]&0xff);
                out.append(hex(v,wide));
            }
            for(int k=0;k<16&&line+k<memory.length;k++){int c=memory[line+k]&0xff;ascii.append(c>=32&&c<127?(char)c:'.');}
            out.append("  ").append(ascii).append('\n');
        }
    }
    private static String archName(int arch){switch(arch){case 0:return "arm";case 1:return "arm64";case 2:return "x86";case 3:return "x86_64";case 4:return "riscv64";default:return "unknown ("+arch+")";}}
    private static String pad(String s,int n){StringBuilder b=new StringBuilder(s);while(b.length()<n)b.append(' ');return b.toString();}
    static String hex(long v,boolean wide){String s=Long.toHexString(wide?v:(v&0xffffffffL));int n=wide?16:8;StringBuilder b=new StringBuilder();for(int i=s.length();i<n;i++)b.append('0');return b.append(s).toString();}
}
