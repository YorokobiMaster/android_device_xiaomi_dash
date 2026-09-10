/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dasheyecare;

/** Map the native slider's CCT to vendor strength, preserving both defaults. */
public final class NightLightLevel {
    private NightLightLevel() {}

    public static int fromTemperature(boolean enabled, int temperature,
            int minimum, int defaultTemperature, int maximum) {
        if (!enabled) return 0;
        int value = Math.max(minimum, Math.min(maximum, temperature));
        int pivot = Math.max(minimum, Math.min(maximum, defaultTemperature));
        if (value == pivot) return 156;
        if (value < pivot) {
            return 156 + Math.round(99f * (pivot - value) / (pivot - minimum));
        }
        return 156 - Math.round(98f * (value - pivot) / (maximum - pivot));
    }
}
