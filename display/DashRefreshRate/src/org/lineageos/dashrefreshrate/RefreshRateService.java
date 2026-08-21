/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.dashrefreshrate;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

public final class RefreshRateService extends Service
        implements DisplayManager.DisplayListener, RefreshRatePolicy.Store {
    private static final String TAG = "DashRefreshRate";
    private static final long RETRY_DELAY_MILLIS = 1000;
    private static final String PREFERENCES = "refresh_rate_state";
    private static final String KEY_OVERRIDE_ACTIVE = "doze_override_active";
    private static final String KEY_AWAKE_MIN_REFRESH_RATE = "awake_min_refresh_rate";
    private static final String KEY_AWAKE_PEAK_REFRESH_RATE = "awake_peak_refresh_rate";
    private static final String NULL_SETTING = "";

    private DisplayManager mDisplayManager;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private SharedPreferences mPreferences;
    private RefreshRatePolicy mPolicy;
    private DisplayFeatureClient mDisplayFeatureClient;
    private RefreshRatePolicy.DisplayState mLastDisplayState;
    private boolean mRetryScheduled;
    private final Runnable mRetrySync = () -> {
        mRetryScheduled = false;
        syncDefaultDisplay(true);
    };

    @Override
    public void onCreate() {
        super.onCreate();

        mPreferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        mPolicy = new RefreshRatePolicy(this, message -> Log.i(TAG, message));
        mDisplayFeatureClient = new DisplayFeatureClient();
        mDisplayManager = getSystemService(DisplayManager.class);

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mDisplayManager.registerDisplayListener(this, mHandler);
        mHandler.post(() -> syncDefaultDisplay(true));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mHandler.post(() -> syncDefaultDisplay(true));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mDisplayManager.unregisterDisplayListener(this);
        mHandler.removeCallbacks(mRetrySync);
        mHandlerThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDisplayAdded(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            syncDefaultDisplay(false);
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            syncDefaultDisplay(false);
        }
    }

    @Override
    public void onDisplayRemoved(int displayId) {}

    private void syncDefaultDisplay(boolean force) {
        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            Log.w(TAG, "default display unavailable");
            return;
        }

        int state = display.getState();
        RefreshRatePolicy.DisplayState policyState;
        if (state == Display.STATE_ON) {
            policyState = RefreshRatePolicy.DisplayState.AWAKE;
        } else if (state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND) {
            policyState = RefreshRatePolicy.DisplayState.DOZE;
        } else {
            policyState = RefreshRatePolicy.DisplayState.OTHER;
        }
        if (!force && policyState == mLastDisplayState) {
            return;
        }
        mLastDisplayState = policyState;
        mPolicy.onDisplayState(policyState);
    }

    @Override
    public String readMinRefreshRate() {
        return Settings.System.getString(
                getContentResolver(), Settings.System.MIN_REFRESH_RATE);
    }

    @Override
    public boolean writeMinRefreshRate(String value) {
        boolean success = Settings.System.putString(
                getContentResolver(), Settings.System.MIN_REFRESH_RATE, value);
        if (!success) {
            scheduleSyncRetry();
        }
        return success;
    }

    @Override
    public String readPeakRefreshRate() {
        return Settings.System.getString(
                getContentResolver(), Settings.System.PEAK_REFRESH_RATE);
    }

    @Override
    public boolean writePeakRefreshRate(String value) {
        boolean success = Settings.System.putString(
                getContentResolver(), Settings.System.PEAK_REFRESH_RATE, value);
        if (!success) {
            scheduleSyncRetry();
        }
        return success;
    }

    @Override
    public boolean isDozeOverrideActive() {
        return mPreferences.getBoolean(KEY_OVERRIDE_ACTIVE, false);
    }

    @Override
    public String readAwakeMinRefreshRate() {
        return mPreferences.getString(KEY_AWAKE_MIN_REFRESH_RATE, null);
    }

    @Override
    public String readAwakePeakRefreshRate() {
        String value = mPreferences.getString(KEY_AWAKE_PEAK_REFRESH_RATE, null);
        return NULL_SETTING.equals(value) ? null : value;
    }

    @Override
    public boolean beginDozeOverride(String awakeMinRefreshRate, String awakePeakRefreshRate) {
        return mPreferences.edit()
                .putString(KEY_AWAKE_MIN_REFRESH_RATE, awakeMinRefreshRate)
                .putString(KEY_AWAKE_PEAK_REFRESH_RATE,
                        awakePeakRefreshRate == null ? NULL_SETTING : awakePeakRefreshRate)
                .putBoolean(KEY_OVERRIDE_ACTIVE, true)
                .commit();
    }

    @Override
    public void clearDozeOverride() {
        mPreferences.edit()
                .remove(KEY_AWAKE_MIN_REFRESH_RATE)
                .remove(KEY_AWAKE_PEAK_REFRESH_RATE)
                .remove(KEY_OVERRIDE_ACTIVE)
                .apply();
    }

    @Override
    public boolean setDozeBrightness(int value) {
        boolean success = mDisplayFeatureClient.setDozeBrightness(value);
        if (!success) {
            scheduleSyncRetry();
        }
        return success;
    }

    private void scheduleSyncRetry() {
        if (mRetryScheduled || mHandler == null) {
            return;
        }
        mRetryScheduled = true;
        mHandler.postDelayed(mRetrySync, RETRY_DELAY_MILLIS);
    }
}
