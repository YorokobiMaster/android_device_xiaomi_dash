/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashcharging;

import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Fast reverse charging control. reverse_quick_charge goes through the MiCharge HAL;
 * revchg_bcl (the tier-gate consent flag) is not exposed by the HAL and is written
 * to sysfs directly. The kernel clears reverse_quick_charge whenever the session or
 * the RQC conditions drop, so all state is read back live and nothing is persisted.
 */
final class ReverseChargingSettings {
    static final String KEY = "dash_fast_reverse_charging";

    private static final String TAG = "DashCharging.ReverseCharging";
    private static final String OTG_ENABLE_PATH = "/sys/class/power_supply/usb/otg_enable";
    private static final String REVCHG_BCL_PATH = "/sys/class/power_supply/usb/revchg_bcl";

    private ReverseChargingSettings() {}

    /** True while this phone is actually sourcing power to an attached device. */
    static boolean isSupplyingDevice() {
        return "1".equals(readNode(OTG_ENABLE_PATH));
    }

    static boolean isEnabled() {
        Boolean enabled = MiChargeClient.isReverseQuickChargeEnabled();
        return enabled != null && enabled;
    }

    static boolean setEnabled(boolean enabled) {
        if (enabled && !isSupplyingDevice()) {
            return false;
        }
        if (enabled && !writeNode(REVCHG_BCL_PATH, "1")) {
            return false;
        }
        if (!MiChargeClient.setReverseQuickCharge(enabled)) {
            if (enabled) {
                writeNode(REVCHG_BCL_PATH, "0");
            }
            return false;
        }
        if (!enabled) {
            writeNode(REVCHG_BCL_PATH, "0");
        }
        return true;
    }

    private static String readNode(String path) {
        byte[] buffer = new byte[16];
        try (FileInputStream in = new FileInputStream(path)) {
            int len = in.read(buffer);
            if (len <= 0) {
                return null;
            }
            return new String(buffer, 0, len, StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Unable to read " + path, e);
            return null;
        }
    }

    private static boolean writeNode(String path, String value) {
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Unable to write " + path, e);
            return false;
        }
    }
}
