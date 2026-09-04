/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package vendor.xiaomi.hardware.displayfeature_aidl;

// Recovered from dash stock miui-framework IDisplayFeatureCallback$Stub smali;
// transaction 1 wire format int,int,float,float,float.
@VintfStability
interface IDisplayFeatureCallback {
    void displayfeatureInfoChanged(int caseId, int value, float red, float green, float blue);
}
