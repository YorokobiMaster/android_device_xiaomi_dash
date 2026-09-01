/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashdt2w;

import android.app.ActivityManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.util.Collection;

public final class DashDt2wApplication extends Application {
    private static final String TAG = "DashDt2w";
    private static final long RETRY_DELAY_MILLIS = 1000;
    private static final int MAX_RETRIES = 30;
    private static final int TOUCH_STATE_RESUME = 0;

    static {
        System.loadLibrary("dashdt2w_jni");
    }

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private Thread mTouchStateThread;
    private int mSyncGeneration;

    private final ContentObserver mSettingObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            scheduleSync("setting");
        }

        @Override
        public void onChange(boolean selfChange, Collection<Uri> uris, int flags,
                UserHandle user) {
            scheduleSync("setting-user-" + user.getIdentifier());
        }
    };

    private final BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            scheduleSync(intent.getAction());
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DOUBLE_TAP_TO_WAKE),
                false, mSettingObserver, UserHandle.USER_ALL);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED);
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        registerReceiver(mUserReceiver, filter, Context.RECEIVER_EXPORTED);

        scheduleSync("startup");

        mTouchStateThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                final int state = nativeWaitForTouchStateChange();
                if (state == TOUCH_STATE_RESUME) {
                    scheduleSync("touch-resume-complete");
                } else if (state < 0) {
                    try {
                        Thread.sleep(RETRY_DELAY_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, TAG + "-TouchState");
        mTouchStateThread.start();
    }

    private void scheduleSync(String reason) {
        mHandler.post(() -> {
            int generation = ++mSyncGeneration;
            syncSetting(reason, generation, MAX_RETRIES);
        });
    }

    private void syncSetting(String reason, int generation, int retriesRemaining) {
        if (generation != mSyncGeneration) {
            return;
        }

        final int userId = ActivityManager.getCurrentUser();
        final boolean enabled;
        try {
            enabled = Settings.Secure.getIntForUser(getContentResolver(),
                    Settings.Secure.DOUBLE_TAP_TO_WAKE, 0, userId) != 0;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to read DT2W setting for user " + userId, e);
            retry(reason, generation, retriesRemaining);
            return;
        }

        if (nativeSetDoubleTapWake(enabled)) {
            Log.i(TAG, "reason=" + reason + " user=" + userId + " enabled=" + enabled);
            return;
        }

        Log.w(TAG, "Failed to apply DT2W; retries remaining=" + retriesRemaining);
        retry(reason, generation, retriesRemaining);
    }

    private void retry(String reason, int generation, int retriesRemaining) {
        if (retriesRemaining <= 0) {
            Log.e(TAG, "Giving up DT2W synchronization for reason=" + reason);
            return;
        }
        mHandler.postDelayed(
                () -> syncSetting(reason, generation, retriesRemaining - 1),
                RETRY_DELAY_MILLIS);
    }

    private static native boolean nativeSetDoubleTapWake(boolean enabled);
    private static native int nativeWaitForTouchStateChange();
}
