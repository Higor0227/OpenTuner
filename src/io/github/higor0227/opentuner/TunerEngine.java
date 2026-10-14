// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.opentuner;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import io.github.higor0227.opentuner.dsp.PitchDetector;

/** Microphone capture thread feeding the pitch detector. Results are read with {@link #snapshot}. */
public final class TunerEngine {
    private static final String TAG = "OpenTuner";

    public interface Listener {
        void onAudioError(String message);
    }

    private final Context context;
    private final Listener listener;
    private Thread thread;
    private volatile boolean running;
    private volatile float gateMarginDb = 12f;

    // latest published result (guarded by lock)
    private final Object lock = new Object();
    private boolean voiced, stable;
    private double frequency;
    private float level, confidence;
    private long resultTimeMs;

    public TunerEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void setGateMarginDb(float db) { gateMarginDb = db; }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "opentuner-audio");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try { t.join(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        synchronized (lock) { voiced = false; stable = false; }
    }

    /** Copies the latest result into {@code out}: {voiced, stable, freq, level, confidence, ageMs}. */
    public void snapshot(double[] out) {
        synchronized (lock) {
            out[0] = voiced ? 1 : 0;
            out[1] = stable ? 1 : 0;
            out[2] = frequency;
            out[3] = level;
            out[4] = confidence;
            out[5] = resultTimeMs == 0 ? 1e9 : (System.currentTimeMillis() - resultTimeMs);
        }
    }

    /** rateOut[0] = sample rate, rateOut[1] = 1 for float samples, 0 for 16-bit PCM. */
    private AudioRecord open(int[] rateOut) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        boolean unprocessed = am != null
                && "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        int[] sources = unprocessed
                ? new int[] {MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC}
                : new int[] {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        int[] rates = {48000, 44100, 22050, 16000};
        int[] encodings = {AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT};
        for (int encoding : encodings) {
            int bytesPerSample = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
            for (int source : sources) {
                for (int rate : rates) {
                    int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, encoding);
                    if (min <= 0) continue;
                    AudioRecord rec = null;
                    try {
                        rec = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, encoding,
                                Math.max(min, rate / 5 * bytesPerSample));
                        if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                            rateOut[0] = rate;
                            rateOut[1] = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 1 : 0;
                            return rec;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "AudioRecord source=" + source + " rate=" + rate + " enc=" + encoding + " failed", e);
                    }
                    if (rec != null) rec.release();
                }
            }
        }
        return null;
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int[] rate = new int[2];
        AudioRecord rec;
        try {
            rec = open(rate);
        } catch (SecurityException e) {
            rec = null;
        }
        if (rec == null) {
            running = false;
            listener.onAudioError(context.getString(R.string.mic_unavailable));
            return;
        }
        PitchDetector detector = new PitchDetector(rate[0]);
        boolean floatMode = rate[1] == 1;
        float[] buf = new float[rate[0] / 100]; // 10 ms
        short[] pcm = floatMode ? null : new short[buf.length];
        try {
            rec.startRecording();
            while (running) {
                int n;
                if (floatMode) {
                    n = rec.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                } else {
                    n = rec.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                    for (int i = 0; i < n; i++) buf[i] = pcm[i] * (1f / 32768f);
                }
                if (n < 0) {
                    Log.w(TAG, "read error " + n);
                    break;
                }
                detector.setGateMarginDb(gateMarginDb);
                if (n > 0 && detector.feed(buf, n)) {
                    PitchDetector.Result r = detector.latest();
                    synchronized (lock) {
                        voiced = r.voiced;
                        stable = r.stable;
                        if (r.stable) frequency = r.frequency;
                        level = r.levelDb;
                        confidence = r.confidence;
                        resultTimeMs = System.currentTimeMillis();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "capture failed", e);
            listener.onAudioError(context.getString(R.string.capture_error));
        } finally {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
            running = false;
        }
    }
}
