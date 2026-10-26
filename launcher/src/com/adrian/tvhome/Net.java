package com.adrian.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny HTTP + image loader (memory LRU + disk cache), no dependencies. */
final class Net {
    interface Cb<T> { void done(T result); }

    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final ExecutorService IO = Executors.newFixedThreadPool(4);
    static File cacheDir;

    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(64 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    static byte[] fetch(String url, String postJson) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) TvHome/1.0");
        if (postJson != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(postJson.getBytes("UTF-8"));
            os.close();
        }
        if (c.getResponseCode() >= 400) throw new RuntimeException("HTTP " + c.getResponseCode());
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        c.disconnect();
        return bo.toByteArray();
    }

    static String text(String url, String postJson) throws Exception {
        return new String(fetch(url, postJson), "UTF-8");
    }

    static void bitmap(final String url, final Cb<Bitmap> cb) {
        if (url == null) return;
        Bitmap hit = MEM.get(url);
        if (hit != null) { cb.done(hit); return; }
        IO.execute(() -> {
            Bitmap b = null;
            try {
                File f = cacheDir == null ? null : new File(cacheDir, "img_" + md5(url));
                byte[] data;
                if (f != null && f.exists()) {
                    data = readAll(f);
                } else {
                    data = fetch(url, null);
                    if (f != null) { FileOutputStream fo = new FileOutputStream(f); fo.write(data); fo.close(); }
                }
                b = BitmapFactory.decodeByteArray(data, 0, data.length);
            } catch (Exception ignored) { }
            if (b != null) MEM.put(url, b);
            final Bitmap res = b;
            if (res != null) MAIN.post(() -> cb.done(res));
        });
    }

    static void into(final ImageView iv, final String url) {
        iv.setTag(url);
        bitmap(url, b -> {
            if (!url.equals(iv.getTag())) return;
            boolean wasEmpty = iv.getDrawable() == null;
            iv.setImageBitmap(b);
            if (wasEmpty) { iv.setAlpha(0f); iv.animate().alpha(1f).setDuration(250).start(); }
        });
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] d = new byte[(int) f.length()];
        int off = 0, n;
        while (off < d.length && (n = in.read(d, off, d.length - off)) > 0) off += n;
        in.close();
        return d;
    }

    static String md5(String s) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : h) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }
}
