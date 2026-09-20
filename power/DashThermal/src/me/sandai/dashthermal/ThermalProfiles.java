// Copyright (C) 2026 GitHub @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

/** Fixed thermald table ids shown on the detail page, grouped by section. */
final class ThermalProfiles {

    static final int[] GENERAL = {0, 7, 11};
    static final int[] GAMING = {19, 20, 25};
    static final int[] OTHERS = {6, 10, 15, 1, 9, 12};

    private ThermalProfiles() {
    }

    static int nameRes(int profileId) {
        switch (profileId) {
            case 0:
                return R.string.profile_default;
            case 7:
                return R.string.profile_everyday;
            case 11:
                return R.string.profile_video;
            case 19:
                return R.string.profile_standard;
            case 20:
                return R.string.profile_enhanced;
            case 25:
                return R.string.profile_extreme;
            case 6:
                return R.string.profile_evaluation;
            case 10:
                return R.string.profile_navigation;
            case 15:
                return R.string.profile_camera;
            case 1:
                return R.string.profile_transfer;
            case 9:
                return R.string.profile_arvr;
            case 12:
                return R.string.profile_demo;
            default:
                return 0;
        }
    }

    static int summaryRes(int profileId) {
        switch (profileId) {
            case 0:
                return R.string.profile_default_summary;
            case 7:
                return R.string.profile_everyday_summary;
            case 11:
                return R.string.profile_video_summary;
            case 19:
                return R.string.profile_standard_summary;
            case 20:
                return R.string.profile_enhanced_summary;
            case 25:
                return R.string.profile_extreme_summary;
            default:
                return 0;
        }
    }

    static boolean known(int profileId) {
        return nameRes(profileId) != 0;
    }

    /** Performance-mode counterparts resolve onto the same option as their base table. */
    static int canon(int profileId) {
        switch (profileId) {
            case 50:
                return 0;
            case 57:
                return 7;
            case 61:
                return 11;
            case 18:
                return 19;
            default:
                return profileId;
        }
    }
}
