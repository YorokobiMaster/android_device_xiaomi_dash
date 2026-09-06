// Copyright (C) 2026 @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0

package me.sandai.dashled.aidl;

import me.sandai.dashled.aidl.DashLedEffect;
import me.sandai.dashled.aidl.DashLedFrame;

/**
 * Exclusive-ish LED ownership unit. The highest-priority session that
 * currently holds content drives the hardware; releasing or clearing it
 * restores the next session. Releasing the binder (or client death) is
 * equivalent to release().
 */
interface IDashLedSession {
    void setFrame(in DashLedFrame frame);
    void playEffect(in DashLedEffect effect);
    void clear();
    void release();
}
