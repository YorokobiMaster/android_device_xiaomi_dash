/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.server.display.DeviceShortTermModelPolicy;

import org.junit.Test;

public class DashShortTermModelPolicyTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void policyInterceptsAospLuxWindowReset() {
        DashShortTermModelPolicy policy = new DashShortTermModelPolicy();
        DeviceShortTermModelPolicy.Evaluation result = policy.evaluate(1000, 100, 100, 0);
        assertTrue(result.handled());
        assertTrue(Float.isNaN(result.replacementNits()));
    }

    @Test
    public void sixMinutesAndMoreThanTwentyLuxClampsHighPoint() {
        DashShortTermModelPolicy policy = new DashShortTermModelPolicy();
        policy.onDisplayPolicyChanged(false, 10, 1000);
        policy.onDisplayPolicyChanged(true, 50, 361000);
        DeviceShortTermModelPolicy.Evaluation result = policy.evaluate(50, 100, 500, 361000);
        assertEquals(185, result.replacementNits(), EPSILON);
    }

    @Test
    public void exactlyTwentyLuxDoesNotQualifyAtSixMinutes() {
        DashShortTermModelPolicy policy = new DashShortTermModelPolicy();
        policy.onDisplayPolicyChanged(false, 10, 1000);
        DeviceShortTermModelPolicy.Evaluation result = policy.evaluate(30, 100, 500, 361000);
        assertTrue(Float.isNaN(result.replacementNits()));
    }

    @Test
    public void thirtyMinutesClampsWithoutLuxChange() {
        DashShortTermModelPolicy policy = new DashShortTermModelPolicy();
        policy.onDisplayPolicyChanged(false, 100, 1000);
        DeviceShortTermModelPolicy.Evaluation result =
                policy.evaluate(100, 100, 1, 1_801_000);
        assertEquals(26, result.replacementNits(), EPSILON);
    }

    @Test
    public void envelopeUsesLinearNitsInterpolation() {
        DashShortTermModelPolicy high = new DashShortTermModelPolicy();
        high.onDisplayPolicyChanged(false, 0, 0);
        assertEquals(246.1f, high.evaluate(0, 600, 1000, 1_800_000)
                .replacementNits(), EPSILON);

        DashShortTermModelPolicy low = new DashShortTermModelPolicy();
        low.onDisplayPolicyChanged(false, 0, 0);
        assertEquals(39.4f, low.evaluate(0, 600, 1, 1_800_000)
                .replacementNits(), EPSILON);
    }

    @Test
    public void pointInsideEnvelopeIsRetainedWithoutReinsertion() {
        DashShortTermModelPolicy policy = new DashShortTermModelPolicy();
        policy.onDisplayPolicyChanged(false, 0, 0);
        DeviceShortTermModelPolicy.Evaluation result =
                policy.evaluate(100, 100, 100, 1_800_000);
        assertTrue(result.handled());
        assertTrue(Float.isNaN(result.replacementNits()));
    }
}
