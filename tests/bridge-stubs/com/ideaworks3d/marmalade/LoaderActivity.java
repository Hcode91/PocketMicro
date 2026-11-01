package com.ideaworks3d.marmalade;
import android.app.Activity;import android.view.KeyEvent;import java.util.*;
/** Test-only callback recorder, not game/SDK code. */
public class LoaderActivity extends Activity {
 public String getPackageName(){return "org.example.marmalade";}
 public android.content.SharedPreferences getSharedPreferences(String name,int mode){throw new UnsupportedOperationException("Not used in key tests");}
 public final List<KeyEvent> events=new ArrayList<>();
 public boolean onKeyDown(int key,KeyEvent event){events.add(event);return true;}
 public boolean onKeyUp(int key,KeyEvent event){events.add(event);return true;}
}
