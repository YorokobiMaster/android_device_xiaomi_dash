/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashcharging;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.util.Log;

final class BypassChargingSettings {
    static final String ENABLED = "dash_bypass_charging_enabled";

    private static final String TAG = "DashCharging.Settings";

    private BypassChargingSettings() {}

    static boolean isDesired(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), ENABLED, 0) != 0;
    }

    static Boolean getRequestedState() {
        return MiChargeClient.isBypassRequested();
    }

    static boolean isEnabled(Context context) {
        Boolean requested = getRequestedState();
        return requested != null ? requested : isDesired(context);
    }

    static boolean setEnabled(Context context, boolean enabled) {
        if (!MiChargeClient.setBypassRequested(enabled)) {
            return false;
        }
        if (!Settings.Global.putInt(context.getContentResolver(), ENABLED, enabled ? 1 : 0)) {
            Log.e(TAG, "Unable to persist bypass charging state");
            MiChargeClient.setBypassRequested(!enabled);
            return false;
        }
        requestTileUpdate(context);
        return true;
    }

    static void restore(Context context) {
        boolean enabled = isDesired(context);
        if (!MiChargeClient.setBypassRequested(enabled)) {
            Log.e(TAG, "Unable to restore bypass charging state after boot");
        }
        requestTileUpdate(context);
    }

    private static void requestTileUpdate(Context context) {
        TileService.requestListeningState(context, new ComponentName(
                context, BypassChargingTileService.class));
    }
}
