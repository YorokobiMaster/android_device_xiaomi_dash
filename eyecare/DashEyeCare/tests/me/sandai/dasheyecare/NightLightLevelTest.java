/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dasheyecare;

import static org.junit.Assert.*;
import org.junit.Test;

public class NightLightLevelTest {
    private int level(boolean enabled, int temperature) {
        return NightLightLevel.fromTemperature(enabled, temperature, 2596, 2850, 4082);
    }
    @Test public void offAlwaysDisablesVendorEffect() {
        assertEquals(0, level(false, 2596));
        assertEquals(0, level(false, 4082));
    }
    @Test public void endpointsAndDefaultMatchVendorLevels() {
        assertEquals(255, level(true, 2596));
        assertEquals(156, level(true, 2850));
        assertEquals(58, level(true, 4082));
    }
    @Test public void strengthNeverIncreasesAsTemperatureRises() {
        int previous = 255;
        for (int cct = 2596; cct <= 4082; cct++) {
            int value = level(true, cct);
            assertTrue(value <= previous);
            assertTrue(value >= 58 && value <= 255);
            previous = value;
        }
    }
    @Test public void staleSettingOutsideResourceRangeIsClamped() {
        assertEquals(255, level(true, 1000));
        assertEquals(58, level(true, 9000));
    }
    @Test public void usesDeviceResourceRangeRatherThanFixedKelvinValues() {
        assertEquals(255, NightLightLevel.fromTemperature(true, 2000, 2000, 4000, 6000));
        assertEquals(156, NightLightLevel.fromTemperature(true, 4000, 2000, 4000, 6000));
        assertEquals(58, NightLightLevel.fromTemperature(true, 6000, 2000, 4000, 6000));
    }
}
