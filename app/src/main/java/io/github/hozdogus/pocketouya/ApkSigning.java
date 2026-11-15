package io.github.hozdogus.pocketouya;

import com.android.apksig.ApkSigner;
import com.android.apksig.SigningCertificateLineage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The signing half of {@link GameSigner}, kept free of Android classes so it runs on a plain JVM
 * (tests/ApkSigningTest.java).
 *
 * <p>A converted game is signed by this phone's own key. Games converted before that change were
 * signed by the shared key in assets/games.p12, which anyone can read from the source, so every
 * signature also carries a key handover (APK Signature Scheme v3 "proof of rotation") from the
 * shared key to this phone's key. Android 9 and later then accept the new APK as an update to a
 * game installed with the shared key, so its saves are kept, and from then on refuse any update
 * signed by the shared key alone (the shared key is given no rollback capability).</p>
 */
final class ApkSigning {

    /** A private key and its certificate. */
    static final class Keys {
        final PrivateKey key;
        final X509Certificate cert;

        Keys(PrivateKey key, X509Certificate cert) {
            this.key = key;
            this.cert = cert;
        }
    }

    private ApkSigning() {}

    /**
     * Signs with {@code device}, handing over from {@code shared}: v1 and v2 signatures (read
     * only by Android versions older than 9) are made by the shared key, v3 by the device key with
     * the handover record.
     */
    static void signWithHandover(Keys shared, Keys device, File in, File out) throws Exception {
        SigningCertificateLineage.SignerConfig from =
                new SigningCertificateLineage.SignerConfig.Builder(shared.key, shared.cert).build();
        SigningCertificateLineage.SignerConfig to =
                new SigningCertificateLineage.SignerConfig.Builder(device.key, device.cert).build();
        // The shared key may still own the game's data, but may not update it any more
        // (no rollback), vouch for signature permissions or share a user id.
        SigningCertificateLineage.SignerCapabilities sharedCapabilities =
                new SigningCertificateLineage.SignerCapabilities.Builder()
                        .setInstalledData(true)
                        .setSharedUid(false)
                        .setPermission(false)
                        .setRollback(false)
                        .setAuth(false)
                        .build();
        SigningCertificateLineage lineage = new SigningCertificateLineage.Builder(from, to)
                .setOriginalCapabilities(sharedCapabilities)
                .build();

        new ApkSigner.Builder(Arrays.asList(signer("POCKETOU", shared), signer("DEVICE", device)))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setSigningCertificateLineage(lineage)
                // apksig's default puts the handover only in the v3.1 block, read from Android 13;
                // PocketOuya supports Android 10, so the v3.0 block carries it.
                .setMinSdkVersionForRotation(28)
                .build()
                .sign();
    }

    /** The old way: the shared key alone. Used only if this phone's key cannot be made. */
    static void signShared(Keys shared, File in, File out) throws Exception {
        new ApkSigner.Builder(java.util.Collections.singletonList(signer("POCKETOU", shared)))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .build()
                .sign();
    }

    private static ApkSigner.SignerConfig signer(String name, Keys keys) {
        return new ApkSigner.SignerConfig.Builder(name, keys.key, java.util.Collections.singletonList(keys.cert)).build();
    }

    /** A new RSA-2048 key with a self-signed certificate, valid for 50 years. */
    static Keys createDeviceKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        long now = System.currentTimeMillis();
        X509Certificate cert = selfSigned(pair, "PocketOuya games on this device",
                new Date(now - 24L * 3600 * 1000), new Date(now + 50L * 365 * 24 * 3600 * 1000));
        return new Keys(pair.getPrivate(), cert);
    }

    // ------------------------------------------------------------------ certificate

    private static final byte[] SHA256_WITH_RSA = {
            0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B };
    private static final byte[] COMMON_NAME = { 0x55, 0x04, 0x03 };

    /**
     * A minimal X.509 v3 certificate (no extensions), the same shape keytool makes for an APK
     * signing key. Written by hand because Android has no public API for it and a certificate
     * library would add a megabyte for 60 lines.
     */
    static X509Certificate selfSigned(KeyPair pair, String commonName, Date notBefore, Date notAfter) throws Exception {
        byte[] algorithm = seq(tlv(0x06, SHA256_WITH_RSA), tlv(0x05, new byte[0]));
        byte[] name = seq(tlv(0x31, seq(tlv(0x06, COMMON_NAME), tlv(0x0C, commonName.getBytes(StandardCharsets.UTF_8)))));
        byte[] serial = new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray();
        byte[] tbs = seq(
                tlv(0xA0, tlv(0x02, new byte[] { 2 })),        // version 3
                tlv(0x02, serial),
                algorithm,
                name,                                           // issuer
                seq(time(notBefore), time(notAfter)),
                name,                                           // subject
                pair.getPublic().getEncoded());                 // SubjectPublicKeyInfo, already DER

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(pair.getPrivate());
        signature.update(tbs);
        byte[] signed = signature.sign();
        byte[] bits = new byte[signed.length + 1];               // leading 0: no unused bits
        System.arraycopy(signed, 0, bits, 1, signed.length);

        byte[] der = seq(tbs, algorithm, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    /** UTCTime up to 2049, GeneralizedTime from 2050, as RFC 5280 requires. */
    private static byte[] time(Date date) {
        java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTime(date);
        boolean utc = c.get(java.util.Calendar.YEAR) < 2050;
        SimpleDateFormat format = new SimpleDateFormat(utc ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(utc ? 0x17 : 0x18, format.format(date).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] seq(byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] part : parts) body.write(part, 0, part.length);
        return tlv(0x30, body.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = value.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            int bytes = length > 0xFFFFFF ? 4 : length > 0xFFFF ? 3 : length > 0xFF ? 2 : 1;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) out.write(length >>> (8 * i));
        }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }
}
