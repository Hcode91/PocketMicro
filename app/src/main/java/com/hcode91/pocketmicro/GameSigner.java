package com.hcode91.pocketmicro;

import android.content.Context;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Signs patched games with PocketMicro's own key (bundled, so updates keep working). */
final class GameSigner {
    // The password of the bundled games.p12. It predates the rename and stays as it is: a new key
    // would give converted games a different signature, so they could no longer be updated.
    private static final char[] PASS = "pocketouya".toCharArray();

    private GameSigner() {}

    static void sign(Context ctx, File in, File out) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = ctx.getAssets().open("games.p12")) {
            ks.load(is, PASS);
        }
        PrivateKey key = (PrivateKey) ks.getKey("games", PASS);
        X509Certificate cert = (X509Certificate) ks.getCertificate("games");
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "POCKETOU", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .build()
                .sign();
    }
}
