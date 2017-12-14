package com.pine.rtc.util;

import android.annotation.TargetApi;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import org.webrtc.Logging;

import java.util.Arrays;
import java.util.List;

/**
 * Created by tanghongfeng on 2017/11/28.
 */

@TargetApi(19)
public class MediaCodecVideoEncoderUtil {
    private static final String TAG = "MediaCodecVideoEncoderUtil";

    public static final int[] SUPPORTED_COLOR_LIST;
    public static final int[] SUPPORTED_SURFACE_COLOR_LIST;
    private static final MediaCodecVideoEncoderUtil.MediaCodecProperties QCOM_H264HW_PROPERTIES;
    private static final MediaCodecVideoEncoderUtil.MediaCodecProperties IMG_H264HW_PROPERTIES;
    private static final MediaCodecVideoEncoderUtil.MediaCodecProperties GOOGLE_H264HW_PROPERTIES;
    private static final MediaCodecVideoEncoderUtil.MediaCodecProperties MTK_H264HW_PROPERTIES;
    private static final MediaCodecVideoEncoderUtil.MediaCodecProperties EXYNOS_H264HW_PROPERTIES;
    public static final MediaCodecVideoEncoderUtil.MediaCodecProperties[] H264HW_LIST;
    private static final String[] H264_HW_EXCEPTION_MODELS =
            new String[]{"SAMSUNG-SGH-I337", "Nexus 7", "Nexus 4"};

    static {
        SUPPORTED_COLOR_LIST = new int[]{19, 21, 2141391872, 2141391876};
        SUPPORTED_SURFACE_COLOR_LIST = new int[]{2130708361};
        QCOM_H264HW_PROPERTIES = new MediaCodecVideoEncoderUtil.MediaCodecProperties("OMX.qcom.",
                19, MediaCodecVideoEncoderUtil.BitrateAdjustmentType.NO_ADJUSTMENT);
        IMG_H264HW_PROPERTIES = new MediaCodecVideoEncoderUtil.MediaCodecProperties("OMX.IMG.",
                19, MediaCodecVideoEncoderUtil.BitrateAdjustmentType.NO_ADJUSTMENT);
        GOOGLE_H264HW_PROPERTIES = new MediaCodecVideoEncoderUtil.MediaCodecProperties("OMX.google.",
                19, MediaCodecVideoEncoderUtil.BitrateAdjustmentType.NO_ADJUSTMENT);
        MTK_H264HW_PROPERTIES = new MediaCodecVideoEncoderUtil.MediaCodecProperties("OMX.MTK.",
                19, MediaCodecVideoEncoderUtil.BitrateAdjustmentType.NO_ADJUSTMENT);
        EXYNOS_H264HW_PROPERTIES = new MediaCodecVideoEncoderUtil.MediaCodecProperties("OMX.Exynos.",
                21, MediaCodecVideoEncoderUtil.BitrateAdjustmentType.FRAMERATE_ADJUSTMENT);
        H264HW_LIST = new MediaCodecVideoEncoderUtil.MediaCodecProperties[]{QCOM_H264HW_PROPERTIES,
                EXYNOS_H264HW_PROPERTIES, MTK_H264HW_PROPERTIES, IMG_H264HW_PROPERTIES, GOOGLE_H264HW_PROPERTIES};
    }

    public static boolean isDeviceSupportRecorder(String mimeType) {
        MediaCodecVideoEncoderUtil.EncoderProperties properties = MediaCodecVideoEncoderUtil
                .findColorFormat(mimeType,
                        MediaCodecVideoEncoderUtil.H264HW_LIST,
                        MediaCodecVideoEncoderUtil.SUPPORTED_COLOR_LIST);
        if (properties == null) {
            Logging.d(TAG, "device did not support " + mimeType + " to record");
            return false;
        }
        Logging.d(TAG, "device support " + mimeType + " to record");
        return true;
    }

    public static MediaCodecVideoEncoderUtil.EncoderProperties findColorFormat(
            String mime, MediaCodecVideoEncoderUtil.MediaCodecProperties[] supportedHwCodecProperties,
            int[] colorList) {
        if (Build.VERSION.SDK_INT < 19) {
            return null;
        }
        if (mime.equals("video/avc")) {
            List i = Arrays.asList(H264_HW_EXCEPTION_MODELS);
            if (i.contains(Build.MODEL)) {
                return null;
            }
        }
        MediaCodecInfo mediaCodecInfo = null;
        for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
            mediaCodecInfo = MediaCodecList.getCodecInfoAt(i);
            if (mediaCodecInfo != null && mediaCodecInfo.isEncoder()) {
                String name = null;
                String[] supportedCodec = mediaCodecInfo.getSupportedTypes();
                for (int j = 0; j < supportedCodec.length; j++) {
                    if (supportedCodec[j].equals(mime)) {
                        name = mediaCodecInfo.getName();
                        break;
                    }
                }
                if (name != null) {
                    Logging.d(TAG, "Found candidate decoder " + name);
                    boolean found = false;
                    MediaCodecVideoEncoderUtil.BitrateAdjustmentType bitrateAdjustmentType =
                            MediaCodecVideoEncoderUtil.BitrateAdjustmentType.NO_ADJUSTMENT;
                    for (int k = 0; k < supportedHwCodecProperties.length; k++) {
                        MediaCodecVideoEncoderUtil.MediaCodecProperties codecProperties =
                                supportedHwCodecProperties[k];
                        if (name.startsWith(codecProperties.codecPrefix)) {
                            if (Build.VERSION.SDK_INT >= codecProperties.minSdk) {
                                if (codecProperties.bitrateAdjustmentType !=
                                        MediaCodecVideoEncoderUtil.BitrateAdjustmentType.NO_ADJUSTMENT) {
                                    bitrateAdjustmentType = codecProperties.bitrateAdjustmentType;
                                    Logging.d(TAG, "Codec " + name + " requires bitrate adjustment: "
                                            + bitrateAdjustmentType);
                                }
                                found = true;
                                break;
                            }
                        }
                    }
                    if (found) {
                        MediaCodecInfo.CodecCapabilities codecCapabilities;
                        try {
                            codecCapabilities = mediaCodecInfo.getCapabilitiesForType(mime);
                        } catch (IllegalArgumentException e) {
                            Logging.e(TAG, "Cannot retrieve encoder capabilities", e);
                            continue;
                        }

                        int[] colorFormats = codecCapabilities.colorFormats;
                        int supportedColorFormat;
                        for (int l = 0; l < colorFormats.length; l++) {
                            supportedColorFormat = colorFormats[l];
                            Logging.d(TAG, "   Color: 0x" + Integer.toHexString(supportedColorFormat));
                        }

                        for (int m = 0; m < colorList.length; m++) {
                            supportedColorFormat = colorList[m];
                            int[] tmpColorFormats = codecCapabilities.colorFormats;
                            for (int n = 0; n < tmpColorFormats.length; n++) {
                                int codecColorFormat = tmpColorFormats[n];
                                if (codecColorFormat == supportedColorFormat) {
                                    if (name.startsWith(IMG_H264HW_PROPERTIES.codecPrefix)
                                            || name.startsWith(GOOGLE_H264HW_PROPERTIES.codecPrefix)) {
                                        codecColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
                                    } else if (name.startsWith("OMX.qcom.video.encoder.avc")) {

                                    }
                                    Logging.d(TAG, "Found target encoder for mime " +
                                            mime + " : " + name + ". Color: 0x"
                                            + Integer.toHexString(codecColorFormat)
                                            + ". Bitrate adjustment: " + bitrateAdjustmentType);
                                    return new MediaCodecVideoEncoderUtil.EncoderProperties(name,
                                            codecColorFormat, bitrateAdjustmentType);
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }


    public static enum BitrateAdjustmentType {
        NO_ADJUSTMENT,
        FRAMERATE_ADJUSTMENT,
        DYNAMIC_ADJUSTMENT;

        private BitrateAdjustmentType() {
        }
    }

    public static class EncoderProperties {
        public final String codecName;
        public final int colorFormat;
        public final MediaCodecVideoEncoderUtil.BitrateAdjustmentType bitrateAdjustmentType;

        public EncoderProperties(String codecName, int colorFormat, MediaCodecVideoEncoderUtil.BitrateAdjustmentType bitrateAdjustmentType) {
            this.codecName = codecName;
            this.colorFormat = colorFormat;
            this.bitrateAdjustmentType = bitrateAdjustmentType;
        }
    }

    private static class MediaCodecProperties {
        public final String codecPrefix;
        public final int minSdk;
        public final MediaCodecVideoEncoderUtil.BitrateAdjustmentType bitrateAdjustmentType;

        MediaCodecProperties(String codecPrefix, int minSdk, MediaCodecVideoEncoderUtil.BitrateAdjustmentType bitrateAdjustmentType) {
            this.codecPrefix = codecPrefix;
            this.minSdk = minSdk;
            this.bitrateAdjustmentType = bitrateAdjustmentType;
        }
    }
}
