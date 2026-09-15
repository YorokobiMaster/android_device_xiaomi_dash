/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashwake.aov;

import android.os.Handler;
import android.os.HandlerThread;
import android.service.attention.AttentionService;
import android.util.Log;

/**
 * AOSP adaptive-sleep backend. Mirrors stock: the first AOV frame after stream-up
 * decides the verdict instead of waiting out the framework's 2 s pre-dim budget.
 */
public final class DashAttentionService extends AttentionService {
    private static final String TAG = "DashAttention";
    // Report TIMED_OUT ourselves when the AOV pipeline delivers no frame at all,
    // staying inside the 2 s budget AttentionManagerService grants us.
    private static final long NO_FRAME_TIMEOUT_MS = 1_500;

    private HandlerThread mWorkerThread;
    private Handler mWorker;
    private MtkAovClient mClient;
    private AttentionCallback mPending;

    private final Runnable mNoFrameTimeout = () -> {
        if (mPending != null) {
            Log.w(TAG, "No AOV frame within " + NO_FRAME_TIMEOUT_MS + " ms");
            fail(ATTENTION_FAILURE_TIMED_OUT);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mWorkerThread = new HandlerThread(TAG);
        mWorkerThread.start();
        mWorker = new Handler(mWorkerThread.getLooper());
    }

    @Override
    public void onDestroy() {
        mWorker.runWithScissors(this::stopCheck, 2_000);
        mWorkerThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public void onCheckAttention(AttentionCallback callback) {
        mWorker.post(() -> startCheck(callback));
    }

    @Override
    public void onCancelAttentionCheck(AttentionCallback callback) {
        // AttentionManagerService only keeps one active check; cancel whatever is pending.
        mWorker.post(() -> {
            if (mPending != null) {
                fail(ATTENTION_FAILURE_CANCELLED);
            }
        });
    }

    private void startCheck(AttentionCallback callback) {
        stopCheck();
        mPending = callback;
        MtkAovClient client = new MtkAovClient(mWorker, new MtkAovClient.Listener() {
            @Override
            public void onPresenceDetected(MtkAovClient source) {
                if (source == mClient) {
                    complete(ATTENTION_SUCCESS_PRESENT);
                }
            }

            @Override
            public void onNoPresenceDetected(MtkAovClient source) {
                if (source == mClient) {
                    complete(ATTENTION_SUCCESS_ABSENT);
                }
            }

            @Override
            public void onServiceDied(MtkAovClient source) {
                if (source == mClient) {
                    fail(ATTENTION_FAILURE_UNKNOWN);
                }
            }
        });
        mClient = client;
        if (!client.start()) {
            fail(ATTENTION_FAILURE_UNKNOWN);
            return;
        }
        mWorker.postDelayed(mNoFrameTimeout, NO_FRAME_TIMEOUT_MS);
    }

    private void complete(int result) {
        AttentionCallback callback = detachCheck();
        if (callback != null) {
            callback.onSuccess(result, System.currentTimeMillis());
        }
        closeClient();
    }

    private void fail(int error) {
        AttentionCallback callback = detachCheck();
        if (callback != null) {
            callback.onFailure(error);
        }
        closeClient();
    }

    // Deliver the verdict before touching the HAL: close() transacts vendor
    // STOP/DISCONNECT synchronously and must not eat the framework's 2 s budget.
    private AttentionCallback detachCheck() {
        mWorker.removeCallbacks(mNoFrameTimeout);
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
        detachCheck();
        closeClient();
    }
}
