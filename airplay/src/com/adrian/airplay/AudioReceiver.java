package com.adrian.airplay;

import android.util.Log;

import com.github.serezhka.jap2lib.AirPlay;
import com.github.serezhka.jap2lib.rtsp.AudioStreamInfo;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

/** RTP audio over UDP (AES-CBC encrypted), plus an idle control socket. */
final class AudioReceiver {
    private final AirPlay airPlay;
    private final MediaSink sink;
    private final DatagramSocket data, control;
    private final int ct, spf;
    private volatile boolean closed;
    private int lastSeq = -1;

    AudioReceiver(AirPlay airPlay, MediaSink sink, AudioStreamInfo info) throws Exception {
        this.airPlay = airPlay;
        this.sink = sink;
        this.data = new DatagramSocket(0);
        this.control = new DatagramSocket(0);
        this.ct = info.getCompressionType() == null ? 8 : codeOf(info.getCompressionType());
        this.spf = info.getSamplesPerFrame() > 0 ? info.getSamplesPerFrame() : 480;
    }

    private static int codeOf(AudioStreamInfo.CompressionType t) {
        switch (t) {
            case LPCM: return 1;
            case ALAC: return 2;
            case AAC: return 4;
            case OPUS: return 32;
            default: return 8;
        }
    }

    int dataPort() { return data.getLocalPort(); }
    int controlPort() { return control.getLocalPort(); }

    void start() {
        sink.onAudioFormat(ct, spf);
        new Thread(this::run, "audio-rx").start();
        new Thread(() -> {
            byte[] b = new byte[2048];
            while (!closed) {
                try { control.receive(new DatagramPacket(b, b.length)); } catch (Exception e) { return; }
            }
        }, "audio-ctrl").start();
    }

    void flush() {
        lastSeq = -1;
        sink.onAudioFlush();
    }

    void close() {
        closed = true;
        data.close();
        control.close();
    }

    private void run() {
        byte[] buf = new byte[4096];
        while (!closed) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                data.receive(p);
                int len = p.getLength();
                if (len <= 12) continue;
                int seq = ((buf[2] & 0xff) << 8) | (buf[3] & 0xff);
                if (lastSeq >= 0) {
                    int d = (seq - lastSeq) & 0xffff;
                    if (d == 0 || d > 0x8000) continue; // duplicate or late packet
                }
                lastSeq = seq;
                int n = len - 12;
                byte[] frame = new byte[n];
                System.arraycopy(buf, 12, frame, 0, n);
                airPlay.decryptAudio(frame, n);
                sink.onAudioFrame(frame, n);
            } catch (Exception e) {
                if (!closed) Log.w(RtspServer.TAG, "audio", e);
                return;
            }
        }
    }
}
