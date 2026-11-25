package icu.wwj.drive;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import icu.wwj.drive.compress.Compressor;
import icu.wwj.drive.dashcam.StorageManager;

/**
 * 统一 Application：集中做跨功能的全局初始化。
 *
 * <p>约定：各功能包不自己再声明 {@code android:name} 的 Application，全局初始化统一放这里，
 * 新增功能时在这里加一行 initialize 即可。
 */
public class DriveApp extends Application {

    /**
     * 行车记录仪前台服务的通知渠道 id。
     * 发布后不要再改：Android 按 id 记用户的渠道设置，改了等于新渠道。
     */
    public static final String CHANNEL_DASHCAM = "icu.wwj.drive.dashcam.recording";

    @Override
    public void onCreate() {
        super.onCreate();

        // 记录仪：StorageManager 需要 Application context 做 MediaStore 同步与路径兜底
        StorageManager.setContext(this);

        // 记录仪：前台服务通知渠道
        ensureDashcamChannel();

        // 压缩：清理上次进程被杀残留的 pending 行/半成品（后台，别拖慢启动）
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Compressor.cleanupStale(DriveApp.this);
                } catch (Throwable t) {
                    android.util.Log.w("DriveApp", "compress cleanup failed", t);
                }
            }
        }, "compress-cleanup").start();
    }

    private void ensureDashcamChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_DASHCAM,
                getString(R.string.dashcam_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }
}
