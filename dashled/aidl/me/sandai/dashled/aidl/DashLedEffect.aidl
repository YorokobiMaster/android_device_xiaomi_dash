// Copyright (C) 2026 @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0

package me.sandai.dashled.aidl;

/**
 * A finite or repeating animated effect, rendered by the service at the
 * coalesced frame rate so clients do not have to stream frames.
 */
parcelable DashLedEffect {
    /** Brightness ramps up and down over periodMs. */
    const int TYPE_BREATH = 0;

    int type;
    /** Per-zone color as 0xRRGGBB. */
    int[4] colors;
    /** Peak brightness, 0-255. */
    int brightness;
    /** Full up+down cycle length. */
    int periodMs;
    /** Number of cycles; <= 0 repeats until clear(). */
    int repeatCount;
}
