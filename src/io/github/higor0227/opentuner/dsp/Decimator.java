// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.opentuner.dsp;

/**
 * Streaming integer-factor decimator: windowed-sinc (Blackman) low-pass FIR evaluated
 * only at the output instants, followed by a 2nd-order Butterworth high-pass that removes
 * DC and rumble below the lowest note of interest.
 */
public final class Decimator {
    private final int factor;
    private final float[] taps;
    private final float[] hist;
    private int pos;
    private int phase;

    // high-pass biquad (RBJ, Q = 1/sqrt(2))
    private final float b0, b1, b2, a1, a2;
    private float x1, x2, y1, y2;

    public Decimator(int inputRate, int factor, float highPassHz) {
        this.factor = Math.max(1, factor);
        int numTaps = 16 * this.factor + 1;
        taps = new float[numTaps];
        double fc = 0.42 / this.factor; // cycles per input sample
        double sum = 0;
        int m = numTaps - 1;
        for (int i = 0; i < numTaps; i++) {
            double x = i - m / 2.0;
            double sinc = x == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
            double w = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / m) + 0.08 * Math.cos(4 * Math.PI * i / m);
            taps[i] = (float) (sinc * w);
            sum += taps[i];
        }
        for (int i = 0; i < numTaps; i++) taps[i] /= (float) sum;
        hist = new float[numTaps * 2];

        double fs = (double) inputRate / this.factor;
        double w0 = 2 * Math.PI * highPassHz / fs;
        double alpha = Math.sin(w0) / (2 * Math.sqrt(0.5));
        double cw = Math.cos(w0);
        double a0 = 1 + alpha;
        b0 = (float) ((1 + cw) / 2 / a0);
        b1 = (float) (-(1 + cw) / a0);
        b2 = b0;
        a1 = (float) (-2 * cw / a0);
        a2 = (float) ((1 - alpha) / a0);
    }

    public int factor() { return factor; }

    /** Processes {@code n} input samples; writes decimated output into {@code out}; returns count. */
    public int process(float[] in, int n, float[] out) {
        int count = 0;
        int len = taps.length;
        for (int i = 0; i < n; i++) {
            float s = in[i];
            hist[pos] = s;
            hist[pos + len] = s; // mirrored copy avoids modulo in the dot product
            pos++;
            if (pos == len) pos = 0;
            if (++phase >= factor) {
                phase = 0;
                float acc = 0;
                // hist[pos .. pos+len-1] holds samples oldest -> newest
                for (int k = 0; k < len; k++) acc += taps[k] * hist[pos + k];
                float y = b0 * acc + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1; x1 = acc; y2 = y1; y1 = y;
                out[count++] = y;
            }
        }
        return count;
    }
}
