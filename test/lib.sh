#!/usr/bin/env bash
 WWJ 行车工具 · 四视角拼接真机测试公共函数库（由 DashcamStitch 1.0 测试脚本迁移）。
set -uo pipefail

PKG=${PKG:-icu.wwj.drive}
DEV_DIR=${DEV_DIR:-/sdcard/Movies/DashcamStitch}
TESTDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ART=${ART:-$TESTDIR/artifacts}
ADB=${ADB:-adb}
mkdir -p "$ART"

# 用例结果汇总（run_tests.sh 末尾打印）
declare -a CASE_ROWS=()

now() { date +%s; }
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
banner() { printf '\n########## %s ##########\n' "$*"; }

# ------------------------------------------------------------------ 设备控制
dev_awake() {
  $ADB shell svc power stayon true >/dev/null 2>&1
  $ADB shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  $ADB shell wm dismiss-keyguard >/dev/null 2>&1
}

dev_sleep() { $ADB shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1; }

dev_wake_state() { $ADB shell dumpsys power 2>/dev/null | grep -m1 'mWakefulness=' | tr -d '\r'; }

app_force_stop() { $ADB shell am force-stop "$PKG" >/dev/null 2>&1; sleep 1; }

# 当前前台 Activity（简写）
top_activity() {
  $ADB shell dumpsys activity activities 2>/dev/null \
    | grep -m1 -E 'mResumedActivity' | sed -E 's/.*u0 ([^ ]+).*/\1/' | tr -d '\r'
}

# ------------------------------------------------------------------ 拼接
# start_stitch <duration> <sizeMB>
start_stitch() {
  local dur=$1 size=$2
  $ADB logcat -c 2>/dev/null
  # CLEAR_TASK：保证 MainActivity 重新 onCreate 并消费 autostart（singleTop 下否则会复用旧实例）
  $ADB shell am start -n "$PKG/.stitch.StitchHomeActivity" -f 0x10008000 \
    --ez autostart true --ei duration "$dur" --ei sizeMB "$size" >/dev/null 2>&1
}

# wait_result <timeout_sec>：成功找到 RESULT 打印该行并返回 0；崩溃返回 3；超时返回 4
wait_result() {
  local max=${1:-180} t0 line
  t0=$(now)
  while [ $(( $(now) - t0 )) -lt "$max" ]; do
    line=$($ADB logcat -d 2>/dev/null | grep -m1 'StitchTest: RESULT')
    if [ -n "$line" ]; then printf '%s\n' "$line"; return 0; fi
    if $ADB logcat -d 2>/dev/null | grep -A3 'FATAL EXCEPTION' | grep -q "$PKG"; then
      return 3
    fi
    sleep 2
  done
  return 4
}

save_log() { # save_log <label>
  local out="$ART/$1_logcat.txt"
  $ADB logcat -d >"$out" 2>/dev/null
  printf '%s\n' "$out"
}

# 日志关键行
log_highlights() {
  $ADB logcat -d 2>/dev/null \
    | grep -E 'StitchTest: (GROUPS|AUTOSTART|RESULT)|Composer: (素材|画布|编码|软件解码|解码器就绪|高码率段|结束|渲染)|AndroidRuntime|FATAL EXCEPTION' \
    | tail -40
}

# ------------------------------------------------------------------ 产物
newest_stitch() {
  $ADB shell "ls -t $DEV_DIR/STITCH_*.mp4 2>/dev/null | head -1" | tr -d '\r'
}

list_stitch() {
  $ADB shell "ls -la $DEV_DIR/STITCH_*.mp4 2>/dev/null" | tr -d '\r'
}

# pull_newest <dest>：拉取设备上最新的 STITCH 产物到本地
pull_newest() {
  local dest=$1 remote
  remote=$(newest_stitch)
  [ -z "$remote" ] && return 1
  $ADB pull "$remote" "$dest" >/dev/null 2>&1 || return 1
  printf '%s\n' "$remote"
}

# 媒体库里处于 pending（未发布）的 STITCH 行（无结果时输出空）
pending_rows() {
  $ADB shell "content query --uri content://media/external/video/media \
    --projection _id:_display_name:is_pending:_size --where \"is_pending=1\"" 2>/dev/null \
    | tr -d '\r' | grep -v '^No result found\.$' | grep -v '^$'
}

media_rows_stitch() {
  $ADB shell "content query --uri content://media/external/video/media \
    --projection _id:_display_name:is_pending:_size:duration --where \"_display_name LIKE 'STITCH_%'\"" 2>/dev/null | tr -d '\r'
}

# ------------------------------------------------------------------ 校验
# probe_json <file> -> ffprobe 关键字段（人类可读）
probe_info() {
  local f=$1
  ffprobe -v error -print_format json -show_format -show_streams "$f" 2>/dev/null | jq -r '
    "duration=\(.format.duration // "?")  size=\(.format.size // "?")  bit_rate=\(.format.bit_rate // "?")",
    ([.streams[] | select(.codec_type=="video")] | .[0]) as $v
    | "video=\($v.codec_name // "?") \($v.width // "?")x\($v.height // "?") avg_fps=\($v.avg_frame_rate // "?") r_fps=\($v.r_frame_rate // "?") nb_frames=\($v.nb_frames // "?") pix=\($v.pix_fmt // "?")",
    "audio_streams=\([.streams[] | select(.codec_type=="audio")] | length)"
  ' 2>/dev/null
}

# validate_media <file> <label> <expected_duration_sec> [expected_w] [expected_h]
# 打印检查项，全部通过返回 0，否则返回 1。
validate_media() {
  local f=$1 label=$2 exp_dur=$3 exp_w=${4:-} exp_h=${5:-}
  local fail=0
  local info dur w h fps frames audio
  info=$(probe_info "$f")
  printf '%s\n' "$info"
  printf '%s\n' "$info" >"$ART/${label}_ffprobe.txt"

  dur=$(printf '%s\n' "$info" | sed -n 's/^duration=\([0-9.]*\).*/\1/p')
  w=$(printf '%s\n' "$info" | sed -n 's/.*video=[^ ]* \([0-9]*\)x\([0-9]*\).*/\1/p')
  h=$(printf '%s\n' "$info" | sed -n 's/.*video=[^ ]* \([0-9]*\)x\([0-9]*\).*/\2/p')
  fps=$(printf '%s\n' "$info" | sed -n 's/.*avg_fps=\([^ ]*\).*/\1/p')
  frames=$(printf '%s\n' "$info" | sed -n 's/.*nb_frames=\([0-9]*\).*/\1/p')
  audio=$(printf '%s\n' "$info" | sed -n 's/^audio_streams=\([0-9]*\).*/\1/p')

  # 时长误差：源 105s 的 VFR 经历，允许 ±0.5s
  if [ -n "$dur" ] && awk -v d="$dur" -v e="$exp_dur" 'BEGIN{exit !(d>e-0.6 && d<e+0.6)}'; then
    echo "  [PASS] duration=$dur (expect ${exp_dur}s)"
  else
    echo "  [FAIL] duration=$dur (expect ${exp_dur}s)"; fail=1
  fi
  if [ -n "$exp_w" ] && [ "$w" = "$exp_w" ] && [ "$h" = "$exp_h" ]; then
    echo "  [PASS] resolution=${w}x${h}"
  elif [ -n "$exp_w" ]; then
    echo "  [FAIL] resolution=${w}x${h} (expect ${exp_w}x${exp_h})"; fail=1
  fi
  if [ "$fps" = "30/1" ]; then
    echo "  [PASS] avg_frame_rate=30/1"
  else
    echo "  [FAIL] avg_frame_rate=$fps (expect 30/1)"; fail=1
  fi
  # nb_frames 期望 = round(时长*30)，允许 ±3
  if [ -n "$frames" ] && [ -n "$dur" ] && awk -v n="$frames" -v d="$dur" 'BEGIN{e=d*30; exit !(n>e-4 && n<e+4)}'; then
    echo "  [PASS] nb_frames=$frames"
  else
    echo "  [FAIL] nb_frames=$frames (expect ~$exp_dur*30)"; fail=1
  fi
  if [ "$audio" = "0" ]; then
    echo "  [PASS] no audio track"
  else
    echo "  [FAIL] audio_streams=$audio (expect 0)"; fail=1
  fi
  return $fail
}

# decode_check <file>：全片解码，有任何 error 即失败
decode_check() {
  local f=$1 out
  out=$(ffmpeg -v error -xerror -i "$f" -f null - 2>&1)
  if [ -z "$out" ]; then
    echo "  [PASS] full decode clean"
    return 0
  fi
  echo "  [FAIL] decode errors:"; printf '%s\n' "$out" | head -20
  return 1
}

# grab <file> <sec> <out.jpg>：抽一帧
grab() {
  local f=$1 sec=$2 out=$3
  ffmpeg -v error -ss "$sec" -i "$f" -frames:v 1 -q:v 2 -y "$out" 2>/dev/null
}

# grab_last <file> <out.jpg>
grab_last() {
  local f=$1 out=$2
  ffmpeg -v error -sseof -1.0 -i "$f" -frames:v 1 -q:v 2 -y "$out" 2>/dev/null
}

# crop_plate <file> <sec> <out.png>：裁主视角车牌区域并放大
crop_plate() {
  local f=$1 sec=$2 out=$3
  ffmpeg -v error -ss "$sec" -i "$f" -frames:v 1 -y \
    -vf "crop=520:260:1650:1000,scale=1560:780:flags=lanczos" "$out" 2>/dev/null
}
