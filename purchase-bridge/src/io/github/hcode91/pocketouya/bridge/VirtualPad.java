package io.github.hcode91.pocketouya.bridge;

import java.util.*;

/** Per-overlay ownership prevents a second finger/repeat from releasing a held key. */
public final class VirtualPad {
    public static final int UP=19,DOWN=20,LEFT=21,RIGHT=22,O=96,U=99,Y=100,A=97,L1=102,R1=103,MENU=82,START=108,SELECT=109;
    public interface Sink {void key(int key,int action);void axis(int axis,float value);}
    private final Sink sink;
    private final Map<Integer,Set<Object>> held=new LinkedHashMap<>();
    private float x,y,rx,ry;
    public VirtualPad(Sink sink){this.sink=sink;}
    public void hold(int key,Object owner,boolean down){
        if(owner==null || !valid(key))throw new IllegalArgumentException("Unknown touch control");
        Set<Object> owners=held.get(key);boolean before=owners!=null&&!owners.isEmpty();
        if(down){if(owners==null){owners=new HashSet<>();held.put(key,owners);}owners.add(owner);}
        else if(owners!=null){owners.remove(owner);if(owners.isEmpty())held.remove(key);}
        boolean after=held.containsKey(key);
        if(before!=after)sink.key(key,after?0:1);
        float nextX=(held.containsKey(RIGHT)?1:0)-(held.containsKey(LEFT)?1:0);
        float nextY=(held.containsKey(DOWN)?1:0)-(held.containsKey(UP)?1:0);
        if(nextX!=x){x=nextX;sink.axis(0,x);}
        if(nextY!=y){y=nextY;sink.axis(1,y);}
    }
    /** Independent analog look stick: Android/OUYA right-stick axes Z and RZ. */
    public void rightStick(float nextX,float nextY){
        if(Float.isNaN(nextX)||Float.isInfinite(nextX)||Float.isNaN(nextY)||Float.isInfinite(nextY))throw new IllegalArgumentException("Invalid stick position");
        double length=Math.hypot(nextX,nextY);if(length>1){nextX=(float)(nextX/length);nextY=(float)(nextY/length);}else if(length<0.15){nextX=0;nextY=0;}
        boolean changedX=nextX!=rx,changedY=nextY!=ry;rx=nextX;ry=nextY;
        if(changedX)sink.axis(11,rx);if(changedY)sink.axis(14,ry);
    }
    public void releaseAll(){
        List<Integer> keys=new ArrayList<>(held.keySet());float oldRX=rx,oldRY=ry;held.clear();x=0;y=0;rx=0;ry=0;
        RuntimeException failure=null;
        for(int key:keys)try{sink.key(key,1);}catch(RuntimeException error){failure=error;}
        try{sink.axis(0,0);}catch(RuntimeException error){failure=error;}
        try{sink.axis(1,0);}catch(RuntimeException error){failure=error;}
        if(oldRX!=0)try{sink.axis(11,0);}catch(RuntimeException error){failure=error;}
        if(oldRY!=0)try{sink.axis(14,0);}catch(RuntimeException error){failure=error;}
        if(failure!=null)throw failure;
    }
    private static boolean valid(int key){return key==UP||key==DOWN||key==LEFT||key==RIGHT||key==O||key==U||key==Y||key==A||key==L1||key==R1||key==MENU||key==START||key==SELECT;}
}
