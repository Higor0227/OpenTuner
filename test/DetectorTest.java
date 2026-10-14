// SPDX-License-Identifier: GPL-3.0-or-later
import io.github.higor0227.opentuner.dsp.PitchDetector;
import java.util.*;

/** Offline accuracy test with synthetic plucked/inharmonic strings, missing fundamentals and noise. */
public class DetectorTest {
    static Random rnd = new Random(42);

    // additive string tone: amplitude ~ 1/h, decay, inharmonicity B, optional fundamental attenuation
    static float[] stringTone(double f0, int fs, double secs, double B, double fundDb, double noiseDb, int nh) {
        int n = (int) (fs * secs);
        float[] x = new float[n];
        double fundGain = Math.pow(10, fundDb / 20);
        double[] ph = new double[nh + 1];
        for (int h = 1; h <= nh; h++) ph[h] = rnd.nextDouble() * 2 * Math.PI;
        for (int h = 1; h <= nh; h++) {
            double fh = h * f0 * Math.sqrt(1 + B * h * h);
            if (fh > fs * 0.45) break;
            double a = 0.3 / h * (h == 1 ? fundGain : 1) * (0.6 + 0.8 * rnd.nextDouble());
            double decay = 1.2 + 0.5 * h;
            for (int i = 0; i < n; i++) {
                double t = (double) i / fs;
                x[i] += (float) (a * Math.exp(-decay * t) * Math.sin(2 * Math.PI * fh * t + ph[h]));
            }
        }
        double na = Math.pow(10, noiseDb / 20);
        for (int i = 0; i < n; i++) x[i] += (float) (na * rnd.nextGaussian());
        return x;
    }

    // Karplus-Strong plucked string
    static float[] ks(double f0, int fs, double secs, double noiseDb) {
        int n = (int) (fs * secs);
        float[] x = new float[n];
        double period = fs / f0;
        // loop delay = L + 0.5 (averaging) + allpass delay; keep allpass delay in (0.05, 1.05)
        int L = (int) Math.floor(period - 0.55);
        double frac = period - L - 0.5;
        double c = (1 - frac) / (1 + frac); // allpass for fractional delay
        double[] buf = new double[L];
        for (int i = 0; i < L; i++) buf[i] = rnd.nextDouble() * 2 - 1;
        int idx = 0; double prev = 0, apX = 0, apY = 0;
        for (int i = 0; i < n; i++) {
            double cur = buf[idx];
            double avg = 0.4985 * (cur + prev);
            prev = cur;
            double y = c * avg + apX - c * apY;
            apX = avg; apY = y;
            buf[idx] = y;
            idx = (idx + 1) % L;
            x[i] = (float) (0.3 * cur);
        }
        double na = Math.pow(10, noiseDb / 20);
        for (int i = 0; i < n; i++) x[i] += (float) (na * rnd.nextGaussian());
        return x;
    }

    static double[] run(float[] x, int fs, double trueF) {
        PitchDetector d = new PitchDetector(fs);
        int block = fs / 100;
        float[] buf = new float[block];
        List<Double> errs = new ArrayList<>();
        int stable = 0, frames = 0, gross = 0;
        double firstStableMs = -1;
        for (int off = 0; off + block <= x.length; off += block) {
            System.arraycopy(x, off, buf, 0, block);
            if (d.feed(buf, block)) {
                double tMs = 1000.0 * (off + block) / fs;
                if (tMs < 250) continue;
                frames++;
                PitchDetector.Result r = d.latest();
                if (r.stable) {
                    stable++;
                    if (firstStableMs < 0) firstStableMs = tMs;
                    double e = 1200 * Math.log(r.frequency / trueF) / Math.log(2);
                    if (Math.abs(e) > 50) gross++; else errs.add(e);
                }
            }
        }
        Collections.sort(errs);
        double med = errs.isEmpty() ? Double.NaN : errs.get(errs.size() / 2);
        double p95 = errs.isEmpty() ? Double.NaN : errs.stream().mapToDouble(Math::abs).sorted().toArray()[(int) (errs.size() * 0.95)];
        return new double[] {frames == 0 ? 0 : 100.0 * stable / frames, gross, med, p95, firstStableMs};
    }

    static void report(String name, float[] x, int fs, double f) {
        double[] r = run(x, fs, f);
        System.out.printf(Locale.ROOT, "%-34s f=%8.2f stable=%5.1f%% gross=%3.0f medErr=%+6.2fc p95|err|=%5.2fc first=%4.0fms%n",
                name, f, r[0], r[1], r[2], r[3], r[4]);
    }

    public static void main(String[] a) {
        double[] guitar = {82.41, 110.0, 146.83, 196.0, 246.94, 329.63};
        double[] bass = {41.20, 55.0, 73.42, 98.0};
        double detune = Math.pow(2, 7 / 1200.0);
        for (int fs : new int[] {48000, 44100}) {
            System.out.println("=== fs " + fs);
            for (double f : guitar) {
                double ft = f * detune;
                report("guitar additive B=1e-4", stringTone(ft, fs, 2.0, 1e-4, 0, -60, 30), fs, ft);
                report("guitar KS", ks(ft, fs, 2.0, -60), fs, ft);
                report("guitar weak fund (-20dB)", stringTone(ft, fs, 2.0, 5e-5, -20, -55, 30), fs, ft);
            }
            for (double f : bass) {
                double ft = f / detune;
                report("bass no fund (-40dB)", stringTone(ft, fs, 2.5, 2e-5, -40, -60, 30), fs, ft);
                report("bass KS", ks(ft, fs, 2.5, -60), fs, ft);
            }
            for (double f : new double[] {261.63, 440.0, 659.26, 880.0, 1046.5}) {
                report("pure sine", stringTone(f, fs, 1.5, 0, 0, -70, 1), fs, f);
                report("violin-like (B=0)", stringTone(f, fs, 1.5, 0, 0, -60, 12), fs, f);
            }
            // noisy room: string at -25 dB SNR-ish (noise -35 dB)
            for (double f : new double[] {82.41, 196.0, 329.63}) {
                report("guitar noisy (noise -35dB)", stringTone(f, fs, 2.0, 1e-4, -6, -35, 30), fs, f);
            }
            // phone-like capture: high-pass 120 Hz kills low E fundamental + 2nd partial attenuated
            for (double f : new double[] {41.2, 82.41, 110.0}) {
                float[] x = stringTone(f, fs, 2.5, 5e-5, -45, -60, 40);
                report("phone mic (fund -45dB)", x, fs, f);
            }
            // note change: A2 then D3 without silence; measure only the second note after settling
            {
                float[] x1 = stringTone(110.0, fs, 1.0, 1e-4, 0, -60, 30);
                float[] x2 = stringTone(146.83, fs, 1.5, 1e-4, 0, -60, 30);
                float[] x = new float[x1.length + x2.length];
                System.arraycopy(x1, 0, x, 0, x1.length);
                System.arraycopy(x2, 0, x, x1.length, x2.length);
                PitchDetector d = new PitchDetector(fs);
                int block = fs / 100; float[] buf = new float[block];
                double switchMs = -1;
                for (int off = 0; off + block <= x.length; off += block) {
                    System.arraycopy(x, off, buf, 0, block);
                    if (d.feed(buf, block) && off > x1.length && switchMs < 0) {
                        PitchDetector.Result r = d.latest();
                        if (r.stable && Math.abs(1200 * Math.log(r.frequency / 146.83) / Math.log(2)) < 10)
                            switchMs = 1000.0 * (off - x1.length) / fs;
                    }
                }
                System.out.printf(Locale.ROOT, "note change A2->D3: settled after %.0f ms%n", switchMs);
            }
            // noise only
            float[] noise = new float[fs * 2];
            for (int i = 0; i < noise.length; i++) noise[i] = (float) (0.01 * rnd.nextGaussian());
            report("white noise -40dB (want 0 stable)", noise, fs, 100);
        }
    }
}
