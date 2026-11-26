package icu.wwj.drive.dashcam;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.File;

/** Foreground service that keeps recording while the screen is off. */
public class RecordService extends Service {

    private static final String TAG = "DashcamService";

    public static final String ACTION_START = "icu.wwj.drive.dashcam.action.START";
    public static final String ACTION_STOP = "icu.wwj.drive.dashcam.action.STOP";
    public static final String ACTION_CLIP = "icu.wwj.drive.dashcam.action.CLIP";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private RecordingSession session;
    private volatile Status lastStatus = new Status();
    private long lastNotifyMs;
    private boolean foregroundStarted;
    private boolean userStopped;
    private static volatile boolean running;

    public static boolean isServiceRunning() {
        return running;
    }

    private android.content.SharedPreferences runtime() {
        return getSharedPreferences("dashcam_runtime", MODE_PRIVATE);
    }

    public class LocalBinder extends Binder {
        public RecordService service() {
            return RecordService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        WatchdogReceiver.schedule(this);
        lastStatus = new Status();
        lastStatus.resolution = Settings.load(this).label();
        new Thread(() -> StorageManager.migrateLegacy(this), "migrate").start();
        Log.i(TAG, "service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onStartCommand action=" + action + " startId=" + startId);
        if (intent != null && intent.hasExtra("geotest")) {
            final String spec = intent.getStringExtra("geotest");
            new Thread(() -> {
                try {
                    String[] p = spec.split(",");
                    android.location.Geocoder g = new android.location.Geocoder(this, java.util.Locale.CHINA);
                    java.util.List<android.location.Address> a =
                            g.getFromLocation(Double.parseDouble(p[0]), Double.parseDouble(p[1]), 1);
                    if (a != null && !a.isEmpty()) {
                        android.location.Address ad = a.get(0);
                        Log.i("DashcamGeo", "geotest ok: " + ad.getAddressLine(0)
                                + " | locality=" + ad.getLocality() + " sub=" + ad.getSubLocality()
                                + " thoroughfare=" + ad.getThoroughfare());
                    } else {
                        Log.w("DashcamGeo", "geotest: empty result");
                    }
                } catch (Throwable t2) {
                    Log.w("DashcamGeo", "geotest failed", t2);
                }
            }, "geotest").start();
        }
        EventLog.write(this, "SERVICE_CMD", String.valueOf(action));

        // Always call startForeground() first: a STOP intent may arrive via
        // startForegroundService() (scripts / tests), and Android kills the process if
        // startForeground() is not called within 5s.
        startForegroundInternal();

        if (ACTION_CLIP.equals(action)) {
            Log.i(TAG, "ACTION_CLIP received (event triggers currently disabled pending Bluetooth remote)");
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            userStopped = true;
            stopRecording();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            userStopped = false;
            startRecording(intent);
        } else if (action == null) {
            // Sticky restart after being killed while recording: resume.
            if (!userStopped) startRecording(null);
        }
        return START_STICKY;
    }

    private void startForegroundInternal() {
        if (foregroundStarted) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.build(this, lastStatus));
        foregroundStarted = true;
    }

    private void updateNotification() {
        if (!foregroundStarted) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NotificationHelper.NOTIFICATION_ID, NotificationHelper.build(this, lastStatus));
        } catch (Throwable t) {
            Log.w(TAG, "notify failed", t);
        }
    }

    public void startRecording() {
        startRecording(null);
    }

    /** Optional intent extras let automation override settings for a single session. */
    private static void applyOverrides(Settings s, android.content.Intent i) {
        if (i == null) return;
        s.width = i.getIntExtra("width", s.width);
        s.height = i.getIntExtra("height", s.height);
        s.fps = i.getIntExtra("fps", s.fps);
        s.bitrateMbps = i.getIntExtra("bitrateMbps", s.bitrateMbps);
        s.segmentSecs = i.getIntExtra("segmentSecs", s.segmentSecs);
        s.quotaMb = i.getIntExtra("quotaMb", s.quotaMb);
        s.cameraIdx = i.getIntExtra("cameraIdx", s.cameraIdx);
        s.watermark = i.getBooleanExtra("watermark", s.watermark);
        s.watermarkExposure = i.getBooleanExtra("wmExposure", s.watermarkExposure);
        s.watermarkPlace = i.getBooleanExtra("wmPlace", s.watermarkPlace);
        s.exposureMode = i.getIntExtra("exposureMode", s.exposureMode);
        s.shutterIndex = i.getIntExtra("shutterIndex", s.shutterIndex);
        int isoIdx = i.getIntExtra("maxIsoIndex", -1);
        if (isoIdx >= 0) s.maxIso = Settings.MAX_ISO[Math.min(Settings.MAX_ISO.length - 1, isoIdx)];
        s.maxIso = i.getIntExtra("maxIso", s.maxIso);
        s.evIndex = i.getIntExtra("evIndex", s.evIndex);
        s.focusMode = i.getIntExtra("focusMode", s.focusMode);
        s.zoomIndex = i.getIntExtra("zoomIndex", s.zoomIndex);
        s.autoLens = i.getBooleanExtra("autoLens", s.autoLens);
        s.eis = i.getBooleanExtra("eis", s.eis);
        s.upsideDownMount = i.getBooleanExtra("upsideDown", s.upsideDownMount);
        s.sharpenLevel = i.getIntExtra("sharpenLevel", s.sharpenLevel);
        s.allow4k = i.getBooleanExtra("allow4k", false);
        s.diagExposure = i.getBooleanExtra("diag", false);
        s.aeLog = i.getBooleanExtra("aeLog", s.aeLog);
        s.evBracket = i.getBooleanExtra("evBracket", s.evBracket);
        s.outDirOverride = i.getStringExtra("outDir");
    }

    public void startRecording(android.content.Intent intent) {
        if (session != null && session.isRunning()) {
            Log.i(TAG, "already recording");
            return;
        }
        runtime().edit().putBoolean("should_record", true).apply();
        StorageManager.cleanupUnfinished(this);
        final Settings settings = Settings.load(this);
        applyOverrides(settings, intent);
        Log.i(TAG, "start recording with " + settings.label() + " seg=" + settings.segmentSecs
                + "s quota=" + settings.quotaMb + "MB");
        lastStatus.resolution = settings.label();
        final RecordingSession s = new RecordingSession(this, settings, listener);
        session = s;
        new Thread(() -> {
            s.start();
            mainHandler.post(this::updateNotification);
        }, "dashcam-start").start();
    }

    public void stopRecording() {
        runtime().edit().putBoolean("should_record", false).apply();
        final RecordingSession s = session;
        session = null;
        if (s == null) {
            shutdownService();
            return;
        }
        new Thread(() -> {
            try {
                s.stop();
            } catch (Throwable t) {
                Log.w(TAG, "stop failed", t);
            }
            mainHandler.post(() -> {
                lastStatus = s.snapshot();
                lastStatus.recording = false;
                updateNotification();
                shutdownService();
            });
        }, "dashcam-stop").start();
    }

    private void shutdownService() {
        mainHandler.post(() -> {
            try {
                stopForeground(true);
            } catch (Throwable ignored) {
            }
            foregroundStarted = false;
            stopSelf();
        });
    }

    public Status getStatus() {
        RecordingSession s = session;
        if (s != null) return s.snapshot();
        return lastStatus;
    }

    public long lastPreviewDrawMs() {
        RecordingSession s = session;
        return s == null ? 0 : s.lastPreviewDrawMs();
    }

    public boolean isRecording() {
        RecordingSession s = session;
        return s != null && s.isRunning();
    }

    public void attachPreview(Surface surface) {
        attachPreview(surface, 0, 0);
    }

    public void attachPreview(Surface surface, int viewW, int viewH) {
        RecordingSession s = session;
        if (s != null) s.setPreviewSurface(surface, viewW, viewH);
    }

    public void detachPreview() {
        RecordingSession s = session;
        if (s != null) s.setPreviewSurface(null);
    }

    private final RecordingSession.Listener listener = new RecordingSession.Listener() {
        @Override
        public void onStatusChanged(Status status) {
            lastStatus = status;
            long now = System.currentTimeMillis();
            if (status.recording && now - lastNotifyMs > 5000) {
                lastNotifyMs = now;
                updateNotification();
            }
        }

        @Override
        public void onSegmentClosed(File file, long bytes, long durationMs) {
            Log.i(TAG, "segment closed " + file.getName() + " " + bytes + "B " + durationMs + "ms");
        }

        @Override
        public void onError(String message) {
            Log.e(TAG, "recording error: " + message);
            lastStatus.lastError = message;
            updateNotification();
        }
    };

    @Override
    public void onDestroy() {
        Log.i(TAG, "service destroyed");
        EventLog.write(this, "SERVICE_DESTROYED", "running=" + running);
        running = false;
        RecordingSession s = session;
        session = null;
        if (s != null) {
            try {
                s.stop();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }
}
