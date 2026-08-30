/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashfod;

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

    Action onVisibleDoze(boolean deviceLocked) {
        if (!deviceLocked || mPhase == Phase.DISPATCHED) return Action.NONE;

        // Neither biometric listener replays an authentication already in
        // progress when this persistent process restarts. A visible Doze
        // pulse on a still-locked device is sufficient to restore that lost
        // keyguard context; a later framework state edge remains authoritative.
        if (mPhase == Phase.IDLE) {
            mPhase = Phase.PENDING;
        }
        mBiometricQualified = true;
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
