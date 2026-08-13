/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.dashfod;

import java.util.function.Consumer;

final class FodController {
    enum Operation {
        ENROLLMENT,
        KEYGUARD_AUTH,
        GENERIC_AUTH,
    }

    private enum Phase {
        IDLE,
        ACTIVE,
        CLEANUP,
        CLEANUP_DEBT,
    }

    private enum CleanupKind {
        GENERIC_DISARM,
        ENROLLMENT_TERMINAL,
        AUTH_TERMINAL,
    }

    interface Client {
        boolean connect();
        boolean isConnected();
        boolean extCmd(int command, int parameter);
    }

    private final Client mClient;
    private final Consumer<String> mLog;
    private Phase mPhase = Phase.IDLE;
    private Operation mOperation;
    private CleanupKind mCleanupKind;

    FodController(Client client, Consumer<String> log) {
        mClient = client;
        mLog = log;
    }

    void onStartup() {
        setIdle();
        reconnectAndCleanup("startup");
    }

    void onVendorDeath() {
        setIdle();
        reconnectAndCleanup("vendor-death");
    }

    void onStart(Operation operation) {
        if (!prepareForEvent("START")) return;

        if (mPhase == Phase.ACTIVE) {
            if (mOperation == operation) {
                mLog.accept("edge=START operation=" + operation + " duplicate=true");
            } else {
                mLog.accept("edge=START operation=" + operation + " blocked=active-"
                        + mOperation);
            }
            return;
        }

        mLog.accept("edge=START operation=" + operation + " duplicate=false");
        if (!mClient.isConnected() && !mClient.connect()) {
            mLog.accept("edge=START operation=" + operation + " blocked=connect");
            return;
        }
        if (!start(operation)) {
            runCleanup(terminalCleanup(operation), "partial-start");
            return;
        }
        setActive(operation);
    }

    void onStop(Operation operation) {
        onTerminal("STOP", operation);
    }

    void onError(Operation operation) {
        onTerminal("ERROR", operation);
    }

    void onFailed(Operation operation) {
        if (!prepareForEvent("FAILED")) return;

        if (mPhase != Phase.ACTIVE || mOperation != operation
                || (operation != Operation.KEYGUARD_AUTH
                && operation != Operation.GENERIC_AUTH)) {
            mLog.accept("edge=FAILED operation=" + operation + " ignored=true");
            return;
        }

        mLog.accept("edge=FAILED operation=" + operation + " ignored=false");
        if (!mClient.extCmd(4, 3) || !mClient.extCmd(7, 0)) {
            runCleanup(CleanupKind.AUTH_TERMINAL, "failed-match");
        }
    }

    void onSucceeded(Operation operation) {
        if (!prepareForEvent("SUCCEEDED")) return;

        if (operation != Operation.KEYGUARD_AUTH
                && operation != Operation.GENERIC_AUTH) {
            mLog.accept("edge=SUCCEEDED operation=" + operation + " ignored=true");
            return;
        }
        onTerminalPrepared("SUCCEEDED", operation);
    }

    private void onTerminal(String edge, Operation operation) {
        if (!prepareForEvent(edge)) return;
        onTerminalPrepared(edge, operation);
    }

    private void onTerminalPrepared(String edge, Operation operation) {
        if (mPhase != Phase.ACTIVE) {
            mLog.accept("edge=" + edge + " operation=" + operation + " duplicate=true");
            return;
        }
        if (mOperation != operation) {
            mLog.accept("edge=" + edge + " operation=" + operation + " blocked=active-"
                    + mOperation);
            return;
        }

        mLog.accept("edge=" + edge + " operation=" + operation + " duplicate=false");
        runCleanup(terminalCleanup(operation), edge.toLowerCase());
    }

    private boolean prepareForEvent(String edge) {
        if (mPhase != Phase.CLEANUP_DEBT) return true;

        CleanupKind cleanupKind = mCleanupKind;
        mLog.accept("edge=" + edge + " cleanup-debt=" + cleanupKind + " retry=true");
        if (!mClient.isConnected() && !mClient.connect()) {
            mLog.accept("edge=" + edge + " cleanup-debt=" + cleanupKind
                    + " blocked=connect");
            return false;
        }
        if (!runCleanup(cleanupKind, "debt-" + edge.toLowerCase())) {
            mLog.accept("edge=" + edge + " cleanup-debt=" + cleanupKind + " dropped=true");
            return false;
        }
        return true;
    }

    private boolean start(Operation operation) {
        switch (operation) {
            case ENROLLMENT:
                return mClient.extCmd(4, 1)
                        && mClient.extCmd(7, 0)
                        && mClient.extCmd(1, 1);
            case KEYGUARD_AUTH:
                return mClient.extCmd(4, 3)
                        && mClient.extCmd(7, 1)
                        && mClient.extCmd(18, 1)
                        && mClient.extCmd(1, 1);
            case GENERIC_AUTH:
                return mClient.extCmd(4, 3)
                        && mClient.extCmd(7, 0)
                        && mClient.extCmd(18, 4)
                        && mClient.extCmd(1, 1);
        }
        throw new IllegalArgumentException("Unknown operation " + operation);
    }

    private CleanupKind terminalCleanup(Operation operation) {
        switch (operation) {
            case ENROLLMENT:
                return CleanupKind.ENROLLMENT_TERMINAL;
            case KEYGUARD_AUTH:
            case GENERIC_AUTH:
                return CleanupKind.AUTH_TERMINAL;
        }
        throw new IllegalArgumentException("Unknown operation " + operation);
    }

    private void reconnectAndCleanup(String reason) {
        mLog.accept("connect reason=" + reason + " desired=disarmed");
        setCleanupDebt(CleanupKind.GENERIC_DISARM);
        if (!mClient.connect()) return;
        runCleanup(CleanupKind.GENERIC_DISARM, reason);
    }

    private boolean runCleanup(CleanupKind cleanupKind, String reason) {
        mPhase = Phase.CLEANUP;
        mOperation = null;
        mCleanupKind = cleanupKind;
        mLog.accept("cleanup reason=" + reason + " kind=" + cleanupKind
                + " desired=disarmed");

        int fingerprintState = cleanupKind == CleanupKind.AUTH_TERMINAL ? 4 : 2;
        boolean success = true;
        if (!mClient.extCmd(4, fingerprintState)) success = false;
        if (!mClient.extCmd(7, 0)) success = false;
        if (!mClient.extCmd(1, 0)) success = false;

        mLog.accept("cleanup reason=" + reason + " kind=" + cleanupKind
                + " success=" + success);
        if (success) {
            setIdle();
        } else {
            setCleanupDebt(cleanupKind);
        }
        return success;
    }

    private void setIdle() {
        mPhase = Phase.IDLE;
        mOperation = null;
        mCleanupKind = null;
    }

    private void setActive(Operation operation) {
        mPhase = Phase.ACTIVE;
        mOperation = operation;
        mCleanupKind = null;
    }

    private void setCleanupDebt(CleanupKind cleanupKind) {
        mPhase = Phase.CLEANUP_DEBT;
        mOperation = null;
        mCleanupKind = cleanupKind;
    }
}
