package com.pine.rtc.util;

import android.os.Build;

/**
 * Created by tanghongfeng on 2018/1/25.
 */

public class DeviceInfoUtil {
    public static boolean isXiaoMi3C() {
        String model = Build.MODEL;
        String brand = Build.BRAND;
        if (brand.equals("Xiaomi") && model.trim().contains("MI 3C")) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean isXiaoMi() {
        String displayStr = Build.DISPLAY;
        String brand = Build.BRAND;

        if ((displayStr != null && displayStr.toLowerCase().contains("miui"))
                || "Xiaomi".equalsIgnoreCase(brand)) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean isMeiZu() {
        String brand = Build.BRAND;
        if ("Meizu".equalsIgnoreCase(brand)) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean isSamsung() {
        String manufacturer = Build.MANUFACTURER;
        int sdkVersion = Build.VERSION.SDK_INT;
        String model = Build.MODEL;
        if ((manufacturer != null && manufacturer.trim().contains("samsung") && sdkVersion >= 9)
                && (model == null || (!model.trim().toLowerCase()
                .contains("google") && !model.trim().toLowerCase()
                .contains("nexus")))) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean isLenovo() {
        String model = Build.MODEL;
        if (model != null && (model.startsWith("Lenovo") || model.toLowerCase().contains("lenovo"))) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean isHUAWEI() {
        return Build.MANUFACTURER.equalsIgnoreCase("huawei") || Build.USER.equalsIgnoreCase("huawei")
                || Build.DEVICE.equalsIgnoreCase("huawei");
    }

    /**
     * 获取设备信息
     */
    public static String getDeviceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Product: " + Build.PRODUCT)
                .append(", CPU_ABI: " + Build.CPU_ABI)
                .append(", TAGS: " + Build.TAGS)
                .append(", VERSION_CODES.BASE: "
                        + Build.VERSION_CODES.BASE)
                .append(", MODEL: " + Build.MODEL)
                .append(", SDK: " + Build.VERSION.SDK)
                .append(", VERSION.RELEASE: " + Build.VERSION.RELEASE)
                .append(", DEVICE: " + Build.DEVICE)
                .append(", DISPLAY: " + Build.DISPLAY)
                .append(", BRAND: " + Build.BRAND)
                .append(", BOARD: " + Build.BOARD)
                .append(", FINGERPRINT: " + Build.FINGERPRINT)
                .append(", ID: " + Build.ID)
                .append(", MANUFACTURER: " + Build.MANUFACTURER)
                .append(", USER: " + Build.USER);
        return sb.toString();
    }
}
