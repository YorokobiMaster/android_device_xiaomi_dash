/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.content.Context;
import android.service.notification.StatusBarNotification;

import java.util.Arrays;

import me.sandai.dashled.aidl.DashLedEffect;
import me.sandai.dashled.aidl.IDashLedManager;

/** Built-in producer: short breathing pulse on all zones for new notifications. */
public final class NotificationLedController {

    private static final int PERIOD_MS = 1500;
    private static final int REPEATS = 3;

    private NotificationLedController() {
    }

    public static void onNotificationPosted(Context context, StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (context.getPackageName().equals(pkg) || sbn.isOngoing()) {
            return;
        }
        if (!DashLedPrefs.isMasterEnabled(context)
                || !DashLedPrefs.isNotifEnabled(context)
                || !DashLedPrefs.isNotifAppEnabled(context, pkg)) {
            return;
        }

        int[] colors = new int[Aw21024Backend.ZONE_COUNT];
        Arrays.fill(colors, DashLedPrefs.getNotifColor(context));
        DashLedEffect effect = new DashLedEffect();
        effect.type = DashLedEffect.TYPE_BREATH;
        effect.colors = colors;
        effect.brightness = 255;
        effect.periodMs = PERIOD_MS;
        effect.repeatCount = REPEATS;

        LedArbiter arbiter = LedCore.get(context).arbiter();
        synchronized (NotificationLedController.class) {
            // A newer notification supersedes the pulse that is still running.
            if (sSession != null) {
                arbiter.release(sSession);
            }
            sSession = arbiter.acquire(IDashLedManager.CATEGORY_NOTIFICATION);
            final LedArbiter.Session session = sSession;
            arbiter.playEffect(session, effect, () -> {
                synchronized (NotificationLedController.class) {
                    if (sSession == session) {
                        sSession = null;
                    }
                }
                arbiter.release(session);
            });
        }
    }

    private static LedArbiter.Session sSession;
}
