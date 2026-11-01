package io.github.hozdogus.pocketouya;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Runs an Ouya game's 32-bit engine through the PocketOuya translator.
 * Lives in its own process (":game") so each launch starts from a clean engine.
 */
public class GameActivity extends Activity {
    static final String EXTRA_APK = "apk", EXTRA_PKG = "pkg", EXTRA_NAME = "name";

    private GLSurfaceView view;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean stopped,engineReady;
    private boolean resumed;
    private String gameName="GameMaker game";
    private EngineTouchControls controls;
    private final EngineInput input=new EngineInput((action,key)->{if(engineReady&&!stopped)Engine.key(action,key);});
    private final Object stickLeftOwner=new Object(),stickRightOwner=new Object(),stickUpOwner=new Object(),stickDownOwner=new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        gameName=getIntent().getStringExtra(EXTRA_NAME);
        if(gameName==null)gameName="GameMaker game";
        LaunchLogging.append(this,gameName,"Engine activity","Game screen created; starting translated engine.");
        String apk = getIntent().getStringExtra(EXTRA_APK);
        String pkg = getIntent().getStringExtra(EXTRA_PKG);
        File save = new File(getFilesDir(), "saves/" + pkg);
        save.mkdirs();

        view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(2);
        view.setEGLConfigChooser(8, 8, 8, 8, 16, 8);
        view.setPreserveEGLContextOnPause(true);
        view.setRenderer(new GLSurfaceView.Renderer() {
            boolean started;
            int w = 1280, h = 720;

            @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
                if (started) return;
                started = true;
                if (!Engine.start(apk, save.getAbsolutePath(), pkg)) engineStopped();
                else {engineReady=true;LaunchLogging.append(GameActivity.this,gameName,"Engine started","Engine initialization returned successfully; beginning frames.");ui.post(()->updateControls());}
            }

            @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
                w = width;
                h = height;
            }

            @Override public void onDrawFrame(GL10 gl) {
                if (stopped) return;
                if (!Engine.frame(w, h)) engineStopped();
            }
        });
        FrameLayout root=new FrameLayout(this);
        root.addView(view,new FrameLayout.LayoutParams(-1,-1));
        controls=new EngineTouchControls(this,input,pkg);
        root.addView(controls,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        hideSystemBars();
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    /** Called on the GL thread when the engine stops: show what happened. */
    private void engineStopped() {
        stopped = true;
        view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        String log = Engine.log();
        LaunchLogging.append(this,gameName,"Engine stopped",log);
        ui.post(() -> {releaseInput();updateControls();showLog(log);});
    }

    private void showLog(String text) {
        releaseInput();
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(32, 24, 32, 24);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("The engine stopped").setView(sv)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("engine log", text));
                    Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
                    finishGame();
                })
                .setNegativeButton("Close", (d, w) -> finishGame())
                .setCancelable(false).show();
    }

    // ---------------------------------------------------------------- input

    /** Controller buttons, d-pad and keyboards: passed straight to the game, as an Ouya would. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if(!acceptsInput())return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) return super.dispatchKeyEvent(e);
        if (code == KeyEvent.KEYCODE_BACK && !isFromController(e)) return super.dispatchKeyEvent(e);
        Object owner="key:"+e.getDeviceId()+":"+code;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) input.hold(owner,code,true);
        else if (e.getAction() == KeyEvent.ACTION_UP) input.hold(owner,code,false);
        return true;
    }

    private static boolean isFromController(KeyEvent e) {
        int s = e.getSource();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (s & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD && e.getDevice() != null
                   && !e.getDevice().isVirtual();
    }

    // Analog stick / hat → d-pad presses.
    private boolean stickUp, stickDown, stickLeft, stickRight;

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if(!acceptsInput())return super.dispatchGenericMotionEvent(e);
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || e.getAction() != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(e);
        float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(hx) > Math.abs(x)) x = hx;
        if (Math.abs(hy) > Math.abs(y)) y = hy;
        stickLeft = press(stickLeftOwner,stickLeft, x < -0.5f, KeyEvent.KEYCODE_DPAD_LEFT);
        stickRight = press(stickRightOwner,stickRight, x > 0.5f, KeyEvent.KEYCODE_DPAD_RIGHT);
        stickUp = press(stickUpOwner,stickUp, y < -0.5f, KeyEvent.KEYCODE_DPAD_UP);
        stickDown = press(stickDownOwner,stickDown, y > 0.5f, KeyEvent.KEYCODE_DPAD_DOWN);
        return true;
    }

    private boolean press(Object owner,boolean was, boolean now, int code) {
        if (now != was) input.hold(owner,code,now);
        return now;
    }

    @Override
    public void onBackPressed() {
        releaseInput();
        new AlertDialog.Builder(this).setTitle("Quit game?")
                .setPositiveButton("Quit", (d, w) -> finishGame())
                .setNeutralButton("Show log", (d, w) -> showLog(Engine.log()))
                .setNegativeButton("Keep playing", null).show();
    }

    private void finishGame() {
        releaseInput();
        LaunchLogging.append(this,gameName,"Engine quit",Engine.log());
        finish();
        // The engine keeps global state; end this process so the next launch starts fresh.
        ui.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
    }

    private boolean acceptsInput(){return engineReady&&!stopped&&resumed&&hasWindowFocus();}
    private void updateControls(){if(controls!=null)controls.setInputEnabled(acceptsInput());}
    private void releaseInput(){if(controls!=null)controls.release();input.releaseAll();stickLeft=stickRight=stickUp=stickDown=false;}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(!focused)releaseInput();updateControls();if(focused)hideSystemBars();}
    @Override protected void onPause(){releaseInput();resumed=false;updateControls();super.onPause();view.onPause();}
    @Override protected void onResume(){super.onResume();resumed=true;view.onResume();hideSystemBars();updateControls();}
    @Override protected void onDestroy(){releaseInput();super.onDestroy();}
}
