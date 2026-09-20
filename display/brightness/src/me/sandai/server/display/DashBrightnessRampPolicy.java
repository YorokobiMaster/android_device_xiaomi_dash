/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import android.content.Context;

import com.android.server.display.DeviceBrightnessRampPolicy;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.config.HighBrightnessModeData;

/** The dash stock automatic-brightness ramp (stock reason 8). */
public final class DashBrightnessRampPolicy implements DeviceBrightnessRampPolicy {
    private static final String SCREEN_BRIGHTNESS = "screenBrightnessFloat";
    private static final String SDR_BRIGHTNESS = "sdrScreenBrightnessFloat";

    private static final float NIT_LEVEL = 40.0f;
    private static final float NIT_LEVEL1 = 35.0f;
    private static final float NIT_LEVEL2 = 87.450005f;
    private static final float NIT_LEVEL3 = 265.0f;
    private static final float TIME_1 = 0.0f;
    private static final float TIME_2 = 0.8f;
    private static final float TIME_3 = 1.8f;
    private static final float TIME_4 = 4.0f;
    private static final float TIME_5 = 24.0f;
    // AospFrameworkResOverlay config_screenBrightnessSettingMaximum_hyper on dash.
    private static final float MINIMUM_RATE = 1.0f / 16383.0f;

    // These are the exact stock tables used by RampRateController's descending-band search.
    private static final float[] NITS_LEVELS = {
        800.0f, 251.0f, 150.0f, 100.0f, 70.0f, 50.0f, 40.0f, 30.0f, 28.5f
    };
    private static final float[] A_LEVELS = {
        800.0f, 569.47998046875f, 344.8900146484375f, 237.75f,
        179.7100067138672f, 135.19000244140625f, 113.58999633789062f,
        62.84000015258789f, 676.8699951171875f
    };
    private static final float[] B_LEVELS = {
        0.9886999726295471f, 0.9919999837875366f, 0.9950000047683716f,
        0.9965000152587891f, 0.9973000288009644f, 0.9979000091552734f,
        0.998199999332428f, 0.9990000128746033f, 0.9959999918937683f
    };

    private final Mapping mMapping;
    private final float mHbmTransitionPointBrightness;

    /** Public constructor used by DisplayPowerController's resource-selected policy. */
    public DashBrightnessRampPolicy(Context context, DisplayDeviceConfig displayDeviceConfig) {
        this(new DisplayDeviceConfigMapping(displayDeviceConfig),
                getHbmTransitionPoint(displayDeviceConfig));
    }

    /** Narrow conversion seam for focused tests; production construction uses real DDC methods. */
    DashBrightnessRampPolicy(Mapping mapping, float hbmTransitionPointBrightness) {
        mMapping = mapping;
        mHbmTransitionPointBrightness = hbmTransitionPointBrightness;
    }

    @Override
    public float getRate(String propertyName, float transitionStartLinearBrightness,
            float currentLinearBrightness, float targetLinearBrightness) {
        if (!SCREEN_BRIGHTNESS.equals(propertyName) && !SDR_BRIGHTNESS.equals(propertyName)) {
            return Float.NaN;
        }
        if (!isUnitValue(transitionStartLinearBrightness)
                || !isUnitValue(currentLinearBrightness)
                || !isUnitValue(targetLinearBrightness)) {
            return Float.NaN;
        }

        final float startNit = toNits(transitionStartLinearBrightness);
        final float currentNit = toNits(currentLinearBrightness);
        final float targetNit = toNits(targetLinearBrightness);
        if (!isNit(startNit) || !isNit(currentNit) || !isNit(targetNit)) {
            return Float.NaN;
        }

        if (targetLinearBrightness == transitionStartLinearBrightness
                || targetNit == startNit) {
            return Float.NaN;
        }
        if (targetLinearBrightness > transitionStartLinearBrightness) {
            if (targetNit <= startNit || currentNit < startNit || currentNit > targetNit) {
                return Float.NaN;
            }
            return finishRate(getBrighteningRate(transitionStartLinearBrightness,
                    startNit, currentNit, targetNit));
        }
        if (targetNit >= startNit || currentNit > startNit || currentNit < targetNit) {
            return Float.NaN;
        }
        return finishRate(getDarkeningRate(currentNit, targetNit));
    }

    private float getBrighteningRate(float startBrightness, float startNit, float currentNit,
            float targetNit) {
        float rate;
        if (startNit < NIT_LEVEL1) {
            if (targetNit < NIT_LEVEL1) {
                rate = fromNits(targetNit - startNit) / TIME_4;
            } else if (targetNit < NIT_LEVEL3) {
                if (currentNit < NIT_LEVEL1) {
                    rate = (fromNits(NIT_LEVEL1) - startBrightness) / TIME_3;
                } else {
                    rate = getExpRate(NIT_LEVEL1, targetNit, currentNit, TIME_3, TIME_4);
                }
            } else if (currentNit < NIT_LEVEL1) {
                rate = (fromNits(NIT_LEVEL1) - startBrightness) / TIME_2;
            } else if (currentNit < NIT_LEVEL2) {
                rate = getExpRate(NIT_LEVEL1, NIT_LEVEL2, currentNit, TIME_2, TIME_3);
            } else {
                rate = getExpRate(NIT_LEVEL2, targetNit, currentNit, TIME_3, TIME_4);
            }
        } else if (startNit < NIT_LEVEL3) {
            if (targetNit < NIT_LEVEL3) {
                rate = getExpRate(startNit, targetNit, currentNit, TIME_1, TIME_4);
            } else if (currentNit < NIT_LEVEL2) {
                rate = getExpRate(startNit, NIT_LEVEL2, currentNit, TIME_1, TIME_3);
            } else {
                rate = getExpRate(NIT_LEVEL2, targetNit, currentNit, TIME_3, TIME_4);
            }
        } else {
            rate = getExpRate(startNit, targetNit, currentNit, TIME_1, TIME_4);
        }
        return rate;
    }

    private float getDarkeningRate(float currentNit, float targetNit) {
        final boolean hbmConfigured = isUnitValue(mHbmTransitionPointBrightness);
        final float normalMaxBrightness = hbmConfigured ? mHbmTransitionPointBrightness : 1.0f;
        final float normalNit = toNits(normalMaxBrightness);
        if (!isNit(normalNit)) {
            return Float.NaN;
        }

        final int normalMaxBrightnessInt = brightnessFloatToInt(normalMaxBrightness);
        final int maxBrightnessInt = brightnessFloatToInt(1.0f);
        final int index = getIndex(currentNit);
        final float rateAtNormal = getBandRate(currentNit, index);
        if (!Float.isFinite(rateAtNormal)) {
            return Float.NaN;
        }

        final float rate;
        if (hbmConfigured && currentNit > normalNit) {
            final float hbmMaxNit = toNits(1.0f);
            final float hbmBrightnessSpan = brightnessIntToFloat(maxBrightnessInt
                    - normalMaxBrightnessInt);
            if (!isNit(hbmMaxNit) || hbmMaxNit <= normalNit || hbmBrightnessSpan <= 0.0f) {
                return Float.NaN;
            }
            rate = hbmBrightnessSpan * rateAtNormal / (hbmMaxNit - normalNit);
        } else {
            if (normalNit <= 0.0f) {
                return Float.NaN;
            }
            rate = rateAtNormal * normalMaxBrightness / normalNit;
        }

        if (targetNit <= NIT_LEVEL && normalMaxBrightnessInt <= 0xfff) {
            final float fastRate = NIT_LEVEL / 16383.0f;
            if (currentNit <= NIT_LEVEL) {
                return fastRate;
            }
        }
        return rate;
    }

    private float getBandRate(float nit, int index) {
        final float a = A_LEVELS[index];
        final float b = B_LEVELS[index];
        final float time = getTime(nit, index);
        return Math.abs(a * TIME_5 * (float) Math.pow(b, time * TIME_5)
                * (float) Math.log(b));
    }

    private float getExpRate(float startNit, float targetNit, float currentNit,
            float startTime, float targetTime) {
        final float beginBrightness = fromNits(startNit);
        final float endBrightness = fromNits(targetNit);
        final float currentBrightness = fromNits(currentNit);
        if (!isUnitValue(beginBrightness) || !isUnitValue(endBrightness)
                || !isUnitValue(currentBrightness) || beginBrightness <= 0.0f
                || endBrightness <= 0.0f || targetTime <= startTime) {
            return Float.NaN;
        }
        return (float) (Math.log(endBrightness / beginBrightness)
                / (targetTime - startTime)) * currentBrightness;
    }

    private float getTime(float nit, int index) {
        final float a = A_LEVELS[index];
        final float b = B_LEVELS[index];
        return Math.abs((float) (Math.log(nit / a) / Math.log(b) / TIME_5));
    }

    private int getIndex(float nit) {
        int index = 1;
        while (NITS_LEVELS.length > index && nit < NITS_LEVELS[index]) {
            index++;
        }
        return index - 1;
    }

    private float toNits(float brightness) {
        if (!isUnitValue(brightness) || mMapping == null) {
            return Float.NaN;
        }
        try {
            final float backlight = mMapping.backlightFromBrightness(brightness);
            if (!isUnitValue(backlight)) {
                return Float.NaN;
            }
            final float nits = mMapping.nitsFromBacklight(backlight);
            return isNit(nits) ? nits : Float.NaN;
        } catch (RuntimeException e) {
            return Float.NaN;
        }
    }

    private float fromNits(float nits) {
        if (!isNit(nits) || mMapping == null) {
            return Float.NaN;
        }
        try {
            final float backlight = mMapping.backlightFromNits(nits);
            if (!isUnitValue(backlight)) {
                return Float.NaN;
            }
            final float brightness = mMapping.brightnessFromBacklight(backlight);
            return isUnitValue(brightness) ? brightness : Float.NaN;
        } catch (RuntimeException e) {
            return Float.NaN;
        }
    }

    private static float finishRate(float rate) {
        if (!Float.isFinite(rate) || rate <= 0.0f) {
            return Float.NaN;
        }
        return Math.max(rate, MINIMUM_RATE);
    }

    private static boolean isUnitValue(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 1.0f;
    }

    private static boolean isNit(float value) {
        return Float.isFinite(value) && value >= 0.0f;
    }

    private static int brightnessFloatToInt(float brightness) {
        return Math.round(brightness * 255.0f);
    }

    private static float brightnessIntToFloat(int brightness) {
        return brightness / 255.0f;
    }

    private static float getHbmTransitionPoint(DisplayDeviceConfig config) {
        if (config == null) {
            return Float.NaN;
        }
        final HighBrightnessModeData data = config.getHighBrightnessModeData();
        // Current AOSP DisplayDeviceConfig has already converted this from backlight space.
        return data == null ? Float.NaN : data.transitionPoint;
    }

    interface Mapping {
        float backlightFromBrightness(float brightness);
        float nitsFromBacklight(float backlight);
        float backlightFromNits(float nits);
        float brightnessFromBacklight(float backlight);
    }

    private static final class DisplayDeviceConfigMapping implements Mapping {
        private final DisplayDeviceConfig mConfig;

        DisplayDeviceConfigMapping(DisplayDeviceConfig config) {
            mConfig = config;
        }

        @Override
        public float backlightFromBrightness(float brightness) {
            return mConfig == null ? Float.NaN : mConfig.getBacklightFromBrightness(brightness);
        }

        @Override
        public float nitsFromBacklight(float backlight) {
            return mConfig == null ? Float.NaN : mConfig.getNitsFromBacklight(backlight);
        }

        @Override
        public float backlightFromNits(float nits) {
            return mConfig == null ? Float.NaN : mConfig.getBacklightFromNits(nits);
        }

        @Override
        public float brightnessFromBacklight(float backlight) {
            return mConfig == null ? Float.NaN : mConfig.getBrightnessFromBacklight(backlight);
        }
    }
}
