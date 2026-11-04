import io.github.hozdogus.pocketouya.ObbImport;

/** OBB copy target checks: nothing may be written outside the game's own Android/obb folder. */
public final class ObbImportTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static boolean rejects(String pkg, String name) { try { ObbImport.target(pkg, name); return false; } catch (IllegalArgumentException expected) { return true; } }
    public static void main(String[] args) {
        String pkg = "com.gameloft.android.ANMP.GloftN3HM";
        check(ObbImport.target(pkg, "main.1.com.gameloft.android.ANMP.GloftN3HM.obb").equals("/storage/emulated/0/Android/obb/com.gameloft.android.ANMP.GloftN3HM/main.1.com.gameloft.android.ANMP.GloftN3HM.obb"), "standard OBB path");
        check(ObbImport.namesGame(pkg, "main.1.com.gameloft.android.ANMP.GloftN3HM.obb") && ObbImport.namesGame(pkg, "patch.12.com.gameloft.android.ANMP.GloftN3HM.obb"), "main/patch names match the game");
        check(!ObbImport.namesGame(pkg, "main.1.com.other.game.obb") && !ObbImport.namesGame(pkg, "nova3.obb"), "other names flagged");
        check(rejects(pkg, "../evil.obb") && rejects(pkg, "a/b.obb") && rejects(pkg, "game.zip") && rejects(pkg, ".hidden.obb") && rejects(pkg, null), "bad file names rejected");
        check(rejects("../../data", "main.1.x.obb") && rejects("com/evil", "main.1.x.obb") && rejects("nodots", "main.1.x.obb"), "bad package names rejected");
        System.out.println("ObbImportTest OK");
    }
}
