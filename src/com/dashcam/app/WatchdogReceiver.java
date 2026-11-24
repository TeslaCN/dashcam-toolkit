package com.dashcam.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * Periodic self-healing: if the app is supposed to be recording but the service died
 * (OEM task killer, crash, low memory), start it again.
 */
public class WatchdogReceiver extends BroadcastReceiver {

    private static final String TAG = "DashcamWatchdog";
    public static final long INTERVAL_MS = 5 * 60 * 1000L;

    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0,
                    new Intent(ctx, WatchdogReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS, INTERVAL_MS, pi);
        } catch (Throwable t) {
            Log.w(TAG, "schedule failed", t);
        }
    }

    public static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0,
                    new Intent(ctx, WatchdogReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT);
            am.cancel(pi);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean shouldRecord = context.getSharedPreferences("dashcam_runtime", Context.MODE_PRIVATE)
                .getBoolean("should_record", false);
        if (!shouldRecord) return;
        if (RecordService.isServiceRunning()) return;
        if (context.checkSelfPermission("android.permission.CAMERA") != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Log.i(TAG, "recording expected but service is gone -> restart");
        Intent svc = new Intent(context, RecordService.class).setAction(RecordService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
            else context.startService(svc);
        } catch (Throwable t) {
            Log.e(TAG, "restart failed", t);
        }
    }
}
