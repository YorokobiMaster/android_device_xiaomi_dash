/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DashBrightnessRampPolicyTest {
    private static final String BRIGHTNESS = "screenBrightnessFloat";
    private static final float EPSILON = 0.000001f;

    @Test
    public void brighteningBranchesMatchStockAnchors() {
        DashBrightnessRampPolicy policy = policy(1000.0f, false);

        // Below 35 nit: direct four-second branch.
        assertEquals(0.005f, rate(policy, 0.01f, 0.02f, 0.03f), EPSILON);
        // Crossing 35 toward below 265: 1.8-second 35-nit leg.
        assertEquals((0.035f - 0.01f) / 1.8f,
                rate(policy, 0.01f, 0.02f, 0.2f), EPSILON);
        // Once past 35 nit, the remainder runs from the 1.8 s anchor to 4 s.
        assertEquals((float) (Math.log(0.2f / 0.035f) / (4.0f - 1.8f) * 0.1f),
                rate(policy, 0.01f, 0.1f, 0.2f), EPSILON);
        // Crossing 35, 87.450005, and 265: 0.8-second 35-nit leg.
        assertEquals((0.035f - 0.01f) / 0.8f,
                rate(policy, 0.01f, 0.02f, 0.4f), EPSILON);
        // Starting at/above 265: the complete four-second exponential segment.
        assertEquals((float) (Math.log(0.8f / 0.4f) / 4.0f * 0.5f),
                rate(policy, 0.4f, 0.5f, 0.8f), EPSILON);
        // A transition wholly between 35 and 265 nit uses the full 0-4 s segment.
        assertEquals((float) (Math.log(0.2f / 0.05f) / 4.0f * 0.1f),
                rate(policy, 0.05f, 0.1f, 0.2f), EPSILON);
    }

    @Test
    public void darkeningUsesHbmAndOrdinaryBands() {
        DashBrightnessRampPolicy policy = policy(600.0f, true);

        assertPositive(rate(policy, 0.8f, 0.7f, 0.5f)); // above HBM transition
        assertPositive(rate(policy, 0.2f, 0.15f, 0.1f)); // ordinary 150/100 nit band
    }

    @Test
    public void darkeningUsesFastLowLightTail() {
        DashBrightnessRampPolicy policy = policy(1000.0f, false);
        assertEquals(40.0f / 16383.0f, rate(policy, 0.03f, 0.025f, 0.02f), EPSILON);
    }

    @Test
    public void minimumRateIsClamped() {
        DashBrightnessRampPolicy policy = policy(1_000_000.0f, false);
        assertEquals(1.0f / 16383.0f, rate(policy, 0.2f, 0.19f, 0.1f), EPSILON);
    }

    @Test
    public void invalidMappingAndDirectionDecline() {
        DashBrightnessRampPolicy invalid = new DashBrightnessRampPolicy(new Mapping() {
            @Override public float backlightFromBrightness(float brightness) { return Float.NaN; }
            @Override public float nitsFromBacklight(float backlight) { return Float.NaN; }
            @Override public float backlightFromNits(float nits) { return Float.NaN; }
            @Override public float brightnessFromBacklight(float backlight) { return Float.NaN; }
        }, Float.NaN);
        assertTrue(Float.isNaN(rate(invalid, 0.1f, 0.1f, 0.2f)));

        DashBrightnessRampPolicy policy = policy(1000.0f, false);
        assertTrue(Float.isNaN(rate(policy, 0.1f, 0.05f, 0.2f)));
        assertTrue(Float.isNaN(policy.getRate("otherProperty", 0.1f, 0.1f, 0.2f)));
    }

    @Test
    public void frameworkAndBacklightScalesAreNotInterchangeable() {
        DashBrightnessRampPolicy identity = policy(1000.0f, false);
        DashBrightnessRampPolicy nonIdentity = new DashBrightnessRampPolicy(
                new NonIdentityMapping(), Float.NaN);

        float identityRate = rate(identity, 0.1f, 0.2f, 0.4f);
        float nonIdentityRate = rate(nonIdentity, 0.1f, 0.2f, 0.4f);
        assertNotNaN(identityRate);
        assertNotNaN(nonIdentityRate);
        assertTrue(Math.abs(identityRate - nonIdentityRate) > EPSILON);
    }

    private static DashBrightnessRampPolicy policy(float nitsPerBrightness, boolean hbm) {
        return new DashBrightnessRampPolicy(new LinearMapping(nitsPerBrightness),
                hbm ? 0.6f : Float.NaN);
    }

    private static float rate(DashBrightnessRampPolicy policy, float start, float current,
            float target) {
        return policy.getRate(BRIGHTNESS, start, current, target);
    }

    private static void assertPositive(float rate) {
        assertNotNaN(rate);
        assertTrue(Float.isFinite(rate) && rate > 0.0f);
    }

    private static void assertNotNaN(float rate) {
        assertTrue(!Float.isNaN(rate));
    }

    private interface Mapping extends DashBrightnessRampPolicy.Mapping {}

    private static final class LinearMapping implements Mapping {
        private final float mNitsPerBrightness;

        LinearMapping(float nitsPerBrightness) {
            mNitsPerBrightness = nitsPerBrightness;
        }

        @Override public float backlightFromBrightness(float brightness) { return brightness; }
        @Override public float nitsFromBacklight(float backlight) {
            return backlight * mNitsPerBrightness;
        }
        @Override public float backlightFromNits(float nits) {
            return nits / mNitsPerBrightness;
        }
        @Override public float brightnessFromBacklight(float backlight) { return backlight; }
    }

    private static final class NonIdentityMapping implements Mapping {
        @Override public float backlightFromBrightness(float brightness) {
            return 0.001f + 0.999f * brightness;
        }
        @Override public float nitsFromBacklight(float backlight) {
            return backlight * 1000.0f;
        }
        @Override public float backlightFromNits(float nits) {
            return nits / 1000.0f;
        }
        @Override public float brightnessFromBacklight(float backlight) {
            return (backlight - 0.001f) / 0.999f;
        }
    }
}
