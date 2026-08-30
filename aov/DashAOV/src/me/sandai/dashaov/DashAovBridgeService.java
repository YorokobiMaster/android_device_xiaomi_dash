/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashaov;

import android.app.Service;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps all vendor AOV Binder access outside the SystemUI process. */
public final class DashAovBridgeService extends Service {
    private static final String TAG = "DashAOV.Bridge";
    private static final long RETRY_DELAY_MS = 5_000;
    private static final long CALLBACK_WAKELOCK_MS = 3_000;

    private HandlerThread mWorkerThread;
    private Handler mWorker;
    private PowerManager.WakeLock mCallbackWakeLock;
    private IDashAovCallback mClientCallback;
    private MtkAovClient mAovClient;
    private final AtomicBoolean mPresencePending = new AtomicBoolean();

    private final IBinder.DeathRecipient mClientDeathRecipient = () -> {
        if (mWorker != null) mWorker.post(this::clearClient);
    };

    private final Runnable mRetry = this::reconcile;

    private final ContentObserver mSettingsObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            if (mWorker != null) mWorker.post(DashAovBridgeService.this::reconcile);
        }
    };

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

        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(SmartAodSettings.ENABLED), false, mSettingsObserver);
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DOZE_ALWAYS_ON), false,
                mSettingsObserver);
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
        getContentResolver().unregisterContentObserver(mSettingsObserver);
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
            mPresencePending.set(false);
            reconcile();
            return;
        }
        if (oldBinder != null && oldBinder != callback.asBinder()) {
            oldBinder.unlinkToDeath(mClientDeathRecipient, 0);
        }
        mClientCallback = callback;
        try {
            callback.asBinder().linkToDeath(mClientDeathRecipient, 0);
        } catch (RemoteException e) {
            Log.w(TAG, "SystemUI callback is already dead");
            clearClient();
            return;
        }
        mPresencePending.set(false);
        reconcile();
    }

    private void clearClient() {
        Handler worker = mWorker;
        if (worker != null) worker.removeCallbacks(mRetry);
        if (mClientCallback != null) {
            mClientCallback.asBinder().unlinkToDeath(mClientDeathRecipient, 0);
            mClientCallback = null;
        }
        mPresencePending.set(false);
        stopAov();
    }

    private void reconcile() {
        mWorker.removeCallbacks(mRetry);
        if (!isEligible()) {
            stopAov();
            return;
        }
        if (mAovClient != null) {
            return;
        }

        MtkAovClient client = new MtkAovClient(new MtkAovClient.Listener() {
            @Override
            public void onPresenceDetected() {
                if (mPresencePending.compareAndSet(false, true)) {
                    mWorker.post(DashAovBridgeService.this::deliverPresence);
                }
            }

            @Override
            public void onServiceDied() {
                mWorker.post(() -> {
                    mAovClient = null;
                    if (isEligible()) mWorker.postDelayed(mRetry, RETRY_DELAY_MS);
                });
            }
        });
        if (client.start()) {
            mAovClient = client;
        } else {
            client.close();
            mWorker.postDelayed(mRetry, RETRY_DELAY_MS);
        }
    }

    private boolean isEligible() {
        return mClientCallback != null
                && SmartAodSettings.isEnabled(getContentResolver())
                && !SmartAodSettings.isAlwaysOnEnabled(getContentResolver());
    }

    private void deliverPresence() {
        stopAov();
        IDashAovCallback callback = mClientCallback;
        if (callback == null || !isEligible()) {
            mPresencePending.set(false);
            return;
        }
        mCallbackWakeLock.acquire(CALLBACK_WAKELOCK_MS);
        try {
            callback.onPresenceDetected();
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to notify SystemUI", e);
            clearClient();
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
