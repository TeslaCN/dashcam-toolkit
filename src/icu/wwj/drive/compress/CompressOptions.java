package icu.wwj.drive.compress;

import android.net.Uri;

/** 压缩参数。{@code targetBytes > 0} 时按硬上限压；否则按 {@code ratioPct} 压到原体积的百分比。 */
public final class CompressOptions {

    public final MediaInfo info;
    /** 目标字节数（十进制，1MB = 1,000,000 字节）。0 表示用 ratioPct。 */
    public final long targetBytes;
    /** 比例模式：50 = 压到原体积 50%。 */
    public final int ratioPct;

    public CompressOptions(MediaInfo info, long targetBytes, int ratioPct) {
        this.info = info;
        this.targetBytes = targetBytes;
        this.ratioPct = ratioPct;
    }

    public boolean isRatioMode() {
        return targetBytes <= 0;
    }

    /** 本次要写的目标字节数（比例模式下按源文件大小折算）。 */
    public long effectiveTargetBytes() {
        if (!isRatioMode()) return targetBytes;
        int pct = Math.max(1, Math.min(100, ratioPct));
        return Math.max(1, info.sizeBytes * pct / 100);
    }

    /** 输出文件名：<原名>_compressed.mp4。 */
    public String outputName() {
        String name = info.displayName == null ? "video.mp4" : info.displayName;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + "_compressed.mp4";
    }

    public Uri inputUri() {
        return info.uri;
    }
}
