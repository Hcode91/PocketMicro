package com.hcode91.pocketmicro;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.util.*;

/** GameMaker engine-owned pad: queues keys directly, without Android event injection. */
final class EngineTouchControls extends FrameLayout {
    private final EngineInput input;
    private final SharedPreferences prefs;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<View> panels=new ArrayList<>();
    private final List<Control> controls=new ArrayList<>();
    private final Set<Object> pulses=new HashSet<>();
    private boolean ready,shown=true;
    // These are runner input values, not synthetic Android keyboard events.
    // The inspected legacy runner maps Android DPAD_CENTER=23 to GM Space;
    // other GM keyboard values below are passed through its legacy key map.
    private static final String[] KEY_NAMES={"Z","X","C","Space","Shift","Ctrl","Enter","Escape","Tab","OUYA O","OUYA U","OUYA Y","OUYA A","L1","R1","Start","Select","Menu"};
    private static final int[] KEYS={90,88,67,23,16,17,66,27,9,96,99,100,97,102,103,108,109,82};
    private final class Control {
        final String id;final Button button;final Object owner=new Object();final Set<Integer> fingers=new HashSet<>();final int fallback;int key;
        Control(String id,int key,int fallback,boolean configurable){
            this.id=id;this.key=key;this.fallback=fallback;button=new Button(getContext());
            button.setAllCaps(false);button.setTextSize(12);button.setTextColor(Color.WHITE);button.setPadding(0,0,0,0);
            button.setFocusable(false);button.setFocusableInTouchMode(false);button.setContentDescription("Engine control "+id);
            GradientDrawable bg=new GradientDrawable();bg.setColor(0xff444444);bg.setCornerRadius(dp(12));bg.setStroke(dp(1),0xffcccccc);button.setBackground(bg);button.setAlpha(0.8f);
            label(configurable);controls.add(this);
            button.setOnTouchListener(new OnTouchListener(){
                public boolean onTouch(View view,MotionEvent event){
                    int action=event.getActionMasked(),index=event.getActionIndex();
                    if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN)fingers.add(event.getPointerId(index));
                    if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)fingers.remove(event.getPointerId(index));
                    if(action==MotionEvent.ACTION_CANCEL)fingers.clear();
                    boolean down=false;
                    for(int i=0;i<event.getPointerCount();i++)if(fingers.contains(event.getPointerId(i))&&event.getX(i)>=0&&event.getX(i)<view.getWidth()&&event.getY(i)>=0&&event.getY(i)<view.getHeight())down=true;
                    if(ready)input.hold(owner,Control.this.key,down);view.setPressed(down);view.setAlpha(down?1f:0.8f);return true;
                }
            });
            // Accessibility clicks produce a bounded pulse; touch events above are consumed.
            button.setOnClickListener(v->{if(ready){Object pulse=new Object();pulses.add(pulse);input.hold(pulse,this.key,true);handler.postDelayed(()->{input.release(pulse);pulses.remove(pulse);},100);}});
            button.setEnabled(false);
        }
        void label(boolean configurable){button.setText(id+(configurable?"\n"+name(key):""));}
    }
    EngineTouchControls(Context context,EngineInput input,String pkg){
        super(context);this.input=input;prefs=context.getSharedPreferences("engine_touch_"+pkg,Context.MODE_PRIVATE);
        setFocusable(false);setClickable(false);setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);setMotionEventSplittingEnabled(true);
        LinearLayout arrows=column();row(arrows,null,direction("↑",19),null);row(arrows,direction("←",21),null,direction("→",22));row(arrows,null,direction("↓",20),null);
        panel(arrows,Gravity.BOTTOM|Gravity.LEFT,14,18);
        LinearLayout face=column();row(face,action("Y",67),action("U",88));row(face,action("O",90),action("A",23));
        panel(face,Gravity.BOTTOM|Gravity.RIGHT,14,18);
        LinearLayout top=new LinearLayout(context);top.setMotionEventSplittingEnabled(true);
        row(top,action("L1",16),action("Select",9),action("Start",66),action("Menu",27),action("R1",17));
        panel(top,Gravity.TOP|Gravity.CENTER_HORIZONTAL,8,54);
        LinearLayout toolbar=new LinearLayout(context);
        Button hide=toolbarButton("Hide pad");hide.setOnClickListener(v->{release();shown=!shown;for(View p:panels)p.setVisibility(shown?VISIBLE:GONE);hide.setText(shown?"Hide pad":"Show pad");prefs.edit().putBoolean("shown",shown).apply();});
        Button mapping=toolbarButton("Controls");mapping.setOnClickListener(v->chooseControl());toolbar.addView(hide);toolbar.addView(mapping);
        LayoutParams tools=new LayoutParams(-2,dp(44),Gravity.TOP|Gravity.CENTER_HORIZONTAL);tools.topMargin=dp(4);addView(toolbar,tools);
        shown=prefs.getBoolean("shown",true);for(View p:panels)p.setVisibility(shown?VISIBLE:GONE);hide.setText(shown?"Hide pad":"Show pad");
    }
    private View direction(String label,int key){return new Control(label,key,-1,false).button;}
    private View action(String label,int fallback){int key=prefs.getInt("key_"+label,fallback);boolean valid=false;for(int value:KEYS)if(value==key)valid=true;if(!valid)key=fallback;return new Control(label,key,fallback,true).button;}
    private void chooseControl(){
        release();List<Control> editable=new ArrayList<>();for(Control c:controls)if(c.fallback>=0)editable.add(c);
        String[] names=new String[editable.size()];for(int i=0;i<names.length;i++)names[i]=editable.get(i).id+" → "+name(editable.get(i).key);
        new AlertDialog.Builder(getContext()).setTitle("GameMaker engine — button mapping").setItems(names,(dialog,which)->{
            Control c=editable.get(which);
            new AlertDialog.Builder(getContext()).setTitle(c.id+" sends which key?").setItems(KEY_NAMES,(d,index)->{release();c.key=KEYS[index];c.label(true);prefs.edit().putInt("key_"+c.id,c.key).apply();}).setNegativeButton("Cancel",null).show();
        }).setNeutralButton("Reset keys",(d,w)->{release();SharedPreferences.Editor edit=prefs.edit();for(Control c:editable){c.key=c.fallback;c.label(true);edit.remove("key_"+c.id);}edit.apply();}).setNegativeButton("Close",null).show();
    }
    void setInputEnabled(boolean enabled){if(!enabled)release();ready=enabled;for(Control c:controls)c.button.setEnabled(enabled);}
    void release(){handler.removeCallbacksAndMessages(null);for(Object pulse:pulses)input.release(pulse);pulses.clear();for(Control c:controls){input.release(c.owner);c.fingers.clear();c.button.setPressed(false);c.button.setAlpha(0.8f);}}
    private String name(int key){for(int i=0;i<KEYS.length;i++)if(KEYS[i]==key)return KEY_NAMES[i];return Integer.toString(key);}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private int cell(){return Math.max(dp(36),Math.min(dp(58),(getResources().getDisplayMetrics().widthPixels-dp(36))/6));}
    private LinearLayout column(){LinearLayout v=new LinearLayout(getContext());v.setOrientation(LinearLayout.VERTICAL);v.setMotionEventSplittingEnabled(true);return v;}
    private void row(LinearLayout parent,View... children){LinearLayout row=new LinearLayout(getContext());row.setMotionEventSplittingEnabled(true);for(View child:children)row.addView(child==null?new View(getContext()):child,new LinearLayout.LayoutParams(cell(),Math.min(dp(54),cell())));parent.addView(row);}
    private void panel(View view,int gravity,int horizontal,int vertical){LayoutParams lp=new LayoutParams(-2,-2,gravity);lp.setMargins(dp(horizontal),dp(vertical),dp(horizontal),dp(vertical));panels.add(view);addView(view,lp);}
    private Button toolbarButton(String text){Button b=new Button(getContext());b.setText(text);b.setAllCaps(false);b.setTextSize(12);b.setFocusable(false);b.setFocusableInTouchMode(false);return b;}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(!focused)release();}
    @Override protected void onDetachedFromWindow(){release();super.onDetachedFromWindow();}
}
