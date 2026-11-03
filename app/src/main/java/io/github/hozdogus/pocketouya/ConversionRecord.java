package io.github.hozdogus.pocketouya;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;

/**
 * Small text note written into every APK PocketOuya converts, so a launch log can say which
 * PocketOuya build converted the game and which fixes were applied. Plain text, no game data.
 */
final class ConversionRecord {
    static final String ENTRY = "assets/pocketouya-conversion.txt";
    /** Set by the app at start-up (versionName). Host tests leave the default. */
    static volatile String converterVersion = "unknown";
    private static final int MAX_REPORT = 16 * 1024;
    private static final String STATUS = "^\\s*(VERIFIED|SUPPORTED|UNSUPPORTED|UNKNOWN|SKIPPED|MALFORMED)\\b.*";

    private ConversionRecord() {}

    static byte[] build(boolean startupRepairs, boolean touch, boolean experimental, String report) {
        StringBuilder s = new StringBuilder();
        s.append("PocketOuya ").append(converterVersion).append('\n');
        SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT);
        date.setTimeZone(TimeZone.getTimeZone("UTC"));
        s.append("Converted: ").append(date.format(new Date())).append('\n');
        s.append("Options: startup repairs=").append(yes(startupRepairs)).append(", touch=").append(yes(touch))
                .append(", experimental runtime=").append(yes(experimental)).append("\n\n");
        String r = report == null ? "" : report;
        if (r.length() > MAX_REPORT) r = r.substring(0, MAX_REPORT) + "\n[report truncated]";
        s.append(r).append('\n');
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String yes(boolean b) { return b ? "yes" : "no"; }

    /** Short summary for a launch log, or a line saying no record exists. */
    static String summary(File apk) {
        if (apk == null || !apk.isFile()) return "Conversion record: game APK not readable";
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry e = zip.getEntry(ENTRY);
            if (e == null) return "Conversion record: none (not converted by this PocketOuya, or converted before records were added)";
            String text = new String(ApkRebuilder.readBounded(zip.getInputStream(e), 64 * 1024), StandardCharsets.UTF_8);
            return summarize(text);
        } catch (IOException | RuntimeException error) {
            return "Conversion record: unreadable (" + error.getMessage() + ")";
        }
    }

    /** Header lines plus every rule status line (which fixes ran and how they ended). */
    static String summarize(String text) {
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\n");
        int header = 0, rules = 0;
        for (String line : lines) {
            if (header < 3 && (line.startsWith("PocketOuya ") || line.startsWith("Converted: ") || line.startsWith("Options: "))) {
                out.append(header == 0 ? "Converted by " : "").append(line).append('\n'); header++; continue;
            }
            if (line.matches(STATUS) && rules < 30) {
                out.append("  ").append(line.length() > 200 ? line.substring(0, 200) + "…" : line.trim()).append('\n'); rules++;
            }
        }
        if (rules == 0) out.append("  (no rule results recorded)\n");
        return out.toString().trim();
    }
}
