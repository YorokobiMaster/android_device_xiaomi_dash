/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashwake.aov;

import android.os.Handler;
import android.os.HandlerThread;
import android.service.attention.AttentionService;
import android.util.Log;

/** AOV-backed implementation of AOSP Screen attention. */
public final class DashAttentionService extends AttentionService {
    private static final String TAG = "DashAttention";

    private HandlerThread mWorkerThread;
    private Handler mWorker;
    private MtkAovClient mClient;
    private AttentionCallback mPending;

    @Override
    public void onCreate() {
        super.onCreate();
        mWorkerThread = new HandlerThread(TAG);
        mWorkerThread.start();
        mWorker = new Handler(mWorkerThread.getLooper());
    }

    @Override
    public void onDestroy() {
        if (mWorker != null) {
            mWorker.removeCallbacksAndMessages(null);
            mWorker.runWithScissors(this::stopCheck, 2_000);
        }
        if (mWorkerThread != null) {
            mWorkerThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public void onCheckAttention(AttentionCallback callback) {
        mWorker.post(() -> startCheck(callback));
    }

    @Override
    public void onCancelAttentionCheck(AttentionCallback callback) {
        mWorker.post(() -> cancelCheck(callback));
    }

    private void startCheck(AttentionCallback callback) {
        if (mPending != null) {
            finishFailure(ATTENTION_FAILURE_PREEMPTED);
        }

        mPending = callback;
        MtkAovClient client = new MtkAovClient(mWorker, new MtkAovClient.Listener() {
            @Override
            public void onPresenceDetected(MtkAovClient source) {
                if (source == mClient) {
                    finishPresent();
                }
            }

            @Override
            public void onServiceDied(MtkAovClient source) {
                if (source == mClient) {
                    finishFailure(ATTENTION_FAILURE_UNKNOWN);
                }
            }
        }, 0);
        mClient = client;
        if (!client.start()) {
            finishFailure(ATTENTION_FAILURE_UNKNOWN);
        }
    }

    private void cancelCheck(AttentionCallback callback) {
        if (mPending == null) {
            return;
        }
        // AttentionService wraps the same Binder callback in a new Java object for cancellation,
        // so object identity cannot be used to match it here. The framework permits one request.
        finishFailure(ATTENTION_FAILURE_CANCELLED);
    }

    private void finishPresent() {
        AttentionCallback callback = detachCheck();
        if (callback == null) {
            return;
        }
        try {
            callback.onSuccess(ATTENTION_SUCCESS_PRESENT, System.currentTimeMillis());
            Log.i(TAG, "attention present");
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to report attention result", e);
        } finally {
            closeClient();
        }
    }

    private void finishFailure(int error) {
        AttentionCallback callback = detachCheck();
        if (callback == null) {
            return;
        }
        try {
            callback.onFailure(error);
            Log.i(TAG, "attention failure=" + error);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to report attention failure", e);
        } finally {
            closeClient();
        }
    }

    private AttentionCallback detachCheck() {
        AttentionCallback callback = mPending;
        mPending = null;
        return callback;
    }

    private void closeClient() {
        if (mClient != null) {
            mClient.close();
            mClient = null;
        }
    }

    private void stopCheck() {
        mPending = null;
        closeClient();
    }
}
