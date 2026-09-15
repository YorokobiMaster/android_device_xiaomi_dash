/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

/** Plain-JDK checks of the fixed dash 305 scenario tree and Switch mapping. */
public final class ThermalScenarioPolicyTest {
    private static int checks;

    private static void expect(int scenario, int profile, String group, String pkg,
            boolean interactive, boolean offHook, int camera, boolean performance,
            boolean lowTempCharge, boolean reverseCharge, String message) {
        ThermalScenarioPolicy.Result result = ThermalScenarioPolicy.select(group, pkg,
                interactive, offHook, camera, performance, lowTempCharge, reverseCharge);
        ++checks;
        if (result.scenario != scenario || result.profile != profile) {
            throw new AssertionError(message + ": got " + result.scenario + "/"
                    + result.profile + ", expected " + scenario + "/" + profile);
        }
    }

    private static void check(boolean value, String message) {
        ++checks;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        expect(0, 0, "unclassified", "me.sandai.unknown", true, false,
                ThermalScenarioPolicy.OFF, false, false, false, "balanced baseline");
        expect(50, 50, "unclassified", "me.sandai.unknown", true, false,
                ThermalScenarioPolicy.OFF, true, false, false, "performance baseline");
        expect(7, 7, "class0", "com.tencent.mm", true, false,
                ThermalScenarioPolicy.OFF, false, false, false, "balanced class0");
        expect(57, 57, "class0", "com.tencent.mm", true, false,
                ThermalScenarioPolicy.OFF, true, false, false, "performance class0");
        expect(19, 19, "game", "me.sandai.game", true, false,
                ThermalScenarioPolicy.OFF, false, false, false, "balanced game");
        expect(69, 18, "game", "me.sandai.game", true, false,
                ThermalScenarioPolicy.OFF, true, false, false, "performance game");
        expect(68, 20, "yuanshen", "com.tencent.tmgp.sgame", true, false,
                ThermalScenarioPolicy.OFF, true, false, false, "performance yuanshen");
        expect(75, 25, "xingtie", "com.miHoYo.Yuanshen", true, false,
                ThermalScenarioPolicy.OFF, true, false, false, "performance xingtie");

        expect(5, 5, "unclassified", "me.sandai.unknown", false, true,
                ThermalScenarioPolicy.OFF, false, false, false, "call without foreground");
        expect(702, 702, "game", "me.sandai.game", true, true,
                ThermalScenarioPolicy.OFF, false, false, false, "game call composite");

        check(ThermalScenarioPolicy.cameraRecordElement(true, 8, 60) == 1, "4K60 element");
        check(ThermalScenarioPolicy.cameraRecordElement(true, 8, 30) == 2, "4K30 element");
        check(ThermalScenarioPolicy.cameraRecordElement(true, 3001, 30) == 2, "8K element");
        check(ThermalScenarioPolicy.cameraRecordElement(true, 6, 60)
                == ThermalScenarioPolicy.OFF, "ordinary recording element");
        check(ThermalScenarioPolicy.cameraRecordElement(false, 8, 60)
                == ThermalScenarioPolicy.OFF, "recording end element");
        expect(16, 16, "camera", "com.android.camera", true, false, 1,
                false, false, false, "4K60 scene");
        expect(17, 17, "camera", "com.android.camera", true, false, 2,
                false, false, false, "4K30 scene");

        expect(27, 27, "unclassified", "", false, false, ThermalScenarioPolicy.OFF,
                false, true, false, "balanced cold charge");
        expect(77, 27, "unclassified", "", false, false, ThermalScenarioPolicy.OFF,
                true, true, false, "performance cold charge maps to base");
        expect(29, 29, "unclassified", "", true, false, ThermalScenarioPolicy.OFF,
                false, false, true, "balanced reverse charge");
        expect(50, 50, "unclassified", "", true, false, ThermalScenarioPolicy.OFF,
                true, false, true, "performance arbitration outranks reverse charge");
        expect(28, 28, "video", "com.ss.android.ugc.aweme", true, false,
                ThermalScenarioPolicy.OFF, false, false, false, "balanced Douyin");
        expect(61, 61, "video", "com.ss.android.ugc.aweme", true, false,
                ThermalScenarioPolicy.OFF, true, false, false, "performance Douyin");

        System.out.println("PASS: " + checks + " thermal scenario checks");
    }

    private ThermalScenarioPolicyTest() {}
}
