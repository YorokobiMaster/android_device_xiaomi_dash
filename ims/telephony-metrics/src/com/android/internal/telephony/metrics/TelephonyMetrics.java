/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.telephony.metrics;

/**
 * Binary-compatibility stub for the TelephonyMetrics class removed from AOSP.
 *
 * <p>The stock MediaTek ImsService.apk and MtkGbaService.apk
 * (OS3.0.305.0.WPLCNXM) invoke this class for telemetry-only metric writes.
 * This class restores exactly the referenced method ABI so those APKs can
 * link; every metric writer is an intentional no-op and no IMS or framework
 * behavior is implemented here.
 */
public final class TelephonyMetrics {

    private static final TelephonyMetrics sInstance = new TelephonyMetrics();

    private TelephonyMetrics() {
    }

    /** Returns the singleton instance; invoked via invoke-static by the MTK APKs. */
    public static TelephonyMetrics getInstance() {
        return sInstance;
    }

    /** No-op binary compatibility for the referenced telemetry writer. */
    public void writeOnRilSolicitedResponse(int phoneId, int slotId, int serial, int error,
            Object ret) {
    }

    /** No-op binary compatibility for the referenced telemetry writer. */
    public void writeOnRilTimeoutResponse(int phoneId, int slotId, int serial) {
    }

    /** No-op binary compatibility for the referenced telemetry writer. */
    public void writeRilAnswer(int phoneId, int serial) {
    }

    /** No-op binary compatibility for the referenced telemetry writer. */
    public void writeRilSendSms(int phoneId, int serial, int tech, int error, long smsId) {
    }
}
