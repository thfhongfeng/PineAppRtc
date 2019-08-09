/*
 *  Copyright 2015 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package com.pine.rtc.ui.fragment;

import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.pine.rtc.R;
import com.pine.rtc.org.component.CallActivity;

/**
 * Fragment for call control.
 */
public class MyCallFragment extends Fragment {
    private View controlView;
    private TextView contactTv;
    private TextView stateTv;
    private ImageView cameraSwitchButton;
    private TextView captureScreenButton;
    private TextView recordVideoButton;
    private ImageView disconnectButton;
    private TextView toggleMuteButton;
    private TextView speakerButton;
    private OnCallEvents mCallEvents;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        controlView = inflater.inflate(R.layout.fragment_my_call, container, false);

        // Create UI controls.
        contactTv = (TextView) controlView.findViewById(R.id.contact_name_tv);
        stateTv = (TextView) controlView.findViewById(R.id.state_tv);
        cameraSwitchButton = (ImageView) controlView.findViewById(R.id.button_call_switch_camera);
        recordVideoButton = (TextView) controlView.findViewById(R.id.button_record_video);
        captureScreenButton = (TextView) controlView.findViewById(R.id.button_call_capture_screen);
        disconnectButton = (ImageView) controlView.findViewById(R.id.button_call_disconnect);
        toggleMuteButton = (TextView) controlView.findViewById(R.id.button_call_toggle_mic);
        speakerButton = (TextView) controlView.findViewById(R.id.button_call_toggle_speaker);

        cameraSwitchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean isFront = mCallEvents.onCameraSwitch();
                cameraSwitchButton.setSelected(isFront);
            }
        });

        recordVideoButton.setAlpha(0.3f);
        captureScreenButton.setAlpha(0.3f);

        // Add buttons click events.
        disconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mCallEvents.onCallHangUp();
            }
        });

        toggleMuteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean enabled = mCallEvents.onToggleMic();
                toggleMuteButton.setSelected(!enabled);
            }
        });

        speakerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean enabled = mCallEvents.onToggleSpeaker();
                speakerButton.setSelected(enabled);
            }
        });

        return controlView;
    }

    @Override
    public void onStart() {
        super.onStart();
        Bundle args = getArguments();
        if (args != null) {
            String contactName = args.getString(CallActivity.EXTRA_ROOMID);
            contactTv.setText(getString(R.string.call_room, contactName));
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mCallEvents = (OnCallEvents) activity;
    }

    public void enableSupportButtons(boolean isSupportCaptureScreen, boolean isSupportMediaRecord) {
        if (isSupportMediaRecord) {
            recordVideoButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mCallEvents.onVideoRecord(view);
                }
            });
            recordVideoButton.setVisibility(View.VISIBLE);
            recordVideoButton.setAlpha(1.0f);
        } else {
            recordVideoButton.setVisibility(View.GONE);
            recordVideoButton.setAlpha(0.3f);
        }
        if (isSupportCaptureScreen) {
            captureScreenButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mCallEvents.onScreenCapture();
                }
            });
            captureScreenButton.setAlpha(1.0f);
        }
    }

    public void onRecorderChange(boolean isRecording) {
        if (recordVideoButton == null) {
            return;
        }
        recordVideoButton.setSelected(isRecording);
        recordVideoButton.setText(isRecording ? "" : getString(R.string.call_record));
    }

    public void onRecorderTimeTick(String timeTick) {
        if (recordVideoButton == null) {
            return;
        }
        recordVideoButton.setText(timeTick);
    }

    public void onSpeakerChange(boolean isOn) {
        if (speakerButton == null) {
            return;
        }
        speakerButton.setSelected(isOn);
    }

    public void onMuteChange(boolean isMicEnable) {
        if (toggleMuteButton == null) {
            return;
        }
        toggleMuteButton.setSelected(!isMicEnable);
    }

    public void setRtcState(String stateMsg) {
        if (stateTv == null) {
            return;
        }
        stateTv.setText(stateMsg);
    }

    /**
     * Call control interface for container activity.
     */
    public interface OnCallEvents {
        void onCallHangUp();

        boolean onCameraSwitch();

        void onScreenCapture();

        void onVideoRecord(View recordButton);

        boolean onToggleMic();

        boolean onToggleSpeaker();
    }
}
