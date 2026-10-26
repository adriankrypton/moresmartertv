package com.adrian.airplay;

import android.util.Log;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSArray;
import com.dd.plist.NSData;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSNumber;
import com.dd.plist.NSObject;
import com.dd.plist.PropertyListParser;
import com.github.serezhka.jap2lib.AirPlay;
import com.github.serezhka.jap2lib.Pairing;
import com.github.serezhka.jap2lib.rtsp.AudioStreamInfo;
import com.github.serezhka.jap2lib.rtsp.MediaStreamInfo;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** AirPlay 2 control channel (RTSP over TCP) for screen mirroring. */
final class RtspServer {
    static final String TAG = "AirPlayRx";
    static final int PORT = 7000;

    interface Listener {
        void onSessionStart();
        void onSessionEnd();
        void onVolume(float linear);
    }

    private final String deviceId;
    private volatile String name;
    private final Listener listener;
    private final MediaSink sink;
    private ServerSocket server;
    private volatile boolean running;

    RtspServer(String name, String deviceId, Listener listener, MediaSink sink) {
        this.name = name;
        this.deviceId = deviceId;
        this.listener = listener;
        this.sink = sink;
    }

    void setName(String n) { name = n; }

    void start() throws IOException {
        server = new ServerSocket(PORT);
        server.setReuseAddress(true);
        running = true;
        new Thread(() -> {
            while (running) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> serve(s), "rtsp-conn").start();
                } catch (IOException e) {
                    if (running) Log.w(TAG, "accept", e);
                }
            }
        }, "rtsp-accept").start();
    }

    void stop() {
        running = false;
        try { server.close(); } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- per connection

    private static final class Conn {
        final AirPlay airPlay = new AirPlay();
        MirrorReceiver mirror;
        AudioReceiver audio;
        DatagramSocket timing;

        void close() {
            if (mirror != null) { mirror.close(); mirror = null; }
            if (audio != null) { audio.close(); audio = null; }
            if (timing != null) { timing.close(); timing = null; }
        }
    }

    private void serve(Socket s) {
        Conn c = new Conn();
        Log.i(TAG, "connection from " + s.getInetAddress());
        try (Socket sock = s) {
            sock.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            while (true) {
                String requestLine = readLine(in);
                if (requestLine == null) break;
                if (requestLine.isEmpty()) continue;
                Map<String, String> headers = new LinkedHashMap<>();
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int i = line.indexOf(':');
                    if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
                }
                int len = headers.containsKey("content-length") ? Integer.parseInt(headers.get("content-length")) : 0;
                byte[] body = new byte[len];
                int off = 0;
                while (off < len) {
                    int n = in.read(body, off, len - off);
                    if (n < 0) throw new IOException("eof");
                    off += n;
                }
                String[] parts = requestLine.split(" ");
                String method = parts[0], uri = parts.length > 1 ? parts[1] : "";
                Response r = new Response(headers.get("cseq"));
                try {
                    handle(c, method, uri, headers, body, r);
                } catch (Exception e) {
                    Log.w(TAG, method + " " + uri + " failed", e);
                }
                out.write(r.bytes());
                out.flush();
            }
        } catch (Exception e) {
            Log.i(TAG, "connection closed: " + e);
        } finally {
            boolean hadMirror = c.mirror != null;
            c.close();
            if (hadMirror) listener.onSessionEnd();
        }
    }

    private void handle(Conn c, String method, String uri, Map<String, String> h, byte[] body, Response r) throws Exception {
        if (!"POST".equals(method) || !"/feedback".equals(uri)) Log.i(TAG, method + " " + uri + " (" + body.length + " bytes)");
        switch (method) {
            case "GET":
                if (uri.startsWith("/info")) { r.plist(info()); }
                return;
            case "POST":
                switch (uri) {
                    case "/pair-setup": c.airPlay.pairSetup(r.body); r.type = "application/octet-stream"; return;
                    case "/pair-verify": c.airPlay.pairVerify(new ByteArrayInputStream(body), r.body); r.type = "application/octet-stream"; return;
                    case "/fp-setup": c.airPlay.fairPlaySetup(new ByteArrayInputStream(body), r.body); r.type = "application/octet-stream"; return;
                    default: return; // /feedback, /audioMode, /command ...
                }
            case "SETUP": setup(c, body, r); return;
            case "GET_PARAMETER":
                r.text("volume: 0.000000\r\n");
                return;
            case "SET_PARAMETER":
                String p = new String(body, StandardCharsets.UTF_8).trim();
                if (p.startsWith("volume:")) {
                    float db = Float.parseFloat(p.substring(7).trim());
                    listener.onVolume(db <= -30f ? 0f : (float) Math.pow(10, db / 20f));
                }
                return;
            case "RECORD":
                r.header("Audio-Latency", "11025");
                r.header("Audio-Jack-Status", "connected; type=analog");
                return;
            case "FLUSH":
                if (c.audio != null) c.audio.flush();
                return;
            case "TEARDOWN":
                MediaStreamInfo info = body.length > 0 ? c.airPlay.rtspGetMediaStreamInfo(new ByteArrayInputStream(body)) : null;
                if (info == null || info.getStreamType() == MediaStreamInfo.StreamType.VIDEO) {
                    if (c.mirror != null) { c.mirror.close(); c.mirror = null; listener.onSessionEnd(); }
                }
                if (info == null || info.getStreamType() == MediaStreamInfo.StreamType.AUDIO) {
                    if (c.audio != null) { c.audio.close(); c.audio = null; }
                }
                return;
            case "OPTIONS":
                r.header("Public", "SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET");
                return;
            default:
        }
    }

    private void setup(Conn c, byte[] body, Response r) throws Exception {
        MediaStreamInfo info = c.airPlay.rtspGetMediaStreamInfo(new ByteArrayInputStream(body));
        if (info == null) {
            // First SETUP: keys for the session plus timing information.
            c.airPlay.rtspSetupEncryption(new ByteArrayInputStream(body));
            if (c.timing == null) c.timing = new DatagramSocket(0);
            NSDictionary res = new NSDictionary();
            res.put("eventPort", 0);
            res.put("timingPort", c.timing.getLocalPort());
            r.plist(res);
            return;
        }
        if (info.getStreamType() == MediaStreamInfo.StreamType.VIDEO) {
            if (c.mirror != null) c.mirror.close();
            c.mirror = new MirrorReceiver(c.airPlay, sink);
            c.mirror.start();
            listener.onSessionStart();
            NSArray streams = new NSArray(1);
            NSDictionary s = new NSDictionary();
            s.put("dataPort", c.mirror.port());
            s.put("type", 110);
            streams.setValue(0, s);
            NSDictionary res = new NSDictionary();
            res.put("streams", streams);
            r.plist(res);
        } else {
            AudioStreamInfo ai = (AudioStreamInfo) info;
            Log.i(TAG, "audio ct=" + ai.getCompressionType() + " fmt=" + ai.getAudioFormat() + " spf=" + ai.getSamplesPerFrame());
            if (c.audio != null) c.audio.close();
            c.audio = new AudioReceiver(c.airPlay, sink, ai);
            c.audio.start();
            NSArray streams = new NSArray(1);
            NSDictionary s = new NSDictionary();
            s.put("dataPort", c.audio.dataPort());
            s.put("controlPort", c.audio.controlPort());
            s.put("type", 96);
            streams.setValue(0, s);
            NSDictionary res = new NSDictionary();
            res.put("streams", streams);
            r.plist(res);
        }
    }

    private NSObject info() throws Exception {
        NSDictionary d = (NSDictionary) PropertyListParser.parse(Pairing.class.getResourceAsStream("/info-response.xml"));
        d.put("name", name);
        d.put("deviceID", deviceId);
        d.put("macAddress", deviceId);
        d.put("model", "AppleTV3,2");
        d.put("features", new NSNumber(Bonjour.FEATURES));
        d.put("pk", new NSData(Pairing.publicKey()));
        return d;
    }

    // ---------------------------------------------------------------- helpers

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
        }
        if (ch == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static final class Response {
        final String cseq;
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final StringBuilder extra = new StringBuilder();
        String type;

        Response(String cseq) { this.cseq = cseq; }

        void header(String k, String v) { extra.append(k).append(": ").append(v).append("\r\n"); }

        void plist(NSObject o) throws IOException {
            BinaryPropertyListWriter.write(body, o);
            type = "application/x-apple-binary-plist";
        }

        void text(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            body.write(b, 0, b.length);
            type = "text/parameters";
        }

        byte[] bytes() {
            StringBuilder sb = new StringBuilder("RTSP/1.0 200 OK\r\n");
            if (cseq != null) sb.append("CSeq: ").append(cseq).append("\r\n");
            sb.append("Server: AirTunes/220.68\r\n").append(extra);
            if (type != null && body.size() > 0) sb.append("Content-Type: ").append(type).append("\r\n");
            sb.append("Content-Length: ").append(body.size()).append("\r\n\r\n");
            byte[] head = sb.toString().getBytes(StandardCharsets.US_ASCII);
            byte[] all = new byte[head.length + body.size()];
            System.arraycopy(head, 0, all, 0, head.length);
            System.arraycopy(body.toByteArray(), 0, all, head.length, body.size());
            return all;
        }
    }

    static NSObject parse(byte[] b) throws Exception { return BinaryPropertyListParser.parse(b); }
}
