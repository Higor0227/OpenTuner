// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.opentuner;

/** Note naming and frequency helpers (12-TET). MIDI 69 = A4. */
public final class Notes {
    private static final String[] LETTERS = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};
    private static String[] solfegeNames = {"Do", "Do♯", "Re", "Re♯", "Mi", "Fa", "Fa♯", "Sol", "Sol♯", "La", "La♯", "Si"};

    private Notes() {}

    /** Localized fixed-do solfège names, C to B (12 entries). */
    public static void setSolfegeNames(String[] names) {
        if (names != null && names.length == 12) solfegeNames = names;
    }

    public static double midiOf(double freq, double a4) {
        return 69.0 + 12.0 * Math.log(freq / a4) / Math.log(2.0);
    }

    public static double freqOf(double midi, double a4) {
        return a4 * Math.pow(2.0, (midi - 69.0) / 12.0);
    }

    public static String name(int midi, boolean solfege) {
        int pc = ((midi % 12) + 12) % 12;
        return solfege ? solfegeNames[pc] : LETTERS[pc];
    }

    public static int octave(int midi) {
        return Math.floorDiv(midi, 12) - 1;
    }

    public static String fullName(int midi, boolean solfege) {
        return name(midi, solfege) + octave(midi);
    }
}
