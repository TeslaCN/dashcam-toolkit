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
    ┌───────────┴────────────┐
    ▼                        ▼
┌──────────────────┐  ┌──────────────────┐
│ dashcam 功能包    │  │ stitch 功能包     │
│ Activity/Service │  │ Activity/Composer│
│ 自己的 res/存储   │  │ 自己的 res/存储   │
└──────────────────┘  └──────────────────┘
    └──── 跨功能只通过公共目录 + MediaStore ────┘
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
- 记录仪：`CAMERA` 必需，`ACCESS_FINE_LOCATION` 只影响 GPS 水印（可拒绝），`WRITE_EXTERNAL_STORAGE` 决定录像落公共目录还是私有目录。
- 拼接：`READ_EXTERNAL_STORAGE` 读相册素材，`WRITE_EXTERNAL_STORAGE` 写 `Movies/DashcamStitch`。
- 电池优化白名单（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）由记录仪设置页引导，属于记录仪功能。

---

## 6. 线程与生命周期（现有两个功能）

| 功能 | 线程模型 | 生命周期要点 |
|---|---|---|
| 记录仪 | 相机回调线程 + GL 线程 + 编码器线程；UI 通过 `Status` 轮询/Binder 拿状态 | 前台服务 `START_STICKY` + WakeLock，息屏继续；`WatchdogReceiver` 定时拉起；杀进程后下次启动清理未完成的段 |
| 拼接 | 单后台线程做 4 路解码 + GL 合成 + 编码；UI 只收进度回调 | 没有前台服务，切后台通常能继续；进程被杀留下 pending 行，下次冷启动清理 |

新功能如果要做**长时间后台任务**，请像记录仪一样用前台服务 + 通知，不要指望普通 Activity 存活。

---

## 7. 自动化与测试入口约定

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

## 8. 版本与包名演进

- **applicationId 一旦对外发布就不再改**（Play/系统都认它，改了 = 新应用、设置全丢）。
- 新增功能改 `versionCode/versionName`（清单里），不改包名。
- 如果哪天要发布到应用商店，把 `signing/debug.jks` 换成正式密钥；调试签名只用于自己刷机覆盖安装。

---

## 9. 待办 / 想法

- `icu.wwj.drive.common`：等第三个功能出现、真的有两处以上重复时再抽，避免过早抽象。
- 记录仪事件触发（加速度/蓝牙遥控）——旧工程 v2.17 起暂停入口，等硬件。
- 拼接更多模板（2×2、画中画）：`gl/CanvasRenderer.draw()` 里加坐标即可。
- 拼接音轨支持：`Composer` 里多开一条 `MediaMuxer` track，时间戳沿用 `TimeBase` 的思路。
- 首页显示记录仪"正在录像"状态（读 `dashcam_runtime` 或绑定服务），目前刻意保持零依赖。
