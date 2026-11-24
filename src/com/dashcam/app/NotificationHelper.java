package com.dashcam.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** Ongoing recording notification with a stop action. */
public class NotificationHelper {

    public static final int NOTIFICATION_ID = 0x1001;

    public static String formatElapsed(long ms) {
        long sec = ms / 1000;
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    public static Notification build(Context ctx, Status s) {
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi = PendingIntent.getActivity(ctx, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(ctx, RecordService.class).setAction(RecordService.ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(ctx, 11, stop,
                PendingIntent.FLAG_UPDATE_CURRENT);

        String title = s.recording ? "正在录像 " + formatElapsed(s.elapsedMs) : "行车记录仪";
        String text = s.recording
                ? (s.resolution + " · " + s.segments + " 段 · " + formatSize(s.sessionBytes)
                + (s.gpsFix ? " · GPS正常" : " · 等待GPS"))
                : "未在录像";

        Notification.Builder b = new Notification.Builder(ctx, DashcamApp.CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(s.recording)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openPi);
        if (s.recording) {
            b.addAction(new Notification.Action.Builder(null, "停止录像", stopPi).build());
        }
        return b.build();
    }
}
