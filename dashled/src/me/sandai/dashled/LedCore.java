/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.content.Context;

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
        mArbiter = new LedArbiter(backend, backend.handler());
        mArbiter.setOutputEnabled(DashLedPrefs.isMasterEnabled(context));
    }

    public LedArbiter arbiter() {
        return mArbiter;
    }
}
