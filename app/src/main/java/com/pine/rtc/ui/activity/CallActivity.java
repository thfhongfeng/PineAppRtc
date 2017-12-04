/*
 *  Copyright 2015 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package com.pine.rtc.ui.activity;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.Toast;

import com.pine.rtc.R;
import com.pine.rtc.component.EglRenderScreenShot;
import com.pine.rtc.component.MediaProjectionScreenShot;
import com.pine.rtc.component.SurfaceViewRenderer;
import com.pine.rtc.component.VideoFileRenderer;
import com.pine.rtc.controller.AppRTCAudioManager;
import com.pine.rtc.controller.AppRTCAudioManager.AudioDevice;
import com.pine.rtc.controller.AppRTCAudioManager.AudioManagerEvents;
import com.pine.rtc.controller.AppRTCClient;
import com.pine.rtc.controller.AppRTCClient.RoomConnectionParameters;
import com.pine.rtc.controller.AppRTCClient.SignalingEvents;
import com.pine.rtc.controller.AppRTCClient.SignalingParameters;
import com.pine.rtc.controller.CpuMonitor;
import com.pine.rtc.controller.DirectRTCClient;
import com.pine.rtc.controller.PeerConnectionClient;
import com.pine.rtc.controller.PeerConnectionClient.DataChannelParameters;
import com.pine.rtc.controller.PeerConnectionClient.PeerConnectionParameters;
import com.pine.rtc.controller.WebSocketRTCClient;
import com.pine.rtc.exception.UnhandledExceptionHandler;
import com.pine.rtc.ui.fragment.CallFragment;
import com.pine.rtc.ui.fragment.HudFragment;
import com.pine.rtc.util.DialogUtil;

import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.EglBase;
import org.webrtc.FileVideoCapturer;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon.ScalingType;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Activity for peer connection call setup, call waiting
 * and call view.
 */
public class CallActivity extends Activity implements SignalingEvents,
        PeerConnectionClient.PeerConnectionEvents, CallFragment.OnCallEvents {
    public static final String EXTRA_ROOMID = "org.appspot.apprtc.ROOMID";
    public static final String EXTRA_URLPARAMETERS = "org.appspot.apprtc.URLPARAMETERS";
    public static final String EXTRA_LOOPBACK = "org.appspot.apprtc.LOOPBACK";
    public static final String EXTRA_VIDEO_CALL = "org.appspot.apprtc.VIDEO_CALL";
    public static final String EXTRA_SCREENCAPTURE = "org.appspot.apprtc.SCREENCAPTURE";
    public static final String EXTRA_CAMERA2 = "org.appspot.apprtc.CAMERA2";
    public static final String EXTRA_VIDEO_WIDTH = "org.appspot.apprtc.VIDEO_WIDTH";
    public static final String EXTRA_VIDEO_HEIGHT = "org.appspot.apprtc.VIDEO_HEIGHT";
    public static final String EXTRA_VIDEO_FPS = "org.appspot.apprtc.VIDEO_FPS";
    public static final String EXTRA_VIDEO_CAPTUREQUALITYSLIDER_ENABLED =
            "org.appsopt.apprtc.VIDEO_CAPTUREQUALITYSLIDER";
    public static final String EXTRA_VIDEO_BITRATE = "org.appspot.apprtc.VIDEO_BITRATE";
    public static final String EXTRA_VIDEOCODEC = "org.appspot.apprtc.VIDEOCODEC";
    public static final String EXTRA_HWCODEC_ENABLED = "org.appspot.apprtc.HWCODEC";
    public static final String EXTRA_CAPTURETOTEXTURE_ENABLED = "org.appspot.apprtc.CAPTURETOTEXTURE";
    public static final String EXTRA_FLEXFEC_ENABLED = "org.appspot.apprtc.FLEXFEC";
    public static final String EXTRA_AUDIO_BITRATE = "org.appspot.apprtc.AUDIO_BITRATE";
    public static final String EXTRA_AUDIOCODEC = "org.appspot.apprtc.AUDIOCODEC";
    public static final String EXTRA_NOAUDIOPROCESSING_ENABLED =
            "org.appspot.apprtc.NOAUDIOPROCESSING";
    public static final String EXTRA_AECDUMP_ENABLED = "org.appspot.apprtc.AECDUMP";
    public static final String EXTRA_OPENSLES_ENABLED = "org.appspot.apprtc.OPENSLES";
    public static final String EXTRA_DISABLE_BUILT_IN_AEC = "org.appspot.apprtc.DISABLE_BUILT_IN_AEC";
    public static final String EXTRA_DISABLE_BUILT_IN_AGC = "org.appspot.apprtc.DISABLE_BUILT_IN_AGC";
    public static final String EXTRA_DISABLE_BUILT_IN_NS = "org.appspot.apprtc.DISABLE_BUILT_IN_NS";
    public static final String EXTRA_ENABLE_LEVEL_CONTROL = "org.appspot.apprtc.ENABLE_LEVEL_CONTROL";
    public static final String EXTRA_DISABLE_WEBRTC_AGC_AND_HPF =
            "org.appspot.apprtc.DISABLE_WEBRTC_GAIN_CONTROL";
    public static final String EXTRA_DISPLAY_HUD = "org.appspot.apprtc.DISPLAY_HUD";
    public static final String EXTRA_TRACING = "org.appspot.apprtc.TRACING";
    public static final String EXTRA_CMDLINE = "org.appspot.apprtc.CMDLINE";
    public static final String EXTRA_RUNTIME = "org.appspot.apprtc.RUNTIME";
    public static final String EXTRA_VIDEO_FILE_AS_CAMERA = "org.appspot.apprtc.VIDEO_FILE_AS_CAMERA";
    public static final String EXTRA_SAVE_REMOTE_VIDEO_TO_FILE =
            "org.appspot.apprtc.SAVE_REMOTE_VIDEO_TO_FILE";
    public static final String EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_WIDTH =
            "org.appspot.apprtc.SAVE_REMOTE_VIDEO_TO_FILE_WIDTH";
    public static final String EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT =
            "org.appspot.apprtc.SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT";
    public static final String EXTRA_USE_VALUES_FROM_INTENT =
            "org.appspot.apprtc.USE_VALUES_FROM_INTENT";
    public static final String EXTRA_DATA_CHANNEL_ENABLED = "org.appspot.apprtc.DATA_CHANNEL_ENABLED";
    public static final String EXTRA_ORDERED = "org.appspot.apprtc.ORDERED";
    public static final String EXTRA_MAX_RETRANSMITS_MS = "org.appspot.apprtc.MAX_RETRANSMITS_MS";
    public static final String EXTRA_MAX_RETRANSMITS = "org.appspot.apprtc.MAX_RETRANSMITS";
    public static final String EXTRA_PROTOCOL = "org.appspot.apprtc.PROTOCOL";
    public static final String EXTRA_NEGOTIATED = "org.appspot.apprtc.NEGOTIATED";
    public static final String EXTRA_ID = "org.appspot.apprtc.ID";
    private static final String TAG = CallActivity.class.getSimpleName();
    private static final int SCREEN_SHOT_REQUEST_CODE = 1;
    private static final int CAPTURE_PERMISSION_REQUEST_CODE = 1;

    // List of mandatory application permissions.
    private static final String[] MANDATORY_PERMISSIONS = {"android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.RECORD_AUDIO", "android.permission.INTERNET"};

    // Peer connection statistics callback period in ms.
    private static final int STAT_CALLBACK_PERIOD = 1000;
    private static Intent mMediaProjectionPermissionResultData;
    private static int mMediaProjectionPermissionResultCode;
    private final ProxyRenderer mRemoteProxyRenderer = new ProxyRenderer();
    private final ProxyRenderer mLocalProxyRenderer = new ProxyRenderer();
    private final List<VideoRenderer.Callbacks> mRemoteRenderers =
            new ArrayList<VideoRenderer.Callbacks>();
    private PeerConnectionClient mPeerConnectionClient = null;
    private AppRTCClient mAppRtcClient;
    private AppRTCClient.SignalingParameters mSignalingParameters;
    private AppRTCAudioManager mAudioManager = null;
    private EglBase mRootEglBase;
    private SurfaceViewRenderer pipRendererView;
    private SurfaceViewRenderer fullscreenRendererView;
    private VideoFileRenderer mVideoFileRenderer;
    private Toast mLogToast;
    private boolean mCommandLineRun;
    private int mRunTimeMs;
    private boolean mActivityRunning;
    private RoomConnectionParameters mRoomConnectionParameters;
    private PeerConnectionParameters mPeerConnectionParameters;
    private boolean mIceConnected;
    private boolean mIsError;
    private boolean mCallControlFragmentVisible = true;
    private long mCallStartedTimeMs = 0;
    private boolean mMicEnabled = true;
    private boolean mScreenCaptureEnabled = false;
    // True if local view is in the fullscreen renderer.
    private boolean mIsSwappedFeeds;
    // Controls
    private CallFragment mCallFragment;
    private HudFragment mHudFragment;
    private CpuMonitor mCpuMonitor;

    private String mRoomId;
    private boolean mIsRecording;
    private String mSaveRemoteVideoToFile;
    private DateFormat mFileDataFormat;
    private MediaRecorder mMediaRecorder;
    private MediaProjectionScreenShot mMediaProjectionScreenShot;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {

        }
    };

    @TargetApi(19)
    private static int getSystemUiVisibility() {
        int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        return flags;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Thread.setDefaultUncaughtExceptionHandler(new UnhandledExceptionHandler(this));

        // Set window styles for fullscreen-window size. Needs to be done before
        // adding content.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(LayoutParams.FLAG_FULLSCREEN | LayoutParams.FLAG_KEEP_SCREEN_ON
                | LayoutParams.FLAG_DISMISS_KEYGUARD | LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(getSystemUiVisibility());
        setContentView(R.layout.activity_call);

        mIceConnected = false;
        mSignalingParameters = null;

        // Create UI controls.
        pipRendererView = (SurfaceViewRenderer) findViewById(R.id.pip_video_view);
        fullscreenRendererView = (SurfaceViewRenderer) findViewById(R.id.fullscreen_video_view);
        mCallFragment = new CallFragment();
        mHudFragment = new HudFragment();

        // Show/hide call control fragment on view click.
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleCallControlFragmentVisibility();
            }
        };

        // Swap feeds on pip view click.
        pipRendererView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setSwappedFeeds(!mIsSwappedFeeds);
            }
        });

        fullscreenRendererView.setOnClickListener(listener);
        mRemoteRenderers.add(mRemoteProxyRenderer);

        final Intent intent = getIntent();

        // Create video renderers.
        mRootEglBase = EglBase.create();
        pipRendererView.init(mRootEglBase.getEglBaseContext(), null);
        pipRendererView.setScalingType(ScalingType.SCALE_ASPECT_FIT);
        String saveRemoteVideoToFile = intent.getStringExtra(EXTRA_SAVE_REMOTE_VIDEO_TO_FILE);
        // When saveRemoteVideoToFile is set we save the video from the remote to a file.
        if (saveRemoteVideoToFile != null) {
            int videoOutWidth = intent.getIntExtra(EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_WIDTH, 0);
            int videoOutHeight = intent.getIntExtra(EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT, 0);
            try {
                mVideoFileRenderer = new VideoFileRenderer(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                        saveRemoteVideoToFile, videoOutWidth, videoOutHeight, mRootEglBase.getEglBaseContext());
                mRemoteRenderers.add(mVideoFileRenderer);
                mIsRecording = true;
            } catch (IOException e) {
                throw new RuntimeException(
                        "Failed to open video file for output: " + saveRemoteVideoToFile, e);
            }
        }

        fullscreenRendererView.init(mRootEglBase.getEglBaseContext(), null);
        fullscreenRendererView.setScalingType(ScalingType.SCALE_ASPECT_FILL);

        pipRendererView.setZOrderMediaOverlay(true);
        pipRendererView.setEnableHardwareScaler(true /* enabled */);
        fullscreenRendererView.setEnableHardwareScaler(true /* enabled */);
        // Start with local feed in fullscreen and swap it to the pip when the call is connected.
        setSwappedFeeds(true /* mIsSwappedFeeds */);

        // Check for mandatory permissions.
        for (String permission : MANDATORY_PERMISSIONS) {
            if (checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                logAndToast("Permission " + permission + " is not granted");
                setResult(RESULT_CANCELED);
                finish();
                return;
            }
        }

        Uri roomUri = intent.getData();
        if (roomUri == null) {
            logAndToast(getString(R.string.missing_url));
            Log.e(TAG, "Didn't get any URL in intent!");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        // Get Intent parameters.
        mRoomId = intent.getStringExtra(EXTRA_ROOMID);
        Log.d(TAG, "Room ID: " + mRoomId);
        if (mRoomId == null || mRoomId.length() == 0) {
            logAndToast(getString(R.string.missing_url));
            Log.e(TAG, "Incorrect room ID in intent!");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        boolean loopback = intent.getBooleanExtra(EXTRA_LOOPBACK, false);
        boolean tracing = intent.getBooleanExtra(EXTRA_TRACING, false);

        int videoWidth = intent.getIntExtra(EXTRA_VIDEO_WIDTH, 0);
        int videoHeight = intent.getIntExtra(EXTRA_VIDEO_HEIGHT, 0);

        mScreenCaptureEnabled = intent.getBooleanExtra(EXTRA_SCREENCAPTURE, false);
        // If capturing format is not specified for screencapture, use screen resolution.
        if (mScreenCaptureEnabled && videoWidth == 0 && videoHeight == 0) {
            DisplayMetrics displayMetrics = getDisplayMetrics();
            videoWidth = displayMetrics.widthPixels;
            videoHeight = displayMetrics.heightPixels;
        }
        DataChannelParameters dataChannelParameters = null;
        if (intent.getBooleanExtra(EXTRA_DATA_CHANNEL_ENABLED, false)) {
            dataChannelParameters = new DataChannelParameters(intent.getBooleanExtra(EXTRA_ORDERED, true),
                    intent.getIntExtra(EXTRA_MAX_RETRANSMITS_MS, -1),
                    intent.getIntExtra(EXTRA_MAX_RETRANSMITS, -1), intent.getStringExtra(EXTRA_PROTOCOL),
                    intent.getBooleanExtra(EXTRA_NEGOTIATED, false), intent.getIntExtra(EXTRA_ID, -1));
        }
        mPeerConnectionParameters =
                new PeerConnectionParameters(intent.getBooleanExtra(EXTRA_VIDEO_CALL, true), loopback,
                        tracing, videoWidth, videoHeight, intent.getIntExtra(EXTRA_VIDEO_FPS, 0),
                        intent.getIntExtra(EXTRA_VIDEO_BITRATE, 0), intent.getStringExtra(EXTRA_VIDEOCODEC),
                        intent.getBooleanExtra(EXTRA_HWCODEC_ENABLED, true),
                        intent.getBooleanExtra(EXTRA_FLEXFEC_ENABLED, false),
                        intent.getIntExtra(EXTRA_AUDIO_BITRATE, 0), intent.getStringExtra(EXTRA_AUDIOCODEC),
                        intent.getBooleanExtra(EXTRA_NOAUDIOPROCESSING_ENABLED, false),
                        intent.getBooleanExtra(EXTRA_AECDUMP_ENABLED, false),
                        intent.getBooleanExtra(EXTRA_OPENSLES_ENABLED, false),
                        intent.getBooleanExtra(EXTRA_DISABLE_BUILT_IN_AEC, false),
                        intent.getBooleanExtra(EXTRA_DISABLE_BUILT_IN_AGC, false),
                        intent.getBooleanExtra(EXTRA_DISABLE_BUILT_IN_NS, false),
                        intent.getBooleanExtra(EXTRA_ENABLE_LEVEL_CONTROL, false),
                        intent.getBooleanExtra(EXTRA_DISABLE_WEBRTC_AGC_AND_HPF, false), dataChannelParameters);
        mCommandLineRun = intent.getBooleanExtra(EXTRA_CMDLINE, false);
        mRunTimeMs = intent.getIntExtra(EXTRA_RUNTIME, 0);

        Log.d(TAG, "VIDEO_FILE: '" + intent.getStringExtra(EXTRA_VIDEO_FILE_AS_CAMERA) + "'");

        // Create connection client. Use DirectRTCClient if room name is an IP otherwise use the
        // standard WebSocketRTCClient.
        if (loopback || !DirectRTCClient.IP_PATTERN.matcher(mRoomId).matches()) {
            mAppRtcClient = new WebSocketRTCClient(this);
        } else {
            Log.i(TAG, "Using DirectRTCClient because room name looks like an IP.");
            mAppRtcClient = new DirectRTCClient(this);
        }
        // Create connection parameters.
        String urlParameters = intent.getStringExtra(EXTRA_URLPARAMETERS);
        mRoomConnectionParameters =
                new RoomConnectionParameters(getString(R.string.pref_room_server_url_default),
                        roomUri.toString(), mRoomId, loopback, urlParameters);

        // Create CPU monitor
        mCpuMonitor = new CpuMonitor(this);
        mHudFragment.setCpuMonitor(mCpuMonitor);

        // Send intent arguments to fragments.
        mCallFragment.setArguments(intent.getExtras());
        mHudFragment.setArguments(intent.getExtras());
        // Activate call and HUD fragments and start the call.
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.add(R.id.call_fragment_container, mCallFragment);
        ft.add(R.id.hud_fragment_container, mHudFragment);
        ft.commit();

        // For command line execution run connection for <runTimeMs> and exit.
        if (mCommandLineRun && mRunTimeMs > 0) {
            (new Handler()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    disconnect();
                }
            }, mRunTimeMs);
        }

        mPeerConnectionClient = PeerConnectionClient.getInstance();
        if (loopback) {
            PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
            options.networkIgnoreMask = 0;
            mPeerConnectionClient.setPeerConnectionFactoryOptions(options);
        }
        mPeerConnectionClient.createPeerConnectionFactory(
                getApplicationContext(), mPeerConnectionParameters, CallActivity.this);

        if (mScreenCaptureEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            startScreenCapture();
        } else {
            startCall();
        }
        mFileDataFormat = new SimpleDateFormat("yyyyMMddHHmmss");
    }

    @TargetApi(17)
    private DisplayMetrics getDisplayMetrics() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager windowManager =
                (WindowManager) getApplication().getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealMetrics(displayMetrics);
        return displayMetrics;
    }

    @TargetApi(21)
    private void startScreenCapture() {
        MediaProjectionManager mediaProjectionManager =
                (MediaProjectionManager) getApplication().getSystemService(
                        Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(), CAPTURE_PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == CAPTURE_PERMISSION_REQUEST_CODE) {
            mMediaProjectionPermissionResultCode = resultCode;
            mMediaProjectionPermissionResultData = data;
            startCall();
        } else if (requestCode == SCREEN_SHOT_REQUEST_CODE) {
            if (resultCode == -1 && data != null) {
                mMediaProjectionScreenShot =
                        new MediaProjectionScreenShot(CallActivity.this, data);
                mMediaProjectionScreenShot.setupScreenShot(new MediaProjectionScreenShot.OnShotListener() {
                    @Override
                    public void onFinish(Bitmap bitmap) {
                        DialogUtil.popShotScreenDialog(CallActivity.this, bitmap);
                    }

                    @Override
                    public void onSaveFinish(String filePath) {
                        String msg = "截图已经保存在 " + filePath;
                        Toast.makeText(CallActivity.this, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    }

    private boolean useCamera2() {
        return Camera2Enumerator.isSupported(this) && getIntent().getBooleanExtra(EXTRA_CAMERA2, true);
    }

    private boolean captureToTexture() {
        return getIntent().getBooleanExtra(EXTRA_CAPTURETOTEXTURE_ENABLED, false);
    }

    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        Logging.d(TAG, "Looking for front facing cameras.");
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating front facing camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Front facing camera not found, try something else
        Logging.d(TAG, "Looking for other cameras.");
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating other camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }

    @TargetApi(21)
    private VideoCapturer createScreenCapturer() {
        if (mMediaProjectionPermissionResultCode != Activity.RESULT_OK) {
            reportError("User didn't give permission to capture the screen.");
            return null;
        }
        return new ScreenCapturerAndroid(
                mMediaProjectionPermissionResultData, new MediaProjection.Callback() {
            @Override
            public void onStop() {
                reportError("User revoked permission to capture the screen.");
            }
        });
    }

    // Activity interfaces
    @Override
    public void onStop() {
        super.onStop();
        mActivityRunning = false;
        // Don't stop the video when using screencapture to allow user to show other apps to the remote
        // end.
        if (mPeerConnectionClient != null && !mScreenCaptureEnabled) {
            mPeerConnectionClient.stopVideoSource();
        }
        mCpuMonitor.pause();
    }

    @Override
    public void onStart() {
        super.onStart();
        mActivityRunning = true;
        // Video is not paused for screencapture. See onPause.
        if (mPeerConnectionClient != null && !mScreenCaptureEnabled) {
            mPeerConnectionClient.startVideoSource();
        }
        mCpuMonitor.resume();
    }

    @Override
    protected void onDestroy() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        if (mLogToast != null) {
            mLogToast.cancel();
        }
        disconnect();
        mActivityRunning = false;
        mRootEglBase.release();
        if (mMediaProjectionScreenShot != null) {
            mMediaProjectionScreenShot.release();
        }
        super.onDestroy();
    }

    // CallFragment.OnCallEvents interface implementation.
    @Override
    public void onCallHangUp() {
        disconnect();
    }

    @Override
    public void onCameraSwitch() {
        if (mPeerConnectionClient != null) {
            mPeerConnectionClient.switchCamera();
        }
    }

    @Override
    public void onVideoScalingSwitch(ScalingType scalingType) {
        fullscreenRendererView.setScalingType(scalingType);
    }

    @Override
    public void onScreenCapture() {
        if (mMediaProjectionScreenShot != null) {
            mMediaProjectionScreenShot.startScreenShot();
        }

//        SurfaceViewRenderer renderer = mRemoteProxyRenderer.getTarget();
//        if (renderer != null) {
//            EglRenderScreenShot.getInstance(renderer, null, new EglRenderScreenShot.Callback() {
//                @Override
//                public void onScreenShot(Bitmap bitmap) {
//                    DialogUtil.popShotScreenDialog(CallActivity.this, bitmap);
//                }
//
//                @Override
//                public void onScreenShotSave(String filePath) {
//                    String msg = "截图已经保存在 " + filePath;
//                    Toast.makeText(CallActivity.this, msg, Toast.LENGTH_SHORT).show();
//                }
//            }, mHandler).screenShot();
//        } else {
//            Toast.makeText(CallActivity.this, "无法截屏", Toast.LENGTH_LONG).show();
//        }
    }

    private boolean setupScreenCapture() {
        if (Build.VERSION.SDK_INT >= 21) {
            startActivityForResult(
                    ((MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE))
                            .createScreenCaptureIntent(), SCREEN_SHOT_REQUEST_CODE);
            return true;
        } else {
            Toast.makeText(CallActivity.this, "版本过低，截屏功能无法实现", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @Override
    public void onVideoRecord(final View recordButton) {
        if (mPeerConnectionClient.isRecorderPrepared()) {
            if (mVideoFileRenderer == null && !mIsRecording) {
                startRecorder(recordButton);
            } else {
                stopRecorder();
            }
        }
    }

//    @TargetApi(Build.VERSION_CODES.M)
//    private void startRecorder(final View recordButton) {
//        String fileDir = Environment.getExternalStorageDirectory().getPath() + "/rtc";
//        File file = new File(fileDir);
//        if (!file.exists()) {
//            file.mkdir();
//        }
//        String dataStr = mFileDataFormat.format(new Date());
//        final String saveRemoteVideoToFile = fileDir + "/room_" +
//                mRoomId + "-" + dataStr + ".mp4";
//        if (mMediaRecorder == null) {
//            mMediaRecorder = new MediaRecorder();
//            //设置音频的来源
//            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK);
//            //设置视频的来源
//            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
//            mMediaRecorder.setInputSurface(fullscreenRendererView.getHolder().getSurface());
//            //设置视频的输出格式
//            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
//            //设置视频中的声音和视频的编码格式
//            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
//            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.DEFAULT);
//        }
//        //设置保存的路径
//        mMediaRecorder.setOutputFile(saveRemoteVideoToFile);
//        //开始录制
//        try {
//            logAndToast("开始录制");
//            mMediaRecorder.prepare();
//            mMediaRecorder.start();
//        } catch (IOException e) {
//            e.printStackTrace();
//            logAndToast("录制出错");
//        }
//    }
//
//    private void stopRecorder() {
//        logAndToast("结束录制，文件已保存在" + mSaveRemoteVideoToFile);
//        //当结束录制之后，就将当前的资源都释放
//        mMediaRecorder.stop();
//        mMediaRecorder.reset();
//    }

    private void startRecorder(final View recordButton) {
        String fileDir = Environment.getExternalStorageDirectory().getPath() + "/rtc";
        File file = new File(fileDir);
        if (!file.exists()) {
            file.mkdir();
        }
        String dataStr = mFileDataFormat.format(new Date());
//        final String saveRemoteVideoToFile = fileDir + "/room_" +
//                mRoomId + "-" + dataStr + ".mp4";
        final String saveRemoteVideoToFile = fileDir + "/room.mp4";
        try {
            DisplayMetrics displayMetrics = getDisplayMetrics();
            mVideoFileRenderer = new VideoFileRenderer(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                    saveRemoteVideoToFile, displayMetrics.widthPixels, displayMetrics.heightPixels,
                    mRootEglBase.getEglBaseContext());
            mVideoFileRenderer.setRecorderListener(new VideoFileRenderer.IRecorderListener() {
                @Override
                public void onRecorderStart() {
                    mIsRecording = true;
                    mSaveRemoteVideoToFile = saveRemoteVideoToFile;
                    recordButton.setSelected(mIsRecording);
                    logAndToast("开始录制");
                }

                @Override
                public void onRecorderCompleting() {
                    logAndToast("正在结束录制，请等待 ……");
                }

                @Override
                public void onRecorderComplete() {
                    mIsRecording = false;
                    recordButton.setSelected(mIsRecording);
                    logAndToast("结束录制，文件已保存在" + mSaveRemoteVideoToFile);
                }

                @Override
                public void onRecorderError() {
                    mIsRecording = false;
                    recordButton.setSelected(mIsRecording);
                    logAndToast("录制出错");
                    File tmpFile = new File(saveRemoteVideoToFile);
                    if (tmpFile.exists()) {
                        tmpFile.delete();
                    }
                }
            });
            mPeerConnectionClient.addVideoRender(mVideoFileRenderer);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to open video file for output: " + saveRemoteVideoToFile, e);
        }
    }

    private void stopRecorder() {
        if (mVideoFileRenderer != null) {
            mPeerConnectionClient.removeVideoRender(mVideoFileRenderer);
            mVideoFileRenderer.release();
            mVideoFileRenderer = null;
        }
    }

    @Override
    public void onCaptureFormatChange(int width, int height, int frameRate) {
        if (mPeerConnectionClient != null) {
            mPeerConnectionClient.changeCaptureFormat(width, height, frameRate);
        }
    }

    @Override
    public boolean onToggleMic() {
        if (mPeerConnectionClient != null) {
            mMicEnabled = !mMicEnabled;
            mPeerConnectionClient.setAudioEnabled(mMicEnabled);
        }
        return mMicEnabled;
    }

    // Helper functions.
    private void toggleCallControlFragmentVisibility() {
        if (!mIceConnected || !mCallFragment.isAdded()) {
            return;
        }
        // Show/hide call control fragment
        mCallControlFragmentVisible = !mCallControlFragmentVisible;
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        if (mCallControlFragmentVisible) {
            ft.show(mCallFragment);
            ft.show(mHudFragment);
        } else {
            ft.hide(mCallFragment);
            ft.hide(mHudFragment);
        }
        ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        ft.commit();
    }

    private void startCall() {
        if (mAppRtcClient == null) {
            Log.e(TAG, "AppRTC client is not allocated for a call.");
            return;
        }
        mCallStartedTimeMs = System.currentTimeMillis();

        // Start room connection.
        logAndToast(getString(R.string.connecting_to, mRoomConnectionParameters.roomUrl));
        mAppRtcClient.connectToRoom(mRoomConnectionParameters);

        // Create and audio manager that will take care of audio routing,
        // audio modes, audio device enumeration etc.
        mAudioManager = AppRTCAudioManager.create(getApplicationContext());
        // Store existing audio settings and change audio mode to
        // MODE_IN_COMMUNICATION for best possible VoIP performance.
        Log.d(TAG, "Starting the audio manager...");
        mAudioManager.start(new AudioManagerEvents() {
            // This method will be called each time the number of available audio
            // devices has changed.
            @Override
            public void onAudioDeviceChanged(
                    AudioDevice audioDevice, Set<AudioDevice> availableAudioDevices) {
                onAudioManagerDevicesChanged(audioDevice, availableAudioDevices);
            }
        });
    }

    // Should be called from UI thread
    private void callConnected() {
        final long delta = System.currentTimeMillis() - mCallStartedTimeMs;
        Log.i(TAG, "Call connected: delay=" + delta + "ms");
        if (mPeerConnectionClient == null || mIsError) {
            Log.w(TAG, "Call is connected in closed or error state");
            return;
        }
        // Enable statistics callback.
        mPeerConnectionClient.enableStatsEvents(true, STAT_CALLBACK_PERIOD);
        setSwappedFeeds(false /* mIsSwappedFeeds */);
        mCallFragment.showScreenShotBtn();
        setupScreenCapture();
    }

    // This method is called when the audio manager reports audio device change,
    // e.g. from wired headset to speakerphone.
    private void onAudioManagerDevicesChanged(
            final AudioDevice device, final Set<AudioDevice> availableDevices) {
        Log.d(TAG, "onAudioManagerDevicesChanged: " + availableDevices + ", "
                + "selected: " + device);
        // TODO(henrika): add callback handler.
    }

    // Disconnect from remote resources, dispose of local resources, and exit.
    private void disconnect() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mActivityRunning = false;
                mRemoteProxyRenderer.setTarget(null);
                mLocalProxyRenderer.setTarget(null);
                if (mAppRtcClient != null) {
                    mAppRtcClient.disconnectFromRoom();
                    mAppRtcClient = null;
                }
                if (mPeerConnectionClient != null) {
                    mPeerConnectionClient.close();
                    mPeerConnectionClient = null;
                }
                if (pipRendererView != null) {
                    pipRendererView.release();
                    pipRendererView = null;
                }
                if (mVideoFileRenderer != null) {
                    mVideoFileRenderer.release();
                    mVideoFileRenderer = null;
                }
                if (fullscreenRendererView != null) {
                    fullscreenRendererView.release();
                    fullscreenRendererView = null;
                }
                if (mAudioManager != null) {
                    mAudioManager.stop();
                    mAudioManager = null;
                }
                if (mIceConnected && !mIsError) {
                    setResult(RESULT_OK);
                } else {
                    setResult(RESULT_CANCELED);
                }
                finish();
            }
        });
    }

    private void disconnectWithErrorMessage(final String errorMessage) {
        if (mCommandLineRun || !mActivityRunning) {
            Log.e(TAG, "Critical error: " + errorMessage);
            disconnect();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(getText(R.string.channel_error_title))
                    .setMessage(errorMessage)
                    .setCancelable(false)
                    .setNeutralButton(R.string.ok,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int id) {
                                    dialog.cancel();
                                    disconnect();
                                }
                            })
                    .create()
                    .show();
        }
    }

    // Log |msg| and Toast about it.
    private void logAndToast(String msg) {
        Log.d(TAG, msg);
        if (mLogToast != null) {
            mLogToast.cancel();
        }
        mLogToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        mLogToast.show();
    }

    private void reportError(final String description) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!mIsError) {
                    mIsError = true;
                    disconnectWithErrorMessage(description);
                }
            }
        });
    }

    private VideoCapturer createVideoCapturer() {
        VideoCapturer videoCapturer = null;
        String videoFileAsCamera = getIntent().getStringExtra(EXTRA_VIDEO_FILE_AS_CAMERA);
        if (videoFileAsCamera != null) {
            try {
                videoCapturer = new FileVideoCapturer(videoFileAsCamera);
            } catch (IOException e) {
                reportError("Failed to open video file for emulated camera");
                return null;
            }
        } else if (mScreenCaptureEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return createScreenCapturer();
        } else if (useCamera2()) {
            if (!captureToTexture()) {
                reportError(getString(R.string.camera2_texture_only_error));
                return null;
            }

            Logging.d(TAG, "Creating capturer using camera2 API.");
            videoCapturer = createCameraCapturer(new Camera2Enumerator(this));
        } else {
            Logging.d(TAG, "Creating capturer using camera1 API.");
            videoCapturer = createCameraCapturer(new Camera1Enumerator(captureToTexture()));
        }
        if (videoCapturer == null) {
            reportError("Failed to open camera");
            return null;
        }
        return videoCapturer;
    }

    private void setSwappedFeeds(boolean isSwappedFeeds) {
        Logging.d(TAG, "setSwappedFeeds: " + isSwappedFeeds);
        this.mIsSwappedFeeds = isSwappedFeeds;
        mLocalProxyRenderer.setTarget(isSwappedFeeds ? fullscreenRendererView : pipRendererView);
        mRemoteProxyRenderer.setTarget(isSwappedFeeds ? pipRendererView : fullscreenRendererView);
        fullscreenRendererView.setMirror(isSwappedFeeds);
        pipRendererView.setMirror(!isSwappedFeeds);
    }

    // -----Implementation of AppRTCClient.AppRTCSignalingEvents ---------------
    // All callbacks are invoked from websocket signaling looper thread and
    // are routed to UI thread.
    private void onConnectedToRoomInternal(final SignalingParameters params) {
        final long delta = System.currentTimeMillis() - mCallStartedTimeMs;

        mSignalingParameters = params;
        logAndToast("Creating peer connection, delay=" + delta + "ms");
        VideoCapturer videoCapturer = null;
        if (mPeerConnectionParameters.videoCallEnabled) {
            videoCapturer = createVideoCapturer();
        }
        mPeerConnectionClient.createPeerConnection(mRootEglBase.getEglBaseContext(), mLocalProxyRenderer,
                mRemoteRenderers, videoCapturer, mSignalingParameters);

        if (mSignalingParameters.initiator) {
            logAndToast("Creating OFFER...");
            // Create offer. Offer SDP will be sent to answering client in
            // PeerConnectionEvents.onLocalDescription event.
            mPeerConnectionClient.createOffer();
        } else {
            if (params.offerSdp != null) {
                mPeerConnectionClient.setRemoteDescription(params.offerSdp);
                logAndToast("Creating ANSWER...");
                // Create answer. Answer SDP will be sent to offering client in
                // PeerConnectionEvents.onLocalDescription event.
                mPeerConnectionClient.createAnswer();
            }
            if (params.iceCandidates != null) {
                // Add remote ICE candidates from room.
                for (IceCandidate iceCandidate : params.iceCandidates) {
                    mPeerConnectionClient.addRemoteIceCandidate(iceCandidate);
                }
            }
        }
    }

    @Override
    public void onConnectedToRoom(final SignalingParameters params) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                onConnectedToRoomInternal(params);
            }
        });
    }

    @Override
    public void onRemoteDescription(final SessionDescription sdp) {
        final long delta = System.currentTimeMillis() - mCallStartedTimeMs;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mPeerConnectionClient == null) {
                    Log.e(TAG, "Received remote SDP for non-initilized peer connection.");
                    return;
                }
                logAndToast("Received remote " + sdp.type + ", delay=" + delta + "ms");
                mPeerConnectionClient.setRemoteDescription(sdp);
                if (!mSignalingParameters.initiator) {
                    logAndToast("Creating ANSWER...");
                    // Create answer. Answer SDP will be sent to offering client in
                    // PeerConnectionEvents.onLocalDescription event.
                    mPeerConnectionClient.createAnswer();
                }
            }
        });
    }

    @Override
    public void onRemoteIceCandidate(final IceCandidate candidate) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mPeerConnectionClient == null) {
                    Log.e(TAG, "Received ICE candidate for a non-initialized peer connection.");
                    return;
                }
                mPeerConnectionClient.addRemoteIceCandidate(candidate);
            }
        });
    }

    @Override
    public void onRemoteIceCandidatesRemoved(final IceCandidate[] candidates) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mPeerConnectionClient == null) {
                    Log.e(TAG, "Received ICE candidate removals for a non-initialized peer connection.");
                    return;
                }
                mPeerConnectionClient.removeRemoteIceCandidates(candidates);
            }
        });
    }

    @Override
    public void onChannelClose() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logAndToast("Remote end hung up; dropping PeerConnection");
                disconnect();
            }
        });
    }

    @Override
    public void onChannelError(final String description) {
        reportError(description);
    }

    // -----Implementation of PeerConnectionClient.PeerConnectionEvents.---------
    // Send local peer connection SDP and ICE candidates to remote party.
    // All callbacks are invoked from peer connection client looper thread and
    // are routed to UI thread.
    @Override
    public void onLocalDescription(final SessionDescription sdp) {
        final long delta = System.currentTimeMillis() - mCallStartedTimeMs;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mAppRtcClient != null) {
                    logAndToast("Sending " + sdp.type + ", delay=" + delta + "ms");
                    if (mSignalingParameters.initiator) {
                        mAppRtcClient.sendOfferSdp(sdp);
                    } else {
                        mAppRtcClient.sendAnswerSdp(sdp);
                    }
                }
                if (mPeerConnectionParameters.videoMaxBitrate > 0) {
                    Log.d(TAG, "Set video maximum bitrate: " + mPeerConnectionParameters.videoMaxBitrate);
                    mPeerConnectionClient.setVideoMaxBitrate(mPeerConnectionParameters.videoMaxBitrate);
                }
            }
        });
    }

    @Override
    public void onIceCandidate(final IceCandidate candidate) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mAppRtcClient != null) {
                    mAppRtcClient.sendLocalIceCandidate(candidate);
                }
            }
        });
    }

    @Override
    public void onIceCandidatesRemoved(final IceCandidate[] candidates) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mAppRtcClient != null) {
                    mAppRtcClient.sendLocalIceCandidateRemovals(candidates);
                }
            }
        });
    }

    @Override
    public void onIceConnected() {
        final long delta = System.currentTimeMillis() - mCallStartedTimeMs;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logAndToast("ICE connected, delay=" + delta + "ms");
                mIceConnected = true;
                callConnected();
            }
        });
    }

    @Override
    public void onIceDisconnected() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logAndToast("ICE disconnected");
                mIceConnected = false;
                disconnect();
            }
        });
    }

    @Override
    public void onPeerConnectionClosed() {
    }

    @Override
    public void onPeerConnectionStatsReady(final StatsReport[] reports) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!mIsError && mIceConnected) {
                    mHudFragment.updateEncoderStatistics(reports);
                }
            }
        });
    }

    @Override
    public void onPeerConnectionError(final String description) {
        reportError(description);
    }

    private class ProxyRenderer implements VideoRenderer.Callbacks {
        private VideoRenderer.Callbacks target;

        synchronized public void renderFrame(VideoRenderer.I420Frame frame) {
            if (target == null) {
                Logging.d(TAG, "Dropping frame in proxy because target is null.");
                VideoRenderer.renderFrameDone(frame);
                return;
            }

            target.renderFrame(frame);
        }

        synchronized public SurfaceViewRenderer getTarget() {
            if (target instanceof SurfaceViewRenderer) {
                return (SurfaceViewRenderer) target;
            }
            return null;
        }

        synchronized public void setTarget(VideoRenderer.Callbacks target) {
            this.target = target;
        }
    }
}
