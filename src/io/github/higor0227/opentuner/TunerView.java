// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.opentuner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/** Draws the tuner: note, cents gauge, strobe band, guidance and string selector. */
public final class TunerView extends View {

    public interface Source {
        /** {voiced, stable, freq, levelDb, confidence, ageMs} */
        void snapshot(double[] out);
    }

    public interface StringTapListener {
        void onStringTapped(int index);
    }

    static final int GREEN = 0xFF3DDC84;
    static final int AMBER = 0xFFFFC23D;
    static final int RED = 0xFFFF5A5A;
    static final int BG = 0xFF101316;
    static final int FG = 0xFFE9EEF2;
    static final int MUTED = 0xFF7D8A96;
    static final int CARD = 0xFF1A1F24;

    private Source source;
    private StringTapListener tapListener;
    private Instrument instrument = Instrument.ALL[0];
    private int lockedString = -1;
    private double a4 = 440.0;
    private boolean solfege;
    private String message;

    // display state
    private final double[] snap = new double[6];
    private boolean hasNote;
    private int noteMidi;
    private int targetString = -1;
    private double cents, centsDisp, freqDisp, targetFreq;
    private long lastHeard;
    private long lastFrame;
    private float strobePhase;
    private float levelDisp = -90f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private final Typeface bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private RectF[] chipRects = new RectF[0];
    private final float dp;

    public TunerView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.CENTER);
        setBackgroundColor(BG);
    }

    public void setSource(Source s) { source = s; postInvalidateOnAnimation(); }
    public void setStringTapListener(StringTapListener l) { tapListener = l; }
    public void setInstrument(Instrument i) { instrument = i; lockedString = -1; hasNote = false; requestLayout(); invalidate(); }
    public void setLockedString(int idx) { lockedString = idx; invalidate(); }
    public int getLockedString() { return lockedString; }
    public void setA4(double hz) { a4 = hz; }
    public void setSolfege(boolean s) { solfege = s; invalidate(); }
    public void setMessage(String m) { message = m; invalidate(); }

    /** Frequency the reference tone should play: locked string, current target, or A4. */
    public double referenceFrequency() {
        if (!instrument.isChromatic() && lockedString >= 0) return Notes.freqOf(instrument.strings[lockedString], a4);
        if (hasNote) return Notes.freqOf(noteMidi, a4);
        return a4;
    }

    private void update() {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (source == null) return;
        source.snapshot(snap);
        boolean stable = snap[1] > 0.5;
        double age = snap[5];
        levelDisp += ((float) snap[3] - levelDisp) * Math.min(1f, dt / 0.08f);

        if (stable && age < 250 && snap[2] > 0) {
            double f = snap[2];
            double midiF = Notes.midiOf(f, a4);
            int target;
            int tString = -1;
            double c;
            if (instrument.isChromatic()) {
                target = (int) Math.round(midiF);
                c = (midiF - target) * 100;
            } else if (lockedString >= 0) {
                target = instrument.strings[lockedString];
                double d = midiF - target;
                // octave folding: harmonics or the same note an octave away still tune the string
                if (Math.abs(d) > 6) d -= 12 * Math.round(d / 12);
                c = d * 100;
                tString = lockedString;
            } else {
                int best = -1;
                double bestD = 1e9;
                for (int i = 0; i < instrument.strings.length; i++) {
                    double d = Math.abs(midiF - instrument.strings[i]);
                    if (d < bestD) { bestD = d; best = i; }
                }
                if (bestD <= 3.0) {
                    target = instrument.strings[best];
                    tString = best;
                    c = (midiF - target) * 100;
                } else {
                    target = (int) Math.round(midiF);
                    c = (midiF - target) * 100;
                }
            }
            boolean jump = !hasNote || target != noteMidi || tString != targetString;
            noteMidi = target;
            targetString = tString;
            cents = c;
            targetFreq = Notes.freqOf(target, a4);
            freqDisp = jump ? f : freqDisp + (f - freqDisp) * Math.min(1.0, dt / 0.15);
            centsDisp = jump ? c : centsDisp + (c - centsDisp) * Math.min(1.0, dt / 0.09);
            hasNote = true;
            lastHeard = now;
        }
        boolean active = hasNote && now - lastHeard < 1200;
        if (active) strobePhase += (float) (Math.max(-60, Math.min(60, centsDisp)) / 25.0) * dt;
        if (hasNote && now - lastHeard > 6000) hasNote = false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pad = 20 * dp;
        shade.setShader(new LinearGradient(pad, 0, w - pad, 0,
                new int[] {BG, 0x00000000, 0x00000000, BG}, new float[] {0f, 0.15f, 0.85f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        update();
        long now = SystemClock.uptimeMillis();
        boolean active = hasNote && now - lastHeard < 1200;
        float w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float pad = 20 * dp;

        int stateColor = !active ? MUTED
                : Math.abs(centsDisp) <= 3 ? GREEN : Math.abs(centsDisp) <= 12 ? AMBER : RED;

        // layout bands
        boolean hasStrings = !instrument.isChromatic();
        float stringsH = hasStrings ? 74 * dp : 0;
        float bottom = h - stringsH - 12 * dp;
        float noteY = h * 0.30f;

        // note
        text.setTypeface(bold);
        float noteSize = Math.min(w * 0.30f, h * 0.20f);
        if (hasNote) {
            String nm = Notes.name(noteMidi, solfege);
            String oct = String.valueOf(Notes.octave(noteMidi));
            text.setTextSize(noteSize * (nm.length() > 2 ? 0.75f : 1f));
            text.setColor(active ? FG : MUTED);
            float nw = text.measureText(nm);
            canvas.drawText(nm, cx, noteY, text);
            text.setTextSize(noteSize * 0.32f);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(MUTED);
            canvas.drawText(oct, cx + nw / 2f + 4 * dp, noteY, text);
            text.setTextAlign(Paint.Align.CENTER);

            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(15 * dp);
            text.setColor(MUTED);
            canvas.drawText(getContext().getString(R.string.frequency_target,
                    String.format(Locale.getDefault(), "%.1f", freqDisp),
                    String.format(Locale.getDefault(), "%.1f", targetFreq)), cx, noteY + 30 * dp, text);
        } else {
            text.setTextSize(noteSize * 0.55f);
            text.setColor(0xFF3A444D);
            canvas.drawText("—", cx, noteY - noteSize * 0.2f, text);
            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(16 * dp);
            text.setColor(MUTED);
            canvas.drawText(message != null ? message
                    : getContext().getString(hasStrings ? R.string.play_a_string : R.string.play_a_note), cx, noteY + 30 * dp, text);
        }

        // gauge
        float gTop = noteY + 70 * dp;
        float gL = pad + 8 * dp, gR = w - pad - 8 * dp;
        float gW = gR - gL;
        float gMid = gTop + 34 * dp;
        p.setStyle(Paint.Style.FILL);
        p.setColor(CARD);
        r.set(pad, gTop, w - pad, gTop + 92 * dp);
        canvas.drawRoundRect(r, 18 * dp, 18 * dp, p);
        // in-tune zone (+-3 cents)
        float zone = gW * 3f / 100f;
        p.setColor((GREEN & 0x00FFFFFF) | 0x33000000);
        r.set(cx - zone, gMid - 22 * dp, cx + zone, gMid + 22 * dp);
        canvas.drawRoundRect(r, 4 * dp, 4 * dp, p);
        // ticks
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int c = -50; c <= 50; c += 5) {
            float x = gL + gW * (c + 50) / 100f;
            boolean major = c % 25 == 0;
            p.setColor(c == 0 ? FG : major ? 0xFF8C99A6 : 0xFF4A5560);
            p.setStrokeWidth((c == 0 ? 3 : major ? 2 : 1.5f) * dp);
            float len = (c == 0 ? 22 : major ? 16 : 9) * dp;
            canvas.drawLine(x, gMid - len, x, gMid + len, p);
        }
        text.setTextSize(12 * dp);
        text.setColor(MUTED);
        for (int c = -50; c <= 50; c += 25) {
            float x = gL + gW * (c + 50) / 100f;
            canvas.drawText(c > 0 ? "+" + c : String.valueOf(c), x, gMid + 40 * dp, text);
        }
        // needle
        if (hasNote) {
            float cc = (float) Math.max(-52, Math.min(52, centsDisp));
            float x = gL + gW * (cc + 50) / 100f;
            p.setColor(stateColor);
            p.setStrokeWidth(4 * dp);
            canvas.drawLine(x, gMid - 26 * dp, x, gMid + 26 * dp, p);
            path.reset();
            path.moveTo(x - 8 * dp, gTop + 2 * dp);
            path.lineTo(x + 8 * dp, gTop + 2 * dp);
            path.lineTo(x, gTop + 12 * dp);
            path.close();
            canvas.drawPath(path, p);
        }

        // cents + guidance
        float infoY = gTop + 92 * dp + 44 * dp;
        text.setTypeface(bold);
        text.setTextSize(30 * dp);
        text.setColor(stateColor);
        if (hasNote) {
            int ci = (int) Math.round(centsDisp);
            canvas.drawText((ci > 0 ? "+" : "") + ci + " ¢", cx, infoY, text);
        }
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(17 * dp);
        String hint = null;
        if (active) {
            if (Math.abs(centsDisp) <= 3) hint = getContext().getString(R.string.in_tune);
            else if (centsDisp < 0) hint = getContext().getString(R.string.too_low);
            else hint = getContext().getString(R.string.too_high);
        }
        if (hint != null) canvas.drawText(hint, cx, infoY + 30 * dp, text);

        // strobe band
        float sTop = infoY + 52 * dp;
        float sH = 30 * dp;
        if (sTop + sH < bottom) {
            r.set(pad, sTop, w - pad, sTop + sH);
            p.setColor(CARD);
            canvas.drawRoundRect(r, 10 * dp, 10 * dp, p);
            canvas.save();
            canvas.clipRect(r);
            float period = 28 * dp;
            float off = ((strobePhase % 1f) + 1f) % 1f * period;
            p.setColor(active ? ((stateColor & 0x00FFFFFF) | 0xAA000000) : 0xFF2A333B);
            for (float x = pad - period + off; x < w - pad + period; x += period) {
                canvas.drawRect(x, sTop, x + period * 0.45f, sTop + sH, p);
            }
            canvas.restore();
            canvas.drawRect(r, shade);
        }

        // strings
        if (hasStrings) {
            int n = instrument.strings.length;
            if (chipRects.length != n) {
                chipRects = new RectF[n];
                for (int i = 0; i < n; i++) chipRects[i] = new RectF();
            }
            float gap = 8 * dp;
            float chipW = Math.min(64 * dp, (w - 2 * pad - gap * (n - 1)) / n);
            float total = chipW * n + gap * (n - 1);
            float x0 = cx - total / 2f;
            float top = h - stringsH;
            for (int i = 0; i < n; i++) {
                RectF cr = chipRects[i];
                cr.set(x0 + i * (chipW + gap), top, x0 + i * (chipW + gap) + chipW, top + 52 * dp);
                boolean isTarget = active && targetString == i;
                boolean locked = lockedString == i;
                p.setStyle(Paint.Style.FILL);
                p.setColor(isTarget ? ((stateColor & 0x00FFFFFF) | 0x40000000) : CARD);
                canvas.drawRoundRect(cr, 14 * dp, 14 * dp, p);
                if (locked) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2 * dp);
                    p.setColor(FG);
                    canvas.drawRoundRect(cr, 14 * dp, 14 * dp, p);
                    p.setStyle(Paint.Style.FILL);
                }
                int midi = instrument.strings[i];
                text.setTypeface(bold);
                text.setTextSize((solfege ? 14 : 17) * dp);
                text.setColor(isTarget ? FG : 0xFFB8C3CC);
                canvas.drawText(Notes.name(midi, solfege), cr.centerX(), cr.centerY() + 1 * dp, text);
                text.setTypeface(Typeface.DEFAULT);
                text.setTextSize(11 * dp);
                text.setColor(MUTED);
                canvas.drawText(String.valueOf(Notes.octave(midi)), cr.centerX(), cr.centerY() + 16 * dp, text);
            }
            text.setTextSize(12 * dp);
            text.setColor(MUTED);
            canvas.drawText(getContext().getString(lockedString >= 0 ? R.string.string_locked : R.string.string_auto), cx, top + 70 * dp, text);
        }

        // input level
        float lv = Math.max(0f, Math.min(1f, (levelDisp + 80f) / 70f));
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF22292F);
        r.set(pad, 10 * dp, w - pad, 13 * dp);
        canvas.drawRoundRect(r, 2 * dp, 2 * dp, p);
        p.setColor(active ? stateColor : 0xFF55616C);
        r.set(pad, 10 * dp, pad + (w - 2 * pad) * lv, 13 * dp);
        canvas.drawRoundRect(r, 2 * dp, 2 * dp, p);

        if (source != null) postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (instrument.isChromatic()) return super.onTouchEvent(e);
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            for (int i = 0; i < chipRects.length; i++) {
                RectF cr = chipRects[i];
                if (cr != null && cr.contains(e.getX(), e.getY())) {
                    if (tapListener != null) tapListener.onStringTapped(i);
                    return true;
                }
            }
            performClick();
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() { return super.performClick(); }
}
