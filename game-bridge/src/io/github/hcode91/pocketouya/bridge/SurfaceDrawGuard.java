package io.github.hcode91.pocketouya.bridge;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * android.surfaceview-detached-draw: old engines (Unity 4's GL view) override
 * SurfaceView.onDetachedFromWindow without calling it, so Android never learns the view was
 * removed. Android 7+ posts a "draw finished" callback that then calls requestTransparentRegion
 * on the view's now-null parent and crashes the main thread (Soul Fjord, when Unity swaps its
 * view for the intro video). Android 4.1, which these games targeted, had no such callback.
 *
 * The guard runs the main thread's message loop inside one posted task and ignores exactly that
 * failure: a NullPointerException whose top frame is SurfaceView.performDrawFinished. The
 * callback has already recorded that drawing finished; the skipped part only marks a
 * transparent area in a parent that no longer exists. Every other failure is rethrown.
 */
public final class SurfaceDrawGuard {
    static final String TAG = "PocketMicroSurfaceGuard";
    private static boolean installed;

    private SurfaceDrawGuard() {}

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() { loop(); } });
    }

    public static void loop() {
        while (true) {
            try { Looper.loop(); return; }
            catch (Throwable error) {
                if (!detachedSurfaceDraw(error)) throw rethrow(error);
                Log.w(TAG, "Ignored a draw-finished callback for a SurfaceView the game already removed", error);
            }
        }
    }

    public static boolean detachedSurfaceDraw(Throwable error) {
        if (!(error instanceof NullPointerException)) return false;
        StackTraceElement[] stack = error.getStackTrace();
        return stack.length > 0 && "android.view.SurfaceView".equals(stack[0].getClassName())
                && "performDrawFinished".equals(stack[0].getMethodName());
    }

    private static RuntimeException rethrow(Throwable error) {
        if (error instanceof RuntimeException) return (RuntimeException) error;
        if (error instanceof Error) throw (Error) error;
        return new RuntimeException(error);
    }
}
