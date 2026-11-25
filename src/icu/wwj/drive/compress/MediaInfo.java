package icu.wwj.drive.compress;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.IOException;

/**
 * 一段视频的探测结果：编码、分辨率、帧率、码率、音轨、旋转、色彩、位置。
 *
 * <p>压缩引擎（{@link Compressor}）只依赖这个类做决策，UI 也用它展示信息。
 * 探测本身只读，不修改文件。
 */
public final class MediaInfo {

    public final Uri uri;
    public final String displayName;
    public final long sizeBytes;
    public final long durationMs;
    public final long dateTakenMs;

    public final int width;
    public final int height;
    /** 画面旋转角度（0/90/180/270），来自 MediaExtractor/MediaMetadataRetriever。 */
    public final int rotation;

    /** 视频 MIME，如 video/hevc、video/avc。 */
    public final String videoMime;
    /** 视频平均码率（bps），探测不到时为 0。 */
    public final long videoBitrate;
    /** 帧率分子/分母，如 30/1、30000/1001。 */
    public final int fpsNum;
    public final int fpsDen;
    /** HEVC/AVC profile 值，探测不到为 -1。 */
    public final int profile;

    /** 音轨 MIME，无音轨为 null。 */
    public final String audioMime;
    public final long audioBitrate;

    /** 色彩信息（MediaFormat 的 KEY_COLOR_*），未知为 -1。 */
    public final int colorStandard;
    public final int colorTransfer;
    public final int colorRange;

    /** 疑似 10bit / HDR（硬件编码器通常不支持，压缩前会给出明确提示）。 */
    public final boolean tenBitOrHdr;

    /** 视频位置（MediaMetadataRetriever 的 METADATA_KEY_LOCATION），没有为 null。 */
    public final String location;

    private MediaInfo(Builder b) {
        this.uri = b.uri;
        this.displayName = b.displayName;
        this.sizeBytes = b.sizeBytes;
        this.durationMs = b.durationMs;
        this.dateTakenMs = b.dateTakenMs;
        this.width = b.width;
        this.height = b.height;
        this.rotation = b.rotation;
        this.videoMime = b.videoMime;
        this.videoBitrate = b.videoBitrate;
        this.fpsNum = b.fpsNum;
        this.fpsDen = b.fpsDen;
        this.profile = b.profile;
        this.audioMime = b.audioMime;
        this.audioBitrate = b.audioBitrate;
        this.colorStandard = b.colorStandard;
        this.colorTransfer = b.colorTransfer;
        this.colorRange = b.colorRange;
        this.tenBitOrHdr = b.tenBitOrHdr;
        this.location = b.location;
    }

    public double fps() {
        return fpsDen > 0 ? (double) fpsNum / fpsDen : 0;
    }

    /** 每像素每帧比特数，用来判断目标码率是否还够看。 */
    public double bpp(long videoBitrateBps) {
        double pixels = (double) width * height;
        double fps = fps();
        if (pixels <= 0 || fps <= 0) return 0;
        return videoBitrateBps / (pixels * fps);
    }

    public String codecLabel() {
        if ("video/hevc".equals(videoMime)) return "HEVC";
        if ("video/avc".equals(videoMime)) return "H.264";
        if ("video/av1".equals(videoMime)) return "AV1";
        if ("video/mp4v-es".equals(videoMime)) return "MPEG-4";
        return videoMime == null ? "?" : videoMime;
    }

    public String resolutionLabel() {
        return width + "×" + height;
    }

    public String fpsLabel() {
        double f = fps();
        if (f <= 0) return "?fps";
        if (Math.abs(f - Math.rint(f)) < 0.01) return String.format(java.util.Locale.US, "%.0ffps", f);
        return String.format(java.util.Locale.US, "%.2ffps", f);
    }

    public static String formatDuration(long ms) {
        long sec = ms / 1000;
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    public static String formatBitrate(long bps) {
        if (bps <= 0) return "?";
        return String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000.0);
    }

    /** 探测视频；不是视频或没有视频轨时抛 IOException。 */
    public static MediaInfo probe(Context ctx, Uri uri) throws IOException {
        Builder b = new Builder(uri);
        readStoreRow(ctx, uri, b);

        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, uri, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && b.videoMime == null) {
                    b.videoMime = mime;
                    b.width = intOr(f, MediaFormat.KEY_WIDTH, 0);
                    b.height = intOr(f, MediaFormat.KEY_HEIGHT, 0);
                    b.rotation = intOr(f, MediaFormat.KEY_ROTATION, 0);
                    b.videoBitrate = longOr(f, MediaFormat.KEY_BIT_RATE, 0);
                    b.profile = intOr(f, MediaFormat.KEY_PROFILE, -1);
                    b.colorStandard = intOr(f, MediaFormat.KEY_COLOR_STANDARD, -1);
                    b.colorTransfer = intOr(f, MediaFormat.KEY_COLOR_TRANSFER, -1);
                    b.colorRange = intOr(f, MediaFormat.KEY_COLOR_RANGE, -1);
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        try {
                            int rate = f.getInteger(MediaFormat.KEY_FRAME_RATE);
                            if (rate > 0) {
                                b.fpsNum = rate;
                                b.fpsDen = 1;
                            }
                        } catch (Exception ignored) {
                            // 个别编码器把 KEY_FRAME_RATE 存成 float
                            try {
                                double rate = f.getFloat(MediaFormat.KEY_FRAME_RATE);
                                if (rate > 0) setFpsFromDouble(b, rate);
                            } catch (Exception ignored2) {
                            }
                        }
                    }
                } else if (mime.startsWith("audio/") && b.audioMime == null) {
                    b.audioMime = mime;
                    b.audioBitrate = longOr(f, MediaFormat.KEY_BIT_RATE, 0);
                }
            }
        } finally {
            ex.release();
        }

        if (b.videoMime == null) throw new IOException("没有视频轨，无法压缩");

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, uri);
            if (b.durationMs <= 0) b.durationMs = longOr(mmr, MediaMetadataRetriever.METADATA_KEY_DURATION, 0);
            if (b.rotation == 0) b.rotation = intOr(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, 0);
            b.location = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
            if (b.width <= 0) b.width = intOr(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, 0);
            if (b.height <= 0) b.height = intOr(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, 0);
            if (b.fpsNum <= 0) {
                int captureFps = intOr(mmr, MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE, 0);
                if (captureFps > 0) {
                    b.fpsNum = captureFps;
                    b.fpsDen = 1;
                }
            }
        } catch (Exception ignored) {
            // metadata retriever 失败不影响主流程
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }

        if (b.fpsNum <= 0) {
            // 惠普/华为部分机型 MEDIA format 不带帧率，退化成 30fps 估计（仅用于展示与 bpp）
            b.fpsNum = 30;
            b.fpsDen = 1;
        }
        if (b.durationMs <= 0 && b.videoBitrate > 0 && b.sizeBytes > 0) {
            b.durationMs = (long) (b.sizeBytes * 8.0 / b.videoBitrate * 1000);
        }
        b.tenBitOrHdr = isTenBitOrHdr(b.profile, b.colorTransfer);
        return new MediaInfo(b);
    }

    private static void setFpsFromDouble(Builder b, double rate) {
        int den = 1000;
        int num = (int) Math.round(rate * den);
        b.fpsNum = num;
        b.fpsDen = den;
    }

    private static boolean isTenBitOrHdr(int profile, int colorTransfer) {
        // HEVC Main10 profile = 2；AVC High10 = 110。色彩传输 PQ(16)/HLG(18) 视为 HDR。
        if (profile == 2 || profile == 110) return true;
        // MediaFormat 常量在部分系统上缺失，直接比较数值
        return colorTransfer == 16 || colorTransfer == 18;
    }

    private static void readStoreRow(Context ctx, Uri uri, Builder b) {
        ContentResolver cr = ctx.getContentResolver();
        String[] proj = {
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
        };
        Cursor c = null;
        try {
            c = cr.query(uri, proj, null, null, null);
            if (c != null && c.moveToFirst()) {
                b.displayName = str(c, MediaStore.Video.Media.DISPLAY_NAME);
                b.sizeBytes = longOr(c, MediaStore.Video.Media.SIZE, 0);
                b.durationMs = longOr(c, MediaStore.Video.Media.DURATION, 0);
                b.dateTakenMs = longOr(c, MediaStore.Video.Media.DATE_TAKEN, 0);
                b.width = intOr(c, MediaStore.Video.Media.WIDTH, 0);
                b.height = intOr(c, MediaStore.Video.Media.HEIGHT, 0);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (b.displayName == null) {
            String last = uri.getLastPathSegment();
            b.displayName = last == null ? "video.mp4" : last;
        }
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 && !c.isNull(i) ? c.getString(i) : null;
    }

    private static long longOr(Cursor c, String col, long def) {
        int i = c.getColumnIndex(col);
        return i >= 0 && !c.isNull(i) ? c.getLong(i) : def;
    }

    private static int intOr(Cursor c, String col, int def) {
        int i = c.getColumnIndex(col);
        return i >= 0 && !c.isNull(i) ? c.getInt(i) : def;
    }

    private static int intOr(MediaFormat f, String key, int def) {
        try {
            return f.containsKey(key) ? f.getInteger(key) : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static long longOr(MediaFormat f, String key, long def) {
        try {
            return f.containsKey(key) ? f.getLong(key) : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static long longOr(MediaMetadataRetriever r, int key, long def) {
        try {
            String v = r.extractMetadata(key);
            return v == null ? def : Long.parseLong(v);
        } catch (Exception e) {
            return def;
        }
    }

    private static int intOr(MediaMetadataRetriever r, int key, int def) {
        try {
            String v = r.extractMetadata(key);
            return v == null ? def : Integer.parseInt(v);
        } catch (Exception e) {
            return def;
        }
    }

    private static final class Builder {
        final Uri uri;
        String displayName;
        long sizeBytes;
        long durationMs;
        long dateTakenMs;
        int width;
        int height;
        int rotation;
        String videoMime;
        long videoBitrate;
        int fpsNum;
        int fpsDen = 1;
        int profile = -1;
        String audioMime;
        long audioBitrate;
        int colorStandard = -1;
        int colorTransfer = -1;
        int colorRange = -1;
        boolean tenBitOrHdr;
        String location;

        Builder(Uri uri) {
            this.uri = uri;
        }
    }
}
