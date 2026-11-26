# 曝光离线验证工具

两个小工具，用来在没有实车素材时验证 `PlateAeController` 的场景分类/代理块/收敛行为。
它们直接编译 `../../src/icu/wwj/drive/dashcam/PlateAeController.java`，不需要 Android 设备。

## 1. 合成场景闭环仿真

```bash
./run_sim.sh
```

输出三组场景（夜间/白天/黄昏）每帧的 p90、曝光、ISO、EV，用来检查收敛、振荡和边界行为。
参数在 `PlateAeSim.java` 顶部（场景辐亮度、车速、目标）。

## 2. 真实素材回放

```bash
./run_clip.sh ../../../某段行车.mp4 1 180
```

脚本会用 ffmpeg 抽 960×540 原始 YUV（1fps），喂给控制器并打印：

```
帧号 scene=... amb=... sceneLuma=... blocks=... p90=... clip=... roadP90=...
```

- `blocks`：被判为"车牌代理"的高亮结构块数量；
- `p90`：代理块亮度（控制的观测量）；
- `sceneLuma`：曝光归一化场景亮度（用于昼夜分类）。

## 3. 需要 JDK

脚本默认用 `~/.sdkman/candidates/java/current`，也可 `JAVA_HOME=... ./run_sim.sh`。
