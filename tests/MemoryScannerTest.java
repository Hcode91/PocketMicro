import io.github.hcode91.pocketouya.bridge.MemoryScanner;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Cheat menu search on this JVM's own memory (/proc/self/mem), as it runs inside a game. */
public final class MemoryScannerTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    public static void main(String[] args) throws Exception {
        if (!new java.io.File("/proc/self/mem").canRead()) { System.out.println("MemoryScannerTest skipped: no /proc/self/mem"); return; }
        ByteBuffer game = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN);
        int coins = 0x5A17C3E9;
        game.putInt(128, coins);
        game.putFloat(256, 73.25f);
        java.io.File cache = java.nio.file.Files.createTempDirectory("cheat").toFile();
        MemoryScanner s = new MemoryScanner(cache);

        check(s.first(MemoryScanner.Type.INT32, coins, null) >= 1, "exact search finds the value");
        game.putInt(128, coins + 5);
        int n = s.next(MemoryScanner.Compare.INCREASED_BY, 5, null);
        check(n >= 1, "narrowing keeps the value that went up by 5");
        for (int i = 0; i < n; i++) s.write(s.address(i), MemoryScanner.Type.INT32, 1234);
        check(game.getInt(128) == 1234, "writing changes the game's value");

        check(s.first(MemoryScanner.Type.FLOAT, 73.25, null) >= 1, "decimal search");
        check(s.first(MemoryScanner.Type.FLOAT, 73, null) >= 1, "a whole number matches the rounded decimal");

        // Unknown value: the whole JVM changes too much between steps, so narrow the listing to the page holding the value.
        check(s.first(MemoryScanner.Type.INT32, 1234, null) >= 1, "find the value's page");
        long page = -1;
        for (int i = 0; i < s.count(); i++) { Double v = s.read(s.address(i), MemoryScanner.Type.INT32); if (v != null && v == 1234) { game.putInt(128, 1235); Double w = s.read(s.address(i), MemoryScanner.Type.INT32); if (w != null && w == 1235) page = s.address(i) & ~0xfffL; game.putInt(128, 1234); } }
        check(page >= 0, "value address located");
        java.io.File maps = new java.io.File(cache, "maps");
        java.nio.file.Files.writeString(maps.toPath(), Long.toHexString(page) + "-" + Long.toHexString(page + 0x2000) + " rw-p 00000000 00:00 0 \n");
        MemoryScanner u = new MemoryScanner(maps, new java.io.File("/proc/self/mem"), new java.io.File(cache, "snap.bin"));
        u.snapshot(MemoryScanner.Type.INT32, null);
        game.putInt(128, 999);
        check(u.next(MemoryScanner.Compare.DECREASED, 0, null) >= 1, "unknown value then Decreased");
        check(u.next(MemoryScanner.Compare.EQUALS, 999, null) == 1, "then Equal to leaves exactly the value");
        check(!new java.io.File(cache, "snap.bin").exists(), "snapshot file removed after the compare");

        check(!s.write(16, MemoryScanner.Type.INT32, 1), "unmapped addresses are refused");
        check(!MemoryScanner.searchable("7f00-7f10 rw-p 00000000 00:00 0  /dev/kgsl-3d0"), "GPU device memory is never searched");
        check(!MemoryScanner.searchable("7f00-7f10 r--p 00000000 00:00 0  [anon:x]"), "read-only memory is skipped");
        check(MemoryScanner.searchable("7f00-7f10 rw-p 00000000 00:00 0  [anon:scudo:primary]"), "game heap is searched");
        check(MemoryScanner.format(2.5, MemoryScanner.Type.FLOAT).equals("2.5") && MemoryScanner.format(7, MemoryScanner.Type.INT32).equals("7"), "value formatting");
        System.out.println("MemoryScannerTest passed");
    }
}
