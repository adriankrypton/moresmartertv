package com.adrian.airplay;

import android.util.Log;

import com.github.serezhka.jap2lib.AirPlay;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** TCP mirroring data stream: 128-byte header + payload (H.264, AES-CTR encrypted). */
final class MirrorReceiver {
    private final AirPlay airPlay;
    private final MediaSink sink;
    private final ServerSocket server;
    private volatile Socket socket;
    private volatile boolean closed;

    MirrorReceiver(AirPlay airPlay, MediaSink sink) throws IOException {
        this.airPlay = airPlay;
        this.sink = sink;
        this.server = new ServerSocket(0);
    }

    int port() { return server.getLocalPort(); }

    void start() {
        new Thread(this::run, "mirror-rx").start();
    }

    void close() {
        closed = true;
        try { server.close(); } catch (Exception ignored) { }
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
    }

    private void run() {
        try {
            socket = server.accept();
            socket.setReceiveBufferSize(1 << 20);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] header = new byte[128];
            int frames = 0;
            while (!closed) {
                in.readFully(header);
                ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                int size = hb.getInt(0);
                int type = hb.getShort(4) & 0xff;
                if (size < 0 || size > 8 << 20) throw new IOException("bad payload size " + size);
                byte[] payload = new byte[size];
                in.readFully(payload);
                if (type == 0) {
                    airPlay.decryptVideo(payload);
                    if (toAnnexB(payload)) {
                        sink.onVideoFrame(payload);
                        if (++frames == 1) Log.i(RtspServer.TAG, "first video frame");
                    } else {
                        Log.w(RtspServer.TAG, "dropped undecryptable frame");
                    }
                } else if (type == 1) {
                    int w = (int) hb.getFloat(56), h = (int) hb.getFloat(60);
                    codecConfig(payload, w, h);
                }
            }
        } catch (Exception e) {
            if (!closed) Log.i(RtspServer.TAG, "mirror stream ended: " + e);
        } finally {
            close();
        }
    }

    /** avcC record -> SPS + PPS. */
    private void codecConfig(byte[] p, int w, int h) {
        int spsLen = ((p[6] & 0xff) << 8) | (p[7] & 0xff);
        byte[] sps = new byte[spsLen];
        System.arraycopy(p, 8, sps, 0, spsLen);
        int o = 8 + spsLen + 1;
        int ppsLen = ((p[o] & 0xff) << 8) | (p[o + 1] & 0xff);
        byte[] pps = new byte[ppsLen];
        System.arraycopy(p, o + 2, pps, 0, ppsLen);
        Log.i(RtspServer.TAG, "codec config " + w + "x" + h);
        sink.onVideoConfig(sps, pps, w, h);
    }

    /** Replaces the 4-byte NAL length prefixes with start codes in place. */
    private static boolean toAnnexB(byte[] p) {
        int i = 0;
        while (i + 4 <= p.length) {
            int n = ((p[i] & 0xff) << 24) | ((p[i + 1] & 0xff) << 16) | ((p[i + 2] & 0xff) << 8) | (p[i + 3] & 0xff);
            if (n <= 0 || i + 4 + n > p.length) return false;
            p[i] = 0; p[i + 1] = 0; p[i + 2] = 0; p[i + 3] = 1;
            i += 4 + n;
        }
        return i == p.length;
    }
}
