package com.pine.rtc.service;

import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by tanghongfeng on 2017/12/13.
 */

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class MediaRecordService {
    private static final String TAG = "MediaRecordService";
    private static final int FRAME_RATE = 30;
    private static final int IFRAME_INTERVAL = 10; // 10 seconds between
    private static final int TIMEOUT_US = 10000;
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private int mWidth;
    private int mHeight;
    private int mBitRate;
    private String mDstPath;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private HandlerThread mRecorderThread;
    private Handler mRecorderThreadHandler;
    private MediaCodec mMediaCodec;
    private MediaMuxer mMediaMuxer;
    private Surface mSurface;
    private AtomicBoolean mQuit = new AtomicBoolean(false);
    private boolean mMuxerStarted = false;
    private int mVideoTrackIndex = -1;
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    public MediaRecordService(int width, int height, int bitrate, String dstPath, MediaProjection mp) {
        mWidth = width;
        mHeight = height;
        mBitRate = bitrate;
        mDstPath = dstPath;
        mMediaProjection = mp;
        mRecorderThread = new HandlerThread("MediaRecordService");
        mRecorderThread.start();
        mRecorderThreadHandler = new Handler(mRecorderThread.getLooper());
    }

    public void reset(int width, int height, int bitrate, String dstPath) {
        mWidth = width;
        mHeight = height;
        mBitRate = bitrate;
        mDstPath = dstPath;
    }

    public void startRecord() {
        mRecorderThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    prepareEncoder();
                    //在mediarecorder.prepare()方法后调用
                    mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG + "-display", mWidth, mHeight,
                            Resources.getSystem().getDisplayMetrics().densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC, mSurface, null, null);
                    Log.i(TAG, "created virtual display: " + mVirtualDisplay);
                    while (!mQuit.get()) {
                        int index = mMediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_US);
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // 后续输出格式变化
                            resetOutputFormat();
                        } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // 请求超时
                            try {
                                // wait 10ms
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                            }
                        } else if (index >= 0) {
                            // 有效输出
                            if (!mMuxerStarted) {
                                throw new IllegalStateException("MediaMuxer dose not call addTrack(format) ");
                            }
                            encodeToVideoTrack(index);
                            mMediaCodec.releaseOutputBuffer(index, false);
                        }
                    }
                    Log.i(TAG, "mediarecorder start");
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    release();
                }
            }
        });
    }

    /**
     * 硬解码获取实时帧数据并写入mp4文件
     *
     * @param index
     */
    private void encodeToVideoTrack(int index) {
        // 获取到的实时帧视频数据
        ByteBuffer encodedData = mMediaCodec.getOutputBuffer(index);
        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // The codec config data was pulled out and fed to the muxer
            // when we got
            // the INFO_OUTPUT_FORMAT_CHANGED status.
            // Ignore it.
            Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
            mBufferInfo.size = 0;
        }
        if (mBufferInfo.size == 0) {
            Log.d(TAG, "info.size == 0, drop it.");
            encodedData = null;
        }
        if (encodedData != null) {
            mMediaMuxer.writeSampleData(mVideoTrackIndex, encodedData, mBufferInfo);
        }
    }

    private void resetOutputFormat() {
        // should happen before receiving buffers, and should only happen
        // once
        if (mMuxerStarted) {
            throw new IllegalStateException("output format already changed!");
        }
        MediaFormat newFormat = mMediaCodec.getOutputFormat();
        mVideoTrackIndex = mMediaMuxer.addTrack(newFormat);
        mMediaMuxer.start();
        mMuxerStarted = true;
        Log.i(TAG, "started media muxer, videoIndex=" + mVideoTrackIndex);
    }

    public void stopRecord() {
        mQuit.set(false);
    }

    private void prepareEncoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);

        Log.d(TAG, "created video format: " + format);
        mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
        mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mSurface = mMediaCodec.createInputSurface();
        Log.d(TAG, "created input surface: " + mSurface);
        mMediaCodec.start();
        mMediaMuxer = new MediaMuxer(mDstPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

    }

    public void release() {
        mRecorderThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mMediaCodec != null) {
                    mMediaCodec.stop();
                    mMediaCodec.release();
                    mMediaCodec = null;
                }
                if (mVirtualDisplay != null) {
                    mVirtualDisplay.release();
                    mVirtualDisplay = null;
                }
                if (mMediaProjection != null) {
                    mMediaProjection.stop();
                    mMediaProjection = null;
                }
                if (mMediaMuxer != null) {
                    mMediaMuxer.stop();
                    mMediaMuxer.release();
                    mMediaMuxer = null;
                }
                Log.i(TAG, "release");
            }
        });
    }
}
