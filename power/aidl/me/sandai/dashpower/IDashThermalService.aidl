/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import android.os.Bundle;

/** Platform-signed Settings client API. -1 means automatic, never a sysfs request. */
interface IDashThermalService {
    const int API_VERSION = 3;
    const String PERFORMANCE_STATE_URI = "content://me.sandai.dashthermal.performance/isChecked/performance_mode";
    int getApiVersion();
    Bundle getState(int userId);
    Bundle getAppPolicy(int userId, String packageName);
    void setAppProfile(int userId, String packageName, int profileId);
    void setEnabled(int userId, boolean enabled);
    // Appended to preserve version-1 transaction numbers. Manual app overrides remain authoritative.
    void setPerformanceMode(int userId, boolean enabled);
    // Stock camera compatibility input. Only the platform-signed bridge may call this.
    void notifyCameraRecordState(boolean recording, int quality, int fps);
}
