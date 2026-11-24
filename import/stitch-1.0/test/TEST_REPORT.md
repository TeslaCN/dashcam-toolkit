# 终验（sha256 d9811acc…）

**结论：可以交付（10/10 用例 PASS）。**

- **测试时间**：2026-11-24 02:08–02:21（Asia/Shanghai）
- **设备**：HUAWEI P30（ELE-AL00），Android 10（API 29），1080×2340
- **被测包（唯一安装版本）**：`dist/dashcam-stitch-1.0.apk`，45,823 B，sha256 `d9811acc3f3958cd0151d7e8aaf181fbaaf3344f3a2255b350559996437c15a5`；`adb install -r` 后从设备 `/data/app/com.dashcam.stitch-*/base.apk` 回读 sha256，与上述值一致
- **存储权限**：安装后 `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` 均为 `granted=true`（无需手工补授）
- **素材**：`/sdcard/Movies/DashcamStitch/DVR_{MAIN,LEFT,BACK,RIGHT}_2026_11_23_20_12_55_780.mp4`，4 路各 105 s
- **触发方式**：`am start -n com.dashcam.stitch/.MainActivity --ez autostart true --ei duration N --ei sizeMB M`；每 2 s 轮询 `adb logcat -d | grep "StitchTest: RESULT"`，上限 180 s
- **证据目录**：`test/final/`（逐用例 logcat、ffprobe JSON/TXT、抽帧与车牌放大图、UI dump、截图；关键日志汇总见 `logcat_highlights.txt`）

## 1. 用例结果

| # | 参数 | 期望 | 实际 | 结论 | 证据 |
|---|---|---|---|---|---|
| 1 | duration=0, sizeMB=70 | ok=true；105.0±0.2 s；3145~3155 帧；3076×2160；30 fps；55~70 MB；全片解码无错；1:36 车牌可辨 | ok=true；**105.032 s**；**3151 帧**；**3076×2160**；**30/1**；**63,480,701 B（63.48 MB）**；`ffmpeg -xerror` 全片无输出；96 s 帧 **沪M·A8678** 放大后清晰可辨 | **PASS** | `baseline_70.mp4`、`baseline_70_ffprobe.txt/json`、`baseline_70_t96.jpg`、`baseline_70_plate.png`、`logcat_baseline_70.txt` |
| 2 | 由 #1 产物计算每秒码率 | 90 s 之后 ≥ 85 s 之前的 2 倍 | 85 s 前均值 **3747 kbps**；90–104 s 均值 **10592 kbps**；**2.83×**（90 s 处由 ~3.7 Mbps 跳到 ~11 Mbps，日志 `高码率段 10204kbps`） | **PASS** | `per_sec_bitrate.txt`、`bitrate_compare.txt`、`logcat_baseline_70.txt` |
| 3 | duration=0, sizeMB=8 | ok=true；体积 ≤8.5 MB；分辨率低于 3076×2160；可解码 | ok=true；**7,739,480 B（7.74 MB）**；**1722×1208**；105.032 s / 3151 帧；全片解码无错；日志 `目标体积过小，已取消末尾提码率`、`分辨率降为 1722x1208`、`编码 1722x1208 499kbps`（无 `高码率段`） | **PASS** | `small8.mp4`、`small8_ffprobe.txt/json`、`small8_t96.jpg`、`logcat_small8.txt` |
| 4 | duration=0, sizeMB=40，20 s 时 force-stop；+5 s 启动；+65 s 冷启动 | 无孤儿文件；logcat 有 `ORPHAN ... deleted=true` | 被杀时半成品 `STITCH_20261124_021704.mp4` 7,397,027 B（MediaStore 无记录、无 moov、不可解码）；冷启动后 `ORPHAN STITCH_20261124_021704.mp4 known=17 deleted=true size=0`，文件消失；目录 13 个 STITCH 与 MediaStore 13 行一一对应，**0 孤儿** | **PASS** | `logcat_orphan_final.txt`、`kill_orphan_partial.mp4`（半成品样本）、`logcat_highlights.txt` |
| 5 | duration=0，16 s（≈20%）时 BACK | ok=false；消息“已取消”；MediaStore 无残留；返回按钮可点；可回列表 | `RESULT ok=false ... msg=已取消`；取消前后均 13 文件 / 13 行，无新文件、无 pending 行；`返回` 按钮 `enabled=true clickable=true`；点按后 `mResumedActivity=com.dashcam.stitch/.MainActivity` | **PASS** | `logcat_cancel.txt`、`ui_cancel.xml`、`cancel_result.png`、`ui_after_back.xml`、`after_back_list.png` |
| 6 | duration=20, sizeMB=30，连跑 3 次 | 3 次都 ok=true | **3/3 ok=true**；均 20.033 s / 601 帧 / 3076×2160；27,300,356 / 27,264,509 / 27,293,467 B；三份全片解码无错 | **PASS** | `triple1..3.mp4`、`logcat_triple_run1..3.txt` |
| 7 | duration=999 | 按 105 s 截断、不崩 | ok=true；**104.999 s**；3150 帧；3076×2160；63,354,127 B；无崩溃、无 FATAL | **PASS** | `d999.mp4`、`d999_ffprobe.txt`、`logcat_d999.txt` |
| 8 | duration=30 / duration=10 | 时长误差 <0.2 s | 30 s → **30.033 s**（+0.033 s）/ 901 帧；10 s → **10.033 s**（+0.033 s）/ 301 帧 | **PASS** | `d30.mp4`、`d10.mp4`、`d30_ffprobe.txt`、`d10_ffprobe.txt`、`logcat_d30.txt`、`logcat_d10.txt` |
| 9 | 手动点第一组素材，截参数弹窗 | “目标大小 / 自定义大小 MB / 时长”在深色底清晰可读 | 三标签文本齐全：`目标大小`、`自定义大小 MB（留空则用上面选中的）`、`时长（秒，留空表示全部）`；截图浅色字 + 深灰底，清晰可读 | **PASS** | `dialog_labels.png`、`ui_dialog.xml` |
| 10 | 对话框默认 70 MB 启动，成功后截图 | 显示 `用时 X 秒 · YY.Y MB · 3076x2160 · N 帧 · 编码器` | `用时 76 秒 · 63.5 MB · 3076x2160 · 3151 帧 · OMX.hisi.video.encoder.hevc` | **PASS** | `result_page.png`、`ui_result.xml`、`logcat_ui_result.txt` |

## 2. 基准 / 小目标产物对照

| 项 | 基准（70 MB 目标） | 小目标（8 MB 目标） |
|---|---|---|
| 容器时长 | 105.032 s | 105.032 s |
| 文件大小 | 63.48 MB（目标 70 MB，-9.3%） | **7.74 MB（目标 8 MB，-3.2%）** |
| 分辨率 | 3076×2160 | **1722×1208** |
| 帧率 / 帧数 | 30/1 · 3151 | 30/1 · 3151 |
| 平均码率 | 4.84 Mbps | 0.59 Mbps |
| 末尾提码率 | 3401→10204 kbps（90–105 s） | **无**（全片码率平坦 0.4–0.8 Mbps） |
| 全片解码 | 无错（8 份产物统一复核，见 `decode_checks.txt`） | 无错 |

## 3. 说明与残余观察（不阻塞交付）

- **孤儿清理只在冷启动（`MainActivity.onCreate`）执行**：实测中按字面用两次普通 `am start`（第二次进程仍存活）时，`singleTop` 只走 `onNewIntent`，不会重跑扫描，半成品会留到下一次冷启动；kill 后 +65 s 做一次真正的冷启动（force-stop + start）即得到 `ORPHAN ... deleted=true` 并删除文件。真实用户“重新打开 APP”就是冷启动，因此不阻塞；建议把 `cleanupOrphans()` 也挂到 `onNewIntent`，或在 README 说明“下次冷启动清理”。
- `ORPHAN ... size=0` 是因为日志在 `delete()` 之后才取 `length()`，属日志显示问题，不影响删除结果。
- 厂商日志偶见 `OMXNodeInstance ... BadPortIndex`（华为 HEVC 编码器配置阶段噪声），app 层无 codec/封装错误；10 条用例产物全部可完整解码。
- 设备最终清理：`/sdcard/Movies/DashcamStitch/` 只保留最新 `STITCH_20261124_021938.mp4`（63.48 MB，MediaStore 1 行），4 个 `DVR_*` 原样保留，`svc power stayon false` 已恢复。

---

> 以下为上一轮候选包（sha256 `f4e64c59…`）的测试记录，保留作对照。

# 四视角拼接（DashcamStitch）真机验收测试报告

- **测试时间**：2026-11-24 01:24–02:00（Asia/Shanghai）
- **设备**：HUAWEI P30（ELE-AL00），Android 10（API 29），Kirin 980，1080×2340，density 480（override 408）
- **被测 APK**：`build/dashcam-stitch.apk`，45,823 bytes，**sha256 `f4e64c594201e611e125a7e44ae2f8815fc78936fc38236a8722cbe5f387eada`**，构建 01:23:46，`adb install -r` 安装于 01:24:57（安装后从设备回读 APK，sha256 一致）
- **对照基线**：`dist/dashcam-stitch-1.0.apk`，sha256 `86250d6a3d0e39ec828e8956dc06dcbcce758e137edc947c573276fc84aaa50b`（01:02 构建，最初设备上装的就是它；其失败项列在文末供对照）
- **测试素材**：`/sdcard/Movies/DashcamStitch/DVR_{MAIN,LEFT,BACK,RIGHT}_2026_11_23_20_12_55_780.mp4`，4 路各 105.0 s；MAIN 3840×1696，侧视角 1280×1000；源内含 AAC 音轨
- **测试脚本**：`test/run_tests.sh` + `test/lib.sh`（12 个用例，逐条打印 PASS/FAIL、耗时，产物与日志落在 `test/artifacts/`）
- **校验手段**：`adb logcat` 轮询 `StitchTest: RESULT`；`ffprobe` 查容器/流参数；`ffmpeg -xerror` 全片解码；按 PTS 检查 CFR；抽帧目检 + 车牌区域放大；`uiautomator` 检查控件可用性；截图目检

## 1. 结果总览

**10 PASS / 2 FAIL**（明细 `test/artifacts/results.tsv`，完整终端记录 `test/artifacts/console_FINAL.log`）

| # | 用例 | 输入 | 期望 | 实际 | 结论 | 证据 |
|---|---|---|---|---|---|---|
| T01 | 基准全片 | duration=0, sizeMB=70 | 成功；≈105 s、≈3150 帧、3076×2160、30 fps、无音轨 | 105.032 s · 3151 帧 · 3076×2160 · 30/1 · 无音轨 · 63.48 MB · 全片解码无错 | **PASS** | `artifacts/full70.mp4`、`full70_logcat.txt`、`full70_ffprobe.txt` |
| T02 | 限长 30 s | duration=30, sizeMB=70 | 成功；30 s / ≈900 帧 | 30.033 s · 901 帧 · 63.38 MB · 30/1 · 无音轨 | **PASS** | `artifacts/d30.mp4`、`d30_logcat.txt` |
| T03 | 限长 10 s | duration=10, sizeMB=70 | 成功；10 s / ≈300 帧 | 10.033 s · 301 帧 · 58.02 MB · 30/1 · 无音轨 | **PASS** | `artifacts/d10.mp4`、`d10_logcat.txt` |
| T04 | 超长请求 | duration=999, sizeMB=70 | 截断到源 105 s，不崩 | 104.999 s · 3150 帧 · 63.35 MB · 全片解码无错 | **PASS** | `artifacts/d999.mp4`、`d999_logcat.txt` |
| T05 | 极小体积 | duration=0, sizeMB=8 | 文件确实接近 8 MB、可解码、画面可辨 | **19.62 MB（目标的 245%，+145%）**；可解码、整体画面可辨、车牌可辨 | **FAIL** | `artifacts/small8.mp4`、`small8_logcat.txt`、`small8_plate.png` |
| T06 | 极大体积 | duration=0, sizeMB=200 | 编码器上限内、不崩、文件有效 | 175.57 MB（-12%）；3076×2160；全片解码无错；**未触发编码器码率上限**（仅需 9.7 Mbps，远低于 P30 的 80 Mbps 上限） | **PASS** | `artifacts/big200.mp4`、`big200_logcat.txt` |
| T07 | 取消 | duration=0, sizeMB=70，20 s 后 BACK | ok=false；无残留；页面可操作；可再次拼接 | `RESULT ok=false ... msg=已取消`；无新文件、无 pending 行；返回按钮 `enabled=true`；随后 10 s 拼接成功且产物有效 | **PASS** | `artifacts/cancel_logcat.txt`、`artifacts/cancel_recovery.mp4` |
| T08 | 进程被杀 | duration=0，20 s 后 force-stop；重启拼 10 s | 不崩、列表正常；候选包声称的启动清理生效 | 重启后 10 s 拼接成功、产物有效；**但被杀留下的 12.81 MB 半成品文件在重启后仍在**（`STITCH_..._015000.mp4`，无 MediaStore 记录、无法解码） | **FAIL** | `artifacts/kill_logcat.txt`、`artifacts/kill_orphan.mp4`、`kill_orphan_evidence.txt` |
| T09 | 连续两次 | 10 s ×2，不杀进程 | 两次都成功、无 codec 错误 | 两次均 10.033 s / 301 帧 / 58.0 MB；app 层 codec 错误 0 | **PASS** | `artifacts/twice_run1_logcat.txt`、`twice_run2_logcat.txt` |
| T10 | 息屏 | 10 s，启动 3 s 后 SLEEP | 息屏状态下完成 | `mWakefulness=Asleep`，8 s 后 `RESULT ok=true`；10.033 s / 301 帧 / 58.0 MB | **PASS** | `artifacts/screenoff_logcat.txt`、`screenoff.mp4` |
| A | 连续三次全片 | 105 s / 70 MB ×3，同进程 | 第 3 次仍成功，无 codec 泄漏 | 3 次均 ok=true，**PID 均为 32452**；3 份产物均 105.032 s / 3151 帧 / 3076×2160、解码无错；app 层 codec 错误 0 | **PASS** | `artifacts/triple_run1..3.mp4`、`triple_run*_logcat.txt` |
| B | 参数弹窗可读性 | 打开 APP，点第一组素材 | 目标大小/自定义大小 MB/时长 三标签在深色背景可读 | 三个标签文本均存在；截图中为浅色文字 + 深灰底，清晰可读 | **PASS** | `test/dialog_labels.png`、`artifacts/dialog_new.xml` |

耗时参考（满片）：基准 77 s、超长 78 s、极小 77 s、极大 78 s；30 s 用例 24 s；10 s 用例 10 s。

## 2. 基准产物规格（T01）

| 项 | 值 |
|---|---|
| 容器时长 | 105.032 s（源 105.000 s） |
| 视频 | HEVC，3076×2160，`avg_frame_rate=30/1`，`nb_frames=3151`，`yuv420p` |
| 音轨 | **0 条**（源素材有 AAC，输出按设计静音） |
| 文件大小 / 平均码率 | 63,481,167 B / 4.84 Mbps（目标 70 MB，-9.3%） |
| 全片解码 | `ffmpeg -v error -xerror -i full70.mp4 -f null -` 无任何错误 |
| 帧间隔（PTS） | 3151 帧，首帧 0.000 s，末帧 105.000 s；Δ=0.033333/0.033334 s 各 2100/1050 次，**无 >34 ms 的缺口，严格 CFR 30 fps** |
| 首帧 | YAVG=87.5（与第 5 秒帧一致，非黑帧）；末帧 YAVG=75.3，非黑 |
| 画面布局 | 主视角整幅在上，左/后/右三等分在下；无黑块、无上下颠倒、无颜色异常；四路各自保持原始宽高比，未见拉伸 |
| 车牌 | 96 s 处主视角黑色奥迪 **沪M·A8678** 放大后清晰可辨：`artifacts/full70_plate.png` |

抽帧目检：`artifacts/full70_t5.jpg`、`full70_t52.jpg`、`full70_t96.jpg`，以及 `full70_plate.png`。

## 3. 问题清单（按严重度）

### P2-1　`sizeMB=8` 目标大小不生效：实际 19.6 MB（+145%）　【T05 FAIL】

**复现步骤**

```bash
adb shell am force-stop com.dashcam.stitch
adb shell am start -n com.dashcam.stitch/.MainActivity \
  --ez autostart true --ei duration 0 --ei sizeMB 8
# 约 77 s 后
adb logcat -d | grep -E "Composer: 编码|高码率段|StitchTest: RESULT"
```

**关键日志/数据**

```
I Composer: 编码 3076x2160 1000kbps (末尾提码率)
I Composer: 高码率段 3000kbps
I StitchTest: RESULT ok=true ... msg=3076x2160 · 3151 帧 · OMX.hisi.video.encoder.hevc
ffprobe: duration=105.032278 size=19619428 bit_rate=1494354
```

**原因判断**：`Composer.chooseEncoder()` 里码率有 1 Mbps 硬下限（源码 `Composer.java:279`：`int b = (int) Math.max(clamped, 1_000_000);`），且末尾 15 s 固定提码率到 3 倍（`:281`）。满片 105 s 时理论最小体积 ≈ (90 s×1 Mbps + 15 s×3 Mbps)/8 ≈ 16.9 MB，编码器实际又高出约 16%，得到 19.6 MB。也就是说 **8 MB 目标在当前实现下物理上不可能达到**，不是偶发。

**影响**：UI 宣称“可指定目标文件大小”，但低目标时偏差 2.45 倍；该码率下非末尾段画质也明显偏低（约 0.0075 bit/pixel，3076×2160@30 下）。文件本身可解码、布局正常、末尾车牌仍可辨，不影响取证可读性。

**建议**：低目标时按目标体积降低输出分辨率/帧率，或允许码率低于 1 Mbps；至少在弹窗/README 标注“最小约 17–20 MB”，避免承诺无法兑现。

### P2-2　进程被杀后残留不可见的半成品文件，候选包的启动清理未覆盖　【T08 FAIL】

**复现步骤**

```bash
adb shell am force-stop com.dashcam.stitch
adb shell am start -n com.dashcam.stitch/.MainActivity \
  --ez autostart true --ei duration 0 --ei sizeMB 70
sleep 20
adb shell am force-stop com.dashcam.stitch
adb shell ls -la /sdcard/Movies/DashcamStitch/STITCH_*.mp4
adb shell am start -n com.dashcam.stitch/.MainActivity \
  --ez autostart true --ei duration 10 --ei sizeMB 70   # 重启后再拼一次
```

**关键日志/数据**

```
被杀后残留文件: /sdcard/Movies/DashcamStitch/STITCH_20261124_015000.mp4
被杀后 pending 行: none
-rw-rw---- 1 root sdcard_rw 12813176 ... STITCH_20261124_015000.mp4
content query --where "_display_name='STITCH_20261124_015000.mp4'"
  → No result found.
ffprobe kill_orphan.mp4 → "moov atom not found"（文件不可播放）
重启后 10 s 拼接：RESULT ok=true，产物有效
重启后该文件仍在：after-restart-leftover=/sdcard/.../STITCH_20261124_015000.mp4
```

证据副本：`test/artifacts/kill_orphan.mp4`（12,813,176 B）、`kill_orphan_evidence.txt`、`kill_logcat.txt`。

**原因判断**：进程被杀时 MediaStore 的 pending 行已经消失（查询无记录），但 12.8 MB 半成品文件留在 `Movies/DashcamStitch/`。候选包新增的 `MainActivity.cleanupStalePending()` 只按 `RELATIVE_PATH LIKE 'Movies/DashcamStitch%' AND IS_PENDING=1` 删行，**没有行可删，自然也不会删文件**。所以该修复对本例无效。文件没有 `moov`，播放器/相册都打不开；因为不在 MediaStore，相册里也看不到，用户只会发现存储空间少了。

**影响**：每被杀一次泄漏约 12–13 MB；文件管理器里能看到一个打不开的“视频”。不崩溃、不影响下一次拼接，列表也正常。

**建议**：拼接期间写到应用私有临时目录（`cacheDir`），成功后再 `MediaStore` 发布；或启动时扫描 `Movies/DashcamStitch/STITCH_*.mp4`，删除“MediaStore 无记录且 mtime 超过 N 分钟”的孤儿文件（保留最新成功产物）。

### P3-1　极短时长下目标体积不可达，且无提示

临时验证（5 s / 70 MB）：

```
I Composer: 编码 3076x2160 80000kbps
文件 50,957,332 B（约 51 MB，而非 70 MB）
```

4 s / 70 MB 时（dist 构建）为 41.9 MB。`chooseEncoder` 用 `Math.min(base, br.getUpper())` 静默钳到 P30 HEVC 的 80 Mbps 上限，UI 不会告诉用户“这个时长下 70 MB 达不到”。不属于本次矩阵用例（10 s 时 58 MB、达标 83%），但建议在参数校验里提示。

### P3-2　取消收尾有数秒“正在取消…”且进度条不回零（观察项）

BACK 后约 5–8 s 才出现 `RESULT ok=false ... msg=已取消`；期间进度条停在原百分比、按钮暂时禁用。之后显示“失败 / 已取消”，返回按钮恢复可点，无残留。属于体验细节，不阻塞。

### 已修复/已验证项（对照 01:02 的 dist 构建）

| 项 | dist（86250d6） | 候选（f4e64c59） |
|---|---|---|
| 取消后“返回”按钮 | **永久 `enabled=false`**，点按无反应，只能系统返回（P1） | `enabled=true`，点按/系统返回均正常；T07 PASS |
| 满片帧数/时长 | 3151 帧 / 105.032 s | 3151 帧 / 105.032 s；`duration=999` 由 3147 帧/104.899 s 改善为 3150 帧/104.999 s，CFR 无缺口 |
| 连续运行 | 两次可行 | **同进程连跑 3 次满片**（PID 32452×3），3 份产物全部有效，0 条 app 层 codec 错误 |
| 进程被杀残留 | 12.8 MB 孤儿文件 | 仍残留 12.8 MB（见 P2-2，修复未覆盖该形态） |
| 极小体积 8 MB | 19.59 MB | 19.62 MB（未改，见 P2-1） |
| 参数弹窗 | 黑字/深灰底不可读 | 浅色字/深灰底，三标签清晰可读（`test/dialog_labels.png`） |
| 连续 `am start` autostart | 依赖 CLEAR_TASK | 每次均 `GROUPS n=1` 并正常触发；另外实测了单实例 `onNewIntent`：第二次 `am start` 不杀进程、同 PID 4475，重新打印 GROUPS/AUTOSTART 并 `RESULT ok=true`（`artifacts/onnewintent_logcat.txt`）；脚本仍用 CLEAR_TASK 保证确定性 |

## 4. 失败路径验证

- **取消路径（已实测）**：`RESULT ok=false uri=... msg=已取消`，随后无新文件、无 `is_pending=1` 行、返回按钮可点，再拼 10 s 成功。这验证了“取消不当成功 + 删除 pending 文件”这条路径。
- **无效素材路径（无法执行）**：尝试直接启动未导出的 `StitchActivity` 并传入无效 media URI，被系统拒绝：

  ```
  SecurityException: Permission Denial: starting Intent ... com.dashcam.stitch/.StitchActivity ...
  from null (pid=..., uid=2000) not exported from uid ...
  ```

  在零售机上无法在不改源码、不制造满盘/只读环境的前提下构造编码器/封装器真实失败，因此 `mEncoderError / mMuxerStarted / mSamplesWritten / watchdog` 只做了代码审查，未实测触发。如果要覆盖，需要 debug 包或可注入故障的测试入口。

## 5. 交付结论

**核心拼接能力可以交付；当前候选包 f4e64c59 不建议直接对外交付**，因为有 1 个功能性问题（P2-1）和 1 个残留清理问题（P2-2）：

- 10/12 用例 PASS：基准、30 s、10 s、999 s、200 MB、取消、连续两次、息屏、连续三次、弹窗可读性全部通过；输出稳定为 3076×2160 / 30 fps CFR / 无音轨，布局正确，车牌 沪M·A8678 可辨，取消不再残留、返回按钮已修复，连续三次满片无 codec 泄漏。
- **P2-1（8 MB → 19.6 MB）是产品承诺层面的问题**：只要“自定义目标大小”是卖点，就应修复或在 UI 明示最小可达体积。
- **P2-2（force-stop 残留 12.8 MB 孤儿文件）**：候选包声称的启动清理只删 `IS_PENDING=1` 行，实测被杀后行已不存在，文件仍在；建议改用临时目录发布，或启动时扫描孤儿文件。

修复建议顺序：P2-1 → P2-2 → P3-1。修完后用下面命令回归（约 6 分钟）：

```bash
cd /home/wuweijie/dsh/default/DashcamStitch/test
./run_tests.sh small8 kill triple
```

若产品明确接受“最小约 17–20 MB、进程被杀可能残留一个不可见半成品文件”，则当前包可交付，但应写进 release notes。

## 6. 复跑方式与产物

```bash
cd /home/wuweijie/dsh/default/DashcamStitch/test
./run_tests.sh                    # 全部 12 条（含 A/B），结束后只保留最新一个 STITCH 文件
./run_tests.sh full70 cancel      # 只跑指定用例
./run_tests.sh cleanup            # 只清理设备：STITCH 只留最新，DVR_* 不动
```

依赖：`adb`（设备已授权）、`ffmpeg`/`ffprobe`、`jq`。脚本只读源码，所有产物在 `test/artifacts/`。测试结束设备状态：`/sdcard/Movies/DashcamStitch/` 下 4 个 `DVR_*` 原样保留，`STITCH_*` 仅保留最新 `STITCH_20261124_015834.mp4`（63.46 MB，105 s / 3151 帧，MediaStore 仅 1 行），`svc power stayon false`。
