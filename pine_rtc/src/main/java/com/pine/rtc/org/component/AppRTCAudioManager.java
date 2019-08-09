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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;

import com.pine.rtc.R;

import org.webrtc.ThreadUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * AppRTCAudioManager manages all audio related parts of the AppRTC demo.
 */
public class AppRTCAudioManager {
    private static final String TAG = "AppRTCAudioManager";

    private static final String SPEAKERPHONE_AUTO = "auto";
    private static final String SPEAKERPHONE_TRUE = "true";
    private static final String SPEAKERPHONE_FALSE = "false";
    private final Context mApprtcContext;
    // Contains speakerphone setting: auto, true or false
    private final String mUseSpeakerphone;
    // Handles all tasks related to Bluetooth headset devices.
    private final AppRTCBluetoothManager mBluetoothManager;
    private AudioManager mAudioManager;
    private AudioManagerEvents mAudioManagerEvents;
    private AudioManagerState mAmState;
    private int mSavedAudioMode = AudioManager.MODE_INVALID;
    private boolean mSavedIsSpeakerPhoneOn = false;
    private boolean mSavedIsMicrophoneMute = false;
    private boolean mHasWiredHeadset = false;
    // Default audio device; speaker phone for video calls or earpiece for audio
    // only calls.
    private AudioDevice mDefaultAudioDevice;
    // Contains the currently selected audio device.
    // This device is changed automatically using a certain scheme where e.g.
    // a wired headset "wins" over speaker phone. It is also possible for a
    // user to explicitly select a device (and overrid any predefined scheme).
    // See |userSelectedAudioDevice| for details.
    private AudioDevice mSelectedAudioDevice;
    // Contains the user-selected audio device which overrides the predefined
    // selection scheme.
    // TODO(henrika): always set to AudioDevice.NONE today. Add support for
    // explicit selection based on choice by userSelectedAudioDevice.
    private AudioDevice mUserSelectedAudioDevice;
    // Proximity sensor object. It measures the proximity of an object in cm
    // relative to the view screen of a device and can therefore be used to
    // assist device switching (close to ear <=> use headset earpiece if
    // available, far from ear <=> use speaker phone).
    private AppRTCProximitySensor mProximitySensor = null;
    // Contains a list of available audio devices. A Set collection is used to
    // avoid duplicate elements.
    private Set<AudioDevice> mAudioDevices = new HashSet<AudioDevice>();
    // Broadcast receiver for wired headset intent broadcasts.
    private BroadcastReceiver mWiredHeadsetReceiver;
    // Callback method for changes in audio focus.
    private AudioManager.OnAudioFocusChangeListener mAudioFocusChangeListener;

    private AppRTCAudioManager(Context context) {
        Log.d(TAG, "ctor");
        ThreadUtils.checkIsOnMainThread();
        mApprtcContext = context;
        mAudioManager = ((AudioManager) context.getSystemService(Context.AUDIO_SERVICE));
        mBluetoothManager = AppRTCBluetoothManager.create(context, this);
        mWiredHeadsetReceiver = new WiredHeadsetReceiver();
        mAmState = AudioManagerState.UNINITIALIZED;

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        mUseSpeakerphone = sharedPreferences.getString(context.getString(R.string.pref_speakerphone_key),
                context.getString(R.string.pref_speakerphone_default));
        Log.d(TAG, "useSpeakerphone: " + mUseSpeakerphone);
        if (mUseSpeakerphone.equals(SPEAKERPHONE_FALSE)) {
            mDefaultAudioDevice = AudioDevice.EARPIECE;
        } else {
            mDefaultAudioDevice = AudioDevice.SPEAKER_PHONE;
        }

        // Create and initialize the proximity sensor.
        // Tablet devices (e.g. Nexus 7) does not support proximity sensors.
        // Note that, the sensor will not be active until start() has been called.
        mProximitySensor = AppRTCProximitySensor.create(context, new Runnable() {
            // This method will be called each time a state change is detected.
            // Example: user holds his hand over the device (closer than ~5 cm),
            // or removes his hand from the device.
            public void run() {
                onProximitySensorChangedState();
            }
        });

        Log.d(TAG, "defaultAudioDevice: " + mDefaultAudioDevice);
        AppRTCUtils.logDeviceInfo(TAG);
    }

    /**
     * Construction.
     */
    public static AppRTCAudioManager create(Context context) {
        return new AppRTCAudioManager(context);
    }

    /**
     * This method is called when the proximity sensor reports a state change,
     * e.g. from "NEAR to FAR" or from "FAR to NEAR".
     */
    private void onProximitySensorChangedState() {
        if (!mUseSpeakerphone.equals(SPEAKERPHONE_AUTO)) {
            return;
        }

        // The proximity sensor should only be activated when there are exactly two
        // available audio devices.
        if (mAudioDevices.size() == 2 && mAudioDevices.contains(AppRTCAudioManager.AudioDevice.EARPIECE)
                && mAudioDevices.contains(AppRTCAudioManager.AudioDevice.SPEAKER_PHONE)) {
            if (mProximitySensor.sensorReportsNearState()) {
                // Sensor reports that a "handset is being held up to a person's ear",
                // or "something is covering the light sensor".
                setAudioDeviceInternal(AppRTCAudioManager.AudioDevice.EARPIECE);
            } else {
                // Sensor reports that a "handset is removed from a person's ear", or
                // "the light sensor is no longer covered".
                setAudioDeviceInternal(AppRTCAudioManager.AudioDevice.SPEAKER_PHONE);
            }
        }
    }

    public void start(AudioManagerEvents audioManagerEvents) {
        Log.d(TAG, "start");
        ThreadUtils.checkIsOnMainThread();
        if (mAmState == AudioManagerState.RUNNING) {
            Log.e(TAG, "AudioManager is already active");
            return;
        }
        // TODO(henrika): perhaps call new method called preInitAudio() here if UNINITIALIZED.

        Log.d(TAG, "AudioManager starts...");
        this.mAudioManagerEvents = audioManagerEvents;
        mAmState = AudioManagerState.RUNNING;

        // Store current audio state so we can restore it when stop() is called.
        mSavedAudioMode = mAudioManager.getMode();
        mSavedIsSpeakerPhoneOn = mAudioManager.isSpeakerphoneOn();
        mSavedIsMicrophoneMute = mAudioManager.isMicrophoneMute();
        mHasWiredHeadset = hasWiredHeadset();

        // Create an AudioManager.OnAudioFocusChangeListener instance.
        mAudioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
            // Called on the listener to notify if the audio focus for this listener has been changed.
            // The |focusChange| value indicates whether the focus was gained, whether the focus was lost,
            // and whether that loss is transient, or whether the new focus holder will hold it for an
            // unknown amount of time.
            // TODO(henrika): possibly extend support of handling audio-focus changes. Only contains
            // logging for now.
            @Override
            public void onAudioFocusChange(int focusChange) {
                String typeOfChange = "AUDIOFOCUS_NOT_DEFINED";
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_GAIN:
                        typeOfChange = "AUDIOFOCUS_GAIN";
                        break;
                    case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:
                        typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT";
                        break;
                    case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE:
                        typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE";
                        break;
                    case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
                        typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK";
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS:
                        typeOfChange = "AUDIOFOCUS_LOSS";
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        typeOfChange = "AUDIOFOCUS_LOSS_TRANSIENT";
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        typeOfChange = "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK";
                        break;
                    default:
                        typeOfChange = "AUDIOFOCUS_INVALID";
                        break;
                }
                Log.d(TAG, "onAudioFocusChange: " + typeOfChange);
            }
        };

        // Request audio playout focus (without ducking) and install listener for changes in focus.
        int result = mAudioManager.requestAudioFocus(mAudioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d(TAG, "Audio focus request granted for VOICE_CALL streams");
        } else {
            Log.e(TAG, "Audio focus request failed");
        }

        // Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
        // required to be in this mode when playout and/or recording starts for
        // best possible VoIP performance.
        mAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        // Always disable microphone mute during a WebRTC call.
        setMicrophoneMute(false);

        // Set initial device states.
        mUserSelectedAudioDevice = AudioDevice.NONE;
        mSelectedAudioDevice = AudioDevice.NONE;
        mAudioDevices.clear();

        // Initialize and start Bluetooth if a BT device is available or initiate
        // detection of new (enabled) BT devices.
        mBluetoothManager.start();

        // Do initial selection of audio device. This setting can later be changed
        // either by adding/removing a BT or wired headset or by covering/uncovering
        // the proximity sensor.
        updateAudioDeviceState();

        // Register receiver for broadcast intents related to adding/removing a
        // wired headset.
        registerReceiver(mWiredHeadsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
        Log.d(TAG, "AudioManager started");
    }

    public void stop() {
        Log.d(TAG, "stop");
        ThreadUtils.checkIsOnMainThread();
        if (mAmState != AudioManagerState.RUNNING) {
            Log.e(TAG, "Trying to stop AudioManager in incorrect state: " + mAmState);
            return;
        }
        mAmState = AudioManagerState.UNINITIALIZED;

        unregisterReceiver(mWiredHeadsetReceiver);

        mBluetoothManager.stop();

        // Restore previously stored audio states.
        setSpeakerphoneOn(mSavedIsSpeakerPhoneOn);
        setMicrophoneMute(mSavedIsMicrophoneMute);
        mAudioManager.setMode(mSavedAudioMode);

        // Abandon audio focus. Gives the previous focus owner, if any, focus.
        mAudioManager.abandonAudioFocus(mAudioFocusChangeListener);
        mAudioFocusChangeListener = null;
        Log.d(TAG, "Abandoned audio focus for VOICE_CALL streams");

        if (mProximitySensor != null) {
            mProximitySensor.stop();
            mProximitySensor = null;
        }

        mAudioManagerEvents = null;
        Log.d(TAG, "AudioManager stopped");
    }

    ;

    /**
     * Changes selection of the currently active audio device.
     */
    public void setAudioDeviceInternal(AudioDevice device) {
        Log.d(TAG, "setAudioDeviceInternal(device=" + device + ")");
        AppRTCUtils.assertIsTrue(mAudioDevices.contains(device));

        switch (device) {
            case SPEAKER_PHONE:
                setSpeakerphoneOn(true);
                break;
            case EARPIECE:
                setSpeakerphoneOn(false);
                break;
            case WIRED_HEADSET:
                setSpeakerphoneOn(false);
                break;
            case BLUETOOTH:
                setSpeakerphoneOn(false);
                break;
            default:
                Log.e(TAG, "Invalid audio device selection");
                break;
        }
        mSelectedAudioDevice = device;
    }

    /**
     * Changes default audio device.
     * TODO(henrika): add usage of this method in the AppRTCMobile client.
     */
    public void setDefaultAudioDevice(AudioDevice defaultDevice) {
        ThreadUtils.checkIsOnMainThread();
        switch (defaultDevice) {
            case SPEAKER_PHONE:
                mDefaultAudioDevice = defaultDevice;
                break;
            case EARPIECE:
                if (hasEarpiece()) {
                    mDefaultAudioDevice = defaultDevice;
                } else {
                    mDefaultAudioDevice = AudioDevice.SPEAKER_PHONE;
                }
                break;
            default:
                Log.e(TAG, "Invalid default audio device selection");
                break;
        }
        Log.d(TAG, "setDefaultAudioDevice(device=" + mDefaultAudioDevice + ")");
        updateAudioDeviceState();
    }

    /**
     * Changes selection of the currently active audio device.
     */
    public void selectAudioDevice(AudioDevice device) {
        ThreadUtils.checkIsOnMainThread();
        if (!mAudioDevices.contains(device)) {
            Log.e(TAG, "Can not select " + device + " from available " + mAudioDevices);
        }
        mUserSelectedAudioDevice = device;
        updateAudioDeviceState();
    }

    /**
     * Returns current set of available/selectable audio devices.
     */
    public Set<AudioDevice> getAudioDevices() {
        ThreadUtils.checkIsOnMainThread();
        return Collections.unmodifiableSet(new HashSet<AudioDevice>(mAudioDevices));
    }

    /**
     * Returns the currently selected audio device.
     */
    public AudioDevice getSelectedAudioDevice() {
        ThreadUtils.checkIsOnMainThread();
        return mSelectedAudioDevice;
    }

    /**
     * Helper method for receiver registration.
     */
    private void registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        mApprtcContext.registerReceiver(receiver, filter);
    }

    /**
     * Helper method for unregistration of an existing receiver.
     */
    private void unregisterReceiver(BroadcastReceiver receiver) {
        mApprtcContext.unregisterReceiver(receiver);
    }

    /**
     * Sets the speaker phone mode.
     */
    private void setSpeakerphoneOn(boolean on) {
        boolean wasOn = mAudioManager.isSpeakerphoneOn();
        if (wasOn == on) {
            return;
        }
        mAudioManager.setSpeakerphoneOn(on);
    }

    /**
     * Sets the microphone mute state.
     */
    private void setMicrophoneMute(boolean on) {
        boolean wasMuted = mAudioManager.isMicrophoneMute();
        if (wasMuted == on) {
            return;
        }
        mAudioManager.setMicrophoneMute(on);
    }

    /**
     * Gets the current earpiece state.
     */
    private boolean hasEarpiece() {
        return mApprtcContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    /**
     * Checks whether a wired headset is connected or not.
     * This is not a valid indication that audio playback is actually over
     * the wired headset as audio routing depends on other conditions. We
     * only use it as an early indicator (during initialization) of an attached
     * wired headset.
     */
    @Deprecated
    private boolean hasWiredHeadset() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return mAudioManager.isWiredHeadsetOn();
        } else {
            final AudioDeviceInfo[] devices = mAudioManager.getDevices(AudioManager.GET_DEVICES_ALL);
            for (AudioDeviceInfo device : devices) {
                final int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                    Log.d(TAG, "hasWiredHeadset: found wired headset");
                    return true;
                } else if (type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                    Log.d(TAG, "hasWiredHeadset: found USB audio device");
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Updates list of possible audio devices and make new device selection.
     * TODO(henrika): add unit test to verify all state transitions.
     */
    public void updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "--- updateAudioDeviceState: "
                + "wired headset=" + mHasWiredHeadset + ", "
                + "BT state=" + mBluetoothManager.getState());
        Log.d(TAG, "Device status: "
                + "available=" + mAudioDevices + ", "
                + "selected=" + mSelectedAudioDevice + ", "
                + "user selected=" + mUserSelectedAudioDevice);

        // Check if any Bluetooth headset is connected. The internal BT state will
        // change accordingly.
        // TODO(henrika): perhaps wrap required state into BT manager.
        if (mBluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_AVAILABLE
                || mBluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_UNAVAILABLE
                || mBluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_DISCONNECTING) {
            mBluetoothManager.updateDevice();
        }

        // Update the set of available audio devices.
        Set<AudioDevice> newAudioDevices = new HashSet<>();

        if (mBluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTED
                || mBluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTING
                || mBluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_AVAILABLE) {
            newAudioDevices.add(AudioDevice.BLUETOOTH);
        }

        if (mHasWiredHeadset) {
            // If a wired headset is connected, then it is the only possible option.
            newAudioDevices.add(AudioDevice.WIRED_HEADSET);
        } else {
            // No wired headset, hence the audio-device list can contain speaker
            // phone (on a tablet), or speaker phone and earpiece (on mobile phone).
            newAudioDevices.add(AudioDevice.SPEAKER_PHONE);
            if (hasEarpiece()) {
                newAudioDevices.add(AudioDevice.EARPIECE);
            }
        }
        // Store state which is set to true if the device list has changed.
        boolean audioDeviceSetUpdated = !mAudioDevices.equals(newAudioDevices);
        // Update the existing audio device set.
        mAudioDevices = newAudioDevices;
        // Correct user selected audio devices if needed.
        if (mBluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_UNAVAILABLE
                && mUserSelectedAudioDevice == AudioDevice.BLUETOOTH) {
            // If BT is not available, it can't be the user selection.
            mUserSelectedAudioDevice = AudioDevice.NONE;
        }
        if (mHasWiredHeadset && mUserSelectedAudioDevice == AudioDevice.SPEAKER_PHONE) {
            // If user selected speaker phone, but then plugged wired headset then make
            // wired headset as user selected device.
            mUserSelectedAudioDevice = AudioDevice.WIRED_HEADSET;
        }
        if (!mHasWiredHeadset && mUserSelectedAudioDevice == AudioDevice.WIRED_HEADSET) {
            // If user selected wired headset, but then unplugged wired headset then make
            // speaker phone as user selected device.
            mUserSelectedAudioDevice = AudioDevice.SPEAKER_PHONE;
        }

        // Need to start Bluetooth if it is available and user either selected it explicitly or
        // user did not select any output device.
        boolean needBluetoothAudioStart =
                mBluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_AVAILABLE
                        && (mUserSelectedAudioDevice == AudioDevice.NONE
                        || mUserSelectedAudioDevice == AudioDevice.BLUETOOTH);

        // Need to stop Bluetooth audio if user selected different device and
        // Bluetooth SCO connection is established or in the process.
        boolean needBluetoothAudioStop =
                (mBluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTED
                        || mBluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTING)
                        && (mUserSelectedAudioDevice != AudioDevice.NONE
                        && mUserSelectedAudioDevice != AudioDevice.BLUETOOTH);

        if (mBluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_AVAILABLE
                || mBluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTING
                || mBluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTED) {
            Log.d(TAG, "Need BT audio: start=" + needBluetoothAudioStart + ", "
                    + "stop=" + needBluetoothAudioStop + ", "
                    + "BT state=" + mBluetoothManager.getState());
        }

        // Start or stop Bluetooth SCO connection given states set earlier.
        if (needBluetoothAudioStop) {
            mBluetoothManager.stopScoAudio();
            mBluetoothManager.updateDevice();
        }

        if (needBluetoothAudioStart && !needBluetoothAudioStop) {
            // Attempt to start Bluetooth SCO audio (takes a few second to start).
            if (!mBluetoothManager.startScoAudio()) {
                // Remove BLUETOOTH from list of available devices since SCO failed.
                mAudioDevices.remove(AudioDevice.BLUETOOTH);
                audioDeviceSetUpdated = true;
            }
        }

        // Update selected audio device.
        AudioDevice newAudioDevice = mSelectedAudioDevice;

        if (mBluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTED) {
            // If a Bluetooth is connected, then it should be used as output audio
            // device. Note that it is not sufficient that a headset is available;
            // an active SCO channel must also be up and running.
            newAudioDevice = AudioDevice.BLUETOOTH;
        } else if (mHasWiredHeadset) {
            // If a wired headset is connected, but Bluetooth is not, then wired headset is used as
            // audio device.
            newAudioDevice = AudioDevice.WIRED_HEADSET;
        } else {
            // No wired headset and no Bluetooth, hence the audio-device list can contain speaker
            // phone (on a tablet), or speaker phone and earpiece (on mobile phone).
            // |mDefaultAudioDevice| contains either AudioDevice.SPEAKER_PHONE or AudioDevice.EARPIECE
            // depending on the user's selection.
            newAudioDevice = mDefaultAudioDevice;
        }
        // Switch to new device but only if there has been any changes.
        if (newAudioDevice != mSelectedAudioDevice || audioDeviceSetUpdated) {
            // Do the required device switch.
            setAudioDeviceInternal(newAudioDevice);
            Log.d(TAG, "New device status: "
                    + "available=" + mAudioDevices + ", "
                    + "selected=" + newAudioDevice);
            if (mAudioManagerEvents != null) {
                // Notify a listening client that audio device has been changed.
                mAudioManagerEvents.onAudioDeviceChanged(mSelectedAudioDevice, mAudioDevices);
            }
        }
        Log.d(TAG, "--- updateAudioDeviceState done");
    }

    /**
     * AudioDevice is the names of possible audio devices that we currently
     * support.
     */
    public enum AudioDevice {
        SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, BLUETOOTH, NONE
    }

    /**
     * AudioManager state.
     */
    public enum AudioManagerState {
        UNINITIALIZED,
        PREINITIALIZED,
        RUNNING,
    }

    /**
     * Selected audio device change event.
     */
    public static interface AudioManagerEvents {
        // Callback fired once audio device is changed or list of available audio devices changed.
        void onAudioDeviceChanged(
                AudioDevice mSelectedAudioDevice, Set<AudioDevice> availableAudioDevices);
    }

    /* Receiver which handles changes in wired headset availability. */
    private class WiredHeadsetReceiver extends BroadcastReceiver {
        private static final int STATE_UNPLUGGED = 0;
        private static final int STATE_PLUGGED = 1;
        private static final int HAS_NO_MIC = 0;
        private static final int HAS_MIC = 1;

        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra("state", STATE_UNPLUGGED);
            int microphone = intent.getIntExtra("microphone", HAS_NO_MIC);
            String name = intent.getStringExtra("name");
            Log.d(TAG, "WiredHeadsetReceiver.onReceive" + AppRTCUtils.getThreadInfo() + ": "
                    + "a=" + intent.getAction() + ", s="
                    + (state == STATE_UNPLUGGED ? "unplugged" : "plugged") + ", m="
                    + (microphone == HAS_MIC ? "mic" : "no mic") + ", n=" + name + ", sb="
                    + isInitialStickyBroadcast());
            mHasWiredHeadset = (state == STATE_PLUGGED);
            updateAudioDeviceState();
        }
    }
}
