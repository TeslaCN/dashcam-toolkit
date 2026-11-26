#!/usr/bin/env bash
# 离线跑合成场景闭环仿真（验证收敛/稳定性）
set -euo pipefail
cd "$(dirname "$0")"
J="${JAVA_HOME:-$(readlink -f "$HOME/.sdkman/candidates/java/current" 2>/dev/null || true)}"
[ -x "$J/bin/javac" ] || { echo "JDK not found; set JAVA_HOME"; exit 1; }
rm -rf build && mkdir -p build
"$J/bin/javac" -d build ../../src/icu/wwj/drive/dashcam/PlateAeController.java PlateAeSim.java
"$J/bin/java" -cp build PlateAeSim
