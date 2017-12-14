package com.pine.rtc.service;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.os.AsyncTaskCompat;
import android.text.TextUtils;

import org.webrtc.EglRenderer;
import org.webrtc.SurfaceViewRenderer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Created by tanghongfeng on 2017/12/1.
 */

public class EglRenderScreenShot {

    private static EglRenderScreenShot mInstance;

    private SurfaceViewRenderer mScreenShotRender;
    private String mFilePath;
    private Handler mHandler;
    private Callback mCallback;

    private EglRenderer.FrameListener mRenderFrameListener = new EglRenderer.FrameListener() {

        @Override
        public void onFrame(final Bitmap bitmap) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mScreenShotRender != null) {
                        mScreenShotRender.removeFrameListener(mRenderFrameListener);
                        if (mCallback != null) {
                            mCallback.onScreenShot(bitmap);
                        }
                        AsyncTaskCompat.executeParallel(new SaveTask(), bitmap);
                    }
                }
            });
        }
    };

    private EglRenderScreenShot() {

    }

    public static synchronized EglRenderScreenShot getInstance(
            SurfaceViewRenderer surfaceViewRenderer, String filePath, Callback callback, Handler handler) {
        if (mInstance == null) {
            mInstance = new EglRenderScreenShot();
        }
        if (surfaceViewRenderer == null) {
            return null;
        }
        mInstance.mFilePath = filePath;
        mInstance.mScreenShotRender = surfaceViewRenderer;
        if (handler == null) {
            mInstance.mHandler = new Handler(Looper.getMainLooper());
        } else {
            mInstance.mHandler = handler;
        }
        mInstance.mCallback = callback;
        return mInstance;
    }

    public void screenShot() {
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
            mFilePath = saveBitMap(bitmap, mFilePath);
            return mFilePath;
        }

        @TargetApi(Build.VERSION_CODES.KITKAT)
        @Override
        protected void onPostExecute(String filePath) {
            super.onPostExecute(filePath);
            if (mCallback != null) {
                mCallback.onScreenShotSave(filePath);
            }
        }
    }

    private String saveBitMap(Bitmap bitmap, String filePath) {
        String fileImagePath = null;
        if (bitmap != null) {
            try {
                if (TextUtils.isEmpty(filePath)) {
                    fileImagePath = Environment.getExternalStorageDirectory().getPath()
                            + "/rtc/" +
                            SystemClock.currentThreadTimeMillis() + ".png";
                }
                File file = new File(fileImagePath.substring(0, fileImagePath.lastIndexOf("/")));
                if (!file.exists()) {
                    file.mkdir();
                }
                File fileImage = new File(fileImagePath);
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
                fileImagePath = null;
            } catch (IOException e) {
                e.printStackTrace();
                fileImagePath = null;
            }
        }
        return fileImagePath;
    }

    public static interface Callback {
        void onScreenShot(Bitmap bitmap);
        void onScreenShotSave(String filePath);
    }
}
