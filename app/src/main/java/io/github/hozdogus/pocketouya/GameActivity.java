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
    private volatile boolean stopped;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        setContentView(view);
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
        ui.post(() -> showLog(log));
    }

    private void showLog(String text) {
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
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) return super.dispatchKeyEvent(e);
        if (code == KeyEvent.KEYCODE_BACK && !isFromController(e)) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) Engine.key(0, code);
        else if (e.getAction() == KeyEvent.ACTION_UP) Engine.key(1, code);
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
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || e.getAction() != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(e);
        float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(hx) > Math.abs(x)) x = hx;
        if (Math.abs(hy) > Math.abs(y)) y = hy;
        stickLeft = press(stickLeft, x < -0.5f, KeyEvent.KEYCODE_DPAD_LEFT);
        stickRight = press(stickRight, x > 0.5f, KeyEvent.KEYCODE_DPAD_RIGHT);
        stickUp = press(stickUp, y < -0.5f, KeyEvent.KEYCODE_DPAD_UP);
        stickDown = press(stickDown, y > 0.5f, KeyEvent.KEYCODE_DPAD_DOWN);
        return true;
    }

    private static boolean press(boolean was, boolean now, int code) {
        if (now != was) Engine.key(now ? 0 : 1, code);
        return now;
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this).setTitle("Quit game?")
                .setPositiveButton("Quit", (d, w) -> finishGame())
                .setNeutralButton("Show log", (d, w) -> showLog(Engine.log()))
                .setNegativeButton("Keep playing", null).show();
    }

    private void finishGame() {
        finish();
        // The engine keeps global state; end this process so the next launch starts fresh.
        ui.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
    }

    @Override protected void onPause() { super.onPause(); view.onPause(); }
    @Override protected void onResume() { super.onResume(); view.onResume(); hideSystemBars(); }
}
