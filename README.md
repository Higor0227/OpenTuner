# OpenTuner

[![CI](https://github.com/Higor0227/OpenTuner/actions/workflows/ci.yml/badge.svg)](https://github.com/Higor0227/OpenTuner/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84.svg)
[![Contributions welcome](https://img.shields.io/badge/contributions-welcome-brightgreen.svg)](CONTRIBUTING.md)

**A free and open source instrument tuner for Android. No ads, no tracking, no internet access.**

OpenTuner listens through the microphone, finds the pitch of the note you play and shows how
many cents it is away from the target. Everything runs on the device; the only permission
it asks for is the microphone.

## Features

- **Chromatic mode** and **instrument presets**: guitar (standard, Drop D, half step down),
  7-string guitar, 4- and 5-string bass, cavaquinho, ukulele and violin.
- **Automatic string detection**, or tap a string to lock the tuner to it.
- **Cents gauge** with an in-tune zone of ±3 cents, a strobe-style band and plain-language
  guidance ("tighten" / "loosen").
- **Adjustable reference pitch** (A4 from 415 to 466 Hz).
- **Reference tone** generator for tuning by ear.
- **Letter or solfège note names** (C D E / Do Re Mi).
- **Three sensitivity levels** for quiet or noisy rooms.
- **English and Portuguese** interface, following the device language.
- **Tiny**: the APK is about 35 KB, with no third-party libraries.

## Privacy

OpenTuner requests a single permission, `RECORD_AUDIO`. It has no network permission, so it
cannot send data anywhere. Audio is analyzed in memory and never stored.

## Download

Signed APKs are published on the [Releases](https://github.com/Higor0227/OpenTuner/releases)
page. Requires Android 8.0 (API 26) or newer. Because the APK is installed outside an app
store, Android asks you to allow installs from the app you use to open it.

## How the pitch detection works

The detector lives in [`src/io/github/higor0227/opentuner/dsp`](src/io/github/higor0227/opentuner/dsp)
and is plain Java with no Android dependencies, so it can be tested on any JVM.

1. **Decimation.** Input (usually 48 or 44.1 kHz) is low-pass filtered with a Blackman-windowed
   sinc FIR and decimated to about 8–9 kHz, then a 2nd-order Butterworth high-pass at 28 Hz
   removes DC and rumble.
2. **Spectrum.** A 2048-sample Hann window (about 230 ms) is zero-padded to 4096 points and
   transformed every 256 samples (about 30 ms).
3. **Peak picking.** Local maxima within 50 dB of the strongest peak are kept, refined with
   parabolic interpolation on the dB magnitude. Window side lobes are discarded.
4. **Fundamental candidates.** Sub-multiples of the strongest peaks and the differences between
   pairs of peaks. The differences let the detector find the fundamental even when the
   microphone barely captures it, which is common for bass strings on phones.
5. **Harmonic scoring.** Each candidate is scored by how much peak weight its harmonic series
   explains, with a penalty for predicted harmonics that are missing and a guard against
   picking a sub-octave.
6. **Refinement and smoothing.** The winning pitch is refined from harmonics 1–4 and passed
   through a 5-frame median filter that needs 3 agreeing frames before a note is shown.

On the synthetic test set in [`test/DetectorTest.java`](test/DetectorTest.java) (plucked
strings, inharmonic strings, missing fundamentals, noise), the median error is below 1 cent,
a note change settles in about 150 ms and white noise produces no false notes.

## Building

The project uses the Android build tools directly; there is no Gradle setup.

Requirements: JDK 17+, Python 3 with `cryptography`, and an Android SDK with
`platforms;android-35` and a recent `build-tools`.

```sh
pip install cryptography
ANDROID_HOME=$HOME/Android/Sdk ./build.sh
# output: build/opentuner.apk
```

`build.sh` runs `aapt2`, `javac`, `d8` and [`tools/apkpack.py`](tools/apkpack.py), which aligns
the archive and signs it with APK Signature Scheme v2. On the first run it creates a
development signing key in `keys/`, which is git-ignored. The tools can also be chosen
individually with the `AAPT2`, `ANDROID_JAR`, `LINK_JAR` and `D8_JAR` variables.

Install on a connected device with `adb install -r build/opentuner.apk`.

## Running the tests

```sh
./test/run.sh
```

This compiles the detector together with `test/DetectorTest.java` and prints accuracy,
stability and latency for each test signal. It needs only a JDK.

## Project layout

```
AndroidManifest.xml
build.sh                     build script (aapt2, javac, d8, signing)
res/                         icons and strings (values/ = English, values-pt/ = Portuguese)
src/io/github/higor0227/opentuner/
    MainActivity.java        screen, controls and dialogs
    TunerView.java           gauge, strobe band and string selector
    TunerEngine.java         microphone capture thread
    ReferenceTone.java       reference tone generator
    Instrument.java          tuning presets
    Notes.java               note names and frequency math
    dsp/                     pitch detector (pure Java)
test/                        detector tests with synthetic signals
tools/                       packaging, signing and build helpers
```

## Contributing

Contributions of every size are welcome: bug reports, recordings of notes the tuner gets
wrong, new tuning presets, translations, UI improvements and DSP work. Please read
[CONTRIBUTING.md](CONTRIBUTING.md) to get started, and check the
[issues](https://github.com/Higor0227/OpenTuner/issues) for ideas.

Some ideas for first contributions:

- More tuning presets (open tunings, banjo, mandolin, cello, viola caipira).
- Custom user-defined tunings.
- Translations into more languages.
- Landscape and tablet layouts.
- Accessibility (TalkBack descriptions for the gauge).
- An optional Gradle build and F-Droid metadata.

Everyone taking part in the project is expected to follow the
[Code of Conduct](CODE_OF_CONDUCT.md).

## License

OpenTuner is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of
the License, or (at your option) any later version. See [LICENSE](LICENSE).
