/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

/** dash 305 offline defaults: mode-dependent stock classification; unknown always uses normal. */
final class ThermalProfiles {
    static final int AUTO = -1;

    static boolean selectable(int id) {
        return id == AUTO || id == 0 || id == 7 || id == 11 || id == 19
                || id == 20 || id == 25;
    }

    static int forGroup(String group, boolean performance) {
        switch (group) {
            case "game": case "pubg": case "game2": return performance ? 18 : 19;
            case "yuanshen": return performance ? 20 : 19;
            case "xingtie": return performance ? 25 : 19;
            case "class0": return performance ? 57 : 7;
            case "video": return performance ? 61 : 11;
            case "camera": return 15;
            case "navigation": return 10;
            case "evaluation": return 6;
            case "huanji": return 1;
            case "demo": return 12;
            case "arvr": return 9;
            default: return 0;
        }
    }

    private ThermalProfiles() {}
}
