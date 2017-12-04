package com.pine.rtc.component;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.webrtc.EglBase;
import org.webrtc.Logging;
import org.webrtc.RendererCommon;
import org.webrtc.ThreadUtils;
import org.webrtc.VideoRenderer;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.webrtc.VideoFileRenderer.nativeCreateNativeByteBuffer;
import static org.webrtc.VideoFileRenderer.nativeFreeNativeByteBuffer;
import static org.webrtc.VideoFileRenderer.nativeI420Scale;

/**
 * Created by tanghongfeng on 2017/11/22.
 */

@TargetApi(Build.VERSION_CODES.KITKAT)
public class VideoFileRenderer implements VideoRenderer.Callbacks {
    private static final String TAG = "VideoFileRenderer";

    private final HandlerThread mRenderThread;
    private final Object mHandlerLock = new Object();
    private final Handler mRenderThreadHandler;
    private final Handler mMainHandler;

    private final int mMuxType;
    private final FileOutputStream mVideoOutFile;
    private final String mOutputFileName;
    private final int mOutputFileWidth;
    private final int mOutputFileHeight;

    private final int mOutputFrameSize;
    private final ByteBuffer mOutputFrameBuffer;
    private EglBase mEglBase;
    private YuvConverter mYuvConverter;
    private int mRawFramesCount;
    private IRecorderListener mListener;
    private boolean mIsFirstRender;
    private MediaCodec mMediaCodec;
    private MediaMuxer mMediaMuxer;
    private int mVideoTrackIndex;

    public VideoFileRenderer(int muxType, String outputFile,
                             int outputFileWidth, int outputFileHeight,
                             final EglBase.Context sharedContext) throws IOException {
        if (outputFileWidth % 2 != 1 && outputFileHeight % 2 != 1) {
            mMuxType = muxType;
            mOutputFileName = outputFile;
            mOutputFileWidth = outputFileWidth;
            mOutputFileHeight = outputFileHeight;
            mOutputFrameSize = outputFileWidth * outputFileHeight * 3 / 2;
            mOutputFrameBuffer = ByteBuffer.allocateDirect(mOutputFrameSize);
            mVideoOutFile = new FileOutputStream(outputFile);
            mRenderThread = new HandlerThread("VideoFileRenderer");
            mRenderThread.start();
            mRenderThreadHandler = new Handler(mRenderThread.getLooper());
            mMainHandler = new Handler(Looper.getMainLooper());

            String mimeType = MediaFormat.MIMETYPE_VIDEO_AVC;
            MediaCodecVideoEncoder.EncoderProperties properties = MediaCodecVideoEncoder
                    .findColorFormat(mimeType,
                            MediaCodecVideoEncoder.H264HW_LIST,
                            MediaCodecVideoEncoder.SUPPORTED_COLOR_LIST);
            if (properties == null) {
                throw new RuntimeException("Can not find HW encoder for " + mimeType);
            } else {
                int fps = 15;
                if (properties.bitrateAdjustmentType == MediaCodecVideoEncoder.BitrateAdjustmentType.FRAMERATE_ADJUSTMENT) {
                    fps = 30;
                } else {
                    fps = Math.min(fps, 30);
                }
                mMediaCodec = MediaCodec.createEncoderByType(mimeType);
                MediaFormat mediaFormat = MediaFormat.createVideoFormat(mimeType,
                        outputFileWidth, outputFileHeight);
                mediaFormat.setInteger(MediaFormat.KEY_WIDTH, outputFileWidth);
                mediaFormat.setInteger(MediaFormat.KEY_HEIGHT, outputFileHeight);
                mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mOutputFrameSize * 8 * 20);
                mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, properties.colorFormat);
                mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 20);
                mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                mMediaCodec.start();

                mMediaMuxer = new MediaMuxer(outputFile, muxType);
                MediaFormat videoFormat = MediaFormat.createVideoFormat(mimeType,
                        outputFileWidth, outputFileHeight);
                byte[] header_sps = {0, 0, 0, 1, 103, 100, 0, 31, -84, -76, 2, -128, 45, -56};
                byte[] header_pps = {0, 0, 0, 1, 104, -18, 60, 97, 15, -1, -16, -121, -1, -8, 67, -1, -4, 33, -1, -2, 16, -1, -1, 8, 127, -1, -64};
                videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(header_sps));
                videoFormat.setByteBuffer("csd-1", ByteBuffer.wrap(header_pps));
                videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, properties.colorFormat);
                videoFormat.setInteger(MediaFormat.KEY_WIDTH, outputFileWidth);
                videoFormat.setInteger(MediaFormat.KEY_HEIGHT, outputFileHeight);
                videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1920 * 1080);
                videoFormat.setInteger(MediaFormat.KEY_CAPTURE_RATE, fps);
                mVideoTrackIndex = mMediaMuxer.addTrack(videoFormat);
                mMediaMuxer.start();

                ThreadUtils.invokeAtFrontUninterruptibly(mRenderThreadHandler, new Runnable() {
                    public void run() {
                        mEglBase = EglBase.create(sharedContext, EglBase.CONFIG_PIXEL_BUFFER);
                        mEglBase.createDummyPbufferSurface();
                        mEglBase.makeCurrent();
                        mYuvConverter = new YuvConverter();
                    }
                });
                mIsFirstRender = true;
            }
        } else {
            throw new IllegalArgumentException("Does not support uneven width or height");
        }
    }

    @Override
    public void renderFrame(final VideoRenderer.I420Frame frame) {
        if (mListener != null && mIsFirstRender) {
            mIsFirstRender = false;
            onRecorderStart();
        }
        if (mRenderThread.isAlive()) {
            mRenderThreadHandler.post(new Runnable() {
                public void run() {
                    renderFrameOnRenderThread(frame);
                }
            });
        }
    }

    private void renderFrameOnRenderThread(VideoRenderer.I420Frame frame) {
        float frameAspectRatio = (float) frame.rotatedWidth() / (float) frame.rotatedHeight();
        float[] rotatedSamplingMatrix = RendererCommon.rotateTextureMatrix(frame.samplingMatrix, (float) frame.rotationDegree);
        float[] layoutMatrix = RendererCommon.getLayoutMatrix(false, frameAspectRatio,
                (float) mOutputFileWidth / (float) mOutputFileHeight);
        float[] texMatrix = RendererCommon.multiplyMatrices(rotatedSamplingMatrix, layoutMatrix);
        try {
            ByteBuffer buffer = nativeCreateNativeByteBuffer(mOutputFrameSize);
            if (frame.yuvFrame) {
                Logging.d(TAG, "renderFrameOnRenderThread frame is yuvFrame");
                nativeI420Scale(frame.yuvPlanes[0], frame.yuvStrides[0], frame.yuvPlanes[1],
                        frame.yuvStrides[1], frame.yuvPlanes[2], frame.yuvStrides[2],
                        frame.width, frame.height, mOutputFrameBuffer,
                        mOutputFileWidth, mOutputFileHeight);
                buffer.put(mOutputFrameBuffer.array(), mOutputFrameBuffer.arrayOffset(), mOutputFrameSize);

                /** Test Code (not correct) begin **/
//                nativeI420Scale(frame.yuvPlanes[0], frame.yuvStrides[0], frame.yuvPlanes[1],
//                        frame.yuvStrides[1], frame.yuvPlanes[2], frame.yuvStrides[2],
//                        frame.width, frame.height, mOutputFrameBuffer,
//                        mOutputFileWidth, mOutputFileHeight);
//                mYuvConverter.yuvRotate90(mOutputFrameBuffer, buffer, mOutputFileWidth,
//                        mOutputFileHeight);

//                ByteBuffer byteBuffer = ByteBuffer.allocateDirect(frame.width * frame.height * 3 / 2);
//                mYuvConverter.scaleYuvAndRotate90(byteBuffer, frame.yuvPlanes[0],
//                        frame.yuvPlanes[1], frame.yuvPlanes[2],
//                        frame.width, frame.height);
//                buffer.put(byteBuffer.array(), byteBuffer.arrayOffset(), frame.width * frame.height * 3 / 2);
                /** Test Code (not correct) end **/
            } else {
                Logging.d(TAG, "renderFrameOnRenderThread frame is not yuvFrame");
                mYuvConverter.convert(mOutputFrameBuffer, mOutputFileWidth,
                        mOutputFileHeight, mOutputFileWidth, frame.textureId, texMatrix);
                buffer.put(mOutputFrameBuffer.array(), mOutputFrameBuffer.arrayOffset(), mOutputFrameSize);
//                int stride = mOutputFileWidth;
//                byte[] data = mOutputFrameBuffer.array();
//                int offset = mOutputFrameBuffer.arrayOffset();
//                buffer.put(data, offset, mOutputFileWidth * mOutputFileHeight);
//                int r;
//                for (r = mOutputFileHeight; r < mOutputFileHeight * 3 / 2; ++r) {
//                    buffer.put(data, offset + r * stride, stride / 2);
//                }
//                for (r = mOutputFileHeight; r < mOutputFileHeight * 3 / 2; ++r) {
//                    buffer.put(data, offset + r * stride + stride / 2, stride / 2);
//                }
            }
            mRawFramesCount++;
            buffer.rewind();
            writeFrameBufferToFile(buffer);
        } finally {
            VideoRenderer.renderFrameDone(frame);
        }
    }

//    private void writeFrameBufferToFile(final ByteBuffer buffer) {
//        try {
//            mVideoOutFile.write("FRAME\n".getBytes());
//            byte[] data = new byte[mOutputFrameSize];
//            buffer.get(data);
//            mVideoOutFile.write(data);
//            nativeFreeNativeByteBuffer(buffer);
//        } catch (IOException e) {
//            e.printStackTrace();
//            release(true);
//            Logging.d("VideoFileRenderer", "Error writing video to disk: " + e);
//        }
//    }

//    private void writeFrameBufferToFile(final ByteBuffer buffer) {
//        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
//        bufferInfo.offset = 0;
//        bufferInfo.size = mOutputFrameSize;
//        bufferInfo.flags = MediaCodec.BUFFER_FLAG_SYNC_FRAME;
//        bufferInfo.presentationTimeUs = 1000000 * mRawFramesCount / 20;
//        mMediaMuxer.writeSampleData(mVideoTrackIndex, buffer, bufferInfo);
//        nativeFreeNativeByteBuffer(buffer);
//    }

//    private void writeFrameBufferToFile(final ByteBuffer buffer) {
//        try {
//            byte[] data = new byte[mOutputFrameSize];
//            buffer.get(data);
//            ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
//            ByteBuffer[] outputBuffers = mMediaCodec.getOutputBuffers();
//            int inputBufferIndex = mMediaCodec.dequeueInputBuffer(50);
//            if (inputBufferIndex >= 0) {
//                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
//                inputBuffer.clear();
//                inputBuffer.put(buffer);
//                mMediaCodec.queueInputBuffer(inputBufferIndex, 0, mOutputFrameSize,
//                        1000000 * mRawFramesCount / 20, 0);
//            }
//            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
//            int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 50);
//            while (outputBufferIndex >= 0) {
//                ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
//                byte[] outData = new byte[bufferInfo.size];
//                outputBuffer.get(outData, 0, outData.length);
//                mVideoOutFile.write(outData);
//                mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
//                outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 50);
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//            release(true);
//            Logging.d("VideoFileRenderer", "Error writing video to disk: " + e);
//        }
//        nativeFreeNativeByteBuffer(buffer);
//    }

    private void writeFrameBufferToFile(final ByteBuffer buffer) {
        ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
        ByteBuffer[] outputBuffers = mMediaCodec.getOutputBuffers();
        int inputBufferIndex = mMediaCodec.dequeueInputBuffer(50);
        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
            inputBuffer.clear();
            inputBuffer.put(buffer);
            mMediaCodec.queueInputBuffer(inputBufferIndex, 0, mOutputFrameSize,
                    1000000 * mRawFramesCount / 20, 0);
        }
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 50);
        while (outputBufferIndex >= 0) {
            ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
            mMediaMuxer.writeSampleData(mVideoTrackIndex, outputBuffer, bufferInfo);
            mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
            outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 50);
        }
        nativeFreeNativeByteBuffer(buffer);
    }

    public void release() {
        release(false);
    }

    private void release(final boolean isError) {
        onRecorderCompleting();
        mRenderThreadHandler.post(new Runnable() {
            public void run() {
                try {
                    mMediaMuxer.stop();
                    mMediaMuxer.release();
                    mMediaCodec.stop();
                    mMediaCodec.release();
                    mVideoOutFile.flush();
                    mVideoOutFile.close();
                    if (mListener != null) {
                        if (isError) {
                            onRecorderError();
                            Logging.d("VideoFileRenderer", "Error when written to disk");
                        } else {
                            onRecorderComplete();
                            Logging.d("VideoFileRenderer", "Video written to disk as "
                                    + mOutputFileName + ". Number frames are "
                                    + mRawFramesCount + " and the dimension of the frames are "
                                    + mOutputFileWidth + "x" + mOutputFileHeight + ".");
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    if (mListener != null) {
                        onRecorderError();
                    }
                    Logging.d("VideoFileRenderer", "Error release: " + e);
                } finally {
                    mRenderThreadHandler.getLooper().quit();
                    mYuvConverter.release();
                    mEglBase.release();
                    mRenderThread.quit();
                }
            }
        });
    }

    private void onRecorderStart() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onRecorderStart();
            }
        });
    }

    private void onRecorderCompleting() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onRecorderCompleting();
            }
        });
    }

    private void onRecorderComplete() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onRecorderComplete();
            }
        });
    }

    private void onRecorderError() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onRecorderError();
            }
        });
    }

    public void setRecorderListener(IRecorderListener listener) {
        mListener = listener;
    }

    public static interface IRecorderListener {
        void onRecorderStart();

        void onRecorderCompleting();

        void onRecorderComplete();

        void onRecorderError();
    }
}
