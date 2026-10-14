// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.opentuner;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;


public final class MainActivity extends Activity implements TunerEngine.Listener {
    private static final int REQ_MIC = 1;
    private static final float[] SENS_DB = {7f, 12f, 18f};
    private static final int[] SENS_NAMES = {R.string.sensitivity_high, R.string.sensitivity_medium, R.string.sensitivity_low};

    private TunerEngine engine;
    private ReferenceTone tone;
    private TunerView tuner;
    private TextView instrumentLabel, a4Label, toneButton;
    private SharedPreferences prefs;
    private int instrumentIdx;
    private int a4;
    private int sensitivity;
    private boolean solfege;
    private boolean permissionDenied;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(TunerView.BG);
        getWindow().setNavigationBarColor(TunerView.BG);
        prefs = getSharedPreferences("opentuner", MODE_PRIVATE);
        Notes.setSolfegeNames(getResources().getStringArray(R.array.solfege_names));
        instrumentIdx = Math.max(0, Math.min(Instrument.ALL.length - 1, prefs.getInt("instrument", 1)));
        a4 = prefs.getInt("a4", 440);
        sensitivity = prefs.getInt("sens", 1);
        solfege = prefs.getBoolean("solfege", false);

        engine = new TunerEngine(this, this);
        engine.setGateMarginDb(SENS_DB[sensitivity]);
        tone = new ReferenceTone();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(TunerView.BG);

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(10), dp(8), dp(4));
        instrumentLabel = pill("", true);
        instrumentLabel.setOnClickListener(v -> chooseInstrument());
        LinearLayout.LayoutParams lpInst = new LinearLayout.LayoutParams(0, dp(44), 1f);
        header.addView(instrumentLabel, lpInst);
        TextView settings = pill("⚙", false);
        settings.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        settings.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams lpSet = new LinearLayout.LayoutParams(dp(52), dp(44));
        lpSet.leftMargin = dp(8);
        header.addView(settings, lpSet);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        tuner = new TunerView(this);
        tuner.setSolfege(solfege);
        tuner.setA4(a4);
        tuner.setInstrument(Instrument.ALL[instrumentIdx]);
        tuner.setStringTapListener(i -> {
            tuner.setLockedString(tuner.getLockedString() == i ? -1 : i);
            if (tone.isPlaying()) tone.setFrequency(tuner.referenceFrequency());
        });
        tuner.setOnClickListener(v -> {
            if (permissionDenied) openAppSettings();
        });
        root.addView(tuner, new LinearLayout.LayoutParams(-1, 0, 1f));

        // bottom bar: A4 calibration and reference tone
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(6), dp(16), dp(14));
        TextView minus = pill("−", false);
        minus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        minus.setOnClickListener(v -> setA4(a4 - 1));
        a4Label = pill("", false);
        a4Label.setOnClickListener(v -> setA4(440));
        TextView plus = pill("+", false);
        plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        plus.setOnClickListener(v -> setA4(a4 + 1));
        toneButton = pill(getString(R.string.tone_play), false);
        toneButton.setOnClickListener(v -> toggleTone());
        bar.addView(minus, new LinearLayout.LayoutParams(dp(52), dp(48)));
        LinearLayout.LayoutParams lpA4 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lpA4.leftMargin = dp(6);
        lpA4.rightMargin = dp(6);
        bar.addView(a4Label, lpA4);
        bar.addView(plus, new LinearLayout.LayoutParams(dp(52), dp(48)));
        LinearLayout.LayoutParams lpTone = new LinearLayout.LayoutParams(dp(104), dp(48));
        lpTone.leftMargin = dp(12);
        bar.addView(toneButton, lpTone);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });
        // draw behind the system bars on every API level and pad with the reported insets
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        setContentView(root);
        refreshLabels();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            permissionDenied = false;
            tuner.setMessage(null);
            tuner.setSource(engine::snapshot);
            engine.start();
        } else if (!permissionDenied) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        engine.stop();
        tone.stop();
        refreshLabels();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            permissionDenied = false;
            tuner.setMessage(null);
            tuner.setSource(engine::snapshot);
            engine.start();
        } else {
            permissionDenied = true;
            tuner.setMessage(getString(R.string.mic_denied));
        }
    }

    @Override
    public void onAudioError(String message) {
        runOnUiThread(() -> tuner.setMessage(message));
    }

    private void setA4(int hz) {
        a4 = Math.max(415, Math.min(466, hz));
        prefs.edit().putInt("a4", a4).apply();
        tuner.setA4(a4);
        if (tone.isPlaying()) tone.setFrequency(tuner.referenceFrequency());
        refreshLabels();
    }

    private void toggleTone() {
        if (tone.isPlaying()) tone.stop();
        else tone.start(tuner.referenceFrequency());
        refreshLabels();
    }

    private void chooseInstrument() {
        String[] names = new String[Instrument.ALL.length];
        for (int i = 0; i < names.length; i++) {
            Instrument in = Instrument.ALL[i];
            StringBuilder sb = new StringBuilder(getString(in.nameRes));
            if (!in.isChromatic()) {
                sb.append("\n");
                for (int k = 0; k < in.strings.length; k++) {
                    if (k > 0) sb.append(' ');
                    sb.append(Notes.fullName(in.strings[k], solfege));
                }
            }
            names[i] = sb.toString();
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.instrument)
                .setSingleChoiceItems(names, instrumentIdx, (d, which) -> {
                    instrumentIdx = which;
                    prefs.edit().putInt("instrument", which).apply();
                    tuner.setInstrument(Instrument.ALL[which]);
                    if (tone.isPlaying()) tone.setFrequency(tuner.referenceFrequency());
                    refreshLabels();
                    d.dismiss();
                })
                .show();
    }

    private void openSettings() {
        String[] items = {
                getString(R.string.notation, getString(solfege ? R.string.notation_solfege : R.string.notation_letters)),
                getString(R.string.sensitivity_value, getString(SENS_NAMES[sensitivity])),
                getString(R.string.about),
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.settings)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        solfege = !solfege;
                        prefs.edit().putBoolean("solfege", solfege).apply();
                        tuner.setSolfege(solfege);
                        refreshLabels();
                    } else if (which == 1) {
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle(R.string.sensitivity)
                                .setSingleChoiceItems(sensitivityNames(), sensitivity, (d2, s) -> {
                                    sensitivity = s;
                                    prefs.edit().putInt("sens", s).apply();
                                    engine.setGateMarginDb(SENS_DB[s]);
                                    d2.dismiss();
                                })
                                .show();
                    } else {
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle(R.string.app_name)
                                .setMessage(R.string.about_text)
                                .setPositiveButton(R.string.ok, null)
                                .show();
                    }
                })
                .show();
    }

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null));
        startActivity(i);
    }

    private void refreshLabels() {
        instrumentLabel.setText(getString(Instrument.ALL[instrumentIdx].nameRes) + "  ▾");
        a4Label.setText(getString(R.string.a4_label, Notes.fullName(69, solfege), a4));
        toneButton.setText(tone != null && tone.isPlaying() ? R.string.tone_stop : R.string.tone_play);
    }

    private String[] sensitivityNames() {
        String[] names = new String[SENS_NAMES.length];
        for (int i = 0; i < names.length; i++) names[i] = getString(SENS_NAMES[i]);
        return names;
    }

    private TextView pill(String label, boolean leftAligned) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(TunerView.FG);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setGravity(leftAligned ? (Gravity.CENTER_VERTICAL | Gravity.START) : Gravity.CENTER);
        t.setPadding(dp(leftAligned ? 16 : 6), 0, dp(6), 0);
        t.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(TunerView.CARD);
        bg.setCornerRadius(dp(14));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
