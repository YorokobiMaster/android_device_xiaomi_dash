/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashfod;

import java.util.function.Consumer;

final class FodController {
    enum Operation {
        ENROLLMENT,
        KEYGUARD_AUTH,
        GENERIC_AUTH,
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
    // Only framework lifecycle edges change the request. A policy pause or
    // failed vendor command changes applied state, not the surviving request.
    private Operation mRequested;
    private Operation mApplied;
    private CleanupKind mCleanupDebt;
    private boolean mKeyguardAllowed = true;

    FodController(Client client, Consumer<String> log) {
        mClient = client;
        mLog = log;
    }

    void onStartup() {
        reset("startup");
    }

    void onVendorDeath() {
        // The framework owns request lifetime, not this connection. A delayed
        // death notification must not erase a newer framework START.
        mApplied = null;
        mCleanupDebt = CleanupKind.GENERIC_DISARM;
        mLog.accept("reset reason=vendor-death requested=" + mRequested);
        reconcile();
    }

    void onUserChanged() {
        reset("user-change");
    }

    private void reset(String reason) {
        mRequested = null;
        mApplied = null;
        mCleanupDebt = CleanupKind.GENERIC_DISARM;
        mLog.accept("reset reason=" + reason + " desired=disarmed");
        reconcile();
    }

    void onStart(Operation operation) {
        mLog.accept("edge=START operation=" + operation);
        mRequested = operation;
        reconcile();
    }

    void onStop(Operation operation) {
        onTerminal("STOP", operation);
    }

    void onError(Operation operation) {
        onTerminal("ERROR", operation);
    }

    void onSucceeded(Operation operation) {
        if (operation != Operation.ENROLLMENT) onTerminal("SUCCEEDED", operation);
    }

    private void onTerminal(String edge, Operation operation) {
        mLog.accept("edge=" + edge + " operation=" + operation
                + " matching=" + (mRequested == operation));
        if (mRequested == operation) mRequested = null;
        reconcile();
    }

    void onFailed(Operation operation) {
        if (mRequested != operation || mApplied != operation
                || operation == Operation.ENROLLMENT) return;

        mLog.accept("edge=FAILED operation=" + operation);
        if (!mClient.extCmd(4, 3) || !mClient.extCmd(7, 0)) {
            cleanup(CleanupKind.AUTH_TERMINAL);
        }
    }

    void setKeyguardAllowed(boolean allowed) {
        mKeyguardAllowed = allowed;
        reconcile();
    }

    private Operation desired() {
        return mRequested == Operation.KEYGUARD_AUTH && !mKeyguardAllowed
                ? null : mRequested;
    }

    boolean needsReconcile() {
        return mCleanupDebt != null || mApplied != desired();
    }

    void reconcile() {
        if (!needsReconcile()) return;
        if (!mClient.isConnected() && !mClient.connect()) return;
        if (mCleanupDebt != null && !cleanup(mCleanupDebt)) return;

        Operation desired = desired();
        if (mApplied == desired) return;
        if (mApplied != null && !cleanup(terminalCleanup(mApplied))) return;
        if (desired == null) return;

        if (start(desired)) {
            mApplied = desired;
            mLog.accept("applied=" + desired);
        } else {
            cleanup(terminalCleanup(desired));
        }
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
        return operation == Operation.ENROLLMENT
                ? CleanupKind.ENROLLMENT_TERMINAL : CleanupKind.AUTH_TERMINAL;
    }

    private boolean cleanup(CleanupKind kind) {
        mApplied = null;
        mCleanupDebt = kind;
        int fingerprintState = kind == CleanupKind.AUTH_TERMINAL ? 4 : 2;
        boolean success = true;
        if (!mClient.extCmd(4, fingerprintState)) success = false;
        if (!mClient.extCmd(7, 0)) success = false;
        if (!mClient.extCmd(1, 0)) success = false;
        if (success) mCleanupDebt = null;
        mLog.accept("cleanup kind=" + kind + " success=" + success);
        return success;
    }
}
