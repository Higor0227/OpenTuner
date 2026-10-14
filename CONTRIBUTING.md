# Contributing to OpenTuner

Thanks for your interest in OpenTuner! This is a community project and contributions of all
kinds are welcome, from typo fixes to new DSP features.

## Ground rules

- OpenTuner stays **free of ads, trackers, analytics and network access**. Pull requests that
  add any of these, or new permissions beyond the microphone, will not be merged.
- Keep the app **small and dependency-free**. If a change needs a third-party library, open an
  issue first so we can discuss it.
- Be kind and constructive. All participants must follow the
  [Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to contribute

### Report a bug

Open an issue using the **Bug report** template and include your device model, Android
version and steps to reproduce. For detection problems (wrong note, jumping octaves, no
reading), a short recording of the note is the most useful thing you can attach: a WAV or
any common audio format, a few seconds long, of the single string you were tuning.

### Suggest a feature

Open an issue using the **Feature request** template. Describe the problem you want to solve
before the solution you have in mind.

### Add a tuning preset

Presets live in [`Instrument.java`](src/io/github/higor0227/opentuner/Instrument.java). Each one
is a string resource for the name plus the MIDI note numbers of the strings, from the lowest
string to the highest (E2 = 40, A4 = 69). Add the name to `res/values/strings.xml` and, if you
can, to the translations.

### Translate the app

Copy `res/values/strings.xml` to `res/values-<language>/strings.xml` (for example
`values-es`) and translate the text. Leave entries marked `translatable="false"` out.
Keep the placeholders such as `%1$s` intact.

### Improve the pitch detector

The detector in [`src/.../dsp`](src/io/github/higor0227/opentuner/dsp) is plain Java. Run
`./test/run.sh` before and after your change and include both outputs in the pull request.
If you fix a case the tests do not cover, add a signal for it to `test/DetectorTest.java`.

## Development setup

1. Install JDK 17 or newer, Python 3 and an Android SDK with `platforms;android-35` and a
   recent `build-tools`.
2. `pip install cryptography`
3. Build with `ANDROID_HOME=/path/to/sdk ./build.sh` and install the result with
   `adb install -r build/opentuner.apk`.
4. Run the detector tests with `./test/run.sh`.

A development signing key is generated in `keys/` on the first build. That directory is
git-ignored; never commit keys. Because each developer signs with their own key, uninstall
the release version before installing your local build.

## Coding style

- Java 8 language level (D8 handles desugaring); 4-space indentation; lines up to 120 columns.
- Keep classes small and focused. UI code stays out of the `dsp` package so the detector can
  run on any JVM.
- No hardcoded user-facing text: put strings in `res/values/strings.xml`.
- Start new source files with `// SPDX-License-Identifier: GPL-3.0-or-later`.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep each pull request focused on one change and describe what it does and why.
3. Make sure `./build.sh` succeeds and `./test/run.sh` shows no regressions.
4. Fill in the pull request template. CI runs the tests and builds the APK automatically.

By submitting a pull request you agree that your contribution is licensed under the
[GPL-3.0-or-later](LICENSE), the same license as the project.
