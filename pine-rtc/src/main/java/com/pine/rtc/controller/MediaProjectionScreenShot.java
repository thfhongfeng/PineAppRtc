package com.pine.rtc.controller;

import android.annotation.TargetApi;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.text.TextUtils;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by tanghongfeng on 2017/12/1.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class MediaProjectionScreenShot {

    private static final String TAG = MediaProjectionScreenShot.class.getSimpleName();

    private static MediaProjectionScreenShot mInstance;
    private ImageReader mImageReader;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private String mLocalUrl = "";
    private int mDensityDpi;
    private int mWidth;
    private int mHeight;
    private OnShotListener mOnShotListener;

    private static final String FILE_SAVE_DIR;

    static {
        FILE_SAVE_DIR = Environment.getExternalStorageDirectory().getPath() + "/rtc/";
    }

    public static synchronized MediaProjectionScreenShot getInstance() {
        if (mInstance == null) {
            mInstance = new MediaProjectionScreenShot();
        }
        return mInstance;
    }

    private MediaProjectionScreenShot() {
        DisplayMetrics displayMetrics = Resources.getSystem().getDisplayMetrics();
        mDensityDpi = displayMetrics.densityDpi;
        mWidth = displayMetrics.widthPixels;
        mHeight = displayMetrics.heightPixels;
    }

    public MediaProjectionScreenShot setupScreenShot(OnShotListener onShotListener, MediaProjection mp) {
        return this.setupScreenShot(onShotListener, mp, mDensityDpi, mWidth, mHeight);
    }

    public MediaProjectionScreenShot setupScreenShot(OnShotListener onShotListener, MediaProjection mp,
                                                     int densityDpi, int width, int height) {
        mMediaProjection = mp;
        mOnShotListener = onShotListener;
        mDensityDpi = densityDpi;
        mWidth = width;
        mHeight = height;
        mImageReader = ImageReader.newInstance(
                mWidth,
                mHeight,
                PixelFormat.RGBA_8888,
                1);
        setupVirtualDisplay();
        return mInstance;
    }

    private void setupVirtualDisplay() {
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("screen-mirror",
                mWidth, mHeight, mDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(), null, null);
    }

    public void startScreenShot() {
        startScreenShot(mLocalUrl);
    }

    public void startScreenShot(String loc_url) {
        if (mVirtualDisplay == null) {
            setupVirtualDisplay();
        }
        mLocalUrl = loc_url;
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                Image image = mImageReader.acquireLatestImage();
                int width = image.getWidth();
                int height = image.getHeight();
                final Image.Plane[] planes = image.getPlanes();
                final ByteBuffer buffer = planes[0].getBuffer();
                //每个像素的间距
                int pixelStride = planes[0].getPixelStride();
                //总的间距
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * width;
                Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height,
                        Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(buffer);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
                image.close();
                if (mOnShotListener != null) {
                    mOnShotListener.onFinish(bitmap);
                }
                AsyncTask task = new SaveTask();
                task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, bitmap);
            }
        }, 50);
    }

    public void release(boolean destroy) {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (destroy) {
            mMediaProjection = null;
        }
    }

    // call back listener
    public interface OnShotListener {
        void onFinish(Bitmap bitmap);

        void onSaveFinish(String filePath);
    }

    public class SaveTask extends AsyncTask<Bitmap, Void, String> {
        @TargetApi(Build.VERSION_CODES.KITKAT)
        @Override
        protected String doInBackground(Bitmap... params) {
            if (params == null || params.length < 1 || params[0] == null) {
                return null;
            }
            Bitmap bitmap = params[0];
            File fileImage = saveBitMap(bitmap, mLocalUrl);
            mLocalUrl = fileImage.getPath();
            if (fileImage != null) {
                return mLocalUrl;
            }
            return null;
        }

        @TargetApi(Build.VERSION_CODES.KITKAT)
        @Override
        protected void onPostExecute(String filePath) {
            super.onPostExecute(filePath);
            if (mOnShotListener != null) {
                mOnShotListener.onSaveFinish(filePath);
            }
        }
    }

    private File saveBitMap(Bitmap bitmap, String url) {
        File fileImage = null;
        if (bitmap != null) {
            try {
                if (TextUtils.isEmpty(url)) {
                    Date now = new Date();
                    DateFormat format = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss");
                    url = FILE_SAVE_DIR + format.format(now) + ".png";
                }
                fileImage = new File(url);
                if (!fileImage.getParentFile().exists()) {
                    fileImage.getParentFile().mkdirs();
                }
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
                fileImage = null;
            } catch (IOException e) {
                e.printStackTrace();
                fileImage = null;
            }
        }
        return fileImage;
    }
}