/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dasheyecare;

import android.app.ActivityManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.display.ColorDisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

/** Night Light owns UI, scheduling and persistence; this process only drives the HAL. */
public final class NightLightBridge extends Application {
    private static final String PROPERTY = "sys.dash.livedisplay.eyecare";
    private Handler handler;
    private final Runnable sync = this::syncCurrentUser;

    @Override
    public void onCreate() {
        super.onCreate();
        // One device-global output, even when several users have app processes.
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) return;
        HandlerThread thread = new HandlerThread("NightLightBridge");
        thread.start();
        handler = new Handler(thread.getLooper());
        ContentObserver observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) { scheduleSync(); }
        };
        // Read Settings for the explicit foreground user: ColorDisplayManager's
        // cached active-user state can lag a user-switch or settings notification.
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.NIGHT_DISPLAY_ACTIVATED),
                false, observer, UserHandle.USER_ALL);
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE),
                false, observer, UserHandle.USER_ALL);
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) { scheduleSync(); }
        }, UserHandle.ALL, filter, null, handler, Context.RECEIVER_EXPORTED);
        scheduleSync();
    }

    private void scheduleSync() {
        // Coalesce queued slider updates and cancel a pending failure retry.
        handler.removeCallbacks(sync);
        handler.post(sync);
    }

    private void syncCurrentUser() {
        try {
            int user = ActivityManager.getCurrentUser();
            boolean enabled = Settings.Secure.getIntForUser(getContentResolver(),
                    Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 0, user) != 0;
            int defaultTemperature = getResources().getInteger(
                    com.android.internal.R.integer.config_nightDisplayColorTemperatureDefault);
            int temperature = Settings.Secure.getIntForUser(getContentResolver(),
                    Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE, defaultTemperature, user);
            int level = NightLightLevel.fromTemperature(enabled, temperature,
                    ColorDisplayManager.getMinimumColorTemperature(this), defaultTemperature,
                    ColorDisplayManager.getMaximumColorTemperature(this));
            String requested = Integer.toString(level);
            if (!requested.equals(SystemProperties.get(PROPERTY))) {
                SystemProperties.set(PROPERTY, requested);
            }
        } catch (RuntimeException error) {
            Log.e("NightLightBridge", "Cannot synchronize Night Light", error);
            handler.removeCallbacks(sync);
            handler.postDelayed(sync, 1000);
        }
    }
}
