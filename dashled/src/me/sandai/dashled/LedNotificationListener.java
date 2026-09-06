/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class LedNotificationListener extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        NotificationLedController.onNotificationPosted(this, sbn);
    }
}
