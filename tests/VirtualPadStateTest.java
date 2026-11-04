import io.github.hcode91.pocketouya.bridge.VirtualPad;
import io.github.hcode91.pocketouya.bridge.VirtualPadState;
import io.github.hozdogus.pocketouya.UhidGamepad;

/** Touch pad -> Shizuku virtual controller mapping, checked against the bytes the pad sends. */
public final class VirtualPadStateTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    public static void main(String[] args) {
        VirtualPadState s = new VirtualPadState();
        // OUYA layout on Linux BTN_A + n bits (Android: O=BUTTON_A, A=BUTTON_B, U=BUTTON_X, Y=BUTTON_Y).
        int[][] pairs = {{VirtualPad.O, 0}, {VirtualPad.A, 1}, {VirtualPad.U, 3}, {VirtualPad.Y, 4}, {VirtualPad.L1, 6}, {VirtualPad.R1, 7}, {VirtualPad.SELECT, 10}, {VirtualPad.START, 11}};
        for (int[] p : pairs) {
            check(s.key(p[0], 0) && s.buttons == 1 << p[1], "key " + p[0] + " sets bit " + p[1]);
            check(s.key(p[0], 1) && s.buttons == 0, "key " + p[0] + " released");
        }
        int[][] keys = {{VirtualPad.RIGHT, UhidGamepad.KEY_RIGHT}, {VirtualPad.LEFT, UhidGamepad.KEY_LEFT}, {VirtualPad.DOWN, UhidGamepad.KEY_DOWN}, {VirtualPad.UP, UhidGamepad.KEY_UP}, {VirtualPad.MENU, UhidGamepad.KEY_MENU}};
        for (int[] k : keys) {
            check(s.key(k[0], 0) && s.keys == k[1], "d-pad/menu key " + k[0] + " matches the uhid key bit");
            check(s.key(k[0], 1) && s.keys == 0, "d-pad/menu key released");
        }
        check(!s.key(4 /* BACK */, 0) && s.buttons == 0 && s.keys == 0, "keys the controller lacks are ignored");
        s.key(VirtualPad.O, 0); s.key(VirtualPad.U, 0); s.key(VirtualPad.UP, 0);
        check(s.buttons == 0b1001 && s.keys == UhidGamepad.KEY_UP, "chords combine");
        check(s.axis(0, -1f) && s.axis(1, 2f) && s.axis(11, 0.5f) && s.axis(14, -0.25f), "stick axes accepted");
        check(s.lx == -1f && s.ly == 1f && s.rx == 0.5f && s.ry == -0.25f, "values stored, clamped to -1..1");
        check(!s.axis(5, 1f) && !s.axis(0, Float.NaN), "unknown axes and NaN ignored");
        byte[] report = UhidGamepad.input(s.buttons, s.keys, s.lx, s.ly, s.rx, s.ry, s.lt, s.rt);
        check(report[6] == 0b1001 && report[7] == 0 && report[14] == UhidGamepad.KEY_UP, "state packs into the uhid report");
        check((report[8] & 0xff) == 0 && (report[9] & 0xff) == 255, "left stick bytes");
        System.out.println("VirtualPadStateTest OK");
    }
}
