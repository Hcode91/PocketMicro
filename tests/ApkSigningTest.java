package io.github.hozdogus.pocketouya;

import com.android.apksig.ApkVerifier;
import com.android.apksig.SigningCertificateLineage;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Per-phone game signing with a handover from the shared key (ApkSigning), checked with apksig's
 * own verifier. Run on a plain JVM with apksig on the classpath:
 *   java -cp apksig.jar:out io.github.hozdogus.pocketouya.ApkSigningTest any.apk app/src/main/assets/games.p12
 */
public final class ApkSigningTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    public static void main(String[] args) throws Exception {
        File in = new File(args[0]);
        char[] pass = "pocketouya".toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream is = new FileInputStream(args[1])) { ks.load(is, pass); }
        ApkSigning.Keys shared = new ApkSigning.Keys((PrivateKey) ks.getKey("games", pass),
                (X509Certificate) ks.getCertificate("games"));

        ApkSigning.Keys device = ApkSigning.createDeviceKeys();
        device.cert.checkValidity();
        device.cert.verify(device.cert.getPublicKey());
        check(device.cert.getSubjectX500Principal().getName().contains("PocketOuya games on this device"),
                "certificate subject: " + device.cert.getSubjectX500Principal());

        File out = File.createTempFile("signed", ".apk");
        ApkSigning.signWithHandover(shared, device, in, out);

        // Android 10 (PocketOuya's minimum) reads v3: the phone's key, with the handover.
        ApkVerifier.Result r = new ApkVerifier.Builder(out).setMinCheckedPlatformVersion(29).build().verify();
        check(r.isVerified(), "verifies on Android 10+: " + r.getErrors());
        check(r.isVerifiedUsingV3Scheme(), "signed with v3");
        X509Certificate effective = r.getSignerCertificates().get(0);
        check(effective.equals(device.cert), "Android 10+ sees the phone's key, saw " + effective.getSubjectX500Principal());
        SigningCertificateLineage lineage = r.getSigningCertificateLineage();
        check(lineage != null && lineage.size() == 2, "handover record present");
        check(lineage.getCertificatesInLineage().get(0).equals(shared.cert), "handover starts at the shared key");
        SigningCertificateLineage.SignerCapabilities old = lineage.getSignerCapabilities(shared.cert);
        check(!old.hasRollback(), "the shared key cannot update a handed-over game");
        check(old.hasInstalledData(), "the shared key's game data is kept");

        // A second signing reuses nothing but the keys: same result.
        File again = File.createTempFile("signed2", ".apk");
        ApkSigning.signWithHandover(shared, device, in, again);
        check(new ApkVerifier.Builder(again).setMinCheckedPlatformVersion(29).build().verify()
                .getSignerCertificates().get(0).equals(device.cert), "same key on the next conversion");

        out.delete();
        again.delete();
        System.out.println("ApkSigningTest passed");
    }
}
