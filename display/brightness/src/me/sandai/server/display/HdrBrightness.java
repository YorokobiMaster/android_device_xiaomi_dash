/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

/** Stock dash HDR peak gate. Values are framework brightness, not nits. */
final class HdrBrightness {
    static final float NORMAL_MAX = 0.805701f;
    private static final float PEAK_LUX = 100000;
    private static final int PEAK_GRAY = 219;

    static float cap(boolean automatic, float lux, int gray) {
        return automatic && Float.isFinite(lux) && lux > PEAK_LUX
                && gray >= 0 && gray <= PEAK_GRAY ? 1.0f : NORMAL_MAX;
    }

    static Integer thermalCondition(boolean hdrLayerPresent, Integer actualCondition) {
        return hdrLayerPresent ? Integer.valueOf(-2) : actualCondition;
    }
}
