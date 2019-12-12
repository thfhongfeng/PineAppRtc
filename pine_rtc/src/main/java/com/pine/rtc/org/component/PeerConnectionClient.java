/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package com.pine.rtc.org.component;

import android.content.Context;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnection.IceConnectionState;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.StatsObserver;
import org.webrtc.StatsReport;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.voiceengine.WebRtcAudioManager;
import org.webrtc.voiceengine.WebRtcAudioRecord;
import org.webrtc.voiceengine.WebRtcAudioRecord.AudioRecordStartErrorCode;
import org.webrtc.voiceengine.WebRtcAudioRecord.WebRtcAudioRecordErrorCallback;
import org.webrtc.voiceengine.WebRtcAudioTrack;
import org.webrtc.voiceengine.WebRtcAudioTrack.WebRtcAudioTrackErrorCallback;
import org.webrtc.voiceengine.WebRtcAudioUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Peer connection client implementation.
 * <p>
 * <p>All public methods are routed to local looper thread.
 * All PeerConnectionEvents callbacks are invoked from the same looper thread.
 * This class is a singleton.
 */
public class PeerConnectionClient {
    public static final String VIDEO_TRACK_ID = "ARDAMSv0";
    public static final String AUDIO_TRACK_ID = "ARDAMSa0";
    public static final String VIDEO_TRACK_TYPE = "video";
    private static final String TAG = "PCRTCClient";
    private static final String VIDEO_CODEC_VP8 = "VP8";
    private static final String VIDEO_CODEC_VP9 = "VP9";
    private static final String VIDEO_CODEC_H264 = "H264";
    private static final String VIDEO_CODEC_H264_BASELINE = "H264 Baseline";
    private static final String VIDEO_CODEC_H264_HIGH = "H264 High";
    private static final String AUDIO_CODEC_OPUS = "opus";
    private static final String AUDIO_CODEC_ISAC = "ISAC";
    private static final String VIDEO_CODEC_PARAM_START_BITRATE = "x-google-start-bitrate";
    private static final String VIDEO_FLEXFEC_FIELDTRIAL =
            "WebRTC-FlexFEC-03-Advertised/Enabled/WebRTC-FlexFEC-03/Enabled/";
    private static final String VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL = "WebRTC-IntelVP8/Enabled/";
    private static final String VIDEO_H264_HIGH_PROFILE_FIELDTRIAL =
            "WebRTC-H264HighProfile/Enabled/";
    private static final String DISABLE_WEBRTC_AGC_FIELDTRIAL =
            "WebRTC-Audio-MinimizeResamplingOnMobile/Enabled/";
    private static final String AUDIO_CODEC_PARAM_BITRATE = "maxaveragebitrate";
    private static final String AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation";
    private static final String AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl";
    private static final String AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter";
    private static final String AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression";
    private static final String AUDIO_LEVEL_CONTROL_CONSTRAINT = "levelControl";
    private static final String DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement";
    private static final int HD_VIDEO_WIDTH = 1280;
    private static final int HD_VIDEO_HEIGHT = 720;
    private static final int BPS_IN_KBPS = 1000;

    private static final PeerConnectionClient mInstance = new PeerConnectionClient();
    private final PCObserver mPcObserver = new PCObserver();
    private final SDPObserver mSdpObserver = new SDPObserver();
    private final ExecutorService mExecutor;
    PeerConnectionFactory.Options mOptions = null;
    private PeerConnectionFactory mFactory;
    private PeerConnection mPeerConnection;
    private AudioSource mAudioSource;
    private VideoSource mVideoSource;
    private boolean mVideoCallEnabled;
    private boolean mPreferIsac;
    private String mPreferredVideoCodec;
    private boolean mVideoCapturerStopped;
    private boolean mIsError;
    private Timer mStatsTimer;
    private VideoRenderer.Callbacks mLocalRender;
    private List<VideoRenderer.Callbacks> mRemoteRenders;
    private AppRTCClient.SignalingParameters mSignalingParameters;
    private MediaConstraints mPcConstraints;
    private int mVideoWidth;
    private int mVideoHeight;
    private int mVideoFps;
    private MediaConstraints mAudioConstraints;
    private ParcelFileDescriptor mAecDumpFileDescriptor;
    private MediaConstraints mSdpMediaConstraints;
    private PeerConnectionParameters mPeerConnectionParameters;
    // Queued remote ICE candidates are consumed only after both local and
    // remote descriptions are set. Similarly local ICE candidates are sent to
    // remote peer after both local and remote description are set.
    private LinkedList<IceCandidate> mQueuedRemoteCandidates;
    private PeerConnectionEvents mEvents;
    private boolean mIsInitiator;
    private SessionDescription mLocalSdp; // either offer or answer SDP
    private MediaStream mMediaStream;
    private VideoCapturer mVideoCapturer;
    // enableVideo is set to true if video should be rendered and sent.
    private boolean mRenderVideo;
    private VideoTrack mLocalVideoTrack;
    private VideoTrack mRemoteVideoTrack;
    private RtpSender mLocalVideoSender;
    // enableAudio is set to true if audio should be sent.
    private boolean mEnableAudio;
    private AudioTrack mLocalAudioTrack;
    private DataChannel mDataChannel;
    private boolean mDataChannelEnabled;

    private PeerConnectionClient() {
        // Executor thread is started once in private ctor and is used for all
        // peer connection API calls to ensure new peer connection factory is
        // created on the same thread as previously destroyed factory.
        mExecutor = Executors.newSingleThreadExecutor();
    }

    public static PeerConnectionClient getInstance() {
        return mInstance;
    }

    private static String setStartBitrate(
            String codec, boolean isVideoCodec, String sdpDescription, int bitrateKbps) {
        String[] lines = sdpDescription.split("\r\n");
        int rtpmapLineIndex = -1;
        boolean sdpFormatUpdated = false;
        String codecRtpMap = null;
        // Search for codec rtpmap in format
        // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
        String regex = "^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$";
        Pattern codecPattern = Pattern.compile(regex);
        for (int i = 0; i < lines.length; i++) {
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                codecRtpMap = codecMatcher.group(1);
                rtpmapLineIndex = i;
                break;
            }
        }
        if (codecRtpMap == null) {
            Log.w(TAG, "No rtpmap for " + codec + " codec");
            return sdpDescription;
        }
        Log.d(TAG, "Found " + codec + " rtpmap " + codecRtpMap + " at " + lines[rtpmapLineIndex]);

        // Check if a=fmtp string already exist in remote SDP for this codec and
        // update it with new bitrate parameter.
        regex = "^a=fmtp:" + codecRtpMap + " \\w+=\\d+.*[\r]?$";
        codecPattern = Pattern.compile(regex);
        for (int i = 0; i < lines.length; i++) {
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                Log.d(TAG, "Found " + codec + " " + lines[i]);
                if (isVideoCodec) {
                    lines[i] += "; " + VIDEO_CODEC_PARAM_START_BITRATE + "=" + bitrateKbps;
                } else {
                    lines[i] += "; " + AUDIO_CODEC_PARAM_BITRATE + "=" + (bitrateKbps * 1000);
                }
                Log.d(TAG, "Update remote SDP line: " + lines[i]);
                sdpFormatUpdated = true;
                break;
            }
        }

        StringBuilder newSdpDescription = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            newSdpDescription.append(lines[i]).append("\r\n");
            // Append new a=fmtp line if no such line exist for a codec.
            if (!sdpFormatUpdated && i == rtpmapLineIndex) {
                String bitrateSet;
                if (isVideoCodec) {
                    bitrateSet =
                            "a=fmtp:" + codecRtpMap + " " + VIDEO_CODEC_PARAM_START_BITRATE + "=" + bitrateKbps;
                } else {
                    bitrateSet = "a=fmtp:" + codecRtpMap + " " + AUDIO_CODEC_PARAM_BITRATE + "="
                            + (bitrateKbps * 1000);
                }
                Log.d(TAG, "Add remote SDP line: " + bitrateSet);
                newSdpDescription.append(bitrateSet).append("\r\n");
            }
        }
        return newSdpDescription.toString();
    }

    /**
     * Returns the line number containing "m=audio|video", or -1 if no such line exists.
     */
    private static int findMediaDescriptionLine(boolean isAudio, String[] sdpLines) {
        final String mediaDescription = isAudio ? "m=audio " : "m=video ";
        for (int i = 0; i < sdpLines.length; ++i) {
            if (sdpLines[i].startsWith(mediaDescription)) {
                return i;
            }
        }
        return -1;
    }

    private static String joinString(
            Iterable<? extends CharSequence> s, String delimiter, boolean delimiterAtEnd) {
        Iterator<? extends CharSequence> iter = s.iterator();
        if (!iter.hasNext()) {
            return "";
        }
        StringBuilder buffer = new StringBuilder(iter.next());
        while (iter.hasNext()) {
            buffer.append(delimiter).append(iter.next());
        }
        if (delimiterAtEnd) {
            buffer.append(delimiter);
        }
        return buffer.toString();
    }

    private static String movePayloadTypesToFront(List<String> preferredPayloadTypes, String mLine) {
        // The format of the media description line should be: m=<media> <port> <proto> <fmt> ...
        final List<String> origLineParts = Arrays.asList(mLine.split(" "));
        if (origLineParts.size() <= 3) {
            Log.e(TAG, "Wrong SDP media description format: " + mLine);
            return null;
        }
        final List<String> header = origLineParts.subList(0, 3);
        final List<String> unpreferredPayloadTypes =
                new ArrayList<String>(origLineParts.subList(3, origLineParts.size()));
        unpreferredPayloadTypes.removeAll(preferredPayloadTypes);
        // Reconstruct the line with |preferredPayloadTypes| moved to the beginning of the payload
        // types.
        final List<String> newLineParts = new ArrayList<String>();
        newLineParts.addAll(header);
        newLineParts.addAll(preferredPayloadTypes);
        newLineParts.addAll(unpreferredPayloadTypes);
        return joinString(newLineParts, " ", false /* delimiterAtEnd */);
    }

    private static String preferCodec(String sdpDescription, String codec, boolean isAudio) {
        final String[] lines = sdpDescription.split("\r\n");
        final int mLineIndex = findMediaDescriptionLine(isAudio, lines);
        if (mLineIndex == -1) {
            Log.w(TAG, "No mediaDescription line, so can't prefer " + codec);
            return sdpDescription;
        }
        // A list with all the payload types with name |codec|. The payload types are integers in the
        // range 96-127, but they are stored as strings here.
        final List<String> codecPayloadTypes = new ArrayList<String>();
        // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
        final Pattern codecPattern = Pattern.compile("^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$");
        for (int i = 0; i < lines.length; ++i) {
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                codecPayloadTypes.add(codecMatcher.group(1));
            }
        }
        if (codecPayloadTypes.isEmpty()) {
            Log.w(TAG, "No payload types with name " + codec);
            return sdpDescription;
        }

        final String newMLine = movePayloadTypesToFront(codecPayloadTypes, lines[mLineIndex]);
        if (newMLine == null) {
            return sdpDescription;
        }
        Log.d(TAG, "Change media description from: " + lines[mLineIndex] + " to " + newMLine);
        lines[mLineIndex] = newMLine;
        return joinString(Arrays.asList(lines), "\r\n", true /* delimiterAtEnd */);
    }

    public void setPeerConnectionFactoryOptions(PeerConnectionFactory.Options options) {
        this.mOptions = options;
    }

    public void createPeerConnectionFactory(final Context context,
                                            final PeerConnectionParameters peerConnectionParameters, final PeerConnectionEvents events) {
        this.mPeerConnectionParameters = peerConnectionParameters;
        this.mEvents = events;
        mVideoCallEnabled = peerConnectionParameters.videoCallEnabled;
        mDataChannelEnabled = peerConnectionParameters.dataChannelParameters != null;
        // Reset variables to initial states.
        mFactory = null;
        mPeerConnection = null;
        mPreferIsac = false;
        mVideoCapturerStopped = false;
        mIsError = false;
        mQueuedRemoteCandidates = null;
        mLocalSdp = null; // either offer or answer SDP
        mMediaStream = null;
        mVideoCapturer = null;
        mRenderVideo = true;
        mLocalVideoTrack = null;
        mRemoteVideoTrack = null;
        mLocalVideoSender = null;
        mEnableAudio = true;
        mLocalAudioTrack = null;
        mStatsTimer = new Timer();

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                createPeerConnectionFactoryInternal(context);
            }
        });
    }

    public void createPeerConnection(final EglBase.Context renderEGLContext,
                                     final VideoRenderer.Callbacks localRender, final VideoRenderer.Callbacks remoteRender,
                                     final VideoCapturer videoCapturer, final AppRTCClient.SignalingParameters signalingParameters) {
        createPeerConnection(renderEGLContext, localRender, Collections.singletonList(remoteRender),
                videoCapturer, signalingParameters);
    }

    public void createPeerConnection(final EglBase.Context renderEGLContext,
                                     final VideoRenderer.Callbacks localRender, final List<VideoRenderer.Callbacks> remoteRenders,
                                     final VideoCapturer videoCapturer, final AppRTCClient.SignalingParameters signalingParameters) {
        if (mPeerConnectionParameters == null) {
            Log.e(TAG, "Creating peer connection without initializing factory.");
            return;
        }
        this.mLocalRender = localRender;
        this.mRemoteRenders = remoteRenders;
        this.mVideoCapturer = videoCapturer;
        this.mSignalingParameters = signalingParameters;
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    createMediaConstraintsInternal();
                    createPeerConnectionInternal(renderEGLContext);
                } catch (Exception e) {
                    reportError("Failed to create peer connection: " + e.getMessage());
                    throw e;
                }
            }
        });
    }

    public void close() {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                closeInternal();
            }
        });
    }

    public boolean isVideoCallEnabled() {
        return mVideoCallEnabled;
    }

    private void createPeerConnectionFactoryInternal(Context context) {
        PeerConnectionFactory.initializeInternalTracer();
        if (mPeerConnectionParameters.tracing) {
            PeerConnectionFactory.startInternalTracingCapture(
                    Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator
                            + "webrtc-trace.txt");
        }
        Log.d(TAG,
                "Create peer connection factory. Use video: " + mPeerConnectionParameters.videoCallEnabled);
        mIsError = false;

        // Initialize field trials.
        String fieldTrials = "";
        if (mPeerConnectionParameters.videoFlexfecEnabled) {
            fieldTrials += VIDEO_FLEXFEC_FIELDTRIAL;
            Log.d(TAG, "Enable FlexFEC field trial.");
        }
        fieldTrials += VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL;
        if (mPeerConnectionParameters.disableWebRtcAGCAndHPF) {
            fieldTrials += DISABLE_WEBRTC_AGC_FIELDTRIAL;
            Log.d(TAG, "Disable WebRTC AGC field trial.");
        }

        // Check preferred video codec.
        mPreferredVideoCodec = VIDEO_CODEC_VP8;
        if (mVideoCallEnabled && mPeerConnectionParameters.videoCodec != null) {
            switch (mPeerConnectionParameters.videoCodec) {
                case VIDEO_CODEC_VP8:
                    mPreferredVideoCodec = VIDEO_CODEC_VP8;
                    break;
                case VIDEO_CODEC_VP9:
                    mPreferredVideoCodec = VIDEO_CODEC_VP9;
                    break;
                case VIDEO_CODEC_H264_BASELINE:
                    mPreferredVideoCodec = VIDEO_CODEC_H264;
                    break;
                case VIDEO_CODEC_H264_HIGH:
                    // TODO(magjed): Strip High from SDP when selecting Baseline instead of using field trial.
                    fieldTrials += VIDEO_H264_HIGH_PROFILE_FIELDTRIAL;
                    mPreferredVideoCodec = VIDEO_CODEC_H264;
                    break;
                default:
                    mPreferredVideoCodec = VIDEO_CODEC_VP8;
            }
        }
        Log.d(TAG, "Preferred video codec: " + mPreferredVideoCodec);
        PeerConnectionFactory.initializeFieldTrials(fieldTrials);
        Log.d(TAG, "Field trials: " + fieldTrials);

        // Check if ISAC is used by default.
        mPreferIsac = mPeerConnectionParameters.audioCodec != null
                && mPeerConnectionParameters.audioCodec.equals(AUDIO_CODEC_ISAC);

        // Enable/disable OpenSL ES playback.
        if (!mPeerConnectionParameters.useOpenSLES) {
            Log.d(TAG, "Disable OpenSL ES audio even if device supports it");
            WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true /* enable */);
        } else {
            Log.d(TAG, "Allow OpenSL ES audio if device supports it");
            WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(false);
        }

        if (mPeerConnectionParameters.disableBuiltInAEC) {
            Log.d(TAG, "Disable built-in AEC even if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true);
        } else {
            Log.d(TAG, "Enable built-in AEC if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(false);
        }

        if (mPeerConnectionParameters.disableBuiltInAGC) {
            Log.d(TAG, "Disable built-in AGC even if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(true);
        } else {
            Log.d(TAG, "Enable built-in AGC if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(false);
        }

        if (mPeerConnectionParameters.disableBuiltInNS) {
            Log.d(TAG, "Disable built-in NS even if device supports it");
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(true);
        } else {
            Log.d(TAG, "Enable built-in NS if device supports it");
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(false);
        }

        // Set audio record error callbacks.
        WebRtcAudioRecord.setErrorCallback(new WebRtcAudioRecordErrorCallback() {
            @Override
            public void onWebRtcAudioRecordInitError(String errorMessage) {
                Log.e(TAG, "onWebRtcAudioRecordInitError: " + errorMessage);
                reportError(errorMessage);
            }

            @Override
            public void onWebRtcAudioRecordStartError(
                    AudioRecordStartErrorCode errorCode, String errorMessage) {
                Log.e(TAG, "onWebRtcAudioRecordStartError: " + errorCode + ". " + errorMessage);
                reportError(errorMessage);
            }

            @Override
            public void onWebRtcAudioRecordError(String errorMessage) {
                Log.e(TAG, "onWebRtcAudioRecordError: " + errorMessage);
                reportError(errorMessage);
            }
        });

        WebRtcAudioTrack.setErrorCallback(new WebRtcAudioTrackErrorCallback() {
            @Override
            public void onWebRtcAudioTrackInitError(String errorMessage) {
                reportError(errorMessage);
            }

            @Override
            public void onWebRtcAudioTrackStartError(String errorMessage) {
                reportError(errorMessage);
            }

            @Override
            public void onWebRtcAudioTrackError(String errorMessage) {
                reportError(errorMessage);
            }
        });

        // Create peer connection factory.
        PeerConnectionFactory.initializeAndroidGlobals(
                context, mPeerConnectionParameters.videoCodecHwAcceleration);
        if (mOptions != null) {
            Log.d(TAG, "Factory networkIgnoreMask option: " + mOptions.networkIgnoreMask);
        }
        mFactory = new PeerConnectionFactory(mOptions);
        Log.d(TAG, "Peer connection factory created.");
    }

    private void createMediaConstraintsInternal() {
        // Create peer connection constraints.
        mPcConstraints = new MediaConstraints();
        // Enable DTLS for normal calls and disable for loopback calls.
        if (mPeerConnectionParameters.loopback) {
            mPcConstraints.optional.add(
                    new MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "false"));
        } else {
            mPcConstraints.optional.add(
                    new MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "true"));
        }

        // Check if there is a camera on device and disable video call if not.
        if (mVideoCapturer == null) {
            Log.w(TAG, "No camera on device. Switch to audio only call.");
            mVideoCallEnabled = false;
        }
        // Create video constraints if video call is enabled.
        if (mVideoCallEnabled) {
            mVideoWidth = mPeerConnectionParameters.videoWidth;
            mVideoHeight = mPeerConnectionParameters.videoHeight;
            mVideoFps = mPeerConnectionParameters.videoFps;

            // If video resolution is not specified, default to HD.
            if (mVideoWidth == 0 || mVideoHeight == 0) {
                mVideoWidth = HD_VIDEO_WIDTH;
                mVideoHeight = HD_VIDEO_HEIGHT;
            }

            // If fps is not specified, default to 30.
            if (mVideoFps == 0) {
                mVideoFps = 30;
            }
            Logging.d(TAG, "Capturing format: " + mVideoWidth + "x" + mVideoHeight + "@" + mVideoFps);
        }

        // Create audio constraints.
        mAudioConstraints = new MediaConstraints();
        mAudioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "true"));
        mAudioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "true"));
        // added for audio performance measurements
        if (mPeerConnectionParameters.noAudioProcessing) {
            Log.d(TAG, "Disabling audio processing");
            mAudioConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "false"));
            mAudioConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false"));
            mAudioConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false"));
            mAudioConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "false"));
        }
        if (mPeerConnectionParameters.enableLevelControl) {
            Log.d(TAG, "Enabling level control.");
            mAudioConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair(AUDIO_LEVEL_CONTROL_CONSTRAINT, "true"));
        }
        // Create SDP constraints.
        mSdpMediaConstraints = new MediaConstraints();
        mSdpMediaConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        if (mVideoCallEnabled || mPeerConnectionParameters.loopback) {
            mSdpMediaConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        } else {
            mSdpMediaConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        }
    }

    private void createPeerConnectionInternal(EglBase.Context renderEGLContext) {
        if (mFactory == null || mIsError) {
            Log.e(TAG, "Peerconnection factory is not created");
            return;
        }
        Log.d(TAG, "Create peer connection.");

        Log.d(TAG, "PCConstraints: " + mPcConstraints.toString());
        mQueuedRemoteCandidates = new LinkedList<IceCandidate>();

        if (mVideoCallEnabled) {
            Log.d(TAG, "EGLContext: " + renderEGLContext);
            mFactory.setVideoHwAccelerationOptions(renderEGLContext, renderEGLContext);
        }

        PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(mSignalingParameters.iceServers);
        // TCP candidates are only useful when connecting to a server that supports
        // ICE-TCP.
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        // Use ECDSA encryption.
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;

        mPeerConnection = mFactory.createPeerConnection(rtcConfig, mPcConstraints, mPcObserver);

        if (mDataChannelEnabled) {
            DataChannel.Init init = new DataChannel.Init();
            init.ordered = mPeerConnectionParameters.dataChannelParameters.ordered;
            init.negotiated = mPeerConnectionParameters.dataChannelParameters.negotiated;
            init.maxRetransmits = mPeerConnectionParameters.dataChannelParameters.maxRetransmits;
            init.maxRetransmitTimeMs = mPeerConnectionParameters.dataChannelParameters.maxRetransmitTimeMs;
            init.id = mPeerConnectionParameters.dataChannelParameters.id;
            init.protocol = mPeerConnectionParameters.dataChannelParameters.protocol;
            mDataChannel = mPeerConnection.createDataChannel("ApprtcDemo data", init);
        }
        mIsInitiator = false;

        // Set default WebRTC tracing and INFO libjingle logging.
        // NOTE: this _must_ happen while |factory| is alive!
        Logging.enableTracing("logcat:", EnumSet.of(Logging.TraceLevel.TRACE_DEFAULT));
        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO);

        mMediaStream = mFactory.createLocalMediaStream("ARDAMS");
        if (mVideoCallEnabled) {
            mMediaStream.addTrack(createVideoTrack(mVideoCapturer));
        }

        mMediaStream.addTrack(createAudioTrack());
        mPeerConnection.addStream(mMediaStream);
        if (mVideoCallEnabled) {
            findVideoSender();
        }

        if (mPeerConnectionParameters.aecDump) {
            try {
                mAecDumpFileDescriptor =
                        ParcelFileDescriptor.open(new File(Environment.getExternalStorageDirectory().getPath()
                                        + File.separator + "Download/audio.aecdump"),
                                ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE
                                        | ParcelFileDescriptor.MODE_TRUNCATE);
                mFactory.startAecDump(mAecDumpFileDescriptor.getFd(), -1);
            } catch (IOException e) {
                Log.e(TAG, "Can not open aecdump file", e);
            }
        }

        Log.d(TAG, "Peer connection created.");
    }

    private void closeInternal() {
        if (mFactory != null && mPeerConnectionParameters.aecDump) {
            mFactory.stopAecDump();
        }
        Log.d(TAG, "Closing peer connection.");
        mStatsTimer.cancel();
        if (mDataChannel != null) {
            mDataChannel.dispose();
            mDataChannel = null;
        }
        if (mPeerConnection != null) {
            mPeerConnection.dispose();
            mPeerConnection = null;
        }
        Log.d(TAG, "Closing audio source.");
        if (mAudioSource != null) {
            mAudioSource.dispose();
            mAudioSource = null;
        }
        Log.d(TAG, "Stopping capture.");
        if (mVideoCapturer != null) {
            try {
                mVideoCapturer.stopCapture();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            mVideoCapturerStopped = true;
            mVideoCapturer.dispose();
            mVideoCapturer = null;
        }
        Log.d(TAG, "Closing video source.");
        if (mVideoSource != null) {
            mVideoSource.dispose();
            mVideoSource = null;
        }
        mLocalRender = null;
        mRemoteRenders = null;
        Log.d(TAG, "Closing peer connection factory.");
        if (mFactory != null) {
            mFactory.dispose();
            mFactory = null;
        }
        mOptions = null;
        Log.d(TAG, "Closing peer connection done.");
        mEvents.onPeerConnectionClosed();
        PeerConnectionFactory.stopInternalTracingCapture();
        PeerConnectionFactory.shutdownInternalTracer();
        mEvents = null;
    }

    public boolean isHDVideo() {
        if (!mVideoCallEnabled) {
            return false;
        }

        return mVideoWidth * mVideoHeight >= 1280 * 720;
    }

    private void getStats() {
        if (mPeerConnection == null || mIsError) {
            return;
        }
        boolean success = mPeerConnection.getStats(new StatsObserver() {
            @Override
            public void onComplete(final StatsReport[] reports) {
                mEvents.onPeerConnectionStatsReady(reports);
            }
        }, null);
        if (!success) {
            Log.e(TAG, "getStats() returns false!");
        }
    }

    public void enableStatsEvents(boolean enable, int periodMs) {
        if (enable) {
            try {
                mStatsTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        mExecutor.execute(new Runnable() {
                            @Override
                            public void run() {
                                getStats();
                            }
                        });
                    }
                }, 0, periodMs);
            } catch (Exception e) {
                Log.e(TAG, "Can not schedule statistics timer", e);
            }
        } else {
            mStatsTimer.cancel();
        }
    }

    public void setAudioEnabled(final boolean enable) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mEnableAudio = enable;
                if (mLocalAudioTrack != null) {
                    mLocalAudioTrack.setEnabled(mEnableAudio);
                }
            }
        });
    }

    public void setVideoEnabled(final boolean enable) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mRenderVideo = enable;
                if (mLocalVideoTrack != null) {
                    mLocalVideoTrack.setEnabled(mRenderVideo);
                }
                if (mRemoteVideoTrack != null) {
                    mRemoteVideoTrack.setEnabled(mRenderVideo);
                }
            }
        });
    }

    public void createOffer() {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (mPeerConnection != null && !mIsError) {
                    Log.d(TAG, "PC Create OFFER");
                    mIsInitiator = true;
                    mPeerConnection.createOffer(mSdpObserver, mSdpMediaConstraints);
                }
            }
        });
    }

    public void createAnswer() {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (mPeerConnection != null && !mIsError) {
                    Log.d(TAG, "PC create ANSWER");
                    mIsInitiator = false;
                    mPeerConnection.createAnswer(mSdpObserver, mSdpMediaConstraints);
                }
            }
        });
    }

    public void addRemoteIceCandidate(final IceCandidate candidate) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (mPeerConnection != null && !mIsError) {
                    if (mQueuedRemoteCandidates != null) {
                        mQueuedRemoteCandidates.add(candidate);
                    } else {
                        mPeerConnection.addIceCandidate(candidate);
                    }
                }
            }
        });
    }

    public void removeRemoteIceCandidates(final IceCandidate[] candidates) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (mPeerConnection == null || mIsError) {
                    return;
                }
                // Drain the queued remote candidates if there is any so that
                // they are processed in the proper order.
                drainCandidates();
                mPeerConnection.removeIceCandidates(candidates);
            }
        });
    }

    public void setRemoteDescription(final SessionDescription sdp) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (mPeerConnection == null || mIsError) {
                    return;
                }
                String sdpDescription = sdp.description;
                if (mPreferIsac) {
                    sdpDescription = preferCodec(sdpDescription, AUDIO_CODEC_ISAC, true);
                }
                if (mVideoCallEnabled) {
                    sdpDescription = preferCodec(sdpDescription, mPreferredVideoCodec, false);
                }
                if (mPeerConnectionParameters.audioStartBitrate > 0) {
                    sdpDescription = setStartBitrate(
                            AUDIO_CODEC_OPUS, false, sdpDescription, mPeerConnectionParameters.audioStartBitrate);
                }
                Log.d(TAG, "Set remote SDP.");
                SessionDescription sdpRemote = new SessionDescription(sdp.type, sdpDescription);
                mPeerConnection.setRemoteDescription(mSdpObserver, sdpRemote);
            }
        });
    }

    public void stopVideoSource() {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (mVideoCapturer != null && !mVideoCapturerStopped) {
                    Log.d(TAG, "Stop video source.");
                    try {
                        mVideoCapturer.stopCapture();
                    } catch (InterruptedException e) {
                    }
                    mVideoCapturerStopped = true;
                }
            }
        });
    }

    public void startVideoSource() {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (mVideoCapturer != null && mVideoCapturerStopped) {
                    Log.d(TAG, "Restart video source.");
                    mVideoCapturer.startCapture(mVideoWidth, mVideoHeight, mVideoFps);
                    mVideoCapturerStopped = false;
                }
            }
        });
    }

    public void setVideoMaxBitrate(final Integer maxBitrateKbps) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (mPeerConnection == null || mLocalVideoSender == null || mIsError) {
                    return;
                }
                Log.d(TAG, "Requested max video bitrate: " + maxBitrateKbps);
                if (mLocalVideoSender == null) {
                    Log.w(TAG, "Sender is not ready.");
                    return;
                }

                RtpParameters parameters = mLocalVideoSender.getParameters();
                if (parameters.encodings.size() == 0) {
                    Log.w(TAG, "RtpParameters are not ready.");
                    return;
                }

                for (RtpParameters.Encoding encoding : parameters.encodings) {
                    // Null value means no limit.
                    encoding.maxBitrateBps = maxBitrateKbps == null ? null : maxBitrateKbps * BPS_IN_KBPS;
                }
                if (!mLocalVideoSender.setParameters(parameters)) {
                    Log.e(TAG, "RtpSender.setParameters failed.");
                }
                Log.d(TAG, "Configured max video bitrate to: " + maxBitrateKbps);
            }
        });
    }

    private void reportError(final String errorMessage) {
        Log.e(TAG, "Peerconnection error: " + errorMessage);
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (!mIsError) {
                    mEvents.onPeerConnectionError(errorMessage);
                    mIsError = true;
                }
            }
        });
    }

    private AudioTrack createAudioTrack() {
        mAudioSource = mFactory.createAudioSource(mAudioConstraints);
        mLocalAudioTrack = mFactory.createAudioTrack(AUDIO_TRACK_ID, mAudioSource);
        mLocalAudioTrack.setEnabled(mEnableAudio);
        return mLocalAudioTrack;
    }

    private VideoTrack createVideoTrack(VideoCapturer capturer) {
        mVideoSource = mFactory.createVideoSource(capturer);
        capturer.startCapture(mVideoWidth, mVideoHeight, mVideoFps);

        mLocalVideoTrack = mFactory.createVideoTrack(VIDEO_TRACK_ID, mVideoSource);
        mLocalVideoTrack.setEnabled(mRenderVideo);
        mLocalVideoTrack.addRenderer(new VideoRenderer(mLocalRender));
        return mLocalVideoTrack;
    }

    private void findVideoSender() {
        for (RtpSender sender : mPeerConnection.getSenders()) {
            if (sender.track() != null) {
                String trackType = sender.track().kind();
                if (trackType.equals(VIDEO_TRACK_TYPE)) {
                    Log.d(TAG, "Found video sender.");
                    mLocalVideoSender = sender;
                }
            }
        }
    }

    private void drainCandidates() {
        if (mQueuedRemoteCandidates != null) {
            Log.d(TAG, "Add " + mQueuedRemoteCandidates.size() + " remote candidates");
            for (IceCandidate candidate : mQueuedRemoteCandidates) {
                mPeerConnection.addIceCandidate(candidate);
            }
            mQueuedRemoteCandidates = null;
        }
    }

    private void switchCameraInternal() {
        if (mVideoCapturer instanceof CameraVideoCapturer) {
            if (!mVideoCallEnabled || mIsError || mVideoCapturer == null) {
                Log.e(TAG, "Failed to switch camera. Video: " + mVideoCallEnabled + ". Error : " + mIsError);
                return; // No video is sent or only one camera is available or error happened.
            }
            Log.d(TAG, "Switch camera");
            CameraVideoCapturer cameraVideoCapturer = (CameraVideoCapturer) mVideoCapturer;
            cameraVideoCapturer.switchCamera(null);
        } else {
            Log.d(TAG, "Will not switch camera, video caputurer is not a camera");
        }
    }

    public void switchCamera() {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                switchCameraInternal();
            }
        });
    }

    public void changeCaptureFormat(final int width, final int height, final int framerate) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                changeCaptureFormatInternal(width, height, framerate);
            }
        });
    }

    private void changeCaptureFormatInternal(int width, int height, int framerate) {
        if (!mVideoCallEnabled || mIsError || mVideoCapturer == null) {
            Log.e(TAG,
                    "Failed to change capture format. Video: " + mVideoCallEnabled + ". Error : " + mIsError);
            return;
        }
        Log.d(TAG, "changeCaptureFormat: " + width + "x" + height + "@" + framerate);
        mVideoSource.adaptOutputFormat(width, height, framerate);
    }

    public boolean isRecorderPrepared() {
        return mRemoteVideoTrack != null;
    }

    public void addVideoRender(final VideoRenderer.Callbacks render) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (mPeerConnection != null && !mIsError) {
                    if (mRemoteVideoTrack != null) {
                        mRemoteVideoTrack.addRenderer(new VideoRenderer(render));
//                        mRemoteRenders.add(render);
                    }
                }
            }
        });
    }

    public void removeVideoRender(final VideoRenderer.Callbacks render) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (mPeerConnection == null || mIsError) {
                    return;
                }
                if (mRemoteVideoTrack != null) {
                    mRemoteVideoTrack.removeRenderer(new VideoRenderer(render));
//                    mRemoteRenders.remove(render);
                }
            }
        });
    }

    /**
     * Peer connection events.
     */
    public interface PeerConnectionEvents {
        /**
         * Callback fired once local SDP is created and set.
         */
        void onLocalDescription(final SessionDescription sdp);

        /**
         * Callback fired once local Ice candidate is generated.
         */
        void onIceCandidate(final IceCandidate candidate);

        /**
         * Callback fired once local ICE candidates are removed.
         */
        void onIceCandidatesRemoved(final IceCandidate[] candidates);

        /**
         * Callback fired once connection is established (IceConnectionState is
         * CONNECTED).
         */
        void onIceConnected();

        /**
         * Callback fired once connection is closed (IceConnectionState is
         * DISCONNECTED).
         */
        void onIceDisconnected();

        /**
         * Callback fired once peer connection is closed.
         */
        void onPeerConnectionClosed();

        /**
         * Callback fired once peer connection statistics is ready.
         */
        void onPeerConnectionStatsReady(final StatsReport[] reports);

        /**
         * Callback fired once peer connection error happened.
         */
        void onPeerConnectionError(final String description);
    }

    /**
     * Peer connection parameters.
     */
    public static class DataChannelParameters {
        public final boolean ordered;
        public final int maxRetransmitTimeMs;
        public final int maxRetransmits;
        public final String protocol;
        public final boolean negotiated;
        public final int id;

        public DataChannelParameters(boolean ordered, int maxRetransmitTimeMs, int maxRetransmits,
                                     String protocol, boolean negotiated, int id) {
            this.ordered = ordered;
            this.maxRetransmitTimeMs = maxRetransmitTimeMs;
            this.maxRetransmits = maxRetransmits;
            this.protocol = protocol;
            this.negotiated = negotiated;
            this.id = id;
        }
    }

    /**
     * Peer connection parameters.
     */
    public static class PeerConnectionParameters {
        public final boolean videoCallEnabled;
        public final boolean loopback;
        public final boolean tracing;
        public final int videoWidth;
        public final int videoHeight;
        public final int videoFps;
        public final int videoMaxBitrate;
        public final String videoCodec;
        public final boolean videoCodecHwAcceleration;
        public final boolean videoFlexfecEnabled;
        public final int audioStartBitrate;
        public final String audioCodec;
        public final boolean noAudioProcessing;
        public final boolean aecDump;
        public final boolean useOpenSLES;
        public final boolean disableBuiltInAEC;
        public final boolean disableBuiltInAGC;
        public final boolean disableBuiltInNS;
        public final boolean enableLevelControl;
        public final boolean disableWebRtcAGCAndHPF;
        private final DataChannelParameters dataChannelParameters;

        public PeerConnectionParameters(boolean videoCallEnabled, boolean loopback, boolean tracing,
                                        int videoWidth, int videoHeight, int videoFps, int videoMaxBitrate, String videoCodec,
                                        boolean videoCodecHwAcceleration, boolean videoFlexfecEnabled, int audioStartBitrate,
                                        String audioCodec, boolean noAudioProcessing, boolean aecDump, boolean useOpenSLES,
                                        boolean disableBuiltInAEC, boolean disableBuiltInAGC, boolean disableBuiltInNS,
                                        boolean enableLevelControl, boolean disableWebRtcAGCAndHPF) {
            this(videoCallEnabled, loopback, tracing, videoWidth, videoHeight, videoFps, videoMaxBitrate,
                    videoCodec, videoCodecHwAcceleration, videoFlexfecEnabled, audioStartBitrate, audioCodec,
                    noAudioProcessing, aecDump, useOpenSLES, disableBuiltInAEC, disableBuiltInAGC,
                    disableBuiltInNS, enableLevelControl, disableWebRtcAGCAndHPF, null);
        }

        public PeerConnectionParameters(boolean videoCallEnabled, boolean loopback, boolean tracing,
                                        int videoWidth, int videoHeight, int videoFps, int videoMaxBitrate, String videoCodec,
                                        boolean videoCodecHwAcceleration, boolean videoFlexfecEnabled, int audioStartBitrate,
                                        String audioCodec, boolean noAudioProcessing, boolean aecDump, boolean useOpenSLES,
                                        boolean disableBuiltInAEC, boolean disableBuiltInAGC, boolean disableBuiltInNS,
                                        boolean enableLevelControl, boolean disableWebRtcAGCAndHPF,
                                        DataChannelParameters dataChannelParameters) {
            this.videoCallEnabled = videoCallEnabled;
            this.loopback = loopback;
            this.tracing = tracing;
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
            this.videoFps = videoFps;
            this.videoMaxBitrate = videoMaxBitrate;
            this.videoCodec = videoCodec;
            this.videoFlexfecEnabled = videoFlexfecEnabled;
            this.videoCodecHwAcceleration = videoCodecHwAcceleration;
            this.audioStartBitrate = audioStartBitrate;
            this.audioCodec = audioCodec;
            this.noAudioProcessing = noAudioProcessing;
            this.aecDump = aecDump;
            this.useOpenSLES = useOpenSLES;
            this.disableBuiltInAEC = disableBuiltInAEC;
            this.disableBuiltInAGC = disableBuiltInAGC;
            this.disableBuiltInNS = disableBuiltInNS;
            this.enableLevelControl = enableLevelControl;
            this.disableWebRtcAGCAndHPF = disableWebRtcAGCAndHPF;
            this.dataChannelParameters = dataChannelParameters;
        }
    }

    // Implementation detail: observe ICE & stream changes and react accordingly.
    private class PCObserver implements PeerConnection.Observer {
        @Override
        public void onIceCandidate(final IceCandidate candidate) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mEvents.onIceCandidate(candidate);
                }
            });
        }

        @Override
        public void onIceCandidatesRemoved(final IceCandidate[] candidates) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mEvents.onIceCandidatesRemoved(candidates);
                }
            });
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState newState) {
            Log.d(TAG, "SignalingState: " + newState);
        }

        @Override
        public void onIceConnectionChange(final PeerConnection.IceConnectionState newState) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "IceConnectionState: " + newState);
                    if (newState == IceConnectionState.CONNECTED) {
                        mEvents.onIceConnected();
                    } else if (newState == IceConnectionState.DISCONNECTED) {
                        mEvents.onIceDisconnected();
                    } else if (newState == IceConnectionState.FAILED) {
                        reportError("ICE connection failed.");
                    }
                }
            });
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
            Log.d(TAG, "IceGatheringState: " + newState);
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
            Log.d(TAG, "IceConnectionReceiving changed to " + receiving);
        }

        @Override
        public void onAddStream(final MediaStream stream) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (mPeerConnection == null || mIsError) {
                        return;
                    }
                    if (stream.audioTracks.size() > 1 || stream.videoTracks.size() > 1) {
                        reportError("Weird-looking stream: " + stream);
                        return;
                    }
                    if (stream.videoTracks.size() == 1) {
                        mRemoteVideoTrack = stream.videoTracks.get(0);
                        mRemoteVideoTrack.setEnabled(mRenderVideo);
                        for (VideoRenderer.Callbacks remoteRender : mRemoteRenders) {
                            mRemoteVideoTrack.addRenderer(new VideoRenderer(remoteRender));
                        }
                    }
                }
            });
        }

        @Override
        public void onRemoveStream(final MediaStream stream) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mRemoteVideoTrack = null;
                }
            });
        }

        @Override
        public void onDataChannel(final DataChannel dc) {
            Log.d(TAG, "New Data channel " + dc.label());

            if (!mDataChannelEnabled)
                return;

            dc.registerObserver(new DataChannel.Observer() {
                public void onBufferedAmountChange(long previousAmount) {
                    Log.d(TAG, "Data channel buffered amount changed: " + dc.label() + ": " + dc.state());
                }

                @Override
                public void onStateChange() {
                    Log.d(TAG, "Data channel state changed: " + dc.label() + ": " + dc.state());
                }

                @Override
                public void onMessage(final DataChannel.Buffer buffer) {
                    if (buffer.binary) {
                        Log.d(TAG, "Received binary msg over " + dc);
                        return;
                    }
                    ByteBuffer data = buffer.data;
                    final byte[] bytes = new byte[data.capacity()];
                    data.get(bytes);
                    String strData = new String(bytes);
                    Log.d(TAG, "Got msg: " + strData + " over " + dc);
                }
            });
        }

        @Override
        public void onRenegotiationNeeded() {
            // No need to do anything; AppRTC follows a pre-agreed-upon
            // signaling/negotiation protocol.
        }

        @Override
        public void onAddTrack(final RtpReceiver receiver, final MediaStream[] mediaStreams) {
        }
    }

    // Implementation detail: handle offer creation/signaling and answer setting,
    // as well as adding remote ICE candidates once the answer SDP is set.
    private class SDPObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(final SessionDescription origSdp) {
            if (mLocalSdp != null) {
                reportError("Multiple SDP create.");
                return;
            }
            String sdpDescription = origSdp.description;
            if (mPreferIsac) {
                sdpDescription = preferCodec(sdpDescription, AUDIO_CODEC_ISAC, true);
            }
            if (mVideoCallEnabled) {
                sdpDescription = preferCodec(sdpDescription, mPreferredVideoCodec, false);
            }
            final SessionDescription sdp = new SessionDescription(origSdp.type, sdpDescription);
            mLocalSdp = sdp;
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (mPeerConnection != null && !mIsError) {
                        Log.d(TAG, "Set local SDP from " + sdp.type);
                        mPeerConnection.setLocalDescription(mSdpObserver, sdp);
                    }
                }
            });
        }

        @Override
        public void onSetSuccess() {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (mPeerConnection == null || mIsError) {
                        return;
                    }
                    if (mIsInitiator) {
                        // For offering peer connection we first create offer and set
                        // local SDP, then after receiving answer set remote SDP.
                        if (mPeerConnection.getRemoteDescription() == null) {
                            // We've just set our local SDP so time to send it.
                            Log.d(TAG, "Local SDP set succesfully");
                            mEvents.onLocalDescription(mLocalSdp);
                        } else {
                            // We've just set remote description, so drain remote
                            // and send local ICE candidates.
                            Log.d(TAG, "Remote SDP set succesfully");
                            drainCandidates();
                        }
                    } else {
                        // For answering peer connection we set remote SDP and then
                        // create answer and set local SDP.
                        if (mPeerConnection.getLocalDescription() != null) {
                            // We've just set our local SDP so time to send it, drain
                            // remote and send local ICE candidates.
                            Log.d(TAG, "Local SDP set succesfully");
                            mEvents.onLocalDescription(mLocalSdp);
                            drainCandidates();
                        } else {
                            // We've just set remote SDP - do nothing for now -
                            // answer will be created soon.
                            Log.d(TAG, "Remote SDP set succesfully");
                        }
                    }
                }
            });
        }

        @Override
        public void onCreateFailure(final String error) {
            reportError("createSDP error: " + error);
        }

        @Override
        public void onSetFailure(final String error) {
            reportError("setSDP error: " + error);
        }
    }
}
