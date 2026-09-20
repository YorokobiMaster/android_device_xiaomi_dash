/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Standalone host check. Argument is the installed-source stock thermal XML. */
public final class BrightnessCapsTest {
    public static void main(String[] args) throws Exception {
        ThermalBrightnessTable table;
        try (FileInputStream input = new FileInputStream(args[0])) {
            table = new ThermalBrightnessTable(input);
        }
        equal(table.cap(0, 0, 35.99f), Float.NaN);
        equal(table.cap(0, 0, 36), 600);
        equal(table.cap(0, 0, 38), 500);
        equal(table.cap(0, 5499, 42), 250);
        equal(table.cap(0, 5500, 42), 300);
        equal(table.cap(0, 6000, 36), 1000); // manual default
        equal(table.cap(0, 20000, 38), 800);
        equal(table.cap(0, 50000, 38), 1000);
        equal(table.cap(123456, 6000, 36), table.cap(0, 6000, 36));
        equal(table.cap(20, 0, 37), 700);
        equal(table.cap(25, 0, 43), 360);
        equal(table.cap(700, 0, 46), 160);
        equal(table.cap(800, 0, 46), 160);
        equal(table.cap(-2, 5500, 40), 700);
        equal(table.cap(0, 2000001, 38), 1000);
        equal(table.cap(0, Float.NaN, 36), Float.NaN);
        equal(table.cap(0, 6000, Float.NaN), Float.NaN);
        equal(table.cap(0, 6000, 100), Float.NaN);
        equal(table.cap(0, 2000000, 36), Float.NaN);
        equal(ThermalCaps.resolve(new ThermalCaps.Inputs(null, Float.NaN, 500, 0), 0),
                Float.NaN);
        equal(ThermalCaps.resolve(new ThermalCaps.Inputs(table, 36, 500, 0), 0), 500);
        int[] gray = {64,77,90,103,116,122,127,132,137,142,147,152,157,162};
        float[] caps = {3500,3200,3000,2600,2200,2000,1800,1700,1600,1500,
                1400,1300,1200,1100,1060};
        for (int i = 0; i < gray.length; i++) {
            equal(ContentBrightness.sdrCap(20001, gray[i]), caps[i]);
            equal(ContentBrightness.sdrCap(20001, gray[i] + 1), caps[i + 1]);
        }
        equal(ContentBrightness.sdrCap(20000, 0), 1060);
        equal(ContentBrightness.sdrCap(20000, -1), 1060);
        equal(ContentBrightness.sdrCap(20001, 0), 3500);
        equal(ContentBrightness.sdrCap(20001, 255), 1060);
        equal(ContentBrightness.sdrCap(20001, 256), 1060);
        equal(ContentBrightness.sdrCap(20001, -1), Float.NaN);
        equal(ContentBrightness.sdrCap(20001, Integer.MAX_VALUE), 1060);
        equal(ContentBrightness.sdrCap(Float.NaN, 0), Float.NaN);
        if (ContentBrightness.isSamplingEligible(true, true, 20000, false)) {
            throw new AssertionError("sampling enabled at the inclusive lux boundary");
        }
        if (!ContentBrightness.isSamplingEligible(true, true, 20000.01f, false)) {
            throw new AssertionError("sampling disabled above the lux boundary");
        }
        if (!ContentBrightness.isSamplingEligible(true, true, 0, true)) {
            throw new AssertionError("HDR sampling disabled at low lux");
        }
        if (ContentBrightness.isSamplingEligible(true, false, 30000, true)
                || ContentBrightness.isSamplingEligible(false, true, 30000, true)) {
            throw new AssertionError("sampling enabled outside interactive automatic mode");
        }
        equal(ContentBrightness.sdrCap(20001, ContentBrightness.RESET_GRAY), 1060);
        equal(HdrBrightness.cap(true, 100001, ContentBrightness.RESET_GRAY), 1.0f);
        if (!ContentBrightness.isValidGray(0) || !ContentBrightness.isValidGray(255)
                || !ContentBrightness.isValidGray(256)
                || !ContentBrightness.isValidGray(Integer.MAX_VALUE)
                || ContentBrightness.isValidGray(-1)) {
            throw new AssertionError("gray callback validation mismatch");
        }
        try {
            new ThermalBrightnessTable(new ByteArrayInputStream(
                    "<thermal-brightness-config/>".getBytes(StandardCharsets.UTF_8)));
            throw new AssertionError("Missing default accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("BrightnessCapsTest passed");
    }

    private static void equal(float actual, float expected) {
        if (Float.compare(actual, expected) != 0) {
            throw new AssertionError("expected " + expected + ", got " + actual);
        }
    }
}
