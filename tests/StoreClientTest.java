package com.hcode91.pocketmicro;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

public final class StoreClientTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    static final class Fake extends HttpURLConnection {
        final int code; final byte[] body; final String location;
        Fake(URL u, int code, byte[] body, String location) { super(u); this.code = code; this.body = body; this.location = location; }
        @Override public int getResponseCode() { return code; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? location : null; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public void connect() {} @Override public void disconnect() {} @Override public boolean usingProxy() { return false; }
    }

    static final String DISCOVER = "{\"title\":\"DISCOVER\",\"rows\":[{\"title\":\"New games\",\"showPrice\":true,\"ranked\":false,\"tiles\":[0,1]},"
            + "{\"title\":\"Genres\",\"tiles\":[2,3]},{\"title\":\"Empty\",\"tiles\":[9]}],\"tiles\":["
            + "{\"gamerNumbers\":[1],\"genres\":[\"Racing\",\"Retro\"],\"url\":\"ouya://launcher/details?app=com.ryo.armagetronadouya\",\"latestVersion\":{\"apk\":{\"md5sum\":\"x\"},\"versionNumber\":\"0.4.5-ouya\",\"uuid\":\"53aeff84-ad7c-4239-9b0d-a4681a74a8dc\"},\"inAppPurchases\":false,\"premium\":false,\"type\":\"app\",\"package\":\"com.ryo.armagetronadouya\",\"title\":\"Armagetron Advanced\",\"image\":\"http://ouya.cweiske.de/apks/com.ryo.armagetronadouya/discover.jpg\"},"
            + "{\"title\":\"The Tribloos 2 Bundle\",\"image\":\"\",\"uuid\":\"com.Tribloos2-lb\",\"type\":\"details_page\",\"url\":\"ouya://launcher/details?page=com.Tribloos2-lb\",\"bundle\":{\"apps\":[\"com.Tribloos2\",\"com.Tribloos2.launcher\"]}},"
            + "{\"url\":\"ouya://launcher/discover/Best_rated\",\"image\":\"\",\"title\":\"Best rated\",\"type\":\"discover\"},"
            + "{\"url\":\"ouya://launcher/discover/2_players\",\"image\":\"\",\"title\":\"2 players\",\"type\":\"discover\"}]}";

    static String details(long size, String md5) {
        return "{\"type\":\"Game\",\"title\":\"Armagetron Advanced\",\"description\":\"Lightcycles.\",\"gamerNumbers\":[1,4],\"genres\":[\"Multiplayer\",\"Racing\"],"
                + "\"suggestedAge\":\"Everyone\",\"premium\":false,\"inAppPurchases\":false,\"rating\":{\"count\":3,\"average\":4.5},"
                + "\"apk\":{\"fileSize\":" + size + ",\"md5sum\":\"" + md5 + "\",\"package\":\"com.ryo.armagetronadouya\",\"versionCode\":8},"
                + "\"version\":{\"number\":\"0.4.5-ouya\",\"uuid\":\"53aeff84-ad7c-4239-9b0d-a4681a74a8dc\"},\"developer\":{\"name\":\"ryo89\"},"
                + "\"tileImage\":\"http://ouya.cweiske.de/apks/com.ryo.armagetronadouya/discover.jpg\",\"mediaTiles\":["
                + "{\"type\":\"image\",\"urls\":{\"thumbnail\":\"http://ouya.cweiske.de/apks/a/shot.jpg\",\"full\":\"http://ouya.cweiske.de/apks/a/shot-full.jpg\"}},"
                + "{\"type\":\"image\",\"urls\":{\"thumbnail\":\"http://ouya.cweiske.de/gen-qr/https___github.com_x.png\",\"full\":\"http://ouya.cweiske.de/gen-qr/https___github.com_x.png\"}}]}";
    }

    public static void main(String[] args) throws Exception {
        check(StoreClient.validateServer(" http://OUYA.cweiske.de/ ").equals("http://ouya.cweiske.de"), "server normalised");
        for (String bad : List.of("ftp://x", "http://ouya.cweiske.de/api/v1", "http://u:p@host", "nonsense"))
            try { StoreClient.validateServer(bad); throw new AssertionError("accepted " + bad); } catch (IllegalArgumentException expected) {}

        StoreClient.Page page = StoreClient.parsePage(DISCOVER);
        check(page.rows.size() == 2, "empty/invalid rows dropped");
        StoreClient.Tile game = page.rows.get(0).tiles.get(0), bundle = page.rows.get(0).tiles.get(1), cat = page.rows.get(1).tiles.get(0);
        check(game.pkg.equals("com.ryo.armagetronadouya") && game.genres.equals("Racing, Retro"), "game tile");
        check(bundle.bundleApps.equals(List.of("com.Tribloos2", "com.Tribloos2.launcher")) && bundle.pkg == null, "bundle tile");
        check("Best_rated".equals(cat.page) && cat.pkg == null, "category tile opens its discover page");

        byte[] apk = "PK fake apk bytes".getBytes(StandardCharsets.UTF_8);
        String md5 = StoreClient.hex(MessageDigest.getInstance("MD5").digest(apk));
        Map<String, Fake> routes = new HashMap<>();
        StoreClient client = new StoreClient("http://ouya.cweiske.de", url -> {
            String key = url.toString();
            if (key.equals("http://ouya.cweiske.de/api/v1/discover/discover")) return new Fake(url, 200, DISCOVER.getBytes(StandardCharsets.UTF_8), null);
            if (key.equals("http://ouya.cweiske.de/api/v1/details?app=com.ryo.armagetronadouya")) return new Fake(url, 200, details(apk.length, md5).getBytes(StandardCharsets.UTF_8), null);
            if (key.equals("http://ouya.cweiske.de/api/v1/apps/53aeff84-ad7c-4239-9b0d-a4681a74a8dc/download"))
                return new Fake(url, 200, "{\"app\":{\"fileSize\":17,\"version\":\"53aeff84\",\"downloadLink\":\"http://ouya.cweiske.de/apks/com.ryo.armagetronadouya/com.ryo.armagetronadouya-8.apk\"}}".getBytes(StandardCharsets.UTF_8), null);
            if (key.equals("http://ouya.cweiske.de/apks/com.ryo.armagetronadouya/com.ryo.armagetronadouya-8.apk")) return new Fake(url, 302, new byte[0], "https://archive.example/dl/game.apk");
            if (key.equals("https://archive.example/dl/game.apk")) return new Fake(url, 200, apk, null);
            return new Fake(url, 404, new byte[0], null);
        });
        check(client.page(null).rows.size() == 2, "front page fetched");
        StoreClient.Game g = client.game("com.ryo.armagetronadouya");
        check(g.title.equals("Armagetron Advanced") && g.developer.equals("ryo89") && g.players == 4 && g.versionCode == 8 && g.fileSize == apk.length, "details parsed");
        check(g.media.size() == 1 && g.media.get(0).full.endsWith("shot-full.jpg"), "QR code media skipped");
        File dir = Files.createTempDirectory("store").toFile(), target = new File(dir, "game.apk");
        long[] seen = {0};
        client.download(g, target, new StoreClient.Progress() { public void update(long d, long t) { seen[0] = d; } public boolean cancelled() { return false; } });
        check(Arrays.equals(Files.readAllBytes(target.toPath()), apk) && seen[0] == apk.length, "download followed http->https redirect and saved the APK");
        g.md5 = "00000000000000000000000000000000";
        try { client.download(g, target, null); throw new AssertionError("bad checksum accepted"); } catch (IOException expected) {}
        check(!new File(dir, "game.apk.part").exists(), "partial file removed after a failed check");
        try { client.game("missing.game"); throw new AssertionError("404 accepted"); } catch (IOException expected) { check(expected.getMessage().contains("404"), "HTTP error reported"); }
        System.out.println("StoreClientTest: PASS");
    }
}
