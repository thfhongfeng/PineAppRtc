package com.pine.rtc.org.lib;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.pine.rtc.org.component.PeerConnectionClient;
import com.pine.rtc.util.MediaCodecVideoEncoderUtil;

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

/**
 * Test Class (not correct)
 **/
@TargetApi(Build.VERSION_CODES.KITKAT)
public class VideoFileRenderer implements VideoRenderer.Callbacks {
    private static final String TAG = "VideoFileRenderer";

    private HandlerThread mRenderThread;
    private final Object mHandlerLock = new Object();
    private Handler mRenderThreadHandler;
    private Handler mMainHandler;

    private int mMuxType;
    private FileOutputStream mVideoOutFile;
    private int mOutputFileWidth;
    private int mOutputFileHeight;
    private int mOutputFrameSize;
    private ByteBuffer mOutputFrameBuffer;
    private String mOutputFileName;
    private PeerConnectionClient mClient;
    private EglBase.Context mSharedContext;
    private EglBase mEglBase;
    private YuvConverter mYuvConverter;
    private int mRawFramesCount;
    private IRecorderListener mListener;
    private boolean mIsFirstRender;
    private MediaCodec mMediaCodec;
    private MediaMuxer mMediaMuxer;
    private int mVideoTrackIndex;
    private boolean mIsRecording;

    public VideoFileRenderer(int muxType, PeerConnectionClient client, final EglBase.Context sharedContext) {
        mMuxType = muxType;
        mClient = client;
        mSharedContext = sharedContext;
        mIsFirstRender = true;
    }

    private void setupRecorder(String outputFile, int outputFileWidth, int outputFileHeight) throws IOException {
        if (outputFileWidth % 2 != 1 && outputFileHeight % 2 != 1) {
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
            MediaCodecVideoEncoderUtil.EncoderProperties properties = MediaCodecVideoEncoderUtil
                    .findColorFormat(mimeType,
                            MediaCodecVideoEncoderUtil.H264HW_LIST,
                            MediaCodecVideoEncoderUtil.SUPPORTED_COLOR_LIST);
            if (properties == null) {
                throw new RuntimeException("Can not find HW encoder for " + mimeType);
            } else {
                int fps = 15;
                if (properties.bitrateAdjustmentType == MediaCodecVideoEncoderUtil.BitrateAdjustmentType.FRAMERATE_ADJUSTMENT) {
                    fps = 30;
                } else {
                    fps = Math.min(fps, 30);
                }
                mMediaCodec = MediaCodec.createEncoderByType(mimeType);
                MediaFormat mediaFormat = MediaFormat.createVideoFormat(mimeType,
                        outputFileWidth, outputFileHeight);
                mediaFormat.setInteger(MediaFormat.KEY_WIDTH, outputFileWidth);
                mediaFormat.setInteger(MediaFormat.KEY_HEIGHT, outputFileHeight);
                mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mOutputFrameSize * 8 * fps);
                mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, properties.colorFormat);
                mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 20);
                mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                mMediaCodec.start();

                mMediaMuxer = new MediaMuxer(outputFile, mMuxType);

                ThreadUtils.invokeAtFrontUninterruptibly(mRenderThreadHandler, new Runnable() {
                    public void run() {
                        mEglBase = EglBase.create(mSharedContext, EglBase.CONFIG_PIXEL_BUFFER);
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
            writeFrameBufferToFile(buffer, frame.yuvFrame);
            nativeFreeNativeByteBuffer(buffer);
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

    private void writeFrameBufferToFile(final ByteBuffer buffer, boolean isYUVFrame) {
        ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
        ByteBuffer[] outputBuffers = mMediaCodec.getOutputBuffers();
        int inputBufferIndex = mMediaCodec.dequeueInputBuffer(0);
        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
            inputBuffer.clear();
            inputBuffer.put(buffer);
            mMediaCodec.queueInputBuffer(inputBufferIndex, 0, mOutputFrameSize,
                    1000000 * mRawFramesCount / 20, 0);
        }
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (mIsRecording) {
            int encoderStatus = mMediaCodec.dequeueOutputBuffer(bufferInfo, 50);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Logging.d(TAG, "encoderStatus INFO_TRY_AGAIN_LATER");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                Logging.d(TAG, "encoderStatus INFO_OUTPUT_BUFFERS_CHANGED");
                outputBuffers = mMediaCodec.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Logging.d(TAG, "encoderStatus INFO_OUTPUT_FORMAT_CHANGED");
                MediaFormat newFormat = mMediaCodec.getOutputFormat();
                newFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1920 * 1080);
                newFormat.setInteger(MediaFormat.KEY_CAPTURE_RATE, 15);
                mVideoTrackIndex = mMediaMuxer.addTrack(newFormat);
                mMediaMuxer.start();
            } else if (encoderStatus < 0) {
                Logging.d(TAG, "encoderStatus < 0");
            } else {
                Logging.d(TAG, "encoderStatus write bufferInfo.flags :" + bufferInfo.flags + ", bufferInfo.size: " + bufferInfo.size);
                ByteBuffer encodedData = outputBuffers[encoderStatus];
                if (bufferInfo.size != 0) {
                    mMediaMuxer.writeSampleData(mVideoTrackIndex, encodedData, bufferInfo);
                }
                mMediaCodec.releaseOutputBuffer(encoderStatus, false);
                break;
            }
        }
    }

    public void startRecord(String outputFile, int outputFileWidth, int outputFileHeight) {
        try {
            setupRecorder(outputFile, outputFileWidth, outputFileHeight);
            mIsRecording = true;
        } catch (IOException e) {
            e.printStackTrace();
            onRecorderError();
            return;
        }
        mClient.addVideoRender(this);
    }

    public void stopRecord() {
        stopRecord(false);
    }

    private void stopRecord(final boolean isError) {
        mIsRecording = false;
        if (mListener != null && !isError) {
            onRecorderCompleting();
        }
        mClient.removeVideoRender(VideoFileRenderer.this);
        mRenderThreadHandler.post(new Runnable() {
            public void run() {
                try {
                    mMediaCodec.flush();
                    mMediaCodec.release();
                    mMediaCodec = null;
                    mMediaMuxer.release();
                    mMediaMuxer = null;
                    mVideoOutFile.flush();
                    mVideoOutFile.close();
                    mVideoOutFile = null;
                    if (mListener != null) {
                        if (isError) {
                            onRecorderError();
                            Logging.d(TAG, "Error when recording");
                        } else {
                            onRecorderComplete();
                            Logging.d(TAG, "Video written to disk as "
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
                    Logging.d(TAG, "Error when stop record: " + e);
                } finally {
                    mRenderThreadHandler.getLooper().quit();
                    mYuvConverter.release();
                    mEglBase.release();
                    mRenderThread.quit();
                }
            }
        });
    }

    public void release() {
        mIsRecording = false;
        if (mRenderThread == null || !mRenderThread.isAlive()) {
            return;
        }
        mRenderThreadHandler.post(new Runnable() {
            public void run() {
                try {
                    if (mMediaCodec != null) {
                        mMediaCodec.flush();
                        mMediaCodec.release();
                        mMediaCodec = null;
                    }
                    if (mMediaMuxer != null) {
                        mMediaMuxer.release();
                        mMediaMuxer = null;
                    }
                    if (mVideoOutFile != null) {
                        mVideoOutFile.flush();
                        mVideoOutFile.close();
                        mVideoOutFile = null;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    Logging.d(TAG, "Error when release: " + e);
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
