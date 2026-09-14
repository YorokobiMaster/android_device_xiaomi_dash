/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

/** Stock case 95000 is raw integer grayscale, not a normalized fraction. */
final class ContentBrightness {
    private static final int[] GRAY = {64, 77, 90, 103, 116, 122, 127, 132, 137, 142,
            147, 152, 157, 162};
    private static final float[] NITS = {3500, 3200, 3000, 2600, 2200, 2000, 1800, 1700,
            1600, 1500, 1400, 1300, 1200, 1100, 1060};

    static float sdrCap(float lux, int gray) {
        if (!Float.isFinite(lux) || lux < 0 || gray < 0 || gray > 255) return Float.NaN;
        if (lux <= 20000) return 1060;
        int i = 0;
        while (i < GRAY.length && gray > GRAY[i]) i++;
        return NITS[i];
    }
}
