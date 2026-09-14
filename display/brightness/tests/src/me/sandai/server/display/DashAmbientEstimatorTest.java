/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Deterministic stock-policy replay; no sensor, camera, or wall-clock dependencies. */
public class DashAmbientEstimatorTest {
    private static DashAmbientEstimator initialized(float lux) {
        DashAmbientEstimator policy = new DashAmbientEstimator();
        policy.start(10000);
        policy.primary(10000, lux);
        assertEquals(lux, policy.evaluate(10000), .001f);
        return policy;
    }

    @Test
    public void stockSplineKnotsAndHbmStrictBoundary() {
        assertEquals(160, DashAmbientEstimator.brightThreshold(30), 0);
        assertEquals(108, DashAmbientEstimator.smallThreshold(30), 0);
        assertEquals(23.064001f, DashAmbientEstimator.smallThreshold(15), 0);
        assertTrue(DashAmbientEstimator.brightThreshold(7180) > 7181);
        assertEquals(7182, DashAmbientEstimator.brightThreshold(7181), 0);
        assertEquals(7182, DashAmbientEstimator.smallThreshold(7181), 0);
    }

    @Test
    public void historyUsesStockIntegralAndRetainsLastSample() {
        DashAmbientEstimator.History history = new DashAmbientEstimator.History();
        history.push(0, 10);
        history.push(1000, 100);
        // [-1000,0] weight=1,000,000; [0,100] weight=155,000.
        assertEquals((10 * 1000000f + 100 * 155000f) / 1155000f,
                history.average(1000, 1000), .0001f);
        history.prune(10000);
        assertEquals(100, history.average(15000, 1000), 0);
        assertEquals(1, history.samples.size());
        assertFalse(history.push(9999, 20));
        assertFalse(history.push(15000, Float.NaN));
    }

    @Test
    public void rawDebounceRequiresStrictCrossing() {
        DashAmbientEstimator.History history = new DashAmbientEstimator.History();
        history.push(0, 100);
        assertEquals(2000, history.transition(1000, 100, true, 1000));
        assertEquals(2000, history.transition(1000, 100, false, 1000));
        history.push(1000, 101);
        assertEquals(2000, history.transition(1500, 100, true, 1000));
    }

    @Test
    public void assistInitializationIsArbitrationNotMaximum() {
        DashAmbientEstimator policy = initialized(100);
        policy.assist(10000, 200); // Above main lux but below its normal brightening threshold.
        assertTrue(Float.isNaN(policy.evaluate(10000)));
        assertEquals(DashAmbientEstimator.MAIN, policy.selected());
        policy.start(20000);
        policy.primary(20000, 100);
        policy.evaluate(20000);
        policy.assist(20000, 300); // Initial rear can cross the main threshold immediately.
        assertEquals(300, policy.evaluate(20000), 0);
        assertEquals(DashAmbientEstimator.ASSIST, policy.selected());
    }

    @Test
    public void assistCannotInitializeWithoutPrimaryOrDaemon() {
        DashAmbientEstimator policy = new DashAmbientEstimator();
        policy.start(10000);
        policy.assist(10000, 300);
        assertTrue(Float.isNaN(policy.evaluate(10000)));
        policy.primary(10000, 100);
        assertEquals(100, policy.evaluate(10000), 0);
    }

    @Test
    public void normalBrighteningWaitsOneSecond() {
        DashAmbientEstimator policy = initialized(100);
        policy.primary(14000, 500);
        assertTrue(Float.isNaN(policy.evaluate(14999)));
        assertEquals(500, policy.evaluate(15000), .001f);
    }

    @Test
    public void smallBrighteningWaitsFiveSeconds() {
        DashAmbientEstimator policy = initialized(100);
        policy.primary(14000, 220);
        assertTrue(Float.isNaN(policy.evaluate(18999)));
        assertEquals(220, policy.evaluate(19000), .001f);
    }

    @Test
    public void mainDarkeningSwitchesToHigherAssist() {
        DashAmbientEstimator policy = initialized(100);
        policy.assist(10000, 50);
        policy.primary(14000, 0);
        assertTrue(Float.isNaN(policy.evaluate(14999)));
        assertEquals(50, policy.evaluate(15000), .001f);
        assertEquals(DashAmbientEstimator.ASSIST, policy.selected());
        policy.assist(16000, 0);
        policy.evaluate(16999);
        assertEquals(0, policy.evaluate(17000), .001f);
        assertEquals(DashAmbientEstimator.MAIN, policy.selected());
    }

    @Test
    public void torchDropsSamplesThroughStrictCloseBoundary() {
        DashAmbientEstimator policy = initialized(100);
        policy.torch(11000, true);
        policy.assist(11000, 100000);
        assertTrue(Float.isNaN(policy.evaluate(11000)));
        policy.torch(12000, false);
        assertFalse(policy.assistAllowed(13800));
        policy.assist(13800, 100000);
        assertTrue(Float.isNaN(policy.evaluate(13800)));
        assertTrue(policy.assistAllowed(13801));
        policy.assist(13801, 300);
        assertEquals(300, policy.evaluate(13801), .001f);
    }

    @Test
    public void nonUiOnlyZeroTransitionOpensTwoSecondFastWindow() {
        DashAmbientEstimator policy = initialized(100);
        policy.nonUi(20000, 0); // Initial zero is not an exit transition.
        policy.primary(20000, 200);
        assertTrue(Float.isNaN(policy.evaluate(20000)));
        policy.nonUi(21000, 1);
        policy.nonUi(22000, 0);
        policy.primary(24000, 500); // Inclusive window endpoint and all three lux gates.
        assertEquals(500, policy.evaluate(24000), 0);
        policy.primary(24001, 2000);
        assertTrue(Float.isNaN(policy.evaluate(24001)));
    }

    @Test
    public void fastSkipMustAlsoBeatAssistAndAbsoluteThreshold() {
        DashAmbientEstimator policy = initialized(100);
        policy.assist(10000, 500);
        policy.evaluate(10000);
        policy.primary(11000, 400);
        assertTrue(Float.isNaN(policy.evaluate(11000)));
        assertEquals(DashAmbientEstimator.ASSIST, policy.selected());
        policy.primary(11001, 900);
        assertEquals(900, policy.evaluate(11001), 0);
        assertEquals(DashAmbientEstimator.MAIN, policy.selected());
    }

    @Test
    public void repeatedStepsDelayMainDarkeningAndExpiryRestoresNormalDebounce() {
        DashAmbientEstimator policy = initialized(100);
        policy.step(14000);
        policy.primary(14000, 0);
        assertTrue(Float.isNaN(policy.evaluate(15000)));
        policy.step(15000);
        assertTrue(Float.isNaN(policy.evaluate(16000)));
        assertTrue(Float.isNaN(policy.evaluate(16300)));
        // Stock expiry changes the debounce; the next ALS sample triggers reevaluation.
        policy.primary(16300, 0);
        assertEquals(0, policy.evaluate(16300), .001f);
    }

    @Test
    public void fastRampUsesNitsAndRememberedTargetThenExpires() {
        DashAmbientEstimator policy = initialized(100);
        assertFalse(policy.fastRamp(11000, .1f, .2f, 100, 155.5f));
        assertTrue(policy.fastRamp(11000, .1f, .2f, 100, 160));
        assertTrue(policy.fastRamp(12000, .15f, .2f, 140, 160));
        assertFalse(policy.fastRamp(12001, .2f, .3f, 160, 180));
        assertTrue(policy.fastRamp(12500, .1f, .2f, 100, 160));
        assertFalse(policy.fastRamp(14500, .1f, .2f, 100, 160));
        policy.start(20000);
        assertFalse(policy.fastRamp(24000, .1f, .2f, 100, 160));
    }

    @Test
    public void restartDiscardsHistoryAndPocketState() {
        DashAmbientEstimator policy = initialized(100);
        policy.nonUi(14000, 1);
        policy.nonUi(15000, 0);
        policy.start(20000);
        assertEquals(Long.MAX_VALUE, policy.nextEvaluation());
        assertTrue(Float.isNaN(policy.evaluate(20000)));
        policy.primary(20000, 20);
        assertEquals(20, policy.evaluate(20000), 0);
    }

    @Test
    public void sensorTimestampConversionRejectsPreSessionAndFutureEvents() {
        assertEquals(-1, DashAmbientEstimator.eventUptime(999, 1000, 10000000, 20));
        assertEquals(-1, DashAmbientEstimator.eventUptime(10000001, 1000, 10000000, 20));
        assertEquals(15, DashAmbientEstimator.eventUptime(5000000, 1000, 10000000, 20));
        // A large suspend offset must not turn elapsedRealtime into Handler uptime.
        assertEquals(15, DashAmbientEstimator.eventUptime(9005000000L, 9000000000L,
                9010000000L, 20));
    }
}
