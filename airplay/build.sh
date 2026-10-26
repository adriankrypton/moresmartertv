#!/usr/bin/env bash
# Builds build/airplay.apk with plain SDK tools (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="${JAVA_HOME:-$(ls -d ~/.jdks/jdk-17* | head -1)}"
export PATH="$JAVA_HOME/bin:$PATH"
# Third-party libraries come from Maven Central (not committed)
mkdir -p libs
M=https://repo1.maven.org/maven2
for u in net/i2p/crypto/eddsa/0.3.0/eddsa-0.3.0.jar org/whispersystems/curve25519-java/0.5.0/curve25519-java-0.5.0.jar          com/googlecode/plist/dd-plist/1.23/dd-plist-1.23.jar org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar; do
  [ -f "libs/$(basename $u)" ] || curl -sfSL -o "libs/$(basename $u)" "$M/$u"
done
CP="$JAR;$(ls libs/*.jar | tr '\n' ';')"

rm -rf build && mkdir -p build/gen build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml \
    --java build/gen --min-sdk-version 31 --target-sdk-version 34 build/res.zip
javac -encoding UTF-8 -nowarn --release 11 -classpath "$CP" -d build/classes \
    $(find src build/gen -name '*.java')
"$BT/d8.bat" --release --min-api 31 --lib "$JAR" --output build/dex \
    $(find build/classes -name '*.class') libs/*.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && "$JAVA_HOME/bin/jar" uf ../unsigned.apk classes*.dex)
(cd javares && "$JAVA_HOME/bin/jar" uf ../build/unsigned.apk *)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
KS=../launcher/debug.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias debug     -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TvHome" >/dev/null 2>&1
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:android --out build/airplay.apk build/aligned.apk
echo "Built build/airplay.apk"
