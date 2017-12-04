package com.pine.rtc.component;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v4.os.AsyncTaskCompat;
import android.text.TextUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;

/**
 * Created by tanghongfeng on 2017/12/1.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class MediaProjectionScreenShot {

    private static final String TAG = MediaProjectionScreenShot.class.getSimpleName();

    private final SoftReference<Context> mRefContext;
    private ImageReader mImageReader;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private String mLocalUrl = "";
    private OnShotListener mOnShotListener;

    public MediaProjectionScreenShot(Context context, Intent data) {
        this.mRefContext = new SoftReference<>(context);
        mMediaProjection = getMediaProjectionManager().getMediaProjection(Activity.RESULT_OK,
                data);
        mImageReader = ImageReader.newInstance(
                getScreenWidth(),
                getScreenHeight(),
                PixelFormat.RGBA_8888,
                1);
    }

    private void setupVirtualDisplay() {
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("screen-mirror",
                getScreenWidth(),
                getScreenHeight(),
                Resources.getSystem().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(), null, null);
    }

    public void setupScreenShot(OnShotListener onShotListener, String loc_url) {
        mLocalUrl = loc_url;
        setupScreenShot(onShotListener);
    }

    public void setupScreenShot(OnShotListener onShotListener) {
        mOnShotListener = onShotListener;
        setupVirtualDisplay();
    }

    public void startScreenShot() {
        if (mVirtualDisplay == null) {
            setupVirtualDisplay();
        }
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
                AsyncTaskCompat.executeParallel(new SaveTask(), bitmap);
            }
        }, 50);
    }

    public void release() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
    }

    private MediaProjectionManager getMediaProjectionManager() {
        return (MediaProjectionManager) getContext().getSystemService(
                Context.MEDIA_PROJECTION_SERVICE);
    }

    private Context getContext() {
        return mRefContext.get();
    }

    private int getScreenWidth() {
        return Resources.getSystem().getDisplayMetrics().widthPixels;
    }

    private int getScreenHeight() {
        return Resources.getSystem().getDisplayMetrics().heightPixels;
    }

    // a  call back listener
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
                    url = Environment.getExternalStorageDirectory().getPath()
                            + "/rtc/" +
                            SystemClock.currentThreadTimeMillis() + ".png";
                }
                File file = new File(url.substring(0, url.lastIndexOf("/")));
                if (!file.exists()) {
                    file.mkdir();
                }
                fileImage = new File(url);
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