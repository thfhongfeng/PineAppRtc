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
public class ScreenRecorderService extends Thread {

    private static final String TAG = "ScreenRecordService";

    private int mWidth;
    private int mHeight;
    private int mBitRate;
    private String mDstPath;
    private MediaProjection mMediaProjection;
    // parameters for the encoder
    private static final String MIME_TYPE = "video/avc"; // H.264 Advanced
    // Video Coding
    private static final int FRAME_RATE = 30; // 30 fps
    private static final int IFRAME_INTERVAL = 10; // 10 seconds between
    // I-frames
    private static final int TIMEOUT_US = 10000;

    private MediaCodec mEncoder;
    private Surface mSurface;
    private MediaMuxer mMuxer;
    private boolean mMuxerStarted = false;
    private int mVideoTrackIndex = -1;
    private AtomicBoolean mQuit = new AtomicBoolean(false);
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private VirtualDisplay mVirtualDisplay;

    public ScreenRecorderService(int width, int height, int bitrate, MediaProjection mp, String dstPath) {
        super(TAG);
        mWidth = width;
        mHeight = height;
        mBitRate = bitrate;
        mMediaProjection = mp;
        mDstPath = dstPath;
    }

    /**
     * stop task
     */
    public final void quit() {
        mQuit.set(true);
    }

    @Override
    public void run() {
        try {
            try {
                prepareEncoder();
                mMuxer = new MediaMuxer(mDstPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG + "-display", mWidth, mHeight,
                    Resources.getSystem().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC, mSurface, null, null);
            Log.d(TAG, "created virtual display: " + mVirtualDisplay);
            recordVirtualDisplay();

        } finally {
            release();
        }
    }

    private void recordVirtualDisplay() {
        while (!mQuit.get()) {
            int index = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_US);
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

                mEncoder.releaseOutputBuffer(index, false);
            }
        }
    }

    /**
     * 硬解码获取实时帧数据并写入mp4文件
     *
     * @param index
     */
    private void encodeToVideoTrack(int index) {
        // 获取到的实时帧视频数据
        ByteBuffer encodedData = mEncoder.getOutputBuffer(index);

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
            mMuxer.writeSampleData(mVideoTrackIndex, encodedData, mBufferInfo);
        }
    }

    private void resetOutputFormat() {
        // should happen before receiving buffers, and should only happen
        // once
        if (mMuxerStarted) {
            throw new IllegalStateException("output format already changed!");
        }
        MediaFormat newFormat = mEncoder.getOutputFormat();
        mVideoTrackIndex = mMuxer.addTrack(newFormat);
        mMuxer.start();
        mMuxerStarted = true;
        Log.i(TAG, "started media muxer, videoIndex=" + mVideoTrackIndex);
    }

    private void prepareEncoder() throws IOException {

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

        Log.d(TAG, "created video format: " + format);
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mSurface = mEncoder.createInputSurface();
        Log.d(TAG, "created input surface: " + mSurface);
        mEncoder.start();
    }

    private void release() {
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
        }
        if (mMuxer != null) {
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        }
    }
}
