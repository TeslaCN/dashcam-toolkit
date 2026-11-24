package icu.wwj.drive.dashcam;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/** H.264 hardware encoder fed from a GL surface. */
public class VideoEncoder {

    private static final String TAG = "DashcamVenc";

    public interface Sink {
        void onVideoFormat(MediaFormat format);

        void onVideoSample(ByteBuffer buffer, MediaCodec.BufferInfo info);
    }

    private MediaCodec codec;
    private Surface inputSurface;
    private Thread thread;
    private volatile boolean running;
    private volatile Throwable error;

    public void start(int width, int height, int fps, int bitrateBps, Sink sink) throws IOException {
        MediaFormat fmt = buildFormat(width, height, fps, bitrateBps, true);
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Log.i(TAG, "configured HEVC with Main profile");
        } catch (Throwable t) {
            Log.w(TAG, "HEVC Main profile not supported, fallback to default profile", t);
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
            fmt = buildFormat(width, height, fps, bitrateBps, false);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }
        inputSurface = codec.createInputSurface();
        codec.start();
        running = true;
        final Sink s = sink;
        thread = new Thread(() -> drain(s), "dashcam-venc");
        thread.start();
        Log.i(TAG, "encoder started " + codec.getName() + " " + width + "x" + height + " @" + bitrateBps);
    }

    private static MediaFormat buildFormat(int width, int height, int fps, int bitrateBps, boolean mainProfile) {
        MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        if (mainProfile) {
            fmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
            fmt.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41);
        }
        return fmt;
    }

    public Surface inputSurface() {
        return inputSurface;
    }

    public Throwable error() {
        return error;
    }

    private void drain(Sink sink) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (running) {
                int idx = codec.dequeueOutputBuffer(info, 10000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sink.onVideoFormat(codec.getOutputFormat());
                    continue;
                }
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER || idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    continue;
                }
                if (idx < 0) continue;
                ByteBuffer buf = codec.getOutputBuffer(idx);
                if (buf != null && info.size > 0) {
                    try {
                        sink.onVideoSample(buf, info);
                    } catch (Throwable t) {
                        Log.w(TAG, "sink failed", t);
                    }
                }
                codec.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        } catch (Throwable t) {
            error = t;
            Log.e(TAG, "drain failed", t);
        }
    }

    public void stop() {
        try {
            if (codec != null) codec.signalEndOfInputStream();
        } catch (Throwable ignored) {
        }
        if (thread != null) {
            try {
                thread.join(4000);
            } catch (InterruptedException ignored) {
            }
        }
        running = false;
        try {
            if (codec != null) codec.stop();
        } catch (Throwable ignored) {
        }
        try {
            if (codec != null) codec.release();
        } catch (Throwable ignored) {
        }
        try {
            if (inputSurface != null) inputSurface.release();
        } catch (Throwable ignored) {
        }
        codec = null;
        inputSurface = null;
    }
}
