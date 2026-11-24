#!/usr/bin/env bash
# 无 Gradle 构建：aapt2 + javac + d8 + zipalign + apksigner
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
BT=$SDK/build-tools/36.1.0
ANDROID_JAR=$SDK/platforms/android-36.1/android.jar
JDK=$HOME/.jdks/temurin-17.0.12
JAVAC=$JDK/bin/javac
KEYTOOL=$JDK/bin/keytool
JAR=$JDK/bin/jar

MIN_SDK=29
TARGET_SDK=29
OUT=build
APK_NAME=dashcam-stitch.apk

rm -rf $OUT/classes $OUT/gen $OUT/dex $OUT/res.zip $OUT/unsigned.apk $OUT/aligned.apk
mkdir -p $OUT/classes $OUT/gen $OUT/dex

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir res -o $OUT/res.zip

echo "[2/6] aapt2 link"
"$BT/aapt2" link \
  -o $OUT/unsigned.apk \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version $TARGET_SDK \
  --version-code 1 --version-name 1.0 \
  --java $OUT/gen \
  --auto-add-overlay \
  $OUT/res.zip

echo "[3/6] javac"
find java $OUT/gen -name '*.java' > $OUT/sources.txt
if ! "$JAVAC" -source 8 -target 8 -nowarn -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d $OUT/classes @$OUT/sources.txt > $OUT/javac.log 2>&1; then
  cat $OUT/javac.log
  exit 1
fi
grep -E "错误|error:" $OUT/javac.log || true

echo "[4/6] d8"
"$JAR" cf $OUT/classes.jar -C $OUT/classes .
"$BT/d8" --lib "$ANDROID_JAR" --min-api $MIN_SDK --release \
  --output $OUT/dex $OUT/classes.jar

echo "[5/6] package"
cp $OUT/unsigned.apk $OUT/app.apk
(cd $OUT/dex && zip -q -X ../app.apk classes.dex)

echo "[6/6] zipalign + sign"
"$BT/zipalign" -f -p 4 $OUT/app.apk $OUT/aligned.apk
if [ ! -f debug.keystore ]; then
  "$KEYTOOL" -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out $OUT/$APK_NAME $OUT/aligned.apk

ls -lh $OUT/$APK_NAME
echo "OK: $OUT/$APK_NAME"
