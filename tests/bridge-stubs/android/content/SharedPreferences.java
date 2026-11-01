package android.content;
import java.util.Map;
public interface SharedPreferences {
 boolean contains(String key);Map<String,?> getAll();Editor edit();
 interface Editor{Editor putLong(String key,long value);Editor remove(String key);boolean commit();void apply();}
}
