package io.github.hcode91.pocketouya.bridge;

import android.app.Activity;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

/**
 * Drives PocketMicro's Shizuku virtual controller ("OUYA Game Controller", a real Android input
 * device) from the touch pad. PocketMicro's provider checks this game was imported by it, starts
 * the Shizuku service and allows this process; the pad is removed when this process dies.
 * Until it is connected, or if Shizuku is unavailable, input goes to the fallback sink.
 */
public final class VirtualDeviceSink implements VirtualPad.Sink {
    static final String TAG = "PocketMicroControls";
    static final Uri PROVIDER = Uri.parse("content://com.hcode91.pocketmicro.virtualpad");
    static final String DESCRIPTOR = "com.hcode91.pocketmicro.VIRTUAL_PAD";
    static final int CREATE = 1, INPUT = 2;
    private static final IBinder TOKEN = new Binder(); // lives as long as this process
    private final VirtualPad.Sink fallback;
    private final VirtualPadState state;
    private volatile IBinder pad;
    private volatile boolean failed;

    public VirtualDeviceSink(final Activity activity, VirtualPad.Sink fallback) {
        this.fallback = fallback;
        // Unity 4 numbers joystick buttons by scan code; OUYA-era Unity scripts expect the real OUYA pad's raw order.
        boolean unity;
        try { Class.forName("com.unity3d.player.UnityPlayer", false, activity.getClassLoader()); unity = true; }
        catch (ClassNotFoundException | LinkageError notUnity) { unity = false; }
        state = new VirtualPadState(unity);
        new Thread(new Runnable() { public void run() { connect(activity); } }, "pocketouya-virtual-pad").start();
    }

    private void connect(Activity activity) {
        try {
            Bundle extras = new Bundle(); extras.putBinder("client", TOKEN);
            Bundle result = activity.getContentResolver().call(PROVIDER, "connect", null, extras);
            String status = result == null ? "PocketMicro did not answer" : result.getString("status", "no status");
            IBinder binder = result == null ? null : result.getBinder("pad");
            if (binder == null || !"OK".equals(status)) { failed = true; Log.w(TAG, "Virtual controller unavailable (" + status + "); using the in-game route"); return; }
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            String created;
            try { data.writeInterfaceToken(DESCRIPTOR); binder.transact(CREATE, data, reply, 0); reply.readException(); created = reply.readString(); }
            finally { data.recycle(); reply.recycle(); }
            if (created == null || !created.startsWith("OK")) { failed = true; Log.w(TAG, "Virtual controller unavailable (" + created + "); using the in-game route"); return; }
            pad = binder;
            Log.i(TAG, "Input route: virtual controller \"OUYA Game Controller\" via Shizuku; layout: " + (state.ouyaScanOrder() ? "OUYA scan order (Unity)" : "Android key order"));
        } catch (Exception | LinkageError error) {
            failed = true; Log.w(TAG, "Virtual controller unavailable; using the in-game route", error);
        }
    }

    public boolean connected() { return pad != null; }
    public boolean unavailable() { return failed; }

    @Override public void key(int key, int action) {
        if (pad == null) { fallback.key(key, action); return; }
        if (state.key(key, action)) send();
    }

    @Override public void axis(int axis, float value) {
        if (pad == null) { fallback.axis(axis, value); return; }
        if (state.axis(axis, value)) send();
    }

    private void send() {
        IBinder binder = pad; if (binder == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(state.buttons); data.writeInt(state.keys);
            data.writeFloat(state.lx); data.writeFloat(state.ly); data.writeFloat(state.rx); data.writeFloat(state.ry); data.writeFloat(state.lt); data.writeFloat(state.rt);
            binder.transact(INPUT, data, null, IBinder.FLAG_ONEWAY);
        } catch (Exception error) {
            pad = null; failed = true; Log.w(TAG, "Virtual controller lost; using the in-game route", error);
        } finally { data.recycle(); }
    }
}
