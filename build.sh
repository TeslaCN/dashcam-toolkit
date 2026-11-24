#!/bin/bash
# Gradle-free build for the Dashcam app.
#
#   ./build.sh            # builds out/dashcam.apk and embeds the full project into assets/
#
# Requirements: Android SDK (build-tools + platform), JDK 8 (javac) and JDK 11+ (d8).
# The script auto-detects SDK/JDK locations; override with ANDROID_HOME / JAVAC_BIN / JAVA_BIN.
set -euo pipefail
cd "$(dirname "$0")"

# ---------------------------------------------------------------- toolchain
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if [ ! -d "$SDK" ]; then
  echo "Android SDK not found (set ANDROID_HOME). Looked in: $SDK" >&2
  exit 1
fi
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
if [ -z "${BT:-}" ]; then echo "no build-tools in $SDK" >&2; exit 1; fi
if [ -f "$SDK/platforms/android-33/android.jar" ]; then
  PLATFORM="$SDK/platforms/android-33/android.jar"
else
  PLATFORM="$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
fi
[ -n "${PLATFORM:-}" ] || { echo "no android.jar in $SDK/platforms" >&2; exit 1; }

pick_java_home() {
  local v p
  for v in "$@"; do
    if [ -x /usr/libexec/java_home ]; then
      p="$(/usr/libexec/java_home -v "$v" 2>/dev/null || true)"
      if [ -n "$p" ] && [ -x "$p/bin/java" ]; then echo "$p"; return 0; fi
    fi
  done
  return 1
}
JAVA8_HOME="$(pick_java_home 1.8 8 || true)"
JAVA11_HOME="$(pick_java_home 11 17 21 || true)"
JAVAC="${JAVAC_BIN:-${JAVA8_HOME:+$JAVA8_HOME/bin/javac}}"
JAVAC="${JAVAC:-javac}"
JAVA_EXE="${JAVA_BIN:-${JAVA11_HOME:+$JAVA11_HOME/bin/java}}"
JAVA_EXE="${JAVA_EXE:-java}"
KEYTOOL="${KEYTOOL_BIN:-${JAVA8_HOME:+$JAVA8_HOME/bin/keytool}}"
KEYTOOL="${KEYTOOL:-keytool}"

echo "SDK:        $SDK"
echo "build-tools:$BT"
echo "platform:   $PLATFORM"
echo "javac:      $JAVAC"
echo "java(d8):   $JAVA_EXE"

rm -rf out assets
mkdir -p out/compiled out/classes out/dex out/gen assets

# ------------------------------------------------- embed the whole project
# The APK carries a complete, buildable copy of this project so it can be
# extracted and rebuilt at any time (unzip dashcam.apk assets/dashcam-source.zip).
echo "== packaging source bundle =="
zip -qr assets/dashcam-source.zip src res signing AndroidManifest.xml build.sh README.md
echo "   assets/dashcam-source.zip: $(du -h assets/dashcam-source.zip | cut -f1)"

echo "== aapt2 compile =="
"$BT/aapt2" compile --dir res -o out/compiled/res.zip

echo "== aapt2 link =="
"$BT/aapt2" link -o out/base.apk -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 29 \
    -A assets \
    --java out/gen -R out/compiled/res.zip --auto-add-overlay

echo "== javac =="
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -g -nowarn \
    -classpath "$PLATFORM" -d out/classes \
    $(find src out/gen -name '*.java')

echo "== d8 =="
"$JAVA_EXE" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 \
    --lib "$PLATFORM" --output out/dex $(find out/classes -name '*.class')

echo "== package =="
cp out/base.apk out/unaligned.apk
(cd out/dex && zip -q -u ../unaligned.apk classes.dex)
"$BT/zipalign" -f 4 out/unaligned.apk out/aligned.apk

if [ ! -f signing/debug.jks ]; then
  echo "== generating debug keystore =="
  "$KEYTOOL" -genkeypair -keystore signing/debug.jks -alias androiddebugkey \
      -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign --ks signing/debug.jks --ks-pass pass:android --key-pass pass:android \
    --out out/dashcam.apk out/aligned.apk

echo "built: $(pwd)/out/dashcam.apk"
echo "source bundle inside: assets/dashcam-source.zip ($(unzip -l out/dashcam.apk | grep -c 'dashcam-source') entry)"
