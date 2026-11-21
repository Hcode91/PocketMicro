package com.hcode91.pocketmicro;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Builds a small tombstone record by hand and checks the decoded text. */
public final class TombstoneTextTest {
    private static final class Proto {
        final ByteArrayOutputStream out=new ByteArrayOutputStream();
        Proto varint(long v){while((v&~0x7fL)!=0){out.write((int)((v&0x7f)|0x80));v>>>=7;}out.write((int)v);return this;}
        Proto num(int field,long v){return varint((long)field<<3).varint(v);}
        Proto bytes(int field,byte[] b){varint(((long)field<<3)|2).varint(b.length);out.write(b,0,b.length);return this;}
        Proto str(int field,String s){return bytes(field,s.getBytes(StandardCharsets.UTF_8));}
        Proto msg(int field,Proto p){return bytes(field,p.out.toByteArray());}
        byte[] get(){return out.toByteArray();}
    }
    static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}

    public static void main(String[] args){
        Proto crashing=new Proto().num(1,32490).str(2,"UnityMain")
            .msg(3,new Proto().str(1,"r0").num(2,0x40))
            .msg(3,new Proto().str(1,"r4").num(2,0x34))
            .msg(4,new Proto().num(1,0xae11c).num(2,0xe662f11cL).str(4,"pthread_mutex_lock").num(5,4).str(6,"/apex/com.android.runtime/lib/bionic/libc.so").str(8,"4050551115b03dff"))
            .msg(4,new Proto().num(1,0x78459).str(6,"/data/app/game/lib/arm/libgfxunity3d.so"))
            .msg(5,new Proto().str(1,"r1").str(2,"[anon:stack]").num(3,0xdae8c5f0L).bytes(4,new byte[]{0x41,0x42,0x43,0x44,1,0,0,0,(byte)0xf0,(byte)0xc5,(byte)0xe8,(byte)0xda,0,0,0,0}));
        Proto other=new Proto().num(1,32491).str(2,"AudioTrack")
            .msg(5,new Proto().str(1,"r9").num(3,0x1000).bytes(4,new byte[16]));
        byte[] record=new Proto().num(1,0).str(2,"samsung/dm1q:14").str(4,"2026-11-03 15:36:06").num(5,32461).num(6,32490).num(7,10736)
            .str(9,"com.airtightgames.soulfjord")
            .msg(10,new Proto().num(1,11).str(2,"SIGSEGV").num(3,1).str(4,"SEGV_MAPERR").num(8,1).num(9,0x40))
            .msg(15,new Proto().str(1,"null pointer dereference"))
            .msg(16,new Proto().num(1,32491).msg(2,other))
            .msg(16,new Proto().num(1,32490).msg(2,crashing))
            .msg(17,new Proto().num(1,0xd5200000L).num(2,0xd5300000L).num(4,1).num(6,1).str(7,"/data/app/game/lib/arm/libgfxunity3d.so"))
            .msg(18,new Proto().str(1,"main"))  // log buffer: must be left out
            .num(20,6).get();

        String text=TombstoneText.format(record);
        check(text!=null,"decodes");
        check(text.contains("ABI: 'arm'"),"abi");
        check(text.contains("pid: 32461, tid: 32490, name: UnityMain"),"crashing thread heading");
        check(text.contains("signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 00000040"),"signal");
        check(text.contains("Cause: null pointer dereference"),"cause");
        check(text.contains("r0  00000040")&&text.contains("r4  00000034"),"registers");
        check(text.contains("#00 pc 000ae11c  /apex/com.android.runtime/lib/bionic/libc.so (pthread_mutex_lock+4) (BuildId: 4050551115b03dff)"),"frame 0");
        check(text.contains("#01 pc 00078459  /data/app/game/lib/arm/libgfxunity3d.so"),"frame 1");
        check(text.contains("memory near r1 ([anon:stack]):"),"dump heading");
        check(text.contains("dae8c5f0 44434241 00000001 dae8c5f0 00000000  ABCD"),"dump line");
        check(!text.contains("memory near r9"),"other threads' memory is not dumped");
        check(text.indexOf("tid: 32490")<text.indexOf("tid: 32491"),"crashing thread first");
        check(text.contains("d5200000-d52fffff r-x 0 100000  /data/app/game/lib/arm/libgfxunity3d.so"),"memory map");
        check(text.contains("Process uptime: 6s"),"uptime");

        check(TombstoneText.format("----- pid 1 at 2026 -----\nanr trace".getBytes(StandardCharsets.UTF_8))==null,"text ANR traces are not tombstones");
        check(TombstoneText.format(new byte[]{(byte)0xff,(byte)0xff})==null,"garbage rejected");
        check(TombstoneText.format(new byte[0])==null,"empty rejected");
        check(TombstoneText.logText(record).startsWith("Full crash dump"),"log text uses the decoder");
        check(TombstoneText.logText("anr trace".getBytes(StandardCharsets.UTF_8)).equals("anr trace"),"text traces kept");
        check(TombstoneText.logText(new byte[]{0,1,2}).contains("base64"),"undecodable binary kept as base64");
        check(TombstoneText.logText(null).contains("no crash dump"),"missing dump explained");
        System.out.println("TombstoneTextTest passed");
    }
}
