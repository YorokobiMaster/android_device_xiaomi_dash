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
        equal(table.cap(0, 0, 35.99f), Float.POSITIVE_INFINITY);
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
        equal(table.cap(0, Float.NaN, 36), Float.NaN);
        equal(table.cap(0, 6000, Float.NaN), Float.NaN);
        equal(table.cap(0, 6000, 100), Float.NaN);
        equal(table.cap(0, 2000000, 36), Float.NaN);
        int[] gray = {64,77,90,103,116,122,127,132,137,142,147,152,157,162};
        float[] caps = {3500,3200,3000,2600,2200,2000,1800,1700,1600,1500,
                1400,1300,1200,1100,1060};
        for (int i = 0; i < gray.length; i++) {
            equal(ContentBrightness.sdrCap(20001, gray[i]), caps[i]);
            equal(ContentBrightness.sdrCap(20001, gray[i] + 1), caps[i + 1]);
        }
        equal(ContentBrightness.sdrCap(20000, 0), 1060);
        equal(ContentBrightness.sdrCap(20001, 0), 3500);
        equal(ContentBrightness.sdrCap(20001, 255), 1060);
        equal(ContentBrightness.sdrCap(20001, -1), Float.NaN);
        equal(ContentBrightness.sdrCap(20001, 256), Float.NaN);
        equal(ContentBrightness.sdrCap(Float.NaN, 0), Float.NaN);
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
