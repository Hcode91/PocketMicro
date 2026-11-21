package com.hcode91.pocketmicro;

import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

/**
 * Hands the Shizuku virtual-controller service to a game PocketOuya imported, so the game's
 * touch pad can drive a real "OUYA Game Controller" input device. Only call("connect") exists:
 * the caller must be an imported game; PocketOuya then starts the service (if Shizuku is
 * running and already allowed), lets that game's process send input, and returns the binder.
 * The service removes the pad when the game's process dies.
 */
public final class VirtualPadProvider extends ContentProvider {
    static final String AUTHORITY = "com.hcode91.pocketmicro.virtualpad";
    private static final Object lock = new Object();
    private static IBinder service;
    private static Shizuku.UserServiceArgs args;
    private static final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { synchronized (lock) { service = binder; lock.notifyAll(); } }
        @Override public void onServiceDisconnected(ComponentName name) { synchronized (lock) { service = null; } }
    };

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (!"connect".equals(method)) { out.putString("status", "FAILED: unknown request"); return out; }
        int uid = Binder.getCallingUid();
        IBinder client = extras == null ? null : extras.getBinder("client");
        long identity = Binder.clearCallingIdentity();
        try {
            if (!imported(uid)) { out.putString("status", "FAILED: not a game imported by PocketOuya"); return out; }
            if (client == null) { out.putString("status", "FAILED: no client token"); return out; }
            String ready = shizukuReady();
            if (ready != null) { out.putString("status", "FAILED: " + ready); return out; }
            IBinder binder = bind();
            if (binder == null) { out.putString("status", "FAILED: the Shizuku helper did not start"); return out; }
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(VirtualPadService.DESCRIPTOR); data.writeInt(uid); data.writeStrongBinder(client);
                binder.transact(VirtualPadService.ALLOW, data, reply, 0); reply.readException();
                String allowed = reply.readString();
                if (!"OK".equals(allowed)) { out.putString("status", allowed); return out; }
            } finally { data.recycle(); reply.recycle(); }
            out.putBinder("pad", binder); out.putString("status", "OK");
            return out;
        } catch (Exception error) {
            out.putString("status", "FAILED: " + error); return out;
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private boolean imported(int uid) {
        Context context = getContext(); if (context == null) return false;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        Set<String> imported = context.getSharedPreferences("library", Context.MODE_PRIVATE).getStringSet("imported", java.util.Collections.emptySet());
        if (packages != null) for (String p : packages) if (imported.contains(p)) return true;
        return false;
    }

    /** Null when usable, else why not. Waits briefly for Shizuku's binder in a freshly started process. */
    private static String shizukuReady() throws InterruptedException {
        if (!Shizuku.pingBinder()) {
            CountDownLatch received = new CountDownLatch(1);
            Shizuku.OnBinderReceivedListener listener = received::countDown;
            Shizuku.addBinderReceivedListenerSticky(listener, new Handler(Looper.getMainLooper()));
            try { received.await(3, TimeUnit.SECONDS); } finally { Shizuku.removeBinderReceivedListener(listener); }
            if (!Shizuku.pingBinder()) return "Shizuku is not running";
        }
        if (Shizuku.getVersion() < 13) return "Shizuku 13 or later is needed";
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return "allow PocketOuya in Shizuku (open PocketOuya's virtual controller test once)";
        return null;
    }

    private IBinder bind() throws InterruptedException {
        synchronized (lock) {
            if (service != null && service.pingBinder()) return service;
            service = null;
            if (args == null) {
                Context context = getContext();
                int version = 1; try { version = (int) context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode(); } catch (PackageManager.NameNotFoundException ignored) {}
                // Daemon: the pad keeps working if Android stops PocketOuya's own process while the game runs.
                args = new Shizuku.UserServiceArgs(new ComponentName(context, VirtualPadService.class)).daemon(true).tag("virtual_pad_game").processNameSuffix("virtual_pad_game").version(version).debuggable(false);
            }
            new Handler(Looper.getMainLooper()).post(() -> { try { Shizuku.bindUserService(args, connection); } catch (RuntimeException ignored) {} });
            long until = SystemClock.uptimeMillis() + 6000;
            while (service == null) { long left = until - SystemClock.uptimeMillis(); if (left <= 0) break; lock.wait(left); }
            return service;
        }
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
