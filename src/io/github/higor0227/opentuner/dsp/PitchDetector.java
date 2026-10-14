// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.opentuner.dsp;

import java.util.Arrays;

/**
 * Monophonic pitch detector for tuning string instruments.
 *
 * Pipeline: decimation to ~8-9 kHz, high-pass, Hann-windowed FFT (zero padded 2x),
 * spectral peak picking with log-parabolic interpolation, harmonic-template scoring of
 * fundamental candidates (peak sub-multiples and peak differences, so a missing
 * fundamental is still found), refinement from the low harmonics and a short median
 * filter that rejects isolated octave jumps.
 */
public final class PitchDetector {

    public static final class Result {
        /** Signal above the noise gate. */
        public boolean voiced;
        /** Pitch confirmed by recent frames; {@link #frequency} is valid only when true. */
        public boolean stable;
        public double frequency;
        /** Fraction of spectral peak weight explained by the harmonic series (0..1). */
        public float confidence;
        public float levelDb;
        public float noiseFloorDb;
    }

    private static final int WINDOW = 2048;
    private static final int NFFT = 4096;
    private static final int HOP = 256;
    private static final double F_MIN = 25.0;
    private static final double F_MAX = 1800.0;
    private static final double TOP_ANALYSIS_HZ = 4000.0;
    private static final float DYN_RANGE_DB = 50f;
    private static final int MAX_PEAKS = 20;
    private static final int MAX_HARMONIC = 40;
    private static final double MISS_PENALTY = 0.6;
    private static final int HISTORY = 5;
    private static final double AGREE_CENTS = 35.0;
    private static final float MIN_CONFIDENCE = 0.55f;
    private static final int MIN_AGREE = 3;

    private final Decimator decimator;
    private final double fs;
    private final float[] ring = new float[WINDOW];
    private int ringPos;
    private int filled;
    private int sinceHop;
    private final float[] decBuf = new float[8192];

    private final Fft fft = new Fft(NFFT);
    private final float[] hann = new float[WINDOW];
    private final float[] re = new float[NFFT];
    private final float[] im = new float[NFFT];
    private final float[] db = new float[NFFT / 2];

    private final double[] peakF = new double[64];
    private final float[] peakDb = new float[64];
    private final float[] peakW = new float[64];
    private int numPeaks;
    private final double[] cand = new double[512];

    private final double[] hist = new double[HISTORY];
    private int histCount;
    private int histPos;
    private int unvoicedRun;
    private float noiseFloorDb = -60f;
    private float gateMarginDb = 12f;

    private final Result latest = new Result();
    private final Result scratch = new Result();

    public PitchDetector(int inputRate) {
        int factor = Math.max(1, inputRate / 8000);
        decimator = new Decimator(inputRate, factor, 28f);
        fs = (double) inputRate / factor;
        for (int i = 0; i < WINDOW; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * (i + 0.5) / WINDOW));
        }
    }

    public double analysisRate() { return fs; }

    /** Extra level (dB) above the tracked noise floor required to analyze; 6..30. */
    public void setGateMarginDb(float marginDb) { gateMarginDb = marginDb; }

    public Result latest() { return latest; }

    /** Feeds input-rate samples. Returns true if at least one new analysis frame was produced. */
    public boolean feed(float[] in, int n) {
        boolean produced = false;
        int offset = 0;
        float[] chunk = in;
        while (offset < n) {
            int len = Math.min(n - offset, decBuf.length);
            if (offset != 0) {
                chunk = Arrays.copyOfRange(in, offset, offset + len);
            }
            int m = decimator.process(chunk, len, decBuf);
            for (int i = 0; i < m; i++) {
                ring[ringPos] = decBuf[i];
                ringPos = (ringPos + 1) % WINDOW;
                if (filled < WINDOW) filled++;
                if (++sinceHop >= HOP && filled == WINDOW) {
                    sinceHop = 0;
                    analyze();
                    produced = true;
                }
            }
            offset += len;
        }
        return produced;
    }

    public void reset() {
        Arrays.fill(ring, 0f);
        filled = 0;
        sinceHop = 0;
        histCount = 0;
        unvoicedRun = 0;
        latest.voiced = false;
        latest.stable = false;
    }

    private void analyze() {
        // level of the most recent half window
        double sumSq = 0;
        for (int i = 0; i < WINDOW / 2; i++) {
            float s = ring[(ringPos - 1 - i + WINDOW) % WINDOW];
            sumSq += s * s;
        }
        float level = (float) (10 * Math.log10(sumSq / (WINDOW / 2) + 1e-12)) + 3f; // dBFS (sine = 0 dB)
        // noise floor: follows drops immediately, rises slowly (~3 dB/s)
        if (level < noiseFloorDb) noiseFloorDb = level;
        else noiseFloorDb += (float) (3.0 * HOP / fs);
        noiseFloorDb = Math.max(-90f, Math.min(noiseFloorDb, -30f));

        latest.levelDb = level;
        latest.noiseFloorDb = noiseFloorDb;
        float gate = Math.max(noiseFloorDb + gateMarginDb, -72f);
        if (level < gate) {
            markUnvoiced();
            return;
        }

        if (!estimate(scratch)) {
            markUnvoiced();
            return;
        }
        unvoicedRun = 0;
        latest.voiced = true;
        latest.confidence = scratch.confidence;

        double cents = 1200 * log2(scratch.frequency / 440.0);
        hist[histPos] = cents;
        histPos = (histPos + 1) % HISTORY;
        if (histCount < HISTORY) histCount++;

        double[] near = new double[HISTORY];
        int k = 0;
        for (int i = 0; i < histCount; i++) {
            if (Math.abs(hist[i] - cents) <= AGREE_CENTS) near[k++] = hist[i];
        }
        if (k >= MIN_AGREE) {
            Arrays.sort(near, 0, k);
            double med = (k % 2 == 1) ? near[k / 2] : 0.5 * (near[k / 2 - 1] + near[k / 2]);
            // the newest frame carries the most current information; blend it with the median
            double outCents = 0.5 * med + 0.5 * cents;
            latest.frequency = 440.0 * Math.pow(2, outCents / 1200.0);
            latest.stable = true;
        } else {
            latest.stable = false;
        }
    }

    private void markUnvoiced() {
        latest.voiced = false;
        latest.stable = false;
        if (++unvoicedRun >= 4) histCount = 0;
    }

    /** Single-frame estimate. Returns false when no harmonic structure is found. */
    boolean estimate(Result out) {
        int start = ringPos; // oldest sample
        for (int i = 0; i < WINDOW; i++) {
            re[i] = ring[(start + i) % WINDOW] * hann[i];
            im[i] = 0f;
        }
        for (int i = WINDOW; i < NFFT; i++) { re[i] = 0f; im[i] = 0f; }
        fft.transform(re, im);

        double binHz = fs / NFFT;
        int kMin = Math.max(2, (int) Math.floor(F_MIN * 0.9 / binHz));
        int kMax = Math.min(NFFT / 2 - 10, (int) (Math.min(TOP_ANALYSIS_HZ, fs * 0.45) / binHz));
        float maxDb = -1e9f;
        for (int k = 0; k < NFFT / 2; k++) {
            double p = (double) re[k] * re[k] + (double) im[k] * im[k];
            db[k] = (float) (10 * Math.log10(p + 1e-20));
        }
        for (int k = kMin; k <= kMax; k++) if (db[k] > maxDb) maxDb = db[k];
        float floorDb = maxDb - DYN_RANGE_DB;

        // spectral peaks
        numPeaks = 0;
        for (int k = kMin; k <= kMax; k++) {
            float b = db[k];
            if (b <= floorDb || b <= db[k - 1] || b < db[k + 1]) continue;
            float lo = b, hi = b;
            for (int j = Math.max(1, k - 11); j <= Math.min(NFFT / 2 - 1, k + 11); j++) {
                if (Math.abs(j - k) <= 8) lo = Math.min(lo, db[j]);
                hi = Math.max(hi, db[j]);
            }
            if (b - lo < 6f) continue;
            // window side lobe of a much stronger neighbouring peak
            if (hi - b > 20f) continue;
            float a = db[k - 1], c = db[k + 1];
            float den = a - 2 * b + c;
            float delta = den < 0 ? 0.5f * (a - c) / den : 0f;
            if (delta > 0.5f) delta = 0.5f; else if (delta < -0.5f) delta = -0.5f;
            float pDb = b - 0.25f * (a - c) * delta;
            insertPeak((k + delta) * binHz, pDb, floorDb);
        }
        if (numPeaks == 0) return false;

        double refW = 0, fTop = 0;
        for (int i = 0; i < numPeaks; i++) {
            if (i < 5) refW += peakW[i];
            if (peakW[i] > 0.35f && peakF[i] > fTop) fTop = peakF[i];
        }
        refW /= Math.min(5, numPeaks);

        // candidates: sub-multiples of strong peaks and differences between them
        int nc = 0;
        int topN = Math.min(10, numPeaks);
        for (int i = 0; i < topN; i++) {
            for (int h = 1; h <= 10; h++) {
                double f = peakF[i] / h;
                if (f >= F_MIN && f <= F_MAX) cand[nc++] = f;
            }
        }
        int diffN = Math.min(8, numPeaks);
        for (int i = 0; i < diffN; i++) {
            for (int j = i + 1; j < diffN; j++) {
                double f = Math.abs(peakF[i] - peakF[j]);
                if (f >= F_MIN && f <= F_MAX && nc < cand.length) cand[nc++] = f;
            }
        }
        Arrays.sort(cand, 0, nc);

        double bestScore = -1e9, bestF = 0;
        double last = -1;
        for (int c = 0; c < nc; c++) {
            double f0 = cand[c];
            if (last > 0 && f0 / last < 1.004) continue;
            last = f0;
            double[] sm = score(f0, binHz, fTop, refW);
            if (sm[0] > bestScore) {
                bestScore = sm[0];
                bestF = f0;
            }
        }
        if (bestF <= 0 || bestScore <= 0) return false;

        // sub-octave guard: if an integer multiple of the winner scores almost as well,
        // the winner is most likely a subharmonic fitted to noise peaks
        for (int m = 2; m <= 3; m++) {
            double up = bestF * m;
            if (up > F_MAX) break;
            double s = score(up, binHz, fTop, refW)[0];
            if (s >= 0.85 * bestScore) {
                bestF = up;
                bestScore = s;
                m = 1; // restart from the new winner
            }
        }

        // refine with low harmonics; higher harmonics give finer relative precision
        double num = 0, den = 0;
        for (int i = 0; i < numPeaks; i++) {
            long h = Math.round(peakF[i] / bestF);
            if (h < 1 || h > 4) continue;
            double rel = Math.abs(peakF[i] / (h * bestF) - 1);
            if (rel > 0.012 + 0.5 * binHz / (h * bestF)) continue;
            double w = peakW[i] * h;
            num += peakF[i] / h * w;
            den += w;
        }
        out.frequency = den > 0 ? num / den : bestF;
        // confidence: share of peak weight up to the 12th harmonic explained by the series
        double localW = 0, localMatched = 0, fLim = 12.5 * bestF;
        for (int i = 0; i < numPeaks; i++) {
            if (peakF[i] > fLim) continue;
            localW += peakW[i];
            long h = Math.round(peakF[i] / bestF);
            if (h < 1) continue;
            double rel = Math.abs(peakF[i] / (h * bestF) - 1);
            double tol = tolerance(bestF, h, binHz);
            if (rel < tol) localMatched += peakW[i] * (1 - rel / tol);
        }
        out.confidence = (float) (localW > 0 ? Math.min(1.0, localMatched / localW) : 0);
        return out.confidence >= MIN_CONFIDENCE;
    }

    private static double tolerance(double f0, long h, double binHz) {
        return Math.min(0.03, 0.3 / h) + 0.5 * binHz / (h * f0);
    }

    /** Returns {score, matchedWeight}. */
    private double[] score(double f0, double binHz, double fTop, double refW) {
        double matched = 0;
        long hits = 0L;
        for (int i = 0; i < numPeaks; i++) {
            long h = Math.round(peakF[i] / f0);
            if (h < 1 || h > MAX_HARMONIC) continue;
            double rel = Math.abs(peakF[i] / (h * f0) - 1);
            double tol = tolerance(f0, h, binHz);
            if (rel >= tol) continue;
            matched += peakW[i] * (1 - rel / tol);
            if (h < 64) hits |= 1L << h;
        }
        int predicted = (int) Math.min(10, Math.floor(fTop * 1.03 / f0));
        int misses = 0;
        for (int h = 1; h <= predicted; h++) if ((hits & (1L << h)) == 0) misses++;
        return new double[] {matched - MISS_PENALTY * refW * misses, matched};
    }

    private void insertPeak(double f, float pDb, float floorDb) {
        float w = Math.max(0.02f, Math.min(1f, (pDb - floorDb) / DYN_RANGE_DB));
        int n = numPeaks;
        if (n == MAX_PEAKS && w <= peakW[n - 1]) return;
        int pos = Math.min(n, MAX_PEAKS - 1);
        while (pos > 0 && peakW[pos - 1] < w) {
            peakF[pos] = peakF[pos - 1];
            peakDb[pos] = peakDb[pos - 1];
            peakW[pos] = peakW[pos - 1];
            pos--;
        }
        peakF[pos] = f;
        peakDb[pos] = pDb;
        peakW[pos] = w;
        if (numPeaks < MAX_PEAKS) numPeaks++;
    }

    private static double log2(double x) { return Math.log(x) / Math.log(2); }
}
