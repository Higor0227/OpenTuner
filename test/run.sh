#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Compiles the pitch detector with the synthetic-signal tests and runs them on the JVM.
set -eu
cd "$(dirname "$0")/.."
rm -rf build/test
mkdir -p build/test
javac -encoding UTF-8 -d build/test src/io/github/higor0227/opentuner/dsp/*.java test/DetectorTest.java
java -cp build/test DetectorTest
