/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.util.Spline;

import org.junit.Test;

import java.util.Arrays;

public class DashBrightnessCurvePolicyTest {
    private static final float[] DEFAULT_LUX =
            {0, 30, 300, 3000, 20000, 100000};
    private static final float[] DEFAULT_NITS =
            {3.5f, 83.13f, 116.48182f, 450, 1060, 3500};
    private static final float[] SAMPLE_LUX =
            {0, 1, 3, 10, 30, 100, 300, 1000, 3000, 5000, 10000, 20000, 25000, 100000};
    private static final Spline DEFAULT = Spline.createLinearSpline(DEFAULT_LUX, DEFAULT_NITS);
    private static final Spline NITS_TO_BRIGHTNESS =
            Spline.createLinearSpline(new float[] {0, 3500}, new float[] {0, 1});
    private static final Spline BRIGHTNESS_TO_NITS =
            Spline.createLinearSpline(new float[] {0, 1}, new float[] {0, 3500});

    @Test
    public void allFiveRegionsPreserveUserPointAndMonotonicity() {
        float[] userLux = {1, 100, 1000, 5000, 25000};
        for (float lux : userLux) {
            exercise(lux, DEFAULT.interpolate(lux) * 0.7f);
            exercise(lux, Math.min(3400, DEFAULT.interpolate(lux) * 1.3f));
        }
    }

    @Test
    public void invalidInputDeclinesForPlatformFallback() {
        DashBrightnessCurvePolicy policy = new DashBrightnessCurvePolicy();
        assertTrue(!policy.smoothCurve(
                new float[] {0}, new float[] {0}, 0, DEFAULT_LUX, DEFAULT_NITS,
                NITS_TO_BRIGHTNESS, BRIGHTNESS_TO_NITS));
        assertTrue(!policy.smoothCurve(
                new float[] {-1, 0}, new float[] {0, 0}, 0, DEFAULT_LUX, DEFAULT_NITS,
                NITS_TO_BRIGHTNESS, BRIGHTNESS_TO_NITS));
    }

    private static void exercise(float userLux, float userNits) {
        int insertion = Arrays.binarySearch(SAMPLE_LUX, userLux);
        final float[] lux;
        if (insertion >= 0) {
            lux = Arrays.copyOf(SAMPLE_LUX, SAMPLE_LUX.length);
        } else {
            insertion = -insertion - 1;
            lux = new float[SAMPLE_LUX.length + 1];
            System.arraycopy(SAMPLE_LUX, 0, lux, 0, insertion);
            lux[insertion] = userLux;
            System.arraycopy(SAMPLE_LUX, insertion, lux, insertion + 1,
                    SAMPLE_LUX.length - insertion);
        }
        float[] brightness = new float[lux.length];
        for (int i = 0; i < lux.length; i++) {
            brightness[i] = NITS_TO_BRIGHTNESS.interpolate(DEFAULT.interpolate(lux[i]));
        }
        brightness[insertion] = NITS_TO_BRIGHTNESS.interpolate(userNits);

        assertTrue("lux=" + userLux + " nits=" + userNits,
                new DashBrightnessCurvePolicy().smoothCurve(lux, brightness, insertion,
                        DEFAULT_LUX, DEFAULT_NITS, NITS_TO_BRIGHTNESS, BRIGHTNESS_TO_NITS));
        assertEquals(userNits, BRIGHTNESS_TO_NITS.interpolate(brightness[insertion]), 0.01f);
        for (int i = 0; i < brightness.length; i++) {
            assertTrue(Float.isFinite(brightness[i]));
            if (i > 0) assertTrue(brightness[i] >= brightness[i - 1]);
        }
    }
}
