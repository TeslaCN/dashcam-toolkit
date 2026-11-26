# WWJ 行车工具（icu.wwj.drive）

把一个旧手机改成的**车载工具集**：只有一个 APK，打开是工具首页，每个工具是一个独立功能包。
目前包含三个工具：**行车记录仪**、**四视角拼接**、**视频压缩**，都是真机验证过的。

- 应用包名：`icu.wwj.drive`（个人域名 `wwj.icu` 倒写）
- 功能包名：`icu.wwj.drive.<feature>`
- 目标设备：HUAWEI P30（ELE-AL00，Android 10 / API 29，EMUI），兼容 Android 10+

---

## 1. 功能

| 工具 | 功能包 | 说明 |
|---|---|---|
| **行车记录仪** | `icu.wwj.drive.dashcam` | 循环录像（段间无空洞）、时间/GPS/速度水印烧录、HEVC 硬编、1080P/720P、息屏后台录制、存储配额 FIFO 覆盖、看门狗自愈、相册式回看 |
| **四视角拼接** | `icu.wwj.drive.stitch` | 同一时刻 MAIN/LEFT/BACK/RIGHT 四路素材拼成一张画面，几何不裁剪不拉伸，按目标体积分配码率，末尾 15 秒提码率保车牌 |
| **视频压缩** | `icu.wwj.drive.compress` | 保持分辨率/帧率/编码/音轨/旋转等元数据，按目标体积或比例降码率，输出到相册 |
| **曝光控制**（记录仪） | `icu.wwj.drive.dashcam` | 全场景自适应 EV（默认，高光保护）；车牌优先闭环（实验）。见 [docs/exposure/design.md](docs/exposure/design.md) |

三个功能共享同一个 APK、同一个 `Application`（`icu.wwj.drive.DriveApp`），首页是工具列表。
以后新增功能（胎压、油耗、停车监控、蓝牙遥控……）按第 6 节的方式挂进来，首页自动出现入口。

---

## 2. 包名与命名规范（重要）

域名 `wwj.icu` 倒写 → 根包 `icu.wwj.drive`。**所有**自造标识符都挂在这个根下，避免和第三方撞车：

| 类别 | 规则 | 实例 |
|---|---|---|
| 应用包名 / applicationId | `icu.wwj.drive` | `icu.wwj.drive` |
| 功能 Java 包 | `icu.wwj.drive.<feature>` | `icu.wwj.drive.dashcam`、`icu.wwj.drive.stitch` |
| ContentProvider authority | `icu.wwj.drive.<feature>.<用途>` | `icu.wwj.drive.dashcam.clips` |
| 广播/服务 action | `icu.wwj.drive.<feature>.action.<动作>` | `icu.wwj.drive.dashcam.action.START` |
| 通知渠道 id | `icu.wwj.drive.<feature>.<渠道>` | `icu.wwj.drive.dashcam.recording` |
| 资源名前缀 | 功能名 + `_` | `stitch_main`、`stitch_loading`、`ic_stitch` |
| 公共存储目录 | `Movies/<功能>` | `Movies/Dashcam`、`Movies/DashcamStitch` |

组件清单（`AndroidManifest.xml`）：

| 组件 | 类 |
|---|---|
| 首页（唯一 launcher） | `icu.wwj.drive.MainActivity` |
| 记录仪主界面 | `icu.wwj.drive.dashcam.DashcamActivity` |
| 记录仪回看 | `icu.wwj.drive.dashcam.ClipsActivity` |
| 记录前台服务 | `icu.wwj.drive.dashcam.RecordService` |
| 看门狗 | `icu.wwj.drive.dashcam.WatchdogReceiver` |
| 录像 Provider | `icu.wwj.drive.dashcam.ClipProvider` |
| 拼接列表 | `icu.wwj.drive.stitch.StitchHomeActivity` |
| 拼接进度 | `icu.wwj.drive.stitch.StitchActivity` |
| 压缩列表 | `icu.wwj.drive.compress.CompressHomeActivity` |
| 压缩进度 | `icu.wwj.drive.compress.CompressActivity` |

---

## 3. 架构

```
icu.wwj.drive.MainActivity（首页）
        │  只依赖 ToolRegistry，不认识任何具体功能
        ▼
   ToolRegistry ──► Tool(dashcam)  ──► dashcam.DashcamActivity ──► RecordService（前台服务）
                ├► Tool(stitch)   ──► stitch.StitchHomeActivity ──► stitch.StitchActivity ──► Composer（MediaCodec+GL）
                └► Tool(compress) ──► compress.CompressHomeActivity ──► compress.CompressActivity ──► Compressor（MediaCodec 转码）

icu.wwj.drive.DriveApp（Application）
   ├─ StorageManager.setContext()        记录仪 MediaStore 同步
   └─ ensureDashcamChannel()             记录仪通知渠道
```

- **首页零业务**：只遍历 `ToolRegistry.all()` 渲染卡片，点卡片 `startActivity` 进入对应功能。
- **功能自包含**：每个功能有自己的 Activity/Service/资源/存储目录，不做跨功能直接调用。
- **跨功能数据走相册**：记录仪把录像写入公共 `Movies/Dashcam` 并入 MediaStore；拼接按文件名时间戳从 MediaStore 聚合素材，因此两个功能零耦合，单独删除其中一个也不影响另一个。
- 记录仪相机/编码链路见 `src/icu/wwj/drive/dashcam/RecordingSession.java`；拼接合成链路见 `src/icu/wwj/drive/stitch/Composer.java`。

---

## 4. 目录

```
AndroidManifest.xml          统一清单（全部组件）
build.sh                     免 Gradle 构建：aapt2 + javac + d8 + zipalign + apksigner
README.md                    本文件
docs/ARCHITECTURE.md         扩展指南：怎么加一个新功能
signing/debug.jks            调试签名（口令 android），保证重建 APK 可覆盖安装

res/
  layout/home_main.xml       首页
  layout/home_item_tool.xml  首页工具卡片
  layout/stitch_main.xml     拼接列表（原 activity_main）
  layout/stitch_progress.xml 拼接进度（原 activity_stitch）
  layout/stitch_dialog.xml   拼接参数对话框
  layout/stitch_item.xml     拼接素材组条目
  drawable/ic_stitch.xml     拼接图标
  drawable/card_bg.xml       首页卡片底
  mipmap-*/ic_launcher.png   应用图标（记录仪沿用）
  values/strings.xml         全部文案

src/icu/wwj/drive/
  DriveApp.java               Application：跨功能全局初始化
  MainActivity.java           首页（工具列表）
  Tool.java                   一个工具入口的数据结构
  ToolRegistry.java           工具注册表 —— 新增功能只改这里一行
  dashcam/                    行车记录仪（18 个类）
    DashcamActivity.java      主界面：预览 / 开始停止 / 设置
    ClipsActivity.java        录像回看：播放 / 分享 / 删除
    RecordService.java        前台服务（START/STOP/CLIP）
    RecordingSession.java     相机 / GL / 编码器 / 写入器 编排、重连、看门狗
    GlRenderer.java           EGL 双输出渲染 + 水印
    VideoEncoder.java         HEVC/AVC 硬编
    SegmentWriter.java        MP4 关键帧轮转、时间戳重基
    StorageManager.java       目录 / 命名 / 配额 FIFO / MediaStore
    Settings.java             设置持久化
    Status.java               运行状态快照
    TimeBase.java             音视频时钟对齐
    GpsTracker.java           定位与速度
    LensInfo.java             镜头/相机能力
    EventLog.java             事件日志
    NotificationHelper.java   常驻通知
    ClipProvider.java         录像 content:// 出口
    WatchdogReceiver.java     定时拉起
  stitch/                    四视角拼接（11 个类）
    StitchHomeActivity.java   素材列表 + 参数对话框
    StitchActivity.java       进度 / 保存 / 播放 / 分享
    Composer.java             解码-合成-编码主流程
    MediaScanner.java         按文件名时间戳聚合多视角
    VideoGroup.java           一组素材
    GroupAdapter.java         列表适配器
    ThumbCache.java           缩略图缓存
    gl/CanvasRenderer.java    四路纹理按模板绘制
    gl/EglCore.java           EGL14 封装
  compress/                  视频压缩（8 个类）
    CompressHomeActivity.java  视频列表 + 压缩参数（50%/30%/自定义 MB）
    CompressActivity.java      进度 / 结果 / 播放 / 分享
    Compressor.java            MediaCodec 转码引擎（解码 Surface → 编码 Surface）
    MediaInfo.java             编码/分辨率/帧率/音轨/旋转/色彩探测
    VideoEntry.java            列表条目
    VideoListAdapter.java      列表适配器
    CompressOptions.java       目标体积 / 比例参数
    CompressResult.java        压缩结果
```

---

## 5. 构建、安装、验证

环境：Android SDK（build-tools + platform）、JDK 11+（javac 用 `-source 8`，d8 需要 JDK 运行）。
本机（Linux）实测命令：

```bash
cd ~/projects/dashcam
J=$(readlink -f ~/.sdkman/candidates/java/current)
ANDROID_HOME=$HOME/Android/Sdk JAVAC_BIN=$J/bin/javac JAVA_BIN=$J/bin/java ./build.sh
# 产物：out/drive.apk
```

`build.sh` 会自动挑最新的 build-tools 和 platform，并把**整个工程**打进 APK 的
`assets/drive-source.zip`，所以任何一份 APK 都能解出完整可构建的源码：

```bash
unzip -p out/drive.apk assets/drive-source.zip > /tmp/drive-src.zip
mkdir -p /tmp/drive && cd /tmp/drive && unzip -q /tmp/drive-src.zip && ./build.sh
```

安装与授权（运行时权限必须授予，否则拼接扫不到相册、记录仪写着写着会落私有目录）：

```bash
adb install -r out/drive.apk
adb shell pm grant icu.wwj.drive android.permission.CAMERA
adb shell pm grant icu.wwj.drive android.permission.ACCESS_FINE_LOCATION
adb shell pm grant icu.wwj.drive android.permission.READ_EXTERNAL_STORAGE    # 相册读取（拼接）
adb shell pm grant icu.wwj.drive android.permission.WRITE_EXTERNAL_STORAGE   # 仅 API ≤32 生效
```

> 部分 ROM（例如 Android 16 上的 ColorOS）禁止 `adb shell pm grant` 授予运行时权限，
> 会报 `Neither user 2000 nor current process has GRANT_RUNTIME_PERMISSIONS`；
> 这种情况在应用内点弹窗的「允许」即可，或到系统设置里授权。

自动化接口：

```bash
# 首页
adb shell am start -n icu.wwj.drive/.MainActivity

# 记录仪：直接起录（可带单次参数覆盖）与停止
adb shell am start-foreground-service -n icu.wwj.drive/.dashcam.RecordService \
  -a icu.wwj.drive.dashcam.action.START \
  --ei width 1920 --ei height 1080 --ei bitrateMbps 20 --ei segmentSecs 60 --ei quotaMb 4096 \
  --ez audio false --ez watermark true
adb shell am start-foreground-service -n icu.wwj.drive/.dashcam.RecordService \
  -a icu.wwj.drive.dashcam.action.STOP

# 拼接：打开列表；或自动挑第一组跑（自动化测试入口）
adb shell am start -n icu.wwj.drive/.stitch.StitchHomeActivity --ez autostart true --ei duration 105 --ei sizeMB 70
```

### 5.1 权限（Android 10 – 16）

存储/相册权限按 targetSdk 29 的兼容路径处理，**不要**在清单里给这些权限加 `maxSdkVersion=29`
（加了之后 Android 11+ 等于没声明，运行时申请会被系统静默拒绝——v1.0.0 的拼接读不到相册就是这个问题）：

| 权限 | 声明 | 用途 |
|---|---|---|
| `READ_EXTERNAL_STORAGE` | 全版本声明 | 拼接读相册里的视频素材；Android 13+ 系统会据此自动补上 `READ_MEDIA_IMAGES/VIDEO/AUDIO`，弹窗授权后与 `READ_EXTERNAL_STORAGE` 一并授予 |
| `WRITE_EXTERNAL_STORAGE` | `maxSdkVersion=32` | 记录仪写公共 `Movies/Dashcam`（legacy 存储）；33+ 不需要 |
| `CAMERA` / `ACCESS_FINE_LOCATION` | 全版本 | 记录仪；定位只影响 GPS 水印，可拒绝 |

行为：首次进拼接页会弹系统授权框；拒绝后应用内会给出「去设置 / 重试」，从设置返回时自动重扫。
Android 13+ 上 `READ_MEDIA_VIDEO` 单独授予对 targetSdk 29 的应用**无效**，所以统一申请
`READ_EXTERNAL_STORAGE`；以后把 targetSdk 提到 33+ 时再切到 `READ_MEDIA_VIDEO`（见 `docs/ARCHITECTURE.md`）。

### 5.2 真机回归脚本

`test/` 下是拼接功能的真机自动化套件（由 DashcamStitch 1.0 的测试脚本迁移）：

```bash
./test/run_tests.sh                 # 跑全部用例
./test/run_tests.sh full70 d30      # 只跑指定用例
./test/run_tests.sh --no-cleanup    # 跑完不清理设备产物
```

依赖 `adb / ffmpeg / ffprobe / jq`，默认包名 `icu.wwj.drive`（可用 `PKG=` 覆盖），设备上要有
`DVR_MAIN_/DVR_LEFT_/DVR_BACK_/DVR_RIGHT_` 同一时间戳的四视角素材。取证产物落在 `test/artifacts/`（已 gitignore）。
记录仪侧没有独立脚本，直接用上面 §5 的 `am start-foreground-service` 接口验证。

---

## 6. 视频压缩（v1.1.0 新增）

把相册里的视频按目标体积压小，**保持分辨率、帧率、编码家族、音轨和旋转/色彩元数据**，只降码率。

入口：首页 →「视频压缩」→ 选视频 → 选「原片 50% / 原片 30% / 自定义 MB」→ 开始。
输出在相册 `Movies/Compressed/<原名>_compressed.mp4`（同名冲突时自动加 `_yyyyMMdd_HHmmss`）。

### 6.1 手机端和电脑端 ffmpeg 的差别

| 项 | 电脑（ffmpeg） | 手机（本项目） |
|---|---|---|
| 编码器 | x264/x265 软件编码 | MediaCodec 硬件编解码，解码输出 Surface 直连编码器 InputSurface（零拷贝） |
| 两遍编码 | 支持，体积可预测 | 硬件编码器不支持两遍 → **单遍 VBR + 体积反馈重试**（最多 2 次） |
| 音频 | `-c:a copy` | MediaExtractor 原样 copy AAC 样本 |
| 码流兼容性 | 要手动关 B 帧、修 elst、打 hvc1 | 硬件编码器默认无 B 帧，MediaMuxer 不写 edit list → 天然规避「电脑流畅手机卡」的根因 |
| 元数据 | `-movflags`、`-tag:v`、色彩标记手动打 | MediaMuxer 保留旋转/位置/色彩；**creation_time 无法保留**（相册里用 DATE_TAKEN 近似） |

> 「电脑流畅、手机卡」的根因是 x264/x265 默认 B 帧 → 首包负 DTS → ffmpeg 写 edit list 偏移，
> 部分手机播放器处理不了。手机端硬编码器一般只输出 P 帧，MediaMuxer 也不写 `elst`，
> 所以这个坑在手机方案里天然不存在；这一点靠 ffprobe 校验 `has_b_frames=0`、首包 DTS≥0 来兜底。

### 6.2 体积预算

```text
视频码率 = (目标字节 × 8 × 0.92) / 时长秒 − 音频码率
bpp      = 视频码率 / (宽 × 高 × 帧率)
```

- 目标按**十进制 MB**（40MB = 40,000,000 字节）；0.92 是编码器超采样余量。
- bpp < 0.012（H.264）/ < 0.008（HEVC）时画质会明显变软，应用照压但建议改用「原片 50%」。
- 源片 bpp 本来就低于 0.05 时收益有限，可能只是再损失一次。

### 6.3 能力与限制

| 项 | 状态 |
|---|---|
| 保持分辨率 | ✅ 不缩放 |
| 保持帧率/帧数 | ✅ 时间戳透传（VFR 也保持），帧数与源相差 ≤1 |
| 保持编码家族 | ✅ HEVC→HEVC、H.264→H.264（profile 由硬件编码器决定） |
| 音轨 | ✅ AAC 直通；其它编码明确报错（不静默丢音轨） |
| 旋转 / 位置 / 色彩 | ✅ `setOrientationHint` / `setLocation` / 编码格式色彩字段 |
| creation_time | ❌ MediaMuxer 不支持写入，相册用 DATE_TAKEN 近似 |
| 10bit / HDR | ❌ 硬件编码器不支持，检测到直接报错，不做有损降位深 |
| 速度 | 硬件编解码，4K 通常快于实时（视机型） |
| 屏幕/锁屏 | 转码页不常亮，锁屏也能继续（partial wakelock）；进度页回来可见 |

### 6.4 中断、取消与清理

| 场景 | 行为 |
|---|---|
| 点「取消」 | 引擎轮询取消标志，走 finally 删除 pending 行与半成品，界面退出 |
| 转码中进程被杀/系统回收 | 转码开始前会把输出 item URI 写进 SharedPreferences；**下次冷启动**时按该 URI 精确删除残留行+文件，再按命名规则兜底删孤儿文件，最后对 `Movies/Compressed` 做一次 MediaScanner 扫描修剪旧记录 |
| 已发布输出（IS_PENDING=0） | 冷启动清理**不会**碰，正常成片始终保留 |
| 列表 | 不显示本功能的产物（文件名包含 `_compressed` 的跳过），避免把压缩结果当素材再压 |
| 屏幕 | **不强制常亮（防 OLED 烧屏）**：屏幕可自动熄灭/锁屏，转码期间只持有 CPU partial wakelock，完成后释放；拼接页同样处理 |

### 6.5 自动化接口

```bash
# 按文件名关键字找视频，压到 40MB
adb shell am start -n icu.wwj.drive/.compress.CompressHomeActivity \
  --ez autostart true --es name DVR_MAIN --ei targetMB 40

# 或压到原体积 30%
adb shell am start -n icu.wwj.drive/.compress.CompressHomeActivity \
  --ez autostart true --es name DVR_MAIN --ei ratioPct 30
```

---

## 7. 新增一个功能（3 步）

1. 建包 `icu.wwj.drive.<feature>`，写功能自己的 Activity/Service；组件用 `<feature>` 前缀命名
   （authority/action/通知渠道同理），资源加 `<feature>_` 前缀，产物写 `Movies/<Feature>`。
2. `AndroidManifest.xml` 里声明组件（`android:label` 指向工具名，`exported` 按需）。
3. `ToolRegistry.java` 的 static 块里 `TOOLS.add(new Tool(...))` 加一行（标题、描述、图标、入口类、排序）。

细节和检查清单见 `docs/ARCHITECTURE.md`。

---

## 8. 与旧工程的关系

本项目是把两个独立 App 合并重命名的结果，业务代码基本原样搬运，只改包名/资源名/标识符：

| 旧 | 新 |
|---|---|
| `com.dashcam.app` v2.23（设备内嵌源码） | `icu.wwj.drive.dashcam` |
| `com.dashcam.app.DashcamApp`（Application） | `icu.wwj.drive.DriveApp` |
| `com.dashcam.app.MainActivity` | `icu.wwj.drive.dashcam.DashcamActivity` |
| authority `com.dashcam.app.clips` | `icu.wwj.drive.dashcam.clips` |
| action `com.dashcam.app.action.START/STOP/CLIP` | `icu.wwj.drive.dashcam.action.*` |
| `com.dashcam.stitch` v1.0（会话 `session-e0344c77-d22f-43fb-a14f-64df4b6b77dc`） | `icu.wwj.drive.stitch` |
| `com.dashcam.stitch.MainActivity` | `icu.wwj.drive.stitch.StitchHomeActivity` |
| `layout/activity_main`、`activity_stitch`、`dialog_stitch`、`item_group` | `stitch_main`、`stitch_progress`、`stitch_dialog`、`stitch_item` |
| `drawable/ic_app` | `drawable/ic_stitch` |
| 字符串 `loading/refresh/start/cancel/done/play/share/back` | `stitch_loading` 等（加 `stitch_` 前缀） |
| `app_name` "行车记录仪" / "四视角拼接" | 统一 `app_name` = "行车工具"；两个工具名各自成资源 |

注意：

- 包名变了 = 系统眼里是**另一个 App**。旧的两个 App 可以并存，也可以 `adb uninstall com.dashcam.app` / `com.dashcam.stitch`。
- 旧 App 的设置（SharedPreferences）不会迁移；录像在公共目录 `Movies/Dashcam`，不受影响。
- 新旧 APK 签名相同（`signing/debug.jks`），但包名不同，`adb install -r` 不能互相覆盖。

---

## 9. 曝光控制（v1.2.0 新增）

固定 EV 无法同时照顾夜间和白天，这一点有实测证据（夜间 1/50 ISO115 把动态范围压扁；白天 -1.5EV 约欠曝 7 档）：
取证见 [docs/exposure/forensics.md](docs/exposure/forensics.md)，技术调研见 [docs/exposure/research.md](docs/exposure/research.md)，
完整方案见 [docs/exposure/design.md](docs/exposure/design.md)。

设置 →「曝光模式」现在有四种：

| 模式 | 行为 | 说明 |
|---|---|---|
| 0 自动（系统 AE + 固定 EV） | 旧行为 | 兼容/对照 |
| 1 快门上限 + 自动曝光/ISO | 自建 ISO 闭环 | 手动控光 |
| 2 **全场景自适应（高光保护）** | 系统 AE + 曝光归一化场景亮度 + 高光裁剪反馈，自动在 0 / -1 / -1.5EV 间调整 | **默认**，解决"夜间要 -1.5、白天要 0" |
| 3 车牌优先（实验） | 960×540 测光 + 结构高光块 + 50Hz 快门档 + 车速快门上限 + 过曝先缩曝光 | 实验，见设计文档 |

关键结论（细节与参数见设计文档）：

- **50Hz 硬约束**：手动曝光下 antibanding 不生效，夜间只能用 10/20/30ms（1/100、1/50、1/30）；1/200s 会有条纹。
- **分辨率比曝光更决定车牌可读性**：1080p 在 10m 处 44cm 车牌只有约 63px，而可读门槛约 ≥88px（建议 ≥120px）；优先 1080p + 2× 变焦或 2.1× 长焦，4K 在本机 HAL 不稳定。
- **1/50s 只适合同速跟车**（相对速度 ≤3.6km/h）；横穿/对向车牌需要 ≥1/200s，夜间受 50Hz 与无 HDR 限制无法两全。
- 夜间"尾灯红光浸染"是读不出字的主因（字符-底色差从 59 掉到 19），曝光只能部分缓解，不能根治。
- 设置页会实时显示「当前分辨率/变焦下 10m/20m 处车牌像素」：可读门槛 ≥88px、建议 ≥120px，不足时会提示需要的变焦倍数。
- 离线验证工具在 [tools/exposure/](tools/exposure/)：`run_sim.sh`（合成场景闭环仿真）、`run_clip.sh`（真实素材回放看场景/代理块统计）。
- 水印的「快门 + ISO」取自最新一帧 `CaptureResult` 的**实际值**，约 3Hz 刷新；固定焦距锁定后也会持续更新（v1.2.2 修复冻结、v1.2.3 提高刷新率）。
- 路测采集（v1.3.0）：设置里可开「记录曝光遥测 CSV」和「自动 EV 包围」（每 5 秒 `+0.5→0→-0.5→-1→-1.5→-2 EV`）。CSV 在 `Android/data/icu.wwj.drive/files/ae_*.csv`；分析/抽帧脚本见 [tools/exposure/](tools/exposure/)，实验室/路测协议见 [docs/exposure/validation.md](docs/exposure/validation.md)。非标准曝光按实际倒数显示，例如 `1/58`，不会强行凑成标准档。

调试日志：`DashcamAEV`（自适应 EV：scene/norm/p99/clip/EV）、`DashcamAE`（车牌优先实验：blocks/plateP90/曝光/ISO）。

---

## 9.1 道路名显示（v1.4.0）

水印/状态里的道路名来自平台 `Geocoder` 反向地理编码。高架与地面在平面图上重叠时，单凭坐标无法判断当前层，因此**不猜层**：

- 只解析匹配到的第 1 条地址自身的 `featureName` / 地址行，提取高架/高速名（如 `内环高架路`、`中环路`、`G2京沪高速`）；
- 同时取 `thoroughfare` 作为地面道路名；
- 两者都有时显示双候选：`内环高架路 / 中山南二路`、`中环路 / 虹梅路`、`G2京沪高速 / 虞姬墩路`；
- 只有一个名字时显示单个（如普通地面道路 `上海市嘉定区安拓路`）。

**限制**：北横通道的部分路段平台 Geocoder 不返回高架名（5 条候选全是地面路/POI），此时只能显示地面名。要补全需要第二数据源（高德/百度 key 或离线道路库）；当前按方案 A 不引入在线/离线补充。

---

## 10. 已知限制

- **曝光**：夜间 50Hz 限制手动快门最快 1/100，横穿/对向车牌拖影无法完全消除（HDR 在 P30 第三方不可用）；"车牌优先"是实验模式，默认是自适应 EV；隧道素材尚未实测。
- **视频压缩只支持 AAC 音轨直通**，AC-3/Opus 等会明确报错；10bit/HDR 直接拒绝。
- **视频压缩的 creation_time 会变成压缩时间**（MediaMuxer 不支持写），其余可迁移元数据（旋转/位置/色彩/相册日期）都会保留。
- **目标体积受硬件编码器码率下限约束**：目标极小时（bpp 低于约 0.008）重试两次后仍可能略超，界面会显示实际体积。
- **Android 13+ 的记录仪落盘**：`WRITE_EXTERNAL_STORAGE` 从 API 33 起失效，`Movies/Dashcam` 不可直接写，
  录像会回退到应用私有目录（应用内「录像文件」照常播放/分享，但系统相册看不到）。要进相册需要改用 MediaStore 写入，属后续适配。
- **minSdk 29**：拼接依赖 MediaStore `RELATIVE_PATH`（API 29）。记录仪本身原支持 Android 8.0+，如果哪天不要拼接，可把 minSdk 降回 26。
- **targetSdk 29**：沿用旧工程，仍用 `requestLegacyExternalStorage`。要上 Android 11+ 得改前台服务类型（`foregroundServiceType="camera"`）和分区存储适配。
- **拼接**需要 arm64 + 硬件 HEVC 编解码；1:45 素材约 75–90 秒出片；理想画布 3840×2696 受编码器高度上限（2160）限制会自动缩到 3076×2160。
- **记录仪** 4K 组合在本机 HAL 上会 `ERROR_CAMERA_DEVICE(4)`，选项只保留 1080P/720P。
- 记录仪界面是横屏（sensorLandscape），首页和拼接是竖屏（portrait）。
- 水印里的「地点」未实现（无 GMS 反向地理编码），显示经纬度。
- 拼接输出无音轨（素材本身静音）；需要音轨在 `Composer` 里加一路 track。

---

## 11. 版本

- `1.0.0`（versionCode 1）—— 记录仪 v2.23 + 拼接 v1.0 的首次整合。
- `1.0.1`（versionCode 2）—— 修复存储权限声明：去掉 `maxSdkVersion=29`，Android 11–16 上相册/存储权限
  现在能正常申请、授权、读取；拼接页补「去设置 / 重试 / 返回自动重扫」。
- `1.1.0`（versionCode 3）—— 新增「视频压缩」工具：`icu.wwj.drive.compress`，MediaCodec 硬转码，
  保持分辨率/帧率/编码/音轨/元数据，按目标体积或比例降码率。
- `1.2.0`（versionCode 4）—— 曝光控制重做：取证 + 调研 + 自适应 EV（默认，高光保护）+ 车牌优先实验模式；
  旧安装的"自动+固定 EV"会迁移到自适应 EV。文档在 `docs/exposure/`。
- `1.2.1`（versionCode 5）—— 设置页新增车牌像素估算（10m/20m）；离线验证工具入库；车牌优先模式标注"车灯场景慎用"。
- `1.2.2`（versionCode 6）—— 修复固定焦距锁定后水印快门/ISO 冻结（`lockFocus` 清空了 `CaptureResult` 回调）；水印改取实际值并按标准档格式化；状态栏 ISO 也改为实际值。
- `1.2.3`（versionCode 7）—— 水印快门/ISO 改为跟随 `CaptureResult` 约 3Hz 刷新（原来等 1s 状态 tick），AE 快速变化时不再对不上帧。
- `1.3.0`（versionCode 8）—— 路测数据采集：逐秒 AE 遥测 CSV（场景/EV/实际曝光 ISO/ROI 分位/裁剪/GPS）+ 自动 EV 包围模式（每 5 秒一档）；新增 CSV 分析、包围抽帧、可打印车牌靶标和实验室/路测验证协议。
- `1.4.0`（versionCode 9）—— 道路名双候选（方案 A）：解析平台匹配地址的 featureName/地址行，显示"高架/高速名 / 地面道路名"；不做层判断、不引入在线/离线补充数据。

---

## 12. 验证记录

2026-11-24，HUAWEI P30（ELE-AL00 / Android 10 / API 29）真机，整合后首次构建的 `out/drive.apk`：

| 项 | 结果 |
|---|---|
| 干净构建 | 通过；`out/drive.apk` 234,875 B，V3 签名校验通过 |
| 包名/组件 | `package=icu.wwj.drive`，minSdk/target=29，唯一 launcher，8 个组件齐全，provider authority `icu.wwj.drive.dashcam.clips` |
| 包名残留 | `src/ res/ AndroidManifest.xml build.sh` 中 `com.dashcam` 零命中；dex 141 个类全部在 `icu.wwj.drive.*` |
| 源码自包含 | APK 内 `assets/drive-source.zip` 解出 44 个文件，与工作区逐字节一致 |
| 首页 | 两张工具卡片 + 页脚 `icu.wwj.drive · v1.0.0`，点击分别进入两个功能 |
| 拼接端到端 | 4 视角素材 105s → 3076×2160 / 901 帧 / 36.3 MB / HEVC（`OMX.hisi.video.encoder.hevc`），用时 22s，无崩溃 |
| 记录仪 | `action.START` 起前台服务（channel `icu.wwj.drive.dashcam.recording`）→ 产出 17.8 MB 分段（编码 5.8s）→ `action.STOP` 正常停止；回看页可见录像 |
| 崩溃 | logcat `FATAL EXCEPTION` / `E AndroidRuntime` 均为 0 |
| 旧包 | `com.dashcam.app` / `.probe` / `.stitch` 保持原样，未卸载未改动 |
| 相册权限修复（v1.0.1） | Android 11 / 14 / 16 三个模拟器 + Android 16 真机复验：权限弹窗正常出现，授权后拼接扫到素材并成组；Android 11 上还验证了「拒绝 → 去设置 → 返回自动重扫」 |
| 曝光取证（v1.2.0） | 18 个 4K 车牌 ROI、6 类失败模式、夜间 `1/50 ISO115` 动态范围被压扁的实测证据；可读阈值（宽≥88px、字符-底色差≥36、对比度≥45）；运动模糊快门/相对速度表 |
| 自适应 EV（v1.2.0） | P30 夜间实测：`scene=NIGHT norm=14 p99=56 ev=-1.5` 稳定无振荡；离线仿真 day/dusk/night 均收敛；白天 EV 回到 0 |
| 车牌优先实验（v1.2.0） | 设备暗场稳定 10ms/ISO6400；离线仿真收敛；真实道路矩阵（横穿/对向/隧道）**尚未验证** |
| 视频压缩（v1.1.0） | P30（Android 10）与 OnePlus（Android 16）真机 + 独立复验 7/7 通过：4K HEVC target 15MB → 14.93MB（600 帧/30fps/hvc1/无 B 帧/首包 DTS=0/无 elst）；1080p H.264 target 8MB → 7.40MB（AAC 直通，首轮超 0.46% 自动重试）；旋转矩阵与 VFR 帧数保留；取消与 force-stop 均无残留；MediaStore 行 `is_pending=0` 且相册可播 |

> 设备的旧 App 与新 App 可以并存；确认新包没问题后，旧包可直接 `adb uninstall`。

