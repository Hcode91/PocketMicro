package io.github.hozdogus.pocketouya;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.File;
import rikka.shizuku.Shizuku;

/**
 * Installs a converted game through PocketOuya's Shizuku user service so the game can keep its
 * original (pre-Android 6) target version. Android 14 refuses to install such APKs normally;
 * `pm install --bypass-low-target-sdk-block` is the official way round that. Keeping the old
 * target lets engines with text relocations (Adobe AIR, old Unity/Marmalade) load as they did.
 * Shizuku is only needed during the install.
 */
final class ShizukuInstaller {
    interface Callback { void done(boolean ok, String message); }
    static final int PERMISSION_REQUEST = 47;

    private ShizukuInstaller() {}

    /** Null when ready; otherwise what the user needs to do. Requests permission if missing. */
    static String readiness() {
        try {
            if (!Shizuku.pingBinder()) return "Shizuku is not running. Start Shizuku (wireless debugging), then try again.";
            if (Shizuku.getVersion() < 13) return "Update Shizuku to version 13 or later.";
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(PERMISSION_REQUEST); return "Allow PocketOuya in the Shizuku prompt, then try again."; }
            return null;
        } catch (RuntimeException e) { return "Shizuku unavailable: " + e.getMessage(); }
    }

    /** Copies a picked OBB into the game's Android/obb folder through the Shizuku service. */
    static void copyObb(android.content.Context context, android.net.Uri source, String pkg, String name, Callback callback) {
        String problem = readiness();
        if (problem != null) { callback.done(false, problem); return; }
        try { ObbImport.target(pkg, name); } catch (IllegalArgumentException e) { callback.done(false, e.getMessage()); return; }
        int version = 1;
        try { version = (int) context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode(); } catch (PackageManager.NameNotFoundException ignored) {}
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(new ComponentName(context, CrashLogService.class))
                .daemon(false).tag("game_obb").processNameSuffix("game_obb").version(version).debuggable(false);
        ServiceConnection[] holder = new ServiceConnection[1];
        holder[0] = new ServiceConnection() {
            boolean used;
            @Override public void onServiceConnected(ComponentName component, IBinder binder) {
                if (used) return; used = true;
                new Thread(() -> {
                    String result;
                    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                    try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(source, "r")) {
                        if (fd == null) throw new java.io.IOException("Cannot open the chosen file");
                        data.writeInterfaceToken(CrashLogService.DESCRIPTOR);
                        data.writeString(pkg);
                        data.writeString(name);
                        data.writeFileDescriptor(fd.getFileDescriptor());
                        data.writeLong(fd.getStatSize());
                        if (!binder.transact(CrashLogService.OBB, data, reply, 0)) throw new RemoteException("OBB copy unsupported");
                        reply.readException();
                        result = reply.readString();
                    } catch (Exception e) { result = "FAILED: " + e; }
                    finally { data.recycle(); reply.recycle(); }
                    try { Shizuku.unbindUserService(args, holder[0], true); } catch (RuntimeException ignored) {}
                    String text = result == null ? "FAILED: no reply" : result;
                    callback.done(text.startsWith("Success"), text);
                }, "shizuku-obb").start();
            }
            @Override public void onServiceDisconnected(ComponentName component) {}
        };
        try { Shizuku.bindUserService(args, holder[0]); }
        catch (RuntimeException e) { callback.done(false, "Cannot start the Shizuku service: " + e.getMessage()); }
    }

    static void install(android.content.Context context, File apk, Callback callback) {
        String problem = readiness();
        if (problem != null) { callback.done(false, problem); return; }
        int version = 1;
        try { version = (int) context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode(); } catch (PackageManager.NameNotFoundException ignored) {}
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(new ComponentName(context, CrashLogService.class))
                .daemon(false).tag("game_install").processNameSuffix("game_install").version(version).debuggable(false);
        ServiceConnection[] holder = new ServiceConnection[1];
        holder[0] = new ServiceConnection() {
            boolean used;
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (used) return; used = true;
                new Thread(() -> {
                    String result;
                    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                    try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)) {
                        data.writeInterfaceToken(CrashLogService.DESCRIPTOR);
                        data.writeFileDescriptor(fd.getFileDescriptor());
                        data.writeLong(apk.length());
                        if (!binder.transact(CrashLogService.INSTALL, data, reply, 0)) throw new RemoteException("Install operation unsupported");
                        reply.readException();
                        result = reply.readString();
                    } catch (Exception e) { result = "FAILED: " + e; }
                    finally { data.recycle(); reply.recycle(); }
                    try { Shizuku.unbindUserService(args, holder[0], true); } catch (RuntimeException ignored) {}
                    String text = result == null ? "FAILED: no reply" : result;
                    callback.done(text.startsWith("Success") || (!text.startsWith("FAILED") && text.contains("Success")), text);
                }, "shizuku-install").start();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        try { Shizuku.bindUserService(args, holder[0]); }
        catch (RuntimeException e) { callback.done(false, "Cannot start the Shizuku install service: " + e.getMessage()); }
    }
}
