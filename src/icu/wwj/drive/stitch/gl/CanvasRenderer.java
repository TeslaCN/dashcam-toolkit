package icu.wwj.drive.stitch.gl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 把四路 OES 纹理按模板画到一个 EGL 画布上。
 * 顶点坐标用"画布像素坐标"（y 向下，原点左上角），由正交矩阵映射到 NDC。
 */
public class CanvasRenderer {
    private static final String TAG = "CanvasRenderer";

    private static final String VERTEX_SHADER =
            "uniform mat4 uMvp;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = uMvp * aPosition;\n" +
            "  vTexCoord = (uTexMatrix * aTexCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
            "}\n";

    // 单位方块，y 轴向下： (0,0) 左上 -> (1,1) 右下
    private static final float[] QUAD_POS = {
            0f, 0f, 0f, 1f,
            1f, 0f, 0f, 1f,
            0f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f,
    };
    // 纹理坐标：v=0 对应画面底部（GL 纹理原点在左下）
    private static final float[] QUAD_TEX = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
    };

    private final FloatBuffer mPosBuf;
    private final FloatBuffer mTexBuf;
    private final float[] mOrtho = new float[16];
    private final float[] mModel = new float[16];
    private final float[] mMvp = new float[16];

    private int mProgram;
    private int aPosition;
    private int aTexCoord;
    private int uMvp;
    private int uTexMatrix;
    private int uTexture;

    private int mCanvasW;
    private int mCanvasH;

    public CanvasRenderer(int canvasW, int canvasH) {
        mCanvasW = canvasW;
        mCanvasH = canvasH;
        mPosBuf = ByteBuffer.allocateDirect(QUAD_POS.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mPosBuf.put(QUAD_POS).position(0);
        mTexBuf = ByteBuffer.allocateDirect(QUAD_TEX.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTexBuf.put(QUAD_TEX).position(0);

        mProgram = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPosition = GLES20.glGetAttribLocation(mProgram, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        uMvp = GLES20.glGetUniformLocation(mProgram, "uMvp");
        uTexMatrix = GLES20.glGetUniformLocation(mProgram, "uTexMatrix");
        uTexture = GLES20.glGetUniformLocation(mProgram, "sTexture");

        // 画布坐标 (0..W, 0..H) y 向下 -> NDC
        Matrix.orthoM(mOrtho, 0, 0, canvasW, canvasH, 0, -1, 1);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /** 把外部纹理画到画布指定矩形区域 */
    public void draw(int texId, float[] texMatrix, float x, float y, float w, float h) {
        Matrix.setIdentityM(mModel, 0);
        Matrix.translateM(mModel, 0, x, y, 0);
        Matrix.scaleM(mModel, 0, w, h, 1f);
        Matrix.multiplyMM(mMvp, 0, mOrtho, 0, mModel, 0);

        GLES20.glUseProgram(mProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniform1i(uTexture, 0);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mMvp, 0);
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);

        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 4, GLES20.GL_FLOAT, false, 0, mPosBuf);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, mTexBuf);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
    }

    public static int createOesTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        int err = GLES20.glGetError();
        if (err != GLES20.GL_NO_ERROR) Log.w(TAG, "texture err " + err);
        return tex[0];
    }

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] status = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("link failed: " + log);
        }
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] status = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("compile failed: " + log);
        }
        return s;
    }
}
