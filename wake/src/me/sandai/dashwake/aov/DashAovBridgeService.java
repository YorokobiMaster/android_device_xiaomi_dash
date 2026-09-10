/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashwake.aov;

import android.app.Service;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;

/** One-shot AOV source. Wake policy belongs to DashWake, vendor calls stay in this process. */
public final class DashAovBridgeService extends Service {
    private static final String TAG = "DashAOV.Bridge";
    private static final long RETRY_DELAY_MS = 5_000;
    private static final long CALLBACK_WAKELOCK_MS = 3_000;

    private HandlerThread mWorkerThread;
    private Handler mWorker;
    private PowerManager.WakeLock mCallbackWakeLock;
    private IDashAovCallback mClientCallback;
    private MtkAovClient mAovClient;

    private final IBinder.DeathRecipient mClientDeathRecipient = () -> {
        if (mWorker != null) mWorker.post(this::clearClient);
    };

    private final Runnable mRetry = this::reconcile;

    private final IDashAovBridge.Stub mBinder = new IDashAovBridge.Stub() {
        @Override
        public void start(IDashAovCallback callback) {
            if (callback == null || mWorker == null) return;
            mWorker.post(() -> setClientAndStart(callback));
        }

        @Override
        public void stop() {
            if (mWorker != null) mWorker.post(DashAovBridgeService.this::clearClient);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mWorkerThread = new HandlerThread("DashAOV");
        mWorkerThread.start();
        mWorker = new Handler(mWorkerThread.getLooper());

        PowerManager powerManager = getSystemService(PowerManager.class);
        mCallbackWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "DashAOV:presence");
        mCallbackWakeLock.setReferenceCounted(false);
    }

    @Override
    public IBinder onBind(android.content.Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (mWorker != null) mWorker.post(this::clearClient);
        return false;
    }

    @Override
    public void onDestroy() {
        if (mWorker != null) {
            mWorker.removeCallbacksAndMessages(null);
            mWorker.runWithScissors(this::clearClient, 2_000);
        }
        if (mWorkerThread != null) {
            mWorkerThread.quitSafely();
        }
        super.onDestroy();
    }

    private void setClientAndStart(IDashAovCallback callback) {
        IBinder oldBinder = mClientCallback == null ? null : mClientCallback.asBinder();
        if (oldBinder == callback.asBinder()) {
            reconcile();
            return;
        }
        if (oldBinder != null) {
            oldBinder.unlinkToDeath(mClientDeathRecipient, 0);
        }
        mClientCallback = callback;
        try {
            callback.asBinder().linkToDeath(mClientDeathRecipient, 0);
        } catch (RemoteException e) {
            Log.w(TAG, "Wake gesture callback is already dead");
            clearClient();
            return;
        }
        reconcile();
    }

    private void clearClient() {
        Handler worker = mWorker;
        if (worker != null) worker.removeCallbacks(mRetry);
        if (mClientCallback != null) {
            mClientCallback.asBinder().unlinkToDeath(mClientDeathRecipient, 0);
            mClientCallback = null;
        }
        stopAov();
    }

    private void reconcile() {
        mWorker.removeCallbacks(mRetry);
        if (mClientCallback == null) {
            stopAov();
            return;
        }
        if (mAovClient != null) {
            return;
        }

        MtkAovClient client = new MtkAovClient(mWorker, new MtkAovClient.Listener() {
            @Override
            public void onPresenceDetected(MtkAovClient source) {
                if (mAovClient != source) return;
                deliverPresence();
            }

            @Override
            public void onServiceDied(MtkAovClient source) {
                if (mAovClient != source) return;
                mAovClient = null;
                mWorker.removeCallbacks(mRetry);
                if (mClientCallback != null) mWorker.postDelayed(mRetry, RETRY_DELAY_MS);
            }
        });
        if (client.start()) {
            mAovClient = client;
        } else {
            client.close();
            mWorker.postDelayed(mRetry, RETRY_DELAY_MS);
        }
    }

    private void deliverPresence() {
        IDashAovCallback callback = mClientCallback;
        clearClient();
        if (callback == null) return;
        mCallbackWakeLock.acquire(CALLBACK_WAKELOCK_MS);
        try {
            callback.onPresenceDetected();
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to notify wake gesture controller", e);
        }
    }

    private void stopAov() {
        if (mAovClient != null) {
            mAovClient.close();
            mAovClient = null;
            Log.i(TAG, "AOV gaze detection stopped");
        }
    }
}
