package com.adrian.tvhome;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/** Govee LAN API (UDP): commands to port 4003, replies arrive on port 4002. */
final class Govee {
    static final class Status {
        boolean on;
        int brightness, r, g, b, kelvin;
    }

    private final String ip;

    Govee(String ip) { this.ip = ip; }

    void send(final JSONObject msg) {
        Net.IO.execute(() -> {
            try (DatagramSocket s = new DatagramSocket()) {
                byte[] d = new JSONObject().put("msg", msg).toString().getBytes("UTF-8");
                s.send(new DatagramPacket(d, d.length, InetAddress.getByName(ip), 4003));
            } catch (Exception ignored) { }
        });
    }

    void power(boolean on) {
        try { send(new JSONObject().put("cmd", "turn").put("data", new JSONObject().put("value", on ? 1 : 0))); }
        catch (Exception ignored) { }
    }

    void brightness(int pct) {
        try { send(new JSONObject().put("cmd", "brightness").put("data", new JSONObject().put("value", Math.max(1, Math.min(100, pct))))); }
        catch (Exception ignored) { }
    }

    void color(int r, int g, int b) {
        try {
            send(new JSONObject().put("cmd", "colorwc").put("data", new JSONObject()
                    .put("color", new JSONObject().put("r", r).put("g", g).put("b", b))
                    .put("colorTemInKelvin", 0)));
        } catch (Exception ignored) { }
    }

    void kelvin(int k) {
        try {
            send(new JSONObject().put("cmd", "colorwc").put("data", new JSONObject()
                    .put("color", new JSONObject().put("r", 0).put("g", 0).put("b", 0))
                    .put("colorTemInKelvin", k)));
        } catch (Exception ignored) { }
    }

    /**
     * Finds a Govee light on the LAN (multicast scan, LAN control must be enabled in the
     * Govee app) and reports {ip, sku} on the main thread, or null.
     */
    static void discover(final Net.Cb<String[]> cb) {
        Net.IO.execute(() -> {
            String[] found = null;
            try (DatagramSocket rx = new DatagramSocket(null)) {
                rx.setReuseAddress(true);
                rx.bind(new InetSocketAddress(4002));
                rx.setSoTimeout(3000);
                byte[] q = "{\"msg\":{\"cmd\":\"scan\",\"data\":{\"account_topic\":\"reserve\"}}}".getBytes("UTF-8");
                rx.send(new DatagramPacket(q, q.length, InetAddress.getByName("239.255.255.250"), 4001));
                byte[] buf = new byte[2048];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                rx.receive(p);
                JSONObject d = new JSONObject(new String(p.getData(), 0, p.getLength(), "UTF-8"))
                        .getJSONObject("msg").getJSONObject("data");
                found = new String[]{d.getString("ip"), d.optString("sku", "LED")};
            } catch (Exception ignored) { }
            final String[] res = found;
            Net.MAIN.post(() -> cb.done(res));
        });
    }

    /** Asks for devStatus and delivers the reply (or null) on the main thread. */
    void status(final Net.Cb<Status> cb) {
        Net.IO.execute(() -> {
            Status st = null;
            try (DatagramSocket rx = new DatagramSocket(null)) {
                rx.setReuseAddress(true);
                rx.bind(new InetSocketAddress(4002));
                rx.setSoTimeout(2000);
                byte[] q = "{\"msg\":{\"cmd\":\"devStatus\",\"data\":{}}}".getBytes("UTF-8");
                rx.send(new DatagramPacket(q, q.length, InetAddress.getByName(ip), 4003));
                byte[] buf = new byte[2048];
                long until = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < until) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    rx.receive(p);
                    JSONObject msg = new JSONObject(new String(p.getData(), 0, p.getLength(), "UTF-8")).getJSONObject("msg");
                    if (!"devStatus".equals(msg.optString("cmd"))) continue;
                    JSONObject d = msg.getJSONObject("data");
                    st = new Status();
                    st.on = d.optInt("onOff") == 1;
                    st.brightness = d.optInt("brightness");
                    JSONObject c = d.optJSONObject("color");
                    if (c != null) { st.r = c.optInt("r"); st.g = c.optInt("g"); st.b = c.optInt("b"); }
                    st.kelvin = d.optInt("colorTemInKelvin");
                    break;
                }
            } catch (Exception ignored) { }
            final Status res = st;
            Net.MAIN.post(() -> cb.done(res));
        });
    }
}
