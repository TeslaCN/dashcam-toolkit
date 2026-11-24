package com.dashcam.app;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Writes encoded video into MP4 files and rotates the muxer on a video keyframe,
 * so the encoder never stops and no footage is lost between segments.
 */
public class SegmentWriter {

    private static final String TAG = "DashcamSeg";

    public interface Listener {
        void onSegmentStarted(File file);

        void onSegmentClosed(File file, long bytes, long durationMs, long frames);

        void onQuotaCleanup(long freedBytes, int remainingFiles);
    }

    private final File dir;
    private final long segmentUs;
    private final TimeBase timeBase = new TimeBase();
    private final long quotaBytes;
    private final Listener listener;

    private MediaMuxer muxer;
    private int videoTrack = -1;
    private MediaFormat videoFormat;
    private long segmentStartUs = -1;
    private long sessionBaseUs = -1;
    private long lastVideoPtsUs = -1;
    private long firstPtsUs = -1;
    private long lastPtsUs = -1;
    private long frames;
    private long bytes;
    private String currentName = "";
    private File currentFile;

    public SegmentWriter(File dir, long segmentSecs, long quotaBytes, Listener listener) {
        this.dir = dir;
        this.segmentUs = Math.max(5, segmentSecs) * 1_000_000L;
        this.quotaBytes = quotaBytes;
        this.listener = listener;
    }

    public synchronized TimeBase timeBase() {
        return timeBase;
    }

    public synchronized String currentFileName() {
        return currentName;
    }

    public synchronized long currentBytes() {
        return bytes;
    }

    public synchronized void onVideoFormat(MediaFormat format) {
        videoFormat = format;
        Log.i(TAG, "video format " + format);
    }

    public synchronized void writeVideo(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (videoFormat == null) return;
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return;
        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        long pts = info.presentationTimeUs;
        if (sessionBaseUs < 0) {
            sessionBaseUs = pts;
            timeBase.setFromVideoSample(pts);
        }
        long ptsOut = pts - sessionBaseUs;
        if (muxer == null) {
            if (!key) return; // start every segment on an IDR
            if (!startSegment(ptsOut)) return;
        } else if (key && ptsOut - segmentStartUs >= segmentUs) {
            closeSegment();
            if (!startSegment(ptsOut)) return;
        }
        if (ptsOut <= lastVideoPtsUs) return;
        try {
            muxer.writeSampleData(videoTrack, buffer, rebased(info, ptsOut));
            lastVideoPtsUs = ptsOut;
            if (firstPtsUs < 0) firstPtsUs = ptsOut;
            lastPtsUs = ptsOut;
            frames++;
            bytes += info.size;
        } catch (Throwable t) {
            Log.w(TAG, "video write failed", t);
        }
    }

    private MediaCodec.BufferInfo rebased(MediaCodec.BufferInfo info, long ptsOutUs) {
        MediaCodec.BufferInfo copy = new MediaCodec.BufferInfo();
        copy.set(info.offset, info.size, ptsOutUs, info.flags);
        return copy;
    }

    private boolean startSegment(long ptsUs) {
        String name = StorageManager.nextFileName();
        File file = new File(dir, name);
        try {
            muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            videoTrack = muxer.addTrack(videoFormat);
            muxer.start();
        } catch (Throwable t) {
            Log.e(TAG, "start segment failed", t);
            try {
                if (muxer != null) muxer.release();
            } catch (Throwable ignored) {
            }
            muxer = null;
            videoTrack = -1;
            return false;
        }
        currentName = name;
        currentFile = file;
        segmentStartUs = ptsUs;
        lastVideoPtsUs = -1;
        firstPtsUs = -1;
        lastPtsUs = -1;
        frames = 0;
        bytes = 0;
        Log.i(TAG, "segment started " + name + " videoTrack=" + videoTrack);
        if (listener != null) listener.onSegmentStarted(file);
        return true;
    }

    private void closeSegment() {
        if (muxer == null) return;
        long durationMs = firstPtsUs >= 0 ? (lastPtsUs - firstPtsUs) / 1000 : 0;
        boolean stopped = false;
        try {
            muxer.stop();
            stopped = true;
        } catch (Throwable t) {
            Log.w(TAG, "muxer stop failed, file will be discarded", t);
        }
        try {
            muxer.release();
        } catch (Throwable ignored) {
        }
        muxer = null;
        videoTrack = -1;
        Log.i(TAG, "segment closed " + currentName + " " + durationMs + "ms " + frames + " frames "
                + bytes + " bytes stopped=" + stopped);
        if (!stopped) {
            if (currentFile != null && currentFile.exists()) {
                long len = currentFile.length();
                if (currentFile.delete()) Log.w(TAG, "discarded corrupt segment " + currentName + " " + len);
            }
            currentFile = null;
            currentName = "";
            return;
        }
        if (listener != null && currentFile != null) {
            listener.onSegmentClosed(currentFile, bytes, durationMs, frames);
        }
        if (quotaBytes > 0) {
            long freed = StorageManager.enforceQuota(dir, quotaBytes, currentName);
            int remaining = StorageManager.listClips(dir).size();
            if (freed > 0 && listener != null) listener.onQuotaCleanup(freed, remaining);
        }
        currentFile = null;
        currentName = "";
    }

    public synchronized void close() {
        closeSegment();
    }
}
