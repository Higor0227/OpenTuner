// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.opentuner;

/** Tuning presets. An empty string list means chromatic mode. MIDI numbers, low to high. */
public final class Instrument {
    /** String resource with the display name. */
    public final int nameRes;
    public final int[] strings;

    private Instrument(int nameRes, int... strings) {
        this.nameRes = nameRes;
        this.strings = strings;
    }

    public boolean isChromatic() { return strings.length == 0; }

    public static final Instrument[] ALL = {
            new Instrument(R.string.inst_chromatic),
            new Instrument(R.string.inst_guitar_standard, 40, 45, 50, 55, 59, 64),
            new Instrument(R.string.inst_guitar_drop_d, 38, 45, 50, 55, 59, 64),
            new Instrument(R.string.inst_guitar_half_down, 39, 44, 49, 54, 58, 63),
            new Instrument(R.string.inst_guitar_7, 36, 40, 45, 50, 55, 59, 64),
            new Instrument(R.string.inst_bass_4, 28, 33, 38, 43),
            new Instrument(R.string.inst_bass_5, 23, 28, 33, 38, 43),
            new Instrument(R.string.inst_cavaquinho, 62, 67, 71, 74),
            new Instrument(R.string.inst_ukulele, 67, 60, 64, 69),
            new Instrument(R.string.inst_violin, 55, 62, 69, 76),
    };
}
