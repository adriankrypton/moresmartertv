package com.adrian.airplay;

/** Receives decrypted media from the network side. */
interface MediaSink {
    void onVideoConfig(byte[] sps, byte[] pps, int width, int height);
    /** One access unit in Annex-B format (start codes). */
    void onVideoFrame(byte[] annexB);
    void onAudioFormat(int compressionType, int samplesPerFrame);
    void onAudioFrame(byte[] frame, int length);
    void onAudioFlush();
}
