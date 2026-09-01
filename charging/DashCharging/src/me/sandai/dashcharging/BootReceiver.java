/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashcharging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                BypassChargingSettings.restore(context);
            } finally {
                result.finish();
            }
        }, "DashCharging-Restore").start();
    }
}
