package icu.wwj.drive.compress;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 视频压缩引擎（转码实现）。
 *
 * <p>设计约束（来自电脑端 ffmpeg 版的结论）：
 * 保持分辨率 / 帧率 / 编码家族 / 音轨 / 旋转与色彩元数据，只降码率；
 * 输出必须是手机播放器友好的码流（无 B 帧、无负 DTS、无 edit list 偏移）。
 * 手机端用 MediaCodec 硬件解码+编码，天然只有一个参考帧且 MediaMuxer 不写 elst，
 * 这正是和电脑端 x264/x265 默认参数最大的区别。
 *
 * <p>实现管线：
 * <pre>
 *   MediaExtractor(视频) → MediaCodec 解码 → 编码器 createInputSurface()（零拷贝）
 *                                           → MediaCodec 编码（同 MIME、同宽高、单遍 VBR）
 *   MediaExtractor(音频) ── AAC 样本原样复制 ──┘
 *                                           → MediaMuxer(MediaStore pending PFD)
 * </pre>
 *
 * <p>输出写系统相册 {@code Movies/Compressed/<原名>_compressed.mp4}，写期间
 * {@code IS_PENDING=1}，成功发布为 0；失败/取消删除 pending 行与文件。
 */
public final class Compressor {

    private static final String TAG = "CompressTest";
    /** 记下本次 pending 输出 URI：进程被杀后下次启动按 item URI 精确删除。 */
    private static final String PREFS = "compress_runtime";
    private static final String KEY_PENDING_URI = "pending_output_uri";

    /** 目标体积模式下预留的封装/码率波动余量。 */
    private static final double TARGET_MARGIN = 0.92;
    /** 比例模式允许的体积误差（验收要求 ≤5%）。 */
    private static final double RATIO_TOLERANCE = 0.05;
    /** 低于这个码率就没法看了，直接按下限压。 */
    private static final long MIN_VIDEO_BPS = 150_000L;
    /** 防止异常输入把 int 码率撑爆。 */
    private static final long MAX_VIDEO_BPS = 200_000_000L;
    /** 体积超目标时最多重试次数。 */
    private static final int MAX_RETRIES = 2;
    private static final long DEFAULT_AAC_BPS = 128_000L;
    /** 解码输入缓冲上限（4K HEVC 关键帧可能到几 MB）。 */
    private static final int MAX_SAMPLE_BYTES = 16 << 20;

    private static final String MIME_AAC = "audio/mp4a-latm";
    private static final String MIME_HEVC = "video/hevc";
    private static final String MIME_AVC = "video/avc";

    /** 进度回调，运行在引擎线程。 */
    public interface Callback {
        void onProgress(int percent, String status);
    }

    /** 压缩被用户取消。 */
    public static class CancelledException extends Exception {
        public CancelledException() {
            super("已取消");
        }
    }


    /**
     * 冷启动清理：转码线程被杀时 finally 不会执行，进程里也不会走 onDestroy，
     * 于是 Movies/Compressed 下会留下 IS_PENDING=1 的 MediaStore 行和半成品文件。
     * 由 DriveApp 在进程启动时调一次（后台线程），只动本功能自己的目录与输出命名，
     * 已发布（IS_PENDING=0）的正常输出不会被动。
     */
    public static void cleanupStale(Context ctx) {
        if (ctx == null) return;
        final ContentResolver cr = ctx.getContentResolver();
        // 用 file 集合而不是 video 集合：被杀留下的行可能是 media_type=0，
        // 在 video 集合里查不到/删不掉，会占着 _data 让下次 insert 冲突。
        final Uri files = MediaStore.Files.getContentUri("external");
        final File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "Compressed");
        final String prefix = dir.getAbsolutePath() + "/";

        // 0) 上次进程被杀时记下的 pending item：按 item URI 删，能精确删掉行+文件
        try {
            String stale = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PENDING_URI, null);
            if (stale != null) {
                int n = cr.delete(Uri.parse(stale), null, null);
                Log.i(TAG, "COMPRESS_CLEANUP_PENDING_URI uri=" + stale + " deleted=" + n);
            }
        } catch (Exception e) {
            Log.w(TAG, "COMPRESS_CLEANUP_PENDING_URI_FAIL", e);
        }
        clearPendingUri(ctx);

        // 1) 残留 pending 行（冷启动时不存在正在写的转码，直接全删）
        int pendingRows = 0;
        try {
            pendingRows = cr.delete(files,
                    MediaStore.MediaColumns.DATA + " LIKE ? AND "
                            + MediaStore.Video.Media.IS_PENDING + "=1",
                    new String[]{prefix + "%"});
        } catch (Exception e) {
            Log.w(TAG, "COMPRESS_CLEANUP_PENDING_FAIL", e);
        }

        // 2) 已发布（IS_PENDING=0）的输出名不能动
        java.util.Set<String> published = new java.util.HashSet<>();
        try (Cursor c = cr.query(files,
                new String[]{MediaStore.Video.Media.DISPLAY_NAME},
                MediaStore.MediaColumns.DATA + " LIKE ? AND "
                        + MediaStore.Video.Media.IS_PENDING + "=0",
                new String[]{prefix + "%"}, null)) {
            if (c != null) {
                int ni = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
                while (c.moveToNext()) {
                    if (ni >= 0 && !c.isNull(ni)) published.add(c.getString(ni));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "COMPRESS_CLEANUP_QUERY_FAIL", e);
        }

        // 3) 孤儿文件：MediaProvider 有时先删行留下文件（P30 实测），按本功能命名兜底删
        int orphans = 0;
        try {
            File[] found = dir.listFiles();
            if (found != null) {
                java.util.regex.Pattern own = java.util.regex.Pattern.compile(
                        "_compressed(_\\d{8}_\\d{6})?( \\(\\d+\\))?\\.mp4$");
                for (File f : found) {
                    String name = f.getName();
                    if (!f.isFile() || !own.matcher(name).find()) continue;
                    if (published.contains(name)) continue;
                    if (f.delete()) orphans++;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "COMPRESS_CLEANUP_ORPHAN_FAIL", e);
        }
        // 4) 触发一次目录扫描：让 MediaProvider 修正/清掉"行在文件没了"的旧记录
        try {
            android.media.MediaScannerConnection.scanFile(ctx,
                    new String[]{dir.getAbsolutePath()}, null, null);
        } catch (Exception e) {
            Log.w(TAG, "COMPRESS_CLEANUP_SCAN_FAIL", e);
        }
        Log.i(TAG, "COMPRESS_CLEANUP_STALE pendingRows=" + pendingRows
                + " published=" + published.size() + " orphans=" + orphans);
    }

    private Compressor() {
    }

    /**
     * 执行压缩。输出写入系统相册 Movies/Compressed/，成功后返回 MediaStore Uri。
     *
     * @param ctx    上下文
     * @param opt    压缩参数（含已探测的 MediaInfo）
     * @param cb     进度回调，可为 null
     * @param cancel 取消标志，可为 null
     */
    public static CompressResult compress(Context ctx, CompressOptions opt, Callback cb,
                                          AtomicBoolean cancel) throws Exception {
        if (ctx == null) throw new IllegalArgumentException("缺少上下文");
        if (opt == null || opt.info == null) throw new IllegalArgumentException("缺少压缩参数");
        final MediaInfo info = opt.info;

        validateInput(info);
        final long targetBytes = opt.effectiveTargetBytes();
        if (targetBytes <= 0) throw new Exception("目标体积无效");
        final long sourceVideoBps = info.videoBitrate > 0
                ? info.videoBitrate : estimateBps(info.sizeBytes, info.durationMs);
        final long audioBps = info.audioMime == null ? 0
                : (info.audioBitrate > 0 ? info.audioBitrate : DEFAULT_AAC_BPS);
        final long sourceCapBps = clamp(sourceVideoBps, MIN_VIDEO_BPS, MAX_VIDEO_BPS);

        long videoBps;
        if (opt.isRatioMode()) {
            int pct = Math.max(1, Math.min(100, opt.ratioPct));
            videoBps = sourceVideoBps * pct / 100;
            // 源码率探测不到时用目标体积反推
            if (videoBps <= 0) videoBps = bpsFromTarget(targetBytes, info.durationMs, audioBps);
        } else {
            videoBps = bpsFromTarget(targetBytes, info.durationMs, audioBps);
        }
        boolean clampedLow = false;
        if (videoBps < MIN_VIDEO_BPS) {
            videoBps = MIN_VIDEO_BPS;
            clampedLow = true;
        }
        if (videoBps > sourceCapBps) videoBps = sourceCapBps;

        Log.i(TAG, "COMPRESS_PLAN name=" + info.displayName + " " + info.resolutionLabel()
                + " " + info.codecLabel() + " fps=" + info.fpsLabel()
                + " rot=" + info.rotation + " durationMs=" + info.durationMs
                + " size=" + info.sizeBytes + " sourceVideoBps=" + sourceVideoBps
                + " audioMime=" + info.audioMime + " audioBps=" + audioBps
                + " target=" + targetBytes + " mode=" + (opt.isRatioMode()
                ? "ratio" + Math.max(1, Math.min(100, opt.ratioPct)) + "%" : "target")
                + " videoBps=" + videoBps + " colorStd=" + info.colorStandard
                + " colorXfer=" + info.colorTransfer + " colorRange=" + info.colorRange
                + " location=" + info.location);
        if (clampedLow) {
            notifyProgress(cb, 0, "目标码率过低，已按 150 kbps 下限转码");
        }

        int attempt = 0;
        while (true) {
            checkCancel(cancel);
            if (attempt > 0) {
                notifyProgress(cb, 0, "体积超出，重试 " + attempt + "/" + MAX_RETRIES
                        + "（码率 " + (videoBps / 1000) + " kbps）");
            }
            Attempt result = runOnce(ctx, opt, videoBps, attempt, cb, cancel);
            double errPct = targetBytes > 0
                    ? (result.bytes - targetBytes) * 100.0 / targetBytes : 0;
            boolean hit = opt.isRatioMode()
                    ? Math.abs(errPct) <= RATIO_TOLERANCE * 100 : result.bytes <= targetBytes;
            Log.i(TAG, "COMPRESS_ATTEMPT attempt=" + attempt + " videoBps=" + videoBps
                    + " bytes=" + result.bytes + " target=" + targetBytes + " errPct="
                    + String.format(java.util.Locale.US, "%.2f", errPct)
                    + " hit=" + hit + " frames=" + result.frames
                    + " audioCopied=" + result.audioCopied);
            if (hit || attempt >= MAX_RETRIES) {
                Log.i(TAG, "COMPRESS_DONE uri=" + result.uri + " bytes=" + result.bytes
                        + " target=" + targetBytes + " frames=" + result.frames
                        + " durationMs=" + result.durationMs + " retries=" + attempt);
                return new CompressResult(result.uri, result.bytes, result.durationMs,
                        info.width, info.height, info.videoMime, result.frames,
                        result.audioCopied, attempt);
            }

            // 体积不达标：删掉这次输出，按实际体积反推新码率再试
            try {
                int deleted = ctx.getContentResolver().delete(result.uri, null, null);
                Log.i(TAG, "COMPRESS_RETRY_DELETE uri=" + result.uri + " deleted=" + deleted);
            } catch (Exception e) {
                Log.w(TAG, "COMPRESS_RETRY_DELETE_FAIL uri=" + result.uri, e);
            }
            attempt++;
            double factor = (targetBytes / (double) result.bytes) * (opt.isRatioMode() ? 0.97 : 0.92);
            long next = Math.round(videoBps * factor);
            next = clamp(next, MIN_VIDEO_BPS, sourceCapBps);
            if (next == videoBps) {
                // 已经压到下限/上限，再试也不会变，直接接受当前结果
                Log.i(TAG, "COMPRESS_DONE_NO_ADJUST bytes=" + result.bytes
                        + " target=" + targetBytes + " retries=" + attempt);
                return new CompressResult(result.uri, result.bytes, result.durationMs,
                        info.width, info.height, info.videoMime, result.frames,
                        result.audioCopied, attempt);
            }
            Log.i(TAG, "COMPRESS_RETRY from=" + videoBps + " to=" + next
                    + " actual=" + result.bytes + " target=" + targetBytes);
            videoBps = next;
        }
    }

    // ------------------------------------------------------------------
    // 输入校验 / 码率预算
    // ------------------------------------------------------------------

    private static void validateInput(MediaInfo info) throws Exception {
        if (info.videoMime == null) throw new Exception("没有视频轨，无法压缩");
        if (info.tenBitOrHdr) {
            throw new Exception("源视频是 10bit/HDR（" + info.codecLabel()
                    + "），本机硬件编码器不支持保持画质压缩，已放弃。");
        }
        if (!MIME_HEVC.equals(info.videoMime) && !MIME_AVC.equals(info.videoMime)) {
            throw new Exception("暂不支持 " + info.codecLabel() + " 编码的视频（仅支持 HEVC/H.264）");
        }
        if (info.width <= 0 || info.height <= 0) throw new Exception("无法读取视频分辨率，无法压缩");
        if (info.durationMs <= 0) throw new Exception("无法读取视频时长，无法估算目标码率");
        if (info.fpsNum <= 0) throw new Exception("无法读取视频帧率，无法压缩");
        if (info.audioMime != null && !MIME_AAC.equals(info.audioMime)) {
            throw new Exception("音轨编码 " + info.audioMime
                    + " 不支持原样复制（仅支持 AAC / audio/mp4a-latm）");
        }
    }

    private static long bpsFromTarget(long targetBytes, long durationMs, long audioBps) {
        double durationSec = durationMs / 1000.0;
        if (durationSec <= 0) return MIN_VIDEO_BPS;
        double videoBits = targetBytes * 8.0 * TARGET_MARGIN - audioBps * durationSec;
        if (videoBits <= 0) return MIN_VIDEO_BPS;
        return Math.max(MIN_VIDEO_BPS, (long) (videoBits / durationSec));
    }

    private static long estimateBps(long bytes, long durationMs) {
        if (bytes <= 0 || durationMs <= 0) return 0;
        return bytes * 8L * 1000L / durationMs;
    }

    // ------------------------------------------------------------------
    // 单次转码
    // ------------------------------------------------------------------

    private static Attempt runOnce(Context ctx, CompressOptions opt, long videoBps,
                                   int attempt, Callback cb, AtomicBoolean cancel)
            throws Exception {
        final MediaInfo info = opt.info;
        final ContentResolver cr = ctx.getContentResolver();

        Uri outputUri = null;
        ParcelFileDescriptor pfd = null;
        MediaMuxer muxer = null;
        MediaExtractor videoEx = null;
        MediaExtractor audioEx = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        Surface inputSurface = null;
        AacCopier audioCopier = null;
        MediaFormat audioFormat = null;
        boolean muxerStarted = false;
        boolean published = false;

        try {
            // ---------- 1. 视频轨 ----------
            videoEx = new MediaExtractor();
            videoEx.setDataSource(ctx, opt.inputUri(), null);
            int videoTrackIndex = findTrack(videoEx, "video/");
            if (videoTrackIndex < 0) throw new Exception("没有视频轨，无法压缩");
            videoEx.selectTrack(videoTrackIndex);
            MediaFormat videoFormat = videoEx.getTrackFormat(videoTrackIndex);
            String mime = videoFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) mime = info.videoMime;
            if (!info.videoMime.equals(mime)) {
                Log.w(TAG, "COMPRESS_MIME_MISMATCH info=" + info.videoMime + " extractor=" + mime);
            }
            long durationUs = info.durationMs > 0
                    ? info.durationMs * 1000L
                    : longOr(videoFormat, MediaFormat.KEY_DURATION, 0);
            if (durationUs <= 0) throw new Exception("无法读取视频时长，无法估算目标码率");
            int fps = Math.max(1, (int) Math.round(info.fps()));
            // 旋转统一由 MediaMuxer.setOrientationHint 表达，解码器按编码朝向出帧，
            // 否则 90° 源会被解码器/显示层旋转两次。
            videoFormat.removeKey(MediaFormat.KEY_ROTATION);
            int maxSampleBytes = (int) Math.min(MAX_SAMPLE_BYTES,
                    Math.max(2L << 20, Math.min((long) MAX_SAMPLE_BYTES, info.sizeBytes / 4)));
            videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxSampleBytes);

            // getSampleTime() 对“没有样本”返回 -1，但 AAC 的 priming 样本时间可以是
            // 负数（例如 -21333us），所以“有没有样本”必须用 getSampleSize() 判断。
            if (videoEx.getSampleSize() < 0) throw new Exception("视频轨没有可解码的帧");
            long firstVideoPts = videoEx.getSampleTime();
            if (firstVideoPts == -1) firstVideoPts = 0;

            // ---------- 2. 音轨（AAC 原样复制，其它编码直接报错） ----------
            final long NO_AUDIO = Long.MAX_VALUE;
            long firstAudioPts = NO_AUDIO;
            if (info.audioMime != null) {
                audioEx = new MediaExtractor();
                audioEx.setDataSource(ctx, opt.inputUri(), null);
                int audioTrackIndex = findTrack(audioEx, "audio/");
                if (audioTrackIndex < 0) {
                    Log.w(TAG, "COMPRESS_AUDIO no track, expect " + info.audioMime);
                    audioEx.release();
                    audioEx = null;
                } else {
                    audioEx.selectTrack(audioTrackIndex);
                    audioFormat = audioEx.getTrackFormat(audioTrackIndex);
                    String aMime = audioFormat.getString(MediaFormat.KEY_MIME);
                    if (!MIME_AAC.equals(aMime)) {
                        throw new Exception("音轨编码 " + aMime
                                + " 不支持原样复制（仅支持 AAC / audio/mp4a-latm）");
                    }
                    if (audioEx.getSampleSize() < 0) {
                        Log.w(TAG, "COMPRESS_AUDIO empty track");
                        audioFormat = null;
                        audioEx.release();
                        audioEx = null;
                    } else {
                        firstAudioPts = audioEx.getSampleTime();
                        if (firstAudioPts == -1) firstAudioPts = 0;
                        Log.i(TAG, "COMPRESS_AUDIO mime=" + aMime
                                + " firstPts=" + firstAudioPts
                                + " csd=" + audioFormat.containsKey("csd-0")
                                + " maxInput="
                                + intOr(audioFormat, MediaFormat.KEY_MAX_INPUT_SIZE, -1));
                    }
                }
            }

            // 音视频各自可能带非 0/负起始时间（edit list / AAC priming / 截取），
            // 整体平移到 >=0，保证首包 DTS >= 0，且 A/V 相对时间关系完全不变。
            long shiftUs = firstVideoPts;
            if (firstAudioPts != NO_AUDIO && firstAudioPts < shiftUs) shiftUs = firstAudioPts;
            Log.i(TAG, "COMPRESS_TIMEBASE firstVideoPts=" + firstVideoPts
                    + " firstAudioPts=" + (firstAudioPts == NO_AUDIO ? "none" : firstAudioPts)
                    + " shiftUs=" + shiftUs);

            if (audioEx != null && audioFormat != null) {
                audioCopier = new AacCopier(audioEx, audioFormat, shiftUs);
            }

            // ---------- 3. MediaStore pending 输出 + Muxer 元数据 ----------
            outputUri = insertPending(ctx, opt);
            savePendingUri(ctx, outputUri);
            pfd = openOutputPfd(ctx, outputUri);
            muxer = new MediaMuxer(pfd.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            applyOrientation(muxer, info.rotation);
            applyLocation(muxer, info.location);
            Log.i(TAG, "COMPRESS_MUXER uri=" + outputUri + " rotation=" + info.rotation
                    + " location=" + info.location);

            // ---------- 4. 编码器 + 解码器（零拷贝：解码输出直接进编码输入 Surface） ----------
            EncoderChoice enc = createEncoder(mime, info, videoBps, fps);
            encoder = enc.codec;
            inputSurface = enc.surface;
            Log.i(TAG, "COMPRESS_ENCODER name=" + enc.name + " variant=" + enc.variant
                    + " mime=" + mime + " " + info.width + "x" + info.height
                    + " fps=" + fps + " bitrate=" + videoBps
                    + " colorFormat=surface");

            decoder = createDecoder(mime, info.width, info.height, videoFormat, inputSurface);
            Log.i(TAG, "COMPRESS_DECODER name=" + decoder.getName());

            Log.i(TAG, "COMPRESS_DECODER_START");
            decoder.start();
            Log.i(TAG, "COMPRESS_DECODER_STARTED");
            Log.i(TAG, "COMPRESS_ENCODER_START");
            encoder.start();
            Log.i(TAG, "COMPRESS_ENCODER_STARTED");

            // ---------- 5. 解码 → 编码 → 封装 ----------
            Engine engine = new Engine(info, decoder, encoder, videoEx, muxer,
                    audioFormat, audioCopier, shiftUs, durationUs, fps, attempt, cb, cancel);
            Log.i(TAG, "COMPRESS_PUMP_BEGIN");
            engine.pump();
            Log.i(TAG, "COMPRESS_PUMP_END frames=" + engine.frames);

            if (!engine.muxerStarted) throw new Exception("编码器没有输出任何数据，压缩失败");
            if (engine.frames <= 0) throw new Exception("没有编码出任何视频帧，压缩失败");

            // 6. 收尾 muxer（stop 会写 moov，必须在取文件大小之前）
            muxer.stop();
            muxerStarted = false;
            muxer.release();
            muxer = null;

            long bytes = 0;
            try {
                bytes = pfd.getStatSize();
            } catch (Exception e) {
                Log.w(TAG, "COMPRESS_STAT_FAIL", e);
            }
            closeQuietly(pfd);
            pfd = null;
            if (bytes <= 0) bytes = queryStoreSize(cr, outputUri);
            if (bytes <= 0) throw new Exception("输出文件为空，压缩失败");

            // 7. 校验输出能被 MediaExtractor 正常打开（相册/播放器可识别的前提），再发布
            validateOutput(ctx, outputUri, info);
            publish(cr, outputUri);
            published = true;

            long outDurationMs = engine.outputDurationMs();
            Log.i(TAG, "COMPRESS_FILE uri=" + outputUri + " bytes=" + bytes
                    + " frames=" + engine.frames + " decoded=" + engine.framesDecoded
                    + " audioSamples=" + engine.audioSamples
                    + " durationMs=" + outDurationMs);
            return new Attempt(outputUri, bytes, outDurationMs, engine.frames,
                    engine.audioSamples > 0);
        } catch (CancelledException ce) {
            Log.i(TAG, "COMPRESS_CANCEL uri=" + outputUri);
            throw ce;
        } catch (Exception e) {
            Log.w(TAG, "COMPRESS_ERROR uri=" + outputUri + " " + describe(e), e);
            throw e;
        } finally {
            // 释放顺序：编码/解码 → Surface → Muxer → PFD → Extractor。
            // 任何一步失败都不能影响后续释放。
            Log.i(TAG, "COMPRESS_CLEANUP_BEGIN uri=" + outputUri);
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception ignored) {
                }
                try {
                    decoder.release();
                } catch (Exception ignored) {
                }
            }
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Exception ignored) {
                }
                try {
                    encoder.release();
                } catch (Exception ignored) {
                }
            }
            Log.i(TAG, "COMPRESS_CLEANUP_CODECS uri=" + outputUri);
            if (inputSurface != null) {
                try {
                    inputSurface.release();
                } catch (Exception ignored) {
                }
            }
            if (muxer != null) {
                if (muxerStarted) {
                    try {
                        muxer.stop();
                    } catch (Exception e) {
                        Log.w(TAG, "COMPRESS_MUXER_STOP_FAIL", e);
                    }
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
            closeQuietly(pfd);
            if (audioEx != null) {
                try {
                    audioEx.release();
                } catch (Exception ignored) {
                }
            }
            if (videoEx != null) {
                try {
                    videoEx.release();
                } catch (Exception ignored) {
                }
            }
            if (outputUri != null) {
                if (!published) {
                    try {
                        int deleted = cr.delete(outputUri, null, null);
                        Log.i(TAG, "COMPRESS_CLEANUP uri=" + outputUri + " deleted=" + deleted);
                    } catch (Exception e) {
                        Log.w(TAG, "COMPRESS_CLEANUP_FAIL uri=" + outputUri, e);
                    }
                }
                clearPendingUri(ctx);
            }
        }
    }

    private static final class Attempt {
        final Uri uri;
        final long bytes;
        final long durationMs;
        final int frames;
        final boolean audioCopied;

        Attempt(Uri uri, long bytes, long durationMs, int frames, boolean audioCopied) {
            this.uri = uri;
            this.bytes = bytes;
            this.durationMs = durationMs;
            this.frames = frames;
            this.audioCopied = audioCopied;
        }
    }

    // ------------------------------------------------------------------
    // 解码 → 编码 流水线（单线程泵：喂解码输入、收解码输出、收编码输出）
    // ------------------------------------------------------------------

    private static final class Engine {
        private final MediaInfo info;
        private final MediaCodec decoder;
        private final MediaCodec encoder;
        private final MediaExtractor videoEx;
        private final MediaMuxer muxer;
        private final MediaFormat audioFormat;
        private final AacCopier audio;
        private final long shiftUs;
        private final long durationUs;
        private final int fps;
        private final int attempt;
        private final Callback cb;
        private final AtomicBoolean cancel;

        private int videoTrack = -1;
        private int audioTrack = -1;
        boolean muxerStarted;
        int frames;
        int framesDecoded;
        long audioSamples;
        private long maxPtsUs = -1;
        private long lastCallbackMs;
        private int lastPercent = -1;
        private int lastLoggedPercent = -1;

        Engine(MediaInfo info, MediaCodec decoder, MediaCodec encoder,
               MediaExtractor videoEx, MediaMuxer muxer, MediaFormat audioFormat,
               AacCopier audio, long shiftUs, long durationUs, int fps, int attempt,
               Callback cb, AtomicBoolean cancel) {
            this.info = info;
            this.decoder = decoder;
            this.encoder = encoder;
            this.videoEx = videoEx;
            this.muxer = muxer;
            this.audioFormat = audioFormat;
            this.audio = audio;
            this.shiftUs = shiftUs;
            this.durationUs = durationUs;
            this.fps = fps;
            this.attempt = attempt;
            this.cb = cb;
            this.cancel = cancel;
        }

        void pump() throws Exception {
            ByteBuffer sampleBuf = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES);
            boolean inputDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;
            long firstInputPts = -1;
            long lastInputPts = 0;
            long lastProgress = System.currentTimeMillis();

            while (!encoderDone) {
                checkCancel(cancel);
                boolean progress = false;

                // 1) 喂解码器输入（非阻塞；EOS 用空 buffer + flag）
                if (!inputDone) {
                    int inIdx = decoder.dequeueInputBuffer(0);
                    if (inIdx >= 0) {
                        ByteBuffer ib = decoder.getInputBuffer(inIdx);
                        if (ib == null) throw new Exception("解码器输入缓冲区不可用");
                        ib.clear();
                        sampleBuf.clear();
                        int size = videoEx.readSampleData(sampleBuf, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0,
                                    Math.max(0, lastInputPts), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            if (size > sampleBuf.capacity()) {
                                throw new Exception("视频帧样本 " + size + " 字节超过缓冲上限 "
                                        + sampleBuf.capacity() + "，无法解码");
                            }
                            if (size > ib.capacity()) {
                                throw new Exception("解码器输入缓冲不足（" + ib.capacity()
                                        + " < " + size + " 字节）");
                            }
                            long pts = videoEx.getSampleTime();
                            if (pts < 0) pts = lastInputPts;
                            if (firstInputPts < 0) firstInputPts = pts;
                            lastInputPts = pts;
                            sampleBuf.position(0);
                            sampleBuf.limit(size);
                            ib.put(sampleBuf);
                            decoder.queueInputBuffer(inIdx, 0, size, pts, 0);
                            videoEx.advance();
                        }
                        progress = true;
                    }
                }

                // 2) 收解码器输出：releaseOutputBuffer(render=true) 把帧送进编码器 InputSurface
                if (!decoderDone) {
                    MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                    int outIdx = decoder.dequeueOutputBuffer(bi, 5_000);
                    if (outIdx >= 0) {
                        boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (bi.size > 0) {
                            decoder.releaseOutputBuffer(outIdx, true);
                            framesDecoded++;
                            onDecodedFrame(bi.presentationTimeUs, firstInputPts);
                        } else {
                            decoder.releaseOutputBuffer(outIdx, false);
                        }
                        if (eos) {
                            decoderDone = true;
                            encoder.signalEndOfInputStream();
                            Log.i(TAG, "COMPRESS_DECODE_EOS decoded=" + framesDecoded);
                        }
                        progress = true;
                    }
                }

                // 3) 收编码器输出 → 写 Muxer（视频与 AAC 按 PTS 交错）
                encoderDone = drainEncoder();
                if (encoderDone) progress = true;

                if (!progress) {
                    long now = System.currentTimeMillis();
                    if (now - lastProgress > 30_000) {
                        throw new Exception("转码卡住超过 30 秒（解码 " + framesDecoded
                                + " 帧，编码 " + frames + " 帧）");
                    }
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Exception("转码线程被中断");
                    }
                } else {
                    lastProgress = System.currentTimeMillis();
                }
            }

            // 编码器已 EOS，把剩余 AAC 样本全部写出（PTS 仍单调）
            if (audio != null && audioTrack >= 0) {
                audio.writeUpTo(muxer, audioTrack, Long.MAX_VALUE);
                audioSamples = audio.samplesWritten();
            }
            if (!muxerStarted) throw new Exception("编码器没有输出任何数据，压缩失败");
            if (frames <= 0) throw new Exception("没有编码出任何视频帧，压缩失败");
            Log.i(TAG, "COMPRESS_PROGRESS p=100 " + statusText() + " frames=" + frames);
        }

        /** 返回 true 表示编码器已输出 EOS。 */
        private boolean drainEncoder() throws Exception {
            while (true) {
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                int outIdx = encoder.dequeueOutputBuffer(bi, 0);
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = encoder.getOutputFormat();
                    injectColorInfo(outFormat, info);
                    videoTrack = muxer.addTrack(outFormat);
                    if (audioFormat != null) {
                        audioTrack = muxer.addTrack(audioFormat);
                    }
                    muxer.start();
                    muxerStarted = true;
                    Log.i(TAG, "COMPRESS_MUX_START videoTrack=" + videoTrack
                            + " audioTrack=" + audioTrack + " format=" + outFormat);
                    continue;
                }
                if (outIdx < 0) continue;

                ByteBuffer ob = encoder.getOutputBuffer(outIdx);
                boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
                if (bi.size > 0) {
                    if (!muxerStarted) throw new Exception("编码器输出格式尚未就绪");
                    if (ob == null) throw new Exception("编码器输出缓冲区不可用");
                    long pts = shift(bi.presentationTimeUs);
                    if (audio != null && audioTrack >= 0) {
                        audio.writeUpTo(muxer, audioTrack, pts);
                        audioSamples = audio.samplesWritten();
                    }
                    ob.position(bi.offset);
                    ob.limit(bi.offset + bi.size);
                    bi.presentationTimeUs = pts;
                    muxer.writeSampleData(videoTrack, ob, bi);
                    frames++;
                    if (pts > maxPtsUs) maxPtsUs = pts;
                }
                encoder.releaseOutputBuffer(outIdx, false);
                if (eos) return true;
            }
        }

        private long shift(long pts) {
            long out = pts - shiftUs;
            return out < 0 ? 0 : out;
        }

        private void onDecodedFrame(long ptsUs, long firstInputPts) {
            int percent = 0;
            if (durationUs > 0) {
                long rel = ptsUs - shiftUs;
                if (rel < 0) rel = 0;
                percent = (int) Math.min(99, rel * 100 / durationUs);
            }
            long now = System.currentTimeMillis();
            if (percent != lastPercent || now - lastCallbackMs >= 300) {
                lastPercent = percent;
                lastCallbackMs = now;
                notifyProgress(cb, percent, statusText());
            }
            if (percent >= lastLoggedPercent + 10) {
                lastLoggedPercent = percent;
                Log.i(TAG, "COMPRESS_PROGRESS p=" + percent + " " + statusText()
                        + " ptsUs=" + ptsUs);
            }
        }

        private String statusText() {
            String s = "转码中 " + framesDecoded + " 帧";
            if (attempt > 0) s = "重试 " + attempt + "/" + MAX_RETRIES + " · " + s;
            return s;
        }

        long outputDurationMs() {
            long last = maxPtsUs < 0 ? 0 : maxPtsUs;
            long frameDurUs = Math.max(1, 1_000_000L / Math.max(1, fps));
            long durUs = last + frameDurUs;
            return Math.max(1, durUs / 1000);
        }
    }

    // ------------------------------------------------------------------
    // 编码器 / 解码器选择与配置
    // ------------------------------------------------------------------

    private static final class EncoderChoice {
        final MediaCodec codec;
        final Surface surface;
        final String name;
        final int variant;

        EncoderChoice(MediaCodec codec, Surface surface, String name, int variant) {
            this.codec = codec;
            this.surface = surface;
            this.name = name;
            this.variant = variant;
        }
    }

    /** 编码器配置的多个回退档位：优先 VBR + 色彩 + 禁 B 帧。 */
    private static final int VARIANT_FULL = 0;
    private static final int VARIANT_NO_BFRAMES = 1;
    private static final int VARIANT_NO_VBR = 2;
    private static final int VARIANT_MIN = 3;

    private static MediaFormat buildEncoderFormat(String mime, MediaInfo info,
                                                  long videoBps, int fps, int variant) {
        MediaFormat format = MediaFormat.createVideoFormat(mime, info.width, info.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE,
                (int) Math.min(Integer.MAX_VALUE, Math.max(1, videoBps)));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (variant <= VARIANT_NO_BFRAMES) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        }
        if (variant <= VARIANT_NO_VBR) {
            if (info.colorStandard != -1) {
                format.setInteger(MediaFormat.KEY_COLOR_STANDARD, info.colorStandard);
            }
            if (info.colorTransfer != -1) {
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, info.colorTransfer);
            }
            if (info.colorRange != -1) {
                format.setInteger(MediaFormat.KEY_COLOR_RANGE, info.colorRange);
            }
        }
        if (variant == VARIANT_FULL) {
            // 兼容性硬约束：输出不能有 B 帧（部分硬件编码器默认会开）
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        }
        return format;
    }

    private static EncoderChoice createEncoder(String mime, MediaInfo info,
                                               long videoBps, int fps) throws Exception {
        List<MediaCodecInfo> candidates = new ArrayList<>();
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo ci : list.getCodecInfos()) {
                try {
                    if (!ci.isEncoder() || !supportsType(ci, mime)) continue;
                    MediaCodecInfo.CodecCapabilities caps = ci.getCapabilitiesForType(mime);
                    if (!supportsSurface(caps)) continue;
                    MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                    if (vc != null && !vc.isSizeSupported(info.width, info.height)) continue;
                    candidates.add(ci);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "COMPRESS_ENC_LIST_FAIL", e);
        }
        Collections.sort(candidates, HW_FIRST);

        List<String> names = new ArrayList<>();
        for (MediaCodecInfo ci : candidates) {
            if (!names.contains(ci.getName())) names.add(ci.getName());
        }
        // 最后兜底：系统默认编码器
        names.add(null);

        List<String> errors = new ArrayList<>();
        for (String name : names) {
            for (int variant = VARIANT_FULL; variant <= VARIANT_MIN; variant++) {
                MediaCodec codec = null;
                Surface surface = null;
                try {
                    codec = name == null ? MediaCodec.createEncoderByType(mime)
                            : MediaCodec.createByCodecName(name);
                    MediaFormat format = buildEncoderFormat(mime, info, videoBps, fps, variant);
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    surface = codec.createInputSurface();
                    if (variant > VARIANT_FULL) {
                        Log.w(TAG, "COMPRESS_ENC_FALLBACK name=" + codec.getName()
                                + " variant=" + variant + "（前档配置被编码器拒绝）");
                    }
                    return new EncoderChoice(codec, surface, codec.getName(), variant);
                } catch (Exception e) {
                    errors.add((name == null ? "<default>" : name) + " variant" + variant
                            + ": " + describe(e));
                    if (surface != null) {
                        try {
                            surface.release();
                        } catch (Exception ignored) {
                        }
                    }
                    if (codec != null) {
                        try {
                            codec.release();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder("没有可用的 ")
                .append(MIME_HEVC.equals(mime) ? "HEVC" : "H.264")
                .append(" 编码器支持 ").append(info.width).append('×').append(info.height)
                .append("（已尝试 ").append(names.size()).append(" 个编码器）");
        for (int i = 0; i < Math.min(6, errors.size()); i++) {
            sb.append("\n  - ").append(errors.get(i));
        }
        throw new Exception(sb.toString());
    }

    private static MediaCodec createDecoder(String mime, int width, int height,
                                            MediaFormat videoFormat, Surface surface)
            throws Exception {
        List<MediaCodecInfo> candidates = new ArrayList<>();
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo ci : list.getCodecInfos()) {
                try {
                    if (ci.isEncoder() || !supportsType(ci, mime)) continue;
                    MediaCodecInfo.CodecCapabilities caps = ci.getCapabilitiesForType(mime);
                    if (!supportsSurface(caps)) continue;
                    MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                    if (vc != null && !vc.isSizeSupported(width, height)) continue;
                    candidates.add(ci);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "COMPRESS_DEC_LIST_FAIL", e);
        }
        Collections.sort(candidates, HW_FIRST);

        List<String> names = new ArrayList<>();
        for (MediaCodecInfo ci : candidates) {
            if (!names.contains(ci.getName())) names.add(ci.getName());
        }

        List<String> errors = new ArrayList<>();
        for (String name : names) {
            MediaCodec codec = null;
            try {
                codec = MediaCodec.createByCodecName(name);
                codec.configure(videoFormat, surface, null, 0);
                return codec;
            } catch (Exception e) {
                errors.add(name + ": " + describe(e));
                if (codec != null) {
                    try {
                        codec.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        try {
            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(videoFormat, surface, null, 0);
            return codec;
        } catch (Exception e) {
            errors.add("<default>: " + describe(e));
        }

        StringBuilder sb = new StringBuilder("没有可用的 ")
                .append(MIME_HEVC.equals(mime) ? "HEVC" : "H.264")
                .append(" 解码器能输出到编码器 Surface（").append(width).append('×')
                .append(height).append("）");
        for (int i = 0; i < Math.min(4, errors.size()); i++) {
            sb.append("\n  - ").append(errors.get(i));
        }
        throw new Exception(sb.toString());
    }

    private static final Comparator<MediaCodecInfo> HW_FIRST =
            new Comparator<MediaCodecInfo>() {
                @Override
                public int compare(MediaCodecInfo a, MediaCodecInfo b) {
                    int ha = a.isHardwareAccelerated() ? 0 : 1;
                    int hb = b.isHardwareAccelerated() ? 0 : 1;
                    if (ha != hb) return ha - hb;
                    int sa = a.isSoftwareOnly() ? 1 : 0;
                    int sb = b.isSoftwareOnly() ? 1 : 0;
                    return sa - sb;
                }
            };

    private static boolean supportsType(MediaCodecInfo ci, String mime) {
        String[] types = ci.getSupportedTypes();
        if (types == null) return false;
        for (String type : types) {
            if (type != null && type.equalsIgnoreCase(mime)) return true;
        }
        return false;
    }

    private static boolean supportsSurface(MediaCodecInfo.CodecCapabilities caps) {
        if (caps == null || caps.colorFormats == null) return false;
        for (int cf : caps.colorFormats) {
            if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) return true;
        }
        return false;
    }

    /** 把源色彩信息补进编码器输出格式，尽量让 MediaMuxer 写出 colr box。 */
    private static void injectColorInfo(MediaFormat format, MediaInfo info) {
        if (format == null) return;
        try {
            if (info.colorStandard != -1 && !format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                format.setInteger(MediaFormat.KEY_COLOR_STANDARD, info.colorStandard);
            }
            if (info.colorTransfer != -1 && !format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, info.colorTransfer);
            }
            if (info.colorRange != -1 && !format.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                format.setInteger(MediaFormat.KEY_COLOR_RANGE, info.colorRange);
            }
        } catch (Exception e) {
            Log.w(TAG, "COMPRESS_COLOR_FORMAT_FAIL", e);
        }
    }

    // ------------------------------------------------------------------
    // AAC 音轨原样复制
    // ------------------------------------------------------------------

    private static final class AacCopier {
        private final MediaExtractor ex;
        private final long shiftUs;
        private final ByteBuffer buf;
        private final ArrayDeque<AudioSample> queue = new ArrayDeque<>();
        private boolean eos;
        private long written;

        AacCopier(MediaExtractor ex, MediaFormat format, long shiftUs) {
            this.ex = ex;
            this.shiftUs = shiftUs;
            int max = 64 * 1024;
            try {
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    max = Math.max(max, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
            } catch (Exception ignored) {
            }
            this.buf = ByteBuffer.allocateDirect(max);
        }

        long samplesWritten() {
            return written;
        }

        /** 写出 PTS <= upToUs 的音频样本；upToUs 用 Long.MAX_VALUE 表示全部写完。 */
        void writeUpTo(MediaMuxer muxer, int track, long upToUs) {
            while (true) {
                if (queue.isEmpty() && !readNext()) return;
                AudioSample sample = queue.peekFirst();
                if (sample.ptsUs > upToUs) return;
                queue.pollFirst();
                ByteBuffer data = ByteBuffer.wrap(sample.data);
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                bi.set(0, sample.data.length, sample.ptsUs, 0);
                muxer.writeSampleData(track, data, bi);
                written++;
            }
        }

        private boolean readNext() {
            if (eos) return false;
            buf.clear();
            int size = ex.readSampleData(buf, 0);
            if (size < 0) {
                eos = true;
                return false;
            }
            if (size > buf.capacity()) {
                Log.w(TAG, "COMPRESS_AUDIO sample too large " + size);
                eos = true;
                return false;
            }
            long pts = ex.getSampleTime();
            if (pts < 0) pts = 0;
            long shifted = pts - shiftUs;
            if (shifted < 0) shifted = 0;
            byte[] data = new byte[size];
            buf.position(0);
            buf.limit(size);
            buf.get(data);
            queue.addLast(new AudioSample(data, shifted));
            ex.advance();
            return true;
        }
    }

    private static final class AudioSample {
        final byte[] data;
        final long ptsUs;

        AudioSample(byte[] data, long ptsUs) {
            this.data = data;
            this.ptsUs = ptsUs;
        }
    }

    // ------------------------------------------------------------------
    // MediaStore 输出
    // ------------------------------------------------------------------



    private static void savePendingUri(Context ctx, Uri uri) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_PENDING_URI, uri.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static void clearPendingUri(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_PENDING_URI).apply();
        } catch (Exception ignored) {
        }
    }

    private static void dumpCompressedRows(ContentResolver cr) {
        try (Cursor c = cr.query(MediaStore.Files.getContentUri("external"),
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA,
                        MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.IS_PENDING,
                        MediaStore.Video.Media.DISPLAY_NAME},
                null, null, null)) {
            if (c == null) return;
            int n = 0;
            int idI = c.getColumnIndex(MediaStore.MediaColumns._ID);
            int dataI = c.getColumnIndex(MediaStore.MediaColumns.DATA);
            int relI = c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH);
            int pendI = c.getColumnIndex(MediaStore.Video.Media.IS_PENDING);
            int nameI = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
            while (c.moveToNext() && n < 12) {
                String data = dataI >= 0 ? c.getString(dataI) : null;
                if (data == null || !data.contains("/Compressed/")) continue;
                n++;
                Log.w(TAG, "COMPRESS_DB_ROW id=" + (idI >= 0 ? c.getLong(idI) : -1)
                        + " data=" + data
                        + " rel=" + (relI >= 0 ? c.getString(relI) : null)
                        + " pending=" + (pendI >= 0 ? c.getInt(pendI) : -1)
                        + " name=" + (nameI >= 0 ? c.getString(nameI) : null));
            }
            Log.w(TAG, "COMPRESS_DB_ROW count=" + n);
        } catch (Exception e) {
            Log.w(TAG, "COMPRESS_DB_DUMP_FAIL", e);
        }
    }

    private static Uri insertPending(Context ctx, CompressOptions opt) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, opt.outputName());
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/Compressed");
        cv.put(MediaStore.Video.Media.IS_PENDING, 1);
        if (opt.info.dateTakenMs > 0) {
            cv.put(MediaStore.MediaColumns.DATE_TAKEN, opt.info.dateTakenMs);
        }
        Uri uri;
        try {
            uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        } catch (IllegalArgumentException iae) {
            // 个别系统不接受 datetaken 写入，去掉再插
            Log.w(TAG, "COMPRESS_INSERT_RETRY", iae);
            cv.remove(MediaStore.MediaColumns.DATE_TAKEN);
            uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        }
        if (uri == null) {
            // 极端情况：上次进程被杀留下的 DB 行还占着同一个 _data（UNIQUE 约束），先清再插
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES), "Compressed");
                String data = new File(dir, opt.outputName()).getAbsolutePath();
                int gone = cr.delete(MediaStore.Files.getContentUri("external"),
                        MediaStore.MediaColumns.DATA + "=?", new String[]{data});
                Log.w(TAG, "COMPRESS_INSERT_CONFLICT data=" + data + " removed=" + gone);
                dumpCompressedRows(cr);
                // 兜底：换一个唯一文件名再插（残留行无法删除时至少让压缩能完成）
                String alt = opt.outputName();
                int dot = alt.lastIndexOf('.');
                String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                        java.util.Locale.US).format(new java.util.Date());
                alt = (dot > 0 ? alt.substring(0, dot) : alt) + "_" + ts + ".mp4";
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, alt);
                uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            } catch (Exception e) {
                Log.w(TAG, "COMPRESS_INSERT_CONFLICT_FAIL", e);
            }
        }
        if (uri == null) throw new Exception("创建相册输出文件失败（MediaStore insert 返回 null）");
        return uri;
    }

    private static ParcelFileDescriptor openOutputPfd(Context ctx, Uri uri) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        ParcelFileDescriptor pfd = null;
        try {
            pfd = cr.openFileDescriptor(uri, "rw");
        } catch (Exception e) {
            Log.w(TAG, "COMPRESS_OPEN_RW_FAIL uri=" + uri, e);
        }
        if (pfd == null) {
            pfd = cr.openFileDescriptor(uri, "w");
        }
        if (pfd == null) throw new Exception("无法打开输出文件描述符");
        return pfd;
    }

    private static long queryStoreSize(ContentResolver cr, Uri uri) {
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{MediaStore.Video.Media.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(MediaStore.Video.Media.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    private static void publish(ContentResolver cr, Uri uri) throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.IS_PENDING, 0);
        int updated = cr.update(uri, cv, null, null);
        if (updated <= 0) throw new Exception("发布到相册失败（更新行数 0）");
        Log.i(TAG, "COMPRESS_PUBLISH uri=" + uri + " updated=" + updated);
    }

    /** 用 MediaExtractor 重新打开 pending 输出，确认 moov/轨道正常。 */
    private static void validateOutput(Context ctx, Uri uri, MediaInfo src) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, uri, null);
            int tracks = ex.getTrackCount();
            boolean hasVideo = false;
            int w = -1;
            int h = -1;
            String mime = null;
            StringBuilder trackLog = new StringBuilder();
            for (int i = 0; i < tracks; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) {
                    hasVideo = true;
                    w = intOr(f, MediaFormat.KEY_WIDTH, -1);
                    h = intOr(f, MediaFormat.KEY_HEIGHT, -1);
                    mime = m;
                    trackLog.append(" [").append(i).append("]video ").append(m).append(' ')
                            .append(w).append('x').append(h)
                            .append(" color=").append(intOr(f, MediaFormat.KEY_COLOR_STANDARD, -1))
                            .append('/').append(intOr(f, MediaFormat.KEY_COLOR_TRANSFER, -1))
                            .append('/').append(intOr(f, MediaFormat.KEY_COLOR_RANGE, -1));
                } else if (m != null && m.startsWith("audio/")) {
                    trackLog.append(" [").append(i).append("]audio ").append(m)
                            .append(' ').append(intOr(f, MediaFormat.KEY_SAMPLE_RATE, -1))
                            .append("Hz ").append(intOr(f, MediaFormat.KEY_CHANNEL_COUNT, -1))
                            .append("ch");
                }
            }
            Log.i(TAG, "COMPRESS_VALIDATE uri=" + uri + " tracks=" + tracks
                    + " videoMime=" + mime + " " + w + "x" + h
                    + " rotation=" + intOr(ex, MediaFormat.KEY_ROTATION, 0) + trackLog);
            if (!hasVideo) throw new Exception("输出文件没有视频轨，压缩失败");
            if (mime == null || !mime.equals(src.videoMime)) {
                throw new Exception("输出编码 " + mime + " 与源 " + src.videoMime + " 不一致");
            }
        } finally {
            ex.release();
        }
    }

    private static void applyOrientation(MediaMuxer muxer, int rotation) {
        int rot = ((rotation % 360) + 360) % 360;
        if (rot == 0) return;
        if (rot != 90 && rot != 180 && rot != 270) {
            Log.w(TAG, "COMPRESS_ORIENTATION skip invalid " + rotation);
            return;
        }
        try {
            muxer.setOrientationHint(rot);
        } catch (Exception e) {
            Log.w(TAG, "COMPRESS_ORIENTATION fail " + rot, e);
        }
    }

    /** 解析 MediaMetadataRetriever 的 ISO-6709 位置串，如 +31.963+5.887/。 */
    private static void applyLocation(MediaMuxer muxer, String location) {
        if (location == null) return;
        try {
            String s = location.trim();
            if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            if (s.isEmpty()) return;
            int second = -1;
            for (int i = 1; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '+' || c == '-') {
                    second = i;
                    break;
                }
            }
            if (second < 0) return;
            int third = -1;
            for (int i = second + 1; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '+' || c == '-') {
                    third = i;
                    break;
                }
            }
            String latStr = s.substring(0, second);
            String lonStr = s.substring(second, third < 0 ? s.length() : third);
            double lat = Double.parseDouble(latStr);
            double lon = Double.parseDouble(lonStr);
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                Log.w(TAG, "COMPRESS_LOCATION out of range " + location);
                return;
            }
            muxer.setLocation((float) lat, (float) lon);
        } catch (Exception e) {
            Log.w(TAG, "COMPRESS_LOCATION parse fail " + location, e);
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static void checkCancel(AtomicBoolean cancel) throws CancelledException {
        if (cancel != null && cancel.get()) throw new CancelledException();
    }

    private static void notifyProgress(Callback cb, int percent, String status) {
        if (cb == null) return;
        try {
            cb.onProgress(percent, status);
        } catch (Exception e) {
            Log.w(TAG, "COMPRESS_CALLBACK_FAIL", e);
        }
    }

    private static int findTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static int intOr(MediaFormat f, String key, int def) {
        if (f == null) return def;
        try {
            return f.containsKey(key) ? f.getInteger(key) : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static int intOr(MediaExtractor ex, String key, int def) {
        try {
            // 输出 track 的旋转信息：优先第一条视频轨的 format
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    return intOr(f, key, def);
                }
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    private static long longOr(MediaFormat f, String key, long def) {
        try {
            return f.containsKey(key) ? f.getLong(key) : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd == null) return;
        try {
            pfd.close();
        } catch (Exception ignored) {
        }
    }

    private static String describe(Throwable t) {
        if (t == null) return "null";
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
