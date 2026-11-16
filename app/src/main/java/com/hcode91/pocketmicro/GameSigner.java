package com.hcode91.pocketmicro;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

/**
 * Signs converted games. Each phone has its own signing key, made the first time a game is
 * converted and kept in PocketMicro's private files (files/signing/). Each signature also carries
 * a handover from the old shared key (assets/games.p12, public in the source), so games converted
 * before this keep their saves when converted again. See {@link ApkSigning}.
 *
 * <p>The phone's key goes when PocketMicro is uninstalled (an update keeps it). A game converted
 * with one key cannot be updated by a conversion signed with another, so after reinstalling
 * PocketMicro such a game must be removed before it is converted again.</p>
 */
final class GameSigner {
    private static final String TAG = "PocketMicro";
    private static final char[] SHARED_PASS = "pocketouya".toCharArray();
    private static final String DEVICE_ALIAS = "device";

    private GameSigner() {}

    static void sign(Context ctx, File in, File out) throws Exception {
        ApkSigning.Keys shared = sharedKeys(ctx);
        ApkSigning.Keys device;
        try {
            device = deviceKeys(ctx);
        } catch (Exception e) {
            // Never seen, but a conversion must not fail over it: sign as before.
            Log.e(TAG, "This phone's signing key could not be made; using the shared key", e);
            ApkSigning.signShared(shared, in, out);
            return;
        }
        ApkSigning.signWithHandover(shared, device, in, out);
    }

    private static ApkSigning.Keys sharedKeys(Context ctx) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = ctx.getAssets().open("games.p12")) {
            ks.load(is, SHARED_PASS);
        }
        return new ApkSigning.Keys((PrivateKey) ks.getKey("games", SHARED_PASS),
                (X509Certificate) ks.getCertificate("games"));
    }

    /** This phone's key, made on first use: files/signing/device.p12 and its random password. */
    private static synchronized ApkSigning.Keys deviceKeys(Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), "signing");
        File store = new File(dir, "device.p12");
        File passFile = new File(dir, "device.pass");
        if (store.isFile() && passFile.isFile()) {
            char[] pass = new String(java.nio.file.Files.readAllBytes(passFile.toPath()), StandardCharsets.US_ASCII).trim().toCharArray();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream is = new FileInputStream(store)) {
                ks.load(is, pass);
            }
            return new ApkSigning.Keys((PrivateKey) ks.getKey(DEVICE_ALIAS, pass),
                    (X509Certificate) ks.getCertificate(DEVICE_ALIAS));
        }

        ApkSigning.Keys keys = ApkSigning.createDeviceKeys();
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        char[] pass = android.util.Base64.encodeToString(random, android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING).toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(DEVICE_ALIAS, keys.key, pass, new Certificate[] { keys.cert });
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Cannot create " + dir);
        // Written to temporary names and renamed, so a half-written key is never picked up.
        File tmpStore = new File(dir, "device.p12.tmp"), tmpPass = new File(dir, "device.pass.tmp");
        try (FileOutputStream os = new FileOutputStream(tmpStore)) {
            ks.store(os, pass);
        }
        try (FileOutputStream os = new FileOutputStream(tmpPass)) {
            os.write(new String(pass).getBytes(StandardCharsets.US_ASCII));
        }
        if (!tmpPass.renameTo(passFile) || !tmpStore.renameTo(store))
            throw new java.io.IOException("Cannot save this phone's signing key");
        Log.i(TAG, "Made this phone's game signing key: " + keys.cert.getSubjectX500Principal());
        return keys;
    }
}
