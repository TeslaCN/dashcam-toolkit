package com.dashcam.stitch.gl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.view.Surface;

/** 极简 EGL14 封装：一个 context，可创建多个 window surface。 */
public class EglCore {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private EGLDisplay mDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig mConfig;
    private EGLSurface mSurface = EGL14.EGL_NO_SURFACE;

    public EglCore() {
        mDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay failed");
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mDisplay, version, 0, version, 1)) {
            throw new RuntimeException("eglInitialize failed");
        }
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(mDisplay, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            throw new RuntimeException("eglChooseConfig failed");
        }
        mConfig = configs[0];
        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        mContext = EGL14.eglCreateContext(mDisplay, mConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (mContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext failed");
    }

    public void makeCurrent(EGLSurface surface) {
        if (!EGL14.eglMakeCurrent(mDisplay, surface, surface, mContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    public EGLSurface createWindowSurface(Surface surface) {
        int[] attribs = {EGL14.EGL_NONE};
        EGLSurface s = EGL14.eglCreateWindowSurface(mDisplay, mConfig, surface, attribs, 0);
        if (s == null || s == EGL14.EGL_NO_SURFACE) throw new RuntimeException("eglCreateWindowSurface failed");
        return s;
    }

    public void setPresentationTime(EGLSurface surface, long nsecs) {
        EGLExt.eglPresentationTimeANDROID(mDisplay, surface, nsecs);
    }

    public boolean swapBuffers(EGLSurface surface) {
        return EGL14.eglSwapBuffers(mDisplay, surface);
    }

    public void releaseSurface(EGLSurface surface) {
        if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(mDisplay, surface);
        }
    }

    public void release() {
        if (mDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (mContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(mDisplay, mContext);
            EGL14.eglTerminate(mDisplay);
        }
        mDisplay = EGL14.EGL_NO_DISPLAY;
        mContext = EGL14.EGL_NO_CONTEXT;
    }
}
