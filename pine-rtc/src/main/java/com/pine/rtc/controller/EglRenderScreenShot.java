package com.pine.rtc.controller;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import org.webrtc.EglRenderer;
import org.webrtc.Logging;
import org.webrtc.SurfaceViewRenderer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Created by tanghongfeng on 2017/12/1.
 */

public class EglRenderScreenShot {
    private static final String TAG = EglRenderScreenShot.class.getSimpleName();

    private static EglRenderScreenShot mInstance;

    private SurfaceViewRenderer mScreenShotRender;
    private String mFilePath;
    private Handler mHandler;
    private OnShotListener mOnShotListener;

    private EglRenderer.FrameListener mRenderFrameListener = new EglRenderer.FrameListener() {

        @Override
        public void onFrame(final Bitmap bitmap) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mScreenShotRender != null) {
                        mScreenShotRender.removeFrameListener(mRenderFrameListener);
                        if (mOnShotListener != null) {
                            mOnShotListener.onScreenShot(bitmap);
                        }
                        AsyncTask task = new SaveTask();
                        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, bitmap);
                    }
                }
            });
        }
    };

    private EglRenderScreenShot() {

    }

    public static synchronized EglRenderScreenShot getInstance() {
        if (mInstance == null) {
            mInstance = new EglRenderScreenShot();
        }
        return mInstance;
    }

    public EglRenderScreenShot setupScreenShot(SurfaceViewRenderer surfaceViewRenderer, OnShotListener listener, Handler handler) {
        if (surfaceViewRenderer == null) {
            Logging.d(TAG, "surfaceViewRenderer is null, return");
            return null;
        }
        mScreenShotRender = surfaceViewRenderer;
        if (handler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        } else {
            mHandler = handler;
        }
        mOnShotListener = listener;
        return mInstance;
    }

    public void screenShot() {
        this.screenShot(mFilePath);
    }

    public void screenShot(String filePath) {
        if (mScreenShotRender == null) {
            return;
        }
        mFilePath = filePath;
        mScreenShotRender.removeFrameListener(mRenderFrameListener);
        mScreenShotRender.addFrameListener(mRenderFrameListener, 1.0f);
    }

    public class SaveTask extends AsyncTask<Bitmap, Void, String> {
        @TargetApi(Build.VERSION_CODES.KITKAT)
        @Override
        protected String doInBackground(Bitmap... params) {
            if (params == null || params.length < 1 || params[0] == null) {
                return null;
            }
            Bitmap bitmap = params[0];
            mFilePath = saveBitMap(bitmap);
            return mFilePath;
        }

        @TargetApi(Build.VERSION_CODES.KITKAT)
        @Override
        protected void onPostExecute(String filePath) {
            super.onPostExecute(filePath);
            if (mOnShotListener != null) {
                mOnShotListener.onScreenShotSave(filePath);
            }
        }
    }

    private String saveBitMap(Bitmap bitmap) {
        if (bitmap != null) {
            try {
                if (TextUtils.isEmpty(mFilePath)) {
                    mFilePath = Environment.getExternalStorageDirectory().getPath()
                            + "/rtc/" +
                            SystemClock.currentThreadTimeMillis() + ".png";
                }
                File file = new File(mFilePath.substring(0, mFilePath.lastIndexOf("/")));
                if (!file.exists()) {
                    file.mkdir();
                }
                File fileImage = new File(mFilePath);
                if (!fileImage.exists()) {
                    fileImage.createNewFile();
                }
                FileOutputStream out = new FileOutputStream(fileImage);
                if (out != null) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    out.flush();
                    out.close();
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                mFilePath = null;
            } catch (IOException e) {
                e.printStackTrace();
                mFilePath = null;
            }
        }
        return mFilePath;
    }

    public static interface OnShotListener {
        void onScreenShot(Bitmap bitmap);

        void onScreenShotSave(String filePath);
    }
}
