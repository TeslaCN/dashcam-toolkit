package icu.wwj.drive.stitch;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import icu.wwj.drive.stitch.gl.CanvasRenderer;
import icu.wwj.drive.stitch.gl.EglCore;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把 N 路视频硬解到纹理，按模板合成，再硬编码写出 MP4。
 * 全程 Surface 零拷贝：解码器 -> SurfaceTexture -> GL 画布 -> 编码器 input Surface。
 *
 * 关键点：解码器会跑得比渲染快，SurfaceTexture 只保留最新帧，不节流就会丢帧。
 * 这里按"已渲染 PTS + 1 帧"限制每路解码器的喂入，保证输出帧不被丢掉。
 */
public class Composer {

    private static final String TAG = "Composer";
    private static final int FPS = 30;
    private static final long BOOST_WINDOW_US = 15_000_000L;
    private static final long FRAME_US = 1_000_000L / FPS;
    /** 允许停留在解码器+SurfaceTexture 里的帧数上限 */
    private static final int MAX_INFLIGHT = 2;

    public static class Config {
        public Uri[] uris;          // [主视角, 侧1, 侧2, 侧3]
        public int durationSec;     // 0 = 全部
        public long targetBytes = 70_000_000L;  // 目标文件大小；<=0 时按 bppMilli 估算
        public int bppMilli = 25;
        public boolean boostEnd;    // 末尾 15 秒提高码率
    }

    public interface Callback {
        void onProgress(int percent, String status);

        void onLog(String msg);

        void onResult(boolean ok, String message);
    }

    private final Context mContext;
    private final Config mConfig;
    private final FileDescriptor mOutputFd;
    private final Callback mCb;

    private final List<Source> mSources = new ArrayList<>();
    private EglCore mEgl;
    private EGLSurface mEglSurface;
    private CanvasRenderer mRenderer;
    private MediaCodec mEncoder;
    private MediaMuxer mMuxer;
    private Surface mEncoderInput;
    private int mVideoTrack = -1;
    private CountDownLatch mEncoderDone = new CountDownLatch(1);

    private int mCanvasW, mCanvasH;
    private int mTileW, mTileH, mMainDrawH;
    private int mOutW, mOutH;
    private long mDurationUs;
    private int mBaseBitrate, mBoostBitrate;
    private volatile boolean mCancelled;
    private volatile boolean mReleasing;
    private volatile boolean mEncoderError;
    private volatile boolean mMuxerStarted;
    private volatile int mSamplesWritten;
    private String mCodecName = "";
    private String mEncoderMime = "video/avc";
    private HandlerThread mDecThread, mEncThread;

    public Composer(Context ctx, Config cfg, FileDescriptor out, Callback cb) {
        mContext = ctx;
        mConfig = cfg;
        mOutputFd = out;
        mCb = cb;
    }

    public void cancel() {
        mCancelled = true;
    }

    private void log(String s) {
        Log.i(TAG, s);
        mCb.onLog(s);
    }

    // ------------------------------------------------------------------

    public void run() {
        boolean ok = false;
        String msg = "";
        try {
            openSources();
            computeLayout();
            startEncoderOrFallback();
            setupGl();
            setupDecoders();
            int frames = renderLoop();
            if (!mCancelled && frames <= 0) throw new java.io.IOException("没有渲染出任何帧");
            finishEncoding();
            ok = true;
            msg = mOutW + "x" + mOutH + " · " + frames + " 帧 · " + mCodecName;
        } catch (Throwable t) {
            Log.e(TAG, "stitch failed", t);
            msg = String.valueOf(t.getMessage());
            if (msg == null) msg = t.getClass().getSimpleName();
        } finally {
            releaseAll();
        }
        mCb.onResult(ok && !mCancelled, mCancelled ? "已取消" : msg);
    }

    // ------------------------------------------------------------------

    private void openSources() throws Exception {
        if (mConfig.uris == null || mConfig.uris.length < 2)
            throw new IllegalArgumentException("至少需要 2 路视角");
        long minDur = Long.MAX_VALUE;
        for (Uri u : mConfig.uris) {
            MediaExtractor ex = new MediaExtractor();
            try {
                ex.setDataSource(mContext, u, null);
            } catch (Exception e) {
                try { ex.release(); } catch (Exception ignored) { }
                throw e;
            }
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) {
                try { ex.release(); } catch (Exception ignored) { }
                throw new IllegalStateException("没有视频轨: " + u);
            }
            ex.selectTrack(track);
            MediaFormat fmt;
            try {
                fmt = ex.getTrackFormat(track);
            } catch (Exception e) {
                try { ex.release(); } catch (Exception ignored) { }
                throw e;
            }
            Source s = new Source();
            s.uri = u;
            s.extractor = ex;
            s.format = fmt;
            s.mime = fmt.getString(MediaFormat.KEY_MIME);
            s.width = fmt.getInteger(MediaFormat.KEY_WIDTH);
            s.height = fmt.getInteger(MediaFormat.KEY_HEIGHT);
            if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                s.durationUs = fmt.getLong(MediaFormat.KEY_DURATION);
                minDur = Math.min(minDur, s.durationUs);
            }
            mSources.add(s);
        }
        if (minDur == Long.MAX_VALUE) minDur = 0;
        long limit = mConfig.durationSec > 0 ? mConfig.durationSec * 1_000_000L : minDur;
        mDurationUs = Math.min(limit, minDur > 0 ? minDur : limit);
        if (mDurationUs <= 0) throw new IllegalStateException("无法确定时长");
        log("素材 " + mSources.size() + " 路 · 时长 " + (mDurationUs / 1_000_000L) + "s");
    }

    private void computeLayout() {
        Source main = mSources.get(0);
        Source side = mSources.get(1);
        int n = mSources.size() - 1;
        int tileW = even(main.width / n);
        int tileH = even((int) ((long) tileW * side.height / side.width));
        mTileW = tileW;
        mTileH = tileH;
        mCanvasW = tileW * n;
        mMainDrawH = (int) ((long) main.height * mCanvasW / main.width);
        mCanvasH = even(mMainDrawH + tileH);
        log("画布 " + mCanvasW + "x" + mCanvasH);
    }

    private void startEncoderOrFallback() throws Exception {
        try {
            chooseEncoder(false);
            startEncoder();
        } catch (Exception e) {
            Log.w(TAG, "HEVC 编码器不可用，回退 H.264", e);
            log("HEVC 编码器不可用，回退 H.264");
            stopEncoderQuietly();
            mEncoderDone = new CountDownLatch(1);
            mEncoderError = false;
            mMuxerStarted = false;
            mSamplesWritten = 0;
            mVideoTrack = -1;
            chooseEncoder(true);
            startEncoder();
        }
    }

    private void stopEncoderQuietly() {
        if (mEncoder != null) {
            try { mEncoder.stop(); } catch (Exception ignored) { }
            try { mEncoder.release(); } catch (Exception ignored) { }
            mEncoder = null;
        }
        try { if (mEncoderInput != null) mEncoderInput.release(); } catch (Exception ignored) { }
        mEncoderInput = null;
        if (mMuxer != null) {
            try { mMuxer.stop(); } catch (Exception ignored) { }
            try { mMuxer.release(); } catch (Exception ignored) { }
            mMuxer = null;
        }
    }

    private void chooseEncoder(boolean avcOnly) throws Exception {
        String[] mimes = avcOnly ? new String[]{"video/avc"} : new String[]{"video/hevc", "video/avc"};
        Exception last = null;
        for (String mime : mimes) {
            try {
                MediaCodecInfo info = findEncoderFor(mime);
                if (info == null) continue;
                MediaCodecInfo.VideoCapabilities vc = info.getCapabilitiesForType(mime).getVideoCapabilities();
                double f = 1.0;
                if (!vc.isSizeSupported(mCanvasW, mCanvasH)) {
                    Range<Integer> wr = vc.getSupportedWidths();
                    Range<Integer> hr = vc.getSupportedHeights();
                    f = Math.min((double) wr.getUpper() / mCanvasW, (double) hr.getUpper() / mCanvasH);
                    if (f >= 1.0) f = 0.99;
                }
                int w = even((int) (mCanvasW * f));
                int h = even((int) (mCanvasH * f));
                while (!vc.isSizeSupported(w, h) && w > 320) {
                    w -= 2;
                    h = even((int) ((long) w * mCanvasH / mCanvasW));
                }
                mOutW = w;
                mOutH = h;
                mCodecName = info.getName();
                mEncoderMime = mime;
                if (f < 0.999) log("编码器上限，缩放为 " + w + "x" + h);

                Range<Integer> br = vc.getBitrateRange();
                long px = (long) mOutW * mOutH;
                double durS = mDurationUs / 1e6;
                boolean doBoost = mConfig.boostEnd && durS > 40;
                double win = doBoost ? Math.min(15.0, durS) : 0;
                double totalBits;
                if (mConfig.targetBytes > 0) {
                    // 硬件编码器实际码率会略高于目标，留 10% 余量
                    totalBits = mConfig.targetBytes * 8.0 * 0.82;
                } else {
                    totalBits = px * FPS * (mConfig.bppMilli / 1000.0) * durS;
                }
                double base = totalBits / (durS + (3.0 - 1) * win);
                // 目标太小：编码器有码率下限，提码率只会让体积超标，这里取消末尾提码率
                if (doBoost && base < 900_000) {
                    doBoost = false;
                    win = 0;
                    base = totalBits / durS;
                    log("目标体积过小，已取消末尾提码率");
                }
                double clamped = Math.min(base, br.getUpper());
                // 下限用编码器自身允许的最小码率，避免小目标体积被硬性抬高
                int floor = Math.max(br.getLower(), 150_000);
                int b = (int) Math.max(clamped, floor);

                // 目标码率低到单位像素不够分时，降分辨率比硬压码率更有效
                double bpp = b / ((double) mOutW * mOutH * FPS);
                if (bpp < 0.008 && mOutW > 1280) {
                    double sc = Math.sqrt(bpp / 0.008);
                    int nw = even((int) (mOutW * sc));
                    int nh = even((int) (mOutH * sc));
                    if (nw >= 1280) {
                        log("目标体积较小，分辨率降为 " + nw + "x" + nh);
                        mOutW = nw;
                        mOutH = nh;
                    }
                }
                mBaseBitrate = b;
                mBoostBitrate = (doBoost && win > 0) ? Math.min((int) (b * 3.0), br.getUpper()) : b;
                log("编码 " + mOutW + "x" + mOutH + " " + (b / 1000) + "kbps" + (win > 0 ? " (末尾提码率)" : ""));
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("没有可用的视频编码器");
    }

    private MediaCodecInfo findEncoderFor(String mime) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo ci : list.getCodecInfos()) {
            if (!ci.isEncoder()) continue;
            for (String t : ci.getSupportedTypes()) {
                if (t.equalsIgnoreCase(mime)) return ci;
            }
        }
        return null;
    }

    private void startEncoder() throws Exception {
        MediaFormat fmt = MediaFormat.createVideoFormat(mEncoderMime, mOutW, mOutH);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, mBaseBitrate);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        mEncThread = new HandlerThread("enc");
        mEncThread.start();
        final Handler h = new Handler(mEncThread.getLooper());

        mEncoder = MediaCodec.createByCodecName(mCodecName);
        mEncoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                if (mReleasing) return;
                try {
                    if (info.size > 0 && mVideoTrack >= 0) {
                        ByteBuffer buf = codec.getOutputBuffer(index);
                        if (buf != null) {
                            buf.position(info.offset);
                            buf.limit(info.offset + info.size);
                            mMuxer.writeSampleData(mVideoTrack, buf, info);
                            mSamplesWritten++;
                        }
                    }
                    codec.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        mEncoderDone.countDown();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "muxer write failed", e);
                    mEncoderError = true;
                    mEncoderDone.countDown();
                }
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                Log.e(TAG, "encoder error", e);
                mEncoderError = true;
                mEncoderDone.countDown();
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                try {
                    mMuxer = new MediaMuxer(mOutputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    mVideoTrack = mMuxer.addTrack(format);
                    mMuxer.start();
                    mMuxerStarted = true;
                } catch (Exception e) {
                    Log.e(TAG, "muxer start failed", e);
                    mEncoderError = true;
                    mEncoderDone.countDown();
                }
            }
        }, h);

        mEncoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoderInput = mEncoder.createInputSurface();
        mEncoder.start();
    }

    private void setupGl() throws Exception {
        mEgl = new EglCore();
        mEglSurface = mEgl.createWindowSurface(mEncoderInput);
        mEgl.makeCurrent(mEglSurface);
        GLES20.glViewport(0, 0, mOutW, mOutH);
        mRenderer = new CanvasRenderer(mCanvasW, mCanvasH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
    }

    private void setupDecoders() throws Exception {
        mDecThread = new HandlerThread("dec");
        mDecThread.start();
        final Handler h = new Handler(mDecThread.getLooper());
        for (final Source s : mSources) {
            s.texId = CanvasRenderer.createOesTexture();
            s.surfaceTexture = new SurfaceTexture(s.texId);
            s.surfaceTexture.setDefaultBufferSize(s.width, s.height);
            s.surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture st) {
                    s.frameAvailable.set(true);
                }
            }, h);
            s.surface = new Surface(s.surfaceTexture);
            s.codec = createDecoder(s, h);
            s.codec.start();
        }
        log("解码器就绪");
    }

    private MediaCodec createDecoder(final Source s, Handler h) {
        MediaCodec codec = null;
        Exception first = null;
        try {
            codec = MediaCodec.createDecoderByType(s.mime);
            codec.setCallback(newDecoderCallback(s), h);
            codec.configure(s.format, s.surface, null, 0);
            return codec;
        } catch (Exception e) {
            first = e;
            if (codec != null) {
                try {
                    codec.release();
                } catch (Exception ignored) {
                }
            }
        }
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo ci : list.getCodecInfos()) {
            if (ci.isEncoder() || ci.isHardwareAccelerated()) continue;
            for (String t : ci.getSupportedTypes()) {
                if (!t.equalsIgnoreCase(s.mime)) continue;
                try {
                    codec = MediaCodec.createByCodecName(ci.getName());
                    codec.setCallback(newDecoderCallback(s), h);
                    codec.configure(s.format, s.surface, null, 0);
                    log("软件解码 " + ci.getName());
                    return codec;
                } catch (Exception e) {
                    if (codec != null) {
                        try {
                            codec.release();
                        } catch (Exception ignored) {
                        }
                        codec = null;
                    }
                }
            }
        }
        throw new RuntimeException("无法创建解码器 " + s.mime, first);
    }

    private MediaCodec.Callback newDecoderCallback(final Source s) {
        return new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                if (mReleasing) return;
                synchronized (s) {
                    s.pendingInputs.add(index);
                    s.inputCallbacks++;
                    pumpLocked(s);
                }
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                if (mReleasing) return;
                try {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        s.outputDone = true;
                    }
                    codec.releaseOutputBuffer(index, true);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        s.outCount++;
                        s.frameAvailable.set(true);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "release failed", e);
                }
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                Log.e(TAG, "decoder error " + s.uri, e);
                s.outputDone = true;
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            }
        };
    }

    /** 按"在途帧数"喂样本：解码器 + SurfaceTexture 里最多 2 帧，喂一帧就得消化一帧 */
    private void pumpLocked(Source s) {
        if (mReleasing || s.inputDone || s.codec == null) return;
        while (!s.pendingInputs.isEmpty()) {
            if (s.fed - s.consumed >= MAX_INFLIGHT) return;
            int idx = s.pendingInputs.poll();
            try {
                ByteBuffer buf = s.codec.getInputBuffer(idx);
                int size = s.extractor.readSampleData(buf, 0);
                if (size < 0) {
                    s.codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    s.inputDone = true;
                    Log.i(TAG, "EOS " + s.uri.getLastPathSegment() + " fed=" + s.fed + " out=" + s.outCount);
                    return;
                }
                long pts = s.extractor.getSampleTime();
                s.codec.queueInputBuffer(idx, 0, size, pts, 0);
                s.fed++;
                s.extractor.advance();
            } catch (Exception e) {
                Log.e(TAG, "feed failed", e);
                return;
            }
        }
    }

    private int renderLoop() throws Exception {
        long lastContentPts = -1;
        long nextOutPts = 0;
        int lastPct = -1;
        int rendered = 0;
        int duplicated = 0;
        boolean primed = false;
        long boostAtUs = mDurationUs - BOOST_WINDOW_US;
        boolean boosted = false;
        long t0 = SystemClock.elapsedRealtime();
        long lastProgressMs = t0;
        long primedAtMs = 0;
        long limitPts = mConfig.durationSec > 0 ? mDurationUs : Long.MAX_VALUE;

        while (!mCancelled) {
            // 1) 取走已就绪的帧，并立刻补喂（消费一帧 -> 喂一帧）
            boolean gotAny = false;
            for (Source s : mSources) {
                if (s.frameAvailable.compareAndSet(true, false)) {
                    gotAny = true;
                    s.surfaceTexture.updateTexImage();
                    long p = s.surfaceTexture.getTimestamp() / 1000L;
                    if (p >= 0) s.ptsUs = p;
                    s.consumed++;
                    synchronized (s) {
                        pumpLocked(s);
                    }
                }
            }

            // 3) 等四路都有首帧（带超时，避免解码器静默失效时死等）
            if (!primed) {
                boolean all = true;
                for (Source s : mSources) if (s.ptsUs < 0) all = false;
                if (all) {
                    primed = true;
                    primedAtMs = SystemClock.elapsedRealtime();
                    lastProgressMs = primedAtMs;
                } else if (allDone()) {
                    break;
                } else {
                    if (primedAtMs == 0) primedAtMs = SystemClock.elapsedRealtime();
                    if (SystemClock.elapsedRealtime() - primedAtMs > 15000) {
                        throw new java.io.IOException("解码器 15 秒没有输出首帧");
                    }
                    Thread.sleep(2);
                    continue;
                }
            }
            if (gotAny) lastProgressMs = SystemClock.elapsedRealtime();
            if (SystemClock.elapsedRealtime() - lastProgressMs > 30000) {
                throw new java.io.IOException("拼接卡住 30 秒，已中止");
            }

            if (primed && mSources.get(0).ptsUs >= 0 && mSources.get(0).ptsUs != lastContentPts) {
                long p = mSources.get(0).ptsUs;
                // 按内容 PTS 推进输出：输出恒定 CFR。源帧率低就补重复帧，
                // 源帧率高就自动跳过（60fps 源 → 30fps 输出），时间轴始终跟随内容。
                int k = 0;
                while (nextOutPts <= p && nextOutPts < limitPts) {
                    if (++k > 900) {          // 单次跳变最多补 30 秒，超过视为素材断裂
                        log("PTS 跳变过大，重新对齐 " + ((p - lastContentPts) / 1000) + "ms");
                        nextOutPts = p;
                        break;
                    }
                    if (mConfig.boostEnd && !boosted && mBoostBitrate > mBaseBitrate && nextOutPts >= boostAtUs) {
                        boosted = true;
                        try {
                            Bundle bp = new Bundle();
                            bp.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, mBoostBitrate);
                            mEncoder.setParameters(bp);
                            log("高码率段 " + (mBoostBitrate / 1000) + "kbps");
                        } catch (Exception e) {
                            Log.w(TAG, "setParameters failed", e);
                        }
                    }
                    drawFrame(nextOutPts);
                    nextOutPts += FRAME_US;
                    rendered++;
                    if (k > 1) duplicated++;
                }
                lastContentPts = p;
                int pct = (int) Math.min(100L, 100L * nextOutPts / mDurationUs);
                if (pct != lastPct) {
                    lastPct = pct;
                    mCb.onProgress(pct, pct + "%");
                }
                if (nextOutPts >= limitPts) break;
            } else if (allDone()) {
                break;
            }
            Thread.sleep(1);
        }
        // 收尾：补到与内容等长（避免最后几帧因 EOS 突发而丢）
        if (mConfig.durationSec == 0 && lastContentPts > 0) {
            long tail = lastContentPts + FRAME_US;
            while (nextOutPts < tail) {
                drawFrame(nextOutPts);
                nextOutPts += FRAME_US;
                rendered++;
                duplicated++;
            }
        }
        long cost = SystemClock.elapsedRealtime() - t0;
        StringBuilder sb = new StringBuilder("结束: ");
        for (Source x : mSources) sb.append(" fed=").append(x.fed).append("/out=").append(x.outCount);
        Log.i(TAG, sb.toString() + " contentPts=" + lastContentPts + " rendered=" + rendered
                + " dup=" + duplicated + " outPts=" + nextOutPts);
        log("渲染 " + rendered + " 帧（补 " + duplicated + "）/ " + cost + "ms");
        return rendered;
    }

    private boolean allDone() {
        for (Source s : mSources) if (!s.outputDone) return false;
        return true;
    }

    private void drawFrame(long ptsUs) {
        GLES20.glViewport(0, 0, mOutW, mOutH);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        Source main = mSources.get(0);
        mRenderer.draw(main.texId, main.texMatrix(), 0, 0, mCanvasW, mMainDrawH);
        int n = mSources.size() - 1;
        for (int i = 1; i <= n; i++) {
            Source s = mSources.get(i);
            float x = (i - 1) * (float) mTileW;
            float ar = s.width / (float) s.height;
            float w = mTileW, h = w / ar;
            if (h > mTileH) {                 // 比格子更高：按高度贴合，左右留黑
                h = mTileH;
                w = h * ar;
            }
            mRenderer.draw(s.texId, s.texMatrix(),
                    x + (mTileW - w) / 2f, mMainDrawH + (mTileH - h) / 2f, w, h);
        }
        mEgl.setPresentationTime(mEglSurface, ptsUs * 1000L);
        mEgl.swapBuffers(mEglSurface);
    }

    private void finishEncoding() throws Exception {
        if (mCancelled) return;
        mEncoder.signalEndOfInputStream();
        if (!mEncoderDone.await(30, TimeUnit.SECONDS)) throw new java.io.IOException("编码器 30 秒未结束");
        if (mEncoderError) throw new java.io.IOException("编码器报错，输出不完整");
        if (!mMuxerStarted) throw new java.io.IOException("封装器未启动，未写出视频");
        if (mSamplesWritten <= 0) throw new java.io.IOException("没有写出任何视频帧");
    }

    private void releaseAll() {
        mReleasing = true;
        // 先停掉回调线程，避免释放 codec/muxer 时与回调并发
        if (mDecThread != null) {
            mDecThread.quitSafely();
            try {
                mDecThread.join(2000);
            } catch (InterruptedException ignored) {
            }
            mDecThread = null;
        }
        for (Source s : mSources) {
            synchronized (s) {
                if (s.codec != null) {
                    try { s.codec.stop(); } catch (Exception ignored) { }
                    try { s.codec.release(); } catch (Exception ignored) { }
                    s.codec = null;
                }
            }
            try { if (s.surface != null) s.surface.release(); } catch (Exception ignored) { }
            try { if (s.surfaceTexture != null) s.surfaceTexture.release(); } catch (Exception ignored) { }
            try { if (s.extractor != null) s.extractor.release(); } catch (Exception ignored) { }
        }
        try {
            if (mEgl != null) {
                mEgl.releaseSurface(mEglSurface);
                mEgl.release();
            }
        } catch (Exception ignored) {
        }
        if (mEncThread != null) {
            mEncThread.quitSafely();
            try {
                mEncThread.join(2000);
            } catch (InterruptedException ignored) {
            }
            mEncThread = null;
        }
        if (mEncoder != null) {
            try { mEncoder.stop(); } catch (Exception ignored) { }
            try { mEncoder.release(); } catch (Exception ignored) { }
            mEncoder = null;
        }
        try { if (mEncoderInput != null) mEncoderInput.release(); } catch (Exception ignored) { }
        if (mMuxer != null) {
            try { mMuxer.stop(); } catch (Exception ignored) { }
            try { mMuxer.release(); } catch (Exception ignored) { }
            mMuxer = null;
        }
    }

    private static int even(int v) {
        return (v % 2 == 0) ? v : v - 1;
    }

    private static class Source {
        Uri uri;
        MediaExtractor extractor;
        MediaFormat format;
        String mime;
        int width, height;
        long durationUs;
        MediaCodec codec;
        SurfaceTexture surfaceTexture;
        Surface surface;
        int texId;
        final AtomicBoolean frameAvailable = new AtomicBoolean(false);
        volatile long ptsUs = -1;
        volatile long consumed = 0;
        final ArrayDeque<Integer> pendingInputs = new ArrayDeque<>();
        volatile boolean inputDone, outputDone;
        volatile int fed, outCount, inputCallbacks;
        private final float[] m = new float[16];

        float[] texMatrix() {
            surfaceTexture.getTransformMatrix(m);
            return m;
        }
    }
}
