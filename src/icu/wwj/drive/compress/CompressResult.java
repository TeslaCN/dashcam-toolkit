package icu.wwj.drive.compress;

import android.net.Uri;

/** 压缩结果。 */
public final class CompressResult {

    public final Uri outputUri;
    public final long bytes;
    public final long durationMs;
    public final int width;
    public final int height;
    public final String videoMime;
    public final int encodedFrames;
    public final boolean audioCopied;
    /** 因为体积超出目标而重试了几次。 */
    public final int retries;

    public CompressResult(Uri outputUri, long bytes, long durationMs, int width, int height,
                          String videoMime, int encodedFrames, boolean audioCopied, int retries) {
        this.outputUri = outputUri;
        this.bytes = bytes;
        this.durationMs = durationMs;
        this.width = width;
        this.height = height;
        this.videoMime = videoMime;
        this.encodedFrames = encodedFrames;
        this.audioCopied = audioCopied;
        this.retries = retries;
    }

    public String codecLabel() {
        if ("video/hevc".equals(videoMime)) return "HEVC";
        if ("video/avc".equals(videoMime)) return "H.264";
        return videoMime == null ? "?" : videoMime;
    }
}
