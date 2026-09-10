// Copyright (C) 2026 GitHub @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0

package me.sandai.dashled.aidl;

/**
 * Static description of the AW21024 lens-ring LED as exposed by DashLedService.
 */
parcelable DashLedCapabilities {
    int apiVersion;
    /** Usable zones; frame arrays are exactly this long. */
    int zoneCount;
    boolean rgbSupported;
    boolean brightnessSupported;
    /** Hardware-autonomous effects; unsupported parameters use software rendering. */
    boolean hardwareBreathingSupported;
    boolean hardwareGradientSupported;
    /** Backend coalescing interval; clients may call faster than this. */
    int minFrameIntervalMs;
}
