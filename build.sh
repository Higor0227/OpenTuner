#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds and signs OpenTuner with the plain Android build tools (no Gradle).
#
# With an Android SDK installed, setting ANDROID_HOME is enough:
#   ANDROID_HOME=~/Android/Sdk ./build.sh
# Every tool can also be selected explicitly through the variables below.
set -eu
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -n "$SDK" ] && [ -d "$SDK/build-tools" ]; then
    BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -n 1)
    : "${AAPT2:=$BT/aapt2}"
    : "${D8_JAR:=$BT/lib/d8.jar}"
    : "${ANDROID_JAR:=$SDK/platforms/android-35/android.jar}"
fi

AAPT2=${AAPT2:-aapt2}
ANDROID_JAR=${ANDROID_JAR:?set ANDROID_HOME or ANDROID_JAR (platform 35 android.jar)}
LINK_JAR=${LINK_JAR:-$ANDROID_JAR}   # older aapt2 builds may need an older android.jar here
D8_JAR=${D8_JAR:?set ANDROID_HOME or D8_JAR (a jar containing com.android.tools.r8.D8)}
KEY=${KEY:-keys/opentuner.pem}
CERT=${CERT:-keys/opentuner.der}
OUT=${OUT:-build/opentuner.apk}
VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-1.0.0}

rm -rf build/res build/gen build/classes build/dex build/stubs build/linked.apk
mkdir -p build/res build/gen build/classes build/dex build/stubs keys

# A development key is generated on first use. Never commit keys/ (it is git-ignored).
[ -f "$KEY" ] || python3 tools/genkey.py "$KEY" "$CERT"

"$AAPT2" compile --dir res -o build/res/res.zip
"$AAPT2" link -o build/linked.apk -I "$LINK_JAR" --manifest AndroidManifest.xml \
    --java build/gen --min-sdk-version 26 --target-sdk-version 35 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" build/res/res.zip

# Compile-time stub for java.lang.invoke.LambdaMetafactory, missing from some android.jar
# builds. D8 desugars lambdas, so the stub never ends up in the APK.
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -Xlint:-options -d build/stubs \
    tools/stubs/java/lang/invoke/LambdaMetafactory.java
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR:build/stubs" -Xlint:-options -encoding UTF-8 \
    -d build/classes $(find src build/gen -name '*.java')

java -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 26 --lib "$ANDROID_JAR" \
    --output build/dex $(find build/classes -name '*.class')

python3 tools/apkpack.py build/linked.apk build/dex/classes.dex "$KEY" "$CERT" "$OUT"
