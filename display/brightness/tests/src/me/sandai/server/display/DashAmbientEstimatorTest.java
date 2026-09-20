/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Focused checks for assist ownership and stock arbitration flags. */
public class DashAmbientEstimatorTest {
    private static DashAmbientEstimator.MainState main(float fast, float slow, float bright,
            float small, float dark, long brightDue, long darkDue) {
        return new DashAmbientEstimator.MainState(true, fast, slow, bright, small, dark,
                brightDue, darkDue);
    }

    @Test
    public void stockThresholdKnotsAndHbmStrictBoundary() {
        assertEquals(160, DashAmbientEstimator.brightThreshold(30), 0);
        assertEquals(108, DashAmbientEstimator.smallThreshold(30), 0);
        assertEquals(23.064001f, DashAmbientEstimator.smallThreshold(15), 0);
        assertTrue(DashAmbientEstimator.brightThreshold(7180) > 7181);
        assertEquals(7182, DashAmbientEstimator.brightThreshold(7181), 0);
    }

    @Test
    public void assistHistoryUsesStockIntegralAndRejectsBadOrder() {
        DashAmbientEstimator.History history = new DashAmbientEstimator.History();
        history.push(0, 10);
        history.push(1000, 100);
        assertEquals((10 * 1000000f + 100 * 155000f) / 1155000f,
                history.average(1000, 1000), .0001f);
        assertFalse(history.push(999, 20));
        assertFalse(history.push(1001, Float.NaN));
    }

    @Test
    public void mainInitializationSeedsLuxWithoutBrightness() {
        DashAmbientEstimator estimator = new DashAmbientEstimator();
        estimator.start(10000);
        DashAmbientEstimator.Decision d = estimator.onMain(10000,
                main(100, 100, 160, 108, 40, 10000, 10000), true);
        assertEquals(DashAmbientEstimator.MAIN, d.event());
        assertTrue(d.updateLux());
        assertFalse(d.updateBrightness());
        assertEquals(DashAmbientEstimator.MAIN, estimator.selected());
    }

    @Test
    public void mainBrightenBlockedByAssistReanchorsWithoutChangingFinalLux() {
        DashAmbientEstimator estimator = new DashAmbientEstimator();
        estimator.start(10000);
        estimator.onMain(10000, main(100, 100, 160, 108, 40, 10000, 10000), true);
        estimator.assist(10000, 300);
        estimator.onAssist(10000, main(100, 100, 160, 108, 40, 10000, 10000));
        assertEquals(DashAmbientEstimator.ASSIST, estimator.selected());
        DashAmbientEstimator.Decision d = estimator.onMain(12000,
                main(200, 200, 160, 108, 40, 12000, 20000), false);
        assertFalse(d.updateLux());
        assertFalse(d.updateBrightness());
        assertEquals(DashAmbientEstimator.MAIN, d.event());
    }

    @Test
    public void mainDarkSlowGateBlocksFastFallThenAllowsAssistSelection() {
        DashAmbientEstimator estimator = new DashAmbientEstimator();
        estimator.start(10000);
        estimator.onMain(10000, main(100, 100, 160, 108, 40, 10000, 10000), true);
        estimator.assist(10000, 50);
        estimator.onAssist(10000, main(100, 100, 160, 108, 40, 10000, 10000));
        DashAmbientEstimator.Decision blocked = estimator.onMain(11000,
                main(0, 200, 160, 108, 40, 11000, 11000), false);
        assertEquals(DashAmbientEstimator.INVALID, blocked.event());
        assertEquals("main-dark-slow-gate", blocked.reason());
        DashAmbientEstimator.Decision due = estimator.onMain(12000,
                main(0, 40, 160, 108, 40, 12000, 12000), false);
        assertEquals(DashAmbientEstimator.ASSIST, estimator.selected());
        assertTrue(due.updateLux());
    }

    @Test
    public void assistDarkDueReturnsMainByStrictFastComparison() {
        DashAmbientEstimator estimator = new DashAmbientEstimator();
        estimator.start(10000);
        estimator.onMain(10000, main(100, 100, 160, 108, 40, 10000, 10000), true);
        estimator.assist(10000, 300);
        DashAmbientEstimator.Decision overtake = estimator.onAssist(10000,
                main(100, 100, 160, 108, 40, 10000, 10000));
        assertEquals(DashAmbientEstimator.ASSIST, overtake.event());
        for (long t = 12000; t <= 17000; t += 250) estimator.assist(t, 0);
        DashAmbientEstimator.Decision regain = estimator.onAssist(18000,
                main(300, 300, 160, 108, 40, 18000, 18000));
        assertEquals(DashAmbientEstimator.MAIN, estimator.selected());
        assertEquals(DashAmbientEstimator.ASSIST, regain.event());
        assertEquals(300, regain.lux(), 0);
        assertTrue(regain.updateLux());
        assertTrue(regain.updateBrightness());
    }

    @Test
    public void assistBrightDueStaysAssistWhenItsFastLuxWins() {
        DashAmbientEstimator estimator = new DashAmbientEstimator();
        estimator.start(10000);
        DashAmbientEstimator.MainState main = main(100, 100, 160, 108, 40, 10000, 10000);
        estimator.onMain(10000, main, true);
        estimator.assist(10000, 300);
        estimator.onAssist(10000, main);
        for (long t = 12000; t <= 13500; t += 250) estimator.assist(t, 1000);
        DashAmbientEstimator.Decision stays = estimator.onAssist(13500, main);
        assertEquals(DashAmbientEstimator.ASSIST, estimator.selected());
        assertEquals(DashAmbientEstimator.ASSIST, stays.event());
        assertTrue(stays.lux() > main.fast());
    }

    @Test
    public void assistHasIndependentDeadlineAndTorchCooldown() {
        DashAmbientEstimator estimator = new DashAmbientEstimator();
        estimator.start(10000);
        estimator.onMain(10000, main(100, 100, 160, 108, 40, 10000, 10000), true);
        estimator.torch(11000, true);
        estimator.assist(11000, 300);
        assertEquals(1, estimator.droppedAssistTorch());
        estimator.torch(12000, false);
        estimator.assist(13800, 300);
        assertEquals(1, estimator.droppedAssistCooldown());
        assertEquals(13801, estimator.cooldownUntil());
        estimator.assist(13801, 300);
        estimator.onAssist(13801, main(100, 100, 160, 108, 40, 10000, 10000));
        assertTrue(estimator.assistNext() != Long.MAX_VALUE);
    }
}
