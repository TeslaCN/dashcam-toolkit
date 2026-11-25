# 架构与扩展指南

面向"以后还会加其他独立功能"这个前提：**首页只是一个工具列表，功能是插件式的**。
这份文档说明壳与功能的边界、命名规则，以及加一个新功能的最小改动集。

---

## 1. 分层

```
┌─────────────────────────────────────────────┐
│ 壳（icu.wwj.drive）                          │
│  MainActivity   首页，只读 ToolRegistry      │
│  ToolRegistry   工具清单（静态注册）          │
│  Tool           一条工具入口（资源 id + 类）  │
│  DriveApp       Application，跨功能初始化     │
└───────────────┬─────────────────────────────┘
                │ 只有 startActivity 这一种耦合
    ┌───────────┼────────────┐
    ▼           ▼            ▼
┌───────────┐ ┌───────────┐ ┌──────────────┐
│ dashcam   │ │ stitch    │ │ compress     │
│ Activity/ │ │ Activity/ │ │ Activity/    │
│ Service   │ │ Composer  │ │ Compressor   │
│ res/存储  │ │ res/存储  │ │ res/存储     │
└───────────┘ └───────────┘ └──────────────┘
    └─── 跨功能只通过公共目录 + MediaStore ───┘
```

三条硬规则：

1. **壳不 import 功能的业务类**，只 import 各功能的入口 Activity（用于 `Tool.activity`）。
2. **功能之间不互相 import**。需要共享数据就放公共目录并进 MediaStore，或者以后抽 `icu.wwj.drive.common`（目前还没有，刻意保持简单）。
3. **功能包不各自声明 Application**。全局初始化统一在 `DriveApp.onCreate()` 里加一行，避免多个功能抢着做初始化。

---

## 2. 命名规则

| 东西 | 规则 | 例子 |
|---|---|---|
| 根包 / applicationId | `icu.wwj.drive`（`wwj.icu` 倒写） | `icu.wwj.drive` |
| 功能包 | `icu.wwj.drive.<feature>` | `icu.wwj.drive.dashcam` |
| 功能入口 Activity | `<Feature>Activity` / `<Feature>HomeActivity` | `DashcamActivity`、`StitchHomeActivity` |
| Service / Receiver / Provider | `<Feature><Role>` | `RecordService`、`WatchdogReceiver`、`ClipProvider` |
| authority | `icu.wwj.drive.<feature>.<用途>` | `icu.wwj.drive.dashcam.clips` |
| action | `icu.wwj.drive.<feature>.action.<动作>` | `icu.wwj.drive.dashcam.action.START` |
| 通知渠道 | `icu.wwj.drive.<feature>.<渠道>` | `icu.wwj.drive.dashcam.recording` |
| 资源 | `<feature>_<用途>`；公共的用 `home_` / `tool_` | `stitch_main`、`stitch_loading`、`tool_stitch_title` |
| 公共存储 | `Movies/<Feature>`；内部临时文件放应用私有目录 | `Movies/Dashcam`、`Movies/DashcamStitch` |
| 日志 TAG | 功能名 + 角色，保持全大写驼峰 | `DashcamService`、`StitchTest` |

> 资源名前缀是**约定不是强制**，但两个功能合并时正是靠它避免 `app_name`、`loading` 这类通用名撞车；
> 以后新增功能请继续遵守，`aapt2 link` 只在编译期报重复，运行期出错更贵。

### 为什么不用 Gradle 多模块

这个工程刻意保持"一个 `src/` 树 + aapt2/javac/d8 一条命令"，因为它是给**旧手机改的车机**用的，
构建环境经常是临时机器。多模块/AGP 会带来 Gradle 版本、插件仓库、SDK 平台一堆依赖。
真要拆模块时，可以按包名拆源码目录，`build.sh` 里把 `find src` 换成多个源根即可，不影响现在的包结构。

---

## 3. 加一个新功能

以"停车监控"（`icu.wwj.drive.parking`）为例，改动集就 4 处：

### 3.1 源码

```
src/icu/wwj/drive/parking/ParkingActivity.java
src/icu/wwj/drive/parking/ParkingService.java   （如需要）
```

包声明 `package icu.wwj.drive.parking;`。用到资源时 `import icu.wwj.drive.R;`
（R 是按清单 `package="icu.wwj.drive"` 生成的，与功能包不同包，必须显式 import）。

### 3.2 资源

- 布局/文案/图标都加前缀：`res/layout/parking_main.xml`、`values/strings.xml` 里加 `parking_*`、`res/drawable/ic_parking.xml`。
- 不要复用别的功能的布局 id 命名（`txtStatus` 这种通用 id 在多个布局里出现是允许的，但语义要自己清楚）。

### 3.3 清单

```xml
<!-- ================= 停车监控：icu.wwj.drive.parking ================= -->
<activity
    android:name=".parking.ParkingActivity"
    android:exported="true"
    android:label="@string/tool_parking_title"
    android:screenOrientation="portrait" />
```

权限、`uses-feature` 按需加到清单顶部的公共区（权限是应用级的，不能按功能隔离）。

### 3.4 注册表

`src/icu/wwj/drive/ToolRegistry.java`：

```java
TOOLS.add(new Tool(
        "parking",
        R.string.tool_parking_title,
        R.string.tool_parking_desc,
        R.drawable.ic_parking,
        ParkingActivity.class,
        30));   // order：10 记录仪、20 拼接、30 停车监控……
```

再加两条文案（`tool_parking_title` / `tool_parking_desc`）即可，**首页代码一行都不用改**。

---

## 4. 存储与媒体

| 数据 | 位置 | 可见性 |
|---|---|---|
| 记录仪录像 | `Movies/Dashcam/*.mp4`（公共目录，失败时回退应用私有 `files/Movies/Dashcam`） | 相册可见，MediaStore 有记录 |
| 记录仪事件片段 | `Movies/Dashcam/events/EVENT_*.mp4` | 同上 |
| 拼接成片 | `Movies/DashcamStitch/STITCH_*.mp4`（MediaStore `IS_PENDING` 写完才发布） | 相册可见 |
| 设置 | `SharedPreferences`（`dashcam_settings` / `dashcam_runtime`） | 应用私有 |
| 缓存 | `ThumbCache` 内存 + 应用私有目录 | 应用私有 |

原则：

- **成片一定进 MediaStore**，不然相册看不到，跨功能也读不到。
- 写文件期间用 `IS_PENDING=1`，写完改 0；进程被杀留下的 pending 行/orphan 文件在下次冷启动清理（拼接已实现，可参考 `StitchHomeActivity.cleanupStalePending()` / `cleanupOrphans()`）。
- 记录仪写公共目录需要 API 29 的 legacy 存储（清单里 `requestLegacyExternalStorage="true"` + `WRITE_EXTERNAL_STORAGE maxSdkVersion=29`）。功能自己的目录不要写到别人的功能目录里。

---

## 5. 权限

权限是应用级声明、功能级申请：

- 清单顶部统一声明（`CAMERA`、定位、存储、前台服务、唤醒锁……）。
- 功能在进入自己的界面时按需 `requestPermissions`，不要一进首页就弹一堆权限。
- 记录仪：`CAMERA` 必需，`ACCESS_FINE_LOCATION` 只影响 GPS 水印（可拒绝），`WRITE_EXTERNAL_STORAGE`
  （`maxSdkVersion=32`）决定录像落公共目录还是私有目录。
- 拼接：`READ_EXTERNAL_STORAGE` 读相册素材，`WRITE_EXTERNAL_STORAGE` 只在 API ≤32 需要（33+ 走 MediaStore）。
- 电池优化白名单（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）由记录仪设置页引导，属于记录仪功能。

### 5.1 存储权限的版本矩阵（踩过的坑）

**别给存储权限加 `maxSdkVersion=29`。** 加了之后 Android 11+ 上这两条权限等于没声明：
系统不会弹授权框，`adb shell pm grant` 直接报「has not requested permission」，
`checkSelfPermission` 永远是 DENIED —— 拼接就会一直提示「没有相册权限」。

当前策略（targetSdk 29）：

| 版本 | 读取相册 | 写公共目录 |
|---|---|---|
| API 29–32 | `READ_EXTERNAL_STORAGE` | `WRITE_EXTERNAL_STORAGE`（清单里 `maxSdkVersion=32`） |
| API 33–36 | 仍申请 `READ_EXTERNAL_STORAGE`：系统会给 targetSdk ≤32 的应用自动补上 `READ_MEDIA_IMAGES/VIDEO/AUDIO`，用户在弹窗里点允许后二者一起授予 | 不再允许，记录仪回退到应用私有目录 |

实测结论（Android 11 / 14 / 16 模拟器）：

- 只申请并授予 `READ_MEDIA_VIDEO`（targetSdk 29）时，MediaStore 查询返回 0 行，**不能**替代
  `READ_EXTERNAL_STORAGE`；
- 申请 `READ_EXTERNAL_STORAGE` 并由用户点允许后，查询正常返回全部视频。

以后把 `targetSdk` 提到 33+ 时要同步做三件事：
1. 清单加 `READ_MEDIA_VIDEO`（34+ 再加 `READ_MEDIA_VISUAL_USER_SELECTED`），
   `READ_EXTERNAL_STORAGE` 加 `maxSdkVersion=32`；
2. `StitchHomeActivity.readPermissions()/hasPermission()` 按 SDK 分支；
3. 记录仪改走 MediaStore 写入（`IS_PENDING` + `openFileDescriptor`），否则 Android 13+ 只能落私有目录。

### 5.2 MediaStore 里看不到新文件？

调试时用 `adb push` 塞进 `/sdcard/Movies/...` 的视频，`MEDIA_SCANNER_SCAN_FILE` 扫描出来的行
可能带 `IS_PENDING=1`（shell 触发的扫描会这样），MediaProvider 对非属主应用**隐藏 pending 行**，
表现就是「权限给了、文件也在，但扫描到 0 个视频」。把行改成已发布即可：

```bash
adb shell content update --uri content://media/external/file/<id> --bind is_pending:i:0
```


---

## 6. 视频压缩引擎（compress）

### 6.1 为什么不用 ffmpeg

电脑端用 ffmpeg 是两遍 ABR + x265 参数调整；手机上塞 ffmpeg 要带 20–40MB 的 so，还要处理
NEON/ABI/许可，和"一个 240KB 的 APK"的工程目标冲突。Android 自带 MediaCodec 能硬解硬编，
零拷贝管线足够，代价是只能单遍、参数可调范围小。

### 6.2 管线

```
MediaExtractor(视频轨) → MediaCodec 解码器 → [输出 Surface = 编码器 createInputSurface()]
                                               → MediaCodec 编码器（同 MIME / 同宽高 / 同帧率）
                                               → MediaMuxer（直接写 MediaStore pending 的 PFD）
MediaExtractor(音频轨) ── 原样 copy AAC 样本 ──┘（按 PTS 与视频交错写）
```

- **零拷贝**：解码器 `releaseOutputBuffer(idx, true)` 直接渲染到编码器 InputSurface，没有 YUV 往返。
- **时间戳透传**：解码输出的 PTS 原样给编码器；只把首个 PTS 平移到 0，保证首包 DTS≥0。
  帧数/帧率/VFR 因此都保持。
- **码率**：单遍 VBR（`KEY_BITRATE_MODE` + `KEY_VIDEO_BITRATE`），I 帧间隔 1 秒。
  硬件编码器不支持两遍；体积靠"编完量一次，超了就按实际/目标比例调低码率重试（≤2 次）"保证。
- **兼容性**：硬件编码器默认无 B 帧，MediaMuxer 不写 `elst`；输出 `has_b_frames=0`、
  首包 DTS=0，正好避开电脑端遇到的「B 帧→负 DTS→edit list→手机卡顿」问题。

### 6.3 元数据能力边界

| 元数据 | 手机端能否保留 | 做法 |
|---|---|---|
| 旋转 | ✅ | `MediaMuxer.setOrientationHint(rotation)` |
| 拍摄位置 | ✅ | 解析 `METADATA_KEY_LOCATION` 后 `setLocation` |
| 色彩（primaries/transfer/range） | ✅ | 写进编码器 MediaFormat，MediaMuxer 落到 `colr`/`pasp` |
| 相册日期 | ✅（近似） | 输出行写源 `DATE_TAKEN` |
| creation_time | ❌ | MediaMuxer 没有写 `mvhd/creation_time` 的 API，会变成压缩时间 |
| profile/level | 部分 | 由硬件编码器决定，可能从 Main10/Main 降级；不允许升高 |
| 音轨编码 | 仅 AAC 直通 | 其它编码明确报错，不静默丢 |

### 6.4 测试与回归

压缩的进程被杀残留采用"记 URI + 冷启动核对"：
转码前把 MediaStore pending item URI 写进 `SharedPreferences`，`DriveApp.onCreate` 后台调用
`Compressor.cleanupStale()` 按 URI 精确删除，再按 `_compressed(...).mp4` 命名兜底删孤儿文件，
最后对 `Movies/Compressed` 触发一次 MediaScanner 扫描（修剪"行在文件没了"的旧记录）。
已发布（`IS_PENDING=0`）的输出不会被清理。列表侧过滤掉文件名含 `_compressed` 的产物。

`test/` 下的脚本目前只覆盖拼接；压缩用 Activity 的自动化入口驱动：

```bash
adb shell am start -n icu.wwj.drive/.compress.CompressHomeActivity \
  --ez autostart true --es name <关键字> --ei targetMB 40
```

验收看 `ffprobe`：分辨率/编码/帧率/帧数/时长/体积、`has_b_frames=0`、首包 `dts_time>=0`、
无 `elst`、音轨编码与声道、旋转、色彩；再 `ffmpeg -xerror` 全片解码。日志 TAG 为 `CompressTest`。

---

## 7. 线程与生命周期（现有三个功能）

| 功能 | 线程模型 | 生命周期要点 |
|---|---|---|
| 记录仪 | 相机回调线程 + GL 线程 + 编码器线程；UI 通过 `Status` 轮询/Binder 拿状态 | 前台服务 `START_STICKY` + WakeLock，息屏继续；`WatchdogReceiver` 定时拉起；杀进程后下次启动清理未完成的段 |
| 拼接 | 单后台线程做 4 路解码 + GL 合成 + 编码；UI 只收进度回调 | 没有前台服务，切后台通常能继续；进程被杀留下 pending 行，下次冷启动清理 |
| 压缩 | 单后台线程：解码+编码+写 muxer；UI 只收进度 | 没有前台服务，Activity 在前台时跑；取消/失败删 pending，进程被杀留下的 pending 行由 MediaStore 自行回收 |

新功能如果要做**长时间后台任务**，请像记录仪一样用前台服务 + 通知，不要指望普通 Activity 存活。

---

## 8. 自动化与测试入口约定

给脚本留的入口保持稳定，方便真机回归：

```bash
# 记录仪：起录/停止（extras 只对本次会话生效，不写盘）
adb shell am start-foreground-service -n icu.wwj.drive/.dashcam.RecordService \
  -a icu.wwj.drive.dashcam.action.START --ei width 1920 --ei height 1080
adb shell am start-foreground-service -n icu.wwj.drive/.dashcam.RecordService \
  -a icu.wwj.drive.dashcam.action.STOP

# 拼接：autostart 自动选第一组素材并按参数出片
adb shell am start -n icu.wwj.drive/.stitch.StitchHomeActivity \
  --ez autostart true --ei duration 105 --ei sizeMB 70 --ez boost true
```

新增功能如果要有自动化入口，建议同样用"显式 Activity + extras + `singleTop` 复用"的模式，
并把关键进度打到固定 TAG 的 logcat，测试脚本才好断言。

---

## 9. 版本与包名演进

- **applicationId 一旦对外发布就不再改**（Play/系统都认它，改了 = 新应用、设置全丢）。
- 新增功能改 `versionCode/versionName`（清单里），不改包名。
- 如果哪天要发布到应用商店，把 `signing/debug.jks` 换成正式密钥；调试签名只用于自己刷机覆盖安装。

---

## 10. 待办 / 想法

- `icu.wwj.drive.common`：等第三个功能出现、真的有两处以上重复时再抽，避免过早抽象。
- 记录仪事件触发（加速度/蓝牙遥控）——旧工程 v2.17 起暂停入口，等硬件。
- 拼接更多模板（2×2、画中画）：`gl/CanvasRenderer.draw()` 里加坐标即可。
- 拼接音轨支持：`Composer` 里多开一条 `MediaMuxer` track，时间戳沿用 `TimeBase` 的思路。
- 压缩引擎可考虑加"后台服务 + 通知"，支持息屏压长视频；目前依赖 Activity 存活。
- 首页显示记录仪"正在录像"状态（读 `dashcam_runtime` 或绑定服务），目前刻意保持零依赖。
