/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

/** Stock case 95000 is raw integer grayscale, not a normalized fraction. */
final class ContentBrightness {
    static final float LUX_THRESHOLD = 20000;
    static final int RESET_GRAY = 163;
    private static final int[] GRAY = {64, 77, 90, 103, 116, 122, 127, 132, 137, 142,
            147, 152, 157, 162};
    private static final float[] NITS = {3500, 3200, 3000, 2600, 2200, 2000, 1800, 1700,
            1600, 1500, 1400, 1300, 1200, 1100, 1060};

    static float sdrCap(float lux, int gray) {
        if (!Float.isFinite(lux) || lux < 0) return Float.NaN;
        if (lux <= LUX_THRESHOLD) return 1060;
        if (!isValidGray(gray)) return Float.NaN;
        int i = 0;
        while (i < GRAY.length && gray > GRAY[i]) i++;
        return NITS[i];
    }

    /** Stock case 95000 sampling gate. The lux argument is the raw automatic-lux value. */
    static boolean isSamplingEligible(boolean interactive, boolean automatic, float ambientLux,
            boolean hdrLayerPresent) {
        return interactive && automatic && (hdrLayerPresent
                || (Float.isFinite(ambientLux) && ambientLux > LUX_THRESHOLD));
    }

    static boolean isValidGray(int gray) {
        return gray >= 0;
    }
}
