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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.Log;

import org.webrtc.ThreadUtils;

/**
 * AppRTCProximitySensor manages functions related to the proximity sensor in
 * the AppRTC demo.
 * On most device, the proximity sensor is implemented as a boolean-sensor.
 * It returns just two values "NEAR" or "FAR". Thresholding is done on the LUX
 * value i.e. the LUX value of the light sensor is compared with a threshold.
 * A LUX-value more than the threshold means the proximity sensor returns "FAR".
 * Anything less than the threshold value and the sensor  returns "NEAR".
 */
public class AppRTCProximitySensor implements SensorEventListener {
    private static final String TAG = "AppRTCProximitySensor";

    // This class should be created, started and stopped on one thread
    // (e.g. the main thread). We use |nonThreadSafe| to ensure that this is
    // the case. Only active when |DEBUG| is set to true.
    private final ThreadUtils.ThreadChecker mThreadChecker = new ThreadUtils.ThreadChecker();

    private final Runnable mOnSensorStateListener;
    private final SensorManager mSensorManager;
    private Sensor mProximitySensor = null;
    private boolean mLastStateReportIsNear = false;

    private AppRTCProximitySensor(Context context, Runnable sensorStateListener) {
        Log.d(TAG, "AppRTCProximitySensor" + AppRTCUtils.getThreadInfo());
        mOnSensorStateListener = sensorStateListener;
        mSensorManager = ((SensorManager) context.getSystemService(Context.SENSOR_SERVICE));
    }

    /**
     * Construction
     */
    static AppRTCProximitySensor create(Context context, Runnable sensorStateListener) {
        return new AppRTCProximitySensor(context, sensorStateListener);
    }

    /**
     * Activate the proximity sensor. Also do initialization if called for the
     * first time.
     */
    public boolean start() {
        mThreadChecker.checkIsOnValidThread();
        Log.d(TAG, "start" + AppRTCUtils.getThreadInfo());
        if (!initDefaultSensor()) {
            // Proximity sensor is not supported on this device.
            return false;
        }
        mSensorManager.registerListener(this, mProximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        return true;
    }

    /**
     * Deactivate the proximity sensor.
     */
    public void stop() {
        mThreadChecker.checkIsOnValidThread();
        Log.d(TAG, "stop" + AppRTCUtils.getThreadInfo());
        if (mProximitySensor == null) {
            return;
        }
        mSensorManager.unregisterListener(this, mProximitySensor);
    }

    /**
     * Getter for last reported state. Set to true if "near" is reported.
     */
    public boolean sensorReportsNearState() {
        mThreadChecker.checkIsOnValidThread();
        return mLastStateReportIsNear;
    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        mThreadChecker.checkIsOnValidThread();
        AppRTCUtils.assertIsTrue(sensor.getType() == Sensor.TYPE_PROXIMITY);
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Log.e(TAG, "The values returned by this sensor cannot be trusted");
        }
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        mThreadChecker.checkIsOnValidThread();
        AppRTCUtils.assertIsTrue(event.sensor.getType() == Sensor.TYPE_PROXIMITY);
        // As a best practice; do as little as possible within this method and
        // avoid blocking.
        float distanceInCentimeters = event.values[0];
        if (distanceInCentimeters < mProximitySensor.getMaximumRange()) {
            Log.d(TAG, "Proximity sensor => NEAR state");
            mLastStateReportIsNear = true;
        } else {
            Log.d(TAG, "Proximity sensor => FAR state");
            mLastStateReportIsNear = false;
        }

        // Report about new state to listening client. Client can then call
        // sensorReportsNearState() to query the current state (NEAR or FAR).
        if (mOnSensorStateListener != null) {
            mOnSensorStateListener.run();
        }

        Log.d(TAG, "onSensorChanged" + AppRTCUtils.getThreadInfo() + ": "
                + "accuracy=" + event.accuracy + ", timestamp=" + event.timestamp + ", distance="
                + event.values[0]);
    }

    /**
     * Get default proximity sensor if it exists. Tablet devices (e.g. Nexus 7)
     * does not support this type of sensor and false will be returned in such
     * cases.
     */
    private boolean initDefaultSensor() {
        if (mProximitySensor != null) {
            return true;
        }
        mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (mProximitySensor == null) {
            return false;
        }
        logProximitySensorInfo();
        return true;
    }

    /**
     * Helper method for logging information about the proximity sensor.
     */
    private void logProximitySensorInfo() {
        if (mProximitySensor == null) {
            return;
        }
        StringBuilder info = new StringBuilder("Proximity sensor: ");
        info.append("name=").append(mProximitySensor.getName());
        info.append(", vendor: ").append(mProximitySensor.getVendor());
        info.append(", power: ").append(mProximitySensor.getPower());
        info.append(", resolution: ").append(mProximitySensor.getResolution());
        info.append(", max range: ").append(mProximitySensor.getMaximumRange());
        info.append(", min delay: ").append(mProximitySensor.getMinDelay());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            // Added in API level 20.
            info.append(", type: ").append(mProximitySensor.getStringType());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Added in API level 21.
            info.append(", max delay: ").append(mProximitySensor.getMaxDelay());
            info.append(", reporting mode: ").append(mProximitySensor.getReportingMode());
            info.append(", isWakeUpSensor: ").append(mProximitySensor.isWakeUpSensor());
        }
        Log.d(TAG, info.toString());
    }
}
