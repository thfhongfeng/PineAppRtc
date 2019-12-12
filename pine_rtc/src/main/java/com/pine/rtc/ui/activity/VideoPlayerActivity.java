package com.pine.rtc.ui.activity;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.Window;
import android.widget.Toast;
import android.widget.VideoView;

import com.pine.rtc.R;

public class VideoPlayerActivity extends Activity implements MediaPlayer.OnBufferingUpdateListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnPreparedListener, TextureView.SurfaceTextureListener {

    private static final String TAG = "VideoPlayerActivity";

    private VideoView videoView;
    private int mVideoWidth;
    private int mVideoHeight;
    private MediaPlayer mMediaPlayer;
    private TextureView mTextureView;
    private String mVideoUrl;
    private Surface mSurf;

    private boolean mIsVideoSizeKnown = false;
    private boolean mIsVideoReadyToBePlayed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_video_player);
        SharedPreferences sharedPreferences =
                getSharedPreferences("FilePath", MODE_PRIVATE);
        mVideoUrl = sharedPreferences.getString("lastVideo", null);
        if (TextUtils.isEmpty(mVideoUrl)) {
            finish();
            return;
        }
        mTextureView = (TextureView) findViewById(R.id.surface);
        mTextureView.setSurfaceTextureListener(this);
    }

    private void playVideo(SurfaceTexture surfaceTexture) {
        doCleanUp();
        try {
            if (mVideoUrl == "") {
                // Tell the user to provide a media file URL.
                Toast.makeText(
                        VideoPlayerActivity.this,
                        "Please edit MediaPlayerDemo_setSurface Activity, "
                                + "and set the path variable to your media file path."
                                + " Your media file must be stored on sdcard.", Toast.LENGTH_LONG).show();
                return;
            }
            // Create a new media player and set the listeners
            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setDataSource(mVideoUrl);
            if (mSurf == null) {
                mSurf = new Surface(surfaceTexture);
            }
            mMediaPlayer.setSurface(mSurf);
            mMediaPlayer.prepareAsync();
            mMediaPlayer.setOnBufferingUpdateListener(this);
            mMediaPlayer.setOnCompletionListener(this);
            mMediaPlayer.setOnPreparedListener(this);
            setVolumeControlStream(AudioManager.STREAM_MUSIC);

        } catch (Exception e) {
            Log.e(TAG, "error: " + e.getMessage(), e);
        }
    }

    public void onBufferingUpdate(MediaPlayer arg0, int percent) {
        // Log.d(TAG, "onBufferingUpdate percent:" + percent);

    }

    public void onCompletion(MediaPlayer arg0) {
        Log.d(TAG, "onCompletion called");
    }

    public void onPrepared(MediaPlayer mediaplayer) {
        Log.d(TAG, "onPrepared called");
        mIsVideoReadyToBePlayed = true;
        if (mIsVideoReadyToBePlayed) {
            startVideoPlayback();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseMediaPlayer();
        doCleanUp();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseMediaPlayer();
        doCleanUp();
    }

    private void releaseMediaPlayer() {
        if (mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
    }

    private void doCleanUp() {
        mVideoWidth = 0;
        mVideoHeight = 0;
        mIsVideoReadyToBePlayed = false;
        mIsVideoSizeKnown = false;
    }

    private void startVideoPlayback() {
        Log.v(TAG, "startVideoPlayback");
        mMediaPlayer.start();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        playVideo(surface);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {

    }
}
