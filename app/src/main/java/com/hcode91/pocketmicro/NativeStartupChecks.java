package com.hcode91.pocketmicro;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;

/** Bounded, non-executing checks for native loading incompatibilities. */
public final class NativeStartupChecks {
    private NativeStartupChecks(){}
    public static String inspect(ZipFile apk)throws IOException {
        return inspect(apk,Collections.emptyMap());
    }
    public static String inspect(ZipFile apk,Map<String,byte[]> replacements)throws IOException {
        List<String> textRelocations=new ArrayList<>();long budget=32L*1024*1024;int inspected=0;boolean partial=false;
        Enumeration<? extends ZipEntry> entries=apk.entries();
        while(entries.hasMoreElements()){
            ZipEntry entry=entries.nextElement();
            if(!entry.getName().matches("lib/[^/]+/[^/]+\\.so"))continue;
            byte[] replacement=replacements.get(entry.getName());
            long size=replacement==null?entry.getSize():replacement.length;
            if(size<0||size>24L*1024*1024||size>budget||inspected>=128){partial=true;continue;}
            inspected++;budget-=size;
            if(replacement!=null){if(hasTextRelocations(replacement))textRelocations.add(entry.getName());continue;}
            try(InputStream input=apk.getInputStream(entry)){
                byte[] bytes=new byte[(int)size];int total=0;
                while(total<bytes.length){int n=input.read(bytes,total,bytes.length-total);if(n<0)break;total+=n;}
                if(total!=size||input.read()!=-1){partial=true;continue;}
                if(hasTextRelocations(bytes))textRelocations.add(entry.getName());
            }
        }
        if(textRelocations.isEmpty())return ""; // Absence is not an all-clear.
        StringBuilder out=new StringBuilder("Native startup warning\n\nLibraries declaring text relocations:\n");
        for(String name:textRelocations)out.append("  ").append(name).append('\n');
        out.append("\nAndroid rejects text relocations when a library is loaded by an app targeting API 23 or later. PocketOuya conversion raises old targets to at least API 23. If the game loads one of these libraries, conversion alone cannot make it launch. Touch controls and offline receipts do not fix native loading.\n\nThis is a static compatibility check, not a crash log or a full security scan; libraries for unused ABIs may never be loaded.");
        if(partial)out.append(" Some libraries were not inspected because of size/count limits.");
        return out.toString();
    }
    static boolean hasTextRelocations(byte[] data){
        try{
            if(data.length<64||data[0]!=0x7f||data[1]!='E'||data[2]!='L'||data[3]!='F'||data[5]!=1)return false;
            boolean wide=data[4]==2;if(!wide&&data[4]!=1)return false;
            ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            long table=wide?b.getLong(32):Integer.toUnsignedLong(b.getInt(28));
            int stride=Short.toUnsignedInt(b.getShort(wide?54:42)),count=Short.toUnsignedInt(b.getShort(wide?56:44));
            if(stride<(wide?56:32)||count>4096||!range(table,(long)stride*count,data.length))return false;
            for(int n=0;n<count;n++){
                int header=(int)table+n*stride;if(b.getInt(header)!=2)continue; // PT_DYNAMIC
                long start=wide?b.getLong(header+8):Integer.toUnsignedLong(b.getInt(header+4));
                long size=wide?b.getLong(header+32):Integer.toUnsignedLong(b.getInt(header+16));
                if(!range(start,size,data.length))return false;
                int unit=wide?16:8;
                for(long at=start;at+unit<=start+size;at+=unit){
                    long tag=wide?b.getLong((int)at):Integer.toUnsignedLong(b.getInt((int)at));
                    long value=wide?b.getLong((int)at+8):Integer.toUnsignedLong(b.getInt((int)at+4));
                    if(tag==0)break;
                    if(tag==22||(tag==30&&(value&4)!=0))return true; // DT_TEXTREL / DF_TEXTREL
                }
            }return false;
        }catch(IndexOutOfBoundsException|IllegalArgumentException error){return false;}
    }
    private static boolean range(long start,long size,int limit){return start>=0&&size>=0&&start<=limit&&size<=limit-start;}
}
