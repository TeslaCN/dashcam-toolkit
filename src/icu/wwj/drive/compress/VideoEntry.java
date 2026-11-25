package icu.wwj.drive.compress;

import android.net.Uri;

/** 列表里的一条视频记录（来自 MediaStore，不打开文件，很快）。 */
public final class VideoEntry {

    public final long id;
    public final Uri uri;
    public final String displayName;
    public final long durationMs;
    public final long sizeBytes;
    public final int width;
    public final int height;
    public final String mime;

    public VideoEntry(long id, Uri uri, String displayName, long durationMs, long sizeBytes,
                      int width, int height, String mime) {
        this.id = id;
        this.uri = uri;
        this.displayName = displayName;
        this.durationMs = durationMs;
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.mime = mime;
    }

    public String codecLabel() {
        if ("video/hevc".equals(mime)) return "HEVC";
        if ("video/avc".equals(mime)) return "H.264";
        if ("video/av1".equals(mime)) return "AV1";
        if ("video/mp4v-es".equals(mime)) return "MPEG-4";
        return mime == null ? "?" : mime;
    }

    public String metaLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(MediaInfo.formatDuration(durationMs));
        if (width > 0 && height > 0) sb.append(" · ").append(width).append('×').append(height);
        sb.append(" · ").append(codecLabel());
        sb.append(" · ").append(MediaInfo.formatSize(sizeBytes));
        return sb.toString();
    }
}
