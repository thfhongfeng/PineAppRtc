package com.pine.rtc.org.lib;

import android.opengl.GLES20;

import org.webrtc.GlShader;
import org.webrtc.GlTextureFrameBuffer;
import org.webrtc.GlUtil;
import org.webrtc.RendererCommon;
import org.webrtc.ThreadUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Created by tanghongfeng on 2017/11/22.
 */

public class YuvConverter {
    private static final FloatBuffer DEVICE_RECTANGLE =
            GlUtil.createFloatBuffer(new float[]{-1.0F, -1.0F, 1.0F, -1.0F, -1.0F, 1.0F, 1.0F, 1.0F});
    private static final FloatBuffer TEXTURE_RECTANGLE =
            GlUtil.createFloatBuffer(new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F});
    private static final String VERTEX_SHADER = "varying vec2 interp_tc;\nattribute vec4 in_pos;\nattribute vec4 in_tc;\n\nuniform mat4 texMatrix;\n\nvoid main() {\n    gl_Position = in_pos;\n    interp_tc = (texMatrix * in_tc).xy;\n}\n";
    private static final String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nvarying vec2 interp_tc;\n\nuniform samplerExternalOES oesTex;\nuniform vec2 xUnit;\nuniform vec4 coeffs;\n\nvoid main() {\n  gl_FragColor.r = coeffs.a + dot(coeffs.rgb,\n      texture2D(oesTex, interp_tc - 1.5 * xUnit).rgb);\n  gl_FragColor.g = coeffs.a + dot(coeffs.rgb,\n      texture2D(oesTex, interp_tc - 0.5 * xUnit).rgb);\n  gl_FragColor.b = coeffs.a + dot(coeffs.rgb,\n      texture2D(oesTex, interp_tc + 0.5 * xUnit).rgb);\n  gl_FragColor.a = coeffs.a + dot(coeffs.rgb,\n      texture2D(oesTex, interp_tc + 1.5 * xUnit).rgb);\n}\n";
    private final GlTextureFrameBuffer mTextureFrameBuffer;
    private final GlShader mShader;
    private final int mTexMatrixLoc;
    private final int mXUnitLoc;
    private final int mCoeffsLoc;
    private final ThreadUtils.ThreadChecker mThreadChecker = new ThreadUtils.ThreadChecker();
    private boolean mReleased = false;

    public YuvConverter() {
        this.mThreadChecker.checkIsOnValidThread();
        this.mTextureFrameBuffer = new GlTextureFrameBuffer(6408);
        this.mShader = new GlShader("varying vec2 interp_tc;\nattribute vec4 in_pos;\nattribute vec4 in_tc;\n\nuniform mat4 texMatrix;\n\nvoid main() {\n    gl_Position = in_pos;\n    interp_tc = (texMatrix * in_tc).xy;\n}\n", "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nvarying vec2 interp_tc;\n\nuniform samplerExternalOES oesTex;\nuniform vec2 xUnit;\nuniform vec4 coeffs;\n\nvoid main() {\n  gl_FragColor.r = coeffs.a + dot(coeffs.rgb,\n      texture2D(oesTex, interp_tc - 1.5 * xUnit).rgb);\n  gl_FragColor.g = coeffs.a + dot(coeffs.rgb,\n      texture2D(oesTex, interp_tc - 0.5 * xUnit).rgb);\n  gl_FragColor.b = coeffs.a + dot(coeffs.rgb,\n      texture2D(oesTex, interp_tc + 0.5 * xUnit).rgb);\n  gl_FragColor.a = coeffs.a + dot(coeffs.rgb,\n      texture2D(oesTex, interp_tc + 1.5 * xUnit).rgb);\n}\n");
        this.mShader.useProgram();
        this.mTexMatrixLoc = this.mShader.getUniformLocation("texMatrix");
        this.mXUnitLoc = this.mShader.getUniformLocation("xUnit");
        this.mCoeffsLoc = this.mShader.getUniformLocation("coeffs");
        GLES20.glUniform1i(this.mShader.getUniformLocation("oesTex"), 0);
        this.mShader.setVertexAttribArray("in_pos", 2, DEVICE_RECTANGLE);
        this.mShader.setVertexAttribArray("in_tc", 2, TEXTURE_RECTANGLE);
    }

    public void convert(ByteBuffer buf, int width, int height, int stride, int srcTextureId, float[] transformMatrix) {
        this.mThreadChecker.checkIsOnValidThread();
        if (this.mReleased) {
            throw new IllegalStateException("YuvConverter.convert called on released object");
        } else if (stride % 8 != 0) {
            throw new IllegalArgumentException("Invalid stride, must be a multiple of 8");
        } else if (stride < width) {
            throw new IllegalArgumentException("Invalid stride, must >= width");
        } else {
            int y_width = (width + 3) / 4;
            int uv_width = (width + 7) / 8;
            int uv_height = (height + 1) / 2;
            int total_height = height + uv_height;
            int size = stride * total_height;
            if (buf.capacity() < size) {
                throw new IllegalArgumentException("YuvConverter.convert called with too small buffer");
            } else {
                transformMatrix = RendererCommon.multiplyMatrices(transformMatrix, RendererCommon.verticalFlipMatrix());
                int frameBufferWidth = stride / 4;
                this.mTextureFrameBuffer.setSize(frameBufferWidth, total_height);
                GLES20.glBindFramebuffer('赀', this.mTextureFrameBuffer.getFrameBufferId());
                GlUtil.checkNoGLES2Error("glBindFramebuffer");
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture('赥', srcTextureId);
                GLES20.glUniformMatrix4fv(this.mTexMatrixLoc, 1, false, transformMatrix, 0);
                GLES20.glViewport(0, 0, y_width, height);
                GLES20.glUniform2f(this.mXUnitLoc, transformMatrix[0] / (float) width, transformMatrix[1] / (float) width);
                GLES20.glUniform4f(this.mCoeffsLoc, 0.299F, 0.587F, 0.114F, 0.0F);
                GLES20.glDrawArrays(5, 0, 4);
                GLES20.glViewport(0, height, uv_width, uv_height);
                GLES20.glUniform2f(this.mXUnitLoc, 2.0F * transformMatrix[0] / (float) width, 2.0F * transformMatrix[1] / (float) width);
                GLES20.glUniform4f(this.mCoeffsLoc, -0.169F, -0.331F, 0.499F, 0.5F);
                GLES20.glDrawArrays(5, 0, 4);
                GLES20.glViewport(stride / 8, height, uv_width, uv_height);
                GLES20.glUniform4f(this.mCoeffsLoc, 0.499F, -0.418F, -0.0813F, 0.5F);
                GLES20.glDrawArrays(5, 0, 4);
                GLES20.glReadPixels(0, 0, frameBufferWidth, total_height, 6408, 5121, buf);
                GlUtil.checkNoGLES2Error("YuvConverter.convert");
                GLES20.glBindFramebuffer('赀', 0);
                GLES20.glBindTexture(3553, 0);
                GLES20.glBindTexture('赥', 0);
            }
        }
    }

    /**
     * Test Code (not correct) begin
     **/

    public void yuvRotate90(ByteBuffer srcBuffer, ByteBuffer desBuffer, int width, int height) {
        int size = width * height;
        int n = 0;
        int pos = 0;
        //copy y
        for (int j = 0; j < width; j++) {
            pos = size;
            for (int i = height - 1; i >= 0; i--) {
                pos -= width;
                desBuffer.put(n++, srcBuffer.get(pos + j));
            }
        }
        int hw = width >> 1;
        int hh = height >> 1;
        int hSize = size >> 2;
        //copy uv
        int m = n + hSize;
        for (int j = 0; j < hw; j++) {
            pos = hSize;
            for (int i = hh - 1; i >= 0; i--) {
                pos -= hw;
                desBuffer.put(n++, srcBuffer.get(size + pos + j));
                desBuffer.put(m++, srcBuffer.get(size + pos + j + hSize));
            }
        }
    }

    public void scaleYuvAndRotate90(ByteBuffer desBuffer, ByteBuffer yBuffer, ByteBuffer uBuffer,
                                    ByteBuffer vBuffer, int width, int height) {
        int size = width * height;
        int n = 0;
        int pos = 0;
        //copy y
        for (int j = 0; j < width; j++) {
            pos = size;
            for (int i = height - 1; i >= 0; i--) {
                pos -= width;
                desBuffer.put(n++, yBuffer.get(pos + j));
            }
        }
        int hw = width >> 1;
        int hh = height >> 1;
        int hSize = size >> 2;
        //copy uv
        int m = n + hSize;
        for (int j = 0; j < hw; j++) {
            pos = hSize;
            for (int i = hh - 1; i >= 0; i--) {
                pos -= hw;
                desBuffer.put(n++, uBuffer.get(pos + j));
                desBuffer.put(m++, vBuffer.get(pos + j));
            }
        }
        desBuffer.rewind();
    }

    /**
     * Test Code (not correct) end
     **/

    public void release() {
        this.mThreadChecker.checkIsOnValidThread();
        this.mReleased = true;
        this.mShader.release();
        this.mTextureFrameBuffer.release();
    }
}
