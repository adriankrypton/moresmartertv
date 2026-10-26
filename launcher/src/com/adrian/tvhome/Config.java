package com.adrian.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Personal device settings. They live in {@code assets/home.json} (excluded from git) or are
 * found at runtime, so no addresses or names of a real household end up in the source:
 *
 * <pre>
 * { "govee": { "ip": "192.168.1.50", "name": "LED Strip" },
 *   "bosch": "192.168.1.60",
 *   "camera": "UniFi Protect" }
 * </pre>
 */
final class Config {
    private static JSONObject file;

    private static synchronized JSONObject file(Context c) {
        if (file != null) return file;
        try (InputStream in = c.getAssets().open("home.json")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            file = new JSONObject(b.toString("UTF-8"));
        } catch (Exception e) {
            file = new JSONObject();
        }
        return file;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** Govee light address: from home.json, else the one found by the LAN scan (or null). */
    static String goveeIp(Context c) {
        JSONObject g = file(c).optJSONObject("govee");
        if (g != null && g.has("ip")) return g.optString("ip");
        return prefs(c).getString("goveeIp", null);
    }

    static String goveeName(Context c) {
        JSONObject g = file(c).optJSONObject("govee");
        if (g != null && g.has("name")) return g.optString("name");
        return prefs(c).getString("goveeName", "LED");
    }

    static void rememberGovee(Context c, String ip, String sku) {
        prefs(c).edit().putString("goveeIp", ip).putString("goveeName", "Govee " + sku).apply();
    }

    /** Bosch Smart Home Controller address, or null when not configured. */
    static String boschHost(Context c) {
        String h = file(c).optString("bosch", "");
        return h.isEmpty() ? null : h;
    }

    static String cameraName(Context c) {
        return file(c).optString("camera", "UniFi Protect");
    }
}
