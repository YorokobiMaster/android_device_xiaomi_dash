/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashaov;

import android.content.ContentResolver;
import android.provider.Settings;

final class SmartAodSettings {
    static final String ENABLED = "dash_smart_aod_enabled";

    private SmartAodSettings() {}

    static boolean isEnabled(ContentResolver resolver) {
        return Settings.Secure.getInt(resolver, ENABLED, 0) != 0;
    }

    static boolean isAlwaysOnEnabled(ContentResolver resolver) {
        return Settings.Secure.getInt(resolver, Settings.Secure.DOZE_ALWAYS_ON, 0) != 0;
    }

    static void setEnabled(ContentResolver resolver, boolean enabled) {
        Settings.Secure.putInt(resolver, ENABLED, enabled ? 1 : 0);
    }
}
