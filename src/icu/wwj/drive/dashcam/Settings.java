package icu.wwj.drive.dashcam;

import android.content.Context;
import android.content.SharedPreferences;

/** User-visible recording settings, persisted in SharedPreferences. */
public class Settings {

    private static final String PREFS = "dashcam_settings";

    /**
     * All of these are real stream sizes reported by this device's camera HAL (verified by
     * actually recording them); 2560x1440 (QHD) is NOT offered by the HAL.
     */
    public static final int[][] RESOLUTIONS = {
            {1920, 1080}, {2560, 1080}, {3648, 2056}, {3840, 2160}, {1280, 720}
    };
    public static final String[] RESOLUTION_LABELS = {
            "1080P (推荐)", "2K 宽屏 2560×1080 (21:9)", "2.7K 3648×2056", "4K (约 22GB/h，发热大)", "720P (省空间)"
    };
    /** Shutter presets (nanoseconds). 1/100 and slower are safe under 50 Hz lighting. */
    public static final long[] SHUTTER_NS = {
            500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 10_000_000L,
            16_666_667L, 20_000_000L, 33_333_333L
    };
    public static final String[] SHUTTER_LABELS = {
            "1/2000", "1/1000", "1/500", "1/250", "1/125", "1/100 (50Hz安全)",
            "1/60", "1/50 (50Hz安全)", "1/30 (最亮)"
    };
    /** Labels for the shutter CEILING mode (exposure never longer than this). */
    public static final String[] SHUTTER_CAP_LABELS = {
            "不超过 1/2000", "不超过 1/1000", "不超过 1/500", "不超过 1/250",
            "不超过 1/125", "不超过 1/100", "不超过 1/60", "不超过 1/50", "不超过 1/30"
    };
    public static final String[] FOCUS_MODE_LABELS = {"连续自动对焦", "固定无穷远（推荐行车）"};
    public static final String[] SHARPEN_LABELS = {"不锐化", "轻度锐化", "较强锐化"};
    public static final float[] ZOOM_FACTORS = {1.0f, 1.5f, 2.0f, 3.0f};
    public static final String[] ZOOM_LABELS = {"1.0x（广角）", "1.5x", "2.0x（车牌模式）", "3.0x（特写）"};
    public static final int[] MAX_ISO = {800, 1600, 3200, 6400};
    public static final String[] MAX_ISO_LABELS = {"ISO 上限 800", "ISO 上限 1600", "ISO 上限 3200", "ISO 上限 6400"};
    public static final String[] EXPOSURE_MODE_LABELS = {"自动（系统 AE + 固定 EV）", "快门上限 + 自动曝光/ISO", "全场景自适应（高光保护）", "车牌优先（实验，车灯场景慎用）"};
    public static final int[] FPS_OPTIONS = {30, 25, 24};
    public static final String[] FPS_LABELS = {"30 fps", "25 fps", "24 fps（更省空间/更亮）"};
    public static final String[] DRIVING_MODE_LABELS = {
            "允许锁屏（省电，依赖后台保活）", "黑屏常亮（推荐：屏幕不锁，纯黑省电）"
    };
    /** -2.0 .. +2.0 EV in 0.5 steps (this device's AE compensation step is 0.5 EV). */
    public static final String[] EV_LABELS = {
            "-2.0", "-1.5", "-1.0", "-0.5", "0.0", "+0.5", "+1.0", "+1.5", "+2.0"
    };

    public static final int[] CAMERA_IDS = {0, 3, 4};
    public static final String[] CAMERA_LABELS = {"主摄 1x（推荐）", "超广角 0.45x", "长焦 2.1x（光学）"};
    public static final int[] BITRATES = {8, 12, 16, 20, 24, 40, 50};
    public static final int[] SEGMENTS = {30, 60, 120, 180};
    public static final int[] QUOTAS_MB = {1024, 2048, 4096, 8192, 16384, 32768};

    public int width = 1920;
    public int height = 1080;
    public int fps = 30;
    public int bitrateMbps = 24;
    public int segmentSecs = 60;
    public int quotaMb = 16384;
    public int cameraIdx = 0;
    public boolean watermark = true;
    public boolean watermarkExposure = false;   // second watermark line: shutter + ISO
    public boolean watermarkPlace = false;      // watermark line: reverse-geocoded place name

    public boolean showPreview = true;
    public boolean upsideDownMount = false;
    public boolean autoOrient = true;   // detect upside-down mounting from gravity
    public int drivingMode = 1;       // 0 = allow lock, 1 = keep screen on with black overlay
    public int focusMode = 0;         // 0 = continuous AF, 1 = fixed infinity
    public int sharpenLevel = 1;      // 0..2
    public int zoomIndex = 0;         // index into ZOOM_FACTORS
    public boolean autoLens = true;   // pick the physical lens that best matches the requested zoom
    public boolean highlightPriority = true; // protect plate highlights (esp. at night)
    public boolean allow4k = false;   // transient (test hook): bypass the 4K blocklist
    public boolean diagExposure = false; // transient: log per-frame exposure/ISO
    public boolean aeLog = false;        // write per-second AE telemetry CSV (road-test tuning)
    public boolean evBracket = false;    // road-test: cycle EV 0 / -0.5 / ... / -2 every 5s
    public String outDirOverride = null; // transient (tests only): alternate output directory
    public boolean brakeTrigger = false; // disabled: road vibrations trigger false positives; keep event-clip infra for future Bluetooth remote
    public boolean eis = false;       // video stabilization (crop + resample)
    public int exposureMode = 2;      // 0=auto+fixed EV, 1=manual shutter+auto ISO, 2=adaptive EV, 3=plate-priority (experimental)
    public int shutterIndex = 2;      // 1/125
    public int evIndex = 4;           // 0.0 EV (index into EV_LABELS)
    public int maxIso = 6400;         // actual ISO cap (clamped to the device's sensitivity range)

    public static Settings load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Settings s = new Settings();
        s.width = sp.getInt("width", 1920);
        s.height = sp.getInt("height", 1080);
        s.fps = sp.getInt("fps", 30);
        s.bitrateMbps = sp.getInt("bitrate", 16);
        s.segmentSecs = sp.getInt("segment", 60);
        s.quotaMb = sp.getInt("quota", 16384);
        s.cameraIdx = sp.getInt("camera", 0);
        s.watermark = sp.getBoolean("watermark", true);
        s.watermarkExposure = sp.getBoolean("watermark_exposure", false);
        s.watermarkPlace = sp.getBoolean("watermark_place", false);
        s.aeLog = sp.getBoolean("ae_log", false);
        s.evBracket = sp.getBoolean("ev_bracket", false);

        s.showPreview = sp.getBoolean("show_preview", true);
        s.upsideDownMount = sp.getBoolean("mount_upside_down", false);
        s.autoOrient = sp.getBoolean("auto_orient", true);
        s.drivingMode = sp.getInt("driving_mode", 1);
        int schema = sp.getInt("schema_version", 0);
        if (schema < 2) {
            // one-time migration to dashcam-oriented defaults (higher bitrate, sharpening, black screen)
            s.bitrateMbps = 24;
            s.sharpenLevel = 1;
            s.drivingMode = 1;
            sp.edit().putInt("bitrate", 24).putInt("sharpen_level", 1)
                    .putInt("driving_mode", 1).putInt("schema_version", 2).apply();
        }
        s.focusMode = sp.getInt("focus_mode", 0);
        s.sharpenLevel = sp.getInt("sharpen_level", 1);
        s.zoomIndex = sp.getInt("zoom_index", 0);
        s.autoLens = sp.getBoolean("auto_lens", true);
        s.highlightPriority = sp.getBoolean("highlight_priority", true);
        s.eis = sp.getBoolean("eis", false);
        s.brakeTrigger = false; // accelerometer trigger disabled; event clips wait for explicit trigger (e.g. future Bluetooth remote)
        s.exposureMode = sp.getInt("exposure_mode", 2);
        if (schema < 3) {
            // v1.2.0: 默认曝光模式从"固定 EV"迁移到"全场景自适应（高光保护）"。
            // 用户显式选过的手动模式（1）保留。
            if (s.exposureMode == 0) s.exposureMode = 2;
            sp.edit().putInt("exposure_mode", s.exposureMode).putInt("schema_version", 3).apply();
        }
        s.shutterIndex = sp.getInt("shutter_index", 2);
        s.evIndex = sp.getInt("ev_index", 4);
        int legacyIdx = Math.max(0, Math.min(MAX_ISO.length - 1, sp.getInt("max_iso_index", 3)));
        s.maxIso = sp.getInt("max_iso", MAX_ISO[legacyIdx]);
        return s;
    }

    public void save(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("width", width)
                .putInt("height", height)
                .putInt("fps", fps)
                .putInt("bitrate", bitrateMbps)
                .putInt("segment", segmentSecs)
                .putInt("quota", quotaMb)
                .putInt("camera", cameraIdx)
                .putBoolean("watermark", watermark)
                .putBoolean("watermark_exposure", watermarkExposure)
                .putBoolean("watermark_place", watermarkPlace)
                .putBoolean("ae_log", aeLog)
                .putBoolean("ev_bracket", evBracket)

                .putBoolean("show_preview", showPreview)
                .putBoolean("mount_upside_down", upsideDownMount)
                .putBoolean("auto_orient", autoOrient)
                .putInt("driving_mode", drivingMode)
                .putInt("focus_mode", focusMode)
                .putInt("sharpen_level", sharpenLevel)
                .putInt("zoom_index", zoomIndex)
                .putBoolean("auto_lens", autoLens)
                .putBoolean("highlight_priority", highlightPriority)
                .putBoolean("eis", eis)
                .putBoolean("brake_trigger", brakeTrigger)
                .putInt("exposure_mode", exposureMode)
                .putInt("shutter_index", shutterIndex)
                .putInt("ev_index", evIndex)
                .putInt("max_iso", maxIso)
                .apply();
    }

    public long shutterNs() {
        return SHUTTER_NS[Math.max(0, Math.min(SHUTTER_NS.length - 1, shutterIndex))];
    }

    public float ev() {
        return (evIndex - 4) * 0.5f;
    }

    public int maxIso() {
        return maxIso;
    }

    public int bitrateBps() {
        return bitrateMbps * 1_000_000;
    }

    public long quotaBytes() {
        return (long) quotaMb * 1024L * 1024L;
    }

    public String label() {
        return width + "x" + height + "@" + fps + " " + bitrateMbps + "Mbps";
    }
}
