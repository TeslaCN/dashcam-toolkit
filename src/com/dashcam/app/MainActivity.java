package com.dashcam.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 100;

    private TextView statusText;
    private TextView hintText;
    private TextView previewHint;
    private View previewCover;
    private View blackoutView;
    private TextView blackoutHint;
    private boolean blackoutActive;
    private boolean blackoutDismissed;
    private boolean manualBlackout;
    private boolean settingsDialogVisible;
    private SurfaceView preview;
    private Button actionButton;
    private Button previewButton;
    private boolean previewEnabled = true;
    private RecordService service;
    private boolean bound;
    private boolean previewAttached;
    private Surface previewSurface;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            poll();
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RecordService.LocalBinder) binder).service();
            bound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        previewEnabled = Settings.load(this).showPreview;
        buildUi();
        requestPermsIfNeeded();
    }

    private float backSwipeStartX;
    private float backSwipeStartY;
    private long backSwipeStartMs;
    private boolean backSwipeTracking;

    /**
     * In-app edge-swipe-back. Some ROMs never turn an edge swipe into a system back gesture for
     * fullscreen/landscape apps, so we recognise it ourselves and treat it exactly like back.
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        int w = getResources().getDisplayMetrics().widthPixels;
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN: {
                float x = ev.getX();
                backSwipeStartX = x;
                backSwipeStartY = ev.getY();
                backSwipeStartMs = android.os.SystemClock.elapsedRealtime();
                backSwipeTracking = x < dp(40) || x > w - dp(40);
                if (backSwipeTracking) {
                    EventLog.write(this, "EDGE_TOUCH", "x=" + Math.round(x) + " w=" + w);
                }
                break;
            }
            case android.view.MotionEvent.ACTION_MOVE: {
                if (backSwipeTracking) {
                    float dx = ev.getX() - backSwipeStartX;
                    float dy = Math.abs(ev.getY() - backSwipeStartY);
                    boolean inward = backSwipeStartX < dp(40) ? dx > dp(70) : dx < -dp(70);
                    if (inward && dy < dp(90) && !settingsDialogVisible) {
                        backSwipeTracking = false;
                        long dt = android.os.SystemClock.elapsedRealtime() - backSwipeStartMs;
                        if (dt < 900) {
                            EventLog.write(this, "EDGE_BACK_SWIPE", "dt=" + dt);
                            onBackPressed();
                            return true;
                        }
                    }
                }
                break;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                backSwipeTracking = false;
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() {
        EventLog.write(this, "UI_BACK", "blackout=" + blackoutActive + " recording="
                + (service != null && service.isRecording()));
        if (blackoutActive) {
            // Back first leaves the black screen; a second back exits the app (recording continues).
            blackoutDismissed = true;
            manualBlackout = false;
            applyDrivingMode(false);
            Toast.makeText(this, "已退出黑屏（再按返回可退出界面，录像继续）", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindService(new Intent(this, RecordService.class), conn, Context.BIND_AUTO_CREATE);
        handler.postDelayed(pollTask, 300);
        autoResumeIfNeeded();
    }

    /** If the app was recording before a reboot/kill but the boot broadcast was blocked, resume on open. */
    private void autoResumeIfNeeded() {
        boolean shouldRecord = getSharedPreferences("dashcam_runtime", MODE_PRIVATE)
                .getBoolean("should_record", false);
        if (!shouldRecord) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        handler.postDelayed(() -> {
            if (service != null && service.isRecording()) return;
            Intent svc = new Intent(this, RecordService.class).setAction(RecordService.ACTION_START);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        }, 1500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(pollTask);
        if (service != null) service.detachPreview();
        previewAttached = false;
        if (bound) {
            try {
                unbindService(conn);
            } catch (Throwable ignored) {
            }
        }
        bound = false;
        service = null;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.parseColor("#0B0F14"));
        getWindow().setNavigationBarColor(Color.parseColor("#0B0F14"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0F14"));

        // status
        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#E6EDF3"));
        statusText.setTextSize(14);
        statusText.setTypeface(android.graphics.Typeface.MONOSPACE);
        statusText.setPadding(dp(16), dp(12), dp(16), dp(4));
        root.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        hintText = new TextView(this);
        hintText.setTextColor(Color.parseColor("#FF8A80"));
        hintText.setTextSize(12);
        hintText.setPadding(dp(16), 0, dp(16), dp(4));
        hintText.setVisibility(View.GONE);
        root.addView(hintText);

        // preview
        FrameLayout previewBox = new FrameLayout(this);
        preview = new SurfaceView(this);
        previewBox.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        previewCover = new View(this);
        previewCover.setBackgroundColor(Color.BLACK);
        previewCover.setVisibility(View.GONE);
        previewBox.addView(previewCover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        previewHint = new TextView(this);
        previewHint.setText("点击下方「开始录像」");
        previewHint.setTextColor(Color.parseColor("#8B949E"));
        previewHint.setTextSize(16);
        previewBox.addView(previewHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        root.addView(previewBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                previewSurface = holder.getSurface();
                previewAttached = false;
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                previewSurface = holder.getSurface();
                previewAttached = false;
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                previewSurface = null;
                previewAttached = false;
                if (service != null) service.detachPreview();
            }
        });

        // buttons
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(16));

        actionButton = makeButton("开始录像", "#2EA043");
        actionButton.setOnClickListener(v -> toggleRecording());
        bar.addView(actionButton, weightParams());

        previewButton = makeButton(previewEnabled ? "预览：开" : "预览：关", "#30363D");
        previewButton.setOnClickListener(v -> togglePreview());
        bar.addView(previewButton, weightParams());

        Button settingsBtn = makeButton("设置", "#30363D");
        settingsBtn.setOnClickListener(v -> showSettings());
        bar.addView(settingsBtn, weightParams());

        Button blackoutBtn = makeButton("黑屏", "#30363D");
        blackoutBtn.setOnClickListener(v -> {
            if (service == null || !service.isRecording()) {
                Toast.makeText(this, "请先开始录像", Toast.LENGTH_SHORT).show();
                return;
            }
            manualBlackout = !manualBlackout;
            blackoutDismissed = false;
            applyDrivingMode(true);
            if (manualBlackout) Toast.makeText(this, "已进入黑屏常亮，长按屏幕退出", Toast.LENGTH_SHORT).show();
        });
        bar.addView(blackoutBtn, weightParams());

        Button filesBtn = makeButton("录像文件", "#30363D");
        filesBtn.setOnClickListener(v -> startActivity(new Intent(this, ClipsActivity.class)));
        bar.addView(filesBtn, weightParams());

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout container = new FrameLayout(this);
        container.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        blackoutView = buildBlackout();
        container.addView(blackoutView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(container);
    }

    /** Full-black, kept-on screen for driving: OLED black saves power and the screen never locks. */
    private View buildBlackout() {
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(Color.BLACK);
        box.setVisibility(View.GONE);
        blackoutHint = new TextView(this);
        blackoutHint.setText("行车模式：长按 2 秒或按返回键退出");
        blackoutHint.setTextColor(Color.parseColor("#26FFFFFF"));
        blackoutHint.setTextSize(14);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        box.addView(blackoutHint, lp);
        box.setOnClickListener(v -> {
            blackoutHint.setTextColor(Color.parseColor("#66FFFFFF"));
            handler.postDelayed(() -> blackoutHint.setTextColor(Color.parseColor("#26FFFFFF")), 2500);
        });
        box.setOnLongClickListener(v -> {
            exitBlackoutForGood();
            return true;
        });
        return box;
    }

    private void exitBlackoutForGood() {
        blackoutDismissed = true;
        manualBlackout = false;
        Settings st = Settings.load(this);
        st.drivingMode = 0;
        st.save(this);
        applyDrivingMode(false);
        Toast.makeText(this, "已退出黑屏常亮（可在设置里重新开启）", Toast.LENGTH_SHORT).show();
    }

    private void applyDrivingMode(boolean recording) {
        boolean want = recording && !blackoutDismissed
                && (manualBlackout || Settings.load(this).drivingMode == 1);
        if (want == blackoutActive) return;
        blackoutActive = want;
        if (blackoutView == null) return;
        if (want) {
            blackoutView.setVisibility(View.VISIBLE);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 0.01f;
            getWindow().setAttributes(lp);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
            // Hide only the status bar: the navigation bar / gesture area stays usable so the
            // user can still swipe back or go home while the screen is black.
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            if (service != null) service.detachPreview();
            previewAttached = false;
            android.util.Log.i("DashcamUi", "driving mode: black screen ON (keep screen on)");
            EventLog.write(this, "BLACKOUT_ON", "keepScreenOn");
        } else {
            blackoutView.setVisibility(View.GONE);
            blackoutHint.setTextColor(Color.parseColor("#26FFFFFF"));
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().setStatusBarColor(Color.parseColor("#0B0F14"));
            getWindow().setNavigationBarColor(Color.parseColor("#0B0F14"));
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            previewAttached = false;
            android.util.Log.i("DashcamUi", "driving mode: black screen OFF");
            EventLog.write(this, "BLACKOUT_OFF", "");
        }
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private Button makeButton(String text, String color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(color));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setPadding(dp(8), dp(10), dp(8), dp(10));
        return b;
    }

    private void toggleRecording() {
        if (service != null && service.isRecording()) {
            blackoutDismissed = false;
            startService(new Intent(this, RecordService.class).setAction(RecordService.ACTION_STOP));
            Toast.makeText(this, "正在停止…", Toast.LENGTH_SHORT).show();
        } else {
            if (getSharedPreferences("dashcam_settings", MODE_PRIVATE).getBoolean("first_run", true)) {
                getSharedPreferences("dashcam_settings", MODE_PRIVATE).edit()
                        .putBoolean("first_run", false).apply();
                maybeShowBatteryDialog();
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(new Intent(this, RecordService.class)
                        .setAction(RecordService.ACTION_START));
            } else {
                startService(new Intent(this, RecordService.class)
                        .setAction(RecordService.ACTION_START));
            }
            Toast.makeText(this, "开始录像", Toast.LENGTH_SHORT).show();
        }
    }

    private void togglePreview() {
        previewEnabled = !previewEnabled;
        Settings st = Settings.load(this);
        st.showPreview = previewEnabled;
        st.save(this);
        previewButton.setText(previewEnabled ? "预览：开" : "预览：关");
        previewAttached = false;
        if (!previewEnabled && service != null) {
            service.detachPreview();
        }
        renderStatus(service != null ? service.getStatus() : new Status());
    }

    private void poll() {
        handler.postDelayed(pollTask, 1000);
        Status s;
        if (service != null) {
            s = service.getStatus();
            if (previewEnabled && !blackoutActive && service.isRecording()
                    && previewSurface != null && !previewAttached) {
                service.attachPreview(previewSurface, preview.getWidth(), preview.getHeight());
                previewAttached = true;
                android.util.Log.i("DashcamUi", "preview attached from activity");
            }
        } else {
            s = new Status();
            s.resolution = Settings.load(this).label();
            s.freeBytes = StorageManager.freeBytes(StorageManager.recordingsDir(this));
            s.quotaBytes = Settings.load(this).quotaBytes();
        }
        renderStatus(s);
    }

    private void renderStatus(Status s) {
        StringBuilder sb = new StringBuilder();
        if (s.recording) {
            sb.append("● 录像中  ").append(NotificationHelper.formatElapsed(s.elapsedMs)).append('\n');
        } else {
            sb.append("○ 未录像\n");
        }
        sb.append(s.resolution).append(" · ").append(Settings.load(this).segmentSecs).append("s/段");
        if (s.recording) {
            sb.append(" · ").append(s.segments).append(" 段 · ")
                    .append(NotificationHelper.formatSize(s.sessionBytes));
        }
        sb.append('\n');
        if (s.freeBytes > 0) {
            sb.append("剩余空间 ").append(NotificationHelper.formatSize(s.freeBytes));
        }
        if (s.quotaBytes > 0) {
            sb.append(" · 配额 ").append(NotificationHelper.formatSize(s.quotaBytes));
        }
        sb.append('\n');
        if (s.gpsFix) {
            sb.append(String.format(java.util.Locale.US, "GPS %.6fN %.6fE · %dkm/h",
                    s.lat, s.lon, Math.round(s.speedKph)));
        } else {
            sb.append("GPS 定位中…");
        }
        if (s.batteryTempC > 0) {
            sb.append(" · 电池 ").append(String.format(java.util.Locale.US, "%.1f℃", s.batteryTempC));
        }
        if (s.reconnects > 0) {
            sb.append(" · 重连 ").append(s.reconnects);
        }
        if (s.exposureLabel != null && !s.exposureLabel.isEmpty()) {
            sb.append('\n').append("曝光 ").append(s.exposureLabel);
            if (s.iso > 0) sb.append(" · ISO ").append(s.iso);
            if (s.luma > 0) sb.append(" · 亮度 ").append(Math.round(s.luma));
        }
        statusText.setText(sb.toString());
        if (previewCover != null) {
            previewCover.setVisibility(previewEnabled ? View.GONE : View.VISIBLE);
        }
        if (previewHint != null) {
            if (!previewEnabled) {
                previewHint.setText(s.recording ? "预览已关闭（仍在录像）" : "预览已关闭");
                previewHint.setVisibility(View.VISIBLE);
            } else if (!s.recording) {
                previewHint.setText("点击下方「开始录像」");
                previewHint.setVisibility(View.VISIBLE);
            } else {
                previewHint.setVisibility(View.GONE);
            }
        }
        applyDrivingMode(s.recording);
        actionButton.setText(s.recording ? "停止录像" : "开始录像");
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(s.recording ? "#DA3633" : "#2EA043"));
        bg.setCornerRadius(dp(10));
        actionButton.setBackground(bg);

        if (s.lastError != null && !s.lastError.isEmpty()) {
            hintText.setText("⚠ " + s.lastError);
            hintText.setVisibility(View.VISIBLE);
        } else {
            hintText.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------- settings

    private void showSettings() {
        final Settings s = Settings.load(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        // ---- 视频格式：分辨率（设备真实支持）+ 帧率 放在一起 ----
        final int[][] sizes = deviceVideoSizes();
        String[] resLabels = new String[sizes.length];
        for (int i = 0; i < sizes.length; i++) resLabels[i] = sizes[i][0] + "×" + sizes[i][1];
        final Spinner resSpinner = addSpinner(box, "分辨率（设备支持的尺寸）", resLabels, indexOfSize(sizes, s.width, s.height));
        final Spinner fpsSpinner = addSpinner(box, "帧率", Settings.FPS_LABELS, indexOf(Settings.FPS_OPTIONS, s.fps));

        final TextView formatLine = new TextView(this);
        formatLine.setTextColor(Color.parseColor("#8B949E"));
        formatLine.setTextSize(12);
        formatLine.setPadding(dp(4), dp(2), dp(4), dp(8));
        box.addView(formatLine);

        // ---- 码率 / 段长 / 配额：可直接输入 ----
        final Runnable[] updf = new Runnable[1];
        final int[] bitrateMbps = {s.bitrateMbps};
        final Button brButton = makeButton("码率：" + bitrateMbps[0] + " Mbps", "#21262D");
        brButton.setAllCaps(false);
        brButton.setOnClickListener(v -> askNumber("码率（Mbps）", "建议：\n"
                        + "· 1920×1080：12–16 Mbps\n· 2560×1080：20–30 Mbps\n· 3648×2056：35–45 Mbps\n· 3840×2160：45–60 Mbps\n"
                        + "HEVC 比 H.264 省 25–50%。\n注意：≥250 万像素的画面会自动提升到 30 Mbps，≥700 万像素提升到 45 Mbps。",
                bitrateMbps[0], 2, 200, value -> {
                    bitrateMbps[0] = value;
                    brButton.setText("码率：" + value + " Mbps");
                    // refresh summary below
                    if (updf[0] != null) updf[0].run();
                }));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(4), 0, dp(4));
        box.addView(brButton, blp);

        final Spinner segSpinner = addSpinner(box, "单段时长", labels(Settings.SEGMENTS, "秒"), indexOf(Settings.SEGMENTS, s.segmentSecs));

        final int[] quotaMb = {s.quotaMb};
        final double freeGb = freeSpaceGb();
        final int quotaMax = freeGb > 2 ? (int) Math.floor(freeGb - 1) : 4000;
        final String freeText = freeGb > 0 ? String.format(Locale.US, "手机剩余 %.1f GB", freeGb) : "无法读取剩余空间";
        final Button quotaButton = makeButton("存储配额：" + formatGb(quotaMb[0]) + "（" + freeText + "）", "#21262D");
        quotaButton.setAllCaps(false);
        quotaButton.setOnClickListener(v -> askNumber("存储配额（GB，1–" + quotaMax + "）",
                freeText + "。超出后自动删除最旧的录像（事件片段不会被删）。\n"
                        + "建议不超过剩余空间的 80%；4K 约 20GB/小时，1080P 约 10GB/小时。",
                Math.round(quotaMb[0] / 1024f), 1, quotaMax, value -> {
                    quotaMb[0] = value * 1024;
                    quotaButton.setText("存储配额：" + formatGb(quotaMb[0]) + "（" + freeText + "）");
                }));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.setMargins(0, dp(4), 0, dp(8));
        box.addView(quotaButton, qlp);

        // Camera labels show the real optical zoom measured from the HAL (device-specific).
        String[] camLabels = new String[Settings.CAMERA_IDS.length];
        for (int i = 0; i < camLabels.length; i++) {
            int id = Settings.CAMERA_IDS[i];
            String name = id == 0 ? "主摄" : id == 3 ? "超广角" : id == 4 ? "长焦" : "相机" + id;
            float z = LensInfo.zoomRelative(this, id, 0);
            camLabels[i] = z > 0 ? String.format(Locale.US, "%s %.2fx（光学）", name, z) : name;
        }
        final Spinner camSpinner = addSpinner(box, "摄像头", camLabels, indexOf(Settings.CAMERA_IDS, s.cameraIdx));
        final Spinner drivingSpinner = addSpinner(box, "行车模式（屏幕）", Settings.DRIVING_MODE_LABELS, s.drivingMode);
        final Spinner exposureSpinner = addSpinner(box, "曝光模式", Settings.EXPOSURE_MODE_LABELS, s.exposureMode);
        final Spinner shutterSpinner = addSpinner(box, "快门上限（曝光不超过此值）", Settings.SHUTTER_CAP_LABELS, s.shutterIndex);
        final int[] isoRange = deviceIsoRange();
        final java.util.List<Integer> isoList = new java.util.ArrayList<>();
        for (int v : new int[]{400, 800, 1600, 3200, 6400, 12800, 25600}) {
            if (v > isoRange[0] && v <= isoRange[1]) isoList.add(v);
        }
        if (isoList.isEmpty() || isoList.get(isoList.size() - 1) != isoRange[1]) isoList.add(isoRange[1]);
        final int[] isoOptions = new int[isoList.size()];
        String[] isoLabels = new String[isoList.size()];
        int isoSel = isoOptions.length - 1;
        for (int i = 0; i < isoOptions.length; i++) {
            isoOptions[i] = isoList.get(i);
            isoLabels[i] = "上限 " + isoOptions[i] + (isoOptions[i] == isoRange[1] ? "（设备最大）" : "");
            if (isoOptions[i] == s.maxIso) isoSel = i;
        }
        final Spinner isoSpinner = addSpinner(box, "自动 ISO 上限（设备 " + isoRange[0] + "–" + isoRange[1] + "）",
                isoLabels, isoSel);
        final Spinner evSpinner = addSpinner(box, "EV 补偿", Settings.EV_LABELS, s.evIndex);
        final Spinner focusSpinner = addSpinner(box, "对焦", Settings.FOCUS_MODE_LABELS, s.focusMode);
        final Spinner zoomSpinner = addSpinner(box, "视野（数码变焦）", Settings.ZOOM_LABELS, s.zoomIndex);
        final CheckBox autoLensCheck = addCheck(box, "自动选镜头（按视野匹配光学镜头）", s.autoLens);
        final CheckBox highlightCheck = addCheck(box, "高光优先（夜间车牌防过曝）", s.highlightPriority);
        final Spinner sharpenSpinner = addSpinner(box, "锐化", Settings.SHARPEN_LABELS, s.sharpenLevel);

        final CheckBox watermark = addCheck(box, "画面水印（时间 / 经纬度）", s.watermark);
        final CheckBox rotate180 = addCheck(box, "倒装安装（画面上下颠倒时勾选）", s.upsideDownMount);
        final CheckBox wmExposure = addCheck(box, "水印辅助信息：快门 + ISO（右侧小字）", s.watermarkExposure);
        final CheckBox wmPlace = addCheck(box, "水印主信息：地点名称（需联网）", s.watermarkPlace);

        Button quit = makeButton("退出界面（录像继续）", "#30363D");
        quit.setOnClickListener(v -> {
            EventLog.write(this, "UI_QUIT", "recording=" + (service != null && service.isRecording()));
            finish();
        });
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qp.setMargins(0, dp(12), 0, 0);
        box.addView(quit, qp);

        Button caps = makeButton("查看设备相机能力", "#30363D");
        caps.setOnClickListener(v -> showCapabilities());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, dp(8), 0, 0);
        box.addView(caps, cp);

        Button battery = makeButton("电池优化白名单", "#30363D");
        battery.setOnClickListener(v -> maybeShowBatteryDialog());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMargins(0, dp(8), 0, 0);
        box.addView(battery, bp);

        Runnable updateFormat = () -> {
            int w = sizes[Math.max(0, resSpinner.getSelectedItemPosition())][0];
            int h = sizes[Math.max(0, resSpinner.getSelectedItemPosition())][1];
            int fps = Settings.FPS_OPTIONS[Math.max(0, fpsSpinner.getSelectedItemPosition())];
            long px = (long) w * h;
            int autoMin = px >= 7_000_000 ? 45 : px >= 2_500_000 ? 30 : 0;
            int effective = Math.max(bitrateMbps[0], autoMin);
            double mbPerHour = effective * 3600 / 8.0;
            String note = effective != bitrateMbps[0]
                    ? "（大画面自动提升至 " + effective + " Mbps）" : "";
            formatLine.setText("当前： " + w + "×" + h + " @ " + fps + "fps，实际码率 "
                    + effective + " Mbps" + note + "，约 "
                    + String.format(Locale.US, "%.1f", mbPerHour / 1024) + " GB/小时");
        };
        resSpinner.setOnItemSelectedListener(new SimpleItemSelected(updateFormat));
        fpsSpinner.setOnItemSelectedListener(new SimpleItemSelected(updateFormat));
        updf[0] = updateFormat;
        updateFormat.run();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        settingsDialogVisible = true;
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    settingsDialogVisible = false;
                    int ri = resSpinner.getSelectedItemPosition();
                    s.width = sizes[ri][0];
                    s.height = sizes[ri][1];
                    s.bitrateMbps = bitrateMbps[0];
                    s.segmentSecs = Settings.SEGMENTS[segSpinner.getSelectedItemPosition()];
                    s.quotaMb = quotaMb[0];
                    s.cameraIdx = Settings.CAMERA_IDS[camSpinner.getSelectedItemPosition()];
                    s.drivingMode = drivingSpinner.getSelectedItemPosition();
                    s.exposureMode = exposureSpinner.getSelectedItemPosition();
                    s.fps = Settings.FPS_OPTIONS[fpsSpinner.getSelectedItemPosition()];
                    s.shutterIndex = shutterSpinner.getSelectedItemPosition();
                    s.maxIso = isoOptions[isoSpinner.getSelectedItemPosition()];
                    s.evIndex = evSpinner.getSelectedItemPosition();
                    s.focusMode = focusSpinner.getSelectedItemPosition();
                    s.zoomIndex = zoomSpinner.getSelectedItemPosition();
                    s.autoLens = autoLensCheck.isChecked();
                    s.highlightPriority = highlightCheck.isChecked();
                    s.eis = false;   // EIS breaks this HAL (camera error 4); always off
                    s.sharpenLevel = sharpenSpinner.getSelectedItemPosition();

                    s.watermark = watermark.isChecked();
                    s.upsideDownMount = rotate180.isChecked();
                    s.watermarkExposure = wmExposure.isChecked();
                    s.watermarkPlace = wmPlace.isChecked();

                    s.save(this);
                    Toast.makeText(this, service != null && service.isRecording()
                            ? "已保存，重新开始录像后生效" : "已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", (d, w) -> settingsDialogVisible = false)
                .create();
        dialog.setOnDismissListener(d -> settingsDialogVisible = false);
        dialog.show();
    }

    private static class SimpleItemSelected implements android.widget.AdapterView.OnItemSelectedListener {
        private final Runnable r;
        SimpleItemSelected(Runnable r) {
            this.r = r;
        }
        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            r.run();
        }
        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }

    private String formatGb(int mb) {
        return mb >= 1024 ? String.format(Locale.US, "%.1f GB", mb / 1024f) : mb + " MB";
    }

    /** Numeric input dialog with inline guidance ("?" content) and range clamping. */
    private void askNumber(String title, String help, int initial, int min, int max,
                           java.util.function.IntConsumer onOk) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        TextView hint = new TextView(this);
        hint.setText(help);
        hint.setTextSize(12);
        hint.setTextColor(Color.parseColor("#8B949E"));
        box.addView(hint);
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(initial));
        input.setSelectAllOnFocus(true);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    int v;
                    try {
                        v = Integer.parseInt(input.getText().toString().trim());
                    } catch (Throwable t) {
                        Toast.makeText(this, "请输入数字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    v = Math.max(min, Math.min(max, v));
                    onOk.accept(v);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** Video sizes actually reported by the camera HAL, wide enough for a dashcam. */
    private int[][] deviceVideoSizes() {
        try {
            android.hardware.camera2.CameraManager cm =
                    (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
            android.hardware.camera2.CameraCharacteristics cc =
                    cm.getCameraCharacteristics(String.valueOf(Settings.load(this).cameraIdx));
            android.hardware.camera2.params.StreamConfigurationMap map = cc.get(
                    android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            android.util.Size[] all = map.getOutputSizes(android.media.MediaRecorder.class);
            java.util.List<int[]> list = new java.util.ArrayList<>();
            if (all != null) {
                for (android.util.Size sz : all) {
                    int w = sz.getWidth(), h = sz.getHeight();
                    double aspect = (double) w / h;
                    long px = (long) w * h;
                    if (w < 1280 || h < 720 || px > 3840L * 2160 || aspect < 1.7 || aspect > 2.45) continue;
                    boolean dup = false;
                    for (int[] e : list) if (e[0] == w && e[1] == h) dup = true;
                    if (!dup) list.add(new int[]{w, h});
                }
            }
            if (list.isEmpty()) {
                for (int[] r : Settings.RESOLUTIONS) list.add(new int[]{r[0], r[1]});
            }
            list.sort((a, b) -> Long.compare((long) b[0] * b[1], (long) a[0] * a[1]));
            return list.toArray(new int[0][]);
        } catch (Throwable t) {
            android.util.Log.w("DashcamUi", "deviceVideoSizes failed", t);
            int[][] fallback = new int[Settings.RESOLUTIONS.length][];
            for (int i = 0; i < fallback.length; i++) fallback[i] = Settings.RESOLUTIONS[i].clone();
            return fallback;
        }
    }

    /** ISO range exposed by the selected camera (falls back to 100..6400). */
    private int[] deviceIsoRange() {
        try {
            android.hardware.camera2.CameraManager cm =
                    (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
            android.hardware.camera2.CameraCharacteristics cc =
                    cm.getCameraCharacteristics(String.valueOf(Settings.load(this).cameraIdx));
            android.util.Range<Integer> r =
                    cc.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (r != null) return new int[]{r.getLower(), r.getUpper()};
        } catch (Throwable ignored) {
        }
        return new int[]{100, 6400};
    }

    /** Free space on the recording volume in GB. */
    private double freeSpaceGb() {
        try {
            java.io.File dir = StorageManager.recordingsDir(this);
            android.os.StatFs stat = new android.os.StatFs(dir.getAbsolutePath());
            return (double) stat.getAvailableBytes() / (1024.0 * 1024.0 * 1024.0);
        } catch (Throwable t) {
            return -1;
        }
    }

    private int indexOfSize(int[][] sizes, int w, int h) {
        for (int i = 0; i < sizes.length; i++) if (sizes[i][0] == w && sizes[i][1] == h) return i;
        return 0;
    }

    private Spinner addSpinner(LinearLayout parent, String label, String[] items, int selection) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.parseColor("#8B949E"));
        t.setTextSize(13);
        t.setPadding(0, dp(10), 0, 0);
        parent.addView(t);
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, items);
        sp.setAdapter(ad);
        sp.setSelection(Math.max(0, Math.min(items.length - 1, selection)));
        parent.addView(sp);
        return sp;
    }

    private CheckBox addCheck(LinearLayout parent, String label, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(Color.parseColor("#E6EDF3"));
        cb.setTextSize(14);
        cb.setChecked(checked);
        cb.setPadding(0, dp(8), 0, 0);
        parent.addView(cb);
        return cb;
    }

    private String[] labels(int[] values, String unit) {
        List<String> out = new ArrayList<>();
        for (int v : values) out.add(v + unit);
        return out.toArray(new String[0]);
    }

    private String[] quotaLabels() {
        String[] out = new String[Settings.QUOTAS_MB.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = Settings.QUOTAS_MB[i] >= 1024
                    ? (Settings.QUOTAS_MB[i] / 1024) + " GB" : Settings.QUOTAS_MB[i] + " MB";
        }
        return out;
    }

    private int indexOf(int[] arr, int value) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == value) return i;
        return 0;
    }

    private int indexOfResolution(Settings s) {
        for (int i = 0; i < Settings.RESOLUTIONS.length; i++) {
            if (Settings.RESOLUTIONS[i][0] == s.width && Settings.RESOLUTIONS[i][1] == s.height) return i;
        }
        return 0;
    }

    private void maybeShowBatteryDialog() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "已在电池优化白名单中", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("保持后台录像")
                .setMessage("请在接下来的系统弹窗中选择“允许”，否则息屏后系统可能结束录像。\n\n"
                        + "华为手机还需在「设置 → 应用 → 应用启动管理」中把本应用设为“手动管理”，并全部允许。")
                .setPositiveButton("去设置", (d, w) -> {
                    try {
                        Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Throwable t) {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        } catch (Throwable ignored) {
                        }
                    }
                })
                .setNegativeButton("以后再说", null)
                .show();
    }

    // ------------------------------------------------------ device capabilities

    /** Dynamically queries what the device actually offers (cameras, sizes, fps, encoders). */
    private void showCapabilities() {
        final String report;
        try {
            report = buildCapabilities();
        } catch (Throwable t) {
            Toast.makeText(this, "读取失败: " + t, Toast.LENGTH_LONG).show();
            return;
        }
        TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextColor(Color.parseColor("#E6EDF3"));
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        AlertDialog capsDialog = new AlertDialog.Builder(this)
                .setTitle("设备相机能力（动态读取）")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .create();
        capsDialog.show();
    }

    private String buildCapabilities() throws Exception {
        android.hardware.camera2.CameraManager cm =
                (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
        StringBuilder sb = new StringBuilder();
        String[] ids = cm.getCameraIdList();
        sb.append("相机数量: ").append(ids.length).append("\n");
        for (String id : ids) {
            android.hardware.camera2.CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            Integer facing = cc.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
            Integer level = cc.get(android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            sb.append("\n#").append(id).append(" ")
                    .append(facing == null ? "?" :
                            facing == android.hardware.camera2.CameraMetadata.LENS_FACING_BACK ? "后置" :
                            facing == android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT ? "前置" : "外接")
                    .append(" level=").append(level == null ? "?" : String.valueOf(level)).append("\n");
            try {
                java.util.Set<String> phys = cc.getPhysicalCameraIds();
                if (phys != null && !phys.isEmpty()) sb.append("  物理子相机: ").append(phys).append("\n");
            } catch (Throwable ignored) {}
            android.hardware.camera2.params.StreamConfigurationMap map = cc.get(
                    android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                android.util.Size[] sizes = map.getOutputSizes(android.media.MediaRecorder.class);
                if (sizes != null) {
                    sb.append("  可录像尺寸(带最高fps):\n");
                    for (android.util.Size s : sizes) {
                        long dur = map.getOutputMinFrameDuration(android.media.MediaRecorder.class, s);
                        double fps = dur > 0 ? 1e9 / dur : 0;
                        if (s.getWidth() < 1280) continue;
                        sb.append("    ").append(s.getWidth()).append("x").append(s.getHeight())
                                .append(" @").append(Math.round(fps)).append("fps\n");
                    }
                }
            }
            Float zoom = cc.get(android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (zoom != null) sb.append("  最大数码变焦: ").append(zoom).append("x\n");
            int[] ois = cc.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            String oisNote;
            if (ois == null || ois.length == 0) oisNote = "（不可用）";
            else if (ois.length == 1 && ois[0] == 1) oisNote = "（仅 ON，硬件常开，无法关闭）";
            else if (ois.length == 1) oisNote = "（仅 OFF，无光学防抖）";
            else oisNote = "（可开关）";
            sb.append("  光学防抖 OIS 可用模式: ").append(ois == null ? "无" : java.util.Arrays.toString(ois))
                    .append(oisNote).append("\n");
            android.util.Range<Integer> isoRange = cc.get(
                    android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (isoRange != null) {
                sb.append("  ISO 范围: ").append(isoRange.getLower()).append("–").append(isoRange.getUpper()).append("\n");
            }
            android.util.Range<Long> expRange = cc.get(
                    android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if (expRange != null) {
                sb.append("  曝光时间范围: ").append(expRange.getLower()).append("–")
                        .append(expRange.getUpper()).append(" ns\n");
            }
            int[] eis = cc.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            sb.append("  视频防抖 EIS 可用模式: ").append(eis == null ? "无" : java.util.Arrays.toString(eis)).append("\n");
        }
        sb.append("\n硬件编码器:\n");
        android.media.MediaCodecList list = new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS);
        for (android.media.MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (!"video/avc".equals(type) && !"video/hevc".equals(type)) continue;
                android.media.MediaCodecInfo.VideoCapabilities vc = info.getCapabilitiesForType(type).getVideoCapabilities();
                sb.append("  ").append(info.getName()).append(" ").append(type)
                        .append(" 尺寸≤").append(vc.getSupportedWidths().getUpper()).append("x")
                        .append(vc.getSupportedHeights().getUpper())
                        .append(" 码率≤").append(vc.getBitrateRange().getUpper() / 1_000_000).append("Mbps\n");
            }
        }
        return sb.toString();
    }

    // ----------------------------------------------------------- permission

    private void requestPermsIfNeeded() {
        List<String> missing = new ArrayList<>();
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        // Android 10 and below: write access to the public Movies/Dashcam folder so that
        // recordings show up in the system gallery.
        if (Build.VERSION.SDK_INT <= 29) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        } else if (StorageManager.publicDir() != null) {
            new Thread(() -> StorageManager.migrateLegacy(this), "migrate").start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[i])) {
                    Toast.makeText(this, "未授权存储：录像仍可录，但只保存在 App 私有目录，相册里看不到（可在系统设置里重新授权）",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "缺少权限：" + permissions[i] + "，录像功能不可用", Toast.LENGTH_LONG).show();
                }
            } else if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[i])) {
                new Thread(() -> StorageManager.migrateLegacy(this), "migrate").start();
                Toast.makeText(this, "录像将保存到相册（Movies/Dashcam）", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
