package io.github.hcode91.pocketouya.bridge;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.*;

/**
 * Value search over the game's own memory, for the opt-in cheat menu.
 *
 * <p>Runs inside the game process and reads {@code /proc/self/mem}, which a process may always do
 * for itself: no root, ptrace, or extra permission. Only private writable mappings are searched
 * (heap, anonymous memory, stacks and library data); device mappings such as GPU memory are
 * skipped because reading them can hang. No Android classes, so it can be tested on a desktop JVM.
 */
public final class MemoryScanner {
    public enum Type {
        INT32("Whole number (4 bytes)",4,4), FLOAT("Decimal (float)",4,4), INT16("Small number (2 bytes)",2,2),
        INT8("Byte",1,1), INT64("Large number (8 bytes)",8,4), DOUBLE("Decimal (double)",8,4);
        public final String label;public final int size,align;
        Type(String label,int size,int align){this.label=label;this.size=size;this.align=align;}
        public boolean decimal(){return this==FLOAT||this==DOUBLE;}
    }
    public enum Compare {
        EQUALS("Equal to"), CHANGED("Changed"), UNCHANGED("Unchanged"), INCREASED("Increased"), DECREASED("Decreased"),
        INCREASED_BY("Increased by"), DECREASED_BY("Decreased by");
        public final String label;Compare(String label){this.label=label;}
        public boolean needsValue(){return this==EQUALS||this==INCREASED_BY||this==DECREASED_BY;}
        public boolean needsPrevious(){return this!=EQUALS;}
    }
    public interface Progress {void update(String message);boolean cancelled();}

    static final class Region {
        final long start,end,offset;final String path;
        Region(long start,long end,long offset,String path){this.start=start;this.end=end;this.offset=offset;this.path=path;}
    }

    public static final int MAX_RESULTS=1_000_000;
    static final long MAX_REGION=1L<<30, MAX_SNAPSHOT=768L<<20;
    private static final int CHUNK=1<<20;

    private final File maps,mem,snapshotFile;
    private Type type=Type.INT32;
    private long[] addresses=new long[0];
    private long[] previous=new long[0];
    private int count;
    private boolean snapshot; // the first "unknown value" step: previous values are in snapshotFile

    public MemoryScanner(File cacheDir){this(new File("/proc/self/maps"),new File("/proc/self/mem"),new File(cacheDir,"pocketouya-cheat-snapshot.bin"));}
    /** For tests: a custom maps listing narrows the search to known memory. */
    public MemoryScanner(File maps,File mem,File snapshotFile){this.maps=maps;this.mem=mem;this.snapshotFile=snapshotFile;}

    public Type type(){return type;}
    public int count(){return count;}
    public boolean hasResults(){return count>0||snapshot;}
    public boolean waitingForUnknownCompare(){return snapshot;}
    public long address(int i){return addresses[i];}
    public void reset(){addresses=new long[0];previous=new long[0];count=0;snapshot=false;snapshotFile.delete();}

    /** Every address of the given type whose value equals {@code value}. Replaces earlier results. */
    public int first(Type type,double value,Progress progress)throws IOException{
        reset();this.type=type;
        long[] found=new long[1024],values=new long[1024];int n=0;
        ByteBuffer buffer=ByteBuffer.allocate(CHUNK).order(ByteOrder.LITTLE_ENDIAN);
        List<Region> regions=regions();int done=0;
        try(FileChannel channel=new RandomAccessFile(mem,"r").getChannel()){
            for(Region r:regions){
                if(progress!=null){if(progress.cancelled())throw new InterruptedIOException("Search cancelled");progress.update("Searching memory… "+(++done*100/Math.max(1,regions.size()))+"% · "+n+" found");}
                for(long at=r.start;at<r.end;at+=CHUNK){
                    int length=(int)Math.min(CHUNK,r.end-at);
                    if(!read(channel,buffer,at,length))continue;
                    for(int i=0;i+type.size<=length;i+=type.align){
                        long raw=raw(buffer,i,type);
                        if(!equalsValue(raw,type,value))continue;
                        if(n==MAX_RESULTS)throw new IOException("Over "+MAX_RESULTS+" matches. Change the value in the game and search for the new number, or pick a less common value.");
                        if(n==found.length){found=Arrays.copyOf(found,n*2);values=Arrays.copyOf(values,n*2);}
                        found[n]=at+i;values[n]=raw;n++;
                    }
                }
            }
        }
        addresses=found;previous=values;count=n;return n;
    }

    /** Remembers all of memory for an "unknown starting value" search; the next {@link #next} compares against it. */
    public long snapshot(Type type,Progress progress)throws IOException{
        reset();this.type=type;long total=0;
        ByteBuffer buffer=ByteBuffer.allocate(CHUNK).order(ByteOrder.LITTLE_ENDIAN);
        List<Region> regions=regions();int done=0;
        try(FileChannel channel=new RandomAccessFile(mem,"r").getChannel();
            DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(snapshotFile),1<<16))){
            for(Region r:regions){
                if(progress!=null){if(progress.cancelled())throw new InterruptedIOException("Search cancelled");progress.update("Remembering memory… "+(++done*100/Math.max(1,regions.size()))+"%");}
                for(long at=r.start;at<r.end;at+=CHUNK){
                    int length=(int)Math.min(CHUNK,r.end-at);
                    if(!read(channel,buffer,at,length))continue;
                    if(total+length>MAX_SNAPSHOT)throw new IOException("This game uses too much memory for an unknown-value search. Search for a number shown in the game instead.");
                    out.writeLong(at);out.writeInt(length);out.write(buffer.array(),0,length);total+=length;
                }
            }
        }catch(IOException error){snapshotFile.delete();throw error;}
        snapshot=true;return total;
    }

    /** Keeps only the results that match the comparison against their previous value. */
    public int next(Compare compare,double value,Progress progress)throws IOException{
        if(snapshot)return compareSnapshot(compare,value,progress);
        if(count==0)return 0;
        long[] kept=new long[count],values=new long[count];int n=0;
        ByteBuffer buffer=ByteBuffer.allocate(64*1024).order(ByteOrder.LITTLE_ENDIAN);
        try(FileChannel channel=new RandomAccessFile(mem,"r").getChannel()){
            int i=0;
            while(i<count){
                if(progress!=null&&progress.cancelled())throw new InterruptedIOException("Search cancelled");
                long base=addresses[i];int j=i;
                while(j<count&&addresses[j]+type.size-base<=buffer.capacity())j++;
                int length=(int)(addresses[j-1]+type.size-base);
                if(read(channel,buffer,base,length)){
                    for(int k=i;k<j;k++){
                        long raw=raw(buffer,(int)(addresses[k]-base),type);
                        if(matches(compare,raw,previous[k],type,value)){kept[n]=addresses[k];values[n]=raw;n++;}
                    }
                }
                i=j;
            }
        }
        addresses=kept;previous=values;count=n;return n;
    }

    private int compareSnapshot(Compare compare,double value,Progress progress)throws IOException{
        long[] found=new long[1024],values=new long[1024];int n=0;
        ByteBuffer now=ByteBuffer.allocate(CHUNK).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer then=ByteBuffer.allocate(CHUNK).order(ByteOrder.LITTLE_ENDIAN);
        long size=snapshotFile.length(),seen=0;
        try(FileChannel channel=new RandomAccessFile(mem,"r").getChannel();
            DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(snapshotFile),1<<16))){
            while(seen<size){
                long at=in.readLong();int length=in.readInt();in.readFully(then.array(),0,length);seen+=12+length;
                if(progress!=null){if(progress.cancelled())throw new InterruptedIOException("Search cancelled");progress.update("Comparing… "+(seen*100/Math.max(1,size))+"% · "+n+" found");}
                if(!read(channel,now,at,length))continue;
                for(int i=0;i+type.size<=length;i+=type.align){
                    long current=raw(now,i,type);
                    if(!matches(compare,current,raw(then,i,type),type,value))continue;
                    if(n==MAX_RESULTS)throw new IOException("Over "+MAX_RESULTS+" matches. Keep playing so the value changes, then search again (for example Decreased).");
                    if(n==found.length){found=Arrays.copyOf(found,n*2);values=Arrays.copyOf(values,n*2);}
                    found[n]=at+i;values[n]=current;n++;
                }
            }
        }finally{snapshotFile.delete();snapshot=false;}
        addresses=found;previous=values;count=n;return n;
    }

    /** Current value at an address, or null when it can no longer be read. */
    public Double read(long address,Type type){
        ByteBuffer buffer=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        try(FileChannel channel=new RandomAccessFile(mem,"r").getChannel()){
            if(!read(channel,buffer,address,type.size))return null;
            return toDouble(raw(buffer,0,type),type);
        }catch(IOException error){return null;}
    }

    /** Writes a value; only addresses inside a writable private mapping are accepted. */
    public boolean write(long address,Type type,double value){
        if(!writable(address,type.size))return false;
        ByteBuffer buffer=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        switch(type){
            case INT8:buffer.put((byte)(long)value);break;
            case INT16:buffer.putShort((short)(long)value);break;
            case INT32:buffer.putInt((int)(long)value);break;
            case INT64:buffer.putLong((long)value);break;
            case FLOAT:buffer.putFloat((float)value);break;
            default:buffer.putDouble(value);
        }
        buffer.flip();
        try(FileChannel channel=new RandomAccessFile(mem,"rw").getChannel()){
            while(buffer.hasRemaining())if(channel.write(buffer,address+buffer.position())<=0)return false;
            return true;
        }catch(IOException|RuntimeException error){return false;}
    }

    /**
     * Where an address lives: {library path, offset from the library's first mapping} for library
     * data, which stays put between launches, or null for heap memory, which can move.
     */
    public String[] locate(long address){
        try{
            Map<String,Long> bases=new HashMap<>();
            for(Region r:allRegions()){
                if(!r.path.startsWith("/"))continue;
                if(!bases.containsKey(r.path))bases.put(r.path,r.start-r.offset);
                if(address>=r.start&&address<r.end&&r.path.endsWith(".so"))return new String[]{r.path,Long.toString(address-bases.get(r.path))};
            }
        }catch(IOException ignored){}
        return null;
    }

    /** Address for a saved {library path, offset}, or -1 when the library isn't loaded. */
    public long resolve(String path,long offset){
        try{for(Region r:allRegions())if(r.path.equals(path))return r.start-r.offset+offset;}catch(IOException ignored){}
        return -1;
    }

    boolean writable(long address,int size){
        try{for(Region r:regions())if(address>=r.start&&address+size<=r.end)return true;}catch(IOException ignored){}
        return false;
    }

    List<Region> regions()throws IOException{
        List<Region> out=new ArrayList<>();
        try(BufferedReader reader=new BufferedReader(new FileReader(maps))){
            for(String line;(line=reader.readLine())!=null;){
                Region r=parse(line,true);if(r!=null)out.add(r);
            }
        }
        return out;
    }

    private List<Region> allRegions()throws IOException{
        List<Region> out=new ArrayList<>();
        try(BufferedReader reader=new BufferedReader(new FileReader(maps))){
            for(String line;(line=reader.readLine())!=null;){Region r=parse(line,false);if(r!=null)out.add(r);}
        }
        return out;
    }

    /** Whether a /proc/self/maps line is memory the search looks at. */
    public static boolean searchable(String mapsLine){return parse(mapsLine,true)!=null;}

    /** One /proc/self/maps line; with {@code searchable}, null unless it's memory worth searching. */
    static Region parse(String line,boolean searchable){
        String[] f=line.trim().split("\\s+",6);
        if(f.length<5)return null;
        int dash=f[0].indexOf('-');if(dash<0)return null;
        long start,end,offset;
        try{start=Long.parseLong(f[0].substring(0,dash),16);end=Long.parseLong(f[0].substring(dash+1),16);offset=Long.parseLong(f[2],16);}
        catch(NumberFormatException error){return null;}
        String path=f.length>5?f[5].trim():"";
        if(!searchable)return new Region(start,end,offset,path);
        String perms=f[1];
        if(perms.length()<4||perms.charAt(0)!='r'||perms.charAt(1)!='w'||perms.charAt(3)!='p')return null;
        if(end<=start||end-start>MAX_REGION)return null;
        boolean wanted=path.isEmpty()||path.equals("[heap]")||path.startsWith("[stack")||path.startsWith("[anon:")||path.endsWith(".so")||path.startsWith("/dev/ashmem/dalvik");
        if(!wanted)return null;
        // Runtime bookkeeping, not game values: searching it only slows things down.
        if(path.contains("card table")||path.contains("bitmap")||path.contains("mark stack")||path.contains("jit-")||path.contains("zygote")||path.contains("CompilerMetadata"))return null;
        return new Region(start,end,offset,path);
    }

    private static boolean read(FileChannel channel,ByteBuffer buffer,long at,int length){
        buffer.clear();buffer.limit(length);
        try{
            while(buffer.hasRemaining()){int n=channel.read(buffer,at+buffer.position());if(n<=0)return false;}
            return true;
        }catch(IOException|RuntimeException unreadable){return false;}
    }

    static long raw(ByteBuffer b,int i,Type type){
        switch(type){
            case INT8:return b.get(i);
            case INT16:return b.getShort(i);
            case INT32:case FLOAT:return b.getInt(i);
            default:return b.getLong(i);
        }
    }

    public static double toDouble(long raw,Type type){
        if(type==Type.FLOAT)return Float.intBitsToFloat((int)raw);
        if(type==Type.DOUBLE)return Double.longBitsToDouble(raw);
        return raw;
    }

    static boolean equalsValue(long raw,Type type,double value){
        if(!type.decimal())return raw==(long)value;
        double v=toDouble(raw,type);
        if(Double.isNaN(v)||Double.isInfinite(v))return false;
        // A whole number typed for a decimal matches what the game would show rounded (99.6 → 100).
        double tolerance=value==Math.rint(value)?0.5:Math.max(1e-4,Math.abs(value)*1e-4);
        return Math.abs(v-value)<=tolerance;
    }

    static boolean matches(Compare compare,long now,long before,Type type,double value){
        double a=toDouble(now,type),b=toDouble(before,type);
        if(type.decimal()&&(Double.isNaN(a)||Double.isInfinite(a)))return false;
        switch(compare){
            case EQUALS:return equalsValue(now,type,value);
            case CHANGED:return now!=before;
            case UNCHANGED:return now==before;
            case INCREASED:return a>b;
            case DECREASED:return a<b;
            case INCREASED_BY:return type.decimal()?Math.abs(a-b-value)<=Math.max(1e-4,Math.abs(value)*1e-3):now-before==(long)value;
            case DECREASED_BY:return type.decimal()?Math.abs(b-a-value)<=Math.max(1e-4,Math.abs(value)*1e-3):before-now==(long)value;
        }
        return false;
    }

    public static String format(double value,Type type){
        if(!type.decimal())return Long.toString((long)value);
        String s=String.format(Locale.US,"%.4f",value);
        s=s.replaceAll("0+$","");return s.endsWith(".")?s.substring(0,s.length()-1):s;
    }
}
