/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package vendor.xiaomi.hardware.displayfeature_aidl;

import vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeatureCallback;

// Recovered from dash stock OS3.0.305.0.WPLCNXM V2-ndk library; declaration
// order is load-bearing: AIDL assigns transaction codes 1..10 in this order,
// matching the stock wire protocol.
@VintfStability
interface IDisplayFeature {
    void notifyBrightness(int brightness);
    void registerCallback(int displayId, in IDisplayFeatureCallback callback);
    void sendMessage(int displayId, int msgId, String msg);
    void sendPanelCommand(String cmd);
    void sendPostProcCommand(int displayId, int cmd);
    void sendRefreshCommand();
    void setFeature(int displayId, int mode, int value, int cookie);
    void setFunction(int displayId, int functionId, int value, int cookie);
    void sendGamePkgName(int displayId, int type, int value, String pkgName);
    void unregisterCallback(int displayId, in IDisplayFeatureCallback callback);
}
