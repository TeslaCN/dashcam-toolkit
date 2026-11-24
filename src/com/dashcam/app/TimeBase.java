package com.dashcam.app;

/**
 * Maps System.nanoTime() onto the camera timestamp clock.
 *
 * Camera frames arrive with timestamps from the camera HAL clock (CLOCK_BOOTTIME-like),
 * which can differ from System.nanoTime() (CLOCK_MONOTONIC) by the accumulated deep-sleep time.
 * Once the first encoded video sample is seen we compute the offset and stamp audio with
 * timestamps on the same timeline, so A/V stay aligned inside the muxer.
 */
public class TimeBase {

    private static final long UNSET = Long.MIN_VALUE;
    private volatile long offsetUs = UNSET;

    public synchronized void setFromVideoSample(long videoPtsUs) {
        if (offsetUs != UNSET) return;
        offsetUs = videoPtsUs - System.nanoTime() / 1000L;
    }

    public boolean isSet() {
        return offsetUs != UNSET;
    }

    public long nowUs() {
        long t = System.nanoTime() / 1000L;
        long off = offsetUs;
        return off == UNSET ? t : t + off;
    }
}
