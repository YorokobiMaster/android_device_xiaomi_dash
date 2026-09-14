/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashfod;

import android.app.Application;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.fingerprint.FingerprintManager;

/** PIN presentation only. Authentication and vendor state live in system_server. */
public final class DashFodApplication extends Application {
    private PinLayoutController mPinLayout;

    @Override
    public void onCreate() {
        super.onCreate();
        mPinLayout = new PinLayoutController(this);
        mPinLayout.start();
        getSystemService(FingerprintManager.class).registerBiometricStateListener(
                new BiometricStateListener() {
                    @Override
                    public void onStateChanged(int state) {
                        mPinLayout.refresh("biometric-state");
                    }

                    @Override
                    public void onEnrollmentsChanged(int userId, int sensorId,
                            boolean hasEnrollments) {
                        mPinLayout.refresh("enrollment");
                    }
                });
    }
}
