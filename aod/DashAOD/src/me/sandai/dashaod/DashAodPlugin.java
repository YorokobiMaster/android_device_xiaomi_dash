/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashaod;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.SensorPrivacyManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import com.android.systemui.plugins.DozeServicePlugin;
import com.android.systemui.plugins.annotations.Requires;

import me.sandai.dashaov.IDashAovBridge;
import me.sandai.dashaov.IDashAovCallback;

/** Owns dash-specific Doze triggers; AOV and pickup only supply events. */
@Requires(target = DozeServicePlugin.class, version = DozeServicePlugin.VERSION)
public final class DashAodPlugin implements DozeServicePlugin, SensorEventListener {
    private static final String TAG = "DashAOD";
    private static final String DOZE_PULSE_ACTION = "com.android.systemui.doze.pulse";
    private static final int PICKUP_SENSOR_TYPE = 33171036;
    private static final float PICKUP_RAISE = 1.0f;
    private static final long AOV_REARM_MS = 7_000;
    private static final long DISPLAY_RECHECK_MS = 500;
    private static final ComponentName AOV_BRIDGE = new ComponentName(
            "me.sandai.dashaov", "me.sandai.dashaov.DashAovBridgeService");

    private Context mSysuiContext;
    private Context mPluginContext;
    private Handler mHandler;
    private SensorManager mSensorManager;
    private SensorPrivacyManager mPrivacyManager;
    private Sensor mPickupSensor;
    private IDashAovBridge mAovBridge;
    private boolean mDreaming;
    private boolean mPickupRegistered;
    private boolean mAovBound;

    private final IDashAovCallback mAovCallback = new IDashAovCallback.Stub() {
        @Override
        public void onPresenceDetected() {
            if (mHandler != null) mHandler.post(DashAodPlugin.this::onAovPresence);
        }
    };

    private final SensorPrivacyManager.OnSensorPrivacyChangedListener mPrivacyListener =
            (sensor, enabled) -> {
                if (sensor == SensorPrivacyManager.Sensors.CAMERA && mHandler != null) {
                    mHandler.post(this::onCameraPrivacyChanged);
                }
            };

    private final ServiceConnection mAovConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mAovBridge = IDashAovBridge.Stub.asInterface(service);
            scheduleAovStart();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mAovBridge = null;
        }

        @Override
        public void onBindingDied(ComponentName name) {
            mAovBridge = null;
            unbindAov();
            if (mDreaming) bindAov();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            mAovBridge = null;
            unbindAov();
        }
    };

    private final Runnable mStartAov = new Runnable() {
        @Override
        public void run() {
            if (!mDreaming || mAovBridge == null || isCameraBlocked()) return;
            if (getDisplayState() != Display.STATE_OFF) {
                mHandler.postDelayed(this, DISPLAY_RECHECK_MS);
                return;
            }
            try {
                mAovBridge.start(mAovCallback);
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to start AOV source", e);
            }
        }
    };

    private final Runnable mRearmAov = () -> {
        if (mDreaming) scheduleAovStart();
    };

    @Override
    public void onCreate(Context sysuiContext, Context pluginContext) {
        mSysuiContext = sysuiContext;
        mPluginContext = pluginContext;
        mHandler = new Handler(sysuiContext.getMainLooper());
        mSensorManager = sysuiContext.getSystemService(SensorManager.class);
        mPrivacyManager = sysuiContext.getSystemService(SensorPrivacyManager.class);
        if (mSensorManager != null) {
            for (Sensor sensor : mSensorManager.getSensorList(Sensor.TYPE_ALL)) {
                if (sensor.getType() == PICKUP_SENSOR_TYPE) {
                    mPickupSensor = sensor;
                    break;
                }
            }
        }
        if (mPrivacyManager != null) {
            mPrivacyManager.addSensorPrivacyListener(
                    SensorPrivacyManager.Sensors.CAMERA, mPrivacyListener);
        }
    }

    @Override
    public void onDestroy() {
        onDreamingStopped();
        if (mPrivacyManager != null) {
            mPrivacyManager.removeSensorPrivacyListener(
                    SensorPrivacyManager.Sensors.CAMERA, mPrivacyListener);
        }
        mPrivacyManager = null;
        mSensorManager = null;
        mPickupSensor = null;
        mHandler = null;
        mPluginContext = null;
        mSysuiContext = null;
    }

    @Override
    public void setDozeRequester(RequestDoze requester) {
        if (!mDreaming) onDreamingStarted();
    }

    @Override
    public void onDreamingStarted() {
        if (mDreaming) return;
        mDreaming = true;
        registerPickup();
        bindAov();
        scheduleAovStart();
    }

    @Override
    public void onDreamingStopped() {
        if (!mDreaming) return;
        mDreaming = false;
        if (mHandler != null) {
            mHandler.removeCallbacks(mStartAov);
            mHandler.removeCallbacks(mRearmAov);
        }
        unregisterPickup();
        stopAov();
        unbindAov();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!mDreaming || event.sensor != mPickupSensor || event.values.length == 0
                || event.values[0] != PICKUP_RAISE) return;
        if (Settings.Secure.getInt(mSysuiContext.getContentResolver(),
                Settings.Secure.DOZE_PICK_UP_GESTURE, 1) != 0) {
            pulse("pickup");
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void registerPickup() {
        if (!mPickupRegistered && mSensorManager != null && mPickupSensor != null) {
            mPickupRegistered = mSensorManager.registerListener(
                    this, mPickupSensor, SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            Log.i(TAG, "pickup registered=" + mPickupRegistered);
        }
    }

    private void unregisterPickup() {
        if (!mPickupRegistered) return;
        mSensorManager.unregisterListener(this, mPickupSensor);
        mPickupRegistered = false;
    }

    private void onAovPresence() {
        if (!mDreaming || isCameraBlocked()) return;
        mHandler.removeCallbacks(mStartAov);
        mHandler.removeCallbacks(mRearmAov);
        pulse("gaze");
        mHandler.postDelayed(mRearmAov, AOV_REARM_MS);
    }

    private void pulse(String source) {
        if (mSysuiContext == null) return;
        Log.i(TAG, "Doze pulse source=" + source);
        mSysuiContext.sendBroadcast(new Intent(DOZE_PULSE_ACTION)
                .setPackage(mSysuiContext.getPackageName()));
    }

    private void onCameraPrivacyChanged() {
        if (isCameraBlocked()) {
            mHandler.removeCallbacks(mStartAov);
            stopAov();
        } else {
            scheduleAovStart();
        }
    }

    private void scheduleAovStart() {
        if (mHandler == null || !mDreaming || mAovBridge == null) return;
        mHandler.removeCallbacks(mStartAov);
        mHandler.post(mStartAov);
    }

    private void bindAov() {
        if (mAovBound || mPluginContext == null) return;
        try {
            mAovBound = mPluginContext.bindService(new Intent().setComponent(AOV_BRIDGE),
                    mAovConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            if (!mAovBound) Log.e(TAG, "Unable to bind AOV source");
        } catch (SecurityException e) {
            Log.e(TAG, "Unable to bind AOV source", e);
        }
    }

    private void unbindAov() {
        if (!mAovBound || mPluginContext == null) return;
        try {
            mPluginContext.unbindService(mAovConnection);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "AOV source already unbound", e);
        }
        mAovBound = false;
        mAovBridge = null;
    }

    private void stopAov() {
        if (mAovBridge == null) return;
        try {
            mAovBridge.stop();
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to stop AOV source", e);
        }
    }

    private boolean isCameraBlocked() {
        return mPrivacyManager != null && mPrivacyManager.areAnySensorPrivacyTogglesEnabled(
                SensorPrivacyManager.Sensors.CAMERA);
    }

    private int getDisplayState() {
        DisplayManager manager = mSysuiContext == null ? null
                : mSysuiContext.getSystemService(DisplayManager.class);
        Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
        return display == null ? Display.STATE_UNKNOWN : display.getState();
    }
}
