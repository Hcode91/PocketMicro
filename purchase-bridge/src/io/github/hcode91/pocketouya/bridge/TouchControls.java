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
    private Stick leftStick,rightStick;
    private float scale=1f;
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
            try{detected=new AirPadSink(activity,new AndroidPadSink(activity,false));}catch(Exception|LinkageError notAir){
            try{detected=new MarmaladePadSink(activity,false);}catch(Exception|LinkageError absent){detected=null;}}}}
        }
        nativeSink=detected;
        android.content.SharedPreferences settings=activity.getSharedPreferences("pocketouya_touch_settings_v1",Activity.MODE_PRIVATE);
        int mode=settings.getInt("mode",0);
        if(nativeSink instanceof LegacyOuyaPadSink&&!settings.getBoolean("legacy_json_v1",false)){mode=0;settings.edit().putInt("mode",0).putBoolean("legacy_json_v1",true).apply();}
        Log.i("PocketOuyaControls","Input route: "+(nativeSink instanceof LegacyOuyaPadSink?"legacy OUYA JSON controller":nativeSink instanceof OuyaPadSink?"OUYA native":nativeSink instanceof GameMakerPadSink?"GameMaker controller":nativeSink instanceof Ue3PadSink?"Unreal Engine 3 controller":nativeSink instanceof AirPadSink?"Adobe AIR GameInput controller":nativeSink instanceof MarmaladePadSink?"Marmalade":"Android fallback")+"; mode="+mode);
        pad=createPad(activity,mode);
        // OUYA controller layout for every game: left stick above the d-pad on the left, O/U/Y/A above
        // the right stick on the right, L1/L2 and R1/R2 on the shoulders, Menu in the middle.
        int width=getResources().getDisplayMetrics().widthPixels;
        int height=getResources().getDisplayMetrics().heightPixels;
        scale=Math.max(0.6f,Math.min(1f,Math.min(width/(float)dp(640),height/(float)dp(360))));
        int stickSize=size(116),cluster=3*size(44),inner=size(12)+stickSize+size(8),lifted=size(10)+cluster;
        boolean analog=!(nativeSink instanceof MarmaladePadSink);
        leftStick=new Stick(activity,"LS",true);
        addStick(leftStick,Gravity.BOTTOM|Gravity.LEFT,size(12),lifted);
        LinearLayout directions=column();
        row(directions,null,button("↑",VirtualPad.UP,0xff555555),null);
        row(directions,button("←",VirtualPad.LEFT,0xff555555),null,button("→",VirtualPad.RIGHT,0xff555555));
        row(directions,null,button("↓",VirtualPad.DOWN,0xff555555),null);
        addPanel(directions,Gravity.BOTTOM|Gravity.LEFT,inner,size(10));
        LinearLayout faces=column();
        row(faces,null,button("Y",VirtualPad.Y,0xffa98413),null);
        row(faces,button("U",VirtualPad.U,0xff1479ad),null,button("A",VirtualPad.A,0xffa73b36));
        row(faces,null,button("O",VirtualPad.O,0xff318b42),null);
        addPanel(faces,Gravity.BOTTOM|Gravity.RIGHT,size(12),lifted-size(12));
        if(analog){rightStick=new Stick(activity,"RS",false);addStick(rightStick,Gravity.BOTTOM|Gravity.RIGHT,inner,size(10));}
        int shoulderTop=dp(56);
        LinearLayout leftShoulder=new LinearLayout(activity);leftShoulder.setMotionEventSplittingEnabled(true);
        leftShoulder.addView(shoulder("L2",VirtualPad.L2));leftShoulder.addView(shoulder("L1",VirtualPad.L1));
        addPanel(leftShoulder,Gravity.TOP|Gravity.LEFT,size(12),shoulderTop);
        LinearLayout rightShoulder=new LinearLayout(activity);rightShoulder.setMotionEventSplittingEnabled(true);
        rightShoulder.addView(shoulder("R1",VirtualPad.R1));rightShoulder.addView(shoulder("R2",VirtualPad.R2));
        addPanel(rightShoulder,Gravity.TOP|Gravity.RIGHT,size(12),shoulderTop);
        // Select/Start are not on the OUYA pad but some ported games still ask for them.
        LinearLayout middle=new LinearLayout(activity);middle.setMotionEventSplittingEnabled(true);
        middle.addView(small("Select",VirtualPad.SELECT));middle.addView(small("Start",VirtualPad.START));
        addPanel(middle,Gravity.TOP|Gravity.CENTER_HORIZONTAL,0,dp(width<dp(560)?58:10));
        Button menu=button("Menu",VirtualPad.MENU,0xff2b2b2b);
        ((GradientDrawable)menu.getBackground()).setCornerRadius(size(26));
        FrameLayout.LayoutParams menuLayout=new FrameLayout.LayoutParams(size(64),size(44),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        menuLayout.setMargins(0,0,0,size(18));panels.add(menu);addView(menu,menuLayout);
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
            String[] options=new String[]{nativeSink instanceof LegacyOuyaPadSink?"Auto (legacy OUYA JSON)":nativeSink instanceof GameMakerPadSink?"Auto (GameMaker controller)":nativeSink instanceof Ue3PadSink?"Auto (Unreal Engine 3 controller)":nativeSink instanceof AirPadSink?"Auto (Adobe AIR controller)":nativeSink instanceof MarmaladePadSink?"Auto (Marmalade keys)":nativeSink==null?"Auto (virtual controller via Shizuku)":"Auto (OUYA native)","Android gamepad","Keyboard","Virtual controller (Shizuku)"};
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
        // Shizuku virtual controller: a real "OUYA Game Controller" input device; falls back to the in-game route until connected.
        if(mode==3)return new VirtualPad(new VirtualDeviceSink(activity,nativeSink!=null?nativeSink:new AndroidPadSink(activity,false)));
        if(mode==0&&nativeSink!=null)return new VirtualPad(nativeSink);
        if(mode==0)return new VirtualPad(new VirtualDeviceSink(activity,new AndroidPadSink(activity,false)));
        if(mode==2&&nativeSink instanceof MarmaladePadSink)return new VirtualPad(new MarmaladePadSink(activity,true));
        return new VirtualPad(new AndroidPadSink(activity,mode==2));
    }
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private LinearLayout column(){LinearLayout layout=new LinearLayout(getContext());layout.setOrientation(LinearLayout.VERTICAL);layout.setMotionEventSplittingEnabled(true);return layout;}
    private int size(int value){return (int)(value*scale*getResources().getDisplayMetrics().density+0.5f);}
    private int cellWidth(){return size(44);}
    private void addStick(Stick stick,int gravity,int horizontal,int bottom){
        FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(size(116),size(116),gravity);
        layout.setMargins(horizontal,0,horizontal,bottom);panels.add(stick);addView(stick,layout);
    }
    private Button shoulder(String text,int key){
        Button button=button(text,key,0xff3a3a3a);
        button.setLayoutParams(new LinearLayout.LayoutParams(size(58),size(40)));return button;
    }
    private Button small(String text,int key){
        Button button=button(text,key,0xff444444);button.setTextSize(10);
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(size(56),size(34));layout.setMargins(dp(3),0,dp(3),0);
        button.setLayoutParams(layout);return button;
    }
    private void row(LinearLayout column,View left,View middle,View right){
        LinearLayout row=new LinearLayout(getContext());row.setMotionEventSplittingEnabled(true);
        for(View view:new View[]{left,middle,right})row.addView(view==null?new View(getContext()):view,new LinearLayout.LayoutParams(cellWidth(),Math.min(dp(54),cellWidth())));
        column.addView(row);
    }
    private void addPanel(View panel,int gravity,int horizontal,int bottomOrTop){
        FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(-2,-2,gravity);
        layout.setMargins(horizontal,bottomOrTop,horizontal,bottomOrTop);
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
    /** Analog stick. Left drives X/Y (or the d-pad keys on routes without analog input); right drives Z/RZ. */
    private final class Stick extends View {
        private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final String label;private final boolean left;
        private int pointer=-1;private float x,y;
        Stick(Activity activity,String label,boolean left){super(activity);this.label=label;this.left=left;setFocusable(false);setContentDescription(left?"Left stick":"Right stick");}
        void resetPointer(){pointer=-1;x=0;y=0;invalidate();}
        @Override protected void onDraw(android.graphics.Canvas canvas){super.onDraw(canvas);float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(cx,cy)-dp(5);
            paint.setStyle(android.graphics.Paint.Style.FILL);paint.setColor(0x66333333);canvas.drawCircle(cx,cy,r,paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(0x99ffffff);canvas.drawCircle(cx,cy,r,paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);paint.setColor(0xbb555555);canvas.drawCircle(cx+x*r*0.6f,cy+y*r*0.6f,r*0.4f,paint);
            paint.setColor(Color.WHITE);paint.setTextSize(dp(11));paint.setTextAlign(android.graphics.Paint.Align.CENTER);canvas.drawText(label,cx+x*r*0.6f,cy+y*r*0.6f+dp(4),paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event){int action=event.getActionMasked(),index=event.getActionIndex();
            if(action==MotionEvent.ACTION_DOWN&&pointer<0)pointer=event.getPointerId(index);
            if(action==MotionEvent.ACTION_CANCEL||((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)&&event.getPointerId(index)==pointer)){resetPointer();stick(this,0,0);return true;}
            int at=event.findPointerIndex(pointer);if(at>=0){float radius=Math.max(1,Math.min(getWidth(),getHeight())/2f-dp(5));x=(event.getX(at)-getWidth()/2f)/radius;y=(event.getY(at)-getHeight()/2f)/radius;double length=Math.hypot(x,y);if(length>1){x/=length;y/=length;}stick(this,x,y);invalidate();}return true;
        }
    }
    private final Object stickOwner=new Object();
    private void stick(Stick which,float x,float y){
        if(!usable)return;
        try{
            if(!which.left)pad.rightStick(x,y);
            else if(nativeSink instanceof MarmaladePadSink){
                // Digital-only route: the left stick presses the d-pad directions instead.
                pad.hold(VirtualPad.LEFT,stickOwner,x<-0.5f);pad.hold(VirtualPad.RIGHT,stickOwner,x>0.5f);
                pad.hold(VirtualPad.UP,stickOwner,y<-0.5f);pad.hold(VirtualPad.DOWN,stickOwner,y>0.5f);
            }else pad.leftStick(x,y);
        }catch(RuntimeException error){usable=false;releasePad();Log.e("PocketOuyaControls","Stick input disabled after input error",error);}
    }
    private void hold(int key,Object owner,boolean down){
        if(!usable)return;
        try{pad.hold(key,owner,down);}catch(RuntimeException error){usable=false;releasePad();Log.e("PocketOuyaControls","Touch input disabled after native input error",error);}
    }
    private void releasePad(){handler.removeCallbacksAndMessages(null);if(leftStick!=null)leftStick.resetPointer();if(rightStick!=null)rightStick.resetPointer();try{pad.releaseAll();}catch(RuntimeException error){Log.e("PocketOuyaControls","Unable to release virtual controller",error);}}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(!focused)releasePad();}
    @Override protected void onDetachedFromWindow(){releasePad();super.onDetachedFromWindow();}
}
