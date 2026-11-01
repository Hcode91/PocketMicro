package android.os;
public final class Bundle {
 private final java.util.Map<String,Object> values=new java.util.HashMap<>();
 public void putString(String key,String value){values.put(key,value);}
 public void putStringArray(String key,String[] value){values.put(key,value);}
 public String getString(String key){return (String)values.get(key);}
 public String[] getStringArray(String key){return (String[])values.get(key);}
 public boolean containsKey(String key){return values.containsKey(key);}
}
