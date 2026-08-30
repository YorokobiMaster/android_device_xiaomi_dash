/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.SensorPrivacyManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;

import com.android.systemui.plugins.DozeServicePlugin;
import com.android.systemui.plugins.annotations.Requires;

/** Uses the retained MediaTek AOV stack to show AOD briefly when the user looks at dash. */
@Requires(target = DozeServicePlugin.class, version = DozeServicePlugin.VERSION)
public final class DashAovPlugin implements DozeServicePlugin {
    private static final String TAG = "DashAOV.Plugin";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String DOZE_PULSE_ACTION = "com.android.systemui.doze.pulse";
    private static final ComponentName BRIDGE_COMPONENT = new ComponentName(
            "me.sandai", "me.sandai.DashAovBridgeService");
    private static final long AOV_REARM_MS = 7_000;
    private static final long DISPLAY_RECHECK_MS = 500;

    private Context mSysuiContext;
    private Context mPluginContext;
    private Handler mHandler;
    private RequestDoze mDozeRequester;
    private IDashAovBridge mBridge;
    private SensorPrivacyManager mPrivacyManager;
    private boolean mDreaming;
    private boolean mBound;

    private final IDashAovCallback mCallback = new IDashAovCallback.Stub() {
        @Override
        public void onPresenceDetected() {
            Handler handler = mHandler;
            if (handler != null) handler.post(DashAovPlugin.this::showAod);
        }
    };

    private final SensorPrivacyManager.OnSensorPrivacyChangedListener mPrivacyListener =
            (sensor, enabled) -> {
                Handler handler = mHandler;
                if (sensor == SensorPrivacyManager.Sensors.CAMERA && handler != null) {
                    handler.post(this::onCameraPrivacyChanged);
                }
            };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBridge = IDashAovBridge.Stub.asInterface(service);
            scheduleAovStart();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBridge = null;
        }

        @Override
        public void onBindingDied(ComponentName name) {
            mBridge = null;
            unbindBridge();
            if (mDreaming) bindBridge();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.e(TAG, "DashAOV bridge returned a null binding");
            mBridge = null;
            unbindBridge();
        }
    };

    private final Runnable mStartAov = new Runnable() {
        @Override
        public void run() {
            if (!mDreaming || mBridge == null || isCameraBlocked()) return;
            if (getDisplayState() != Display.STATE_OFF) {
                mHandler.postDelayed(this, DISPLAY_RECHECK_MS);
                return;
            }
            try {
                mBridge.start(mCallback);
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to start DashAOV bridge", e);
            }
        }
    };

    private final Runnable mRearmAov = () -> {
        if (!mDreaming) return;
        scheduleAovStart();
    };

    @Override
    public void onCreate(Context sysuiContext, Context pluginContext) {
        mSysuiContext = sysuiContext;
        mPluginContext = pluginContext;
        mHandler = new Handler(sysuiContext.getMainLooper());
        mPrivacyManager = sysuiContext.getSystemService(SensorPrivacyManager.class);
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
        mDozeRequester = null;
        mHandler = null;
        mPluginContext = null;
        mSysuiContext = null;
    }

    @Override
    public void setDozeRequester(RequestDoze requester) {
        mDozeRequester = requester;
        // DozeService does not replay onDreamingStarted() when this plugin connects late.
        // Pre-arm here; mStartAov still waits until the display is fully off.
        if (!mDreaming) onDreamingStarted();
    }

    @Override
    public void onDreamingStarted() {
        mDreaming = true;
        bindBridge();
        scheduleAovStart();
    }

    @Override
    public void onDreamingStopped() {
        mDreaming = false;
        Handler handler = mHandler;
        if (handler != null) {
            handler.removeCallbacks(mStartAov);
            handler.removeCallbacks(mRearmAov);
        }
        stopBridge();
        unbindBridge();
    }

    private void showAod() {
        if (!mDreaming || mDozeRequester == null || mSysuiContext == null
                || isCameraBlocked()) return;
        mHandler.removeCallbacks(mStartAov);
        mHandler.removeCallbacks(mRearmAov);
        Log.i(TAG, "Requesting gaze-triggered Doze pulse");
        mSysuiContext.sendBroadcast(new Intent(DOZE_PULSE_ACTION).setPackage(SYSTEM_UI_PACKAGE));
        mHandler.postDelayed(mRearmAov, AOV_REARM_MS);
    }

    private void onCameraPrivacyChanged() {
        if (isCameraBlocked()) {
            mHandler.removeCallbacks(mStartAov);
            stopBridge();
        } else {
            scheduleAovStart();
        }
    }

    private void scheduleAovStart() {
        Handler handler = mHandler;
        if (handler == null || !mDreaming || mBridge == null) return;
        handler.removeCallbacks(mStartAov);
        handler.post(mStartAov);
    }

    private void bindBridge() {
        if (mBound || mPluginContext == null) return;
        Intent intent = new Intent().setComponent(BRIDGE_COMPONENT);
        try {
            mBound = mPluginContext.bindService(intent, mConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            if (!mBound) Log.e(TAG, "Unable to bind DashAOV bridge");
        } catch (SecurityException e) {
            Log.e(TAG, "SystemUI cannot bind DashAOV bridge", e);
        }
    }

    private void unbindBridge() {
        if (!mBound || mPluginContext == null) return;
        try {
            mPluginContext.unbindService(mConnection);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "DashAOV bridge was already unbound", e);
        }
        mBound = false;
        mBridge = null;
    }

    private void stopBridge() {
        IDashAovBridge bridge = mBridge;
        if (bridge == null) return;
        try {
            bridge.stop();
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to stop DashAOV bridge", e);
        }
    }

    private boolean isCameraBlocked() {
        return mPrivacyManager != null && mPrivacyManager.areAnySensorPrivacyTogglesEnabled(
                SensorPrivacyManager.Sensors.CAMERA);
    }

    private int getDisplayState() {
        if (mSysuiContext == null) return Display.STATE_UNKNOWN;
        DisplayManager displayManager = mSysuiContext.getSystemService(DisplayManager.class);
        Display display = displayManager == null
                ? null : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        return display == null ? Display.STATE_UNKNOWN : display.getState();
    }
}
