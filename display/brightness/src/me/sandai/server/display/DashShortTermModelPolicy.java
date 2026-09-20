/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import com.android.server.display.DeviceShortTermModelPolicy;

/** Stock dash screen-off reset policy for the automatic-brightness user point. */
public final class DashShortTermModelPolicy implements DeviceShortTermModelPolicy {
    private static final long LONG_SCREEN_OFF_MILLIS = 30 * 60 * 1000L;
    private static final long CHANGED_LUX_SCREEN_OFF_MILLIS = 6 * 60 * 1000L;
    private static final float LUX_CHANGE_THRESHOLD = 20.0f;
    private static final float INVALID_LUX = -1.0f;

    private static final float[] ENVELOPE_LUX =
            {0.0f, 2.0f, 30.0f, 100.0f, 1100.0f, 3000.0f, 20000.0f};
    private static final float[] MIN_NITS =
            {0.0f, 0.0f, 23.0f, 26.0f, 52.8f, 200.0f, 500.1f};
    private static final float[] MAX_NITS =
            {84.0f, 84.0f, 150.0f, 185.0f, 307.2f, 691.2f, 1400.0f};

    private long mScreenOffElapsedRealtime = -1L;
    private float mScreenOffLux = INVALID_LUX;
    private boolean mPendingScreenOff;

    public DashShortTermModelPolicy() {}

    @Override
    public void onDisplayPolicyChanged(boolean interactive, float ambientLux,
            long elapsedRealtimeMillis) {
        if (!interactive) {
            mScreenOffElapsedRealtime = elapsedRealtimeMillis;
            mScreenOffLux = ambientLux;
            mPendingScreenOff = true;
        }
    }

    @Override
    public Evaluation evaluate(float ambientLux, float userLux, float userNits,
            long elapsedRealtimeMillis) {
        if (!mPendingScreenOff) return Evaluation.HANDLED;
        mPendingScreenOff = false;

        long duration = elapsedRealtimeMillis - mScreenOffElapsedRealtime;
        boolean longScreenOff = duration >= LONG_SCREEN_OFF_MILLIS;
        boolean environmentChanged = duration >= CHANGED_LUX_SCREEN_OFF_MILLIS
                && validLux(ambientLux) && validLux(mScreenOffLux)
                && Math.abs(ambientLux - mScreenOffLux) > LUX_CHANGE_THRESHOLD;
        if (!longScreenOff && !environmentChanged) return Evaluation.HANDLED;
        if (!validLux(userLux) || !Float.isFinite(userNits)) return Evaluation.HANDLED;

        float minimum = interpolate(ENVELOPE_LUX, MIN_NITS, userLux);
        float maximum = interpolate(ENVELOPE_LUX, MAX_NITS, userLux);
        float replacement = Math.max(minimum, Math.min(maximum, userNits));
        return replacement == userNits ? Evaluation.HANDLED
                : new Evaluation(true, replacement);
    }

    private static boolean validLux(float lux) {
        return Float.isFinite(lux) && lux >= 0.0f;
    }

    private static float interpolate(float[] x, float[] y, float value) {
        if (value <= x[0]) return y[0];
        int last = x.length - 1;
        if (value >= x[last]) return y[last];
        for (int i = 1; i < x.length; i++) {
            if (value <= x[i]) {
                float ratio = (value - x[i - 1]) / (x[i] - x[i - 1]);
                return y[i - 1] + ratio * (y[i] - y[i - 1]);
            }
        }
        return y[last];
    }
}
