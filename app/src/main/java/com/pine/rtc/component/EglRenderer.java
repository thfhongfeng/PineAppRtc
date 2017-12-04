//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package com.pine.rtc.component;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;

import org.webrtc.EglBase;
import org.webrtc.EglBase.Context;
import org.webrtc.GlTextureFrameBuffer;
import org.webrtc.GlUtil;
import org.webrtc.Logging;
import org.webrtc.RendererCommon;
import org.webrtc.RendererCommon.GlDrawer;
import org.webrtc.RendererCommon.YuvUploader;
import org.webrtc.ThreadUtils;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoRenderer.Callbacks;
import org.webrtc.VideoRenderer.I420Frame;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class EglRenderer implements Callbacks {
    private static final String TAG = "EglRenderer";
    private static final long LOG_INTERVAL_SEC = 4L;
    private static final int MAX_SURFACE_CLEAR_COUNT = 3;
    private final String name;
    private final Object handlerLock = new Object();
    private final ArrayList<EglRenderer.FrameListenerAndParams> frameListeners = new ArrayList();
    private final Object fpsReductionLock = new Object();
    private final YuvUploader yuvUploader = new YuvUploader();
    private final Object frameLock = new Object();
    private final Object layoutLock = new Object();
    private final Object statisticsLock = new Object();
    private final EglRenderer.EglSurfaceCreation eglSurfaceCreationRunnable = new EglRenderer.EglSurfaceCreation();
    private Handler renderThreadHandler;
    private long nextFrameTimeNs;
    private long minRenderPeriodNs;
    private EglBase eglBase;
    private GlDrawer drawer;
    private I420Frame pendingFrame;
    private float layoutAspectRatio;
    private boolean mirror;
    private int framesReceived;
    private int framesDropped;
    private int framesRendered;
    private long statisticsStartTimeNs;
    private long renderTimeNs;
    private long renderSwapBufferTimeNs;
    private final Runnable logStatisticsRunnable = new Runnable() {
        public void run() {
            EglRenderer.this.logStatistics();
            synchronized (EglRenderer.this.handlerLock) {
                if (EglRenderer.this.renderThreadHandler != null) {
                    EglRenderer.this.renderThreadHandler.removeCallbacks(EglRenderer.this.logStatisticsRunnable);
                    EglRenderer.this.renderThreadHandler.postDelayed(EglRenderer.this.logStatisticsRunnable, TimeUnit.SECONDS.toMillis(4L));
                }

            }
        }
    };
    private GlTextureFrameBuffer bitmapTextureFramebuffer;
    private final Runnable renderFrameRunnable = new Runnable() {
        public void run() {
            EglRenderer.this.renderFrameOnRenderThread();
        }
    };

    public EglRenderer(String name) {
        this.name = name;
    }

    public void init(final Context sharedContext, final int[] configAttributes, GlDrawer drawer) {
        Object var4 = this.handlerLock;
        synchronized (this.handlerLock) {
            if (this.renderThreadHandler != null) {
                throw new IllegalStateException(this.name + "Already initialized");
            } else {
                this.logD("Initializing EglRenderer");
                this.drawer = drawer;
                HandlerThread renderThread = new HandlerThread(this.name + "EglRenderer");
                renderThread.start();
                this.renderThreadHandler = new Handler(renderThread.getLooper());
                ThreadUtils.invokeAtFrontUninterruptibly(this.renderThreadHandler, new Runnable() {
                    public void run() {
                        if (sharedContext == null) {
                            EglRenderer.this.logD("EglBase10.create context");
                            EglRenderer.this.eglBase = EglBase.createEgl10(configAttributes);
                        } else {
                            EglRenderer.this.logD("EglBase.create shared context");
                            EglRenderer.this.eglBase = EglBase.create(sharedContext, configAttributes);
                        }

                    }
                });
                this.renderThreadHandler.post(this.eglSurfaceCreationRunnable);
                long currentTimeNs = System.nanoTime();
                this.resetStatistics(currentTimeNs);
                this.renderThreadHandler.postDelayed(this.logStatisticsRunnable, TimeUnit.SECONDS.toMillis(4L));
            }
        }
    }

    public void createEglSurface(Surface surface) {
        this.createEglSurfaceInternal(surface);
    }

    public void createEglSurface(SurfaceTexture surfaceTexture) {
        this.createEglSurfaceInternal(surfaceTexture);
    }

    private void createEglSurfaceInternal(Object surface) {
        this.eglSurfaceCreationRunnable.setSurface(surface);
        this.postToRenderThread(this.eglSurfaceCreationRunnable);
    }

    public void release() {
        this.logD("Releasing.");
        final CountDownLatch eglCleanupBarrier = new CountDownLatch(1);
        Object var2 = this.handlerLock;
        synchronized (this.handlerLock) {
            if (this.renderThreadHandler == null) {
                this.logD("Already released");
                return;
            }

            this.renderThreadHandler.removeCallbacks(this.logStatisticsRunnable);
            this.renderThreadHandler.postAtFrontOfQueue(new Runnable() {
                public void run() {
                    if (EglRenderer.this.drawer != null) {
                        EglRenderer.this.drawer.release();
                        EglRenderer.this.drawer = null;
                    }

                    EglRenderer.this.yuvUploader.release();
                    if (EglRenderer.this.bitmapTextureFramebuffer != null) {
                        EglRenderer.this.bitmapTextureFramebuffer.release();
                        EglRenderer.this.bitmapTextureFramebuffer = null;
                    }

                    if (EglRenderer.this.eglBase != null) {
                        EglRenderer.this.logD("eglBase detach and release.");
                        EglRenderer.this.eglBase.detachCurrent();
                        EglRenderer.this.eglBase.release();
                        EglRenderer.this.eglBase = null;
                    }

                    eglCleanupBarrier.countDown();
                }
            });
            final Looper renderLooper = this.renderThreadHandler.getLooper();
            this.renderThreadHandler.post(new Runnable() {
                public void run() {
                    EglRenderer.this.logD("Quitting render thread.");
                    renderLooper.quit();
                }
            });
            this.renderThreadHandler = null;
        }

        ThreadUtils.awaitUninterruptibly(eglCleanupBarrier);
        var2 = this.frameLock;
        synchronized (this.frameLock) {
            if (this.pendingFrame != null) {
                VideoRenderer.renderFrameDone(this.pendingFrame);
                this.pendingFrame = null;
            }
        }

        this.logD("Releasing done.");
    }

    private void resetStatistics(long currentTimeNs) {
        Object var3 = this.statisticsLock;
        synchronized (this.statisticsLock) {
            this.statisticsStartTimeNs = currentTimeNs;
            this.framesReceived = 0;
            this.framesDropped = 0;
            this.framesRendered = 0;
            this.renderTimeNs = 0L;
            this.renderSwapBufferTimeNs = 0L;
        }
    }

    public void printStackTrace() {
        Object var1 = this.handlerLock;
        synchronized (this.handlerLock) {
            Thread renderThread = this.renderThreadHandler == null ? null : this.renderThreadHandler.getLooper().getThread();
            if (renderThread != null) {
                StackTraceElement[] renderStackTrace = renderThread.getStackTrace();
                if (renderStackTrace.length > 0) {
                    this.logD("EglRenderer stack trace:");
                    StackTraceElement[] var4 = renderStackTrace;
                    int var5 = renderStackTrace.length;

                    for (int var6 = 0; var6 < var5; ++var6) {
                        StackTraceElement traceElem = var4[var6];
                        this.logD(traceElem.toString());
                    }
                }
            }

        }
    }

    public void setMirror(boolean mirror) {
        this.logD("setMirror: " + mirror);
        Object var2 = this.layoutLock;
        synchronized (this.layoutLock) {
            this.mirror = mirror;
        }
    }

    public void setLayoutAspectRatio(float layoutAspectRatio) {
        this.logD("setLayoutAspectRatio: " + layoutAspectRatio);
        Object var2 = this.layoutLock;
        synchronized (this.layoutLock) {
            this.layoutAspectRatio = layoutAspectRatio;
        }
    }

    public void setFpsReduction(float fps) {
        this.logD("setFpsReduction: " + fps);
        Object var2 = this.fpsReductionLock;
        synchronized (this.fpsReductionLock) {
            long previousRenderPeriodNs = this.minRenderPeriodNs;
            if (fps <= 0.0F) {
                this.minRenderPeriodNs = 9223372036854775807L;
            } else {
                this.minRenderPeriodNs = (long) ((float) TimeUnit.SECONDS.toNanos(1L) / fps);
            }

            if (this.minRenderPeriodNs != previousRenderPeriodNs) {
                this.nextFrameTimeNs = System.nanoTime();
            }

        }
    }

    public void disableFpsReduction() {
        this.setFpsReduction((float) (1.0F / 0.0));
    }

    public void pauseVideo() {
        this.setFpsReduction(0.0F);
    }

    public void addFrameListener(EglRenderer.FrameListener listener, float scale) {
        this.addFrameListener(listener, scale, (GlDrawer) null, false);
    }

    public void addFrameListener(EglRenderer.FrameListener listener, float scale, GlDrawer drawerParam) {
        this.addFrameListener(listener, scale, drawerParam, false);
    }

    public void addFrameListener(final EglRenderer.FrameListener listener, final float scale, final GlDrawer drawerParam, final boolean applyFpsReduction) {
        this.postToRenderThread(new Runnable() {
            public void run() {
                GlDrawer listenerDrawer = drawerParam == null ? EglRenderer.this.drawer : drawerParam;
                EglRenderer.this.frameListeners.add(new EglRenderer.FrameListenerAndParams(listener, scale, listenerDrawer, applyFpsReduction));
            }
        });
    }

    public void removeFrameListener(final EglRenderer.FrameListener listener) {
        if (Thread.currentThread() == this.renderThreadHandler.getLooper().getThread()) {
            throw new RuntimeException("removeFrameListener must not be called on the render thread.");
        } else {
            final CountDownLatch latch = new CountDownLatch(1);
            this.postToRenderThread(new Runnable() {
                public void run() {
                    latch.countDown();
                    Iterator iter = EglRenderer.this.frameListeners.iterator();

                    while (iter.hasNext()) {
                        if (((EglRenderer.FrameListenerAndParams) iter.next()).listener == listener) {
                            iter.remove();
                        }
                    }

                }
            });
            ThreadUtils.awaitUninterruptibly(latch);
        }
    }

    public void renderFrame(I420Frame frame) {
        Object dropOldFrame = this.statisticsLock;
        synchronized (this.statisticsLock) {
            ++this.framesReceived;
        }

        Object var3 = this.handlerLock;
        boolean dropOldFrame1;
        synchronized (this.handlerLock) {
            if (this.renderThreadHandler == null) {
                this.logD("Dropping frame - Not initialized or already released.");
                VideoRenderer.renderFrameDone(frame);
                return;
            }

            Object var4 = this.frameLock;
            synchronized (this.frameLock) {
                dropOldFrame1 = this.pendingFrame != null;
                if (dropOldFrame1) {
                    VideoRenderer.renderFrameDone(this.pendingFrame);
                }

                this.pendingFrame = frame;
                this.renderThreadHandler.post(this.renderFrameRunnable);
            }
        }

        if (dropOldFrame1) {
            var3 = this.statisticsLock;
            synchronized (this.statisticsLock) {
                ++this.framesDropped;
            }
        }

    }

    public void releaseEglSurface(final Runnable completionCallback) {
        this.eglSurfaceCreationRunnable.setSurface((Object) null);
        Object var2 = this.handlerLock;
        synchronized (this.handlerLock) {
            if (this.renderThreadHandler != null) {
                this.renderThreadHandler.removeCallbacks(this.eglSurfaceCreationRunnable);
                this.renderThreadHandler.postAtFrontOfQueue(new Runnable() {
                    public void run() {
                        if (EglRenderer.this.eglBase != null) {
                            EglRenderer.this.eglBase.detachCurrent();
                            EglRenderer.this.eglBase.releaseSurface();
                        }

                        completionCallback.run();
                    }
                });
                return;
            }
        }

        completionCallback.run();
    }

    public void postToRenderThread(Runnable runnable) {
        Object var2 = this.handlerLock;
        synchronized (this.handlerLock) {
            if (this.renderThreadHandler != null) {
                this.renderThreadHandler.post(runnable);
            }

        }
    }

    private void clearSurfaceOnRenderThread(float r, float g, float b, float a) {
        if (this.eglBase != null && this.eglBase.hasSurface()) {
            this.logD("clearSurface");
            GLES20.glClearColor(r, g, b, a);
            GLES20.glClear(16384);
            this.eglBase.swapBuffers();
        }

    }

    public void clearImage() {
        this.clearImage(0.0F, 0.0F, 0.0F, 0.0F);
    }

    public void clearImage(final float r, final float g, final float b, final float a) {
        Object var5 = this.handlerLock;
        synchronized (this.handlerLock) {
            if (this.renderThreadHandler != null) {
                this.renderThreadHandler.postAtFrontOfQueue(new Runnable() {
                    public void run() {
                        EglRenderer.this.clearSurfaceOnRenderThread(r, g, b, a);
                    }
                });
            }
        }
    }

    private void renderFrameOnRenderThread() {
        Object shouldRenderFrame = this.frameLock;
        I420Frame frame;
        synchronized (this.frameLock) {
            if (this.pendingFrame == null) {
                return;
            }

            frame = this.pendingFrame;
            this.pendingFrame = null;
        }

        if (this.eglBase != null && this.eglBase.hasSurface()) {
            Object startTimeNs = this.fpsReductionLock;
            boolean shouldRenderFrame1;
            synchronized (this.fpsReductionLock) {
                if (this.minRenderPeriodNs == 9223372036854775807L) {
                    shouldRenderFrame1 = false;
                } else if (this.minRenderPeriodNs <= 0L) {
                    shouldRenderFrame1 = true;
                } else {
                    long currentTimeNs = System.nanoTime();
                    if (currentTimeNs < this.nextFrameTimeNs) {
                        this.logD("Skipping frame rendering - fps reduction is active.");
                        shouldRenderFrame1 = false;
                    } else {
                        this.nextFrameTimeNs += this.minRenderPeriodNs;
                        this.nextFrameTimeNs = Math.max(this.nextFrameTimeNs, currentTimeNs);
                        shouldRenderFrame1 = true;
                    }
                }
            }

            long startTimeNs1 = System.nanoTime();
            float[] texMatrix = RendererCommon.rotateTextureMatrix(frame.samplingMatrix, (float) frame.rotationDegree);
            Object shouldUploadYuvTextures = this.layoutLock;
            float[] drawMatrix;
            int drawnFrameWidth;
            int drawnFrameHeight;
            synchronized (this.layoutLock) {
                float[] yuvTextures;
                if (this.layoutAspectRatio > 0.0F) {
                    float swapBuffersStartTimeNs = (float) frame.rotatedWidth() / (float) frame.rotatedHeight();
                    yuvTextures = RendererCommon.getLayoutMatrix(this.mirror, swapBuffersStartTimeNs, this.layoutAspectRatio);
                    if (swapBuffersStartTimeNs > this.layoutAspectRatio) {
                        drawnFrameWidth = (int) ((float) frame.rotatedHeight() * this.layoutAspectRatio);
                        drawnFrameHeight = frame.rotatedHeight();
                    } else {
                        drawnFrameWidth = frame.rotatedWidth();
                        drawnFrameHeight = (int) ((float) frame.rotatedWidth() / this.layoutAspectRatio);
                    }
                } else {
                    yuvTextures = this.mirror ? RendererCommon.horizontalFlipMatrix() : RendererCommon.identityMatrix();
                    drawnFrameWidth = frame.rotatedWidth();
                    drawnFrameHeight = frame.rotatedHeight();
                }

                drawMatrix = RendererCommon.multiplyMatrices(texMatrix, yuvTextures);
            }

            boolean shouldUploadYuvTextures1 = false;
            if (frame.yuvFrame) {
                shouldUploadYuvTextures1 = shouldRenderFrame1;
                if (!shouldRenderFrame1) {
                    label122:
                    {
                        Iterator yuvTextures1 = this.frameListeners.iterator();

                        EglRenderer.FrameListenerAndParams swapBuffersStartTimeNs1;
                        do {
                            do {
                                if (!yuvTextures1.hasNext()) {
                                    break label122;
                                }

                                swapBuffersStartTimeNs1 = (EglRenderer.FrameListenerAndParams) yuvTextures1.next();
                            } while (swapBuffersStartTimeNs1.scale == 0.0F);
                        } while (!shouldRenderFrame1 && swapBuffersStartTimeNs1.applyFpsReduction);

                        shouldUploadYuvTextures1 = true;
                    }
                }
            }

            int[] yuvTextures2 = shouldUploadYuvTextures1 ? this.yuvUploader.uploadYuvData(frame.width, frame.height, frame.yuvStrides, frame.yuvPlanes) : null;
            if (shouldRenderFrame1) {
                GLES20.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
                GLES20.glClear(16384);
                if (frame.yuvFrame) {
                    this.drawer.drawYuv(yuvTextures2, drawMatrix, drawnFrameWidth, drawnFrameHeight, 0, 0, this.eglBase.surfaceWidth(), this.eglBase.surfaceHeight());
                } else {
                    this.drawer.drawOes(frame.textureId, drawMatrix, drawnFrameWidth, drawnFrameHeight, 0, 0, this.eglBase.surfaceWidth(), this.eglBase.surfaceHeight());
                }

                long swapBuffersStartTimeNs2 = System.nanoTime();
                this.eglBase.swapBuffers();
                long currentTimeNs1 = System.nanoTime();
                Object var15 = this.statisticsLock;
                synchronized (this.statisticsLock) {
                    ++this.framesRendered;
                    this.renderTimeNs += currentTimeNs1 - startTimeNs1;
                    this.renderSwapBufferTimeNs += currentTimeNs1 - swapBuffersStartTimeNs2;
                }
            }

            this.notifyCallbacks(frame, yuvTextures2, texMatrix, shouldRenderFrame1);
            VideoRenderer.renderFrameDone(frame);
        } else {
            this.logD("Dropping frame - No surface");
            VideoRenderer.renderFrameDone(frame);
        }
    }

    private void notifyCallbacks(I420Frame frame, int[] yuvTextures, float[] texMatrix, boolean wasRendered) {
        if (!this.frameListeners.isEmpty()) {
            float[] bitmapMatrix = RendererCommon.multiplyMatrices(RendererCommon.multiplyMatrices(texMatrix, this.mirror ? RendererCommon.horizontalFlipMatrix() : RendererCommon.identityMatrix()), RendererCommon.verticalFlipMatrix());
            Iterator it = this.frameListeners.iterator();

            while (true) {
                while (true) {
                    EglRenderer.FrameListenerAndParams listenerAndParams;
                    do {
                        if (!it.hasNext()) {
                            return;
                        }

                        listenerAndParams = (EglRenderer.FrameListenerAndParams) it.next();
                    } while (!wasRendered && listenerAndParams.applyFpsReduction);

                    it.remove();
                    int scaledWidth = (int) (listenerAndParams.scale * (float) frame.rotatedWidth());
                    int scaledHeight = (int) (listenerAndParams.scale * (float) frame.rotatedHeight());
                    if (scaledWidth != 0 && scaledHeight != 0) {
                        if (this.bitmapTextureFramebuffer == null) {
                            this.bitmapTextureFramebuffer = new GlTextureFrameBuffer(6408);
                        }

                        this.bitmapTextureFramebuffer.setSize(scaledWidth, scaledHeight);
                        GLES20.glBindFramebuffer('赀', this.bitmapTextureFramebuffer.getFrameBufferId());
                        GLES20.glFramebufferTexture2D('赀', '賠', 3553, this.bitmapTextureFramebuffer.getTextureId(), 0);
                        GLES20.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
                        GLES20.glClear(16384);
                        if (frame.yuvFrame) {
                            listenerAndParams.drawer.drawYuv(yuvTextures, bitmapMatrix, frame.rotatedWidth(), frame.rotatedHeight(), 0, 0, scaledWidth, scaledHeight);
                        } else {
                            listenerAndParams.drawer.drawOes(frame.textureId, bitmapMatrix, frame.rotatedWidth(), frame.rotatedHeight(), 0, 0, scaledWidth, scaledHeight);
                        }

                        ByteBuffer bitmapBuffer = ByteBuffer.allocateDirect(scaledWidth * scaledHeight * 4);
                        GLES20.glViewport(0, 0, scaledWidth, scaledHeight);
                        GLES20.glReadPixels(0, 0, scaledWidth, scaledHeight, 6408, 5121, bitmapBuffer);
                        GLES20.glBindFramebuffer('赀', 0);
                        GlUtil.checkNoGLES2Error("EglRenderer.notifyCallbacks");
                        Bitmap bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Config.ARGB_8888);
                        bitmap.copyPixelsFromBuffer(bitmapBuffer);
                        listenerAndParams.listener.onFrame(bitmap);
                    } else {
                        listenerAndParams.listener.onFrame((Bitmap) null);
                    }
                }
            }
        }
    }

    private String averageTimeAsString(long sumTimeNs, int count) {
        return count <= 0 ? "NA" : TimeUnit.NANOSECONDS.toMicros(sumTimeNs / (long) count) + " μs";
    }

    private void logStatistics() {
        long currentTimeNs = System.nanoTime();
        Object var3 = this.statisticsLock;
        synchronized (this.statisticsLock) {
            long elapsedTimeNs = currentTimeNs - this.statisticsStartTimeNs;
            if (elapsedTimeNs > 0L) {
                float renderFps = (float) ((long) this.framesRendered * TimeUnit.SECONDS.toNanos(1L)) / (float) elapsedTimeNs;
                this.logD("Duration: " + TimeUnit.NANOSECONDS.toMillis(elapsedTimeNs) + " ms. Frames received: " + this.framesReceived + ". Dropped: " + this.framesDropped + ". Rendered: " + this.framesRendered + ". Render fps: " + String.format(Locale.US, "%.1f", new Object[]{Float.valueOf(renderFps)}) + ". Average render time: " + this.averageTimeAsString(this.renderTimeNs, this.framesRendered) + ". Average swapBuffer time: " + this.averageTimeAsString(this.renderSwapBufferTimeNs, this.framesRendered) + ".");
                this.resetStatistics(currentTimeNs);
            }
        }
    }

    private void logD(String string) {
        Logging.d("EglRenderer", this.name + string);
    }

    public interface FrameListener {
        void onFrame(Bitmap var1);
    }

    private static class FrameListenerAndParams {
        public final EglRenderer.FrameListener listener;
        public final float scale;
        public final GlDrawer drawer;
        public final boolean applyFpsReduction;

        public FrameListenerAndParams(EglRenderer.FrameListener listener, float scale, GlDrawer drawer, boolean applyFpsReduction) {
            this.listener = listener;
            this.scale = scale;
            this.drawer = drawer;
            this.applyFpsReduction = applyFpsReduction;
        }
    }

    private class EglSurfaceCreation implements Runnable {
        private Object surface;

        private EglSurfaceCreation() {
        }

        public synchronized void setSurface(Object surface) {
            this.surface = surface;
        }

        public synchronized void run() {
            if (this.surface != null && EglRenderer.this.eglBase != null && !EglRenderer.this.eglBase.hasSurface()) {
                if (this.surface instanceof Surface) {
                    EglRenderer.this.eglBase.createSurface((Surface) this.surface);
                } else {
                    if (!(this.surface instanceof SurfaceTexture)) {
                        throw new IllegalStateException("Invalid surface: " + this.surface);
                    }

                    EglRenderer.this.eglBase.createSurface((SurfaceTexture) this.surface);
                }

                EglRenderer.this.eglBase.makeCurrent();
                GLES20.glPixelStorei(3317, 1);
            }

        }
    }
}
