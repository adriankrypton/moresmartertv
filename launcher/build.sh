#!/usr/bin/env bash
# Builds build/tvhome.apk with plain SDK tools (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="${JAVA_HOME:-$(ls -d ~/.jdks/jdk-17* | head -1)}"
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf build && mkdir -p build/res build/gen build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml \
    -A assets --java build/gen --min-sdk-version 25 --target-sdk-version 34 build/res.zip
javac -encoding UTF-8 -nowarn --release 11 -classpath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java')
"$BT/d8.bat" --release --min-api 25 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && "$JAVA_HOME/bin/jar" uf ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
KS=debug.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias debug \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TvHome" >/dev/null 2>&1
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:android --out build/tvhome.apk build/aligned.apk
echo "Built build/tvhome.apk"
