// Copyright (C) 2026 @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0

package me.sandai.dashled.aidl;

import me.sandai.dashled.aidl.DashLedCapabilities;
import me.sandai.dashled.aidl.IDashLedSession;

/**
 * Entry point of the lens-ring LED service. Bind with action
 * "me.sandai.dashled.BIND" on package "me.sandai.dashled".
 *
 * Callers must hold me.sandai.dashled.permission.CONTROL and be granted in
 * Settings > Accessibility > LED apertures > App access, otherwise
 * acquireSession throws SecurityException.
 */
interface IDashLedManager {
    const int API_VERSION = 1;

    /** External apps always get CATEGORY_THIRD_PARTY; the higher categories
     *  are reserved for producers built into the service. */
    const int CATEGORY_THIRD_PARTY = 0;
    const int CATEGORY_NOTIFICATION = 1;
    const int CATEGORY_CAMERA_COUNTDOWN = 2;

    int getApiVersion();
    DashLedCapabilities getCapabilities();
    IDashLedSession acquireSession(int category);
}
