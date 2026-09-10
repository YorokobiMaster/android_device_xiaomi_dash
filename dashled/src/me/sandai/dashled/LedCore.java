/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/** Process-wide LED plumbing shared by the binder service, the notification
 *  listener and the settings UI (all live in the app process). */
public final class LedCore {

    private static LedCore sInstance;

    private final LedArbiter mArbiter;

    public static synchronized LedCore get(Context context) {
        if (sInstance == null) {
            sInstance = new LedCore(context.getApplicationContext());
        }
        return sInstance;
    }

    private LedCore(Context context) {
        Aw21024Backend backend = new Aw21024Backend();
        mArbiter = new LedArbiter(backend, backend.handler(),
                context.getSystemService(AlarmManager.class));
        mArbiter.setOutputEnabled(DashLedPrefs.isMasterEnabled(context));
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mArbiter.onWake();
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_ON), null, backend.handler(),
                Context.RECEIVER_NOT_EXPORTED);
    }

    public LedArbiter arbiter() {
        return mArbiter;
    }
}
