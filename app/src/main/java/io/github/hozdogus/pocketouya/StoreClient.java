package io.github.hozdogus.pocketouya;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/**
 * Client for a stouyapi community OUYA server (default http://ouya.cweiske.de): the Discover
 * pages, game detail pages and APK download links that the OUYA's own store used. Plain Java
 * (org.json) so it runs in host tests. The public server answers over plain HTTP and redirects
 * HTTPS to HTTP; game files may be served by other hosts (e.g. the Internet Archive) through
 * redirects, which are followed for http/https only.
 */
public final class StoreClient {
    public static final String DEFAULT_SERVER = "http://ouya.cweiske.de";
    static final int MAX_JSON = 4 * 1024 * 1024, MAX_REDIRECTS = 6;
    static final long MAX_APK = 4L * 1024 * 1024 * 1024;

    public interface Connections { HttpURLConnection open(URL url) throws IOException; }
    public interface Progress { void update(long done, long total); boolean cancelled(); }

    private final String base;
    private final Connections connections;

    public StoreClient(String server) { this(server, url -> (HttpURLConnection) url.openConnection()); }
    StoreClient(String server, Connections connections) { base = validateServer(server); this.connections = connections; }
    public String server() { return base; }

    /** http(s)://host[:port] with no credentials, path, query or fragment. */
    public static String validateServer(String value) {
        try {
            URI u = new URI(value == null ? "" : value.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null)
                throw new IllegalArgumentException("Use a server address like http://ouya.cweiske.de");
            String path = u.getRawPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) throw new IllegalArgumentException("Use the server address only, without /api/...");
            return new URI(scheme, null, u.getHost().toLowerCase(Locale.ROOT), u.getPort(), null, null, null).toASCIIString();
        } catch (URISyntaxException e) { throw new IllegalArgumentException("Invalid server address", e); }
    }

    // ---- model ----
    public static final class Tile {
        public String type = "", title = "", image = "", pkg, page, bundle;
        public List<String> bundleApps = new ArrayList<>();
        public boolean premium, inAppPurchases;
        public String genres = "";
    }
    public static final class Row { public String title = ""; public final List<Tile> tiles = new ArrayList<>(); }
    public static final class Page { public String title = ""; public final List<Row> rows = new ArrayList<>(); }
    public static final class Media { public String thumbnail = "", full = ""; }
    public static final class Game {
        public String pkg = "", title = "", description = "", developer = "", genres = "", age = "", versionName = "", versionUuid = "", md5 = "", tileImage = "";
        public long fileSize; public int versionCode, players; public boolean premium, inAppPurchases; public double rating; public int ratingCount;
        public final List<Media> media = new ArrayList<>();
    }

    // ---- ouya:// links ----
    /** "ouya://launcher/discover/NAME" -> NAME. */
    static String discoverName(String url) {
        String p = "ouya://launcher/discover/";
        return url != null && url.startsWith(p) && url.length() > p.length() ? url.substring(p.length()) : null;
    }
    static String query(String url, String key) {
        int q = url == null ? -1 : url.indexOf('?');
        if (q < 0) return null;
        for (String part : url.substring(q + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) try { return URLDecoder.decode(part.substring(eq + 1), "UTF-8"); } catch (UnsupportedEncodingException e) { return null; }
        }
        return null;
    }

    // ---- parsing ----
    static String join(JSONArray a) {
        if (a == null) return "";
        List<String> out = new ArrayList<>(); for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return String.join(", ", out);
    }

    static Page parsePage(String json) throws JSONException {
        JSONObject d = new JSONObject(json);
        Page page = new Page(); page.title = d.optString("title");
        JSONArray tiles = d.optJSONArray("tiles"), rows = d.optJSONArray("rows");
        List<Tile> all = new ArrayList<>();
        if (tiles != null) for (int i = 0; i < tiles.length(); i++) all.add(parseTile(tiles.optJSONObject(i)));
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i); if (r == null) continue;
            Row row = new Row(); row.title = r.optString("title");
            JSONArray refs = r.optJSONArray("tiles");
            if (refs != null) for (int k = 0; k < refs.length(); k++) {
                int idx = refs.optInt(k, -1);
                if (idx >= 0 && idx < all.size() && all.get(idx) != null) row.tiles.add(all.get(idx));
            }
            if (!row.tiles.isEmpty()) page.rows.add(row);
        }
        return page;
    }

    static Tile parseTile(JSONObject t) {
        if (t == null) return null;
        Tile tile = new Tile();
        tile.type = t.optString("type"); tile.title = t.optString("title"); tile.image = t.optString("image");
        tile.premium = t.optBoolean("premium"); tile.inAppPurchases = t.optBoolean("inAppPurchases"); tile.genres = join(t.optJSONArray("genres"));
        String url = t.optString("url");
        if (tile.type.equals("discover")) tile.page = discoverName(url);
        else if (tile.type.equals("details_page")) {
            tile.bundle = query(url, "page");
            JSONObject b = t.optJSONObject("bundle"); JSONArray apps = b == null ? null : b.optJSONArray("apps");
            if (apps != null) for (int i = 0; i < apps.length(); i++) tile.bundleApps.add(apps.optString(i));
        } else {
            tile.pkg = t.has("package") ? t.optString("package") : query(url, "app");
        }
        return tile;
    }

    static Game parseGame(String pkg, String json) throws JSONException {
        JSONObject d = new JSONObject(json);
        Game g = new Game(); g.pkg = pkg;
        g.title = d.optString("title", pkg); g.description = d.optString("description"); g.genres = join(d.optJSONArray("genres"));
        g.age = d.optString("suggestedAge"); g.premium = d.optBoolean("premium"); g.inAppPurchases = d.optBoolean("inAppPurchases");
        JSONArray players = d.optJSONArray("gamerNumbers"); if (players != null) for (int i = 0; i < players.length(); i++) g.players = Math.max(g.players, players.optInt(i));
        JSONObject rating = d.optJSONObject("rating"); if (rating != null) { g.rating = rating.optDouble("average", 0); g.ratingCount = rating.optInt("count"); }
        JSONObject apk = d.optJSONObject("apk");
        if (apk != null) { g.fileSize = apk.optLong("fileSize"); g.md5 = apk.optString("md5sum").toLowerCase(Locale.ROOT); g.versionCode = apk.optInt("versionCode"); if (apk.has("package")) g.pkg = apk.optString("package", pkg); }
        JSONObject version = d.optJSONObject("version");
        if (version != null) { g.versionName = version.optString("number"); g.versionUuid = version.optString("uuid"); }
        JSONObject dev = d.optJSONObject("developer"); if (dev != null) g.developer = dev.optString("name");
        g.tileImage = d.optString("tileImage");
        JSONArray media = d.optJSONArray("mediaTiles");
        if (media != null) for (int i = 0; i < media.length(); i++) {
            JSONObject m = media.optJSONObject(i); if (m == null || !"image".equals(m.optString("type"))) continue;
            JSONObject urls = m.optJSONObject("urls"); if (urls == null) continue;
            Media x = new Media(); x.thumbnail = urls.optString("thumbnail"); x.full = urls.optString("full", x.thumbnail);
            if (!x.thumbnail.contains("/gen-qr/")) g.media.add(x);   // skip generated QR codes for web links
        }
        return g;
    }

    static String parseDownloadLink(String json) throws JSONException {
        JSONObject app = new JSONObject(json).getJSONObject("app");
        String link = app.getString("downloadLink");
        if (!(link.startsWith("http://") || link.startsWith("https://"))) throw new JSONException("Unsupported download link");
        return link;
    }

    // ---- network ----
    static String segment(String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 256 || value.indexOf('/') >= 0) throw new IOException("Invalid identifier");
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    /** Opens url following up to MAX_REDIRECTS http/https redirects (HttpURLConnection does not cross schemes). */
    HttpURLConnection open(String url, int timeoutMs) throws IOException {
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL u = new URL(url);
            if (!(u.getProtocol().equals("http") || u.getProtocol().equals("https"))) throw new IOException("Unsupported address " + u.getProtocol());
            HttpURLConnection c = connections.open(u);
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "PocketOuya-store/1");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && code != 304) {
                String next = c.getHeaderField("Location"); c.disconnect();
                if (next == null) throw new IOException("Redirect without location");
                url = new URL(u, next).toString(); continue;
            }
            if (code != 200) { c.disconnect(); throw new IOException("Server returned HTTP " + code + " for " + u.getPath()); }
            return c;
        }
        throw new IOException("Too many redirects");
    }

    String getJson(String path) throws IOException {
        HttpURLConnection c = open(base + path, 15000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384]; int total = 0;
            for (int n; (n = in.read(buf)) != -1; ) { total += n; if (total > MAX_JSON) throw new IOException("Server response too large"); out.write(buf, 0, n); }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally { c.disconnect(); }
    }

    public Page page(String name) throws IOException {
        try { return parsePage(getJson("/api/v1/discover/" + segment(name == null ? "discover" : name))); }
        catch (JSONException e) { throw new IOException("Unexpected store page format: " + e.getMessage(), e); }
    }
    public Game game(String pkg) throws IOException {
        try { return parseGame(pkg, getJson("/api/v1/details?app=" + URLEncoder.encode(pkg, "UTF-8"))); }
        catch (JSONException e) { throw new IOException("Unexpected game page format: " + e.getMessage(), e); }
    }
    public String downloadLink(String versionUuid) throws IOException {
        try { return parseDownloadLink(getJson("/api/v1/apps/" + segment(versionUuid) + "/download")); }
        catch (JSONException e) { throw new IOException("Unexpected download format: " + e.getMessage(), e); }
    }

    /** Downloads the game's APK to target, checking the server's size and MD5 when given. */
    public void download(Game g, File target, Progress progress) throws IOException {
        if (g.versionUuid.isEmpty()) throw new IOException("This game has no downloadable version on the server");
        HttpURLConnection c = open(downloadLink(g.versionUuid), 30000);
        File part = new File(target.getPath() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : g.fileSize, done = 0;
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) != -1; ) {
                if (progress != null && progress.cancelled()) throw new InterruptedIOException("Download cancelled");
                done += n; if (done > MAX_APK) throw new IOException("Download too large");
                md5.update(buf, 0, n); out.write(buf, 0, n);
                if (progress != null) progress.update(done, total);
            }
            if (g.fileSize > 0 && done != g.fileSize) throw new IOException("Download incomplete: got " + done + " of " + g.fileSize + " bytes");
            String got = hex(md5.digest());
            if (!g.md5.isEmpty() && !g.md5.equals(got)) throw new IOException("Downloaded file does not match the server's checksum");
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
        catch (IOException e) { part.delete(); throw e; }
        finally { c.disconnect(); }
        if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target.getName());
        if (!part.renameTo(target)) throw new IOException("Cannot save " + target.getName());
    }

    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x & 0xff)); return s.toString(); }
}
