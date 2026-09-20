/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HdrBrightnessTest {
    @Test
    public void peakBoundaryMatchesStock() {
        assertEquals(HdrBrightness.NORMAL_MAX, HdrBrightness.cap(true, 100000, 219), 0);
        assertEquals(1.0f, HdrBrightness.cap(true, 100001, 219), 0);
        assertEquals(HdrBrightness.NORMAL_MAX, HdrBrightness.cap(true, 100001, 220), 0);
        assertEquals(HdrBrightness.NORMAL_MAX, HdrBrightness.cap(true, 100001, -1), 0);
        assertEquals(HdrBrightness.NORMAL_MAX, HdrBrightness.cap(false, 100001, 0), 0);
    }

    @Test
    public void hdrLayerOverridesThermalCondition() {
        assertEquals(Integer.valueOf(-2), HdrBrightness.thermalCondition(true, 25));
        assertEquals(Integer.valueOf(25), HdrBrightness.thermalCondition(false, 25));
        assertEquals(null, HdrBrightness.thermalCondition(false, null));
    }
}
