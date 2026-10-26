package com.adrian.tvhome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Bosch Smart Home Controller local API (port 8444, client certificate auth). */
final class Bosch {

    static final class Room {
        String id, name, climateDevice;
        /** Set for Homematic IP rooms instead of a Bosch climate device. */
        String hmipGroup;
        double current = Double.NaN, setpoint = Double.NaN;
        String mode;
        boolean heating;
    }

    private final SSLContext ssl;
    private final String host;

    /** Throws when no controller is configured (assets/home.json) or not paired. */
    Bosch(Context c) throws Exception {
        host = Config.boschHost(c);
        if (host == null) throw new IllegalStateException("no Bosch controller configured");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = c.getAssets().open("bosch-client.p12")) { ks.load(in, "tvhome".toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "tvhome".toCharArray());
        // The controller presents a self-signed certificate on the local network.
        TrustManager[] trust = {new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
            @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), trust, null);
    }

    private String call(String method, String path, String body) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL("https://" + host + ":8444/smarthome" + path).openConnection();
        c.setSSLSocketFactory(ssl.getSocketFactory());
        c.setHostnameVerifier((h, s) -> host.equals(h));
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestMethod(method);
        c.setRequestProperty("api-version", "3.2");
        c.setRequestProperty("Content-Type", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
        }
        int code = c.getResponseCode();
        if (code >= 400) throw new RuntimeException("HTTP " + code);
        if (code == 204) return "";
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    /** Rooms that have a room climate control, with current and target temperature. */
    List<Room> rooms() throws Exception {
        JSONArray rooms = new JSONArray(call("GET", "/rooms", null));
        JSONArray devices = new JSONArray(call("GET", "/devices", null));
        JSONArray services = new JSONArray(call("GET", "/services", null));

        Map<String, Room> byId = new HashMap<>();
        for (int i = 0; i < rooms.length(); i++) {
            JSONObject r = rooms.getJSONObject(i);
            Room room = new Room();
            room.id = r.getString("id");
            room.name = r.optString("name", "Raum");
            byId.put(room.id, room);
        }
        Map<String, Room> byDevice = new HashMap<>();
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.getJSONObject(i);
            Room room = byId.get(d.optString("roomId"));
            if (room == null) continue;
            byDevice.put(d.getString("id"), room);
            if ("ROOM_CLIMATE_CONTROL".equals(d.optString("deviceModel"))) room.climateDevice = d.getString("id");
        }
        for (int i = 0; i < services.length(); i++) {
            JSONObject s = services.getJSONObject(i);
            Room room = byDevice.get(s.optString("deviceId"));
            JSONObject st = s.optJSONObject("state");
            if (room == null || st == null) continue;
            String id = s.optString("id");
            if ("RoomClimateControl".equals(id)) {
                room.climateDevice = s.getString("deviceId");
                room.setpoint = st.optDouble("setpointTemperature", Double.NaN);
                room.mode = st.optString("operationMode", null);
                room.heating = "HEATING".equals(st.optString("roomControlMode", "HEATING"));
            } else if ("TemperatureLevel".equals(id)) {
                // Prefer the climate control's value; otherwise any thermostat in the room.
                if (s.getString("deviceId").equals(room.climateDevice) || Double.isNaN(room.current)) {
                    room.current = st.optDouble("temperature", room.current);
                }
            }
        }
        List<Room> out = new ArrayList<>();
        for (int i = 0; i < rooms.length(); i++) {
            Room r = byId.get(rooms.getJSONObject(i).getString("id"));
            out.add(r); // rooms without a radiator yet are shown as placeholders
        }
        return out;
    }

    void setSetpoint(String climateDevice, double celsius) throws Exception {
        String body = new JSONObject().put("@type", "climateControlState").put("setpointTemperature", celsius).toString();
        call("PUT", "/devices/" + climateDevice + "/services/RoomClimateControl/state", body);
    }
}
