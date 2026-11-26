package icu.wwj.drive.dashcam;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns camera + GL + encoders + segment writer for one continuous recording run. */
public class RecordingSession {

    private static final String TAG = "DashcamSession";

    public interface Listener {
        void onStatusChanged(Status status);

        void onSegmentClosed(File file, long bytes, long durationMs);

        void onError(String message);
    }

    private final Context ctx;
    private final Settings settings;
    private final Listener listener;
    private final Status status = new Status();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final java.util.concurrent.ExecutorService cameraExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "dashcam-cam-open"));

    private CameraManager cameraManager;
    private CameraDevice camera;
    private CameraCaptureSession captureSession;

    private GlRenderer renderer;
    private VideoEncoder videoEncoder;
    private SegmentWriter writer;
    private GpsTracker gps;
    private PowerManager.WakeLock wakeLock;
    private File dir;

    private volatile boolean running;
    private long startWallMs;
    private long closedBytes;
    private int reconnectAttempts;
    private boolean reconnecting;

    public RecordingSession(Context ctx, Settings settings, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.settings = settings;
        this.listener = listener;
        status.resolution = settings.label();
        status.quotaBytes = settings.quotaBytes();
    }

    public Status snapshot() {
        return status.copy();
    }

    private volatile Surface pendingPreview;
    private volatile int previewViewW, previewViewH;

    public void setPreviewSurface(Surface surface) {
        setPreviewSurface(surface, 0, 0);
    }

    public void setPreviewSurface(Surface surface, int viewW, int viewH) {
        pendingPreview = surface;
        previewViewW = viewW;
        previewViewH = viewH;
        GlRenderer r = renderer;
        if (r != null) r.setPreviewSurface(surface, viewW, viewH);
    }

    public long lastPreviewDrawMs() {
        GlRenderer r = renderer;
        return r == null ? 0 : r.lastPreviewDrawMs();
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ start

    private volatile boolean stopRequested;
    private android.media.ImageReader meteringReader;
    private volatile int currentIso = 400;
    private volatile int deviceIsoMin = 100;
    private volatile int deviceIsoMax = 6400;
    private volatile long currentExposureNs;
    private long exposureCeilingNs;   // learned "no-clip" ceiling, slowly relaxes upwards
    private volatile float lastLuma = -1f;
    private long lastIsoAdjustMs;
    private CaptureRequest.Builder requestBuilder;
    private boolean manualExposure;
    private boolean plateAutoExposure;
    private boolean adaptiveEv;
    private int adaptiveEvSteps;
    private double adaptiveSceneLuma = -1;
    private long lastAdaptiveEvMs;
    private long lastAdaptiveLogMs;
    private long lastAdaptiveEvStepMs;
    private final PlateAeController plateAe = new PlateAeController();
    private long lastPlateAeMs;
    private byte[] meterY, meterU, meterV;
    private long lastAeLogMs;
    /** Metering stream size: large enough to see a plate at 10-15m for the adaptive AE. */
    private static final int METER_W = 960;
    private static final int METER_H = 540;
    private boolean lowBatteryStopped;

    public void start() {
        if (running) return;
        running = true;
        stopRequested = false;
        resolveCameraId();
        resolveSize();
        startWallMs = System.currentTimeMillis();
        status.recording = true;
        status.startWallMs = startWallMs;
        try {
            if (settings.outDirOverride != null && !settings.outDirOverride.isEmpty()) {
                dir = new File(settings.outDirOverride);
                if (!dir.exists()) dir.mkdirs();
                Log.w(TAG, "OUTPUT DIR OVERRIDE (test mode): " + dir.getAbsolutePath());
            } else {
                dir = StorageManager.recordingsDir(ctx);
            }
            StorageManager.enforceQuota(dir, settings.quotaBytes(), "");
            acquireWakeLock();
            startGps();

            if (stopRequested) throw new IllegalStateException("stopped during start");
            writer = new SegmentWriter(dir, settings.segmentSecs, settings.quotaBytes(), writerListener);

            videoEncoder = new VideoEncoder();
            long pixels = (long) settings.width * settings.height;
            int minBitrate = pixels >= 7_000_000 ? 45_000_000 : pixels >= 2_500_000 ? 30_000_000 : 0;
            int effectiveBitrate = Math.max(settings.bitrateBps(), minBitrate);
            if (effectiveBitrate != settings.bitrateBps()) {
                Log.i(TAG, "large frame (" + pixels + " px), raising bitrate to " + effectiveBitrate);
            }
            try {
                videoEncoder.start(settings.width, settings.height, settings.fps, effectiveBitrate, videoSink);
            } catch (Throwable first) {
                if (settings.width != 1920 || settings.height != 1080) {
                    Log.w(TAG, "encoder start failed at " + settings.width + "x" + settings.height
                            + ", retry 1080p", first);
                    settings.width = 1920;
                    settings.height = 1080;
                    status.resolution = settings.label();
                    videoEncoder = new VideoEncoder();
                    videoEncoder.start(1920, 1080, settings.fps, settings.bitrateBps(), videoSink);
                } else {
                    throw first;
                }
            }
            Thread.sleep(80);

            if (stopRequested) throw new IllegalStateException("stopped during start");
            renderer = new GlRenderer(settings.width, settings.height);
            renderer.setUpsideDownMount(needsUpsideDownFlip());
            renderer.setSharpen(new float[]{0f, 0.35f, 0.7f}[Math.max(0, Math.min(2, settings.sharpenLevel))]);
            renderer.setTargetFps(settings.fps);
            renderer.start(videoEncoder.inputSurface());
            renderer.setPreviewSurface(pendingPreview, previewViewW, previewViewH);
            Surface camSurface = renderer.cameraSurface();
            if (camSurface == null) throw new IllegalStateException("no camera surface");

            if (stopRequested) throw new IllegalStateException("stopped during start");
            ensureCameraThread();
            try {
                resolveLens();
                openCameraBlocking();
                createSessionBlocking(camSurface);
            } catch (Throwable camError) {
                Log.e(TAG, "initial camera open failed, will retry", camError);
                status.lastError = "摄像头打开失败，自动重试中: " + camError.getMessage();
                scheduleReconnect("启动时摄像头不可用");
            }

            startOrientationSensor();
            mainHandler.post(tick);
            Log.i(TAG, "recording started");
            EventLog.write(ctx, "SESSION_START", settings.label() + " seg=" + settings.segmentSecs
                    + "s audio=off exp=" + status.exposureLabel + " focus=" + settings.focusMode
                    + " cam=" + activeCameraId + " zoom=" + activeZoom
                    + " flip=" + (needsUpsideDownFlip() ? "X" : "Y")
                    + " gravityY=" + Math.round(gravityY)
                    + (settings.autoLens ? "(auto lens)" : "") + " eis=" + settings.eis);
            notifyStatus();
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
            status.lastError = t.getMessage() == null ? t.toString() : t.getMessage();
            listener.onError("启动失败: " + status.lastError);
            stop();
        }
    }

    /** Makes sure the configured size is actually offered by the camera, else falls back to 1080p. */
    /**
     * Rotation (0/180) needed to make the camera image upright for the current display rotation.
     * Empirically on this device: sensorOrientation + displayRotation (both in degrees), mod 360.
     */
    private int resolveImageRotation() {
        int sensor = 90;
        int displayDeg = 0;
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cc = cm.getCameraCharacteristics(String.valueOf(activeCameraId));
            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (so != null) sensor = so;
            android.view.Display d = ((android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            switch (d.getRotation()) {
                case android.view.Surface.ROTATION_90: displayDeg = 90; break;
                case android.view.Surface.ROTATION_180: displayDeg = 180; break;
                case android.view.Surface.ROTATION_270: displayDeg = 270; break;
                default: displayDeg = 0; break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveImageRotation failed", t);
        }
        int deg = ((sensor + displayDeg) % 360 + 360) % 360;
        Log.i(TAG, "image rotation: sensor=" + sensor + " display=" + displayDeg + " -> " + deg);
        return deg;
    }

    private void resolveCameraId() {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            boolean found = false;
            for (String id : cm.getCameraIdList()) {
                if (id.equals(String.valueOf(activeCameraId))) found = true;
            }
            if (!found) {
                Log.w(TAG, "camera " + activeCameraId + " not present, fallback to 0");
                activeCameraId = 0;
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveCameraId failed", t);
            settings.cameraIdx = 0;
        }
    }

    private void resolveSize() {
        try {
            boolean allowed = false;
            for (int[] wh : Settings.RESOLUTIONS) {
                if (wh[0] == settings.width && wh[1] == settings.height) allowed = true;
            }
            if (!allowed && !settings.allow4k) {
                Log.w(TAG, "resolution " + settings.width + "x" + settings.height
                        + " not in supported option list (4K unstable on this device), fallback to 1920x1080");
                settings.width = 1920;
                settings.height = 1080;
                status.resolution = settings.label();
            }
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cc = cm.getCameraCharacteristics(String.valueOf(activeCameraId));
            android.hardware.camera2.params.StreamConfigurationMap map =
                    cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;
            android.util.Size[] sizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
            if (sizes == null) return;
            boolean supported = false;
            for (android.util.Size s : sizes) {
                if (s.getWidth() == settings.width && s.getHeight() == settings.height) supported = true;
            }
            if (!supported) {
                Log.w(TAG, "resolution " + settings.width + "x" + settings.height
                        + " not supported, fallback to 1920x1080");
                settings.width = 1920;
                settings.height = 1080;
                status.resolution = settings.label();
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveSize failed", t);
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dashcam:recording");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(12L * 60L * 60L * 1000L);
    }

    /** Saves the previous segment (the "before" part) and marks the current one as the "after" part. */
    public void requestEventClip(String reason) {
        try {
            String base = "EVENT_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + "_" + reason;
            File prev = lastClosedSegment;
            if (prev != null && prev.exists()
                    && System.currentTimeMillis() - lastClosedAtMs < 3 * 60_000L) {
                saveEventClip(prev, base + "_a.mp4");
            }
            clipBaseName = base;
            clipNextSegment = true;
            status.lastError = "";
            EventLog.write(ctx, "EVENT_CLIP", reason + " base=" + base);
            Log.i(TAG, "event clip requested: " + base);
        } catch (Throwable t) {
            Log.w(TAG, "event clip failed", t);
        }
    }

    private void saveEventClip(File src, String name) {
        final File dir = new File(dir(), "events");
        if (!dir.exists()) dir.mkdirs();
        final File dst = new File(dir, name);
        // Same filesystem: a rename is instant. Only fall back to a background copy.
        if (src.renameTo(dst)) {
            StorageManager.scan(ctx, dst);
            Log.i(TAG, "event clip moved " + dst.getAbsolutePath() + " " + dst.length() + "B");
            EventLog.write(ctx, "EVENT_SAVED", dst.getName() + " " + dst.length() + "B");
            return;
        }
        ioExecutor.execute(() -> copyEventClip(src, dst));
    }

    private void copyEventClip(File src, File dst) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.getFD().sync();
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
                try { out.close(); } catch (Throwable ignored) {}
            }
            StorageManager.scan(ctx, dst);
            Log.i(TAG, "event clip saved " + dst.getAbsolutePath() + " " + dst.length() + "B");
            EventLog.write(ctx, "EVENT_SAVED", dst.getName() + " " + dst.length() + "B");
        } catch (Throwable t) {
            Log.w(TAG, "save event clip failed", t);
        }
    }

    private final java.util.concurrent.ExecutorService ioExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private File dir() {
        return dir != null ? dir : StorageManager.recordingsDir(ctx);
    }

    /**
     * Registers only the gravity sensor for upside-down mount auto-detection.
     * Accelerometer / linear-acceleration brake auto-triggering is intentionally disabled so road
     * bumps never spam event clips; requestEventClip() + ACTION_CLIP remain intact for future
     * triggers (e.g. Bluetooth remote / MediaButton).
     */
    private void startOrientationSensor() {
        try {
            sensorManager = (android.hardware.SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) return;
            android.hardware.Sensor grav = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY);
            if (grav != null) {
                gravityListener = new android.hardware.SensorEventListener() {
                    @Override
                    public void onSensorChanged(android.hardware.SensorEvent event) {
                        gravityY = event.values[1];
                        GlRenderer r = renderer;
                        if (r != null) {
                            boolean flip = needsUpsideDownFlip();
                            r.setUpsideDownMount(flip);
                        }
                    }

                    @Override
                    public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
                    }
                };
                sensorManager.registerListener(gravityListener, grav,
                        android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
                Log.i(TAG, "gravity listener started (brake auto-trigger disabled)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "gravity listener failed", t);
        }
    }

    private void startGps() {
        try {
            gps = new GpsTracker(ctx);
            gps.start((fix, lat, lon, speed) -> {
                status.gpsFix = fix;
                status.lat = lat;
                status.lon = lon;
                status.speedKph = speed;
            });
        } catch (Throwable t) {
            Log.w(TAG, "gps failed", t);
        }
    }

    // --------------------------------------------------------------- stop

    public void stop() {
        stopRequested = true;
        if (!running) return;
        running = false;
        mainHandler.removeCallbacks(tick);
        mainHandler.removeCallbacks(bracketTick);
        closeAeCsv();
        try {
            if (cameraHandler != null) cameraHandler.removeCallbacksAndMessages(null);
        } catch (Throwable ignored) {
        }
        closeCamera();
        if (meteringReader != null) {
            try { meteringReader.close(); } catch (Throwable ignored) {}
            meteringReader = null;
        }
        if (renderer != null) {
            renderer.stop();
            renderer = null;
        }
        if (videoEncoder != null) {
            videoEncoder.stop();
            videoEncoder = null;
        }
        if (writer != null) {
            writer.close();
            writer = null;
        }
        if (gps != null) {
            gps.stop();
            gps = null;
        }
        if (sensorManager != null) {
            if (gravityListener != null) {
                try { sensorManager.unregisterListener(gravityListener); } catch (Throwable ignored) {}
                gravityListener = null;
            }
            sensorManager = null;
        }
        if (wakeLock != null) {
            try {
                wakeLock.release();
            } catch (Throwable ignored) {
            }
            wakeLock = null;
        }
        status.recording = false;
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
        cameraExecutor.shutdownNow();
        notifyStatus();
        Log.i(TAG, "recording stopped");
        EventLog.write(ctx, "SESSION_STOP", "segments=" + status.segments + " bytes=" + status.sessionBytes);
    }

    // ------------------------------------------------------------- camera

    private void ensureCameraThread() {
        if (cameraThread == null) {
            cameraThread = new HandlerThread("dashcam-camera");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }
        if (cameraManager == null) {
            cameraManager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        }
    }

    /**
     * Picks the physical lens for this session.
     * - autoLens OFF: use exactly the selected lens with the selected digital zoom.
     * - autoLens ON + a non-main lens selected (超广角/长焦): honour the user's choice; the zoom
     *   setting is applied as extra digital zoom on top of that lens.
     * - autoLens ON + main selected: pick the lens that best matches the requested zoom
     *   (e.g. 3x -> tele 2.1x + 1.4x digital).
     */
    private void resolveLens() {
        int selected = settings.cameraIdx;
        float want = Settings.ZOOM_FACTORS[Math.max(0, Math.min(Settings.ZOOM_FACTORS.length - 1, settings.zoomIndex))];
        float selectedZoom = LensInfo.zoomRelative(ctx, selected, 0);
        if (selectedZoom <= 0) selectedZoom = 1f;

        if (!settings.autoLens || (selected != 0 && selected != 3 && selected != 4)) {
            activeCameraId = selected;
            activeZoom = want;
            Log.i(TAG, "lens: fixed camera " + activeCameraId + ", digital zoom " + activeZoom);
            return;
        }
        if (selected != 0) {
            // User explicitly chose ultra-wide or telephoto: use that lens and apply the
            // "视野" value as plain digital zoom on top of it (so ultra-wide stays wide at 1.0x).
            activeCameraId = selected;
            activeZoom = Math.max(1f, want);
            Log.i(TAG, "lens: user picked camera " + selected + " (" + selectedZoom
                    + "x optical) + " + activeZoom + "x digital");
            return;
        }
        int bestId = 0;
        float bestZoom = 1f;
        for (int id : new int[]{0, 3, 4}) {
            try {
                float z = LensInfo.zoomRelative(ctx, id, 0);
                if (z <= 0) continue;
                if (z <= want * 1.15f && z > bestZoom) {
                    bestZoom = z;
                    bestId = id;
                }
            } catch (Throwable ignored) {
            }
        }
        activeCameraId = bestId;
        activeZoom = Math.max(1f, want / bestZoom);
        Log.i(TAG, "lens: zoom " + want + "x -> camera " + activeCameraId + " (" + bestZoom
                + "x optical) + " + activeZoom + "x digital");
    }

    /** One-shot capability dump: AE ranges/steps, ISO/shutter ranges, AE regions, HDR scene modes
     *  and Huawei vendor tags. Used to pick the exposure controller's operating envelope. */
    private void logCapabilities() {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cc = cm.getCameraCharacteristics(String.valueOf(activeCameraId));
            Log.i(TAG, "cap hwlevel=" + cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    + " caps=" + java.util.Arrays.toString(cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES))
                    + " aeModes=" + java.util.Arrays.toString(cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES))
                    + " aeCompRange=" + cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    + " aeCompStep=" + cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    + " maxAeRegions=" + cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)
                    + " expRange=" + cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    + " isoRange=" + cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    + " sceneModes=" + java.util.Arrays.toString(cc.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES))
                    + " maxFrameDur=" + cc.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION));
            float[] focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            android.util.SizeF phys = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            android.graphics.Rect active = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Log.i(TAG, "cap lens focal=" + java.util.Arrays.toString(focals) + " phys=" + phys
                    + " active=" + active);
            for (CameraCharacteristics.Key<?> k : cc.getKeys()) {
                String n = k.getName();
                if (n == null || !n.startsWith("com.huawei")) continue;
                String ln = n.toLowerCase(java.util.Locale.US);
                if (!(ln.contains("hdr") || ln.contains("iso") || ln.contains("exposure"))) continue;
                try {
                    Log.i(TAG, "cap hw " + n + "=" + cc.get(k));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "logCapabilities failed", t);
        }
    }

    private void openCameraBlocking() throws Exception {
        ensureCameraThread();
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] err = new String[1];
        cameraManager.openCamera(String.valueOf(activeCameraId), new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice device) {
                camera = device;
                latch.countDown();
            }

            @Override
            public void onDisconnected(CameraDevice device) {
                Log.w(TAG, "camera disconnected");
                device.close();
                if (camera == device) camera = null;
                latch.countDown();
                scheduleReconnect("camera disconnected");
            }

            @Override
            public void onError(CameraDevice device, int code) {
                Log.e(TAG, "camera error " + code);
                device.close();
                if (camera == device) camera = null;
                err[0] = "camera error " + code;
                latch.countDown();
                scheduleReconnect("camera error " + code);
            }
        }, cameraHandler);
        if (!latch.await(8, TimeUnit.SECONDS) || camera == null) {
            throw new IllegalStateException("摄像头打开失败: " + (err[0] == null ? "timeout" : err[0]));
        }
        logCapabilities();
    }

    private void createSessionBlocking(Surface camSurface) throws Exception {
        adaptiveEv = settings.exposureMode == 2;
        plateAutoExposure = settings.exposureMode == 3;
        manualExposure = settings.exposureMode == 1 || plateAutoExposure;
        bracketMode = settings.evBracket;
        if (bracketMode) {
            // 路测包围曝光必须走系统 AE（只改 EV），不能和自适应/手动闭环打架
            adaptiveEv = false;
            plateAutoExposure = false;
            manualExposure = false;
        }
        java.util.List<Surface> surfaces = new java.util.ArrayList<>();
        surfaces.add(camSurface);
        if (manualExposure || adaptiveEv || bracketMode) {
            try {
                int mw = plateAutoExposure ? METER_W : 320;
                int mh = plateAutoExposure ? METER_H : 240;
                meteringReader = android.media.ImageReader.newInstance(mw, mh,
                        android.graphics.ImageFormat.YUV_420_888, 2);
                surfaces.add(meteringReader.getSurface());
                meteringReader.setOnImageAvailableListener(this::onMeteringFrame, cameraHandler);
            } catch (Throwable t) {
                Log.w(TAG, "metering stream unavailable, manual exposure without auto ISO", t);
                meteringReader = null;
            }
            if (meteringReader == null) {
                // 没有测光流：车牌模式/自适应 EV 都无法闭环，回退到系统 AE + 固定 EV，
                // 避免停在 seed 曝光把画面拍死。
                Log.w(TAG, "metering unavailable -> fallback to auto AE + fixed EV");
                plateAutoExposure = false;
                adaptiveEv = false;
                manualExposure = false;
            }
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] err = new String[1];
        camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
                captureSession = session;
                latch.countDown();
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                err[0] = "configure failed";
                latch.countDown();
            }
        }, cameraHandler);
        if (!latch.await(8, TimeUnit.SECONDS) || captureSession == null) {
            throw new IllegalStateException("录像会话创建失败: " + err[0]);
        }

        CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        b.addTarget(camSurface);
        if (meteringReader != null) b.addTarget(meteringReader.getSurface());
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(settings.fps, settings.fps));
        // Focus: continuous AF by default; in "fixed focus" mode we first run one AF trigger,
        // let the lens settle on the distance scene, then switch AF off so it never hunts again.
        b.set(CaptureRequest.CONTROL_AF_MODE, settings.focusMode == 1
                ? CaptureRequest.CONTROL_AF_MODE_AUTO
                : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        // EIS is NOT usable on this HAL: enabling it leads to ERROR_CAMERA_DEVICE (camera error 4)
        // within a few seconds, so we always request it OFF. OIS stays on (hardware default).
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        if (settings.eis) {
            Log.w(TAG, "EIS requested by settings but unsupported on this device; forcing OFF");
            EventLog.write(ctx, "EIS_UNSUPPORTED", "forced OFF");
        }
        applyCropRegion(b);
        b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO);
        // OIS is a hardware mode exposed by this HAL; request it explicitly so it is not left off.
        try {
            CameraCharacteristics cc = ((CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE))
                    .getCameraCharacteristics(String.valueOf(settings.cameraIdx));
            android.util.Range<Integer> isoRange =
                    cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (isoRange != null) {
                deviceIsoMin = isoRange.getLower();
                deviceIsoMax = isoRange.getUpper();
                Log.i(TAG, "device ISO range " + deviceIsoMin + ".." + deviceIsoMax);
            }
            int[] ois = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            boolean on = false;
            if (ois != null) {
                for (int m : ois) if (m == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) on = true;
            }
            if (on) {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
                Log.i(TAG, "OIS requested ON (available modes=" + java.util.Arrays.toString(ois) + ")");
            } else {
                Log.i(TAG, "OIS not available via Camera2 (modes=" + java.util.Arrays.toString(ois) + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "OIS request failed", t);
        }

        if (manualExposure) {
            // Shutter CEILING mode: exposure never longer than the selected value; our own
            // controller adjusts exposure down to 1/2000 and ISO 100..max to hit target luma.
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            long frameNs = 1_000_000_000L / Math.max(1, settings.fps);
            if (plateAutoExposure) {
                currentExposureNs = Math.min(16_666_667L, frameNs);
                currentIso = 1600;
                status.exposureLabel = "车牌优先自适应";
                Log.i(TAG, "plate AE: metering " + METER_W + "x" + METER_H
                        + ", ISO " + deviceIsoMin + ".." + Math.min(settings.maxIso(), deviceIsoMax));
            } else {
                currentExposureNs = Math.min(settings.shutterNs(), frameNs);
                status.exposureLabel = Settings.SHUTTER_CAP_LABELS[settings.shutterIndex] + " 自动曝光";
                Log.i(TAG, "manual exposure: " + settings.shutterNs() + "ns, ISO " + currentIso
                        + ".." + settings.maxIso() + ", ev=" + settings.ev());
            }
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureNs);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso);
            b.set(CaptureRequest.CONTROL_AE_LOCK, false);
        } else if (adaptiveEv) {
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            adaptiveEvSteps = Math.round(settings.ev() / 0.5f);
            applyEvSteps(b, adaptiveEvSteps);
            status.exposureLabel = "自适应 EV（高光保护）";
            Log.i(TAG, "adaptive EV mode: start at " + (adaptiveEvSteps * 0.5f) + "EV");
        } else {
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            applyEvCompensation(b);
            status.exposureLabel = "自动 AE " + Settings.EV_LABELS[settings.evIndex] + "EV";
            Log.i(TAG, "auto exposure with EV " + settings.ev());
        }
        requestBuilder = b;
        focusLocked = false;
        captureSession.setRepeatingRequest(b.build(), resultCallback, cameraHandler);
        openAeCsv();
        if (bracketMode) {
            // 立即应用第一档，保证 CSV 的 ev_steps 和画面实际 EV 一致
            bracketEvSteps = BRACKET_STEPS[0];
            bracketIdx = 1;
            applyEvSteps(b, bracketEvSteps);
            captureSession.setRepeatingRequest(b.build(), resultCallback, cameraHandler);
            status.exposureLabel = "EV包围 " + evText(bracketEvSteps) + "EV";
            mainHandler.postDelayed(bracketTick, 5000);
        }
        if (settings.focusMode == 1) startFocusLock();
        reconnectAttempts = 0;
        reconnecting = false;
    }

    /** Sensor-landscape: when the device is mounted the other way round the frame is 180 deg off. */
    private int displayRotationDegrees() {
        try {
            android.view.Display d = ((android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            return d.getRotation() * 90;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Camera mounting flip: base frame is upright when the phone is "top up". If the phone is
     * physically upside down (gravity says so) or the user ticked "倒装安装", rotate 180 deg.
     */
    private boolean needsUpsideDownFlip() {
        boolean byGravity = settings.autoOrient && gravityY < -4.0f;
        return settings.upsideDownMount ^ byGravity;
    }

    /** Digital zoom via SCALER_CROP_REGION: raises pixel density on the plate at the cost of FOV. */
    private void applyCropRegion(CaptureRequest.Builder b) {
        float z = activeZoom;
        if (z <= 1.001f) return;
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cc = cm.getCameraCharacteristics(String.valueOf(activeCameraId));
            android.graphics.Rect active = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (active == null) return;
            int w = Math.round(active.width() / z);
            int h = Math.round(active.height() / z);
            int cx = active.centerX();
            int cy = active.centerY();
            android.graphics.Rect crop = new android.graphics.Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            b.set(CaptureRequest.SCALER_CROP_REGION, crop);
            Log.i(TAG, "crop region " + z + "x -> " + crop.width() + "x" + crop.height());
        } catch (Throwable t) {
            Log.w(TAG, "crop region failed", t);
        }
    }

    private volatile boolean focusLocked;
    private int activeCameraId;
    private float activeZoom = 1f;
    private volatile long lastResultExposureNs;
    private volatile int lastResultIso;
    private long lastAuxWatermarkMs;

    // ---- road-test telemetry (CSV) / EV bracket ----
    private java.io.BufferedWriter aeWriter;
    private String aeCsvPath;
    private boolean bracketMode;
    private int bracketIdx;
    private int bracketEvSteps;
    private static final int[] BRACKET_STEPS = {1, 0, -1, -2, -3, -4}; // +0.5 .. -2.0 EV
    private volatile double aeMean, aeP50, aeP90, aeP99, aeClip, aePlateP90, aePlateClip;
    private volatile int aeScene = -1;
    private volatile String aeNote = "";
    private volatile String placeName;
    private volatile double geocodedLat = 999;
    private volatile double geocodedLon = 999;
    private volatile long lastGeocodeMs;
    private volatile int diagFrame;
    private final CameraCaptureSession.CaptureCallback resultCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                       android.hardware.camera2.TotalCaptureResult result) {
            Long e = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer i = result.get(CaptureResult.SENSOR_SENSITIVITY);
            if (e != null) lastResultExposureNs = e;
            if (i != null) lastResultIso = i;
            // 水印里的快门/ISO 跟随 CaptureResult 刷新（约 3Hz），而不是等 1s 的状态 tick，
            // 这样 AE 在两次 tick 之间变化时，水印与画面帧对得上。
            long now = System.currentTimeMillis();
            if (settings.watermark && settings.watermarkExposure
                    && now - lastAuxWatermarkMs > 300) {
                lastAuxWatermarkMs = now;
                mainHandler.post(RecordingSession.this::updateWatermark);
            }
            if (settings.diagExposure) {
                int f = diagFrame;
                diagFrame = f + 1;
                if (f % 5 == 0) {
                    Log.d("DashcamDiag", "frame=" + f + " exp_ns=" + e + " iso=" + i);
                }
            }
        }
    };

    // ---- event clips (retained for explicit triggers like ACTION_CLIP / future Bluetooth remote) ----
    private volatile File lastClosedSegment;
    private volatile long lastClosedAtMs;
    private volatile boolean clipNextSegment;
    private volatile String clipBaseName;
    private android.hardware.SensorManager sensorManager;
    private android.hardware.SensorEventListener gravityListener;
    private volatile float gravityY;

    private void startFocusLock() {
        try {
            requestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            captureSession.capture(requestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                               android.hardware.camera2.TotalCaptureResult result) {
                    Integer st = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (st != null && (st == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                            || st == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                            || st == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
                        lockFocus();
                    }
                }
            }, cameraHandler);
            cameraHandler.postDelayed(this::lockFocus, 3000);
        } catch (Throwable t) {
            Log.w(TAG, "focus trigger failed", t);
            lockFocus();
        }
    }

    private void lockFocus() {
        if (focusLocked || !running) return;
        focusLocked = true;
        try {
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);
            requestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            // 必须继续挂 resultCallback：否则锁定对焦后 lastResultExposureNs/ISO 不再更新，
            // 水印（以及自适应 EV 的曝光归一化）会一直用锁定前那一帧的旧值。
            captureSession.setRepeatingRequest(requestBuilder.build(), resultCallback, cameraHandler);
            Log.i(TAG, "focus locked (AF off, lens kept at distance)");
        } catch (Throwable t) {
            Log.w(TAG, "lock focus failed", t);
        }
    }

    /** Applies EV compensation (auto mode) using the device's step size and range. */
    private void applyEvCompensation(CaptureRequest.Builder b) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cc = cm.getCameraCharacteristics(String.valueOf(activeCameraId));
            Range<Integer> range = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            android.util.Rational step = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            float stepEv = step == null ? 1f / 3f : step.floatValue();
            int steps = Math.round(settings.ev() / stepEv);
            if (range != null) {
                steps = Math.max(range.getLower(), Math.min(range.getUpper(), steps));
            }
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps);
            Log.i(TAG, "EV " + settings.ev() + " -> compensation steps " + steps + " (step=" + stepEv + ")");
        } catch (Throwable t) {
            Log.w(TAG, "EV compensation failed", t);
        }
    }

    /** Called for every metering frame; adjusts ISO (shutter priority) at most ~6x per second. */
    private void onMeteringFrame(android.media.ImageReader reader) {
        android.media.Image image = reader.acquireNextImage();
        if (image == null) return;
        try {
            if (plateAutoExposure) {
                handlePlateAe(image);
                return;
            }
            if (adaptiveEv) {
                handleAdaptiveEv(image);
                return;
            }
            if (bracketMode) {
                handleBracketStats(image);
                return;
            }
            android.media.Image.Plane plane = image.getPlanes()[0];
            java.nio.ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int w = image.getWidth();
            int h = image.getHeight();
            long sum = 0;
            long clipped = 0;
            int n = 0;
            long csum = 0;
            int cn = 0;
            long cclip = 0;
            int cx0 = w / 4, cx1 = w * 3 / 4, cy0 = h * 45 / 100, cy1 = h * 85 / 100;
            for (int y = 0; y < h; y += 4) {
                int row = y * rowStride;
                boolean inCy = y >= cy0 && y < cy1;
                for (int x = 0; x < w; x += 4) {
                    int off = row + x * pixelStride;
                    if (off >= buf.limit()) continue;
                    int v = buf.get(off) & 0xFF;
                    sum += v;
                    if (v > 246) clipped++;
                    n++;
                    if (inCy && x >= cx0 && x < cx1) {
                        csum += v;
                        if (v > 238) cclip++;
                        cn++;
                    }
                }
            }
            if (n == 0 || !manualExposure) return;
            float gmean = sum / (float) n;
            float cmean = cn > 0 ? csum / (float) cn : gmean;
            float mean = cn > 0 ? 0.65f * cmean + 0.35f * gmean : gmean;
            float cclipRatio = cn > 0 ? cclip / (float) cn : 0;
            lastLuma = mean;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastIsoAdjustMs < 150) return;
            lastIsoAdjustMs = now;

            float target = 110f * (float) Math.pow(2.0, settings.ev());
            target = Math.max(40f, Math.min(170f, target));
            double ratio = target / Math.max(8f, mean);
            // Deadband: ignore tiny brightness errors so the loop does not hunt.
            if (Math.abs(Math.log(ratio) / Math.log(2)) < 0.08) ratio = 1.0;
            // Plate/highlight priority: retro-reflective plates blow out long before the frame
            // average does, so watch the central band. Three zones avoid a pull/push limit cycle.
            if (settings.highlightPriority) {
                if (cclipRatio > 0.03f) {
                    ratio = Math.min(ratio, 0.70);        // clearly clipping: expose down
                    exposureCeilingNs = (long) (currentExposureNs * 0.88);
                } else if (cclipRatio > 0.002f) {
                    ratio = Math.min(ratio, 1.0);         // some clipping: hold, never push up
                } else if (exposureCeilingNs > 0) {
                    exposureCeilingNs = (long) (exposureCeilingNs * 1.02); // relax slowly
                }
            }
            if (clipped > n * 0.05 && ratio > 1.0) ratio = 1.0;

            long frameNs = 1_000_000_000L / Math.max(1, settings.fps);
            long maxExp = Math.min(settings.shutterNs(), frameNs);   // user ceiling
            long minExp = Math.max(125_000L, frameNs / 64);          // ~1/2000 at 30fps
            if (minExp > maxExp) minExp = maxExp;                    // the user's cap always wins

            double wantExp = currentExposureNs * Math.pow(ratio, 0.65);
            long newExp = Math.max(minExp, Math.min(maxExp, Math.round(wantExp)));
            if (exposureCeilingNs > 0 && newExp > exposureCeilingNs) newExp = Math.max(minExp, exposureCeilingNs);
            double residual = wantExp / (double) newExp;             // what ISO still has to cover
            int max = Math.min(settings.maxIso(), deviceIsoMax);
            int newIso = (int) Math.round(currentIso * Math.max(0.5, Math.min(2.0, residual)));
            if (exposureCeilingNs > 0 && newExp >= exposureCeilingNs && newIso > currentIso) {
                // At the no-clip ceiling: only creep ISO up, so the picture does not pulse.
                newIso = Math.min(newIso, (int) Math.round(currentIso * 1.08));
            }
            newIso = Math.max(Math.max(1, deviceIsoMin), Math.min(max, newIso));

            if (newExp != currentExposureNs || newIso != currentIso) {
                if (Math.abs(newExp - currentExposureNs) > Math.max(200_000L, currentExposureNs / 10)
                        || Math.abs(newIso - currentIso) > Math.max(40, currentIso / 10)) {
                    Log.i(TAG, "exposure " + currentExposureNs + "ns/" + currentIso + " -> "
                            + newExp + "ns/" + newIso + " (luma " + Math.round(mean)
                            + ", target " + Math.round(target) + ", cclip " + cclip + "/" + cn
                            + " (" + Math.round(cclipRatio * 1000) / 10f + "%))");
                }
                currentExposureNs = newExp;
                currentIso = newIso;
                try {
                    requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureNs);
                    requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso);
                    captureSession.setRepeatingRequest(requestBuilder.build(), resultCallback, cameraHandler);
                } catch (Throwable t) {
                    Log.w(TAG, "exposure update failed", t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "metering failed", t);
        } finally {
            image.close();
        }
    }

    private void applyEvSteps(CaptureRequest.Builder b, int steps) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cc = cm.getCameraCharacteristics(String.valueOf(activeCameraId));
            Range<Integer> range = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            int s = steps;
            if (range != null) s = Math.max(range.getLower(), Math.min(range.getUpper(), s));
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s);
        } catch (Throwable t) {
            Log.w(TAG, "applyEvSteps failed", t);
        }
    }

    /** 包围曝光模式只统计 ROI（不控制曝光），让 CSV 每档 EV 都带场景/高光数据。 */
    private void handleBracketStats(android.media.Image image) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastAdaptiveEvMs < 150) return;
        lastAdaptiveEvMs = now;
        try {
            android.media.Image.Plane p0 = image.getPlanes()[0];
            java.nio.ByteBuffer b0 = p0.getBuffer();
            int rowStride = p0.getRowStride();
            int ps = p0.getPixelStride();
            int w = image.getWidth(), h = image.getHeight();
            long sum = 0, clip = 0;
            int n = 0;
            int[] hist = new int[256];
            int x0 = w / 5, x1 = w * 4 / 5, y0 = h * 45 / 100, y1 = h * 92 / 100;
            for (int y = y0; y < y1; y += 2) {
                int row = y * rowStride;
                for (int x = x0; x < x1; x += 2) {
                    int off = row + x * ps;
                    if (off >= b0.capacity()) continue;
                    int v = b0.get(off) & 0xFF;
                    hist[v]++;
                    sum += v;
                    n++;
                    if (v >= 246) clip++;
                }
            }
            if (n == 0) return;
            double p50 = 0, p90 = 0, p99 = 0;
            int acc = 0;
            for (int i = 0; i < 256; i++) {
                acc += hist[i];
                if (p50 == 0 && acc >= n * 0.5) p50 = i;
                if (p90 == 0 && acc >= n * 0.9) p90 = i;
                if (acc >= n * 0.99) { p99 = i; break; }
            }
            double mean = sum / (double) n;
            aeMean = mean; aeP50 = p50; aeP90 = p90; aeP99 = p99;
            aeClip = clip * 100.0 / n; aePlateP90 = 0; aePlateClip = 0;
            double exp = lastResultExposureNs > 0 ? lastResultExposureNs : 16_666_667.0;
            double iso = lastResultIso > 0 ? lastResultIso : 800.0;
            double norm = mean * (16_666_667.0 / exp) * (800.0 / iso);
            aeScene = norm < 80 ? 3 : (norm < 400 ? 2 : 1);
            aeNote = "bracket";
        } catch (Throwable t) {
            Log.w(TAG, "bracket stats failed", t);
        }
    }

    /**
     * 自适应 EV：系统 AE 负责曝光，我们只根据"曝光归一化场景亮度 + 高光裁剪"调整 EV。
     * 目标：夜间自动落到 -1.5 左右保护车牌，白天回到 0 避免车牌发黑，隧道/过渡 -1。
     * 比逐帧手动曝光稳，且不需要车牌检测。
     */
    private void handleAdaptiveEv(android.media.Image image) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastAdaptiveEvMs < 150) return;
        lastAdaptiveEvMs = now;
        try {
            android.media.Image.Plane p0 = image.getPlanes()[0];
            java.nio.ByteBuffer b0 = p0.getBuffer();
            int rowStride = p0.getRowStride();
            int ps = p0.getPixelStride();
            int w = image.getWidth(), h = image.getHeight();
            long sum = 0, clip = 0;
            int n = 0;
            int x0 = w / 5, x1 = w * 4 / 5, y0 = h * 45 / 100, y1 = h * 92 / 100;
            int[] hist = new int[256];
            for (int y = y0; y < y1; y += 2) {
                int row = y * rowStride;
                for (int x = x0; x < x1; x += 2) {
                    int off = row + x * ps;
                    if (off >= b0.capacity()) continue;
                    int v = b0.get(off) & 0xFF;
                    hist[v]++;
                    sum += v;
                    n++;
                    if (v >= 246) clip++;
                }
            }
            if (n == 0) return;
            double p50 = 0, p90 = 0, p99 = 0;
            int acc = 0;
            for (int i = 0; i < 256; i++) {
                acc += hist[i];
                if (p50 == 0 && acc >= n * 0.5) p50 = i;
                if (p90 == 0 && acc >= n * 0.9) p90 = i;
                if (acc >= n * 0.99) { p99 = i; break; }
            }
            double mean = sum / (double) n;
            double clipPct = clip * 100.0 / n;
            aeMean = mean; aeP50 = p50; aeP90 = p90; aeP99 = p99;
            aeClip = clipPct; aePlateP90 = 0; aePlateClip = 0; aeNote = "adaptive";

            // 曝光归一化：折算到 1/60s、ISO800 的亮度（与当前 AE 实际曝光无关）
            double exp = lastResultExposureNs > 0 ? lastResultExposureNs : 16_666_667.0;
            double iso = lastResultIso > 0 ? lastResultIso : 800.0;
            double norm = mean * (16_666_667.0 / exp) * (800.0 / iso);
            if (adaptiveSceneLuma < 0) adaptiveSceneLuma = norm;
            else adaptiveSceneLuma = 0.7 * adaptiveSceneLuma + 0.3 * norm;

            int scene;
            double a = adaptiveSceneLuma;
            if (a < 80) scene = 3;        // night
            else if (a < 400) scene = 2;  // dusk/tunnel
            else scene = 1;               // day
            aeScene = scene;

            int baseSteps;
            switch (scene) {
                case 3: baseSteps = -3; break;   // -1.5EV
                case 2: baseSteps = -2; break;   // -1.0EV
                default: baseSteps = 0; break;   // 0EV
            }
            // 高光反馈：车牌/路灯把 p99 打满且裁剪明显时再压半档（最低 -2.0EV）
            if (clipPct > 6.0 || p99 >= 252) baseSteps = Math.min(baseSteps, -3);
            if (clipPct > 15.0) baseSteps = -4;

            int target = baseSteps;
            if (now - lastAdaptiveLogMs > 1000) {
                lastAdaptiveLogMs = now;
                Log.i("DashcamAEV", "scene=" + scene + " norm=" + Math.round(adaptiveSceneLuma)
                        + " mean=" + Math.round(mean) + " p50=" + Math.round(p50)
                        + " p99=" + Math.round(p99)
                        + " clip=" + String.format(java.util.Locale.US, "%.1f", clipPct)
                        + " exp=" + (lastResultExposureNs / 1000) + "us iso=" + lastResultIso
                        + " ev=" + (adaptiveEvSteps * 0.5f) + " -> " + (target * 0.5f));
            }
            if (target != adaptiveEvSteps) {
                // 每步 0.5EV，最多 1 秒变一次，避免 AE 抖动
                if (now - lastAdaptiveEvStepMs < 800) return;
                lastAdaptiveEvStepMs = now;
                adaptiveEvSteps = target;
                applyEvSteps(requestBuilder, adaptiveEvSteps);
                try {
                    captureSession.setRepeatingRequest(requestBuilder.build(), resultCallback, cameraHandler);
                } catch (Throwable t) {
                    Log.w(TAG, "adaptive EV apply failed", t);
                }
                status.exposureLabel = "自适应 EV " + (adaptiveEvSteps * 0.5f) + "EV";
            }
        } catch (Throwable t) {
            Log.w(TAG, "adaptive EV failed", t);
        }
    }

    /** 车牌优先自适应曝光：960×540 测光 → PlateAeController → 逐帧改曝光/ISO。 */
    private void handlePlateAe(android.media.Image image) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastPlateAeMs < 150) return;
        lastPlateAeMs = now;
        try {
            android.media.Image.Plane p0 = image.getPlanes()[0];
            android.media.Image.Plane p1 = image.getPlanes()[1];
            android.media.Image.Plane p2 = image.getPlanes()[2];
            int w = image.getWidth(), h = image.getHeight();
            int yLen = Math.min(p0.getRowStride() * h, p0.getBuffer().limit());
            int uLen = Math.min(p1.getRowStride() * (h / 2), p1.getBuffer().limit());
            int vLen = Math.min(p2.getRowStride() * (h / 2), p2.getBuffer().limit());
            if (meterY == null || meterY.length < yLen) meterY = new byte[yLen];
            if (meterU == null || meterU.length < uLen) meterU = new byte[uLen];
            if (meterV == null || meterV.length < vLen) meterV = new byte[vLen];
            java.nio.ByteBuffer b0 = p0.getBuffer();
            java.nio.ByteBuffer b1 = p1.getBuffer();
            java.nio.ByteBuffer b2 = p2.getBuffer();
            b0.position(0); b0.limit(Math.min(b0.capacity(), yLen));
            b1.position(0); b1.limit(Math.min(b1.capacity(), uLen));
            b2.position(0); b2.limit(Math.min(b2.capacity(), vLen));
            b0.get(meterY, 0, b0.limit());
            b1.get(meterU, 0, b1.limit());
            b2.get(meterV, 0, b2.limit());

            PlateAeController.Input in = new PlateAeController.Input();
            in.y = meterY;
            in.u = meterU;
            in.v = meterV;
            in.width = w;
            in.height = h;
            in.rowStride = p0.getRowStride();
            in.pixelStride = p0.getPixelStride();
            in.uvRowStride = p1.getRowStride();
            in.uvPixelStride = p1.getPixelStride();
            in.nowMs = now;
            in.exposureNs = currentExposureNs;
            in.iso = currentIso;
            in.speedKph = status.speedKph;
            in.fps = settings.fps;
            in.isoMin = deviceIsoMin;
            in.isoMax = Math.min(settings.maxIso(), deviceIsoMax);

            PlateAeController.Output out = plateAe.onFrame(in);
            aeScene = out.scene;
            aeMean = out.roadP50; aeP50 = out.roadP50; aeP90 = out.roadP90; aeP99 = 0;
            aeClip = out.roadClipPct; aePlateP90 = out.plateP90; aePlateClip = out.plateClipPct;
            aeNote = out.note;
            status.iso = out.iso;
            status.luma = (float) out.roadP50;
            status.exposureLabel = "车牌自适应 " + PlateAeController.sceneName(out.scene)
                    + " " + (out.exposureNs / 1000) + "us/ISO" + out.iso;
            if (out.changed) {
                currentExposureNs = out.exposureNs;
                currentIso = out.iso;
                try {
                    requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureNs);
                    requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso);
                    captureSession.setRepeatingRequest(requestBuilder.build(), resultCallback, cameraHandler);
                } catch (Throwable t) {
                    Log.w(TAG, "plate AE apply failed", t);
                }
            }
            if (now - lastAeLogMs > 1000) {
                lastAeLogMs = now;
                Log.i("DashcamAE", "scene=" + PlateAeController.sceneName(out.scene)
                        + " blocks=" + out.plateBlocks
                        + " plateP90=" + Math.round(out.plateP90)
                        + " clip=" + String.format(java.util.Locale.US, "%.1f", out.plateClipPct)
                        + " roadP90=" + Math.round(out.roadP90)
                        + " amb=" + Math.round(out.ambient)
                        + " sceneLuma=" + Math.round(out.sceneLuma)
                        + " -> " + (out.exposureNs / 1000) + "us/ISO" + out.iso
                        + " target=" + Math.round(out.target)
                        + " note=" + out.note);
            }
        } catch (Throwable t) {
            Log.w(TAG, "plate AE failed", t);
        }
    }

    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (camera != null) {
                camera.close();
                camera = null;
            }
        } catch (Throwable ignored) {
        }
    }

    private void scheduleReconnect(String reason) {
        if (!running || reconnecting) return;
        reconnecting = true;
        reconnectAttempts++;
        status.reconnects = reconnectAttempts;
        status.lastError = reason;
        long delay = Math.min(3000L, 1000L * reconnectAttempts);
        Log.w(TAG, "reconnect in " + delay + "ms (" + reason + ")");
        EventLog.write(ctx, "RECONNECT", reason + " delay=" + delay);
        notifyStatus();
        if (cameraHandler == null) return;
        cameraHandler.postDelayed(() -> cameraExecutor.execute(() -> {
            if (!running) return;
            try {
                closeCamera();
                resolveLens();
                openCameraBlocking();
                GlRenderer r = renderer;
                if (r == null || r.cameraSurface() == null) throw new IllegalStateException("no surface");
                createSessionBlocking(r.cameraSurface());
                status.lastError = "";
                Log.i(TAG, "camera reconnected");
            } catch (Throwable t) {
                Log.e(TAG, "reconnect failed", t);
                reconnecting = false;
                scheduleReconnect("重连失败: " + t.getMessage());
                return;
            }
            notifyStatus();
        }), delay);
    }

    // --------------------------------------------------------------- tick

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                status.elapsedMs = System.currentTimeMillis() - startWallMs;
                if (dir != null) status.freeBytes = StorageManager.freeBytes(dir);
                if (writer != null) status.sessionBytes = closedBytes + writer.currentBytes();
                status.batteryTempC = batteryTemp();
                if (status.elapsedMs % 60000 < 1100) {
                    int[] bs = batteryState();
                    EventLog.write(ctx, "TICK", "elapsed=" + (status.elapsedMs / 1000) + "s battery="
                            + bs[0] + "% status=" + bs[1] + " temp=" + status.batteryTempC
                            + " segs=" + status.segments + " reconnects=" + status.reconnects);
                }
                status.iso = manualExposure
                        ? (lastResultIso > 0 ? lastResultIso : currentIso) : -1;
                maybeGeocode();
                if (manualExposure && lastResultExposureNs > 0) {
                    status.exposureLabel = Settings.SHUTTER_CAP_LABELS[settings.shutterIndex]
                            + " · 实际 " + shutterLabel(lastResultExposureNs);
                }
                status.luma = lastLuma;
                if (dir != null && status.freeBytes > 0 && status.freeBytes < 200L * 1024 * 1024) {
                    status.lastError = "存储空间不足 200MB，请清理";
                } else if (status.batteryTempC > 46f) {
                    status.lastError = "电池温度过高（" + Math.round(status.batteryTempC) + "℃），建议散热";
                } else if (status.lastError != null && (status.lastError.startsWith("存储空间不足")
                        || status.lastError.startsWith("电池温度过高"))) {
                    status.lastError = "";
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                    status.thermalStatus = pm.getCurrentThermalStatus();
                }
                int[] batt = batteryState();
                boolean charging = batt[1] == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                        || batt[1] == android.os.BatteryManager.BATTERY_STATUS_FULL;
                if (!charging && batt[0] >= 0 && batt[0] < 5 && !lowBatteryStopped) {
                    lowBatteryStopped = true;
                    status.lastError = "电量低于 5%，为保护文件已停止录像";
                    EventLog.write(ctx, "LOW_BATTERY", "level=" + batt[0]);
                    notifyStatus();
                    stop();
                    if (listener != null) listener.onError(status.lastError);
                    return;
                }
                updateWatermark();
                writeAeCsvRow();
                watchdog();
                notifyStatus();
            } catch (Throwable t) {
                Log.w(TAG, "tick failed", t);
            } finally {
                if (running) mainHandler.postDelayed(this, 1000);
            }
        }
    };

    private String evText(int steps) {
        return String.format(Locale.US, "%+.1f", steps * 0.5f);
    }

    private void openAeCsv() {
        if (!settings.aeLog || aeWriter != null) return;
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            String name = "ae_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new java.util.Date()) + ".csv";
            java.io.File f = new java.io.File(dir, name);
            aeWriter = new java.io.BufferedWriter(new java.io.FileWriter(f));
            aeWriter.write("wall_ms,elapsed_ms,mode,scene,ev_steps,req_exp_ns,req_iso,"
                    + "act_exp_ns,act_iso,roi_mean,roi_p50,roi_p90,roi_p99,roi_clip_pct,"
                    + "plate_p90,plate_clip_pct,speed_kph,lat,lon,note\n");
            aeWriter.flush();
            aeCsvPath = f.getAbsolutePath();
            Log.i(TAG, "AE csv: " + aeCsvPath);
        } catch (Throwable t) {
            Log.w(TAG, "open AE csv failed", t);
            aeWriter = null;
        }
    }

    private void writeAeCsvRow() {
        if (!settings.aeLog) return;
        if (aeWriter == null) openAeCsv();
        if (aeWriter == null) return;
        try {
            String mode;
            int evSteps;
            if (bracketMode) {
                mode = "bracket";
                evSteps = bracketEvSteps;
                aeNote = "bracket";
            } else if (plateAutoExposure) {
                mode = "plate";
                evSteps = 0;
            } else if (adaptiveEv) {
                mode = "adaptive";
                evSteps = adaptiveEvSteps;
            } else if (manualExposure) {
                mode = "manual";
                evSteps = settings.evIndex - 4;
            } else {
                mode = "auto";
                evSteps = settings.evIndex - 4;
            }
            boolean aeMode = bracketMode || adaptiveEv || (!manualExposure && !plateAutoExposure);
            long reqExp = aeMode ? lastResultExposureNs : currentExposureNs;
            int reqIso = aeMode ? lastResultIso : currentIso;
            aeWriter.write(System.currentTimeMillis() + "," + status.elapsedMs + ","
                    + mode + "," + aeScene + "," + evSteps + ","
                    + reqExp + "," + reqIso + ","
                    + lastResultExposureNs + "," + lastResultIso + ","
                    + String.format(Locale.US, "%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f",
                    aeMean, aeP50, aeP90, aeP99, aeClip, aePlateP90, aePlateClip) + ","
                    + String.format(Locale.US, "%.1f", status.speedKph) + ","
                    + status.lat + "," + status.lon + "," + aeNote.replace(',', ';'));
            aeWriter.newLine();
            aeWriter.flush();
        } catch (Throwable t) {
            Log.w(TAG, "write AE csv failed", t);
        }
    }

    private void closeAeCsv() {
        if (aeWriter == null) return;
        try {
            aeWriter.flush();
            aeWriter.close();
        } catch (Throwable ignored) {
        }
        aeWriter = null;
        Log.i(TAG, "AE csv closed: " + aeCsvPath);
    }

    private final Runnable bracketTick = new Runnable() {
        @Override
        public void run() {
            if (!running || !bracketMode) return;
            bracketEvSteps = BRACKET_STEPS[bracketIdx];
            bracketIdx = (bracketIdx + 1) % BRACKET_STEPS.length;
            try {
                applyEvSteps(requestBuilder, bracketEvSteps);
                captureSession.setRepeatingRequest(requestBuilder.build(), resultCallback, cameraHandler);
                status.exposureLabel = "EV包围 " + evText(bracketEvSteps) + "EV";
            } catch (Throwable t) {
                Log.w(TAG, "EV bracket apply failed", t);
            }
            mainHandler.postDelayed(this, 5000);
        }
    };

    private void updateWatermark() {
        GlRenderer r = renderer;
        if (r == null) return;
        if (!settings.watermark) {
            r.setOverlay(null);
            return;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        String time = sdf.format(new Date());
        String coord = status.gpsFix
                ? String.format(Locale.US, "%.6fN %.6fE", status.lat, status.lon)
                : "GPS 定位中";
        // Main info left-aligned (time + coords [+ place]); shutter/ISO right-aligned,
        // in a smaller font because it is auxiliary information.
        StringBuilder main = new StringBuilder();
        main.append(time).append("   ").append(coord);
        if (settings.watermarkPlace && placeName != null && !placeName.isEmpty()) {
            main.append("   ").append(placeName);
        }
        String aux = null;
        if (settings.watermarkExposure && lastResultExposureNs > 0 && lastResultIso > 0) {
            aux = shutterLabel(lastResultExposureNs) + " ISO " + lastResultIso;
            if (bracketMode) aux += " EV" + evText(bracketEvSteps);
        }
        r.setOverlay(main.toString(), aux);
    }

    private void geocodeAsync(final double lat, final double lon) {
        new Thread(() -> {
            try {
                android.location.Geocoder g = new android.location.Geocoder(ctx, Locale.CHINA);
                java.util.List<android.location.Address> addrs = g.getFromLocation(lat, lon, 1);
                if (addrs != null && !addrs.isEmpty()) {
                    android.location.Address a = addrs.get(0);
                    StringBuilder sb = new StringBuilder();
                    if (a.getLocality() != null) sb.append(a.getLocality());
                    if (a.getSubLocality() != null && !sb.toString().contains(a.getSubLocality()))
                        sb.append(a.getSubLocality());
                    if (a.getThoroughfare() != null) sb.append(a.getThoroughfare());
                    String name = sb.toString();
                    if (!name.isEmpty()) {
                        placeName = name;
                        geocodedLat = lat;
                        geocodedLon = lon;
                        status.placeName = name;
                        EventLog.write(ctx, "GEOCODE", name);
                        Log.i(TAG, "geocode: " + name);
                    }
                } else {
                    Log.w(TAG, "geocode returned no address");
                }
            } catch (Throwable t) {
                Log.w(TAG, "geocode failed: " + t);
            }
        }, "geocode").start();
    }

    /** 1/500 style label from an exposure time in nanoseconds. */
    private String shutterLabel(long ns) {
        if (ns <= 0) return "?";
        double inv = 1e9 / ns;
        // 优先匹配常见快门档（相机 HAL 报的 29.97ms/33.33ms 等会被显示成 1/30）
        int[] std = {2000, 1000, 500, 250, 200, 125, 100, 60, 50, 40, 30, 25, 24, 20, 15, 12, 10, 8, 6, 5, 4, 3, 2, 1};
        for (int v : std) {
            if (Math.abs(inv - v) / (double) v < 0.035) return "1/" + v;
        }
        if (inv >= 1.0) return String.format(Locale.US, "1/%.0f", inv);
        return String.format(Locale.US, "%.1fs", ns / 1e9);
    }

    /** Reverse-geocodes the current fix in the background (throttled, cached). */
    private void maybeGeocode() {
        if (!settings.watermarkPlace || !status.gpsFix) return;
        long now = System.currentTimeMillis();
        if (now - lastGeocodeMs < 20_000) return;
        boolean moved = Math.abs(status.lat - geocodedLat) > 0.003
                || Math.abs(status.lon - geocodedLon) > 0.003;
        if (placeName != null && !moved && now - lastGeocodeMs < 5 * 60_000) return;
        lastGeocodeMs = now;
        geocodeAsync(status.lat, status.lon);
    }

    private void watchdog() {
        GlRenderer r = renderer;
        if (r == null || !running) return;
        long last = r.lastFrameElapsedMs();
        if (last > 0 && SystemClock.elapsedRealtime() - last > 4000) {
            Log.w(TAG, "watchdog: no frames for " + (SystemClock.elapsedRealtime() - last) + "ms");
            scheduleReconnect("无画面，重启摄像头");
        }
    }

    private int[] batteryState() {
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent i = ctx.registerReceiver(null, f);
            if (i != null) {
                return new int[]{i.getIntExtra("level", -1), i.getIntExtra("status", -1)};
            }
        } catch (Throwable ignored) {
        }
        return new int[]{-1, -1};
    }

    private float batteryTemp() {
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent i = ctx.registerReceiver(null, f);
            if (i != null) return i.getIntExtra("temperature", -1) / 10f;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private void notifyStatus() {
        final Status s = status.copy();
        mainHandler.post(() -> {
            if (listener != null) listener.onStatusChanged(s);
        });
    }

    // ------------------------------------------------------------- sinks

    private final SegmentWriter.Listener writerListener = new SegmentWriter.Listener() {
        @Override
        public void onSegmentStarted(File file) {
            status.file = file.getName();
            ctx.getSharedPreferences("dashcam_runtime", Context.MODE_PRIVATE).edit()
                    .putString("open_segment", file.getName()).apply();
        }

        @Override
        public void onSegmentClosed(File file, long bytes, long durationMs, long frames) {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("dashcam_runtime", Context.MODE_PRIVATE);
            if (file.getName().equals(sp.getString("open_segment", ""))) {
                sp.edit().remove("open_segment").apply();
            }
            status.segments++;
            EventLog.write(ctx, "SEGMENT", file.getName() + " " + durationMs + "ms " + bytes + "B frames=" + frames);
            lastClosedSegment = file;
            lastClosedAtMs = System.currentTimeMillis();
            if (clipNextSegment) {
                clipNextSegment = false;
                saveEventClip(file, clipBaseName + "_b.mp4");
            }
            closedBytes += bytes;
            status.sessionBytes = closedBytes;
            mainHandler.post(() -> {
                StorageManager.scan(ctx, file);   // appear in the system gallery
                if (listener != null) listener.onSegmentClosed(file, bytes, durationMs);
            });
        }

        @Override
        public void onQuotaCleanup(long freedBytes, int remainingFiles) {
            Log.i(TAG, "quota cleanup freed " + freedBytes + " files=" + remainingFiles);
        }
    };

    private final VideoEncoder.Sink videoSink = new VideoEncoder.Sink() {
        @Override
        public void onVideoFormat(MediaFormat format) {
            if (writer != null) writer.onVideoFormat(format);
        }

        @Override
        public void onVideoSample(java.nio.ByteBuffer buffer, MediaCodec.BufferInfo info) {
            if (writer != null) writer.writeVideo(buffer, info);
        }
    };

}
