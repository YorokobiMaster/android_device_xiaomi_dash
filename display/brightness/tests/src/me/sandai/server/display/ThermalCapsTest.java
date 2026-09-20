/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** Deterministic thermal cap arithmetic and stock boundary semantics. */
public class ThermalCapsTest {
    private static final String XML = """
            <thermal-brightness-config>
              <thermal-condition-item>
                <identifier>0</identifier>
                <lux-temperature-pair>
                  <min-inclusive>0</min-inclusive>
                  <max-exclusive>100</max-exclusive>
                  <temperature-brightness-pair>
                    <min-inclusive>10</min-inclusive>
                    <max-exclusive>20</max-exclusive>
                    <nit>1000</nit>
                  </temperature-brightness-pair>
                  <temperature-brightness-pair>
                    <min-inclusive>20</min-inclusive>
                    <max-exclusive>40</max-exclusive>
                    <nit>500</nit>
                  </temperature-brightness-pair>
                </lux-temperature-pair>
                <lux-temperature-pair>
                  <min-inclusive>100</min-inclusive>
                  <max-exclusive>200</max-exclusive>
                  <temperature-brightness-pair>
                    <min-inclusive>20</min-inclusive>
                    <max-exclusive>40</max-exclusive>
                    <nit>300</nit>
                  </temperature-brightness-pair>
                </lux-temperature-pair>
              </thermal-condition-item>
              <thermal-condition-item>
                <identifier>-2</identifier>
                <lux-temperature-pair>
                  <min-inclusive>0</min-inclusive>
                  <max-exclusive>200</max-exclusive>
                  <temperature-brightness-pair>
                    <min-inclusive>20</min-inclusive>
                    <max-exclusive>30</max-exclusive>
                    <nit>700</nit>
                  </temperature-brightness-pair>
              </lux-temperature-pair>
              </thermal-condition-item>
            </thermal-brightness-config>
            """;

    private static ThermalBrightnessTable table() throws Exception {
        return new ThermalBrightnessTable(new ByteArrayInputStream(
                XML.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void finiteTableSafetyTightensThenZeroClearsSafetyCap() throws Exception {
        ThermalBrightnessTable table = table();
        assertEquals(800, ThermalCaps.resolve(
                new ThermalCaps.Inputs(table, 15, 800, 0), 10), 0);
        assertEquals(1000, ThermalCaps.resolve(
                new ThermalCaps.Inputs(table, 15, 0, 0), 10), 0);
    }

    @Test
    public void safetyOnlyInputRemainsUnavailable() {
        assertTrue(Float.isNaN(ThermalCaps.resolve(
                new ThermalCaps.Inputs(null, Float.NaN, 800, null), 10)));
        assertTrue(Float.isNaN(ThermalCaps.resolve(
                new ThermalCaps.Inputs(null, Float.NaN, 0, null), 10)));
    }

    @Test
    public void noInputIsUnavailable() {
        assertTrue(Float.isNaN(ThermalCaps.resolve(ThermalCaps.Inputs.EMPTY, 10)));
    }

    @Test
    public void sameCapTreatsNaNAsUnchanged() {
        assertTrue(ThermalCaps.sameCap(Float.NaN, Float.NaN));
        assertTrue(ThermalCaps.sameCap(800, 800));
        assertFalse(ThermalCaps.sameCap(Float.NaN, 800));
        assertFalse(ThermalCaps.sameCap(800, 1000));
    }

    @Test
    public void temperatureHysteresisMatchesStockBoundaries() {
        assertTrue(ThermalCaps.equivalentTemperature(40, 40));
        assertTrue(ThermalCaps.equivalentTemperature(Float.NaN, Float.NaN));
        assertFalse(ThermalCaps.equivalentTemperature(Float.NaN, 40));
        assertTrue(ThermalCaps.equivalentTemperature(40.1f, 40.9f));
        assertTrue(ThermalCaps.equivalentTemperature(40.9f, 41.39f));
        assertFalse(ThermalCaps.equivalentTemperature(40.9f, 41.4f));
    }

    @Test
    public void belowMinimumTemperatureHasNoTableCap() throws Exception {
        assertTrue(Float.isNaN(table().cap(0, 10, 9.99f)));
    }

    @Test
    public void unknownConditionUsesDefault() throws Exception {
        ThermalBrightnessTable table = table();
        assertEquals(table.cap(0, 10, 15), table.cap(123456, 10, 15), 0);
    }

    @Test
    public void aboveLastLuxUsesHighestBand() throws Exception {
        assertEquals(300, table().cap(0, 200, 25), 0);
        assertEquals(300, table().cap(0, 2000000, 25), 0);
    }

    @Test
    public void hdrConditionUsesMinusTwo() throws Exception {
        assertEquals(700, table().cap(-2, 6000, 25), 0);
    }
}
