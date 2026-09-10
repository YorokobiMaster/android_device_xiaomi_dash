// Copyright (C) 2026 GitHub @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0

package me.sandai.dashled.aidl;

import me.sandai.dashled.aidl.DashLedEffect;
import me.sandai.dashled.aidl.DashLedFrame;

/**
 * Exclusive-ish LED ownership unit. The highest-priority session that
 * currently holds content drives the hardware; releasing or clearing it
 * restores the next session. Call release() when finished; death of the
 * clientToken supplied to acquireSession() also releases the session.
 */
interface IDashLedSession {
    void setFrame(in DashLedFrame frame);
    void playEffect(in DashLedEffect effect);
    void clear();
    void release();
}
