// Copyright (C) 2026 GitHub @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.sandai.dashpower.IDashThermalService;

/**
 * Async client for the dash_thermal binder service. All binder traffic runs on a
 * single background thread; callbacks are posted to the main thread.
 */
final class ThermalServiceClient {

    static final String SERVICE_NAME = "dash_thermal";

    static final String KEY_READY = "ready";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_ERROR = "error";
    static final String KEY_OVERRIDES = "overrides";
    static final String KEY_OVERRIDE_PROFILE = "overrideProfile";
    static final String KEY_STOCK_GROUP = "stockGroup";

    private static final long RETRY_DELAY_MS = 3000;

    interface StateCallback {
        /** Called on the main thread. {@code state} is null when unavailable. */
        void onState(boolean available, Bundle state);
    }

    interface PolicyCallback {
        void onPolicy(Bundle policy);
        void onError();
    }

    interface OpCallback {
        void onResult(boolean success);
    }

    private final int mUserId = UserHandle.myUserId();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private IDashThermalService mService;
    private boolean mStarted;
    private boolean mRetryScheduled;
    private StateCallback mStateCallback;

    private final IBinder.DeathRecipient mDeathRecipient = () -> mHandler.post(() -> {
        mService = null;
        notifyUnavailable();
        scheduleRetry();
    });

    private final Runnable mRetryTask = () -> {
        mRetryScheduled = false;
        if (mStarted) {
            refresh();
        }
    };

    void start(StateCallback callback) {
        mStateCallback = callback;
        mStarted = true;
        refresh();
    }

    void stop() {
        mStarted = false;
        mStateCallback = null;
        mHandler.removeCallbacks(mRetryTask);
    }

    void shutdown() {
        stop();
        mExecutor.shutdown();
    }

    /** Re-resolves the service if needed and fetches getState() in the background. */
    void refresh() {
        mExecutor.execute(() -> {
            IDashThermalService service = mService;
            if (service == null) {
                final IBinder binder = ServiceManager.checkService(SERVICE_NAME);
                if (binder != null) {
                    try {
                        binder.linkToDeath(mDeathRecipient, 0);
                        service = IDashThermalService.Stub.asInterface(binder);
                        mService = service;
                    } catch (RemoteException e) {
                        service = null;
                    }
                }
            }
            if (service == null) {
                mHandler.post(() -> {
                    notifyUnavailable();
                    scheduleRetry();
                });
                return;
            }
            final Bundle state;
            try {
                state = service.getState(mUserId);
            } catch (RemoteException | RuntimeException e) {
                mHandler.post(() -> {
                    notifyUnavailable();
                    scheduleRetry();
                });
                return;
            }
            final boolean ready = state != null && state.getBoolean(KEY_READY, false);
            mHandler.post(() -> {
                if (mStateCallback != null) {
                    mStateCallback.onState(ready, ready ? state : null);
                }
                if (!ready) {
                    scheduleRetry();
                }
            });
        });
    }

    void getAppPolicy(String packageName, PolicyCallback callback) {
        mExecutor.execute(() -> {
            final IDashThermalService service = mService;
            if (service == null) {
                mHandler.post(callback::onError);
                return;
            }
            try {
                final Bundle policy = service.getAppPolicy(mUserId, packageName);
                mHandler.post(() -> callback.onPolicy(policy));
            } catch (RemoteException | RuntimeException e) {
                mHandler.post(callback::onError);
            }
        });
    }

    void setAppProfile(String packageName, int profileId, OpCallback callback) {
        mExecutor.execute(() -> {
            final IDashThermalService service = mService;
            boolean success = false;
            if (service != null) {
                try {
                    service.setAppProfile(mUserId, packageName, profileId);
                    success = true;
                } catch (RemoteException | RuntimeException e) {
                    success = false;
                }
            }
            final boolean result = success;
            mHandler.post(() -> callback.onResult(result));
        });
    }

    void setEnabled(boolean enabled, OpCallback callback) {
        mExecutor.execute(() -> {
            final IDashThermalService service = mService;
            boolean success = false;
            if (service != null) {
                try {
                    service.setEnabled(mUserId, enabled);
                    success = true;
                } catch (RemoteException | RuntimeException e) {
                    success = false;
                }
            }
            final boolean result = success;
            mHandler.post(() -> callback.onResult(result));
        });
    }

    private void notifyUnavailable() {
        if (mStateCallback != null) {
            mStateCallback.onState(false, null);
        }
    }

    private void scheduleRetry() {
        if (mStarted && !mRetryScheduled) {
            mRetryScheduled = true;
            mHandler.postDelayed(mRetryTask, RETRY_DELAY_MS);
        }
    }
}
