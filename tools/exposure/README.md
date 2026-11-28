# 曝光离线验证 / 路测采集工具

没有实车素材时验证曝光控制逻辑；路测时一次采集足够多的数据。
各工具的完整用法见下，实验室/路测协议见 [docs/exposure/validation.md](../../docs/exposure/validation.md)。

## 依赖

JDK（默认 `~/.sdkman/candidates/java/current`，可用 `JAVA_HOME` 覆盖）、ffmpeg、Python 3；
`extract_bracket.py` / `make_targets.py` 需要 Pillow。

## 合成场景闭环仿真

```bash
./run_sim.sh
```

输出夜间/白天/黄昏每帧的 p90、曝光、ISO、EV，检查收敛/振荡/边界。

## 真实素材回放

```bash
./run_clip.sh ../../../某段行车.mp4 1 180
```

抽 960×540 原始 YUV 喂控制器，打印 `scene / blocks / p90 / clip / roadP90`。

## AE CSV 分析

App 设置里打开「记录曝光遥测 CSV」，文件在 `Android/data/icu.wwj.drive/files/ae_*.csv`。

```bash
python3 analyze_ae.py ae_20261126_224403.csv
```

按 `mode/scene/EV` 汇总实际曝光、ISO、ROI 分位/裁剪率，用来判断自适应 EV 选得对不对。

## EV 包围抽帧

路测时打开「自动 EV 包围」（每 5 秒 +0.5→0→-0.5→-1→-1.5→-2 EV）：

```bash
python3 extract_bracket.py ae_YYYYmmdd_HHMMSS.csv DASHCAM_*.mp4
```

每档 EV 抽一帧，带 EV/ISO/裁剪率标注，并生成 `bracket_sheet.jpg` 接触表。

## 车牌靶标

```bash
./make_targets.py
```

生成 `targets/plate_targets_A4.pdf`（蓝/黄/绿/白四块，A4 横向，打印宽 260mm）与单张 PNG。
打印后量实际宽度：等效真实距离 = 实测距离 × 440 / 打印宽度。也可直接全屏显示在显示器上做亮度扫描。
