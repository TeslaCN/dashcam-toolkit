package com.dashcam.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Camera frames -> OES texture -> GL scene (camera + burnt-in watermark) -> encoder surface.
 * Optionally mirrors the same scene to an on-screen preview surface (letterboxed).
 */
public class GlRenderer {

    private static final String TAG = "DashcamGl";

    private final int width;
    private final int height;
    private final Object frameLock = new Object();

    private Thread thread;
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile boolean running;
    private volatile Throwable error;
    private volatile boolean frameAvailable;

    private Surface encoderSurface;
    private volatile Surface pendingPreview;
    private volatile Surface currentPreview;
    private volatile int previewViewW, previewViewH;
    private volatile Surface cameraSurface;

    private volatile String[] overlayLines;
    private volatile String overlayAux;
    private volatile int overlayVersion;
    private int renderedVersion = -1;

    private volatile long lastFrameElapsedMs;
    private volatile long frameCounter;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface encoderEgl = EGL14.EGL_NO_SURFACE;
    private EGLSurface previewEgl = EGL14.EGL_NO_SURFACE;

    private SurfaceTexture cameraTexture;
    private int cameraTextureId;
    private int camProgram, overlayProgram;
    private int camPosLoc, camTexLoc, camMatrixLoc, camSamplerLoc, camTexelLoc, camSharpLoc;
    private int ovPosLoc, ovTexLoc, ovSamplerLoc, ovMatrixLoc;
    private int overlayTextureId;
    private Bitmap overlayBitmap;
    private FloatBuffer quadPos, quadTex, ovPos, ovTex;
    private final float[] identity = new float[16];
    private boolean loggedTransformFallback;
    private volatile boolean upsideDownMount;
    private volatile float sharpen = 0.35f;
    private volatile int targetFps = 30;
    private volatile long lastPreviewDrawMs;
    private long lastPresentedNs;
    private long nextPresentNs;
    /** vertical flip: (s,t) -> (s, 1-t) */
    private static final float[] FLIP_Y = {1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1};
    /** horizontal flip: (s,t) -> (1-s, t) */
    private static final float[] FLIP_X = {-1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1};
    private static final float[] IDENTITY16 = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};

    public GlRenderer(int width, int height) {
        this.width = width;
        this.height = height;
        Matrix.setIdentityM(identity, 0);
    }

    public void start(Surface encoderSurface) throws Exception {
        this.encoderSurface = encoderSurface;
        running = true;
        thread = new Thread(this::runGl, "dashcam-gl");
        thread.start();
        if (!readyLatch.await(6, TimeUnit.SECONDS)) {
            throw new IllegalStateException("GL renderer not ready");
        }
        if (error != null) throw new IllegalStateException("GL init failed: " + error);
    }

    public Surface cameraSurface() {
        return cameraSurface;
    }

    public void setPreviewSurface(Surface s) {
        setPreviewSurface(s, 0, 0);
    }

    public void setPreviewSurface(Surface s, int viewWidth, int viewHeight) {
        pendingPreview = s;
        previewViewW = viewWidth;
        previewViewH = viewHeight;
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
    }

    public long lastPreviewDrawMs() {
        return lastPreviewDrawMs;
    }

    /** Camera may deliver 30 fps; pace the encoder stream to the requested rate (e.g. 24 fps). */
    public void setTargetFps(int fps) {
        this.targetFps = Math.max(1, fps);
    }

    public void setSharpen(float amount) {
        this.sharpen = Math.max(0f, Math.min(1.5f, amount));
    }

    /** false = normal mount (flip Y), true = upside-down mount (flip X). */
    public void setUpsideDownMount(boolean upsideDown) {
        this.upsideDownMount = upsideDown;
    }

    public void setOverlay(String[] lines) {
        overlayLines = lines;
        overlayAux = null;
        overlayVersion++;
    }

    /** Main line drawn left, auxiliary line (shutter/ISO) right-aligned in a smaller font. */
    public void setOverlay(String mainLine, String auxLine) {
        overlayLines = mainLine == null ? null : new String[]{mainLine};
        overlayAux = auxLine;
        overlayVersion++;
    }

    public long lastFrameElapsedMs() {
        return lastFrameElapsedMs;
    }

    public long framesRendered() {
        return frameCounter;
    }

    public void stop() {
        running = false;
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        if (thread != null) {
            try {
                thread.join(3000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // ------------------------------------------------------------- GL thread

    private void runGl() {
        try {
            initEgl();
            cameraTextureId = createOesTexture();
            cameraTexture = new SurfaceTexture(cameraTextureId);
            cameraTexture.setDefaultBufferSize(width, height);
            cameraTexture.setOnFrameAvailableListener(st -> {
                synchronized (frameLock) {
                    frameAvailable = true;
                    frameLock.notifyAll();
                }
            });
            cameraSurface = new Surface(cameraTexture);
            buildQuadBuffers();
            readyLatch.countDown();
            renderLoop();
        } catch (Throwable t) {
            error = t;
            Log.e(TAG, "render failed", t);
            readyLatch.countDown();
        } finally {
            releaseEgl();
            if (cameraTexture != null) cameraTexture.release();
            if (cameraSurface != null) cameraSurface.release();
        }
    }

    private void initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("eglInitialize failed");
        }
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            throw new RuntimeException("no EGL config");
        }
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        encoderEgl = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface,
                new int[]{EGL14.EGL_NONE}, 0);
        if (encoderEgl == null || encoderEgl == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("encoder EGL surface failed");
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, encoderEgl, encoderEgl, eglContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
        camProgram = buildProgram(VERTEX_SHADER, FRAGMENT_OES);
        camPosLoc = GLES20.glGetAttribLocation(camProgram, "aPos");
        camTexLoc = GLES20.glGetAttribLocation(camProgram, "aTex");
        camMatrixLoc = GLES20.glGetUniformLocation(camProgram, "uMatrix");
        camSamplerLoc = GLES20.glGetUniformLocation(camProgram, "uTexture");
        camTexelLoc = GLES20.glGetUniformLocation(camProgram, "uTexel");
        camSharpLoc = GLES20.glGetUniformLocation(camProgram, "uSharp");
        overlayProgram = buildProgram(VERTEX_SHADER, FRAGMENT_2D);
        ovPosLoc = GLES20.glGetAttribLocation(overlayProgram, "aPos");
        ovTexLoc = GLES20.glGetAttribLocation(overlayProgram, "aTex");
        ovSamplerLoc = GLES20.glGetUniformLocation(overlayProgram, "uTexture");
        ovMatrixLoc = GLES20.glGetUniformLocation(overlayProgram, "uMatrix");
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        Log.i(TAG, "EGL ready " + width + "x" + height);
    }

    private void releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (encoderEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, encoderEgl);
            if (previewEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, previewEgl);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        encoderEgl = EGL14.EGL_NO_SURFACE;
        previewEgl = EGL14.EGL_NO_SURFACE;
    }

    private void renderLoop() throws Exception {
        float[] transform = new float[16];
        long lastPresentation = 0;
        while (running) {
            boolean gotFrame;
            synchronized (frameLock) {
                gotFrame = frameAvailable;
                if (!gotFrame) {
                    frameLock.wait(200);
                }
                gotFrame = frameAvailable;
                frameAvailable = false;
            }
            handlePreviewChange();
            if (!running) break;
            if (!gotFrame) continue;

            if (!EGL14.eglMakeCurrent(eglDisplay, encoderEgl, encoderEgl, eglContext)) {
                continue;
            }
            try {
                cameraTexture.updateTexImage();
                // Do NOT use SurfaceTexture#getTransformMatrix here: on this HAL it is computed for a
            // portrait display consumer and would rotate/stretch our landscape encoder output.
            // We apply our own rotation derived from sensor orientation + display rotation.
            cameraTexture.getTransformMatrix(transform);
            // Raw camera buffer is vertically flipped on this HAL (we bypass the HAL preview
            // matrix). "normal" mount => flip Y; "upside-down" mount => flip X (flipY + 180 deg).
            System.arraycopy(upsideDownMount ? FLIP_X : FLIP_Y, 0, transform, 0, 16);
            } catch (Throwable t) {
                Log.w(TAG, "updateTexImage failed", t);
                Thread.sleep(20);
                continue;
            }
            long tsNs = cameraTexture.getTimestamp();
            if (tsNs <= lastPresentation) tsNs = lastPresentation + 1_000_000L;
            if (targetFps > 0 && targetFps < 30) {
                // The HAL keeps streaming ~30 fps; pace the encoder with a virtual clock so the
                // output averages the requested rate (e.g. 24 fps => 2:3 frame cadence).
                long periodNs = 1_000_000_000L / targetFps;
                if (nextPresentNs == 0) nextPresentNs = tsNs;
                if (tsNs + periodNs / 3 < nextPresentNs) {
                    continue; // too early: drop this camera frame
                }
                nextPresentNs += periodNs;
                if (tsNs - nextPresentNs > periodNs) nextPresentNs = tsNs + periodNs; // resync
                lastPresentedNs = tsNs;
            }
            lastPresentation = tsNs;
            lastFrameElapsedMs = SystemClock.elapsedRealtime();
            frameCounter++;

            if (overlayVersion != renderedVersion) {
                renderOverlayBitmap();
                renderedVersion = overlayVersion;
            }

            int ew = surfaceWidth(encoderEgl);
            int eh = surfaceHeight(encoderEgl);
            if (ew <= 0 || eh <= 0) { ew = width; eh = height; }
            GLES20.glViewport(0, 0, ew, eh);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            drawScene(transform);
            EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEgl, tsNs);
            EGL14.eglSwapBuffers(eglDisplay, encoderEgl);

            if (previewEgl != EGL14.EGL_NO_SURFACE) {
                if (EGL14.eglMakeCurrent(eglDisplay, previewEgl, previewEgl, eglContext)) {
                    int pw = previewViewW > 0 ? previewViewW : surfaceWidth(previewEgl);
                    int ph = previewViewH > 0 ? previewViewH : surfaceHeight(previewEgl);
                    if (pw > 0 && ph > 0) {
                        GLES20.glViewport(0, 0, pw, ph);
                        GLES20.glClearColor(0f, 0f, 0f, 1f);
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                        int[] lb = letterbox(pw, ph);
                        GLES20.glViewport(lb[0], lb[1], lb[2], lb[3]);
                        drawScene(transform);
                    }
                    EGL14.eglSwapBuffers(eglDisplay, previewEgl);
                }
            }
        }
    }

    private void handlePreviewChange() {
        Surface next = pendingPreview;
        if (next == currentPreview) return;
        if (previewEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(eglDisplay, previewEgl);
            previewEgl = EGL14.EGL_NO_SURFACE;
            Log.i(TAG, "preview detached");
        }
        // keep the GL context bound to the encoder surface
        EGL14.eglMakeCurrent(eglDisplay, encoderEgl, encoderEgl, eglContext);
        currentPreview = next;
        if (currentPreview != null && currentPreview.isValid()) {
            try {
                previewEgl = createWindowSurface(currentPreview);
                Log.i(TAG, "preview attached");
            } catch (Throwable t) {
                Log.w(TAG, "preview attach failed", t);
            }
        }
    }

    /** True when the 2x2 linear part of the matrix is a signed permutation (rotate/flip only). */
    private static boolean isPureOrientation(float[] m) {
        float m00 = m[0], m01 = m[4], m10 = m[1], m11 = m[5];
        float eps = 1e-3f;
        boolean perm = Math.abs(Math.abs(m00) + Math.abs(m01) - 1f) < eps
                && Math.abs(Math.abs(m10) + Math.abs(m11) - 1f) < eps
                && Math.abs(Math.abs(m00) + Math.abs(m10) - 1f) < eps
                && Math.abs(Math.abs(m01) + Math.abs(m11) - 1f) < eps;
        if (!perm) return false;
        float det = m00 * m11 - m01 * m10;
        return Math.abs(Math.abs(det) - 1f) < eps;
    }

    private int surfaceWidth(EGLSurface surface) {
        int[] v = new int[1];
        return EGL14.eglQuerySurface(eglDisplay, surface, EGL14.EGL_WIDTH, v, 0) ? v[0] : 0;
    }

    private int surfaceHeight(EGLSurface surface) {
        int[] v = new int[1];
        return EGL14.eglQuerySurface(eglDisplay, surface, EGL14.EGL_HEIGHT, v, 0) ? v[0] : 0;
    }

    private int[] letterbox(int pw, int ph) {
        float videoAspect = width / (float) height;
        float screenAspect = pw / (float) ph;
        int w = pw, h = ph, x = 0, y = 0;
        if (screenAspect > videoAspect) {
            w = (int) (ph * videoAspect);
            x = (pw - w) / 2;
        } else {
            h = (int) (pw / videoAspect);
            y = (ph - h) / 2;
        }
        return new int[]{x, y, w, h};
    }

    private EGLSurface createWindowSurface(Surface surface) {
        int[] attribs = {EGL14.EGL_NONE};
        return EGL14.eglCreateWindowSurface(eglDisplay,
                getConfig(), surface, attribs, 0);
    }

    private EGLConfig cachedConfig;

    private EGLConfig getConfig() {
        if (cachedConfig != null) return cachedConfig;
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0);
        cachedConfig = configs[0];
        return cachedConfig;
    }

    private void drawScene(float[] transform) {
        GLES20.glUseProgram(camProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(camSamplerLoc, 0);
        GLES20.glUniform2f(camTexelLoc, 1f / width, 1f / height);
        GLES20.glUniform1f(camSharpLoc, sharpen);
        GLES20.glUniformMatrix4fv(camMatrixLoc, 1, false, transform, 0);
        GLES20.glEnableVertexAttribArray(camPosLoc);
        GLES20.glVertexAttribPointer(camPosLoc, 2, GLES20.GL_FLOAT, false, 0, quadPos);
        GLES20.glEnableVertexAttribArray(camTexLoc);
        GLES20.glVertexAttribPointer(camTexLoc, 2, GLES20.GL_FLOAT, false, 0, quadTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        if (overlayTextureId != 0 && ovPos != null) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(overlayProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
            GLES20.glUniform1i(ovSamplerLoc, 0);
            GLES20.glUniformMatrix4fv(ovMatrixLoc, 1, false, identity, 0);
            GLES20.glEnableVertexAttribArray(ovPosLoc);
            GLES20.glVertexAttribPointer(ovPosLoc, 2, GLES20.GL_FLOAT, false, 0, ovPos);
            GLES20.glEnableVertexAttribArray(ovTexLoc);
            GLES20.glVertexAttribPointer(ovTexLoc, 2, GLES20.GL_FLOAT, false, 0, ovTex);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    private void buildQuadBuffers() {
        quadPos = floatBuffer(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
        quadTex = floatBuffer(new float[]{0, 0, 1, 0, 0, 1, 1, 1});
    }

    /**
     * Watermark: first line full size (time + GPS), following lines at ~65% size
     * (exposure/ISO, place name). Full width, thin strip at the very top.
     */
    private void renderOverlayBitmap() {
        String[] lines = overlayLines;
        if (lines == null || lines.length == 0 || (lines[0] != null && lines[0].isEmpty())) {
            overlayTextureId = 0;
            return;
        }
        int count = 0;
        for (String l : lines) if (l != null && !l.isEmpty()) count++;
        if (count == 0) {
            overlayTextureId = 0;
            return;
        }
        int bw = width;
        float mainH = Math.max(64, height / 16f);
        float smallH = mainH * 0.58f;
        int bh = Math.round(mainH + (count - 1) * smallH);
        if (overlayBitmap == null || overlayBitmap.getWidth() != bw || overlayBitmap.getHeight() != bh) {
            overlayBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        }
        Canvas c = new Canvas(overlayBitmap);
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setShadowLayer(7f, 2f, 2f, Color.BLACK);
        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        float maxWidth = bw * 0.96f;
        float baseline = mainH * 0.76f;
        String aux = overlayAux;
        float auxWidth = 0f;
        float auxSize = mainH * 0.40f;   // auxiliary info: ~64% of the main font
        if (aux != null && !aux.isEmpty()) {
            p.setTextSize(auxSize);
            auxWidth = p.measureText(aux);
        }
        float mainMax = maxWidth - auxWidth - (auxWidth > 0 ? bw * 0.02f : 0f);
        int drawn = 0;
        for (String text : lines) {
            if (text == null || text.isEmpty()) continue;
            float stripH = drawn == 0 ? mainH : smallH;
            float lineBaseline = drawn == 0 ? baseline
                    : mainH + (drawn - 1) * smallH + smallH * 0.80f;
            float size = stripH * 0.62f;
            p.setTextSize(size);
            while (p.measureText(text) > (drawn == 0 ? mainMax : maxWidth) && size > 10f) {
                size -= 1.5f;
                p.setTextSize(size);
            }
            c.drawText(text, bw * 0.02f, lineBaseline, p);
            drawn++;
        }
        if (auxWidth > 0) {
            p.setTextSize(auxSize);
            c.drawText(aux, bw * 0.98f - auxWidth, baseline, p);
        }

        if (overlayTextureId == 0) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            overlayTextureId = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0);

        // full-width strip anchored to the top edge
        float hNdc = 2f * bh / (float) height;
        float y1 = 0.999f;
        float y0 = y1 - hNdc;
        ovPos = floatBuffer(new float[]{-1f, y0, 1f, y0, -1f, y1, 1f, y1});
        ovTex = floatBuffer(new float[]{0, 1, 1, 1, 0, 0, 1, 0});
    }

    private int createOesTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return tex[0];
    }

    private int buildProgram(String vs, String fs) {
        int v = compileShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, v);
        GLES20.glAttachShader(prog, f);
        GLES20.glLinkProgram(prog);
        int[] status = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) throw new RuntimeException("link failed: " + GLES20.glGetProgramInfoLog(prog));
        return prog;
    }

    private int compileShader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] status = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) throw new RuntimeException("compile failed: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private FloatBuffer floatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    private static final String VERTEX_SHADER =
            "attribute vec4 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "uniform mat4 uMatrix;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = aPos;\n" +
            "  vTex = (uMatrix * vec4(aTex, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String FRAGMENT_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "uniform vec2 uTexel;\n" +
            "uniform float uSharp;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  vec4 c = texture2D(uTexture, vTex);\n" +
            "  if (uSharp > 0.001) {\n" +
            "    vec3 n = texture2D(uTexture, vTex + vec2(uTexel.x, 0.0)).rgb;\n" +
            "    vec3 s = texture2D(uTexture, vTex - vec2(uTexel.x, 0.0)).rgb;\n" +
            "    vec3 e = texture2D(uTexture, vTex + vec2(0.0, uTexel.y)).rgb;\n" +
            "    vec3 w = texture2D(uTexture, vTex - vec2(0.0, uTexel.y)).rgb;\n" +
            "    vec3 blur = (n + s + e + w) * 0.25;\n" +
            "    c.rgb = clamp(c.rgb + (c.rgb - blur) * uSharp, 0.0, 1.0);\n" +
            "  }\n" +
            "  gl_FragColor = c;\n" +
            "}\n";

    private static final String FRAGMENT_2D =
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "varying vec2 vTex;\n" +
            "void main() { gl_FragColor = texture2D(uTexture, vTex); }\n";
}
