/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

public final class ThermalProfilesTest {
    public static void main(String[] args) {
        for (int id : new int[] {-1, 0, 1, 6, 7, 9, 10, 11, 12, 15, 19, 20, 25}) {
            if (!ThermalProfiles.selectable(id)) throw new AssertionError("Rejected " + id);
        }
        for (int id : new int[] {-2, 2, 18, 50, 57, 61, 1000, 1001, 1002, 1003}) {
            if (ThermalProfiles.selectable(id)) throw new AssertionError("Accepted " + id);
        }
        equal(7, ThermalProfiles.forGroup("class0", false));
        equal(57, ThermalProfiles.forGroup("class0", true));
        equal(11, ThermalProfiles.forGroup("video", false));
        equal(61, ThermalProfiles.forGroup("video", true));
        equal(0, ThermalProfiles.forGroup("unclassified", true));
        System.out.println("ThermalProfilesTest passed");
    }

    private static void equal(int expected, int actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
}
