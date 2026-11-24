package com.dashcam.app;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Recording directory, file naming and FIFO quota cleanup. */
public class StorageManager {

    private static final String TAG = "DashcamStorage";

    /** Application context for MediaStore sync (set from DashcamApp). */
    private static volatile Context appCtx;

    public static void setContext(Context ctx) {
        appCtx = ctx == null ? null : ctx.getApplicationContext();
    }

    /**
     * Public Movies/Dashcam directory so recordings show up in the system gallery.
     * Falls back to the app-private directory if the public one is not writable
     * (no storage permission / newer Android without legacy storage).
     */
    public static File recordingsDir(Context ctx) {
        File pub = publicDir();
        if (pub != null) return pub;
        File base = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, "Dashcam");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** The app-private recording directory (used before v2.18 / as fallback). */
    public static File privateDir(Context ctx) {
        File base = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) base = ctx.getFilesDir();
        return new File(base, "Dashcam");
    }

    /** Public directory, or null when it cannot be written. */
    public static File publicDir() {
        try {
            File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            File dir = new File(base, "Dashcam");
            if (!dir.exists()) dir.mkdirs();
            if (dir.exists() && dir.canWrite()) return dir;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "public dir unavailable", t);
        }
        return null;
    }

    /** Deletes a recording and removes its MediaStore row (avoids ghost entries in the gallery). */
    public static boolean delete(Context ctx, File file) {
        if (file == null) return false;
        boolean ok = !file.exists() || file.delete();
        Context c = ctx != null ? ctx : appCtx;
        if (c == null) return ok;
        try {
            android.media.MediaScannerConnection.scanFile(c,
                    new String[]{file.getAbsolutePath()}, null, null);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "scan-after-delete failed", t);
        }
        return ok;
    }

    /** Makes a finished file visible to the system gallery (MediaStore). */
    public static void scan(Context ctx, File file) {
        if (file == null || !file.exists()) return;
        try {
            android.media.MediaScannerConnection.scanFile(ctx,
                    new String[]{file.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "scan failed", t);
        }
    }

    /** Moves recordings made in the old app-private directory into the public one, then rescans. */
    public static void migrateLegacy(Context ctx) {
        try {
            File priv = privateDir(ctx);
            File pub = publicDir();
            if (pub == null || priv.getAbsolutePath().equals(pub.getAbsolutePath())) return;
            File[] files = priv.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.isDirectory()) continue;
                File dst = new File(pub, f.getName());
                if (dst.exists()) continue;
                if (f.renameTo(dst)) scan(ctx, dst);
                else {
                    try {
                        java.io.FileInputStream in = new java.io.FileInputStream(f);
                        java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
                        byte[] buf = new byte[256 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        in.close();
                        out.close();
                        if (f.delete()) scan(ctx, dst);
                    } catch (Throwable t2) {
                        android.util.Log.w(TAG, "migrate failed " + f, t2);
                    }
                }
            }
            File privEvents = new File(priv, "events");
            File pubEvents = new File(pub, "events");
            if (privEvents.isDirectory()) {
                if (!pubEvents.exists()) pubEvents.mkdirs();
                File[] ev = privEvents.listFiles();
                if (ev != null) for (File f : ev) {
                    File dst = new File(pubEvents, f.getName());
                    if (!dst.exists() && f.renameTo(dst)) scan(ctx, dst);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "migrateLegacy failed", t);
        }
    }

    /** Removes the segment that was being written when the process died (its moov atom is missing). */
    public static void cleanupUnfinished(Context ctx) {
        android.content.SharedPreferences sp = ctx.getSharedPreferences("dashcam_runtime", Context.MODE_PRIVATE);
        String name = sp.getString("open_segment", "");
        if (name == null || name.isEmpty()) return;
        try {
            File f = new File(recordingsDir(ctx), name);
            if (f.exists()) {
                long len = f.length();
                if (delete(ctx, f)) android.util.Log.w(TAG, "deleted unfinished segment " + name + " " + len);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "cleanup failed", t);
        }
        sp.edit().remove("open_segment").apply();
    }

    public static String nextFileName() {
        return "DASHCAM_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
    }

    public static List<File> listClips(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.startsWith("DASHCAM_") && name.endsWith(".mp4"));
        if (files != null) out.addAll(Arrays.asList(files));
        File events = new File(dir, "events");
        File[] ev = events.listFiles((d, name) -> name.startsWith("EVENT_") && name.endsWith(".mp4"));
        if (ev != null) out.addAll(Arrays.asList(ev));
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    public static long totalBytes(List<File> files) {
        long total = 0;
        for (File f : files) total += f.length();
        return total;
    }

    public static long freeBytes(File dir) {
        try {
            StatFs s = new StatFs(dir.getAbsolutePath());
            return s.getAvailableBytes();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Delete oldest clips until the total size is under the quota.
     * Never deletes {@code keepName} (the file currently being written) or any of the last two files.
     */
    public static long enforceQuota(File dir, long quotaBytes, String keepName) {
        final android.content.Context ctxRef = appCtx;
        if (quotaBytes <= 0) return 0;
        List<File> clips = listClips(dir);
        File[] all = dir.listFiles();
        long total = 0;
        if (all != null) {
            for (File f : all) {
                if (f.getName().endsWith(".mp4")) total += f.length();
            }
        }
        long freed = 0;
        int guard = 0;
        for (int i = clips.size() - 1; i >= 0 && total > quotaBytes && guard < 200; i--, guard++) {
            File f = clips.get(i);
            if (f.getName().equals(keepName)) continue;
            long len = f.length();
            if (delete(ctxRef, f)) {
                total -= len;
                freed += len;
            }
        }
        return freed;
    }
}
