package com.pine.rtc.controller;

import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.RequiresApi;

import org.webrtc.Logging;
import org.webrtc.voiceengine.WebRtcAudioRecord;
import org.webrtc.voiceengine.WebRtcAudioTrack;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by tanghongfeng on 2017/12/13.
 */

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class MediaRecordController {
    private static final String TAG = "MediaRecordController";
    private static final int FRAME_RATE = 15;
    private static final int I_FRAME_INTERVAL = 10; // 10 seconds between
    private static final String VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String AUDIO_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int QUEUE_MAX_COUNT = 100;
    private static final long DEQUEUE_TIME_OUT = 100L;

    private static final int STATE_IDLE = 0;
    private static final int STATE_PREPARING = 1;
    private static final int STATE_RECORDING = 2;
    private static final int STATE_STOPPING = 3;

    private static MediaRecordController mInstance;
    private final Object mLock = new Object();

    private OnRecordListener mOnRecordListener;
    private AtomicBoolean mIsCreate = new AtomicBoolean(false);
    private AtomicBoolean mIsAudioOutInit = new AtomicBoolean(false);
    private AtomicBoolean mIsAudioInInit = new AtomicBoolean(false);

    private int mWidth;
    private int mHeight;
    private int mBitRate;
    private int mDensityDpi;
    private String mDstPath;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private HandlerThread mRecorderThread;
    private int mState = STATE_IDLE;
    private Handler mRecorderThreadHandler;
    private Thread mVideoThread;
    private Thread mAudioFeedThread;
    private Thread mAudioWriteThread;
    private AtomicBoolean mVideoThreadCancel = new AtomicBoolean(true);
    private AtomicBoolean mAudioThreadCancel = new AtomicBoolean(true);

    private LinkedBlockingQueue<AudioData> mAudioOutBufferQueue;
    private int mAudioOutSource;
    private int mAudioOutFormat;
    private int mAudioOutSampleRate;
    private int mAudioOutChannels;
    private int mAudioOutBitsPerSample;
    private int mAudioOutBuffersPerSecond;
    private int mAudioOutBufferSize;
    private LinkedBlockingQueue<AudioData> mAudioInBufferQueue;
    private int mAudioInFormat;
    private int mAudioInSampleRate;
    private int mAudioInChannels;
    private int mAudioInBitsPerSample;
    private int mAudioInBuffersPerSecond;
    private int mAudioInBufferSize;

    private int mAudioFormat;
    private int mAudioSampleRate;
    private int mAudioChannels;
    private int mAudioBitsPerSample;
    private int mAudioBuffersPerSecond;
    private int mAudioBufferSize;

    private long mNanoTime;
    private MediaCodec mVideoCodec;
    private MediaCodec mAudioCodec;
    private long mLastAudioPresentationTimeUs = 0L;
    private int mVideoTrackIndex = -1;
    private int mAudioTrackIndex = -1;
    private AtomicBoolean mMuxerStarted = new AtomicBoolean(false);
    private MediaMuxer mMediaMuxer;

    private Surface mSurface;

    private static final String FILE_SAVE_DIR;

    static {
        FILE_SAVE_DIR = Environment.getExternalStorageDirectory().getPath() + "/rtc/";
    }

    private MediaRecordController() {
        DisplayMetrics displayMetrics = Resources.getSystem().getDisplayMetrics();
        mDensityDpi = displayMetrics.densityDpi;
        mWidth = displayMetrics.widthPixels;
        mHeight = displayMetrics.heightPixels;
        mBitRate = 6000000;
    }

    public static synchronized MediaRecordController getInstance() {
        if (mInstance == null) {
            mInstance = new MediaRecordController();
        }
        return mInstance;
    }

    public void onCreate() {
        mState = STATE_IDLE;
        if (!mIsCreate.get()) {
            mIsCreate.set(true);
            WebRtcAudioRecord.setWebRtcAudioRecordCallback(new WebRtcAudioRecord.WebRtcAudioRecordCallback() {
                @Override
                public void onWebRtcAudioRecordInit(int audioSource, int audioFormat, int sampleRate,
                                                    int channels, int bitsPerSample, int buffersPerSecond,
                                                    int bufferSize) {
                    mIsAudioOutInit.set(true);
                    mAudioOutSource = audioSource;
                    mAudioOutFormat = audioFormat;
                    mAudioOutSampleRate = sampleRate;
                    mAudioOutChannels = channels;
                    mAudioOutBitsPerSample = bitsPerSample;
                    mAudioOutBuffersPerSecond = buffersPerSecond;
                    mAudioOutBufferSize = bufferSize;
                    mAudioOutBufferQueue = new LinkedBlockingQueue<AudioData>(QUEUE_MAX_COUNT);
                    if (mIsAudioInInit.get()) {
                        judgeAudioParams();
                    }
                    Logging.d(TAG, "onWebRtcAudioRecordInit audioSource:" + audioSource
                            + ", audioFormat:" + audioFormat + ", sampleRate:" + sampleRate
                            + ", channels:" + channels + ", bitsPerSample:" + bitsPerSample
                            + ", buffersPerSecond:" + buffersPerSecond + ", bufferSize:" + bufferSize);
                }

                @Override
                public void onWebRtcAudioRecordStart() {
                    Logging.d(TAG, "onWebRtcAudioRecordStart");
                }

                @Override
                public void onWebRtcAudioRecording(ByteBuffer byteBuffer,
                                                   final int bytesRead, boolean microphoneMute) {
//                    Logging.d(TAG, "onWebRtcAudioRecording byteBuffer.capacity():" + byteBuffer.capacity()
//                            + ", bytesRead:" + bytesRead + ", microphoneMute:" + microphoneMute);
                    if (!mAudioThreadCancel.get()) {
                        final ByteBuffer cpBuffer = ByteBuffer.allocateDirect(bytesRead);
                        cpBuffer.order(byteBuffer.order());
                        cpBuffer.put(byteBuffer.array(), byteBuffer.arrayOffset(), bytesRead);
                        cpBuffer.rewind();
                        cpBuffer.limit(bytesRead);
                        AudioData audioData = new AudioData(cpBuffer, System.nanoTime() / 1000L
                                , bytesRead, 1);
                        mAudioOutBufferQueue.offer(audioData);
                        cpBuffer.clear();
                    }
                }

                @Override
                public void onWebRtcAudioRecordStop() {
                    Logging.d(TAG, "onWebRtcAudioRecordStop");
                }
            });

            WebRtcAudioTrack.setWebRtcAudioTrackCallback(new WebRtcAudioTrack.WebRtcAudioTrackCallback() {
                @Override
                public void onWebRtcAudioTrackInit(int audioFormat, int sampleRate, int channels,
                                                   int bitsPerSample, int buffersPerSecond, int bufferSize) {
                    mIsAudioInInit.set(true);
                    mAudioInFormat = audioFormat;
                    mAudioInSampleRate = sampleRate;
                    mAudioInChannels = channels;
                    mAudioInBitsPerSample = bitsPerSample;
                    mAudioInBuffersPerSecond = buffersPerSecond;
                    mAudioInBufferSize = bufferSize;
                    mAudioInBufferQueue = new LinkedBlockingQueue<AudioData>(QUEUE_MAX_COUNT);
                    if (mIsAudioOutInit.get()) {
                        judgeAudioParams();
                    }
                    Logging.d(TAG, "onWebRtcAudioTrackInit audioFormat:" + audioFormat + ", sampleRate:" + sampleRate
                            + ", channels:" + channels + ", bitsPerSample:" + bitsPerSample
                            + ", buffersPerSecond:" + buffersPerSecond + ", bufferSize:" + bufferSize);
                }

                @Override
                public void onWebRtcAudioTrackStart() {
                    Logging.d(TAG, "onWebRtcAudioTrackStart");
                }

                @Override
                public void onWebRtcAudioTracking(ByteBuffer byteBuffer, final int bytesWrite, boolean speakerMute) {
//                    Logging.d(TAG, "onWebRtcAudioTracking byteBuffer.capacity():" + byteBuffer.capacity()
//                            + ", bytesWrite:" + bytesWrite + ", speakerMute:" + speakerMute);
                    if (!mAudioThreadCancel.get()) {
                        final ByteBuffer cpBuffer = ByteBuffer.allocateDirect(bytesWrite);
                        cpBuffer.order(byteBuffer.order());
                        cpBuffer.put(byteBuffer.array(), byteBuffer.arrayOffset(), bytesWrite);
                        cpBuffer.rewind();
                        cpBuffer.limit(bytesWrite);
                        AudioData audioData = new AudioData(cpBuffer, System.nanoTime() / 1000L,
                                bytesWrite, 2);
                        mAudioInBufferQueue.offer(audioData);
                        cpBuffer.clear();
                    }
                }

                @Override
                public void onWebRtcAudioTrackStop() {
                    Logging.d(TAG, "onWebRtcAudioTrackStop");
                }
            });
        }
    }

    private synchronized void judgeAudioParams() {
        Logging.d(TAG, "judgeAudioParams mAudioInSampleRate:" + mAudioInSampleRate
                + ", mAudioOutSampleRate:" + mAudioOutSampleRate
                + ", mAudioInChannels:" + mAudioInChannels
                + ", mAudioOutChannels:" + mAudioOutChannels
                + ", mAudioInBitsPerSample:" + mAudioInBitsPerSample
                + ", mAudioOutBitsPerSample:" + mAudioOutBitsPerSample
                + ", mAudioInBufferSize:" + mAudioInBufferSize
                + ", mAudioOutBufferSize:" + mAudioOutBufferSize);
        mAudioSampleRate = Math.max(mAudioInSampleRate, mAudioOutSampleRate);
        mAudioChannels = Math.max(mAudioInChannels, mAudioOutChannels);
        mAudioBitsPerSample = Math.max(mAudioInBitsPerSample, mAudioOutBitsPerSample);
        mAudioBufferSize = Math.max(mAudioInBufferSize, mAudioOutBufferSize);
    }

    public void setupController(OnRecordListener listener, MediaProjection mp) {
        this.setupController(listener, mp, mBitRate, mDensityDpi, mWidth, mHeight);
    }

    public void setupController(OnRecordListener listener, MediaProjection mp, int bitrate,
                                int dpi, int width, int height) {
        if (!mIsCreate.get()) {
            onCreate();
        }
        mOnRecordListener = listener;
        mMediaProjection = mp;
        mBitRate = bitrate;
        mDensityDpi = dpi;
        mWidth = width;
        mHeight = height;
        mRecorderThread = new HandlerThread("MediaRecordController");
        mRecorderThread.start();
        mRecorderThreadHandler = new Handler(mRecorderThread.getLooper());
        mState = STATE_IDLE;
    }

    public void startRecord() {
        startRecord(mDstPath);
    }

    public void startRecord(String filePath) {
        if (!mIsCreate.get()) {
            throw new RuntimeException("you need call onCreate method of MediaRecordController before start record");
        }
        mDstPath = filePath;
        if (TextUtils.isEmpty(mDstPath)) {
            mDstPath = FILE_SAVE_DIR + "/room.mp4";
        }
        File file = new File(mDstPath);
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        mNanoTime = System.nanoTime();
        mRecorderThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mState = STATE_PREPARING;
                    if (mAudioFeedThread != null && mAudioFeedThread.isAlive()) {
                        mAudioThreadCancel.set(true);
                        mAudioFeedThread.join();
                    }
                    if (mAudioWriteThread != null && mAudioWriteThread.isAlive()) {
                        mAudioThreadCancel.set(true);
                        mAudioWriteThread.join();
                    }
                    if (mVideoThread != null && mVideoThread.isAlive()) {
                        mVideoThreadCancel.set(true);
                        mVideoThread.join();
                    }
                    prepareEncoder();
                    mMediaMuxer = new MediaMuxer(mDstPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG + "-display",
                        mWidth, mHeight, mDensityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC, mSurface, null, null);
                Log.d(TAG, "created virtual display: " + mVirtualDisplay);

                mAudioThreadCancel.set(false);
                mAudioFeedThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (!mAudioThreadCancel.get()) {
                            if (feedAudioData()) {
                                break;
                            }
                        }
                    }
                });
                mAudioFeedThread.start();

                mAudioWriteThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (!mAudioThreadCancel.get()) {
                            if (writeAudioData()) {
                                break;
                            }
                        }
                    }
                });
                mAudioWriteThread.start();

                mVideoThreadCancel.set(false);
                mVideoThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (!mVideoThreadCancel.get()) {
                            if (writeVideoData()) {
                                break;
                            }
                        }
                    }
                });
                mVideoThread.start();
                mState = STATE_RECORDING;
            }
        });
    }

    public final synchronized void stopRecord() {
        mState = STATE_STOPPING;
        release(false);
    }

    public void release(final boolean destroy) {
        if (mRecorderThreadHandler != null) {
            mRecorderThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (mAudioFeedThread != null && mAudioFeedThread.isAlive()) {
                            mAudioThreadCancel.set(true);
                            mAudioFeedThread.join();
                        }
                        if (mAudioWriteThread != null && mAudioWriteThread.isAlive()) {
                            mAudioThreadCancel.set(true);
                            mAudioWriteThread.join();
                        }
                        if (mVideoThread != null && mVideoThread.isAlive()) {
                            mVideoThreadCancel.set(true);
                            mVideoThread.join();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        mAudioThreadCancel.set(true);
                        mVideoThreadCancel.set(true);
                    }
                    if (mVirtualDisplay != null) {
                        mVirtualDisplay.release();
                    }
                    if (destroy) {
                        mMediaProjection = null;
                    }
                    if (mAudioCodec != null) {
                        mAudioCodec.stop();
                        mAudioCodec.release();
                        mAudioCodec = null;
                    }
                    if (mVideoCodec != null) {
                        mVideoCodec.stop();
                        mVideoCodec.release();
                        mVideoCodec = null;
                    }
                    mAudioTrackIndex = -1;
                    mVideoTrackIndex = -1;
                    if (mMuxerStarted.get()) {
                        mMuxerStarted.set(false);
                        if (mMediaMuxer != null) {
                            mMediaMuxer.stop();
                            mMediaMuxer.release();
                            mMediaMuxer = null;
                        }
                    }
                    if (mOnRecordListener != null && mState != STATE_IDLE) {
                        mOnRecordListener.onFinish(mDstPath);
                    }
                    if (destroy) {
                        mOnRecordListener = null;
                    }
                    mState = STATE_IDLE;
                    Logging.d(TAG, "released");
                }
            });
        }
    }

    private boolean feedAudioData() {
        if (mAudioOutBufferQueue.size() < 1 || mAudioInBufferQueue.size() < 1) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return false;
        }
        AudioData audioOut = mAudioOutBufferQueue.poll();
        AudioData audioIn = mAudioInBufferQueue.poll();
        ByteBuffer audioOutBuffer = audioOut.mData;
        ByteBuffer audioInBuffer = audioIn.mData;
        int outSize = audioOut.mSize;
        int inSize = audioIn.mSize;
        int size = Math.max(outSize, inSize);
        long timeUs = audioOut.mPresentationTimeUs;
        byte[][] bMulRoadAudios = new byte[2][size];
        audioOutBuffer.get(bMulRoadAudios[0], 0, size);
        audioInBuffer.get(bMulRoadAudios[1], 0, size);
        if (outSize > inSize) {
            for (int i = inSize; i < outSize; i++) {
                bMulRoadAudios[1][i] = 0x00;
            }
        } else {
            for (int i = outSize; i < inSize; i++) {
                bMulRoadAudios[0][i] = 0x00;
            }
        }
        byte[] mixAudio = averageMix(bMulRoadAudios);
        int index = mAudioCodec.dequeueInputBuffer((System.nanoTime() - mNanoTime) / 1000L);
        if (index >= 0) {
            ByteBuffer inputBuffer = mAudioCodec.getInputBuffer(index);
            inputBuffer.clear();
            inputBuffer.put(mixAudio);
            mAudioCodec.queueInputBuffer(index, 0, size,
                    timeUs, // presentationTimeUs要与视频的一致（MediaProjection使用的是System.nanoTime() / 1000L）
                    mAudioThreadCancel.get() ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
        }
        return false;
    }

    private boolean writeAudioData() {
        MediaCodec.BufferInfo audioBufferInfo = new MediaCodec.BufferInfo();
        int outIndex = mAudioCodec.dequeueOutputBuffer(audioBufferInfo, DEQUEUE_TIME_OUT);
        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // 后续输出格式变化
            if (mMuxerStarted.get()) {
                throw new IllegalStateException("output format already changed!");
            }
            MediaFormat newFormat = mAudioCodec.getOutputFormat();
            mAudioTrackIndex = mMediaMuxer.addTrack(newFormat);
            synchronized (mLock) {
                if (mAudioTrackIndex >= 0 && mVideoTrackIndex >= 0) {
                    mMediaMuxer.start();
                    mMuxerStarted.set(true);
                    Logging.d(TAG, "started media muxer, mAudioTrackIndex=" + mAudioTrackIndex
                            + ",mVideoTrackIndex=" + mVideoTrackIndex);
                }
            }
        } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            // 请求超时
            try {
                // wait 10ms
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }
        } else if (outIndex >= 0) {
            // 获取到的实时音频数据
            ByteBuffer encodedData = mAudioCodec.getOutputBuffer(outIndex);
            if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                // The codec config data was pulled out and fed to the muxer
                // when we got
                // the INFO_OUTPUT_FORMAT_CHANGED status.
                // Ignore it.
                Logging.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                audioBufferInfo.size = 0;
            }
            if (audioBufferInfo.size == 0) {
                Logging.d(TAG, "info.size == 0, drop it.");
                encodedData = null;
            }
            if (encodedData != null && mMuxerStarted.get()
                    && mLastAudioPresentationTimeUs < audioBufferInfo.presentationTimeUs) {
                mMediaMuxer.writeSampleData(mAudioTrackIndex, encodedData, audioBufferInfo);
                mLastAudioPresentationTimeUs = audioBufferInfo.presentationTimeUs;
            }
            mAudioCodec.releaseOutputBuffer(outIndex, false);
            if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                return true;
            }
        }
        return false;
    }

    private boolean writeVideoData() {
        MediaCodec.BufferInfo videoBufferInfo = new MediaCodec.BufferInfo();
        int outIndex = mVideoCodec.dequeueOutputBuffer(videoBufferInfo, DEQUEUE_TIME_OUT);
        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // 后续输出格式变化
            if (mMuxerStarted.get()) {
                throw new IllegalStateException("output format already changed!");
            }
            MediaFormat newFormat = mVideoCodec.getOutputFormat();
            mVideoTrackIndex = mMediaMuxer.addTrack(newFormat);
            synchronized (mLock) {
                if (mAudioTrackIndex >= 0 && mVideoTrackIndex >= 0) {
                    mMediaMuxer.start();
                    mMuxerStarted.set(true);
                    Logging.d(TAG, "started media muxer, mAudioTrackIndex=" + mAudioTrackIndex
                            + ",mVideoTrackIndex=" + mVideoTrackIndex);
                }
            }
        } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            // 请求超时
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }
        } else if (outIndex >= 0) {
            // 获取到的实时帧视频数据
            ByteBuffer encodedData = mVideoCodec.getOutputBuffer(outIndex);
            if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                // The codec config data was pulled out and fed to the muxer
                // when we got
                // the INFO_OUTPUT_FORMAT_CHANGED status.
                // Ignore it.
                Logging.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                videoBufferInfo.size = 0;
            }
            if (videoBufferInfo.size == 0) {
                Logging.d(TAG, "info.size == 0, drop it.");
                encodedData = null;
            }
            if (encodedData != null && mMuxerStarted.get()) {
                mMediaMuxer.writeSampleData(mVideoTrackIndex, encodedData, videoBufferInfo);
            }
            mVideoCodec.releaseOutputBuffer(outIndex, false);
            if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                return true;
            }
        }
        return false;
    }

    private void prepareEncoder() throws IOException {
        MediaFormat audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE, mAudioSampleRate, mAudioChannels);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, mAudioBitsPerSample * mAudioSampleRate * 4);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, mAudioBufferSize);
        Log.d(TAG, "created audio format: " + audioFormat);
        mAudioCodec = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        mAudioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioCodec.start();

        MediaFormat videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, mWidth, mHeight);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        Log.d(TAG, "created video format: " + videoFormat);
        mVideoCodec = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
        mVideoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mSurface = mVideoCodec.createInputSurface();
        Log.d(TAG, "created input surface: " + mSurface);
        mVideoCodec.start();
    }

    /**
     * 采用简单的平均算法 average audio mixing algorithm
     * 这种算法会降低录制的音量
     */
    private byte[] averageMix(byte[][] bMulRoadAudios) {
        if (bMulRoadAudios == null || bMulRoadAudios.length == 0) {
            return null;
        }
        byte[] realMixAudio = bMulRoadAudios[0];
        if (bMulRoadAudios.length == 1) {
            return realMixAudio;
        }
        for (int rw = 0; rw < bMulRoadAudios.length; ++rw) {
            if (bMulRoadAudios[rw].length != realMixAudio.length) {
                Logging.d(TAG, "column of the road of audio + " + rw + " is different.");
                return null;
            }
        }
        int row = bMulRoadAudios.length;
        int column = realMixAudio.length / 2;
        short[][] sMulRoadAudios = new short[row][column];
        for (int r = 0; r < row; ++r) {
            for (int c = 0; c < column; ++c) {
                sMulRoadAudios[r][c] = (short) ((bMulRoadAudios[r][c * 2] & 0xff) | (bMulRoadAudios[r][c * 2 + 1] & 0xff) << 8);
            }
        }
        short[] sMixAudio = new short[column];
        int mixVal;
        int sr = 0;
        for (int sc = 0; sc < column; ++sc) {
            mixVal = 0;
            sr = 0;
            for (; sr < row; ++sr) {
                mixVal += sMulRoadAudios[sr][sc];
            }
            sMixAudio[sc] = (short) (mixVal / row);
        }
        for (sr = 0; sr < column; ++sr) {
            realMixAudio[sr * 2] = (byte) (sMixAudio[sr] & 0x00FF);
            realMixAudio[sr * 2 + 1] = (byte) ((sMixAudio[sr] & 0xFF00) >> 8);
        }
        return realMixAudio;
    }

    // call back listener
    public interface OnRecordListener {
        void onFinish(String filePath);
    }

    class AudioData {
        ByteBuffer mData;
        long mPresentationTimeUs;
        int mSize;
        int mType;  // 1-out;2-in

        public AudioData(ByteBuffer data, long timeUs, int size, int type) {
            mData = data;
            mPresentationTimeUs = timeUs;
            mSize = size;
            mType = type;
        }
    }
}
