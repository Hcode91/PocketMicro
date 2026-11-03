package io.github.hcode91.pocketouya.bridge;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.util.*;

/** Game-owned view: no system overlay, accessibility service, or input-injection permission. */
public final class TouchControls extends FrameLayout {
    private static final Map<Activity,WeakReference<TouchControls>> installed=new WeakHashMap<>();
    private static final Map<android.app.Application,Boolean> watched=new WeakHashMap<>();
    private static final Set<Activity> legacy=Collections.newSetFromMap(new WeakHashMap<Activity,Boolean>());
    public static void watchLegacy(Activity activity){legacy.add(activity);watch(activity);}
    public static void installLegacy(Activity activity){legacy.add(activity);install(activity);}
    private static final Set<Activity> resumed=Collections.newSetFromMap(new WeakHashMap<Activity,Boolean>());
    public static void watch(Activity activity){
        try{watchApplication(activity);}catch(RuntimeException error){Log.e("PocketOuyaControls","Unable to watch game activities",error);}
    }
    private static void watchApplication(Activity activity){
        final String pkg=activity.getPackageName();android.app.Application app=activity.getApplication();
        if(watched.containsKey(app))return;
        app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity current,android.os.Bundle state){}
            public void onActivityStarted(Activity current){}
            public void onActivityResumed(Activity current){
                resumed.add(current);
                String name=current.getClass().getName();
                boolean eligible=name.startsWith(pkg+".")||name.startsWith("com.unity3d.player.")||name.startsWith("com.ideaworks3d.marmalade.")||name.startsWith("org.cocos2dx.")||name.startsWith("com.badlogic.gdx.backends.android.")||name.startsWith("org.libsdl.app.")||name.startsWith("com.yoyogames.");
                if(!eligible)return;
                final WeakReference<Activity> ref=new WeakReference<>(current);
                try{current.getWindow().getDecorView().post(()->{Activity next=ref.get();if(next!=null&&resumed.contains(next)&&!next.isFinishing()&&!next.isDestroyed())install(next);});}
                catch(RuntimeException error){Log.e("PocketOuyaControls","Unable to attach controls to this screen",error);}
            }
            public void onActivityPaused(Activity current){resumed.remove(current);release(current);}
            public void onActivityStopped(Activity current){release(current);}
            public void onActivitySaveInstanceState(Activity current,android.os.Bundle state){}
            public void onActivityDestroyed(Activity current){release(current);installed.remove(current);legacy.remove(current);}
        });
        watched.put(app,true);
    }
    private VirtualPad pad;
    private LookStick look;
    private final VirtualPad.Sink nativeSink;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<View> panels=new ArrayList<>();
    private boolean usable=true,shown=true;

    public static void install(Activity activity){
        try{
            watch(activity);
            WeakReference<TouchControls> old=installed.get(activity);
            TouchControls existing=old==null?null:old.get();
            if(existing!=null && existing.getParent()!=null)return;
            TouchControls controls=new TouchControls(activity);
            activity.addContentView(controls,new ViewGroup.LayoutParams(-1,-1));
            installed.put(activity,new WeakReference<>(controls));
        }catch(Exception|LinkageError error){Log.e("PocketOuyaControls","Touch controller unavailable",error);
            Toast.makeText(activity,"Touch controller could not start. See PocketOuyaControls log.",Toast.LENGTH_LONG).show();}
    }
    public static void release(Activity activity){
        WeakReference<TouchControls> entry=installed.get(activity);
        TouchControls controls=entry==null?null:entry.get();
        if(controls!=null)controls.releasePad();
    }
    private TouchControls(final Activity activity)throws Exception{
        super(activity);
        setMotionEventSplittingEnabled(true);setFocusable(false);setClickable(false);
        setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        VirtualPad.Sink detected;
        try{detected=legacy.contains(activity)?new LegacyOuyaPadSink(activity):new OuyaPadSink(activity);}catch(Exception|LinkageError unavailable){
            try{detected=new GameMakerPadSink(activity.getClassLoader(),new AndroidPadSink(activity,false));}catch(Exception|LinkageError notGameMaker){
            try{detected=new Ue3PadSink(activity);}catch(Exception|LinkageError notUnreal){
            try{detected=new MarmaladePadSink(activity,false);}catch(Exception|LinkageError absent){detected=null;}}}
        }
        nativeSink=detected;
        android.content.SharedPreferences settings=activity.getSharedPreferences("pocketouya_touch_settings_v1",Activity.MODE_PRIVATE);
        int mode=settings.getInt("mode",0);
        if(nativeSink instanceof LegacyOuyaPadSink&&!settings.getBoolean("legacy_json_v1",false)){mode=0;settings.edit().putInt("mode",0).putBoolean("legacy_json_v1",true).apply();}
        Log.i("PocketOuyaControls","Input route: "+(nativeSink instanceof LegacyOuyaPadSink?"legacy OUYA JSON controller":nativeSink instanceof OuyaPadSink?"OUYA native":nativeSink instanceof GameMakerPadSink?"GameMaker controller":nativeSink instanceof Ue3PadSink?"Unreal Engine 3 controller":nativeSink instanceof MarmaladePadSink?"Marmalade":"Android fallback")+"; mode="+mode);
        pad=createPad(activity,mode);
        LinearLayout directions=column();
        row(directions,null,button("↑",VirtualPad.UP,0xff555555),null);
        row(directions,button("←",VirtualPad.LEFT,0xff555555),null,button("→",VirtualPad.RIGHT,0xff555555));
        row(directions,null,button("↓",VirtualPad.DOWN,0xff555555),null);
        addPanel(directions,Gravity.BOTTOM|Gravity.LEFT,16,22);
        LinearLayout faces=column();
        row(faces,null,button("Y",VirtualPad.Y,0xffa98413),null);
        row(faces,button("U",VirtualPad.U,0xff1479ad),null,button("A",VirtualPad.A,0xffa73b36));
        row(faces,null,button("O",VirtualPad.O,0xff318b42),null);
        addPanel(faces,Gravity.BOTTOM|Gravity.RIGHT,16,22);
        if(!(nativeSink instanceof MarmaladePadSink)&&getResources().getDisplayMetrics().widthPixels>=dp(500)){look=new LookStick(activity);FrameLayout.LayoutParams lookLayout=new FrameLayout.LayoutParams(dp(100),dp(100),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);lookLayout.setMargins(0,0,0,dp(22));panels.add(look);addView(look,lookLayout);}
        LinearLayout top=new LinearLayout(activity);top.setGravity(Gravity.CENTER);
        top.addView(button("L1",VirtualPad.L1,0xff555555));
        top.addView(button("Select",VirtualPad.SELECT,0xff555555));
        top.addView(button("Menu",VirtualPad.MENU,0xff555555));
        top.addView(button("Start",VirtualPad.START,0xff555555));
        top.addView(button("R1",VirtualPad.R1,0xff555555));
        addPanel(top,Gravity.TOP|Gravity.CENTER_HORIZONTAL,0,getResources().getDisplayMetrics().widthPixels<dp(560)?58:10);
        Button toggle=new Button(activity);toggle.setText("Hide pad");toggle.setAllCaps(false);toggle.setTextSize(11);
        toggle.setFocusable(false);toggle.setFocusableInTouchMode(false);
        FrameLayout.LayoutParams toggleLayout=new FrameLayout.LayoutParams(dp(92),dp(44),Gravity.TOP|Gravity.LEFT);
        toggleLayout.setMargins(dp(8),dp(8),0,0);addView(toggle,toggleLayout);
        Button input=new Button(activity);input.setText("Input");input.setAllCaps(false);input.setTextSize(11);
        input.setFocusable(false);input.setFocusableInTouchMode(false);
        FrameLayout.LayoutParams inputLayout=new FrameLayout.LayoutParams(dp(78),dp(44),Gravity.TOP|Gravity.LEFT);
        inputLayout.setMargins(dp(106),dp(8),0,0);addView(input,inputLayout);
        input.setOnClickListener(v->{
            releasePad();
            String[] options=new String[]{nativeSink instanceof LegacyOuyaPadSink?"Auto (legacy OUYA JSON)":nativeSink instanceof GameMakerPadSink?"Auto (GameMaker controller)":nativeSink instanceof Ue3PadSink?"Auto (Unreal Engine 3 controller)":nativeSink instanceof MarmaladePadSink?"Auto (Marmalade keys)":nativeSink==null?"Auto (Android gamepad)":"Auto (OUYA native)","Android gamepad","Keyboard"};
            new android.app.AlertDialog.Builder(activity).setTitle("Game input mode").setItems(options,(dialog,which)->{
                releasePad();usable=true;
                try{pad=createPad(activity,which);}catch(Exception error){usable=false;Log.e("PocketOuyaControls","Input mode unavailable",error);return;}
                activity.getSharedPreferences("pocketouya_touch_settings_v1",Activity.MODE_PRIVATE).edit().putInt("mode",which).apply();
                Toast.makeText(activity,"Input: "+options[which],Toast.LENGTH_SHORT).show();
            }).show();
        });
        toggle.setOnClickListener(v->{releasePad();shown=!shown;for(View panel:panels)panel.setVisibility(shown?View.VISIBLE:View.GONE);toggle.setText(shown?"Hide pad":"Show pad");});
    }
    private VirtualPad createPad(Activity activity,int mode)throws Exception{
        if(mode==0&&nativeSink!=null)return new VirtualPad(nativeSink);
        if(mode==2&&nativeSink instanceof MarmaladePadSink)return new VirtualPad(new MarmaladePadSink(activity,true));
        return new VirtualPad(new AndroidPadSink(activity,mode==2));
    }
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private LinearLayout column(){LinearLayout layout=new LinearLayout(getContext());layout.setOrientation(LinearLayout.VERTICAL);layout.setMotionEventSplittingEnabled(true);return layout;}
    private int cellWidth(){return Math.max(dp(36),Math.min(dp(58),(getResources().getDisplayMetrics().widthPixels-dp(48))/6));}
    private void row(LinearLayout column,View left,View middle,View right){
        LinearLayout row=new LinearLayout(getContext());row.setMotionEventSplittingEnabled(true);
        for(View view:new View[]{left,middle,right})row.addView(view==null?new View(getContext()):view,new LinearLayout.LayoutParams(cellWidth(),Math.min(dp(54),cellWidth())));
        column.addView(row);
    }
    private void addPanel(View panel,int gravity,int horizontal,int bottomOrTop){
        FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(-2,-2,gravity);
        layout.setMargins(dp(horizontal),dp(bottomOrTop),dp(horizontal),dp(bottomOrTop));
        panels.add(panel);addView(panel,layout);
    }
    private Button button(String text,final int key,int color){
        Button button=new Button(getContext());button.setText(text);button.setTextColor(Color.WHITE);
        button.setFocusable(false);button.setFocusableInTouchMode(false);
        button.setAllCaps(false);button.setTextSize(12);button.setPadding(0,0,0,0);
        button.setContentDescription("OUYA "+text.replace('\n',' '));
        GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(14));bg.setStroke(dp(1),0xffcccccc);
        button.setBackground(bg);button.setAlpha(0.8f);
        final Object touchOwner=new Object();
        final boolean[] finishingTouch={false};
        button.setOnClickListener(v->{
            if(finishingTouch[0]||!usable)return;
            // Accessibility/programmatic clicks get a complete press pulse.
            final Object clickOwner=new Object();hold(key,clickOwner,true);
            handler.postDelayed(()->hold(key,clickOwner,false),100);
        });
        button.setOnTouchListener(new OnTouchListener(){
            final Set<Integer> fingers=new HashSet<>();
            public boolean onTouch(View view,android.view.MotionEvent event){
                int action=event.getActionMasked(),index=event.getActionIndex();
                if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN)fingers.add(event.getPointerId(index));
                if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP)fingers.remove(event.getPointerId(index));
                if(action==MotionEvent.ACTION_CANCEL)fingers.clear();
                boolean inside=false;
                for(int i=0;i<event.getPointerCount();i++)if(fingers.contains(event.getPointerId(i))
                        && event.getX(i)>=0 && event.getX(i)<view.getWidth() && event.getY(i)>=0 && event.getY(i)<view.getHeight())inside=true;
                hold(key,touchOwner,inside);view.setPressed(inside);view.setAlpha(inside?1f:0.8f);
                if(action==MotionEvent.ACTION_UP){finishingTouch[0]=true;try{view.performClick();}finally{finishingTouch[0]=false;}}
                return true;
            }
        });
        return button;
    }
    private final class LookStick extends View {
        private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private int pointer=-1;private float x,y;
        LookStick(Activity activity){super(activity);setFocusable(false);setContentDescription("Right stick: drag to look");}
        void resetPointer(){pointer=-1;x=0;y=0;invalidate();}
        @Override protected void onDraw(android.graphics.Canvas canvas){super.onDraw(canvas);float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(cx,cy)-dp(5);
            paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(0x99ffffff);canvas.drawCircle(cx,cy,r,paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);paint.setColor(0x66555555);canvas.drawCircle(cx,cy,r,paint);paint.setColor(0xaaffffff);canvas.drawCircle(cx+x*r*0.65f,cy+y*r*0.65f,r*0.25f,paint);
            paint.setColor(Color.WHITE);paint.setTextSize(dp(12));paint.setTextAlign(android.graphics.Paint.Align.CENTER);canvas.drawText("Look / RS",cx,cy-r*0.5f,paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event){int action=event.getActionMasked(),index=event.getActionIndex();
            if(action==MotionEvent.ACTION_DOWN&&pointer<0)pointer=event.getPointerId(index);
            if(action==MotionEvent.ACTION_CANCEL||((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)&&event.getPointerId(index)==pointer)){resetPointer();stick(0,0);return true;}
            int at=event.findPointerIndex(pointer);if(at>=0){float radius=Math.max(1,Math.min(getWidth(),getHeight())/2f-dp(5));x=(event.getX(at)-getWidth()/2f)/radius;y=(event.getY(at)-getHeight()/2f)/radius;double length=Math.hypot(x,y);if(length>1){x/=length;y/=length;}stick(x,y);invalidate();}return true;
        }
    }
    private void stick(float x,float y){if(!usable)return;try{pad.rightStick(x,y);}catch(RuntimeException error){usable=false;releasePad();Log.e("PocketOuyaControls","Right stick input disabled after input error",error);}}
    private void hold(int key,Object owner,boolean down){
        if(!usable)return;
        try{pad.hold(key,owner,down);}catch(RuntimeException error){usable=false;releasePad();Log.e("PocketOuyaControls","Touch input disabled after native input error",error);}
    }
    private void releasePad(){handler.removeCallbacksAndMessages(null);if(look!=null)look.resetPointer();try{pad.releaseAll();}catch(RuntimeException error){Log.e("PocketOuyaControls","Unable to release virtual controller",error);}}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(!focused)releasePad();}
    @Override protected void onDetachedFromWindow(){releasePad();super.onDetachedFromWindow();}
}
