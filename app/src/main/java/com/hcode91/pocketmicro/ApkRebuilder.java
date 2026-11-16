package com.hcode91.pocketmicro;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Copies an APK with a patched manifest and without its old signature files. */
public final class ApkRebuilder {
    private ApkRebuilder() {}

    public static ManifestPatcher.Result rebuild(File in, File out) throws IOException {
        ManifestPatcher.Result result;
        try (ZipFile zip = new ZipFile(in)) {
            ZipEntry manifestEntry = zip.getEntry("AndroidManifest.xml");
            if (manifestEntry == null) throw new IOException("Not an Android app (no AndroidManifest.xml).");
            result = ManifestPatcher.patch(readAll(zip.getInputStream(manifestEntry)));

            byte[] buf = new byte[1 << 16];
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String name = e.getName();
                    if (isOldSignature(name) || e.isDirectory()) continue;

                    byte[] replacement = name.equals("AndroidManifest.xml") ? result.manifest : null;
                    ZipEntry ne = new ZipEntry(name);
                    ne.setTime(e.getTime());
                    if (e.getMethod() == ZipEntry.STORED) {
                        // Stored entries (e.g. resources.arsc, media) must stay uncompressed.
                        byte[] content = replacement != null ? replacement : readAll(zip.getInputStream(e));
                        CRC32 crc = new CRC32();
                        crc.update(content);
                        ne.setMethod(ZipEntry.STORED);
                        ne.setSize(content.length);
                        ne.setCompressedSize(content.length);
                        ne.setCrc(crc.getValue());
                        zos.putNextEntry(ne);
                        zos.write(content);
                    } else {
                        ne.setMethod(ZipEntry.DEFLATED);
                        zos.putNextEntry(ne);
                        if (replacement != null) {
                            zos.write(replacement);
                        } else {
                            try (InputStream is = zip.getInputStream(e)) {
                                int n;
                                while ((n = is.read(buf)) > 0) zos.write(buf, 0, n);
                            }
                        }
                    }
                    zos.closeEntry();
                }
            }
        }
        return result;
    }

    static boolean isOldSignature(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String n = name.toUpperCase(java.util.Locale.ROOT);
        return n.equals("META-INF/MANIFEST.MF") || n.endsWith(".SF") || n.endsWith(".RSA")
                || n.endsWith(".DSA") || n.endsWith(".EC");
    }

    static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /** Small helper for copying a stream to a file. */
    static void copy(InputStream in, File out) throws IOException {
        try (InputStream is = in; OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }
}
