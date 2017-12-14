package com.pine.rtc.service;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.util.Log;

/**
 * Created by tanghongfeng on 2017/12/13.
 */

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class MediaRecorderService extends Thread {
    private static final String TAG = "MediaRecordService";
    private static final int FRAME_RATE = 30;
    private int mWidth;
    private int mHeight;
    private int mBitRate;
    private int mDpi;
    private String mDstPath;
    private MediaRecorder mMediaRecorder;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;

    public MediaRecorderService(int width, int height, int bitrate, int dpi, MediaProjection mp, String dstPath) {
        mWidth = width;
        mHeight = height;
        mBitRate = bitrate;
        mDpi = dpi;
        mMediaProjection = mp;
        mDstPath = dstPath;
    }

    @Override
    public void run() {
        try {
            initMediaRecorder();

            //在mediarecorder.prepare()方法后调用
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG + "-display", mWidth, mHeight, mDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mMediaRecorder.getSurface(), null, null);
            Log.i(TAG, "created virtual display: " + mVirtualDisplay);
            mMediaRecorder.start();
            Log.i(TAG, "mediarecorder start");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 初始化MediaRecorder
     *
     * @return
     */
    public void initMediaRecorder() {
        mMediaRecorder = new MediaRecorder();
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setOutputFile(mDstPath);
        mMediaRecorder.setVideoSize(mWidth, mHeight);
        mMediaRecorder.setVideoFrameRate(FRAME_RATE);
        mMediaRecorder.setVideoEncodingBitRate(mBitRate);
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);

        try {
            mMediaRecorder.prepare();
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.i(TAG, "media recorder" + mBitRate + "kps");
    }

    public void release() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        if (mMediaRecorder != null) {
            mMediaRecorder.setOnErrorListener(null);
            mMediaRecorder.stop();
            mMediaRecorder.reset();
            mMediaRecorder.release();
        }
        Log.i(TAG, "release");
    }
}
