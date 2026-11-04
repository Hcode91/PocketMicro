import io.github.hcode91.pocketouya.bridge.VirtualPad;
import io.github.hcode91.pocketouya.bridge.VirtualPadState;
import io.github.hozdogus.pocketouya.UhidGamepad;

/** Touch pad -> Shizuku virtual controller mapping, checked against the bytes the pad sends. */
public final class VirtualPadStateTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    static void pairs(VirtualPadState s, int[][] pairs, String layout) {
        for (int[] p : pairs) {
            check(s.key(p[0], 0) && s.buttons == 1 << p[1], layout + ": key " + p[0] + " sets bit " + p[1] + " (scan 0x" + Integer.toHexString(0x130 + p[1]) + ")");
            check(s.key(p[0], 1) && s.buttons == 0, layout + ": key " + p[0] + " released");
        }
    }

    public static void main(String[] args) {
        // Unity: the real OUYA pad's raw order (its Vendor_2836_Product_0001.kl): 0x130 O, 0x131 U, 0x132 Y, 0x133 A,
        // 0x134 L1, 0x135 R1, 0x138-0x13b d-pad up/down/left/right, 0x13e Home/Menu.
        VirtualPadState unity = new VirtualPadState(true);
        pairs(unity, new int[][]{{VirtualPad.O, 0}, {VirtualPad.U, 1}, {VirtualPad.Y, 2}, {VirtualPad.A, 3}, {VirtualPad.L1, 4}, {VirtualPad.R1, 5},
                {VirtualPad.UP, 8}, {VirtualPad.DOWN, 9}, {VirtualPad.LEFT, 10}, {VirtualPad.RIGHT, 11}, {VirtualPad.L2, 12}, {VirtualPad.R2, 13}, {VirtualPad.START, 14}, {VirtualPad.SELECT, 15}}, "OUYA order");
        check(unity.keys == 0, "OUYA order sends the d-pad as buttons, not keys");
        check(unity.key(VirtualPad.MENU, 0) && unity.buttons == 1 << 14 && unity.keys == UhidGamepad.KEY_MENU, "Menu = OUYA home button and the MENU key");
        unity.key(VirtualPad.MENU, 1);
        check(unity.buttons == 0 && unity.keys == 0, "Menu released");

        // Other engines: Android key codes through Generic.kl.
        VirtualPadState android = new VirtualPadState(false);
        pairs(android, new int[][]{{VirtualPad.O, 0}, {VirtualPad.A, 1}, {VirtualPad.U, 3}, {VirtualPad.Y, 4}, {VirtualPad.L1, 6}, {VirtualPad.R1, 7}, {VirtualPad.L2, 8}, {VirtualPad.R2, 9}, {VirtualPad.SELECT, 10}, {VirtualPad.START, 11}}, "Android order");
        int[][] keys = {{VirtualPad.RIGHT, UhidGamepad.KEY_RIGHT}, {VirtualPad.LEFT, UhidGamepad.KEY_LEFT}, {VirtualPad.DOWN, UhidGamepad.KEY_DOWN}, {VirtualPad.UP, UhidGamepad.KEY_UP}, {VirtualPad.MENU, UhidGamepad.KEY_MENU}};
        for (int[] k : keys) {
            check(android.key(k[0], 0) && android.keys == k[1] && android.buttons == 0, "d-pad/menu key " + k[0] + " matches the uhid key bit");
            check(android.key(k[0], 1) && android.keys == 0, "d-pad/menu key released");
        }
        check(!android.key(4 /* BACK */, 0) && !unity.key(4, 0), "keys the controller lacks are ignored");

        // L2/R2 are analogue triggers too (Brake/Accelerator in the report).
        for (VirtualPadState s : new VirtualPadState[]{unity, android}) {
            s.key(VirtualPad.L2, 0); check(s.lt == 1f && s.rt == 0f, "L2 pulls the left trigger");
            s.key(VirtualPad.R2, 0); check(s.rt == 1f, "R2 pulls the right trigger");
            byte[] r = UhidGamepad.input(s.buttons, s.keys, 0, 0, 0, 0, s.lt, s.rt);
            check((r[12] & 0xff) == 255 && (r[13] & 0xff) == 255, "trigger bytes full");
            s.key(VirtualPad.L2, 1); s.key(VirtualPad.R2, 1); check(s.lt == 0f && s.rt == 0f && s.buttons == 0, "triggers released");
        }
        unity.key(VirtualPad.O, 0); unity.key(VirtualPad.U, 0); unity.key(VirtualPad.UP, 0);
        check(unity.buttons == (0b11 | 1 << 8), "chords combine");
        check(unity.axis(0, -1f) && unity.axis(1, 2f) && unity.axis(11, 0.5f) && unity.axis(14, -0.25f), "stick axes accepted");
        check(unity.lx == -1f && unity.ly == 1f && unity.rx == 0.5f && unity.ry == -0.25f, "values stored, clamped to -1..1");
        check(!unity.axis(5, 1f) && !unity.axis(0, Float.NaN), "unknown axes and NaN ignored");
        byte[] report = UhidGamepad.input(unity.buttons, unity.keys, unity.lx, unity.ly, unity.rx, unity.ry, unity.lt, unity.rt);
        check(report[6] == 0b11 && report[7] == 1 && report[14] == 0, "state packs into the uhid report (buttons 0,1,8)");
        check((report[8] & 0xff) == 0 && (report[9] & 0xff) == 255, "left stick bytes");
        System.out.println("VirtualPadStateTest OK");
    }
}
