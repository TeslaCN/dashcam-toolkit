# 曝光验证与数据采集（少路测版）

> 目的：实际路测机会少，所以把验证拆成**离线 → 实验室 → 一次路测**三层，让一次上路收集到足够多的数据。
> 相关：[design.md](design.md)、[forensics.md](forensics.md)、[research.md](research.md)、[tools/exposure/](../../tools/exposure/)

---

## 0. 三层协议

1. **离线**（现在就能做）：现有素材回放 + 合成闭环仿真，验证控制逻辑/收敛/边界；
2. **实验室**（10–30 分钟）：打印车牌靶标 / 显示器显示，配合 EV 包围，验证真实 ISP/镜头/AE 的响应；
3. **一次路测**（20–30 分钟）：开启 CSV 遥测 + 自动 EV 包围，跑一条覆盖夜/日/隧道/车灯/横穿的路线，之后离线分析。

验收用组合判据（宽 ≥88px、字符-底色差 ≥36、对比度 ≥45、过曝 ≤10%/25%），不是"整帧亮度"。

> 2026-11-27 已执行早/晚两段路测（4K，白天/黄昏/夜间），并据此修复了场景 2↔3 抖动；
> 下次重点：隧道、横穿/对向、车灯直射。

---

## 1. 离线层（无设备）

- `tools/exposure/run_sim.sh`：合成场景闭环仿真（夜间/白天/黄昏收敛、振荡、50Hz 快门、过曝保护）；
- `tools/exposure/run_clip.sh <video>`：真实视频抽 960×540 帧回放，看场景分类/代理块/统计；
- `tools/exposure/analyze_ae.py <csv>`：按场景/EV 汇总实际曝光、ISO、ROI 均值/分位、裁剪率；
- `tools/exposure/extract_bracket.py <csv> <video>`：每档 EV 抽一帧，生成带 EV/ISO/裁剪标注的接触表。

局限：回放素材已经"曝光过"，不能模拟传感器满阱饱和，只用于逻辑/趋势验证，不替代真机。

---

## 2. 实验室层（真实 ISP，不占路测）

### 2.1 靶标

`tools/exposure/targets/plate_targets_A4.pdf`：A4 横向，蓝/黄/绿/白四块车牌，打印宽度 260mm
（约标准 44cm 车牌的 0.59 倍）。打印后量实际宽度 W，等效真实距离 = 实测距离 × (440 / W)。
没有打印机可把 `page1.png/page2.png` 全屏显示在显示器上做亮度扫描；屏幕自发光，只能验证曝光逻辑/对比度。

### 2.2 测试矩阵（建议 30 分钟内完成）

| 变量 | 取值 |
|---|---|
| 距离 | 3m / 6m / 10m（对应真实 5m / 10m / 17m） |
| 角度 | 0° / 15° / 30° |
| 照明 | 关灯 / 室内灯 / 手电或车灯直射靶标 |
| 曝光 | 默认自适应 EV；再开一次 EV 包围 |
| 分辨率/变焦 | 1080p 1×；1080p 2×；有长焦则长焦 |

每组录 10–15 秒即可；固定焦距，水印 + CSV 打开。

### 2.3 记录命令

```bash
adb shell am start-foreground-service -n icu.wwj.drive/.dashcam.RecordService \
  -a icu.wwj.drive.dashcam.action.START \
  --ei width 1920 --ei height 1080 --ei exposureMode 2 \
  --ei focusMode 1 --ei zoomIndex 0 \
  --ez watermark true --ez wmExposure true --ez aeLog true
# 停止
adb shell am start-foreground-service -n icu.wwj.drive/.dashcam.RecordService \
  -a icu.wwj.drive.dashcam.action.STOP
```

CSV 在 `Android/data/icu.wwj.drive/files/ae_*.csv`：
`adb shell ls -t /sdcard/Android/data/icu.wwj.drive/files/ae_*.csv | head -1`，然后 `adb pull <path>`。

---

## 3. 路测层（一次拿够数据）

开录前：打开「记录曝光遥测 CSV」和「路测：自动 EV 包围」；分辨率 1080p（或确认 4K 稳定），水印辅助信息打开；
固定焦距，记录起点（时间、天气、路线）。

### 3.2 路线（尽量一条路覆盖）

1. 白天/晴天：车牌应亮，检验 EV 是否回到 0；
2. 桥下/隧道进出：检验场景过渡；
3. 夜间路灯跟车：检验 -1.5 是否保护车牌；
4. 夜间对向/车灯直射：检验裁剪反馈是否压到 -2；
5. 横穿/被超车：检验运动模糊，不指望夜间能冻结；停车近距离拍前车车牌作为"可读"正样本。

### 3.3 自动 EV 包围

开启后每 5 秒循环 `+0.5 / 0 / -0.5 / -1.0 / -1.5 / -2.0 EV`，视频水印带 `EV+0.5` 等标记、CSV 的 `ev_steps`
同步记录。同一段路有 6 档对照，离线可判断"最佳 EV 是哪档、自适应选得对不对"。

### 3.4 分析

```bash
python3 tools/exposure/analyze_ae.py ae_YYYYmmdd_HHMMSS.csv
python3 tools/exposure/extract_bracket.py ae_YYYYmmdd_HHMMSS.csv DASHCAM_*.mp4
```

`extract_bracket.py` 为每档 EV 抽一帧拼成 `bracket_sheet.jpg`，文件名带 EV/ISO/裁剪率，可直接目视车牌是否可读。
CSV 关键列：`scene`（1=白天 2=黄昏/隧道 3=夜间）、`mode`（auto/manual/adaptive/plate/bracket）、
`ev_steps`（0.5EV 单位）、`act_exp_ns`/`act_iso`（回读的实际曝光/ISO）、`roi_*`/`plate_*`（道路 ROI 与车牌代理块统计）、
`speed_kph`、GPS 坐标、`note`。

### 3.5 验收

对每档 EV 抽帧，量：车牌宽、字符-底色差、对比度、单通道过曝、运动模糊；判定可读：宽 ≥88px、
字符差 ≥36、对比度 ≥45、过曝 ≤10%（中小牌）/25%（大牌）。记录"最佳 EV"并对比自适应选择，偏差 >0.5EV 就调整场景阈值。

---

## 4. 一次路测需要带回的东西

1. `ae_*.csv` 与对应视频（至少每档 EV 的抽帧）；
2. 路线/天气/时间说明；
3. 特殊场景时间点（隧道进出、车灯直射、横穿）——有这些，后续调参不再需要反复上路。

