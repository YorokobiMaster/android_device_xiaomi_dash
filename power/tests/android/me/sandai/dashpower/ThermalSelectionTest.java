/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import android.app.ActivityManager;
import android.content.ComponentName;

import junit.framework.TestCase;

/** Uses the framework task object and the predicate called by the production task query. */
public final class ThermalSelectionTest extends TestCase {
    public void testRequestedVisibilityNotCommittedVisibility() {
        ActivityManager.RunningTaskInfo task = new ActivityManager.RunningTaskInfo();
        task.topActivity = new ComponentName("me.sandai.test", "me.sandai.test.Main");
        task.isFocused = true;
        task.isVisibleRequested = true;
        task.isVisible = false;
        assertTrue(ThermalBackend.isForegroundTask(task));

        task.isVisibleRequested = false;
        task.isVisible = true;
        assertFalse(ThermalBackend.isForegroundTask(task));

        task.isVisibleRequested = true;
        task.isFocused = false;
        assertFalse(ThermalBackend.isForegroundTask(task));

        task.isFocused = true;
        task.topActivity = null;
        assertFalse(ThermalBackend.isForegroundTask(task));
    }
}
