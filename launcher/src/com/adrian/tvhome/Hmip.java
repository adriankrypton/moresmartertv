package com.adrian.tvhome;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Homematic IP cloud API (the access point has no local interface). */
final class Hmip {
    private static final String CLIENT = "{\"clientCharacteristics\":{\"apiVersion\":\"10\",\"applicationIdentifier\":\"tvhome\","
            + "\"applicationVersion\":\"1.0\",\"deviceManufacturer\":\"none\",\"deviceType\":\"Computer\","
            + "\"language\":\"de_DE\",\"osType\":\"Linux\",\"osVersion\":\"1.0\"}}";

    private final String rest, sgtin, clientAuth, authToken;

    /** Throws if the launcher has not been coupled (no assets/hmip.json). */
    Hmip(Context c) throws Exception {
        JSONObject cfg;
        try (InputStream in = c.getAssets().open("hmip.json")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            cfg = new JSONObject(b.toString("UTF-8"));
        }
        rest = cfg.getString("urlREST");
        sgtin = cfg.getString("sgtin");
        clientAuth = cfg.getString("clientAuth");
        authToken = cfg.getString("authToken");
    }

    private String post(String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(rest + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        c.setRequestProperty("accept", "application/json");
        c.setRequestProperty("VERSION", "12");
        c.setRequestProperty("CLIENTAUTH", clientAuth);
        c.setRequestProperty("AUTHTOKEN", authToken);
        c.setRequestProperty("ACCESSPOINT-ID", sgtin);
        try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
        if (c.getResponseCode() >= 400) throw new RuntimeException("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    /** Heating groups (one per room) with actual and target temperature. */
    List<Bosch.Room> rooms() throws Exception {
        JSONObject state = new JSONObject(post("/hmip/home/getCurrentState", CLIENT));
        JSONObject groups = state.optJSONObject("groups");
        List<Bosch.Room> out = new ArrayList<>();
        if (groups == null) return out;
        Iterator<String> it = groups.keys();
        while (it.hasNext()) {
            JSONObject g = groups.getJSONObject(it.next());
            if (!"HEATING".equals(g.optString("type"))) continue;
            Bosch.Room r = new Bosch.Room();
            r.id = g.getString("id");
            r.hmipGroup = r.id;
            r.name = g.optString("label", "Raum").trim();
            r.current = g.isNull("actualTemperature") ? Double.NaN : g.optDouble("actualTemperature", Double.NaN);
            r.setpoint = g.optDouble("setPointTemperature", Double.NaN);
            r.mode = g.optString("controlMode", null);
            out.add(r);
        }
        return out;
    }

    void setSetpoint(String groupId, double celsius) throws Exception {
        post("/hmip/group/heating/setSetPointTemperature",
                new JSONObject().put("groupId", groupId).put("setPointTemperature", celsius).toString());
    }
}
