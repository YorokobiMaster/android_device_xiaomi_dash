/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.dashfod;

final class KeyguardAuthGate {
    enum Action {
        NONE,
        START,
        STOP,
    }

    private enum Phase {
        IDLE,
        PENDING,
        DISPATCHED,
    }

    private Phase mPhase = Phase.IDLE;
    private boolean mBiometricQualified;
    private boolean mWakeQualified;

    Action onAuthenticationStart(boolean interactive) {
        if (mPhase != Phase.IDLE) return Action.NONE;

        mPhase = Phase.PENDING;
        mWakeQualified = interactive;
        return maybeStart();
    }

    Action onBiometricState(boolean keyguardAuth) {
        if (!keyguardAuth) {
            if (mPhase == Phase.DISPATCHED) {
                clear();
                return Action.STOP;
            }
            if (mPhase == Phase.PENDING) {
                mBiometricQualified = false;
                return Action.NONE;
            }
            clear();
            return Action.NONE;
        }

        mBiometricQualified = true;
        return maybeStart();
    }

    Action onScreenOn() {
        if (mPhase != Phase.PENDING) return Action.NONE;

        mWakeQualified = true;
        return maybeStart();
    }

    Action onScreenOff() {
        if (mPhase == Phase.IDLE) return Action.NONE;

        mWakeQualified = false;
        if (mPhase == Phase.DISPATCHED) {
            mPhase = Phase.PENDING;
            return Action.STOP;
        }
        return Action.NONE;
    }

    void onTerminal() {
        clear();
    }

    void reset() {
        clear();
    }

    @Override
    public String toString() {
        return "phase=" + mPhase + " biometric=" + mBiometricQualified
                + " wake=" + mWakeQualified;
    }

    private Action maybeStart() {
        if (mPhase != Phase.PENDING || !mBiometricQualified || !mWakeQualified) {
            return Action.NONE;
        }
        mPhase = Phase.DISPATCHED;
        return Action.START;
    }

    private void clear() {
        mPhase = Phase.IDLE;
        mBiometricQualified = false;
        mWakeQualified = false;
    }
}
