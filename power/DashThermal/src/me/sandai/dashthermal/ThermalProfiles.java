// Copyright (C) 2026 GitHub @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

/** Static table of the manually selectable thermal profiles. */
final class ThermalProfiles {

    /** Profile ids in display order; -1 is "automatic" and clears the override. */
    static final int[] IDS = {-1, 0, 7, 11, 19, 20, 25};

    private ThermalProfiles() {
    }

    static int nameRes(int profileId) {
        switch (profileId) {
            case -1:
                return R.string.profile_auto;
            case 0:
                return R.string.profile_normal;
            case 7:
                return R.string.profile_class0;
            case 19:
                return R.string.profile_gaming;
            case 11:
                return R.string.profile_video;
            case 20:
                return R.string.profile_performance;
            case 25:
                return R.string.profile_extreme;
            default:
                return 0;
        }
    }

    static int summaryRes(int profileId) {
        switch (profileId) {
            case -1:
                return R.string.profile_auto_summary;
            case 0:
                return R.string.profile_normal_summary;
            case 7:
                return R.string.profile_class0_summary;
            case 19:
                return R.string.profile_gaming_summary;
            case 11:
                return R.string.profile_video_summary;
            case 20:
                return R.string.profile_performance_summary;
            case 25:
                return R.string.profile_extreme_summary;
            default:
                return 0;
        }
    }
}
