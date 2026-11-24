# Dashcam（行车记录仪）— Android 旧手机改造

一个**从零自研、已在真机长跑验证**的行车记录仪 App：循环录像、时间/GPS/速度水印烧录、分辨率/帧率/码率可调、息屏后台录制、开机/接电自动开录、存储配额自动覆盖、看门狗自愈。

> 目标设备：HUAWEI P30（ELE-AL00，Android 10 / API 29，EMUI）。兼容 Android 8.0+。
> 实测结论：1080p30 + AAC 48k + 60s 分段，息屏连续录制 **20 分钟/20 段/35,683 帧，覆盖率 99.9%**。

---

## 1. 功能

| 功能 | 说明 |
|---|---|
| 循环录像 | 编码器常开、在关键帧处轮转 Muxer，**段间无空洞**；段长 30/60/120/180s 可选 |
| 视频编码 | **H.265 / HEVC**（硬件编码，`OMX.hisi.video.encoder.hevc`），1080P/720P，码率 8–50 Mbps |
| 水印烧录 | GL 合成，逐帧烧进画面：**顶部单行**——主信息（时间 + 经纬度 [+ 地点，需联网]）左对齐；辅助信息（快门 + ISO）**右对齐、约 0.6 倍字号**；占用画面最小 |
| 画面方向 | 默认垂直翻转校正（v2.1 修复镜像/翻转问题）；设置里「倒装安装」可再转 180° |
| 防抖 | **EIS 在本机不可用**（开启会触发 camera error 4，已强制关闭）；OIS 由硬件常开 |
| 参数可调 | 分辨率（720P/1080P/**2K宽屏 2560×1080**/**2.7K 3648×2056**/**4K**）、**帧率 30/25/24**、码率、段长、存储配额 1–32 GB、主摄/超广角 |
| 回看界面 | **相册式网格**：每个缩略图带起始时间与大小，点击播放，长按删除/分享；事件片段带 ★ 标记 |
| ISO 范围 | 从 `SENSOR_INFO_SENSITIVITY_RANGE` 动态读取（本机 50–6400），上限选项按设备范围生成并夹紧 |
| 参数输入 | 码率、存储配额、单段时长支持**直接输入数值**（带推荐值提示）；配额上限按**手机剩余空间**动态计算并显示 |
| 事件片段 | 已保留前后 30 秒独立转存能力（`events/EVENT_*.mp4`），v2.17 起已禁用加速度触发并暂停所有触发入口，待后续接入蓝牙遥控 |
| 设备能力 | 设置 →「查看设备相机能力」：动态枚举相机数量/朝向/级别/物理子相机/可录像尺寸+fps/编码器上限 |
| 曝光控制 | **EV 补偿**（±2 EV，0.5 步进，自动 AE 模式）；**手动快门 + 自动 ISO**（1/500…1/30，ISO 100–6400 由内置亮度闭环控制） |
| 纯视频 | **不录声音**，不需要麦克风权限 |
| 息屏录像 | 前台服务 + WakeLock，锁屏/息屏继续录制；**UI 可关闭实时预览**（不影响录制），也支持"只关预览不关屏" |
| 自动开录 | 不提供任何开机/接电自启（按要求移除） |
| 自愈 | 无画面看门狗自动重连摄像头；进程被杀后 AlarmManager 定时拉起；相机被抢占后自动恢复 |
| 存储位置 | 录像写到**公共目录 `/sdcard/Movies/Dashcam/` 并入库**，系统相册/文件管理器可见；旧版本私有目录里的录像在启动时自动迁移 |
| 存储管理 | 配额 FIFO 覆盖最旧片段，保留水位，不删正在写的段 |
| 录像管理 | 应用内文件列表：播放 / 分享 / 删除 |
| 状态显示 | 时长、段数、占用、剩余空间、GPS、电池温度、重连次数、错误提示 |

## 2. 架构

```
Camera2 (后摄, SurfaceTexture/OES)
        │
        ▼
GlRenderer — EGL(共享上下文) ──► 预览 EGLSurface (SurfaceView, 可随时 detach)
        │                       └► 编码器 EGLSurface (MediaCodec input surface)
        │  (相机帧 + 每秒重绘的水印纹理)
        ▼
VideoEncoder (H.264, CBR, I 帧间隔 1s, 硬件编码器)
AudioEncoder (AudioRecord 48k → AAC-LC 96kbps)
        │
        ▼
SegmentWriter — 关键帧处轮转 MediaMuxer，时间戳统一重基到 0，写盘 + 配额清理
        │
        ▼
RecordingSession — 摄像头生命周期/重连/看门狗/GPS/温控/状态
        │
        ▼
RecordService (前台服务, 通知, START/STOP, START_STICKY + Watchdog)
```

关键设计点（都来自实测踩坑）：

1. **会话里必须有一路 SurfaceTexture 输出**：华为 HAL 在"只有编码器输入 Surface"的会话里几乎不出帧。
2. **分段必须用轮转 Muxer，不能重建会话**：`MediaRecorder` 重建会话每段会丢 ~1.5s；轮转方案实测 0 空洞。
3. **`MediaRecorder` 会忽略码率**（1080p 固定 ~2.5 Mbps），必须自建 `MediaCodec`。
4. **音视频时间戳要统一时钟**：相机时间戳走 HAL 时钟（≈BOOTTIME），`System.nanoTime()` 是 MONOTONIC，两者相差累计深睡时间；用 `TimeBase` 把音频映射到视频时钟，否则音轨时间戳全部早于视频、会被丢弃，`MediaMuxer.stop()` 直接失败。
5. **GL 上下文要一直绑定在编码器 Surface 上**：预览 detach 时若把 context 解绑，下一帧的 `updateTexImage()` 会抛异常导致停帧。
6. **水印层着色器的 `uMatrix` 必须显式设单位阵**（默认全 0 会让纹理坐标退化为 (0,0)，画面"没有水印"）。
7. **不要用 `SurfaceTexture#getTransformMatrix` 直接喂给编码器**：本机 HAL 的这个矩阵是按"竖屏预览消费端"算的，直接用在横屏编码输出上会导致旋转/拉伸；v2.1 改为对原始相机帧做垂直翻转（正常安装），并提供「倒装安装」开关做 180° 补偿。

## 3. 目录

```
app/
  AndroidManifest.xml
  build.sh                 # 不依赖 Gradle：aapt2 + javac + d8 + apksigner
  res/                     # 图标、字符串
  src/com/dashcam/app/
    DashcamApp.java        # Application + 通知渠道
    MainActivity.java      # 主界面：预览、状态、开始/停止、设置
    ClipsActivity.java     # 录像列表：播放/分享/删除
    RecordService.java     # 前台服务，START/STOP，通知
    RecordingSession.java  # 会话编排：相机/GL/编码器/写入器/重连/看门狗
    GlRenderer.java        # EGL + 双输出渲染 + 水印
    VideoEncoder.java      # H.264 硬件编码
    AudioEncoder.java      # AAC 音频编码
    SegmentWriter.java     # MP4 轮转、时间戳重基、配额清理
    StorageManager.java    # 目录/命名/配额/FIFO
    GpsTracker.java        # 定位与速度
    TimeBase.java          # 音视频时钟对齐
    Settings.java          # 设置持久化
    NotificationHelper.java
    ClipProvider.java      # ContentProvider（播放/分享）
    BootReceiver.java      # 开机自启
    PowerReceiver.java     # 接电自启
    WatchdogReceiver.java  # 定时拉起
tests/
  smoke_test.sh            # 安装→录制→停止→拉取→ffprobe 校验
  ...
```

## 4. 构建与安装

环境：Android SDK（build-tools 34、platform android-33）、JDK 8（javac）、JDK 11+（d8）。

```bash
cd app
./build.sh                  # 产出 app/out/dashcam.apk
adb install -r out/dashcam.apk
adb shell pm grant com.dashcam.app android.permission.CAMERA
adb shell pm grant com.dashcam.app android.permission.RECORD_AUDIO
adb shell pm grant com.dashcam.app android.permission.ACCESS_FINE_LOCATION
```

首次打开 App 也会请求运行时权限；建议点一次“设置 → 电池优化白名单”。

### 4.1 工程内嵌于 APK（随时取回、随时重建）

每次 `./build.sh` 都会把**整个工程**（`src/ res/ signing/ AndroidManifest.xml build.sh README.md`）打包进 APK 的
`assets/dashcam-source.zip`，所以 APK 本身就是一个完整可构建的工程副本：

```bash
# 从已安装/任意一份 APK 中取回工程
unzip dashcam.apk assets/dashcam-source.zip -d /tmp/proj
cd /tmp/proj && unzip -q dashcam-source.zip && ./build.sh
# 产物：out/dashcam.apk（与外部 APK 同签名，可直接覆盖安装）
```

> `signing/debug.jks` 是随工程附带的**调试密钥**（口令 `android`），目的是让重建的 APK 能原地覆盖安装；
> 正式分发请替换成你自己的密钥。构建脚本会自动探测 Android SDK 与 JDK（可用 `ANDROID_HOME` / `JAVAC_BIN` / `JAVA_BIN` 覆盖）。

### 4.1.1 曝光参数的更新节奏（每帧都会变吗？）

- **自动 AE 模式**：曝光/ISO 由 HAL 的 AE 决定，收敛期可能逐帧调整，稳定后保持不变（实测：8 秒 240 帧只出现 2 个不同的 (曝光, ISO) 组合）。
- **快门上限 + 自动曝光/ISO**：App 的测光闭环**每 150 ms 更新一次**请求（30 fps 下约 4–5 帧一个台阶），并做了 ±2 倍限幅平滑；同一请求覆盖的帧参数完全相同。
- 每帧的 `CaptureResult` 都会回报实际生效值；HAL 可能叠加数字增益（自动模式下曾报出 ISO 12800，超过宣称的 6400）。

### 4.2 曝光/快门使用建议（行车记录场景）

| 场景 | 建议设置 | 说明 |
|---|---|---|
| 白天、高速（想拍清车牌） | 手动快门 **1/500**，ISO 上限 800–1600 | 快门短 → 运动模糊小；ISO 由闭环自动压低 |
| 黄昏 / 夜间城市道路 | 手动快门 **1/50** 或 **1/30**，ISO 上限 6400 | 1/50 是 50Hz 灯光安全值；1/30 最亮但运动模糊明显 |
| 进出隧道、光照突变 | **自动 AE** + EV 0 | 系统 AE 适应最快；手动模式的 ISO 闭环约 0.5–1 s 收敛 |
| 逆光 / 强反光 | 自动 AE，EV −0.5 ~ −1.0 | 保住高光，暗部可接受 |
| 固定长时间停车监控 | 手动 1/100，ISO 上限 1600 | 50Hz 安全、噪点低、码率稳定 |

> 手动模式下 ISO 由 App 的亮度闭环（320×240 测光、目标 110/255、6 Hz）在 100–上限之间自动调节，EV 用来整体偏移目标亮度。

## 5. 使用

1. 打开 App（横屏），点 **开始录像**。
2. 需要无人值守时：
   - 设置里打开 **开机自动录像 / 接电自动录像**；
   - 系统设置里把本应用加入**电池优化白名单**；
   - 华为 EMUI：「设置 → 应用 → 应用启动管理」→ 本应用改为**手动管理**，自启动/关联启动/后台活动全开。
3. 录像文件在 `Android/data/com.dashcam.app/files/Movies/Dashcam/`，App 内“录像文件”可直接播放/分享/删除，也可 `adb pull`。
4. 锁屏/息屏后继续录制；通知栏常驻，带“停止录像”按钮。

### 5.1 自动化 / 脚本接口

服务支持通过 Intent extras **为单次会话覆盖参数**（不写盘，便于自动化测试）：

```bash
adb shell am start-foreground-service -n com.dashcam.app/.RecordService \
  -a com.dashcam.app.action.START \
  --ei width 3840 --ei height 2160 --ei bitrateMbps 50 \
  --ei segmentSecs 30 --ei quotaMb 1024 \
  --ez audio true --ez watermark true
adb shell am start-foreground-service -n com.dashcam.app/.RecordService \
  -a com.dashcam.app.action.STOP
```

## 6. 已知限制

- **4K 在本机不可用**：本机 Camera2 HAL 在「4K + GL 水印」组合下抛 `ERROR_CAMERA_DEVICE(4)`，故选项里只保留 1080P/720P；其他机型可自行加回（`Settings.RESOLUTIONS`）。
- **曝光控制说明**：本机 Camera2 虽未声明 `MANUAL_SENSOR`，但 `CONTROL_AE_MODE_OFF` + `SENSOR_EXPOSURE_TIME` 实测**生效**（2ms→40ms 画面亮度 16.6→195.9 线性变化），因此 v2.3 提供「手动快门 + 自动 ISO」：快门由用户固定，ISO 由 App 自建亮度闭环（320×240 YUV 测光，~6Hz）在 100–6400 内自动调整，默认目标亮度 110/255，带高光保护。
- 手动模式下 **ISO 不是系统 AE 在调**，而是 App 的闭环；极端场景（如 1/500 进隧道）可能 0.5–1 s 才收敛；1/500、1/250 等非 10ms 整数倍快门在 50Hz 灯光下可能有轻微频闪，长时间固定拍摄建议选「1/100、1/50（50Hz 安全）」。
- 长曝光（1/30）在白天会过曝（ISO 已到下限 100），白天请选 1/500~1/125。
- 目标 SDK 为 29（适配本机 Android 10）。Android 11+ 若要后台启动前台服务，需要把 `targetSdk` 提到 30+ 并在 manifest 声明 `foregroundServiceType="camera"`。
- 水印里的「地点」未实现：需要反向地理编码（网络/Google 服务），本机无 GMS 定位服务；当前用经纬度表达位置。
- 主摄视角约 73°，不如成品记录仪广；可在设置里改用超广角（`cameraIdx=3`，需自行加选项）。
- 长期车内高温+插电对电池不友好，建议避免暴晒。

## 7. 测试

见 `docs/测试报告.md`：功能矩阵、长跑数据、异常注入（抢占相机/杀进程/重启/断电）与修复记录。
