#!/usr/bin/env bash
# 把视频抽成 960x540 原始 YUV420p，回放给 PlateAeController 看检测/场景统计
# 用法：./run_clip.sh <video.mp4> [fps] [maxFrames]
set -euo pipefail
cd "$(dirname "$0")"
J="${JAVA_HOME:-$(readlink -f "$HOME/.sdkman/candidates/java/current" 2>/dev/null || true)}"
[ -x "$J/bin/javac" ] || { echo "JDK not found; set JAVA_HOME"; exit 1; }
VIDEO="$1"; FPS="${2:-1}"; MAX="${3:-180}"
RAW="$(mktemp /tmp/ae-clip-XXXX.raw)"
echo "extracting $VIDEO @ ${FPS}fps -> $RAW"
ffmpeg -v error -y -i "$VIDEO" -vf "fps=$FPS,scale=960:540" -pix_fmt yuv420p -f rawvideo "$RAW"
rm -rf build && mkdir -p build
"$J/bin/javac" -d build ../../src/icu/wwj/drive/dashcam/PlateAeController.java ClipStats.java
"$J/bin/java" -cp build ClipStats "$RAW" 960 540 "$MAX"
rm -f "$RAW"
