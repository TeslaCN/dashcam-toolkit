package com.dashcam.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent, rotating event log (external files dir) so real-world failures can be
 * diagnosed after the fact: session start/stop, segment rotation, camera errors, battery.
 */
public class EventLog {

    private static final String TAG = "DashcamLog";
    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 512 * 1024;

    public static void write(Context ctx, String event, String detail) {
        if (ctx == null) return;
        synchronized (LOCK) {
            try {
                File dir = ctx.getExternalFilesDir(null);
                if (dir == null) return;
                File f = new File(dir, "dashcam.log");
                if (f.length() > MAX_BYTES) {
                    File old = new File(dir, "dashcam.log.1");
                    if (old.exists()) old.delete();
                    f.renameTo(old);
                }
                String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                        + " " + event + " " + (detail == null ? "" : detail) + "\n";
                FileOutputStream fos = new FileOutputStream(f, true);
                try {
                    fos.write(line.getBytes("UTF-8"));
                } finally {
                    fos.close();
                }
            } catch (Throwable t) {
                Log.w(TAG, "write failed", t);
            }
        }
    }
}
