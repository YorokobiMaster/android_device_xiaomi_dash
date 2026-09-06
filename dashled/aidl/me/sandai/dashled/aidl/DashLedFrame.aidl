// Copyright (C) 2026 @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0

package me.sandai.dashled.aidl;

/**
 * One static LED state. Only zones 0-3 exist; they map to chip pixels 3-6.
 * Chip pixels 0/1/2/7 are unpopulated and are not exposed here.
 */
parcelable DashLedFrame {
    /** Per-zone color as 0xRRGGBB, zone order matches DashLedCapabilities. */
    int[4] colors;
    /** Global PWM brightness, 0-255. */
    int brightness;
}
