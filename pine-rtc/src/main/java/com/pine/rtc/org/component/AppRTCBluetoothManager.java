/*
 *  Copyright 2016 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package com.pine.rtc.org.component;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import org.webrtc.ThreadUtils;

import java.util.List;
import java.util.Set;

/**
 * AppRTCProximitySensor manages functions related to Bluetoth devices in the
 * AppRTC demo.
 */
public class AppRTCBluetoothManager {
    private static final String TAG = "AppRTCBluetoothManager";

    // Timeout interval for starting or stopping audio to a Bluetooth SCO device.
    private static final int BLUETOOTH_SCO_TIMEOUT_MS = 4000;
    // Maximum number of SCO connection attempts.
    private static final int MAX_SCO_CONNECTION_ATTEMPTS = 2;
    private final Context mApprtcContext;
    private final AppRTCAudioManager mApprtcAudioManager;
    private final AudioManager mAudioManager;
    private final Handler mHandler;
    private final BluetoothProfile.ServiceListener mBluetoothServiceListener;
    private final BroadcastReceiver mBluetoothHeadsetReceiver;
    int mScoConnectionAttempts;
    private State mBluetoothState;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothHeadset mBluetoothHeadset;
    private BluetoothDevice mBluetoothDevice;
    // Runs when the Bluetooth timeout expires. We use that timeout after calling
    // startScoAudio() or stopScoAudio() because we're not guaranteed to get a
    // callback after those calls.
    private final Runnable mBluetoothTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            bluetoothTimeout();
        }
    };

    protected AppRTCBluetoothManager(Context context, AppRTCAudioManager audioManager) {
        Log.d(TAG, "ctor");
        ThreadUtils.checkIsOnMainThread();
        mApprtcContext = context;
        mApprtcAudioManager = audioManager;
        mAudioManager = getAudioManager(context);
        mBluetoothState = State.UNINITIALIZED;
        mBluetoothServiceListener = new BluetoothServiceListener();
        mBluetoothHeadsetReceiver = new BluetoothHeadsetBroadcastReceiver();
        mHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Construction.
     */
    static AppRTCBluetoothManager create(Context context, AppRTCAudioManager audioManager) {
        Log.d(TAG, "create" + AppRTCUtils.getThreadInfo());
        return new AppRTCBluetoothManager(context, audioManager);
    }

    /**
     * Returns the internal state.
     */
    public State getState() {
        ThreadUtils.checkIsOnMainThread();
        return mBluetoothState;
    }

    ;

    /**
     * Activates components required to detect Bluetooth devices and to enable
     * BT SCO (audio is routed via BT SCO) for the headset profile. The end
     * state will be HEADSET_UNAVAILABLE but a state machine has started which
     * will start a state change sequence where the final outcome depends on
     * if/when the BT headset is enabled.
     * Example of state change sequence when start() is called while BT device
     * is connected and enabled:
     * UNINITIALIZED --> HEADSET_UNAVAILABLE --> HEADSET_AVAILABLE -->
     * SCO_CONNECTING --> SCO_CONNECTED <==> audio is now routed via BT SCO.
     * Note that the AppRTCAudioManager is also involved in driving this state
     * change.
     */
    public void start() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "start");
        if (!hasPermission(mApprtcContext, android.Manifest.permission.BLUETOOTH)) {
            Log.w(TAG, "Process (pid=" + Process.myPid() + ") lacks BLUETOOTH permission");
            return;
        }
        if (mBluetoothState != State.UNINITIALIZED) {
            Log.w(TAG, "Invalid BT state");
            return;
        }
        mBluetoothHeadset = null;
        mBluetoothDevice = null;
        mScoConnectionAttempts = 0;
        // Get a handle to the default local Bluetooth adapter.
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Log.w(TAG, "Device does not support Bluetooth");
            return;
        }
        // Ensure that the device supports use of BT SCO audio for off call use cases.
        if (!mAudioManager.isBluetoothScoAvailableOffCall()) {
            Log.e(TAG, "Bluetooth SCO audio is not available off call");
            return;
        }
        logBluetoothAdapterInfo(mBluetoothAdapter);
        // Establish a connection to the HEADSET profile (includes both Bluetooth Headset and
        // Hands-Free) proxy object and install a listener.
        if (!getBluetoothProfileProxy(
                mApprtcContext, mBluetoothServiceListener, BluetoothProfile.HEADSET)) {
            Log.e(TAG, "BluetoothAdapter.getProfileProxy(HEADSET) failed");
            return;
        }
        // Register receivers for BluetoothHeadset change notifications.
        IntentFilter bluetoothHeadsetFilter = new IntentFilter();
        // Register receiver for change in connection state of the Headset profile.
        bluetoothHeadsetFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        // Register receiver for change in audio connection state of the Headset profile.
        bluetoothHeadsetFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
        registerReceiver(mBluetoothHeadsetReceiver, bluetoothHeadsetFilter);
        Log.d(TAG, "HEADSET profile state: "
                + stateToString(mBluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET)));
        Log.d(TAG, "Bluetooth proxy for headset profile has started");
        mBluetoothState = State.HEADSET_UNAVAILABLE;
        Log.d(TAG, "start done: BT state=" + mBluetoothState);
    }

    /**
     * Stops and closes all components related to Bluetooth audio.
     */
    public void stop() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "stop: BT state=" + mBluetoothState);
        if (mBluetoothAdapter == null) {
            return;
        }
        // Stop BT SCO connection with remote device if needed.
        stopScoAudio();
        // Close down remaining BT resources.
        if (mBluetoothState == State.UNINITIALIZED) {
            return;
        }
        unregisterReceiver(mBluetoothHeadsetReceiver);
        cancelTimer();
        if (mBluetoothHeadset != null) {
            mBluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, mBluetoothHeadset);
            mBluetoothHeadset = null;
        }
        mBluetoothAdapter = null;
        mBluetoothDevice = null;
        mBluetoothState = State.UNINITIALIZED;
        Log.d(TAG, "stop done: BT state=" + mBluetoothState);
    }

    /**
     * Starts Bluetooth SCO connection with remote device.
     * Note that the phone application always has the priority on the usage of the SCO connection
     * for telephony. If this method is called while the phone is in call it will be ignored.
     * Similarly, if a call is received or sent while an application is using the SCO connection,
     * the connection will be lost for the application and NOT returned automatically when the call
     * ends. Also note that: up to and including API version JELLY_BEAN_MR1, this method initiates a
     * virtual voice call to the Bluetooth headset. After API version JELLY_BEAN_MR2 only a raw SCO
     * audio connection is established.
     * TODO(henrika): should we add support for virtual voice call to BT headset also for JBMR2 and
     * higher. It might be required to initiates a virtual voice call since many devices do not
     * accept SCO audio without a "call".
     */
    public boolean startScoAudio() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "startSco: BT state=" + mBluetoothState + ", "
                + "attempts: " + mScoConnectionAttempts + ", "
                + "SCO is on: " + isScoOn());
        if (mScoConnectionAttempts >= MAX_SCO_CONNECTION_ATTEMPTS) {
            Log.e(TAG, "BT SCO connection fails - no more attempts");
            return false;
        }
        if (mBluetoothState != State.HEADSET_AVAILABLE) {
            Log.e(TAG, "BT SCO connection fails - no headset available");
            return false;
        }
        // Start BT SCO channel and wait for ACTION_AUDIO_STATE_CHANGED.
        Log.d(TAG, "Starting Bluetooth SCO and waits for ACTION_AUDIO_STATE_CHANGED...");
        // The SCO connection establishment can take several seconds, hence we cannot rely on the
        // connection to be available when the method returns but instead register to receive the
        // intent ACTION_SCO_AUDIO_STATE_UPDATED and wait for the state to be SCO_AUDIO_STATE_CONNECTED.
        mBluetoothState = State.SCO_CONNECTING;
        mAudioManager.startBluetoothSco();
        mAudioManager.setBluetoothScoOn(true);
        mScoConnectionAttempts++;
        startTimer();
        Log.d(TAG, "startScoAudio done: BT state=" + mBluetoothState + ", "
                + "SCO is on: " + isScoOn());
        return true;
    }

    /**
     * Stops Bluetooth SCO connection with remote device.
     */
    public void stopScoAudio() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "stopScoAudio: BT state=" + mBluetoothState + ", "
                + "SCO is on: " + isScoOn());
        if (mBluetoothState != State.SCO_CONNECTING && mBluetoothState != State.SCO_CONNECTED) {
            return;
        }
        cancelTimer();
        mAudioManager.stopBluetoothSco();
        mAudioManager.setBluetoothScoOn(false);
        mBluetoothState = State.SCO_DISCONNECTING;
        Log.d(TAG, "stopScoAudio done: BT state=" + mBluetoothState + ", "
                + "SCO is on: " + isScoOn());
    }

    /**
     * Use the BluetoothHeadset proxy object (controls the Bluetooth Headset
     * Service via IPC) to update the list of connected devices for the HEADSET
     * profile. The internal state will change to HEADSET_UNAVAILABLE or to
     * HEADSET_AVAILABLE and |mBluetoothDevice| will be mapped to the connected
     * device if available.
     */
    public void updateDevice() {
        if (mBluetoothState == State.UNINITIALIZED || mBluetoothHeadset == null) {
            return;
        }
        Log.d(TAG, "updateDevice");
        // Get connected devices for the headset profile. Returns the set of
        // devices which are in state STATE_CONNECTED. The BluetoothDevice class
        // is just a thin wrapper for a Bluetooth hardware address.
        List<BluetoothDevice> devices = mBluetoothHeadset.getConnectedDevices();
        if (devices.isEmpty()) {
            mBluetoothDevice = null;
            mBluetoothState = State.HEADSET_UNAVAILABLE;
            Log.d(TAG, "No connected bluetooth headset");
        } else {
            // Always use first device in list. Android only supports one device.
            mBluetoothDevice = devices.get(0);
            mBluetoothState = State.HEADSET_AVAILABLE;
            Log.d(TAG, "Connected bluetooth headset: "
                    + "name=" + mBluetoothDevice.getName() + ", "
                    + "state=" + stateToString(mBluetoothHeadset.getConnectionState(mBluetoothDevice))
                    + ", SCO audio=" + mBluetoothHeadset.isAudioConnected(mBluetoothDevice));
        }
        Log.d(TAG, "updateDevice done: BT state=" + mBluetoothState);
    }

    /**
     * Stubs for test mocks.
     */
    protected AudioManager getAudioManager(Context context) {
        return (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    protected void registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        mApprtcContext.registerReceiver(receiver, filter);
    }

    protected void unregisterReceiver(BroadcastReceiver receiver) {
        mApprtcContext.unregisterReceiver(receiver);
    }

    protected boolean getBluetoothProfileProxy(
            Context context, BluetoothProfile.ServiceListener listener, int profile) {
        return mBluetoothAdapter.getProfileProxy(context, listener, profile);
    }

    protected boolean hasPermission(Context context, String permission) {
        return mApprtcContext.checkPermission(permission, Process.myPid(), Process.myUid())
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Logs the state of the local Bluetooth adapter.
     */
    @SuppressLint("HardwareIds")
    protected void logBluetoothAdapterInfo(BluetoothAdapter localAdapter) {
        Log.d(TAG, "BluetoothAdapter: "
                + "enabled=" + localAdapter.isEnabled() + ", "
                + "state=" + stateToString(localAdapter.getState()) + ", "
                + "name=" + localAdapter.getName() + ", "
                + "address=" + localAdapter.getAddress());
        // Log the set of BluetoothDevice objects that are bonded (paired) to the local adapter.
        Set<BluetoothDevice> pairedDevices = localAdapter.getBondedDevices();
        if (!pairedDevices.isEmpty()) {
            Log.d(TAG, "paired devices:");
            for (BluetoothDevice device : pairedDevices) {
                Log.d(TAG, " name=" + device.getName() + ", address=" + device.getAddress());
            }
        }
    }

    /**
     * Ensures that the audio manager updates its list of available audio devices.
     */
    private void updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "updateAudioDeviceState");
        mApprtcAudioManager.updateAudioDeviceState();
    }

    /**
     * Starts timer which times out after BLUETOOTH_SCO_TIMEOUT_MS milliseconds.
     */
    private void startTimer() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "startTimer");
        mHandler.postDelayed(mBluetoothTimeoutRunnable, BLUETOOTH_SCO_TIMEOUT_MS);
    }

    /**
     * Cancels any outstanding timer tasks.
     */
    private void cancelTimer() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "cancelTimer");
        mHandler.removeCallbacks(mBluetoothTimeoutRunnable);
    }

    /**
     * Called when start of the BT SCO channel takes too long time. Usually
     * happens when the BT device has been turned on during an ongoing call.
     */
    private void bluetoothTimeout() {
        ThreadUtils.checkIsOnMainThread();
        if (mBluetoothState == State.UNINITIALIZED || mBluetoothHeadset == null) {
            return;
        }
        Log.d(TAG, "bluetoothTimeout: BT state=" + mBluetoothState + ", "
                + "attempts: " + mScoConnectionAttempts + ", "
                + "SCO is on: " + isScoOn());
        if (mBluetoothState != State.SCO_CONNECTING) {
            return;
        }
        // Bluetooth SCO should be connecting; check the latest result.
        boolean scoConnected = false;
        List<BluetoothDevice> devices = mBluetoothHeadset.getConnectedDevices();
        if (devices.size() > 0) {
            mBluetoothDevice = devices.get(0);
            if (mBluetoothHeadset.isAudioConnected(mBluetoothDevice)) {
                Log.d(TAG, "SCO connected with " + mBluetoothDevice.getName());
                scoConnected = true;
            } else {
                Log.d(TAG, "SCO is not connected with " + mBluetoothDevice.getName());
            }
        }
        if (scoConnected) {
            // We thought BT had timed out, but it's actually on; updating state.
            mBluetoothState = State.SCO_CONNECTED;
            mScoConnectionAttempts = 0;
        } else {
            // Give up and "cancel" our request by calling stopBluetoothSco().
            Log.w(TAG, "BT failed to connect after timeout");
            stopScoAudio();
        }
        updateAudioDeviceState();
        Log.d(TAG, "bluetoothTimeout done: BT state=" + mBluetoothState);
    }

    /**
     * Checks whether audio uses Bluetooth SCO.
     */
    private boolean isScoOn() {
        return mAudioManager.isBluetoothScoOn();
    }

    /**
     * Converts BluetoothAdapter states into local string representations.
     */
    private String stateToString(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_DISCONNECTED:
                return "DISCONNECTED";
            case BluetoothAdapter.STATE_CONNECTED:
                return "CONNECTED";
            case BluetoothAdapter.STATE_CONNECTING:
                return "CONNECTING";
            case BluetoothAdapter.STATE_DISCONNECTING:
                return "DISCONNECTING";
            case BluetoothAdapter.STATE_OFF:
                return "OFF";
            case BluetoothAdapter.STATE_ON:
                return "ON";
            case BluetoothAdapter.STATE_TURNING_OFF:
                // Indicates the local Bluetooth adapter is turning off. Local clients should immediately
                // attempt graceful disconnection of any remote links.
                return "TURNING_OFF";
            case BluetoothAdapter.STATE_TURNING_ON:
                // Indicates the local Bluetooth adapter is turning on. However local clients should wait
                // for STATE_ON before attempting to use the adapter.
                return "TURNING_ON";
            default:
                return "INVALID";
        }
    }

    // Bluetooth connection state.
    public enum State {
        // Bluetooth is not available; no adapter or Bluetooth is off.
        UNINITIALIZED,
        // Bluetooth error happened when trying to start Bluetooth.
        ERROR,
        // Bluetooth proxy object for the Headset profile exists, but no connected headset devices,
        // SCO is not started or disconnected.
        HEADSET_UNAVAILABLE,
        // Bluetooth proxy object for the Headset profile connected, connected Bluetooth headset
        // present, but SCO is not started or disconnected.
        HEADSET_AVAILABLE,
        // Bluetooth audio SCO connection with remote device is closing.
        SCO_DISCONNECTING,
        // Bluetooth audio SCO connection with remote device is initiated.
        SCO_CONNECTING,
        // Bluetooth audio SCO connection with remote device is established.
        SCO_CONNECTED
    }

    /**
     * Implementation of an interface that notifies BluetoothProfile IPC clients when they have been
     * connected to or disconnected from the service.
     */
    private class BluetoothServiceListener implements BluetoothProfile.ServiceListener {
        @Override
        // Called to notify the client when the proxy object has been connected to the service.
        // Once we have the profile proxy object, we can use it to monitor the state of the
        // connection and perform other operations that are relevant to the headset profile.
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HEADSET || mBluetoothState == State.UNINITIALIZED) {
                return;
            }
            Log.d(TAG, "BluetoothServiceListener.onServiceConnected: BT state=" + mBluetoothState);
            // Android only supports one connected Bluetooth Headset at a time.
            mBluetoothHeadset = (BluetoothHeadset) proxy;
            updateAudioDeviceState();
            Log.d(TAG, "onServiceConnected done: BT state=" + mBluetoothState);
        }

        @Override
        /** Notifies the client when the proxy object has been disconnected from the service. */
        public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.HEADSET || mBluetoothState == State.UNINITIALIZED) {
                return;
            }
            Log.d(TAG, "BluetoothServiceListener.onServiceDisconnected: BT state=" + mBluetoothState);
            stopScoAudio();
            mBluetoothHeadset = null;
            mBluetoothDevice = null;
            mBluetoothState = State.HEADSET_UNAVAILABLE;
            updateAudioDeviceState();
            Log.d(TAG, "onServiceDisconnected done: BT state=" + mBluetoothState);
        }
    }

    // Intent broadcast receiver which handles changes in Bluetooth device availability.
    // Detects headset changes and Bluetooth SCO state changes.
    private class BluetoothHeadsetBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mBluetoothState == State.UNINITIALIZED) {
                return;
            }
            final String action = intent.getAction();
            // Change in connection state of the Headset profile. Note that the
            // change does not tell us anything about whether we're streaming
            // audio to BT over SCO. Typically received when user turns on a BT
            // headset while audio is active using another audio device.
            if (action.equals(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)) {
                final int state =
                        intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED);
                Log.d(TAG, "BluetoothHeadsetBroadcastReceiver.onReceive: "
                        + "a=ACTION_CONNECTION_STATE_CHANGED, "
                        + "s=" + stateToString(state) + ", "
                        + "sb=" + isInitialStickyBroadcast() + ", "
                        + "BT state: " + mBluetoothState);
                if (state == BluetoothHeadset.STATE_CONNECTED) {
                    mScoConnectionAttempts = 0;
                    updateAudioDeviceState();
                } else if (state == BluetoothHeadset.STATE_CONNECTING) {
                    // No action needed.
                } else if (state == BluetoothHeadset.STATE_DISCONNECTING) {
                    // No action needed.
                } else if (state == BluetoothHeadset.STATE_DISCONNECTED) {
                    // Bluetooth is probably powered off during the call.
                    stopScoAudio();
                    updateAudioDeviceState();
                }
                // Change in the audio (SCO) connection state of the Headset profile.
                // Typically received after call to startScoAudio() has finalized.
            } else if (action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {
                final int state = intent.getIntExtra(
                        BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
                Log.d(TAG, "BluetoothHeadsetBroadcastReceiver.onReceive: "
                        + "a=ACTION_AUDIO_STATE_CHANGED, "
                        + "s=" + stateToString(state) + ", "
                        + "sb=" + isInitialStickyBroadcast() + ", "
                        + "BT state: " + mBluetoothState);
                if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                    cancelTimer();
                    if (mBluetoothState == State.SCO_CONNECTING) {
                        Log.d(TAG, "+++ Bluetooth audio SCO is now connected");
                        mBluetoothState = State.SCO_CONNECTED;
                        mScoConnectionAttempts = 0;
                        updateAudioDeviceState();
                    } else {
                        Log.w(TAG, "Unexpected state BluetoothHeadset.STATE_AUDIO_CONNECTED");
                    }
                } else if (state == BluetoothHeadset.STATE_AUDIO_CONNECTING) {
                    Log.d(TAG, "+++ Bluetooth audio SCO is now connecting...");
                } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    Log.d(TAG, "+++ Bluetooth audio SCO is now disconnected");
                    if (isInitialStickyBroadcast()) {
                        Log.d(TAG, "Ignore STATE_AUDIO_DISCONNECTED initial sticky broadcast.");
                        return;
                    }
                    updateAudioDeviceState();
                }
            }
            Log.d(TAG, "onReceive done: BT state=" + mBluetoothState);
        }
    }
}
