# WWJ 行车工具（icu.wwj.drive）

把一个旧手机改造成**车载工具集**：只有一个 APK，打开是工具首页，每个工具是一个独立功能。
目前包含行车记录仪、四视角拼接、视频压缩三个工具，以及记录仪内置的曝光控制与道路名显示。

- 应用包名：`icu.wwj.drive`（个人域名 `wwj.icu` 倒写）
- 目标设备：HUAWEI P30（ELE-AL00，Android 10 / API 29 / EMUI），兼容 Android 10+
- 当前版本：1.4.0；文档索引见 [docs/README.md](docs/README.md)

---

## 功能

| 工具 | 说明 |
|---|---|
| **行车记录仪** | 循环录像（段间无空洞）、时间/GPS/速度/道路名水印、HEVC 硬编、1080P 起步、息屏后台录制、存储配额 FIFO 覆盖、看门狗自愈、相册式回看 |
| **四视角拼接** | 同一时刻 MAIN/LEFT/BACK/RIGHT 四路素材拼成一张画面，几何不裁剪不拉伸，按目标体积分配码率，末尾 15 秒提码率保车牌 |
| **视频压缩** | 保持分辨率/帧率/编码/音轨/旋转等元数据，按目标体积或比例降码率，输出到相册 |
| **曝光控制**（记录仪） | 全场景自适应 EV（默认，高光保护）、车牌优先闭环（实验），见 [设计文档](docs/exposure/design.md) |
| **道路名**（记录仪） | 水印/状态显示「高架/高速名 / 地面道路名」双候选，不猜当前所在层 |

三个工具共享同一个 APK 和同一个 Application；首页只负责列出工具。新增功能不需要改首页代码，
约定与步骤见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

---

## 快速开始

### 构建

环境：Android SDK（build-tools + platform）、JDK 11+（javac 用 `-source 8`，d8 需要 JDK 运行）。
免 Gradle，`build.sh` 会自动挑最新的 build-tools 和 platform：

```bash
export ANDROID_HOME=$HOME/Android/Sdk
J=$(readlink -f ~/.sdkman/candidates/java/current)
JAVAC_BIN=$J/bin/javac JAVA_BIN=$J/bin/java ./build.sh    # 产物：out/drive.apk
```

构建会把**整个工程**打进 APK 的 `assets/drive-source.zip`，任何一份 APK 都能解出源码重建：
`unzip -p out/drive.apk assets/drive-source.zip > /tmp/src.zip && unzip -q /tmp/src.zip -d /tmp/drive && cd /tmp/drive && ./build.sh`。

### 安装与授权

运行时权限必须授予，否则拼接扫不到相册、记录仪会回退到应用私有目录：

```bash
adb install -r out/drive.apk
adb shell pm grant icu.wwj.drive android.permission.CAMERA
adb shell pm grant icu.wwj.drive android.permission.ACCESS_FINE_LOCATION
adb shell pm grant icu.wwj.drive android.permission.READ_EXTERNAL_STORAGE    # 相册读取（拼接）
adb shell pm grant icu.wwj.drive android.permission.WRITE_EXTERNAL_STORAGE   # 仅 API ≤32 生效
```

> 部分 ROM（如 Android 16 上的 ColorOS）禁止 `adb shell pm grant`，会报
> `Neither user 2000 nor current process has GRANT_RUNTIME_PERMISSIONS`；在应用内允许或到系统设置授权即可。

### 自动化入口

给真机回归脚本留的稳定入口：extras 只对本次会话生效，不写盘。

```bash
# 首页
adb shell am start -n icu.wwj.drive/.MainActivity

# 记录仪：起录（可带单次参数覆盖）与停止
adb shell am start-foreground-service -n icu.wwj.drive/.dashcam.RecordService \
  -a icu.wwj.drive.dashcam.action.START \
  --ei width 1920 --ei height 1080 --ei bitrateMbps 20 --ei segmentSecs 60 --ei quotaMb 4096 \
  --ez watermark true
adb shell am start-foreground-service -n icu.wwj.drive/.dashcam.RecordService \
  -a icu.wwj.drive.dashcam.action.STOP

# 拼接：打开列表；或自动挑第一组素材出片
adb shell am start -n icu.wwj.drive/.stitch.StitchHomeActivity --ez autostart true \
  --ei duration 105 --ei sizeMB 70

# 压缩：按文件名关键字找视频，压到 40MB 或原体积的 30%
adb shell am start -n icu.wwj.drive/.compress.CompressHomeActivity \
  --ez autostart true --es name DVR_MAIN --ei targetMB 40
adb shell am start -n icu.wwj.drive/.compress.CompressHomeActivity \
  --ez autostart true --es name DVR_MAIN --ei ratioPct 30
```

拼接的真机回归套件在 [`test/`](test/)，依赖 `adb / ffmpeg / ffprobe / jq`：
`./test/run_tests.sh` 跑全部用例（可跟用例名只跑指定项，`--no-cleanup` 保留设备产物）。
记录仪与压缩没有独立脚本，用上面的 `am` 接口驱动；压缩的验收方法见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

---

## 记录仪设置（用户语义）

| 设置组 | 含义 |
|---|---|
| 画质 | 分辨率（1080P 推荐、2K 宽屏、2.7K、720P；4K 本机不稳定）、帧率 30/25/24、码率、锐化 |
| 分段与配额 | 单段时长（秒）、存储总配额；配额满了按 FIFO 覆盖最旧的录像，始终保留最近一段 |
| 镜头 | 主摄/超广角/长焦、1.0–3.0× 变焦、自动选镜头、对焦（连续 AF / 锁定无穷远） |
| 曝光 | 曝光模式 0–3、固定 EV、ISO 上限、高光优先；可显示车牌像素估算与所需变焦 |
| 水印与显示 | 时间/GPS/速度、地点名称（联网）、快门+ISO 辅助行、自动方向、驾驶模式（黑屏常亮/允许锁屏） |
| 路测 | 记录曝光遥测 CSV、自动 EV 包围（详见 [验证协议](docs/exposure/validation.md)） |

具体选项以应用内为准；`am` 接口可单次覆盖分辨率、码率、分段、配额、水印、曝光模式等。

---

## 曝光控制（v1.2.0 起）

固定 EV 无法同时照顾夜间和白天，这一点有实测证据：

- 夜间固定 `1/50 ISO 115`（v1.2.0 前的固定曝光）会把动态范围压扁：全帧平均亮度中位 30.9（旧 DVR 自动 AE 同场景 67.5），
  高光 p99 却基本相同（185 vs 177）；尾灯红光浸染会把可读样本的字符-底色差从 59 打到 19。
- 白天用夜间参数约过曝 7.3 档；反之压曝光会让夜间车牌掉到可读下限附近。
- 分辨率比曝光更决定可读性：1080p 在 10 m 处 44 cm 车牌约 63 px，可读门槛约 ≥88 px（建议 ≥120 px）。
- 手动曝光下 50 Hz 防闪烁不生效，夜间只能用 10/20/30 ms（1/100、1/50、1/30）等安全档。

取证见 [forensics.md](docs/exposure/forensics.md)，技术调研见 [research.md](docs/exposure/research.md)，方案与取舍见 [design.md](docs/exposure/design.md)。

设置 →「曝光模式」有四种：

| 模式 | 行为 | 说明 |
|---|---|---|
| 0 自动（系统 AE + 固定 EV） | 旧行为 | 兼容/对照 |
| 1 快门上限 + 自动曝光/ISO | 自建 ISO 闭环 | 手动控光 |
| 2 **全场景自适应（高光保护）** | 系统 AE + 曝光归一化场景亮度 + 高光裁剪反馈；用太阳高度角防止夜间误判成白天，3 秒滞回防抖；在 0 / -1 / -1.5 EV 间调整 | **默认** |
| 3 车牌优先（实验） | 自建测光闭环：优先保护车牌高光、兼顾运动模糊，车灯/招牌场景可能误判 | 实验，默认不用 |

关键行为与限制：

- 设置页实时显示「当前分辨率/变焦下 10 m/20 m 处车牌像素」；不足 88 px 时提示需要的变焦倍数。
- 水印的「快门 + ISO」取相机回读的实际曝光值刷新，非标准档按实际倒数显示（如 `1/58`）。
- 路测可开「记录曝光遥测 CSV」与「自动 EV 包围」（每 5 秒 +0.5→0→-0.5→-1→-1.5→-2 EV）。
  CSV 在 `Android/data/icu.wwj.drive/files/ae_*.csv`，分析/抽帧工具见 [tools/exposure/](tools/exposure/)；
  实验室与路测协议见 [validation.md](docs/exposure/validation.md)。
- 夜间 50 Hz 约束下快门最快到 1/100，横穿/对向车牌的拖影无法完全消除；HDR 在 P30 第三方不可用。
- 「车牌优先」是实验模式，默认使用自适应 EV；隧道素材尚未实测。

---

## 道路名显示（v1.4.0）

水印/状态里的道路名来自平台 `Geocoder` 反向地理编码。高架与地面在平面图上重叠时，单凭坐标无法判断当前层，
因此**不猜层**：

- 只解析匹配到的第 1 条地址自身的 `featureName` / 地址行，提取高架/高速名（如 `内环高架路`、`中环路`、`G2京沪高速`）；
- 同时取 `thoroughfare` 作为地面道路名，两者都有时显示双候选：`内环高架路 / 中山南二路`；
- 只有一个名字时显示单个（如普通地面道路 `上海市嘉定区安拓路`）。

**限制**：地点名称需要联网；北横通道的部分路段平台 Geocoder 不返回高架名（候选全是地面路/POI），
此时只能显示地面道路名。要补全需要第二数据源（高德/百度 key 或离线道路库），当前按方案 A 不引入。

---

## 已知限制

- **曝光**：夜间 50 Hz 限制手动快门最快 1/100，横穿/对向车牌拖影无法完全消除（HDR 在 P30 第三方不可用）；
  "车牌优先"是实验模式，默认是自适应 EV。隧道素材尚未实测。
- **视频压缩只支持 AAC 音轨直通**，AC-3/Opus 等会明确报错；10bit/HDR 直接拒绝。
- **视频压缩的 creation_time 会变成压缩时间**（MediaMuxer 不支持写），其余可迁移元数据
  （旋转/位置/色彩/相册日期）都会保留。
- **目标体积受硬件编码器码率下限约束**：目标极小时（bpp 低于约 0.008）重试两次后仍可能略超，界面会显示实际体积。
- **Android 13+ 的记录仪落盘**：`WRITE_EXTERNAL_STORAGE` 从 API 33 起失效，`Movies/Dashcam` 不可直接写，
  录像会回退到应用私有目录（应用内「录像文件」照常播放/分享，但系统相册看不到）。要进相册需要改用 MediaStore 写入，属后续适配。
- **minSdk 29**：拼接依赖 MediaStore `RELATIVE_PATH`（API 29）。记录仪本身原支持 Android 8.0+，如果哪天不要拼接，可把 minSdk 降回 26。
- **targetSdk 29**：沿用旧工程，仍用 `requestLegacyExternalStorage`。要上 Android 11+ 得改前台服务类型
  （`foregroundServiceType="camera"`）和分区存储适配。
- **拼接**需要 arm64 + 硬件 HEVC 编解码；1:45 素材约 75–90 秒出片；理想画布 3840×2696 受编码器高度上限（2160）限制会自动缩到 3076×2160。
- **记录仪 4K**：2026-11-27 路测抽检为 3840×2160 HEVC 连续分段；早期某些组合仍可能 `ERROR_CAMERA_DEVICE(4)`，首次使用建议先短测，长期记录仍推荐 1080p 省空间。
- 记录仪界面横屏（sensorLandscape），首页、拼接、压缩竖屏（portrait）；拼接输出无音轨（素材本身静音），需要音轨需在合成链路加一路 track。

---

## 版本

- `1.0.0`（versionCode 1）—— 记录仪 v2.23 + 拼接 v1.0 的首次整合。
- `1.0.1`（versionCode 2）—— 修复存储权限声明（去掉 `maxSdkVersion=29`），Android 11–16 上相册/存储权限能正常申请、授权、读取；拼接页补「去设置 / 重试 / 返回自动重扫」。
- `1.1.0`（versionCode 3）—— 新增「视频压缩」：MediaCodec 硬转码，保持分辨率/帧率/编码/音轨/元数据，
  按目标体积或比例降码率。
- `1.2.0`（versionCode 4）—— 曝光控制重做：取证 + 调研 + 自适应 EV（默认，高光保护）+ 车牌优先实验模式；
  旧安装的"自动 + 固定 EV"迁移到自适应 EV。文档在 `docs/exposure/`。
- `1.2.1`–`1.2.3`（versionCode 5–7）—— 车牌像素估算（10 m/20 m）；离线验证工具入库；修复锁定焦距后
  水印快门/ISO 冻结；水印与状态栏 ISO 改为实际曝光值并跟随刷新。
- `1.3.0`（versionCode 8）—— 路测数据采集：逐秒 AE 遥测 CSV + 自动 EV 包围；新增 CSV 分析、包围抽帧、
  可打印车牌靶标和实验室/路测验证协议。
- `1.4.0`（versionCode 9）—— 道路名双候选（方案 A）：显示"高架/高速名 / 地面道路名"；不做层判断、
  不引入在线/离线补充数据。
- `1.4.1`（versionCode 10）—— 自适应 EV 场景判定加太阳高度角闸门与 3 秒滞回，修复夜间被路灯误判为
  白天（EV0 + 大面积裁剪）以及场景 2↔3 频繁切换导致的 EV 抖动；日志增加太阳高度角。

> 本项目由记录仪 `com.dashcam.app` v2.23 与拼接 `com.dashcam.stitch` v1.0 合并重命名而来，业务代码基本原样搬运。
> 包名变了 = 系统眼里是另一个 App：旧的两个 App 可并存/卸载，SharedPreferences 不迁移，录像在 `Movies/Dashcam`
> 不受影响；新旧 APK 签名相同但包名不同，`adb install -r` 不能互相覆盖。

---

## 验证记录

2026-11-24，HUAWEI P30（ELE-AL00 / Android 10 / API 29）真机，整合后首次构建的 `out/drive.apk`：

| 项 | 结果 |
|---|---|
| 干净构建 | 通过；签名校验通过；APK 内 `assets/drive-source.zip` 可解出源码独立重建 |
| 包名/组件 | `package=icu.wwj.drive`，minSdk/target=29，唯一 launcher，组件齐全；源码中 `com.dashcam` 零命中 |
| 拼接端到端 | 4 视角素材 105s → 3076×2160 / 901 帧 / 36.3 MB / HEVC，用时 22s，无崩溃 |
| 记录仪 | `action.START` 起前台服务 → 产出 17.8 MB 分段 → `action.STOP` 正常停止；回看页可见录像 |
| 崩溃 | logcat `FATAL EXCEPTION` / `E AndroidRuntime` 均为 0 |
| 相册权限修复（v1.0.1） | Android 11 / 14 / 16 模拟器 + Android 16 真机复验：授权后拼接扫到素材并成组；拒绝→去设置→返回自动重扫可用 |
| 曝光取证（v1.2.0） | 18 个 4K 车牌 ROI、6 类失败模式；可读阈值宽≥88 px、字符-底色差≥36、对比度≥45；运动模糊快门/相对速度表 |
| 自适应 EV（v1.2.0） | P30 夜间实测 `scene=NIGHT EV=-1.5` 稳定无振荡；离线仿真 day/dusk/night 均收敛 |
| 路测（v1.4.0，2026-11-27） | 08:04–09:16 / 18:34–19:50 两段；抽检 3840×2160 HEVC。白天/黄昏近距车牌可读（`苏A·H8K23`、`沪A·ET9114`），夜间被尾灯照亮的 `沪D·577V8` 可读。发现场景 2↔3 频繁切换、傍晚路灯误判 DAY；v1.4.1 加太阳闸门 + 3s 滞回，CSV 回放切换 232→25 次 |
| 车牌优先实验（v1.2.0） | 设备暗场稳定 10ms/ISO6400；离线仿真收敛；真实道路矩阵（横穿/对向/隧道）**尚未验证** |
| 视频压缩（v1.1.0） | P30 与 OnePlus 真机 + 独立复验 7/7 通过：4K HEVC 15 MB 目标 → 14.93 MB（无 B 帧/无 elst）；1080p H.264 8 MB 目标 → 7.40 MB（AAC 直通，首轮超 0.46% 自动重试）；旋转矩阵与 VFR 保留；取消与 force-stop 无残留 |

---

## 项目结构

```
AndroidManifest.xml  统一清单（全部组件、权限）    src/    首页壳 + dashcam / stitch / compress
build.sh             免 Gradle 构建               res/    布局、图标、文案
signing/debug.jks    调试签名（口令 android）      test/   拼接真机回归套件
assets/              构建时生成的源码包            tools/exposure/  曝光验证/分析工具
docs/                架构与曝光设计/取证/验证文档，索引见 docs/README.md
```
