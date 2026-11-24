#!/usr/bin/env bash
# 免 Gradle 构建：aapt2 + javac + d8 + zipalign + apksigner
# 产物：out/drive.apk（签名沿用 signing/debug.jks，可原地覆盖安装重建版）
# 每次构建都会把整个工程打包进 APK 的 assets/drive-source.zip，随时可解出重建：
#   unzip drive.apk assets/drive-source.zip -d /tmp/drive && cd /tmp/drive && unzip -q drive-source.zip && ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

# ---------------------------------------------------------------- toolchain
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
[ -d "$SDK" ] || SDK="$HOME/Library/Android/sdk"          # macOS 默认路径兜底
[ -d "$SDK" ] || { echo "Android SDK not found (set ANDROID_HOME)" >&2; exit 1; }

BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[ -n "${BT:-}" ] || { echo "no build-tools in $SDK" >&2; exit 1; }

PLATFORM="$SDK/platforms/android-36.1/android.jar"
[ -f "$PLATFORM" ] || PLATFORM="$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
[ -n "${PLATFORM:-}" ] || { echo "no android.jar in $SDK/platforms" >&2; exit 1; }

JAVAC="${JAVAC_BIN:-javac}"
JAVA="${JAVA_BIN:-java}"
KEYTOOL="${KEYTOOL_BIN:-keytool}"

MIN_SDK=29
TARGET_SDK=29
OUT=out
APK="$OUT/drive.apk"

echo "SDK:        $SDK"
echo "build-tools:$BT"
echo "platform:   $PLATFORM"
echo "javac:      $JAVAC"

rm -rf "$OUT" assets
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex" "$OUT/gen" assets

# ------------------------------------------------- 把整个工程塞进 APK
echo "== packaging source bundle =="
BUNDLE="src res signing AndroidManifest.xml build.sh README.md"
[ -d docs ] && BUNDLE="$BUNDLE docs"
[ -d test ] && BUNDLE="$BUNDLE test"
zip -qr assets/drive-source.zip $BUNDLE
echo "   assets/drive-source.zip: $(du -h assets/drive-source.zip | cut -f1)"

echo "== aapt2 compile =="
"$BT/aapt2" compile --dir res -o "$OUT/compiled/res.zip"

echo "== aapt2 link =="
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
    -A assets --java "$OUT/gen" -R "$OUT/compiled/res.zip" --auto-add-overlay

echo "== javac =="
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -g -nowarn \
    -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== d8 =="
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api $MIN_SDK \
    --lib "$PLATFORM" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "== package =="
cp "$OUT/base.apk" "$OUT/unaligned.apk"
(cd "$OUT/dex" && zip -q -u ../unaligned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

if [ ! -f signing/debug.jks ]; then
  echo "== generating debug keystore =="
  "$KEYTOOL" -genkeypair -keystore signing/debug.jks -alias androiddebugkey \
      -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US"
fi

"$BT/apksigner" sign --ks signing/debug.jks --ks-pass pass:android --key-pass pass:android \
    --out "$APK" "$OUT/aligned.apk"

echo "built: $(pwd)/$APK"
echo "source bundle inside: assets/drive-source.zip ($(unzip -l "$APK" | grep -c 'drive-source') entry)"
