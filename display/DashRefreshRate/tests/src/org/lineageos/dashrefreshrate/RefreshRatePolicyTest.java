/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashrefreshrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RefreshRatePolicyTest {
    @Test
    public void awakeWithoutSettingInitializesSixty() {
        FakeStore store = new FakeStore(null);

        policy(store).onDisplayState(RefreshRatePolicy.DisplayState.AWAKE);

        assertEquals("60.0", store.minRefreshRate);
        assertFalse(store.overrideActive);
    }

    @Test
    public void awakeWithExplicitSettingLeavesItAlone() {
        FakeStore store = new FakeStore("90.0");

        policy(store).onDisplayState(RefreshRatePolicy.DisplayState.AWAKE);

        assertEquals("90.0", store.minRefreshRate);
        assertFalse(store.overrideActive);
    }

    @Test
    public void dozeReleasesAndAwakeRestoresMinimum() {
        FakeStore store = new FakeStore("60.0");
        RefreshRatePolicy policy = policy(store);

        policy.onDisplayState(RefreshRatePolicy.DisplayState.DOZE);

        assertEquals("0.0", store.minRefreshRate);
        assertEquals("30.0", store.peakRefreshRate);
        assertEquals(RefreshRatePolicy.DOZE_BRIGHTNESS_LBM, store.dozeBrightness);
        assertEquals("60.0", store.savedAwakeMinRefreshRate);
        assertNull(store.savedAwakePeakRefreshRate);
        assertTrue(store.overrideActive);

        policy.onDisplayState(RefreshRatePolicy.DisplayState.AWAKE);

        assertEquals("60.0", store.minRefreshRate);
        assertNull(store.peakRefreshRate);
        assertEquals(RefreshRatePolicy.DOZE_BRIGHTNESS_NORMAL, store.dozeBrightness);
        assertNull(store.savedAwakeMinRefreshRate);
        assertFalse(store.overrideActive);
    }

    @Test
    public void dozeWithoutSettingRestoresDefaultOnWake() {
        FakeStore store = new FakeStore(null);
        RefreshRatePolicy policy = policy(store);

        policy.onDisplayState(RefreshRatePolicy.DisplayState.DOZE);
        policy.onDisplayState(RefreshRatePolicy.DisplayState.AWAKE);

        assertEquals("60.0", store.minRefreshRate);
        assertFalse(store.overrideActive);
    }

    @Test
    public void dozeRestoresExplicitPeakOnWake() {
        FakeStore store = new FakeStore("60.0");
        store.peakRefreshRate = "120.0";
        RefreshRatePolicy policy = policy(store);

        policy.onDisplayState(RefreshRatePolicy.DisplayState.DOZE);
        policy.onDisplayState(RefreshRatePolicy.DisplayState.AWAKE);

        assertEquals("60.0", store.minRefreshRate);
        assertEquals("120.0", store.peakRefreshRate);
        assertFalse(store.overrideActive);
    }

    @Test
    public void externalChangeDuringDozeIsPreserved() {
        FakeStore store = new FakeStore("60.0");
        RefreshRatePolicy policy = policy(store);
        policy.onDisplayState(RefreshRatePolicy.DisplayState.DOZE);
        store.minRefreshRate = "90.0";

        policy.onDisplayState(RefreshRatePolicy.DisplayState.AWAKE);

        assertEquals("90.0", store.minRefreshRate);
        assertNull(store.peakRefreshRate);
        assertFalse(store.overrideActive);
    }

    @Test
    public void externalPeakChangeDuringDozeIsPreserved() {
        FakeStore store = new FakeStore("60.0");
        RefreshRatePolicy policy = policy(store);
        policy.onDisplayState(RefreshRatePolicy.DisplayState.DOZE);
        store.peakRefreshRate = "90.0";

        policy.onDisplayState(RefreshRatePolicy.DisplayState.AWAKE);

        assertEquals("60.0", store.minRefreshRate);
        assertEquals("90.0", store.peakRefreshRate);
        assertFalse(store.overrideActive);
    }

    @Test
    public void failedDozeWriteDoesNotLeaveActiveOverride() {
        FakeStore store = new FakeStore("60.0");
        store.failNextMinWrite = true;

        policy(store).onDisplayState(RefreshRatePolicy.DisplayState.DOZE);

        assertEquals("60.0", store.minRefreshRate);
        assertFalse(store.overrideActive);
    }

    @Test
    public void dozeBrightnessIsSelectedBeforeMinimumIsReleased() {
        FakeStore store = new FakeStore("60.0");

        policy(store).onDisplayState(RefreshRatePolicy.DisplayState.DOZE);

        assertEquals("brightness=2,begin=60.0/null,peak=30.0,min=0.0",
                store.operations.toString());
    }

    @Test
    public void failedDozeBrightnessDoesNotReleaseMinimum() {
        FakeStore store = new FakeStore("60.0");
        store.failNextDozeBrightness = true;

        policy(store).onDisplayState(RefreshRatePolicy.DisplayState.DOZE);

        assertEquals("60.0", store.minRefreshRate);
        assertFalse(store.overrideActive);
    }

    @Test
    public void wakeRestoresRangeBeforeLeavingDozeBrightness() {
        FakeStore store = new FakeStore("60.0");
        RefreshRatePolicy policy = policy(store);
        policy.onDisplayState(RefreshRatePolicy.DisplayState.DOZE);
        store.operations.setLength(0);

        policy.onDisplayState(RefreshRatePolicy.DisplayState.AWAKE);

        assertEquals("peak=null,min=60.0,clear,brightness=0", store.operations.toString());
    }

    @Test
    public void failedWakeRestoreKeepsDozeBrightnessAndOverride() {
        FakeStore store = new FakeStore("60.0");
        RefreshRatePolicy policy = policy(store);
        policy.onDisplayState(RefreshRatePolicy.DisplayState.DOZE);
        store.failNextMinWrite = true;

        policy.onDisplayState(RefreshRatePolicy.DisplayState.AWAKE);

        assertEquals("0.0", store.minRefreshRate);
        assertEquals(RefreshRatePolicy.DOZE_BRIGHTNESS_LBM, store.dozeBrightness);
        assertTrue(store.overrideActive);
    }

    @Test
    public void failedDozePeakWriteRestoresMinimumAndClearsOverride() {
        FakeStore store = new FakeStore("60.0");
        store.failNextPeakWrite = true;

        policy(store).onDisplayState(RefreshRatePolicy.DisplayState.DOZE);

        assertEquals("60.0", store.minRefreshRate);
        assertNull(store.peakRefreshRate);
        assertFalse(store.overrideActive);
    }

    private static RefreshRatePolicy policy(FakeStore store) {
        return new RefreshRatePolicy(store, message -> {});
    }

    private static final class FakeStore implements RefreshRatePolicy.Store {
        String minRefreshRate;
        String peakRefreshRate;
        String savedAwakeMinRefreshRate;
        String savedAwakePeakRefreshRate;
        boolean overrideActive;
        boolean failNextMinWrite;
        boolean failNextPeakWrite;
        boolean failNextDozeBrightness;
        int dozeBrightness = RefreshRatePolicy.DOZE_BRIGHTNESS_NORMAL;
        final StringBuilder operations = new StringBuilder();

        FakeStore(String minRefreshRate) {
            this.minRefreshRate = minRefreshRate;
        }

        @Override
        public String readMinRefreshRate() {
            return minRefreshRate;
        }

        @Override
        public boolean writeMinRefreshRate(String value) {
            if (failNextMinWrite) {
                failNextMinWrite = false;
                return false;
            }
            minRefreshRate = value;
            appendOperation("min=" + value);
            return true;
        }

        @Override
        public String readPeakRefreshRate() {
            return peakRefreshRate;
        }

        @Override
        public boolean writePeakRefreshRate(String value) {
            if (failNextPeakWrite) {
                failNextPeakWrite = false;
                return false;
            }
            peakRefreshRate = value;
            appendOperation("peak=" + value);
            return true;
        }

        @Override
        public boolean isDozeOverrideActive() {
            return overrideActive;
        }

        @Override
        public String readAwakeMinRefreshRate() {
            return savedAwakeMinRefreshRate;
        }

        @Override
        public String readAwakePeakRefreshRate() {
            return savedAwakePeakRefreshRate;
        }

        @Override
        public boolean beginDozeOverride(String awakeMinRefreshRate, String awakePeakRefreshRate) {
            savedAwakeMinRefreshRate = awakeMinRefreshRate;
            savedAwakePeakRefreshRate = awakePeakRefreshRate;
            overrideActive = true;
            appendOperation("begin=" + awakeMinRefreshRate + "/" + awakePeakRefreshRate);
            return true;
        }

        @Override
        public void clearDozeOverride() {
            savedAwakeMinRefreshRate = null;
            savedAwakePeakRefreshRate = null;
            overrideActive = false;
            appendOperation("clear");
        }

        @Override
        public boolean setDozeBrightness(int value) {
            if (failNextDozeBrightness) {
                failNextDozeBrightness = false;
                return false;
            }
            dozeBrightness = value;
            appendOperation("brightness=" + value);
            return true;
        }

        private void appendOperation(String operation) {
            if (operations.length() > 0) {
                operations.append(',');
            }
            operations.append(operation);
        }
    }
}
