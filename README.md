# WWJ 行车工具（icu.wwj.drive）

把一个旧手机改成的**车载工具集**：只有一个 APK，打开是工具首页，每个工具是一个独立功能包。
首批整合了两个从零自研、真机长跑验证过的工具：**行车记录仪** 和 **四视角拼接**。

- 应用包名：`icu.wwj.drive`（个人域名 `wwj.icu` 倒写）
- 功能包名：`icu.wwj.drive.<feature>`
- 目标设备：HUAWEI P30（ELE-AL00，Android 10 / API 29，EMUI），兼容 Android 10+

---

## 1. 功能

| 工具 | 功能包 | 说明 |
|---|---|---|
| **行车记录仪** | `icu.wwj.drive.dashcam` | 循环录像（段间无空洞）、时间/GPS/速度水印烧录、HEVC 硬编、1080P/720P、息屏后台录制、存储配额 FIFO 覆盖、看门狗自愈、相册式回看 |
| **四视角拼接** | `icu.wwj.drive.stitch` | 同一时刻 MAIN/LEFT/BACK/RIGHT 四路素材拼成一张画面，几何不裁剪不拉伸，按目标体积分配码率，末尾 15 秒提码率保车牌 |

两个功能共享同一个 APK、同一个 `Application`（`icu.wwj.drive.DriveApp`），首页是工具列表。
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

---

## 3. 架构

```
icu.wwj.drive.MainActivity（首页）
        │  只依赖 ToolRegistry，不认识任何具体功能
        ▼
   ToolRegistry ──► Tool(dashcam) ──► dashcam.DashcamActivity ──► RecordService（前台服务）
                └► Tool(stitch)  ──► stitch.StitchHomeActivity ──► stitch.StitchActivity ──► Composer（MediaCodec+GL）

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

安装与授权（API 29 需要运行时权限）：

```bash
adb install -r out/drive.apk
adb shell pm grant icu.wwj.drive android.permission.CAMERA
adb shell pm grant icu.wwj.drive android.permission.ACCESS_FINE_LOCATION
adb shell pm grant icu.wwj.drive android.permission.WRITE_EXTERNAL_STORAGE   # API 29 legacy 存储
```

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

### 5.1 真机回归脚本

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

## 6. 新增一个功能（3 步）

1. 建包 `icu.wwj.drive.<feature>`，写功能自己的 Activity/Service；组件用 `<feature>` 前缀命名
   （authority/action/通知渠道同理），资源加 `<feature>_` 前缀，产物写 `Movies/<Feature>`。
2. `AndroidManifest.xml` 里声明组件（`android:label` 指向工具名，`exported` 按需）。
3. `ToolRegistry.java` 的 static 块里 `TOOLS.add(new Tool(...))` 加一行（标题、描述、图标、入口类、排序）。

细节和检查清单见 `docs/ARCHITECTURE.md`。

---

## 7. 与旧工程的关系

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

## 8. 已知限制

- **minSdk 29**：拼接依赖 MediaStore `RELATIVE_PATH`（API 29）。记录仪本身原支持 Android 8.0+，如果哪天不要拼接，可把 minSdk 降回 26。
- **targetSdk 29**：沿用旧工程，仍用 `requestLegacyExternalStorage`。要上 Android 11+ 得改前台服务类型（`foregroundServiceType="camera"`）和分区存储适配。
- **拼接**需要 arm64 + 硬件 HEVC 编解码；1:45 素材约 75–90 秒出片；理想画布 3840×2696 受编码器高度上限（2160）限制会自动缩到 3076×2160。
- **记录仪** 4K 组合在本机 HAL 上会 `ERROR_CAMERA_DEVICE(4)`，选项只保留 1080P/720P。
- 记录仪界面是横屏（sensorLandscape），首页和拼接是竖屏（portrait）。
- 水印里的「地点」未实现（无 GMS 反向地理编码），显示经纬度。
- 拼接输出无音轨（素材本身静音）；需要音轨在 `Composer` 里加一路 track。

---

## 9. 版本

`versionCode 1 / versionName 1.0.0` —— 记录仪 v2.23 + 拼接 v1.0 的首次整合。

---

## 10. 验证记录

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

> 设备的旧 App 与新 App 可以并存；确认新包没问题后，旧包可直接 `adb uninstall`。

