/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

/** Fixed dash 305 scenario tree and SwitchProcessor mapping. */
final class ThermalScenarioPolicy {
    static final int ANY = 0;
    static final int ON = 98;
    static final int OFF = 99;

    private static final int ELEMENTS = 13;

    // Each row is scenario id followed by the 13 values from odm/etc/setting.xml.
    private static final int[][] RULES = {
        {0,   0, 0,  0,  0,  0,  0,  0, 0, 0, 0, 0,  0,  0},
        {1,   1, 0, 98, 99,  0,  0, 99, 0, 0, 0, 0,  0, 99},
        {5,   0, 0,  0, 98,  0,  0, 99, 0, 0, 0, 0,  0, 99},
        {6,   4, 0, 98, 99,  0,  0, 99, 0, 0, 0, 0,  0, 99},
        {6,   0, 0,  0, 99,  0,  0, 99, 0, 0, 0, 0,  0, 98},
        {7,   5, 0, 98, 99,  0, 99, 99, 0, 0, 0, 0,  0, 99},
        {7,  15, 0, 98, 99,  0, 99, 99, 0, 0, 0, 0,  0, 99},
        {9,   9, 0, 98, 99,  0,  0, 99, 0, 0, 0, 0,  0, 99},
        {10, 11, 0, 98, 99,  0,  0, 99, 0, 0, 0, 0,  0, 99},
        {11, 13, 0, 98, 99,  0, 99, 99, 0, 0, 0, 0,  0, 99},
        {12, 14, 0, 98, 99,  0,  0, 99, 0, 0, 0, 0,  0, 99},
        {14, 15, 0,  0, 99, 98,  0, 99, 0, 0, 0, 0,  0, 99},
        {15,  7, 0, 98, 99,  0,  0, 99, 0, 0, 0, 0,  0, 99},
        {15,  0, 1, 98, 99, 98,  0, 99, 0, 0, 0, 0,  0, 99},
        {16,  0, 0, 98, 99,  1,  0, 99, 0, 0, 0, 0,  0, 99},
        {17,  0, 0, 98, 99,  2,  0, 99, 0, 0, 0, 0,  0, 99},
        {19,  3, 0, 98, 99,  0, 99, 99, 0, 0, 0, 0,  0, 99},
        {19,  6, 0, 98, 99,  0, 99, 99, 0, 0, 0, 0,  0, 99},
        {19, 10, 0, 98, 99,  0, 99, 99, 0, 0, 0, 0,  0, 99},
        {19, 16, 0, 98, 99,  0, 99, 99, 0, 0, 0, 0,  0, 99},
        {18, 12, 0, 98, 99,  0, 99, 99, 0, 0, 0, 0,  0, 99},
        {25, 18, 0, 98, 99,  0, 99, 99, 0, 0, 0, 0,  0, 99},
        {26, 13, 2, 98, 99,  0, 99, 99, 0, 1, 0, 0,  0, 99},
        {27,  0, 0, 99, 99,  0, 99, 99, 0, 0, 1, 0,  0, 99},
        {28,  0, 0, 98, 99,  0, 99, 99, 0, 0, 0, 6,  0, 99},
        {29,  0, 0,  0,  0,  0,  0,  0, 0, 0, 0, 0, 98, 99},

        {50,  0, 0,  0,  0,  0, 98,  0, 0, 0, 0, 0,  0, 99},
        {51,  1, 0, 98, 99,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {55,  0, 0,  0, 98,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {56,  4, 0, 98, 99,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {57,  5, 0, 98, 99,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {57, 15, 0, 98, 99,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {59,  9, 0, 98, 99,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {60, 11, 0, 98, 99,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {61, 13, 0, 98, 99,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {62, 14, 0, 98, 99,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {64, 15, 0,  0, 99, 98, 98, 99, 0, 0, 0, 0,  0, 99},
        {65,  7, 0, 98, 99,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {65,  0, 1, 98, 99, 98, 98, 99, 0, 0, 0, 0,  0, 99},
        {66,  0, 0, 98, 99,  1, 98, 99, 0, 0, 0, 0,  0, 99},
        {67,  0, 0, 98, 99,  2, 98, 99, 0, 0, 0, 0,  0, 99},
        {69,  3, 0, 98, 99,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {69,  6, 0, 98, 99,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {69, 10, 0, 98, 99,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {69, 16, 0, 98, 99,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {68, 12, 0, 98, 99,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {75, 18, 0, 98, 99,  0, 98, 99, 0, 0, 0, 0,  0, 99},
        {76, 13, 2, 98, 99,  0, 98, 99, 0, 1, 0, 0,  0, 99},
        {77,  0, 0, 99, 99,  0, 98, 99, 0, 0, 1, 0,  0, 99},

        {500, 0, 0,  0,  0,  0,  0, 98, 0, 0, 0, 0,  0, 99},
        {501, 3, 0, 98, 99,  0,  0, 98, 0, 0, 0, 0,  0, 99},
        {501, 6, 0, 98, 99,  0,  0, 98, 0, 0, 0, 0,  0, 99},
        {501,10, 0, 98, 99,  0,  0, 98, 0, 0, 0, 0,  0, 99},
        {501,16, 0, 98, 99,  0,  0, 98, 0, 0, 0, 0,  0, 99},
        {501,12, 0, 98, 99,  0,  0, 98, 0, 0, 0, 0,  0, 99},

        {700, 0, 0, 98, 99,  0,  0, 99, 2, 0, 0, 0,  0, 99},
        {701, 5, 0, 98, 99,  0,  0, 99, 1, 0, 0, 0,  0, 99},
        {702, 3, 0, 98, 98,  0,  0, 99, 0, 0, 0, 0,  0, 99},
        {702,10, 0, 98, 98,  0,  0, 99, 0, 0, 0, 0,  0, 99},
        {702,16, 0, 98, 98,  0,  0, 99, 0, 0, 0, 0,  0, 99},
        {702,12, 0, 98, 98,  0,  0, 99, 0, 0, 0, 0,  0, 99},
        {702, 6, 0, 98, 98,  0,  0, 99, 0, 0, 0, 0,  0, 99},
        {702,18, 0, 98, 98,  0,  0, 99, 0, 0, 0, 0,  0, 99},
    };

    static final class Result {
        final int scenario;
        final int profile;

        Result(int scenario, int profile) {
            this.scenario = scenario;
            this.profile = profile;
        }
    }

    static Result select(String group, String pkg, boolean interactive, boolean offHook,
            int camera, boolean performance, boolean lowTempCharge, boolean reverseCharge) {
        int[] state = {
            foregroundElement(group),
            0, // HighFpsTopActivity: no Lineage source yet.
            interactive ? ON : OFF,
            offHook ? ON : OFF,
            camera,
            performance ? ON : OFF,
            OFF, // IEC: no Lineage source yet.
            0, // SpecialCScenario: no Lineage source yet.
            0, // Playback FPS: no Lineage source yet.
            lowTempCharge ? 1 : 0,
            "com.ss.android.ugc.aweme".equals(pkg) ? 6 : 97,
            reverseCharge ? ON : OFF,
            OFF, // SPTM_2: no Lineage source yet.
        };
        int scenario = match(state);
        return new Result(scenario, mapToProfile(scenario, "videochat".equals(group)));
    }

    static int cameraRecordElement(boolean recording, int quality, int fps) {
        if (!recording) return OFF;
        // FKVideoReceiver: 4K60 -> 1; 4K30 and 8K -> 2; other modes -> off.
        if (quality == 8 && fps == 60) return 1;
        if (quality == 8 || quality == 3001) return 2;
        return OFF;
    }

    private static int match(int[] state) {
        int result = 0;
        for (int[] rule : RULES) {
            boolean matches = true;
            for (int i = 0; i < ELEMENTS; i++) {
                int expected = rule[i + 1];
                if (expected != ANY && expected != state[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches) result = Math.max(result, rule[0]);
        }
        return result;
    }

    private static int foregroundElement(String group) {
        if (group == null) return 97;
        switch (group) {
            case "huanji": return 1;
            case "abnormal": return 2;
            case "game": return 3;
            case "evaluation": return 4;
            case "class0": return 5;
            case "pubg": return 6;
            case "camera": return 7;
            case "youtube": return 8;
            case "arvr": return 9;
            case "game2": return 10;
            case "navigation": return 11;
            case "yuanshen": return 12;
            case "video": return 13;
            case "demo": return 14;
            case "videochat": return 15;
            case "optigame": return 16;
            case "xingtie": return 18;
            case "jkchess": return 21;
            default: return 97;
        }
    }

    private static int mapToProfile(int scenario, boolean videoChatEnabled) {
        if (scenario == 0) return 0;
        int high = scenario / 100;
        int low = scenario % 100;
        switch (low) {
            case 19: case 69:
                low = low >= 50 ? 18 : 19;
                break;
            case 18: case 68:
                low = low >= 50 ? 20 : 19;
                break;
            case 25: case 75:
                low = low >= 50 ? 25 : 19;
                break;
            case 14: case 64:
                low = videoChatEnabled ? 14 : 15;
                break;
            case 51: case 55: case 56: case 59: case 60:
            case 62: case 65: case 66: case 67: case 77:
                low -= 50;
                break;
            default:
                break;
        }
        return high * 100 + low;
    }

    private ThermalScenarioPolicy() {}
}
