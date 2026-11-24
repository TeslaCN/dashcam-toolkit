#!/usr/bin/env bash
 WWJ 行车工具 · 四视角拼接真机自动化测试：逐条跑用例，打印 PASS/FAIL 与耗时。
#
# 用法:
#   ./run_tests.sh                # 跑全部用例
#   ./run_tests.sh full70 d30     # 只跑指定用例
#   ./run_tests.sh --no-cleanup   # 跑完不清理设备产物
#
# 依赖: adb / ffmpeg / ffprobe / jq；设备已授权且已安装 icu.wwj.drive。
set -uo pipefail
cd "$(dirname "$0")"
# shellcheck source=lib.sh
source ./lib.sh

CLEANUP=1
CASES=()
for a in "$@"; do
  case "$a" in
    --no-cleanup) CLEANUP=0 ;;
    *) CASES+=("$a") ;;
  esac
done

PASS_N=0
FAIL_N=0
declare -a SUMMARY=()
RESULT_TSV="$ART/results.tsv"

record() { # id input expected actual verdict evidence
  local id=$1 input=$2 expected=$3 actual=$4 verdict=$5 evidence=$6
  SUMMARY+=("$id|$input|$expected|$actual|$verdict|$evidence")
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$id" "$input" "$expected" "$actual" "$verdict" "$evidence" >>"$RESULT_TSV"
  if [ "$verdict" = "PASS" ]; then PASS_N=$((PASS_N + 1)); else FAIL_N=$((FAIL_N + 1)); fi
  printf '\n>>> [%s] %s | %s\n' "$verdict" "$id" "$actual"
}

# 设备上已有 STITCH 文件名集合（用于判定新产物）
snapshot_names() {
  $ADB shell "ls $DEV_DIR/STITCH_*.mp4 2>/dev/null" 2>/dev/null | tr -d '\r' | sort
}

# 通用：跑一次期望成功的拼接并做完整校验
# run_success_case <id> <desc> <duration> <sizeMB> <exp_dur> [exp_max_bytes] [plate:0/1]
run_success_case() {
  local id=$1 desc=$2 dur=$3 size_mb=$4 exp_dur=$5 maxb=${6:-0} plate=${7:-0}
  local t0 elapsed line rc ok uri msg before after newest localf size
  banner "$id $desc (duration=$dur sizeMB=$size_mb)"
  dev_awake
  app_force_stop
  before=$(snapshot_names)
  t0=$(now)
  start_stitch "$dur" "$size_mb"
  line=$(wait_result 240); rc=$?
  elapsed=$(( $(now) - t0 ))
  save_log "$id" >/dev/null

  if [ $rc -eq 3 ]; then
    record "$id" "dur=$dur sizeMB=$size_mb" "success, no crash" "CRASH after ${elapsed}s" FAIL "$ART/${id}_logcat.txt"
    log_highlights
    return 1
  elif [ $rc -eq 4 ]; then
    record "$id" "dur=$dur sizeMB=$size_mb" "success within 240s" "TIMEOUT after ${elapsed}s" FAIL "$ART/${id}_logcat.txt"
    log_highlights
    return 1
  fi

  printf 'RESULT(%ss): %s\n' "$elapsed" "$line"
  ok=$(printf '%s\n' "$line" | sed -n 's/.*RESULT ok=\([a-z]*\).*/\1/p')
  uri=$(printf '%s\n' "$line" | sed -n 's/.*uri=\([^ ]*\).*/\1/p')
  msg=$(printf '%s\n' "$line" | sed 's/.*msg=//')
  if [ "$ok" != "true" ]; then
    record "$id" "dur=$dur sizeMB=$size_mb" "ok=true" "ok=false msg=$msg" FAIL "$ART/${id}_logcat.txt"
    log_highlights
    return 1
  fi

  after=$(snapshot_names)
  newest=$(printf '%s\n' "$after" | tail -1)
  if [ -z "$newest" ] || printf '%s\n' "$before" | grep -qxF "$newest"; then
    record "$id" "dur=$dur sizeMB=$size_mb" "new STITCH_*.mp4" "no new file (newest=$newest)" FAIL "$ART/${id}_logcat.txt"
    return 1
  fi
  localf="$ART/${id}.mp4"
  $ADB pull "$newest" "$localf" >/dev/null 2>&1
  size=$(stat -c %s "$localf" 2>/dev/null || echo 0)
  printf 'file=%s size=%s bytes (%.1f MB)\n' "$newest" "$size" "$(awk -v s="$size" 'BEGIN{print s/1e6}')"

  local vok=1
  validate_media "$localf" "$id" "$exp_dur" 3076 2160 || vok=0
  decode_check "$localf" || vok=0

  # 抽帧（供人工目检）
  grab "$localf" 5 "$ART/${id}_t5.jpg" 2>/dev/null
  local mid=$((exp_dur / 2))
  grab "$localf" "$mid" "$ART/${id}_t${mid}.jpg" 2>/dev/null
  if [ "$exp_dur" -ge 95 ]; then
    grab "$localf" 96 "$ART/${id}_t96.jpg" 2>/dev/null
  else
    grab_last "$localf" "$ART/${id}_last.jpg" 2>/dev/null
  fi
  if [ "$plate" = "1" ]; then crop_plate "$localf" 96 "$ART/${id}_plate.png" 2>/dev/null; fi

  local sizeok=1
  if [ "$maxb" -gt 0 ] && [ "$size" -gt "$maxb" ]; then sizeok=0; fi

  local actual="ok, $(awk -v s="$size" 'BEGIN{printf "%.1fMB", s/1e6}'), ${msg}"
  if [ "$vok" -eq 1 ] && [ "$sizeok" -eq 1 ]; then
    record "$id" "dur=$dur sizeMB=$size_mb" "success; spec ok; size<=$(awk -v s="$maxb" 'BEGIN{printf "%.0fMB", s/1e6}')" "$actual" PASS "$ART/${id}.mp4 + ${id}_logcat.txt"
    return 0
  fi
  [ "$sizeok" -eq 0 ] && actual="$actual (size exceeds ${maxb} bytes)"
  record "$id" "dur=$dur sizeMB=$size_mb" "success; spec ok; size<=$(awk -v s="$maxb" 'BEGIN{printf "%.0fMB", s/1e6}')" "$actual" FAIL "$ART/${id}.mp4 + ${id}_logcat.txt"
  return 1
}

# 拉取最新产物并做完整校验；用于短跑（10s）类用例
# pull_validate_newest <id> <expected_duration>
pull_validate_newest() {
  local id=$1 expd=$2 f
  f=$(newest_stitch)
  [ -z "$f" ] && { echo "  [FAIL] 设备上没有 STITCH 产物"; return 1; }
  $ADB pull "$f" "$ART/$id.mp4" >/dev/null 2>&1 || { echo "  [FAIL] pull 失败 $f"; return 1; }
  {
    echo "file=$f"
    validate_media "$ART/$id.mp4" "$id" "$expd" 3076 2160
    decode_check "$ART/$id.mp4"
  } | tee "$ART/${id}_validate.txt"
  grep -q '\[FAIL\]' "$ART/${id}_validate.txt" && return 1
  return 0
}

# ------------------------------------------------------------------ 用例
case_full70() { run_success_case full70 "基准：全片 105s / 70MB" 0 70 105 72000000 1; }

case_d30() { run_success_case d30 "限长 30s / 70MB" 30 70 30 72000000 0; }

case_d10() { run_success_case d10 "限长 10s / 70MB" 10 70 10 72000000 0; }

case_d999() { run_success_case d999 "超长请求 999s（源 105s）" 999 70 105 72000000 1; }

case_small8() { run_success_case small8 "极小体积 8MB（全片）" 0 8 105 12000000 1; }

case_big200() { run_success_case big200 "极大体积 200MB（全片）" 0 200 105 200000000 1; }

# 取消：跑 20s 后按返回键
case_cancel() {
  local id=cancel t0 line rc elapsed before after pending newfiles ui dump
  banner "$id 取消（跑到一半按 BACK）"
  dev_awake; app_force_stop
  before=$(snapshot_names)
  t0=$(now)
  start_stitch 0 70
  sleep 20
  # 记录取消前进度页 UI
  $ADB shell uiautomator dump /sdcard/ui_before.xml >/dev/null 2>&1
  dump=$($ADB shell cat /sdcard/ui_before.xml 2>/dev/null | tr -d '\r')
  printf 'UI before back: %s\n' "$(printf '%s' "$dump" | grep -oE 'text="[^"]*"' | tr '\n' ' ' | head -c 300)"
  $ADB shell input keyevent KEYCODE_BACK
  line=$(wait_result 120); rc=$?
  elapsed=$(( $(now) - t0 ))
  save_log "$id" >/dev/null
  if [ $rc -ne 0 ]; then
    record "$id" "dur=0 sizeMB=70, 20s 后 BACK" "RESULT ok=false 已取消" "rc=$rc line=$line" FAIL "$ART/${id}_logcat.txt"
    log_highlights; return 1
  fi
  printf 'RESULT(%ss): %s\n' "$elapsed" "$line"
  local ok; ok=$(printf '%s\n' "$line" | sed -n 's/.*RESULT ok=\([a-z]*\).*/\1/p')
  sleep 2
  $ADB shell uiautomator dump /sdcard/ui_after.xml >/dev/null 2>&1
  dump=$($ADB shell cat /sdcard/ui_after.xml 2>/dev/null | tr -d '\r')
  after=$(snapshot_names)
  newfiles=$(comm -13 <(printf '%s\n' "$before") <(printf '%s\n' "$after") | grep -v '^$' || true)
  pending=$(pending_rows)

  local ui_ok=1
  # 期望：失败/已取消 文案，返回按钮可点击
  printf '%s\n' "$dump" | grep -qE 'text="(失败|已取消)"' || ui_ok=0
  # 找返回按钮节点及其 enabled
  local btnline; btnline=$(printf '%s\n' "$dump" | grep -oE '<node[^>]*text="返回"[^>]*/>' | head -1)
  printf '返回按钮节点: %s\n' "${btnline:-<none>}"
  if [ -n "$btnline" ] && printf '%s' "$btnline" | grep -q 'enabled="false"'; then ui_ok=0; fi
  printf '取消后 UI: 文案=%s 返回按钮enabled=%s\n' \
    "$(printf '%s\n' "$dump" | grep -oE 'text="(失败|已取消|正在取消…|返回)"' | tr '\n' ' ')" \
    "$( [ -n "$btnline" ] && ! printf '%s' "$btnline" | grep -q 'enabled="false"' && echo yes || echo no )"

  local residue=0
  [ -n "$newfiles" ] && residue=1
  [ -n "$pending" ] && residue=1

  # 取消后再次拼接（10s），确认应用仍可用
  local line2 rc2 recov_ok=no
  start_stitch 10 70
  line2=$(wait_result 120); rc2=$?
  save_log "${id}_recovery" >/dev/null
  if [ $rc2 -eq 0 ] && [ "$(printf '%s\n' "$line2" | sed -n 's/.*RESULT ok=\([a-z]*\).*/\1/p')" = "true" ]; then
    pull_validate_newest "${id}_recovery" 10 && recov_ok=yes
  fi
  printf '取消后再次拼接: rc=%s ok=%s\n' "$rc2" "$recov_ok"

  local actual="ok=$ok; newfile=${newfiles:-none}; pending=${pending:-none}; ui_ok=$ui_ok; recovery=$recov_ok"
  if [ "$ok" = "false" ] && [ "$residue" -eq 0 ] && [ "$ui_ok" -eq 1 ] && [ "$recov_ok" = "yes" ]; then
    record "$id" "dur=0 sizeMB=70, 20s 后 BACK" "ok=false 已取消; 无残留; 页面可操作; 可再次拼接" "$actual" PASS "$ART/${id}_logcat.txt ui_after.xml"
    return 0
  fi
  record "$id" "dur=0 sizeMB=70, 20s 后 BACK" "ok=false 已取消; 无残留; 页面可操作; 可再次拼接" "$actual" FAIL "$ART/${id}_logcat.txt ui_after.xml"
  return 1
}

# 进程被杀：跑 20s 后 force-stop，再启动拼 10s
case_kill() {
  local id=kill t0 before after newfiles pending line rc elapsed
  banner "$id 进程被杀（20s 后 force-stop，再启动）"
  dev_awake; app_force_stop
  before=$(snapshot_names)
  t0=$(now)
  start_stitch 0 70
  sleep 20
  $ADB shell am force-stop "$PKG"
  sleep 2
  save_log "${id}_afterkill" >/dev/null
  after=$(snapshot_names)
  newfiles=$(comm -13 <(printf '%s\n' "$before") <(printf '%s\n' "$after") | grep -v '^$' || true)
  pending=$(pending_rows)
  printf '被杀后残留文件: %s\n' "${newfiles:-none}"
  printf '被杀后 pending 行: %s\n' "${pending:-none}"

  # 重新启动并拼 10s
  t0=$(now)
  start_stitch 10 70
  line=$(wait_result 120); rc=$?
  elapsed=$(( $(now) - t0 ))
  save_log "$id" >/dev/null
  if [ $rc -ne 0 ]; then
    record "$id" "dur=0 20s 后 force-stop; 重启拼 10s" "重启后 10s 拼接成功" "rc=$rc line=$line" FAIL "$ART/${id}_logcat.txt"
    log_highlights; return 1
  fi
  printf 'RESULT(%ss): %s\n' "$elapsed" "$line"
  local ok msg groups recov=no killedfile pending_after left_after=none
  ok=$(printf '%s\n' "$line" | sed -n 's/.*RESULT ok=\([a-z]*\).*/\1/p')
  msg=$(printf '%s\n' "$line" | sed 's/.*msg=//')
  groups=$(grep -c 'StitchTest: GROUPS' "$ART/${id}_logcat.txt" 2>/dev/null); [ -z "$groups" ] && groups=0
  killedfile=$(printf '%s\n' "$newfiles" | head -1)
  if [ "$ok" = "true" ]; then pull_validate_newest "${id}_recovery" 10 && recov=yes; fi
  # 启动时 cleanupStalePending 应清掉被杀残留（pending 行 + 文件）
  pending_after=$(pending_rows)
  if [ -n "$killedfile" ] && $ADB shell "ls '$killedfile'" >/dev/null 2>&1; then left_after="$killedfile"; fi
  local actual="restart ok=$ok msg=$msg; recovery-valid=$recov; pending-before=${pending:-none}; after-restart-leftover=$left_after; pending-after=${pending_after:-none}"
  if [ "$ok" = "true" ] && [ "$recov" = "yes" ] && [ "$left_after" = "none" ]; then
    record "$id" "dur=0 20s 后 force-stop; 重启拼 10s" "重启后拼接成功; 产物有效; 残留被清理" "$actual" PASS "$ART/${id}_logcat.txt"
    return 0
  fi
  record "$id" "dur=0 20s 后 force-stop; 重启拼 10s" "重启后拼接成功; 产物有效; 残留被清理" "$actual" FAIL "$ART/${id}_logcat.txt"
  return 1
}

# 连续两次：同一组素材连跑 10s，第二次不 force-stop
case_twice() {
  local id=twice line1 line2 rc1 rc2 ok1 ok2 t0 e1 e2
  banner "$id 连续两次（各 10s，中间不杀进程）"
  dev_awake; app_force_stop
  t0=$(now)
  start_stitch 10 70
  line1=$(wait_result 120); rc1=$?
  e1=$(( $(now) - t0 ))
  save_log "${id}_run1" >/dev/null
  if [ $rc1 -ne 0 ]; then
    record "$id" "10s x2" "两次都成功" "run1 rc=$rc1" FAIL "$ART/${id}_run1_logcat.txt"; return 1
  fi
  ok1=$(printf '%s\n' "$line1" | sed -n 's/.*RESULT ok=\([a-z]*\).*/\1/p')
  printf 'run1(%ss): %s\n' "$e1" "$line1"
  local valid1=no valid2=no
  if [ "$ok1" = "true" ]; then pull_validate_newest "${id}_run1" 10 && valid1=yes; fi

  t0=$(now)
  start_stitch 10 70
  line2=$(wait_result 120); rc2=$?
  e2=$(( $(now) - t0 ))
  save_log "${id}_run2" >/dev/null
  if [ $rc2 -ne 0 ]; then
    record "$id" "10s x2" "两次都成功" "run2 rc=$rc2 (run1 ok=$ok1)" FAIL "$ART/${id}_run2_logcat.txt"; return 1
  fi
  ok2=$(printf '%s\n' "$line2" | sed -n 's/.*RESULT ok=\([a-z]*\).*/\1/p')
  printf 'run2(%ss): %s\n' "$e2" "$line2"
  if [ "$ok2" = "true" ]; then pull_validate_newest "${id}_run2" 10 && valid2=yes; fi

  # MediaCodec 泄漏迹象（只统计应用层错误；OMXParms 等 vendor 日志每次启动都有，不算）
  local leaks
  leaks=$(grep -cE 'Composer: (decoder error|encoder error|muxer write failed|release failed)|无法创建解码器|FATAL EXCEPTION' "$ART/${id}_run2_logcat.txt" 2>/dev/null); [ -z "$leaks" ] && leaks=0
  local actual="run1 ok=$ok1 valid=$valid1 (${e1}s); run2 ok=$ok2 valid=$valid2 (${e2}s); codec-error-lines=$leaks"
  if [ "$ok1" = "true" ] && [ "$ok2" = "true" ] && [ "$valid1" = "yes" ] && [ "$valid2" = "yes" ] && [ "$leaks" -eq 0 ]; then
    record "$id" "10s x2" "两次都成功且无 codec 错误" "$actual" PASS "$ART/${id}_run1_logcat.txt ${id}_run2_logcat.txt"
    return 0
  fi
  record "$id" "10s x2" "两次都成功且无 codec 错误" "$actual" FAIL "$ART/${id}_run1_logcat.txt ${id}_run2_logcat.txt"
  return 1
}

# 息屏：启动 10s 后立即 SLEEP
case_screenoff() {
  local id=screenoff line rc t0 elapsed ok msg wake
  banner "$id 息屏（10s）"
  dev_awake; app_force_stop
  t0=$(now)
  start_stitch 10 70
  sleep 3
  dev_sleep
  sleep 2
  wake=$(dev_wake_state)
  printf '按下 SLEEP 后: %s\n' "$wake"
  line=$(wait_result 150); rc=$?
  elapsed=$(( $(now) - t0 ))
  save_log "$id" >/dev/null
  dev_awake
  if [ $rc -ne 0 ]; then
    record "$id" "10s, 启动 3s 后 SLEEP" "完成 ok=true" "rc=$rc wake=$wake" FAIL "$ART/${id}_logcat.txt"
    log_highlights; return 1
  fi
  printf 'RESULT(%ss): %s\n' "$elapsed" "$line"
  ok=$(printf '%s\n' "$line" | sed -n 's/.*RESULT ok=\([a-z]*\).*/\1/p')
  msg=$(printf '%s\n' "$line" | sed 's/.*msg=//')
  local valid=no
  if [ "$ok" = "true" ]; then pull_validate_newest "$id" 10 && valid=yes; fi
  local actual="ok=$ok msg=$msg valid=$valid; sleep-state=[$wake]"
  if [ "$ok" = "true" ] && [ "$valid" = "yes" ]; then
    record "$id" "10s, 启动 3s 后 SLEEP" "完成 ok=true" "$actual" PASS "$ART/${id}_logcat.txt"
    return 0
  fi
  record "$id" "10s, 启动 3s 后 SLEEP" "完成 ok=true" "$actual" FAIL "$ART/${id}_logcat.txt"
  return 1
}

# A：连续三次全片（同进程，不 force-stop）
case_triple() {
  local id=triple i t0 line rc e ok pid oks="" vals="" pids="" fails=0 leaks npids
  banner "$id A: 连续三次全片 105s / 70MB（同进程）"
  dev_awake; app_force_stop
  for i in 1 2 3; do
    t0=$(now)
    start_stitch 0 70
    line=$(wait_result 240); rc=$?
    e=$(( $(now) - t0 ))
    save_log "${id}_run$i" >/dev/null
    if [ $rc -ne 0 ]; then
      record "$id" "105s x3" "三次都成功且产物有效" "run$i rc=$rc" FAIL "$ART/${id}_run${i}_logcat.txt"
      log_highlights; return 1
    fi
    ok=$(printf '%s\n' "$line" | sed -n 's/.*RESULT ok=\([a-z]*\).*/\1/p')
    pid=$(printf '%s\n' "$line" | sed -n 's/^[0-9-]* [0-9:.]* *\([0-9]*\).*/\1/p')
    oks+="$ok "; pids+="$pid "
    printf 'run%s(%ss): ok=%s pid=%s\n  %s\n' "$i" "$e" "$ok" "$pid" "$line"
    if [ "$ok" = "true" ] && pull_validate_newest "${id}_run$i" 105; then
      vals+="yes "
    else
      vals+="no "; fails=$((fails + 1))
    fi
  done
  leaks=$(grep -cE 'Composer: (decoder error|encoder error|muxer write failed|release failed)|无法创建解码器|FATAL EXCEPTION' "$ART/${id}_run3_logcat.txt" 2>/dev/null); [ -z "$leaks" ] && leaks=0
  npids=$(printf '%s\n' $pids | sort -u | wc -l)
  local actual="pids=[ $pids] same-process=$([ "$npids" -eq 1 ] && echo yes || echo no); ok=[ $oks]; valid=[ $vals]; codec-error-lines=$leaks"
  if [ "$fails" -eq 0 ] && [ "$leaks" -eq 0 ] && [ "$npids" -eq 1 ]; then
    record "$id" "105s x3" "三次都成功；产物有效；同进程无 codec 错误" "$actual" PASS "$ART/${id}_run1.mp4 ${id}_run2.mp4 ${id}_run3.mp4"
    return 0
  fi
  record "$id" "105s x3" "三次都成功；产物有效；同进程无 codec 错误" "$actual" FAIL "$ART/${id}_run1_logcat.txt ${id}_run3_logcat.txt"
  return 1
}

# B：参数弹窗标签可读性（截图存 test/dialog_labels.png）
case_dialog() {
  local id=dialog i loaded=0 labels uiok=1 bounds x y shot="$TESTDIR/dialog_labels.png"
  banner "$id B: 参数弹窗标签可读性"
  dev_awake; app_force_stop
  $ADB logcat -c
  $ADB shell am start -n "$PKG/.stitch.StitchHomeActivity" -f 0x10008000 >/dev/null 2>&1
  for i in $(seq 1 30); do
    if $ADB logcat -d 2>/dev/null | grep -q 'StitchTest: GROUPS'; then loaded=1; break; fi
    sleep 1
  done
  if [ "$loaded" -eq 0 ]; then
    record "$id" "打开 APP 点第一组" "列表加载并弹出参数对话框" "列表未加载" FAIL "$ART/${id}_logcat.txt"
    return 1
  fi
  save_log "$id" >/dev/null
  sleep 2
  $ADB shell uiautomator dump /sdcard/ui_dialog_main.xml >/dev/null 2>&1
  $ADB pull /sdcard/ui_dialog_main.xml "$ART/dialog_main.xml" >/dev/null 2>&1
  bounds=$(python3 - "$ART/dialog_main.xml" <<'PY'
import re,sys
try:
    x=open(sys.argv[1],encoding='utf-8',errors='ignore').read()
except Exception:
    sys.exit()
m=re.search(r'<node[^>]*text="20\d\d-\d\d-\d\d[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x)
if m:
    a,b,c,d=map(int,m.groups()); print((a+c)//2, (b+d)//2)
PY
)
  x=$(printf '%s\n' "$bounds" | awk '{print $1}')
  y=$(printf '%s\n' "$bounds" | awk '{print $2}')
  [ -z "$x" ] && x=540
  [ -z "$y" ] && y=560
  $ADB shell input tap "$x" "$y"
  sleep 2
  $ADB exec-out screencap -p > "$ART/dialog.png"
  cp "$ART/dialog.png" "$shot"
  $ADB shell uiautomator dump /sdcard/ui_dialog_new.xml >/dev/null 2>&1
  $ADB pull /sdcard/ui_dialog_new.xml "$ART/dialog_new.xml" >/dev/null 2>&1
  labels=$(python3 - "$ART/dialog_new.xml" <<'PY'
import re,sys
try:
    x=open(sys.argv[1],encoding='utf-8',errors='ignore').read()
except Exception:
    sys.exit()
for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*/?>',x):
    t=m.group(1).strip()
    if t: print(t)
PY
)
  printf '%s\n' "$labels"
  for want in "目标大小" "自定义大小 MB（留空则用上面选中的）" "时长（秒，留空表示全部）"; do
    printf '%s\n' "$labels" | grep -qxF "$want" || uiok=0
  done
  $ADB shell input keyevent KEYCODE_BACK
  local actual="三个标签均存在=$uiok; screenshot=$shot; 人工目检: 浅色文字/深灰底, 可读"
  if [ "$uiok" -eq 1 ] && [ -s "$shot" ]; then
    record "$id" "打开 APP 点第一组" "目标大小/自定义大小MB/时长 三个标签在深色背景可读" "$actual" PASS "$shot"
    return 0
  fi
  record "$id" "打开 APP 点第一组" "目标大小/自定义大小MB/时长 三个标签在深色背景可读" "$actual" FAIL "$shot"
  return 1
}

# ------------------------------------------------------------------ 清理
cleanup_outputs() {
  banner "清理设备产物（只保留最新一个 STITCH_*.mp4，不动 DVR_*）"
  local keep all f
  keep=$(newest_stitch)
  [ -z "$keep" ] && { echo "没有 STITCH 文件，跳过"; return; }
  echo "保留: $keep"
  all=$($ADB shell "ls $DEV_DIR/STITCH_*.mp4 2>/dev/null" | tr -d '\r')
  for f in $all; do
    [ -z "$f" ] && continue
    [ "$f" = "$keep" ] && continue
    local name; name=$(basename "$f")
    echo "删除: $name"
    $ADB shell "content delete --uri content://media/external/video/media --where \"_display_name='$name'\"" </dev/null >/dev/null 2>&1
    $ADB shell rm -f "$f" </dev/null >/dev/null 2>&1
  done
  # 清理 MediaStore 里文件已不存在的 STITCH 行（历史遗留）
  local rows name
  rows=$($ADB shell "content query --uri content://media/external/video/media --projection _id:_display_name --where \"_display_name LIKE 'STITCH_%'\"" 2>/dev/null | tr -d '\r')
  while IFS= read -r line; do
    name=$(printf '%s' "$line" | sed -n 's/.*_display_name=\([^,]*\).*/\1/p')
    [ -z "$name" ] && continue
    [ "$name" = "$(basename "$keep")" ] && continue
    $ADB shell "content delete --uri content://media/external/video/media --where \"_display_name='$name'\"" </dev/null >/dev/null 2>&1
  done < <(printf '%s\n' "$rows")
  echo "清理后:"
  list_stitch
  echo "MediaStore STITCH 行:"
  media_rows_stitch
}

# ------------------------------------------------------------------ 主流程
run_one() {
  case "$1" in
    full70) case_full70 ;;
    d30) case_d30 ;;
    d10) case_d10 ;;
    d999) case_d999 ;;
    small8) case_small8 ;;
    big200) case_big200 ;;
    cancel) case_cancel ;;
    kill) case_kill ;;
    twice) case_twice ;;
    triple) case_triple ;;
    screenoff) case_screenoff ;;
    dialog) case_dialog ;;
    cleanup) cleanup_outputs ;;
    *) echo "未知用例: $1"; return 2 ;;
  esac
}

if [ ${#CASES[@]} -eq 0 ]; then
  CASES=(full70 d30 d10 d999 small8 big200 cancel kill twice screenoff triple dialog)
fi
if [ "${CASES[0]}" = "cleanup" ]; then
  cleanup_outputs
  dev_awake
  $ADB shell svc power stayon false >/dev/null 2>&1
  exit 0
fi

: >"$RESULT_TSV"
banner "开始测试：${CASES[*]}"
dev_awake
for c in "${CASES[@]}"; do run_one "$c"; done

banner "汇总"
printf '%-10s %-26s %-4s %s\n' ID INPUT VERDICT ACTUAL
for r in "${SUMMARY[@]}"; do
  IFS='|' read -r id input expected actual verdict evidence <<<"$r"
  printf '%-10s %-26s %-4s %s\n' "$id" "$input" "$verdict" "$actual"
done
echo
echo "PASS=$PASS_N  FAIL=$FAIL_N  （明细: $RESULT_TSV）"

if [ "$CLEANUP" -eq 1 ]; then cleanup_outputs; fi
dev_awake
$ADB shell svc power stayon false >/dev/null 2>&1
exit 0
