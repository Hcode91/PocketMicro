package io.github.hozdogus.pocketouya;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small background image loader for Store covers and screenshots (memory cache only). */
final class StoreImages {
    private static final ExecutorService pool = Executors.newFixedThreadPool(3);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private StoreImages() {}

    static void load(StoreClient client, String url, ImageView view, int maxWidth) {
        view.setTag(url);
        if (url == null || url.isEmpty()) { view.setImageDrawable(null); return; }
        Bitmap hit = cache.get(url + "@" + maxWidth);
        if (hit != null) { view.setImageBitmap(hit); return; }
        view.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap bitmap = null;
            try {
                HttpURLConnection c = client.open(url, 15000);
                byte[] bytes;
                try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[16384]; int total = 0;
                    for (int n; (n = in.read(buf)) != -1; ) { total += n; if (total > MAX_BYTES) throw new java.io.IOException("image too large"); out.write(buf, 0, n); }
                    bytes = out.toByteArray();
                } finally { c.disconnect(); }
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = 1;
                while (bounds.outWidth / (opts.inSampleSize * 2) >= maxWidth) opts.inSampleSize *= 2;
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                if (bitmap != null) cache.put(url + "@" + maxWidth, bitmap);
            } catch (Exception ignored) {}
            final Bitmap result = bitmap;
            main.post(() -> { if (result != null && url.equals(view.getTag())) view.setImageBitmap(result); });
        });
    }
}
