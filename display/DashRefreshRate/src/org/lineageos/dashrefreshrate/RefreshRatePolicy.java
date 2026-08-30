/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashrefreshrate;

final class RefreshRatePolicy {
    static final String DEFAULT_AWAKE_MIN_REFRESH_RATE = "60.0";
    static final String DOZE_MIN_REFRESH_RATE = "0.0";
    static final String DOZE_PEAK_REFRESH_RATE = "30.0";
    static final int DOZE_BRIGHTNESS_NORMAL = 0;
    static final int DOZE_BRIGHTNESS_LBM = 2;

    enum DisplayState {
        AWAKE,
        DOZE,
        OTHER,
    }

    interface Store {
        String readMinRefreshRate();
        boolean writeMinRefreshRate(String value);
        String readPeakRefreshRate();
        boolean writePeakRefreshRate(String value);
        boolean isDozeOverrideActive();
        String readAwakeMinRefreshRate();
        String readAwakePeakRefreshRate();
        boolean beginDozeOverride(String awakeMinRefreshRate, String awakePeakRefreshRate);
        void clearDozeOverride();
        boolean setDozeBrightness(int value);
    }

    interface Logger {
        void log(String message);
    }

    private final Store mStore;
    private final Logger mLogger;

    RefreshRatePolicy(Store store, Logger logger) {
        mStore = store;
        mLogger = logger;
    }

    void onDisplayState(DisplayState state) {
        switch (state) {
            case AWAKE:
                leaveDoze();
                break;
            case DOZE:
                enterDoze();
                break;
            case OTHER:
                break;
        }
    }

    private void enterDoze() {
        // The panel's 30 Hz command table defaults to AOD HBM. Select LBM
        // before releasing the refresh-rate floor so the mode switch consumes
        // the rewritten low-brightness table.
        if (!mStore.setDozeBrightness(DOZE_BRIGHTNESS_LBM)) {
            mLogger.log("failed to select AOD low brightness");
            return;
        }

        if (mStore.isDozeOverrideActive()) {
            mLogger.log("doze override already active");
            return;
        }

        String awakeMinRefreshRate = mStore.readMinRefreshRate();
        // A 0.0 floor without an active override is an orphaned doze floor,
        // not an awake setting; never enshrine it into the recovery record.
        boolean orphanedDozeRange = isZero(awakeMinRefreshRate);
        if (awakeMinRefreshRate == null || orphanedDozeRange) {
            awakeMinRefreshRate = DEFAULT_AWAKE_MIN_REFRESH_RATE;
        }
        String awakePeakRefreshRate = mStore.readPeakRefreshRate();
        if (orphanedDozeRange && isThirty(awakePeakRefreshRate)) {
            awakePeakRefreshRate = null;
        }

        if (!mStore.beginDozeOverride(awakeMinRefreshRate, awakePeakRefreshRate)) {
            mLogger.log("failed to save awake refresh-rate settings");
            return;
        }

        // AOSP caps the physical rate at max(min, peak). Cap peak first so an
        // awake 60 Hz floor prevents an intermediate jump to 120 Hz. Releasing
        // the floor afterwards produces a single 60 -> 30 transition.
        if (!mStore.writePeakRefreshRate(DOZE_PEAK_REFRESH_RATE)) {
            mStore.clearDozeOverride();
            mLogger.log("failed to cap peak refresh rate for doze");
            return;
        }

        if (!mStore.writeMinRefreshRate(DOZE_MIN_REFRESH_RATE)) {
            if (mStore.writePeakRefreshRate(awakePeakRefreshRate)) {
                mStore.clearDozeOverride();
            }
            mLogger.log("failed to release minimum refresh rate for doze");
            return;
        }

        mLogger.log("entered doze; saved min=" + awakeMinRefreshRate
                + " peak=" + awakePeakRefreshRate + " active=0.0-30.0");
    }

    private void leaveDoze() {
        String currentMinRefreshRate = mStore.readMinRefreshRate();
        if (!mStore.isDozeOverrideActive()) {
            if (currentMinRefreshRate == null) {
                if (!mStore.writeMinRefreshRate(DEFAULT_AWAKE_MIN_REFRESH_RATE)) {
                    mLogger.log("failed to initialize awake minimum refresh rate");
                    return;
                }
                mLogger.log("initialized awake minimum refresh rate to 60.0");
            } else if (isZero(currentMinRefreshRate) && !repairOrphanedDozeRange()) {
                return;
            }
            leaveDozeBrightness();
            return;
        }

        String currentPeakRefreshRate = mStore.readPeakRefreshRate();
        String awakeMinRefreshRate = mStore.readAwakeMinRefreshRate();
        if (awakeMinRefreshRate == null) {
            awakeMinRefreshRate = DEFAULT_AWAKE_MIN_REFRESH_RATE;
        }
        String awakePeakRefreshRate = mStore.readAwakePeakRefreshRate();

        boolean peakRestored = true;
        if (isThirty(currentPeakRefreshRate)) {
            peakRestored = mStore.writePeakRefreshRate(awakePeakRefreshRate);
        } else {
            mLogger.log("preserved external peak refresh rate=" + currentPeakRefreshRate);
        }

        boolean minimumRestored = true;
        if (isZero(currentMinRefreshRate)) {
            minimumRestored = mStore.writeMinRefreshRate(awakeMinRefreshRate);
        } else {
            mLogger.log("preserved external minimum refresh rate=" + currentMinRefreshRate);
        }

        if (peakRestored && minimumRestored) {
            mStore.clearDozeOverride();
            mLogger.log("left doze; restored min=" + awakeMinRefreshRate
                    + " peak=" + awakePeakRefreshRate);
            leaveDozeBrightness();
        } else {
            mLogger.log("failed to restore awake refresh-rate settings");
        }
    }

    // A 0.0 floor is only ever written by enterDoze(). If the recovery record
    // was lost while the installed doze range survived, the range is orphaned;
    // restore the awake defaults. The peak cap is cleared first so that a
    // failed floor write is still recognized as orphaned on the retry sync.
    private boolean repairOrphanedDozeRange() {
        String currentPeakRefreshRate = mStore.readPeakRefreshRate();
        if (isThirty(currentPeakRefreshRate)
                && !mStore.writePeakRefreshRate(null)) {
            mLogger.log("failed to clear orphaned doze peak refresh rate");
            return false;
        }
        if (!mStore.writeMinRefreshRate(DEFAULT_AWAKE_MIN_REFRESH_RATE)) {
            mLogger.log("failed to restore awake minimum refresh rate");
            return false;
        }
        mLogger.log("repaired orphaned doze range; restored min="
                + DEFAULT_AWAKE_MIN_REFRESH_RATE + " peak="
                + (isThirty(currentPeakRefreshRate) ? "null" : currentPeakRefreshRate));
        return true;
    }

    private void leaveDozeBrightness() {
        // Restore the awake refresh-rate range first. The 60/90/120 Hz tables
        // leave panel idle mode before the vendor doze state is cleared.
        if (!mStore.setDozeBrightness(DOZE_BRIGHTNESS_NORMAL)) {
            mLogger.log("failed to leave AOD brightness mode");
        }
    }

    private static boolean isZero(String value) {
        return isValue(value, 0.0f);
    }

    private static boolean isThirty(String value) {
        return isValue(value, 30.0f);
    }

    private static boolean isValue(String value, float expected) {
        if (value == null) {
            return false;
        }
        try {
            return Float.parseFloat(value) == expected;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
