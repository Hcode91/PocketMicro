package io.github.hozdogus.pocketouya;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.*;
import android.view.*;
import android.widget.*;
import rikka.shizuku.Shizuku;

/**
 * First step of the Shizuku virtual controller: creates "OUYA Game Controller" through
 * VirtualPadService, checks Android lists it as a gamepad, then presses A (OUYA O) and moves
 * the left stick, showing which events arrive here from that device.
 */
public final class VirtualPadTestActivity extends Activity {
    private static final int ACCESS = 7311;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, output;
    private final StringBuilder log = new StringBuilder();
    private IBinder remote;
    private boolean bound, testing;
    private Shizuku.UserServiceArgs args;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { remote = binder; if (testing) new Thread(VirtualPadTestActivity.this::runTest, "virtual-pad-test").start(); }
        @Override public void onServiceDisconnected(ComponentName name) { remote = null; bound = false; line("Shizuku service disconnected."); }
    };
    private final Shizuku.OnRequestPermissionResultListener permission = (request, result) -> { if (request == ACCESS) { if (result == PackageManager.PERMISSION_GRANTED) bind(); else message("Shizuku permission denied."); } };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        int version = 1; try { version = (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); } catch (PackageManager.NameNotFoundException ignored) {}
        args = new Shizuku.UserServiceArgs(new ComponentName(this, VirtualPadService.class)).daemon(false).tag("virtual_pad").processNameSuffix("virtual_pad").version(version).debuggable(false);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(20, 16, 20, 12);
        TextView title = new TextView(this); title.setText("Virtual controller test"); title.setTextSize(18); root.addView(title);
        TextView info = new TextView(this);
        info.setText("Uses Shizuku to create a real virtual gamepad named \"" + UhidGamepad.NAME + "\" through Android's /dev/uhid, checks Android lists it, then presses A (the OUYA O button) and moves the left stick. It is removed when you leave this screen. Nothing is installed or changed on the phone.");
        root.addView(info);
        status = new TextView(this); root.addView(status);
        LinearLayout row = new LinearLayout(this);
        Button test = new Button(this); test.setText("Create and test"); test.setOnClickListener(v -> start()); row.addView(test);
        Button remove = new Button(this); remove.setText("Remove"); remove.setOnClickListener(v -> new Thread(() -> { call(VirtualPadService.REMOVE, null); line("Removed."); }).start()); row.addView(remove);
        root.addView(row);
        output = new TextView(this); output.setTextSize(11); output.setTypeface(Typeface.MONOSPACE); output.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(output); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        Shizuku.addRequestPermissionResultListener(permission, ui);
        message("Start Shizuku, then tap Create and test.");
    }

    private void start() {
        log.setLength(0); output.setText("");
        try {
            if (!Shizuku.pingBinder()) { message("Shizuku is not running. Start it, then try again."); return; }
            if (Shizuku.getVersion() < 13) { message("Update Shizuku to version 13 or later."); return; }
            testing = true;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind(); else Shizuku.requestPermission(ACCESS);
        } catch (RuntimeException error) { message("Shizuku unavailable: " + error); }
    }

    private void bind() {
        if (remote != null) { new Thread(this::runTest, "virtual-pad-test").start(); return; }
        if (bound) return;
        bound = true; message("Starting the Shizuku helper…");
        try { Shizuku.bindUserService(args, connection); } catch (RuntimeException error) { bound = false; message("Could not start the Shizuku helper: " + error); }
    }

    private void runTest() {
        testing = false;
        String created = call(VirtualPadService.CREATE, null);
        line(created == null ? "No reply from the helper." : created);
        if (created == null || !created.startsWith("OK")) { message("Result: virtual controller NOT created. Details below."); return; }
        sleep(1200);
        InputDevice found = null;
        for (int id : InputDevice.getDeviceIds()) { InputDevice d = InputDevice.getDevice(id); if (d != null && UhidGamepad.NAME.equals(d.getName())) found = d; }
        line("Helper status: " + call(VirtualPadService.STATUS, null));
        if (found == null) { message("Result: created, but Android does not list it as an input device."); return; }
        int s = found.getSources();
        line("Android device: id " + found.getId() + ", name \"" + found.getName() + "\", vendor " + Integer.toHexString(found.getVendorId()) + ", product " + Integer.toHexString(found.getProductId())
                + ", gamepad " + ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) + ", joystick " + ((s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) + ", external " + found.isExternal());
        StringBuilder axes = new StringBuilder(); for (InputDevice.MotionRange r : found.getMotionRanges()) axes.append(MotionEvent.axisToString(r.getAxis())).append(' ');
        line("Axes: " + axes.toString().trim());
        line("Pressing A (OUYA O) and moving the left stick…");
        input(1, 0, 0); sleep(150); input(0, 0, 0); sleep(150);
        input(0, 1f, 0); sleep(150); input(0, 0, 0); sleep(400);
        message((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ? "Result: virtual controller works so far. Check the events below, then send me this screen." : "Result: listed, but not as a gamepad. Details below.");
    }

    private void input(int buttons, float lx, float ly) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VirtualPadService.DESCRIPTOR); data.writeInt(buttons);
            data.writeFloat(lx); data.writeFloat(ly); data.writeFloat(0); data.writeFloat(0); data.writeFloat(0); data.writeFloat(0); data.writeInt(-1);
            remote.transact(VirtualPadService.INPUT, data, reply, 0); reply.readException();
            if (reply.readInt() == 0) line("Input write failed.");
        } catch (Exception error) { line("Input failed: " + error); }
        finally { data.recycle(); reply.recycle(); }
    }

    private String call(int code, String arg) {
        IBinder binder = remote; if (binder == null) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VirtualPadService.DESCRIPTOR); if (arg != null) data.writeString(arg);
            binder.transact(code, data, reply, 0); reply.readException();
            return code == VirtualPadService.REMOVE ? "" : reply.readString();
        } catch (Exception error) { return "FAILED: " + error; }
        finally { data.recycle(); reply.recycle(); }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        InputDevice d = event.getDevice();
        if (d != null && UhidGamepad.NAME.equals(d.getName())) { line("Key from virtual controller: " + KeyEvent.keyCodeToString(event.getKeyCode()) + (event.getAction() == KeyEvent.ACTION_DOWN ? " down" : " up")); return true; }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        InputDevice d = event.getDevice();
        if (d != null && UhidGamepad.NAME.equals(d.getName())) { line(String.format(java.util.Locale.ROOT, "Motion from virtual controller: X %.2f Y %.2f", event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))); return true; }
        return super.dispatchGenericMotionEvent(event);
    }

    private void message(String text) { ui.post(() -> { if (!isDestroyed()) status.setText(text); }); }
    private void line(String text) { ui.post(() -> { if (isDestroyed()) return; log.append(text).append('\n'); output.setText(log); }); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permission);
        IBinder binder = remote;
        if (binder != null) new Thread(() -> { Parcel d = Parcel.obtain(), r = Parcel.obtain(); try { d.writeInterfaceToken(VirtualPadService.DESCRIPTOR); binder.transact(VirtualPadService.REMOVE, d, r, 0); } catch (Exception ignored) {} finally { d.recycle(); r.recycle(); } try { Shizuku.unbindUserService(args, connection, true); } catch (RuntimeException ignored) {} }).start();
        super.onDestroy();
    }
}
