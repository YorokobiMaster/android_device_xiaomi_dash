/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashwake;

import android.content.ContentResolver;
import android.provider.Settings;

final class WakeSettings {
    static final String PICKUP_ENABLED = "dash_pickup_wake_enabled";
    static final String GAZE_ENABLED = "dash_gaze_wake_enabled";

    private WakeSettings() {}

    static boolean isEnabled(ContentResolver resolver, String key, int userId) {
        return Settings.Secure.getIntForUser(resolver, key, 1, userId) != 0;
    }

    static boolean setEnabled(ContentResolver resolver, String key, int userId, boolean enabled) {
        return Settings.Secure.putIntForUser(resolver, key, enabled ? 1 : 0, userId);
    }

    static void migrate(ContentResolver resolver, int userId) {
        migrateKey(resolver, userId, Settings.Secure.DOZE_PICK_UP_GESTURE, PICKUP_ENABLED);
        migrateKey(resolver, userId, "dash_smart_aod_enabled", GAZE_ENABLED);
    }

    private static void migrateKey(ContentResolver resolver, int userId,
            String oldKey, String newKey) {
        // Preserve disabled preferences and never overwrite an already migrated choice.
        if (Settings.Secure.getStringForUser(resolver, newKey, userId) == null) {
            int value = Settings.Secure.getIntForUser(resolver, oldKey, 1, userId);
            if (!Settings.Secure.putIntForUser(resolver, newKey, value, userId)) return;
        }
        if (Settings.Secure.getStringForUser(resolver, oldKey, userId) != null) {
            Settings.Secure.putStringForUser(resolver, oldKey, null, userId);
        }
    }
}
