package com.hcode91.pocketmicro;

import java.util.*;

/** Shared held-key ownership for touchscreen, physical keys and joystick directions. UI thread only. */
final class EngineInput {
    interface Sink {void key(int action,int key);}
    private final Sink sink;
    private final Map<Integer,Set<Object>> held=new LinkedHashMap<>();
    EngineInput(Sink sink){this.sink=sink;}
    void hold(Object owner,int key,boolean down){
        if(owner==null)throw new IllegalArgumentException("Missing input owner");
        Set<Object> owners=held.get(key);boolean before=owners!=null&&!owners.isEmpty();
        if(down){if(owners==null){owners=new HashSet<>();held.put(key,owners);}owners.add(owner);}
        else if(owners!=null){owners.remove(owner);if(owners.isEmpty())held.remove(key);}
        boolean after=held.containsKey(key);if(before!=after)sink.key(after?0:1,key);
    }
    void release(Object owner){
        for(int key:new ArrayList<>(held.keySet()))hold(owner,key,false);
    }
    void releaseAll(){for(int key:new ArrayList<>(held.keySet())){held.remove(key);sink.key(1,key);}}
}
