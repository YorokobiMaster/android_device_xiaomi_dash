/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashdolby;

import android.app.Application;

public class DolbyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        DolbyController.getInstance(this).init();
    }
}
