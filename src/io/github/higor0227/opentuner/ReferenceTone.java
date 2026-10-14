// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.opentuner;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Plays a soft reference tone (fundamental + two weak harmonics) with click-free fades. */
public final class ReferenceTone {
    private static final int RATE = 48000;
    private Thread thread;
    private volatile boolean playing;
    private volatile double freq = 440.0;

    public boolean isPlaying() { return playing; }

    public void setFrequency(double f) { freq = f; }

    public synchronized void start(double f) {
        freq = f;
        if (playing) return;
        playing = true;
        thread = new Thread(this::run, "opentuner-tone");
        thread.start();
    }

    public synchronized void stop() {
        playing = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    private void run() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min, 4096 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        float[] buf = new float[512];
        double phase = 0;
        float gain = 0f;
        float fadeStep = 1f / (RATE * 0.04f);
        try {
            track.play();
            boolean stopping = false;
            while (true) {
                if (!playing) stopping = true;
                double inc = 2 * Math.PI * freq / RATE;
                for (int i = 0; i < buf.length; i++) {
                    if (stopping) gain = Math.max(0f, gain - fadeStep);
                    else gain = Math.min(1f, gain + fadeStep);
                    double s = Math.sin(phase) + 0.25 * Math.sin(2 * phase) + 0.1 * Math.sin(3 * phase);
                    buf[i] = (float) (0.35 * gain * s);
                    phase += inc;
                    if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
                }
                track.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
                if (stopping && gain <= 0f) break;
            }
            track.stop();
        } catch (Exception ignored) {
        } finally {
            track.release();
        }
    }
}
