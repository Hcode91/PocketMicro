package com.hcode91.pocketmicro;

/**
 * Where a game's OBB data file goes: /storage/emulated/0/Android/obb/&lt;package&gt;/&lt;file&gt;.obb.
 * Android 13+ stops ordinary apps writing there, so PocketMicro copies it through its Shizuku
 * service. This class only checks names, so nothing can be written outside a game's obb folder.
 */
public final class ObbImport {
    static final String OBB_ROOT = "/storage/emulated/0/Android/obb/";
    static final long MAX_SIZE = 8L << 30;

    private ObbImport() {}

    public static boolean validPackage(String pkg) {
        return pkg != null && pkg.length() <= 255 && pkg.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
    }

    public static boolean validName(String name) {
        return name != null && name.length() <= 200 && name.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.obb") && !name.contains("..");
    }

    /** Folder for the game's OBB files, or IllegalArgumentException for a bad package name. */
    public static String folder(String pkg) {
        if (!validPackage(pkg)) throw new IllegalArgumentException("Invalid game package");
        return OBB_ROOT + pkg;
    }

    /** Full target path, or IllegalArgumentException for a bad package or file name. */
    public static String target(String pkg, String name) {
        if (!validName(name)) throw new IllegalArgumentException("Not an OBB file name (it must end in .obb)");
        return folder(pkg) + "/" + name;
    }

    /** True when the file name names this game (main.&lt;version&gt;.&lt;package&gt;.obb), the name games look for. */
    public static boolean namesGame(String pkg, String name) {
        return name != null && pkg != null && name.matches("(?:main|patch)\\.\\d+\\." + java.util.regex.Pattern.quote(pkg) + "\\.obb");
    }
}
