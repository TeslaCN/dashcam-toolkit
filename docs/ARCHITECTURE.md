# 架构与扩展指南

面向"以后还会加其他独立功能"这个前提：**首页只是一个工具列表，功能是插件式的**。
本文只写壳与功能的边界、稳定约定和技术取舍；具体实现以代码为准。

---

## 1. 分层与边界

```
壳：首页（工具列表）+ 工具注册表 + Application 全局初始化
        │  只通过 startActivity 进入功能
        ├─ dashcam   行车记录仪（Activity / 前台服务 / 相册回看）
        ├─ stitch    四视角拼接（列表 / 合成 / 保存）
        └─ compress  视频压缩（列表 / 转码 / 保存）
```

三条硬规则：

1. **壳不认识功能业务**，只列出各功能的入口 Activity；新增功能不改首页。
2. **功能之间不互相依赖**。需要共享数据就放公共相册目录并写入 MediaStore；目前刻意不做公共模块，
   等真的出现两处以上重复再抽。
3. **功能不各自声明 Application**。全局初始化统一在壳里加，避免多个功能抢初始化。

每个功能自带 Activity/Service、资源和存储目录；单独删掉一个功能不影响其他功能运行。

---

## 2. 命名与目录约定

| 东西 | 规则 | 例子 |
|---|---|---|
| 根包 / applicationId | `icu.wwj.drive`（`wwj.icu` 倒写） | `icu.wwj.drive` |
| 功能包 | `icu.wwj.drive.<feature>` | `icu.wwj.drive.dashcam` |
| authority / action / 通知渠道 | `icu.wwj.drive.<feature>.<用途>` | `icu.wwj.drive.dashcam.clips` |
| 资源 | `<feature>_<用途>`；公共的用 `home_` / `tool_` | `stitch_main`、`tool_compress_title` |
| 公共存储 | `Movies/<Feature>`；内部临时文件放应用私有目录 | `Movies/Dashcam`、`Movies/DashcamStitch` |

### 为什么不用 Gradle 多模块

工程刻意保持"一个 `src/` 树 + aapt2/javac/d8 一条命令"：这是给旧手机改的车机用的，构建环境经常是临时机器，
多模块/AGP 会引入 Gradle 版本、插件仓库、SDK 平台一堆依赖。真要拆模块时按包名拆源码目录、
`build.sh` 换多个源根即可，不影响包结构。

### 为什么压缩不用 ffmpeg

手机端塞 ffmpeg 要带 20–40MB 的 so，还要处理 NEON/ABI/许可，和"一个几百 KB 的 APK"目标冲突。
平台 MediaCodec 能硬解硬编、零拷贝管线，代价是只能单遍、参数可调范围小——体积靠编完实测、超了调低码率重试来保证。
取舍后果（音轨仅 AAC、10bit/HDR 拒绝等）写在 README 的「已知限制」。

---

## 3. 加一个新功能

以"停车监控"（`icu.wwj.drive.parking`）为例，改动集只有 4 处：

1. **建包**：`src/icu/wwj/drive/parking/`，写功能自己的 Activity/Service。
2. **资源**：布局/文案/图标加 `parking_` 前缀，避免与现有功能撞名。
3. **清单**：在 `AndroidManifest.xml` 声明组件（label 指向工具名，exported 按需）；
   权限、`uses-feature` 加到顶部公共区，权限是应用级的，不能按功能隔离。
4. **注册表**：在工具注册表里加一行（标题、描述、图标、入口 Activity、排序），
   首页自动出现入口，首页代码一行不用改。

存储按 `Movies/<Feature>` 放公共目录；功能间共享素材走 MediaStore，不要读别的功能的私有目录。

---

## 4. 存储与媒体

| 数据 | 位置 | 可见性 |
|---|---|---|
| 记录仪录像（含事件片段） | `Movies/Dashcam/*.mp4`，失败时回退应用私有目录 | 相册可见 |
| 拼接成片 | `Movies/DashcamStitch/STITCH_*.mp4`，写完才发布 | 相册可见 |
| 压缩成片 | `Movies/Compressed/<原名>_compressed.mp4` | 相册可见 |
| 设置 / 缓存 | SharedPreferences 与应用私有目录 | 应用私有 |

原则：

- **成片一定进 MediaStore**，否则相册看不到，跨功能也读不到。
- 写文件期间用 `IS_PENDING=1`，写完改 0；进程被杀留下的 pending 行和孤儿文件在下次冷启动清理。
- 记录仪写公共目录依赖 API 29 的 legacy 存储；Android 13+ 无法直接写，回退应用私有目录。

---

## 5. 权限

权限是**应用级声明、功能级申请**：清单顶部统一声明，功能进入自己的界面时再按需申请，
不要一进首页就弹一堆权限。

| 版本 | 读相册素材 | 写公共目录 |
|---|---|---|
| API 29–32 | `READ_EXTERNAL_STORAGE` | `WRITE_EXTERNAL_STORAGE`（清单 `maxSdkVersion=32`） |
| API 33–36 | 仍申请 `READ_EXTERNAL_STORAGE`：系统给 targetSdk ≤32 的应用自动补 `READ_MEDIA_*`，允许后一并授予 | 不再允许，记录仪回退应用私有目录 |

**别给存储权限加 `maxSdkVersion=29`。** 加了之后 Android 11+ 上等于没声明：系统不弹授权框、
`adb shell pm grant` 报「has not requested permission」、`checkSelfPermission` 永远是 DENIED，
拼接会一直提示没有相册权限——v1.0.0 的拼接读不到相册就是这个原因。

实测（Android 11 / 14 / 16 模拟器）：targetSdk 29 时单独授予 `READ_MEDIA_VIDEO`，MediaStore 查询返回 0 行，
**不能**替代 `READ_EXTERNAL_STORAGE`；申请并允许 `READ_EXTERNAL_STORAGE` 后查询正常。

以后把 targetSdk 提到 33+ 时要同步：声明 `READ_MEDIA_VIDEO` 等新权限并按 SDK 分支申请，记录仪改走 MediaStore 写入。

---

## 6. 后台与生命周期原则

- **记录仪**：前台服务 + 唤醒锁 + 看门狗定时拉起，息屏继续录；进程被杀后下次启动清理未完成的段。
- **拼接/压缩**：没有前台服务，单后台任务 + CPU partial wakelock，屏幕可以熄灭/锁屏；
  取消或失败删 pending 输出，进程被杀留下的残留下次冷启动清理。
- 新增功能若要长时间后台运行，用前台服务 + 常驻通知；普通 Activity 不保证存活。

---

## 7. 自动化与测试入口约定

命令示例见 README「自动化入口」，这里只约定扩展时要保持的东西：

- 每个功能留一个显式 Activity/Service 入口，用 extras 传参数；extras 只对本次会话生效，不写盘。
- 入口 Activity 用 `singleTop`/`singleTask` 复用，方便脚本连续触发。
- 关键结果打固定 TAG 的 logcat（拼接 `RESULT ok=...`，压缩 `RESULT ok=true bytes=...`），测试脚本才好断言。
- `test/` 目前只覆盖拼接；压缩通过自动化入口驱动，用 `ffprobe` 验收元数据与码流兼容性，再全片解码确认无错误。

---

## 8. 版本与发布

- **applicationId 一旦对外发布就不再改**（系统/商店都认它，改了 = 新应用、设置全丢）。
- 新增功能改 `versionCode/versionName`，不改包名。
- `signing/debug.jks` 只用于自己刷机覆盖安装；要发布到商店需换正式密钥。

---

## 9. 待办 / 想法

- `icu.wwj.drive.common`：等第三个功能出现、真的有两处以上重复时再抽，避免过早抽象。
- 记录仪事件触发（加速度/蓝牙遥控）——旧工程 v2.17 起暂停入口，等硬件。
- 拼接更多模板（2×2、画中画）与音轨支持。
- 压缩改为后台服务 + 通知，支持息屏压长视频；目前依赖 Activity 存活。
- 首页显示记录仪"正在录像"状态：目前刻意保持零依赖，后续可读共享状态或绑定服务。
