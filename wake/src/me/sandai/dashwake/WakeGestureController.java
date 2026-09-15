/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashwake;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.SensorPrivacyManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import me.sandai.dashwake.aov.IDashAovBridge;
import me.sandai.dashwake.aov.IDashAovCallback;

/** Process-lifetime wake gestures. Display content and authentication belong to SystemUI. */
final class WakeGestureController implements SensorEventListener {
    private static final String TAG = "DashWake";
    private static final int PICKUP_SENSOR_TYPE = 33171036;
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String DOZE_PULSE_ACTION = "com.android.systemui.doze.pulse";
    // Stock-style polling: arm AOV for a short window every period instead of
    // streaming continuously. 5 s matches the stock smart-AOD re-check cadence.
    private static final long GAZE_POLL_WINDOW_MS = 3_000;
    private static final long GAZE_POLL_PERIOD_MS = 5_000;
    private static final ComponentName AOV_BRIDGE = new ComponentName(
            "me.sandai.dashwake", "me.sandai.dashwake.aov.DashAovBridgeService");

    private final Context mContext;
    private final Handler mHandler;
    private final PowerManager mPowerManager;
    private final SensorManager mSensorManager;
    private final SensorPrivacyManager mPrivacyManager;
    private final DisplayManager mDisplayManager;
    private final Sensor mPickupSensor;
    private final KeyguardManager mKeyguardManager;
    private boolean mPickupRegistered;
    private boolean mWokeByPickup;
    private AovConnection mAovConnection;

    WakeGestureController(Context context) {
        mContext = context;
        mHandler = new Handler(context.getMainLooper());
        mPowerManager = context.getSystemService(PowerManager.class);
        mSensorManager = context.getSystemService(SensorManager.class);
        mPrivacyManager = context.getSystemService(SensorPrivacyManager.class);
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mPickupSensor = mSensorManager.getDefaultSensor(PICKUP_SENSOR_TYPE, true);
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
    }

    void start() {
        WakeSettings.migrate(mContext.getContentResolver(), ActivityManager.getCurrentUser());
        ContentObserver settingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                update();
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(WakeSettings.PICKUP_ENABLED),
                false, settingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(WakeSettings.GAZE_ENABLED),
                false, settingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(WakeSettings.PUTDOWN_ENABLED),
                false, settingsObserver, UserHandle.USER_ALL);
        mPrivacyManager.addSensorPrivacyListener(SensorPrivacyManager.Sensors.CAMERA,
                (sensor, enabled) -> update());
        mDisplayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {}

            @Override
            public void onDisplayRemoved(int displayId) {}

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY) update();
            }
        }, mHandler);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    mWokeByPickup = false;
                } else if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                    mWokeByPickup = false;
                    stopAov();
                    WakeSettings.migrate(mContext.getContentResolver(),
                            ActivityManager.getCurrentUser());
                }
                update();
            }
        }, filter, Context.RECEIVER_EXPORTED);
        update();
    }

    private boolean canWake() {
        return !mPowerManager.isInteractive();
    }

    private boolean isPickupEnabled() {
        return WakeSettings.isEnabled(mContext.getContentResolver(),
                WakeSettings.PICKUP_ENABLED, ActivityManager.getCurrentUser());
    }

    private boolean isPutDownEnabled() {
        return WakeSettings.isEnabled(mContext.getContentResolver(),
                WakeSettings.PUTDOWN_ENABLED, ActivityManager.getCurrentUser());
    }

    // Stock registration window: listen while the screen is off so a lift can
    // wake, and after a pickup wake keep listening while the keyguard is still
    // up so a put-down can end the session. Unlocking unregisters the sensor.
    private boolean shouldListenPickup() {
        if (canWake()) {
            return isPickupEnabled();
        }
        return mWokeByPickup && isPutDownEnabled() && mKeyguardManager.isKeyguardLocked();
    }

    private boolean canDetectGaze() {
        if (!canWake() || !WakeSettings.isEnabled(mContext.getContentResolver(),
                WakeSettings.GAZE_ENABLED, ActivityManager.getCurrentUser())
                || mPrivacyManager.areAnySensorPrivacyTogglesEnabled(
                        SensorPrivacyManager.Sensors.CAMERA)) {
            return false;
        }
        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        int state = display == null ? Display.STATE_UNKNOWN : display.getState();
        return state == Display.STATE_OFF || state == Display.STATE_DOZE
                || state == Display.STATE_DOZE_SUSPEND;
    }

    private void update() {
        boolean pickup = mPickupSensor != null && shouldListenPickup();
        if (pickup && !mPickupRegistered) {
            mPickupRegistered = mSensorManager.registerListener(
                    this, mPickupSensor, SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            Log.i(TAG, "pickup registered=" + mPickupRegistered);
        } else if (!pickup && mPickupRegistered) {
            mSensorManager.unregisterListener(this, mPickupSensor);
            mPickupRegistered = false;
            Log.i(TAG, "pickup registered=false");
        }
        if (canDetectGaze()) {
            if (mAovConnection == null) {
                AovConnection connection = new AovConnection();
                mAovConnection = connection;
                if (!mContext.bindService(new Intent().setComponent(AOV_BRIDGE), connection,
                        Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT)) {
                    mAovConnection = null;
                    Log.e(TAG, "Unable to bind AOV source");
                }
            }
        } else {
            stopAov();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!mPickupRegistered || event.sensor != mPickupSensor || event.values.length == 0) {
            return;
        }
        float value = event.values[0];
        if (value == 1.0f && canWake() && isPickupEnabled()) {
            wake("pickup");
        } else if ((value == 2.0f || value == 0.0f) && mWokeByPickup && !canWake()
                && isPutDownEnabled() && mKeyguardManager.isKeyguardLocked()) {
            Log.i(TAG, "Put down after pickup wake; going to sleep");
            mWokeByPickup = false;
            mPowerManager.goToSleep(SystemClock.uptimeMillis());
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void wake(String source) {
        if (!canWake()) return;
        Log.i(TAG, "Wake source=" + source);
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE,
                "me.sandai.dashwake:" + source);
        if ("pickup".equals(source)) {
            mWokeByPickup = true;
        }
        update();
    }

    // Gaze never wakes the device; it only lights the ambient display through
    // SystemUI's exported pulse trigger, like the stock smart-AOD mode.
    private void pulse() {
        Log.i(TAG, "Gaze pulse");
        mContext.sendBroadcast(new Intent(DOZE_PULSE_ACTION).setPackage(SYSTEMUI_PACKAGE));
    }

    private void scheduleNextPoll() {
        mHandler.postDelayed(mPollTask, GAZE_POLL_PERIOD_MS);
    }

    private final Runnable mPollTask = () -> {
        AovConnection connection = mAovConnection;
        if (connection == null || connection.bridge == null || connection.callback == null
                || !canDetectGaze()) {
            return;
        }
        try {
            connection.bridge.start(connection.callback);
            mHandler.postDelayed(mPollTimeout, GAZE_POLL_WINDOW_MS);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to start AOV poll", e);
            scheduleNextPoll();
        }
    };

    private final Runnable mPollTimeout = () -> {
        AovConnection connection = mAovConnection;
        if (connection != null && connection.bridge != null) {
            try {
                connection.bridge.stop();
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to stop AOV poll", e);
            }
        }
        scheduleNextPoll();
    };

    private void stopAov() {
        mHandler.removeCallbacks(mPollTask);
        mHandler.removeCallbacks(mPollTimeout);
        AovConnection connection = mAovConnection;
        if (connection == null) return;
        mAovConnection = null;
        if (connection.bridge != null) {
            try {
                connection.bridge.stop();
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to stop AOV source", e);
            }
        }
        mContext.unbindService(connection);
    }

    private final class AovConnection implements ServiceConnection {
        IDashAovBridge bridge;
        IDashAovCallback callback;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (mAovConnection != this) return;
            bridge = IDashAovBridge.Stub.asInterface(service);
            if (!canDetectGaze()) {
                stopAov();
                return;
            }
            callback = new IDashAovCallback.Stub() {
                @Override
                public void onPresenceDetected() {
                    mHandler.post(() -> {
                        if (mAovConnection != AovConnection.this || callback != this) {
                            return;
                        }
                        mHandler.removeCallbacks(mPollTimeout);
                        if (canDetectGaze()) {
                            pulse();
                            scheduleNextPoll();
                        }
                    });
                }
            };
            mPollTask.run();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bridge = null;
            callback = null;
        }

        @Override
        public void onBindingDied(ComponentName name) {
            if (mAovConnection != this) return;
            stopAov();
            update();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            if (mAovConnection == this) stopAov();
        }
    }
}
