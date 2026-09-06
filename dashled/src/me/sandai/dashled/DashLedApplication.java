/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.app.Application;
import android.content.ComponentName;
import android.provider.Settings;

public class DashLedApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        LedCore.get(this);
        ensureNotificationListenerEnabled();
    }

    private void ensureNotificationListenerEnabled() {
        ComponentName listener = new ComponentName(this, LedNotificationListener.class);
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_NOTIFICATION_LISTENERS);
        String flat = listener.flattenToString();
        if (enabled != null && enabled.contains(flat)) {
            return;
        }
        Settings.Secure.putString(getContentResolver(),
                Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                enabled == null || enabled.isEmpty() ? flat : enabled + ":" + flat);
    }
}
