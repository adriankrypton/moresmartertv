package com.adrian.airplay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** MediaCodec H.264 video to a Surface and AAC(-ELD) audio to an AudioTrack. */
final class Player implements MediaSink {
    interface Listener {
        void onVideoSize(int w, int h);
        void onFirstFrame();
    }

    private final Listener listener;
    private Surface surface;
    private MediaCodec video;
    private byte[] lastSps;
    private boolean firstFrameSeen;

    private MediaCodec audio;
    private AudioTrack track;
    private volatile float volume = 1f;
    private int audioCt;
    private volatile boolean audioEnabled = true;

    void setAudioEnabled(boolean on) { audioEnabled = on; }

    Player(Listener l) { listener = l; }

    synchronized void setSurface(Surface s) {
        surface = s;
        if (s == null) releaseVideo();
    }

    synchronized void stopAll() {
        releaseVideo();
        releaseAudio();
        lastSps = null;
        firstFrameSeen = false;
    }

    void setVolume(float v) {
        volume = v;
        AudioTrack t = track;
        if (t != null) t.setVolume(v);
    }

    // ---------------------------------------------------------------- video

    @Override public synchronized void onVideoConfig(byte[] sps, byte[] pps, int w, int h) {
        if (w > 0 && h > 0) listener.onVideoSize(w, h);
        if (video != null && Arrays.equals(sps, lastSps)) return;
        releaseVideo();
        if (surface == null) return;
        lastSps = sps;
        try {
            MediaFormat f = MediaFormat.createVideoFormat("video/avc", w > 0 ? w : 1920, h > 0 ? h : 1080);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)));
            f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 << 20);
            video = MediaCodec.createDecoderByType("video/avc");
            video.configure(f, surface, null, 0);
            video.start();
            final MediaCodec codec = video;
            new Thread(() -> drainVideo(codec), "video-out").start();
        } catch (Exception e) {
            Log.e(RtspServer.TAG, "video decoder", e);
            video = null;
        }
    }

    @Override public void onVideoFrame(byte[] annexB) {
        MediaCodec codec;
        synchronized (this) { codec = video; }
        if (codec == null) return;
        try {
            int i = codec.dequeueInputBuffer(50_000);
            if (i < 0) return;
            ByteBuffer b = codec.getInputBuffer(i);
            b.clear();
            b.put(annexB);
            codec.queueInputBuffer(i, 0, annexB.length, System.nanoTime() / 1000, 0);
        } catch (Exception e) {
            Log.w(RtspServer.TAG, "video input: " + e);
        }
    }

    private void drainVideo(MediaCodec codec) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int o = codec.dequeueOutputBuffer(info, 100_000);
                if (o >= 0) {
                    codec.releaseOutputBuffer(o, true);
                    if (!firstFrameSeen) { firstFrameSeen = true; listener.onFirstFrame(); }
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
                    if (f.containsKey("crop-right")) {
                        w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
                        h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
                    }
                    listener.onVideoSize(w, h);
                }
            }
        } catch (Exception ignored) {
            // codec released
        }
    }

    private void releaseVideo() {
        if (video != null) {
            try { video.stop(); } catch (Exception ignored) { }
            try { video.release(); } catch (Exception ignored) { }
            video = null;
        }
    }

    private static byte[] withStartCode(byte[] nal) {
        byte[] r = new byte[nal.length + 4];
        r[3] = 1;
        System.arraycopy(nal, 0, r, 4, nal.length);
        return r;
    }

    // ---------------------------------------------------------------- audio

    @Override public synchronized void onAudioFormat(int ct, int spf) {
        releaseAudio();
        audioCt = ct;
        if (!audioEnabled) return;
        byte[] asc;
        if (ct == 8) asc = new byte[]{(byte) 0xF8, (byte) 0xE8, 0x50, 0x00};   // AAC-ELD 44.1k stereo, 480 spf
        else if (ct == 4) asc = new byte[]{0x12, 0x10};                         // AAC-LC 44.1k stereo
        else { Log.w(RtspServer.TAG, "unsupported audio codec ct=" + ct); return; }
        try {
            MediaFormat f = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(asc));
            f.setInteger(MediaFormat.KEY_IS_ADTS, 0);
            audio = MediaCodec.createDecoderByType("audio/mp4a-latm");
            audio.configure(f, null, null, 0);
            audio.start();
            int min = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(44100)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min * 2, 16384))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            track.setVolume(volume);
            track.play();
            final MediaCodec codec = audio;
            final AudioTrack t = track;
            new Thread(() -> drainAudio(codec, t), "audio-out").start();
        } catch (Exception e) {
            Log.e(RtspServer.TAG, "audio decoder", e);
            releaseAudio();
        }
    }

    @Override public void onAudioFrame(byte[] frame, int length) {
        MediaCodec codec;
        synchronized (this) { codec = audio; }
        if (codec == null) return;
        try {
            int i = codec.dequeueInputBuffer(20_000);
            if (i < 0) return;
            ByteBuffer b = codec.getInputBuffer(i);
            b.clear();
            b.put(frame, 0, length);
            codec.queueInputBuffer(i, 0, length, System.nanoTime() / 1000, 0);
        } catch (Exception e) {
            Log.w(RtspServer.TAG, "audio input: " + e);
        }
    }

    @Override public void onAudioFlush() {
        AudioTrack t = track;
        if (t != null) { try { t.flush(); } catch (Exception ignored) { } }
    }

    private void drainAudio(MediaCodec codec, AudioTrack t) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] pcm = new byte[16384];
        try {
            while (true) {
                int o = codec.dequeueOutputBuffer(info, 100_000);
                if (o < 0) continue;
                ByteBuffer b = codec.getOutputBuffer(o);
                int n = Math.min(info.size, pcm.length);
                b.position(info.offset);
                b.get(pcm, 0, n);
                codec.releaseOutputBuffer(o, false);
                t.write(pcm, 0, n);
            }
        } catch (Exception ignored) {
            // codec released
        }
    }

    private void releaseAudio() {
        if (audio != null) {
            try { audio.stop(); } catch (Exception ignored) { }
            try { audio.release(); } catch (Exception ignored) { }
            audio = null;
        }
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) { }
            track.release();
            track = null;
        }
    }
}
