/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashfod;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.OverlayManager;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.SensorProperties;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseBooleanArray;

import com.android.internal.widget.LockPatternUtils;

import java.util.List;

/** Selects PIN geometry from fingerprint eligibility, independently of HAL listening. */
final class PinLayoutController {
    private static final String TAG = "DashPinLayout";
    private static final String LOW_OVERLAY = "me.sandai.overlay.dash.pinlow";
    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final SparseBooleanArray mEnabledForUser = new SparseBooleanArray();
    private final FingerprintManager mFingerprint;
    private final LockPatternUtils mLocks;
    private final LockPatternUtils.StrongAuthTracker mStrongAuth;

    PinLayoutController(Context context) {
        mContext = context;
        mFingerprint = context.getSystemService(FingerprintManager.class);
        mLocks = new LockPatternUtils(context);
        mStrongAuth = new LockPatternUtils.StrongAuthTracker(context, mHandler.getLooper()) {
            @Override
            public void onStrongAuthRequiredChanged(int userId) {
                refresh("strong-auth");
            }

            @Override
            public void onIsNonStrongBiometricAllowedChanged(int userId) {
                refresh("non-strong-auth");
            }
        };
    }

    void start() {
        try {
            mLocks.registerStrongAuthTracker(mStrongAuth);
            mContext.getSystemService(BiometricManager.class).registerEnabledOnKeyguardCallback(
                    new IBiometricEnabledOnKeyguardCallback.Stub() {
                        @Override
                        public void onChanged(boolean enabled, int userId, int modality) {
                            if ((modality & BiometricAuthenticator.TYPE_FINGERPRINT) == 0) return;
                            mHandler.post(() -> {
                                mEnabledForUser.put(userId, enabled);
                                update("enabled-setting");
                            });
                        }
                    });
            mFingerprint.addLockoutResetCallback(new FingerprintManager.LockoutResetCallback() {
                @Override
                public void onLockoutReset(int sensorId) {
                    refresh("lockout-reset");
                }
            });
            mFingerprint.addAuthenticatorsRegisteredCallback(
                    new IFingerprintAuthenticatorsRegisteredCallback.Stub() {
                        @Override
                        public void onAllAuthenticatorsRegistered(
                                List<FingerprintSensorPropertiesInternal> sensors) {
                            refresh("sensors-ready");
                        }
                    });
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_SWITCHED);
            filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    refresh(intent.getAction());
                }
            }, filter, null, mHandler);
            refresh("startup");
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot register PIN layout state listeners", e);
        }
    }

    void refresh(String reason) {
        mHandler.post(() -> update(reason));
    }

    private void update(String reason) {
        try {
            int userId = ActivityManager.getCurrentUser();
            boolean enrolled = mFingerprint.hasEnrolledFingerprints(userId);
            boolean enabled = mEnabledForUser.get(userId);
            int strongAuth = mLocks.getStrongAuthForUser(userId);
            boolean authAllowed = mLocks.isBiometricAllowedForUser(userId);
            boolean policyAllowed = (mContext.getSystemService(DevicePolicyManager.class)
                    .getKeyguardDisabledFeatures(null, userId)
                    & DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT) == 0;
            boolean sensorAllowed = false;
            for (FingerprintSensorPropertiesInternal sensor
                    : mFingerprint.getSensorPropertiesInternal()) {
                if (!sensor.isAnyUdfpsType()) continue;
                boolean strengthAllowed = sensor.sensorStrength == SensorProperties.STRENGTH_STRONG
                        || mStrongAuth.isNonStrongBiometricAllowedAfterIdleTimeout(userId);
                sensorAllowed |= strengthAllowed
                        && mFingerprint.getLockoutModeForUser(sensor.sensorId, userId) == 0;
            }
            boolean high = enrolled && enabled && authAllowed && policyAllowed && sensorAllowed;
            OverlayManager overlays = mContext.getSystemService(OverlayManager.class);
            UserHandle user = UserHandle.of(userId);
            var info = overlays.getOverlayInfo(LOW_OVERLAY, user);
            if (info == null) {
                Log.e(TAG, "Missing PIN layout overlay for user=" + userId);
                return;
            }
            if (info.isEnabled() == !high) return;
            overlays.setEnabled(LOW_OVERLAY, !high, user);
            Log.i(TAG, "reason=" + reason + " user=" + userId + " enrolled=" + enrolled
                    + " enabled=" + enabled + " strongAuth=" + strongAuth
                    + " policyAllowed=" + policyAllowed + " sensorAllowed=" + sensorAllowed
                    + " layout=" + (high ? "HIGH" : "LOW"));
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot update PIN layout: " + reason, e);
        }
    }
}
