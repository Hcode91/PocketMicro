package io.github.hozdogus.pocketouya;

import android.content.Context;
import android.os.*;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;

/**
 * Optional Shizuku user service (runs as the shell user) that owns one virtual gamepad created
 * through Linux /dev/uhid. Fixed operations only: create the pad, send one input state, remove
 * it, report status. Games then see a real Android input device named "OUYA Game Controller".
 * The pad is removed when PocketOuya asks, when the service is destroyed, or when this process
 * exits (closing /dev/uhid destroys the device).
 */
public final class VirtualPadService extends Binder {
    static final String DESCRIPTOR = "io.github.hozdogus.pocketouya.VIRTUAL_PAD";
    static final int CREATE = 1, INPUT = 2, REMOVE = 3, STATUS = 4, DESTROY = 16777115;
    private final int owner;
    private final Object lock = new Object();
    private FileDescriptor fd;
    private String status = "No virtual controller.";
    private boolean started, opened;

    public VirtualPadService(Context context) { owner = context.getApplicationInfo().uid; attachInterface(null, DESCRIPTOR); }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
        if (code == PING_TRANSACTION) return true;
        int caller = Binder.getCallingUid();
        if (code == DESTROY) { if (caller != owner && caller != android.os.Process.myUid()) throw new SecurityException("Virtual pad belongs to PocketOuya"); remove(); System.exit(0); return true; }
        if (caller != owner) throw new SecurityException("Virtual pad belongs to PocketOuya");
        data.enforceInterface(DESCRIPTOR);
        switch (code) {
            case CREATE: { String result = create(); reply.writeNoException(); reply.writeString(result); return true; }
            case INPUT: {
                int buttons = data.readInt(); float lx = data.readFloat(), ly = data.readFloat(), rx = data.readFloat(), ry = data.readFloat(), lt = data.readFloat(), rt = data.readFloat(); int hat = data.readInt();
                boolean ok = send(UhidGamepad.input(buttons, lx, ly, rx, ry, lt, rt, hat));
                reply.writeNoException(); reply.writeInt(ok ? 1 : 0); return true;
            }
            case REMOVE: { remove(); reply.writeNoException(); return true; }
            case STATUS: { String s; synchronized (lock) { s = status + (fd == null ? "" : " Kernel: " + (started ? "started" : "not started") + ", " + (opened ? "in use by Android" : "not opened yet") + "."); } reply.writeNoException(); reply.writeString(s); return true; }
            default: return super.onTransact(code, data, reply, flags);
        }
    }

    private String create() {
        synchronized (lock) {
            if (fd != null) return "OK: virtual controller already present.";
            FileDescriptor opened;
            try { opened = Os.open("/dev/uhid", OsConstants.O_RDWR | OsConstants.O_CLOEXEC, 0); }
            catch (ErrnoException error) { status = "Cannot open /dev/uhid: " + error.getMessage() + " (uid " + android.os.Process.myUid() + ")."; return "FAILED: " + status; }
            try {
                byte[] create = UhidGamepad.create(UhidGamepad.NAME);
                int written = Os.write(opened, create, 0, create.length);
                if (written != create.length) throw new IllegalStateException("short write " + written);
            } catch (Exception error) {
                try { Os.close(opened); } catch (ErrnoException ignored) {}
                status = "The kernel refused the controller: " + error.getMessage() + ".";
                return "FAILED: " + status;
            }
            fd = opened; started = false; this.opened = false;
            status = "Virtual controller \"" + UhidGamepad.NAME + "\" created.";
            final FileDescriptor reader = opened;
            Thread thread = new Thread(() -> read(reader), "virtual-pad-uhid");
            thread.setDaemon(true); thread.start();
            return "OK: " + status;
        }
    }

    /** Handles the kernel's messages: start/stop/open/close bookkeeping and report requests. */
    private void read(FileDescriptor reader) {
        byte[] event = new byte[UhidGamepad.EVENT_SIZE];
        while (true) {
            int n;
            try { n = Os.read(reader, event, 0, event.length); } catch (Exception error) { return; }
            if (n <= 0) return;
            int type = UhidGamepad.type(event, n);
            synchronized (lock) {
                if (fd != reader) return;
                if (type == UhidGamepad.START) started = true;
                else if (type == UhidGamepad.STOP) started = false;
                else if (type == UhidGamepad.OPEN) opened = true;
                else if (type == UhidGamepad.CLOSE) opened = false;
            }
            try {
                if (type == UhidGamepad.GET_REPORT && n >= 8) send(UhidGamepad.getReportReply(UhidGamepad.requestId(event)));
                else if (type == UhidGamepad.SET_REPORT && n >= 8) send(UhidGamepad.setReportReply(UhidGamepad.requestId(event)));
            } catch (Exception ignored) {}
        }
    }

    private boolean send(byte[] event) {
        synchronized (lock) {
            if (fd == null) return false;
            try { return Os.write(fd, event, 0, event.length) == event.length; }
            catch (Exception error) { status = "Writing to the virtual controller failed: " + error.getMessage() + "."; return false; }
        }
    }

    private void remove() {
        synchronized (lock) {
            if (fd == null) return;
            try { byte[] destroy = UhidGamepad.destroy(); Os.write(fd, destroy, 0, destroy.length); } catch (Exception ignored) {}
            try { Os.close(fd); } catch (ErrnoException ignored) {}
            fd = null; started = false; opened = false;
            status = "Virtual controller removed.";
        }
    }
}
