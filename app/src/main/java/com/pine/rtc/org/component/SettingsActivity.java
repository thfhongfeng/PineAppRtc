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

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;

import com.pine.rtc.R;

import org.webrtc.Camera2Enumerator;
import org.webrtc.voiceengine.WebRtcAudioUtils;

/**
 * Settings activity for AppRTC.
 */
public class SettingsActivity extends Activity implements OnSharedPreferenceChangeListener {
    private SettingsFragment mSettingsFragment;
    private String mKeyPrefVideoCall;
    private String mKeyPrefScreencapture;
    private String mKeyPrefCamera2;
    private String mKeyPrefResolution;
    private String mKeyPrefFps;
    private String mKeyPrefCaptureQualitySlider;
    private String mKeyPrefMaxVideoBitrateType;
    private String mKeyPrefMaxVideoBitrateValue;
    private String mKeyPrefVideoCodec;
    private String mKeyPrefHwCodec;
    private String mKeyPrefCaptureToTexture;
    private String mKeyPrefFlexfec;

    private String mKeyPrefStartAudioBitrateType;
    private String mKeyPrefStartAudioBitrateValue;
    private String mKeyPrefAudioCodec;
    private String mKeyPrefNoAudioProcessing;
    private String mKeyPrefAecDump;
    private String mKeyPrefOpenSLES;
    private String mKeyPrefDisableBuiltInAEC;
    private String mKeyPrefDisableBuiltInAGC;
    private String mKeyPrefDisableBuiltInNS;
    private String mKeyPrefEnableLevelControl;
    private String mKeyPrefDisableWebRtcAGCAndHPF;
    private String mKeyPrefSpeakerphone;

    private String mKeyPrefRoomServerUrl;
    private String mKeyPrefDisplayHud;
    private String mKeyPrefTracing;

    private String mKeyPrefEnableDataChannel;
    private String mKeyPrefOrdered;
    private String mKeyPrefMaxRetransmitTimeMs;
    private String mKeyPrefMaxRetransmits;
    private String mKeyPrefDataProtocol;
    private String mKeyPrefNegotiated;
    private String mKeyPrefDataId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mKeyPrefVideoCall = getString(R.string.pref_videocall_key);
        mKeyPrefScreencapture = getString(R.string.pref_screencapture_key);
        mKeyPrefCamera2 = getString(R.string.pref_camera2_key);
        mKeyPrefResolution = getString(R.string.pref_resolution_key);
        mKeyPrefFps = getString(R.string.pref_fps_key);
        mKeyPrefCaptureQualitySlider = getString(R.string.pref_capturequalityslider_key);
        mKeyPrefMaxVideoBitrateType = getString(R.string.pref_maxvideobitrate_key);
        mKeyPrefMaxVideoBitrateValue = getString(R.string.pref_maxvideobitratevalue_key);
        mKeyPrefVideoCodec = getString(R.string.pref_videocodec_key);
        mKeyPrefHwCodec = getString(R.string.pref_hwcodec_key);
        mKeyPrefCaptureToTexture = getString(R.string.pref_capturetotexture_key);
        mKeyPrefFlexfec = getString(R.string.pref_flexfec_key);

        mKeyPrefStartAudioBitrateType = getString(R.string.pref_startaudiobitrate_key);
        mKeyPrefStartAudioBitrateValue = getString(R.string.pref_startaudiobitratevalue_key);
        mKeyPrefAudioCodec = getString(R.string.pref_audiocodec_key);
        mKeyPrefNoAudioProcessing = getString(R.string.pref_noaudioprocessing_key);
        mKeyPrefAecDump = getString(R.string.pref_aecdump_key);
        mKeyPrefOpenSLES = getString(R.string.pref_opensles_key);
        mKeyPrefDisableBuiltInAEC = getString(R.string.pref_disable_built_in_aec_key);
        mKeyPrefDisableBuiltInAGC = getString(R.string.pref_disable_built_in_agc_key);
        mKeyPrefDisableBuiltInNS = getString(R.string.pref_disable_built_in_ns_key);
        mKeyPrefEnableLevelControl = getString(R.string.pref_enable_level_control_key);
        mKeyPrefDisableWebRtcAGCAndHPF = getString(R.string.pref_disable_webrtc_agc_and_hpf_key);
        mKeyPrefSpeakerphone = getString(R.string.pref_speakerphone_key);

        mKeyPrefEnableDataChannel = getString(R.string.pref_enable_datachannel_key);
        mKeyPrefOrdered = getString(R.string.pref_ordered_key);
        mKeyPrefMaxRetransmitTimeMs = getString(R.string.pref_max_retransmit_time_ms_key);
        mKeyPrefMaxRetransmits = getString(R.string.pref_max_retransmits_key);
        mKeyPrefDataProtocol = getString(R.string.pref_data_protocol_key);
        mKeyPrefNegotiated = getString(R.string.pref_negotiated_key);
        mKeyPrefDataId = getString(R.string.pref_data_id_key);

        mKeyPrefRoomServerUrl = getString(R.string.pref_room_server_url_key);
        mKeyPrefDisplayHud = getString(R.string.pref_displayhud_key);
        mKeyPrefTracing = getString(R.string.pref_tracing_key);

        // Display the fragment as the main content.
        mSettingsFragment = new SettingsFragment();
        getFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, mSettingsFragment)
                .commit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Set summary to be the user-description for the selected value
        SharedPreferences sharedPreferences =
                mSettingsFragment.getPreferenceScreen().getSharedPreferences();
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);
        updateSummaryB(sharedPreferences, mKeyPrefVideoCall);
        updateSummaryB(sharedPreferences, mKeyPrefScreencapture);
        updateSummaryB(sharedPreferences, mKeyPrefCamera2);
        updateSummary(sharedPreferences, mKeyPrefResolution);
        updateSummary(sharedPreferences, mKeyPrefFps);
        updateSummaryB(sharedPreferences, mKeyPrefCaptureQualitySlider);
        updateSummary(sharedPreferences, mKeyPrefMaxVideoBitrateType);
        updateSummaryBitrate(sharedPreferences, mKeyPrefMaxVideoBitrateValue);
        setVideoBitrateEnable(sharedPreferences);
        updateSummary(sharedPreferences, mKeyPrefVideoCodec);
        updateSummaryB(sharedPreferences, mKeyPrefHwCodec);
        updateSummaryB(sharedPreferences, mKeyPrefCaptureToTexture);
        updateSummaryB(sharedPreferences, mKeyPrefFlexfec);

        updateSummary(sharedPreferences, mKeyPrefStartAudioBitrateType);
        updateSummaryBitrate(sharedPreferences, mKeyPrefStartAudioBitrateValue);
        setAudioBitrateEnable(sharedPreferences);
        updateSummary(sharedPreferences, mKeyPrefAudioCodec);
        updateSummaryB(sharedPreferences, mKeyPrefNoAudioProcessing);
        updateSummaryB(sharedPreferences, mKeyPrefAecDump);
        updateSummaryB(sharedPreferences, mKeyPrefOpenSLES);
        updateSummaryB(sharedPreferences, mKeyPrefDisableBuiltInAEC);
        updateSummaryB(sharedPreferences, mKeyPrefDisableBuiltInAGC);
        updateSummaryB(sharedPreferences, mKeyPrefDisableBuiltInNS);
        updateSummaryB(sharedPreferences, mKeyPrefEnableLevelControl);
        updateSummaryB(sharedPreferences, mKeyPrefDisableWebRtcAGCAndHPF);
        updateSummaryList(sharedPreferences, mKeyPrefSpeakerphone);

        updateSummaryB(sharedPreferences, mKeyPrefEnableDataChannel);
        updateSummaryB(sharedPreferences, mKeyPrefOrdered);
        updateSummary(sharedPreferences, mKeyPrefMaxRetransmitTimeMs);
        updateSummary(sharedPreferences, mKeyPrefMaxRetransmits);
        updateSummary(sharedPreferences, mKeyPrefDataProtocol);
        updateSummaryB(sharedPreferences, mKeyPrefNegotiated);
        updateSummary(sharedPreferences, mKeyPrefDataId);
        setDataChannelEnable(sharedPreferences);

        updateSummary(sharedPreferences, mKeyPrefRoomServerUrl);
        updateSummaryB(sharedPreferences, mKeyPrefDisplayHud);
        updateSummaryB(sharedPreferences, mKeyPrefTracing);

        if (!Camera2Enumerator.isSupported(this)) {
            Preference camera2Preference = mSettingsFragment.findPreference(mKeyPrefCamera2);

            camera2Preference.setSummary(getString(R.string.pref_camera2_not_supported));
            camera2Preference.setEnabled(false);
        }

        // Disable forcing WebRTC based AEC so it won't affect our value.
        // Otherwise, if it was enabled, isAcousticEchoCancelerSupported would always return false.
        WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(false);
        if (!WebRtcAudioUtils.isAcousticEchoCancelerSupported()) {
            Preference disableBuiltInAECPreference =
                    mSettingsFragment.findPreference(mKeyPrefDisableBuiltInAEC);

            disableBuiltInAECPreference.setSummary(getString(R.string.pref_built_in_aec_not_available));
            disableBuiltInAECPreference.setEnabled(false);
        }

        WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(false);
        if (!WebRtcAudioUtils.isAutomaticGainControlSupported()) {
            Preference disableBuiltInAGCPreference =
                    mSettingsFragment.findPreference(mKeyPrefDisableBuiltInAGC);

            disableBuiltInAGCPreference.setSummary(getString(R.string.pref_built_in_agc_not_available));
            disableBuiltInAGCPreference.setEnabled(false);
        }

        WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(false);
        if (!WebRtcAudioUtils.isNoiseSuppressorSupported()) {
            Preference disableBuiltInNSPreference =
                    mSettingsFragment.findPreference(mKeyPrefDisableBuiltInNS);

            disableBuiltInNSPreference.setSummary(getString(R.string.pref_built_in_ns_not_available));
            disableBuiltInNSPreference.setEnabled(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        SharedPreferences sharedPreferences =
                mSettingsFragment.getPreferenceScreen().getSharedPreferences();
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // clang-format off
        if (key.equals(mKeyPrefResolution)
                || key.equals(mKeyPrefFps)
                || key.equals(mKeyPrefMaxVideoBitrateType)
                || key.equals(mKeyPrefVideoCodec)
                || key.equals(mKeyPrefStartAudioBitrateType)
                || key.equals(mKeyPrefAudioCodec)
                || key.equals(mKeyPrefRoomServerUrl)
                || key.equals(mKeyPrefMaxRetransmitTimeMs)
                || key.equals(mKeyPrefMaxRetransmits)
                || key.equals(mKeyPrefDataProtocol)
                || key.equals(mKeyPrefDataId)) {
            updateSummary(sharedPreferences, key);
        } else if (key.equals(mKeyPrefMaxVideoBitrateValue)
                || key.equals(mKeyPrefStartAudioBitrateValue)) {
            updateSummaryBitrate(sharedPreferences, key);
        } else if (key.equals(mKeyPrefVideoCall)
                || key.equals(mKeyPrefScreencapture)
                || key.equals(mKeyPrefCamera2)
                || key.equals(mKeyPrefTracing)
                || key.equals(mKeyPrefCaptureQualitySlider)
                || key.equals(mKeyPrefHwCodec)
                || key.equals(mKeyPrefCaptureToTexture)
                || key.equals(mKeyPrefFlexfec)
                || key.equals(mKeyPrefNoAudioProcessing)
                || key.equals(mKeyPrefAecDump)
                || key.equals(mKeyPrefOpenSLES)
                || key.equals(mKeyPrefDisableBuiltInAEC)
                || key.equals(mKeyPrefDisableBuiltInAGC)
                || key.equals(mKeyPrefDisableBuiltInNS)
                || key.equals(mKeyPrefEnableLevelControl)
                || key.equals(mKeyPrefDisableWebRtcAGCAndHPF)
                || key.equals(mKeyPrefDisplayHud)
                || key.equals(mKeyPrefEnableDataChannel)
                || key.equals(mKeyPrefOrdered)
                || key.equals(mKeyPrefNegotiated)) {
            updateSummaryB(sharedPreferences, key);
        } else if (key.equals(mKeyPrefSpeakerphone)) {
            updateSummaryList(sharedPreferences, key);
        }
        // clang-format on
        if (key.equals(mKeyPrefMaxVideoBitrateType)) {
            setVideoBitrateEnable(sharedPreferences);
        }
        if (key.equals(mKeyPrefStartAudioBitrateType)) {
            setAudioBitrateEnable(sharedPreferences);
        }
        if (key.equals(mKeyPrefEnableDataChannel)) {
            setDataChannelEnable(sharedPreferences);
        }
    }

    private void updateSummary(SharedPreferences sharedPreferences, String key) {
        Preference updatedPref = mSettingsFragment.findPreference(key);
        // Set summary to be the user-description for the selected value
        updatedPref.setSummary(sharedPreferences.getString(key, ""));
    }

    private void updateSummaryBitrate(SharedPreferences sharedPreferences, String key) {
        Preference updatedPref = mSettingsFragment.findPreference(key);
        updatedPref.setSummary(sharedPreferences.getString(key, "") + " kbps");
    }

    private void updateSummaryB(SharedPreferences sharedPreferences, String key) {
        Preference updatedPref = mSettingsFragment.findPreference(key);
        updatedPref.setSummary(sharedPreferences.getBoolean(key, true)
                ? getString(R.string.pref_value_enabled)
                : getString(R.string.pref_value_disabled));
    }

    private void updateSummaryList(SharedPreferences sharedPreferences, String key) {
        ListPreference updatedPref = (ListPreference) mSettingsFragment.findPreference(key);
        updatedPref.setSummary(updatedPref.getEntry());
    }

    private void setVideoBitrateEnable(SharedPreferences sharedPreferences) {
        Preference bitratePreferenceValue =
                mSettingsFragment.findPreference(mKeyPrefMaxVideoBitrateValue);
        String bitrateTypeDefault = getString(R.string.pref_maxvideobitrate_default);
        String bitrateType =
                sharedPreferences.getString(mKeyPrefMaxVideoBitrateType, bitrateTypeDefault);
        if (bitrateType.equals(bitrateTypeDefault)) {
            bitratePreferenceValue.setEnabled(false);
        } else {
            bitratePreferenceValue.setEnabled(true);
        }
    }

    private void setAudioBitrateEnable(SharedPreferences sharedPreferences) {
        Preference bitratePreferenceValue =
                mSettingsFragment.findPreference(mKeyPrefStartAudioBitrateValue);
        String bitrateTypeDefault = getString(R.string.pref_startaudiobitrate_default);
        String bitrateType =
                sharedPreferences.getString(mKeyPrefStartAudioBitrateType, bitrateTypeDefault);
        if (bitrateType.equals(bitrateTypeDefault)) {
            bitratePreferenceValue.setEnabled(false);
        } else {
            bitratePreferenceValue.setEnabled(true);
        }
    }

    private void setDataChannelEnable(SharedPreferences sharedPreferences) {
        boolean enabled = sharedPreferences.getBoolean(mKeyPrefEnableDataChannel, true);
        mSettingsFragment.findPreference(mKeyPrefOrdered).setEnabled(enabled);
        mSettingsFragment.findPreference(mKeyPrefMaxRetransmitTimeMs).setEnabled(enabled);
        mSettingsFragment.findPreference(mKeyPrefMaxRetransmits).setEnabled(enabled);
        mSettingsFragment.findPreference(mKeyPrefDataProtocol).setEnabled(enabled);
        mSettingsFragment.findPreference(mKeyPrefNegotiated).setEnabled(enabled);
        mSettingsFragment.findPreference(mKeyPrefDataId).setEnabled(enabled);
    }
}
