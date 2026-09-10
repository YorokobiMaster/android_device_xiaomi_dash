/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashdolby;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Owns the single process-wide DolbyAudioEffect instance and persists the
 * user's choices. The effect is created on the global output mix, so this
 * process must stay alive for processing to remain active; the app is marked
 * android:persistent to guarantee that.
 */
public class DolbyController {

    private static final String TAG = "DashDolby";

    private static final String PREFS = "dolby";
    private static final String KEY_DS_ON = "ds_on";
    private static final String KEY_PROFILE = "profile";
    private static final String KEY_GRAPHIC_EQUALIZER_GAINS = "geq_gains";
    private static final String KEY_GRAPHIC_EQUALIZER_PRESET = "geq_preset";

    public static final int GRAPHIC_EQUALIZER_CONTROL_COUNT = 10;
    public static final int GRAPHIC_EQUALIZER_PRESET_COUNT = 9;
    public static final int GRAPHIC_EQUALIZER_MIN_QUARTER_DB = -24;
    public static final int GRAPHIC_EQUALIZER_MAX_QUARTER_DB = 24;

    // Stock MiSound GEQ curves, expressed in quarter-decibel units.
    private static final int[][] GRAPHIC_EQUALIZER_PRESET_GAINS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {16, 4, -8, -1, 0, -8, 0, -8, 2, 16},
            {0, 0, 0, -4, -4, -12, -2, 0, 0, 0},
            {-8, -2, -20, -4, 0, 0, -2, -12, -2, 0},
            {0, 0, 0, 0, 2, 12, 4, 24, 8, 24},
            {12, 0, -12, -2, -2, -12, -2, 0, 0, 8},
            {8, 8, -24, -8, 12, 4, 0, 4, 0, 8},
            {12, 4, -4, 0, -2, -12, -2, 0, 0, 0},
            {8, 0, 0, -5, -4, -16, 0, 0, 0, 0},
    };

    private static final int MAX_CREATE_ATTEMPTS = 30;
    private static final long CREATE_RETRY_DELAY_MS = 1000;

    private static DolbyController sInstance;

    private final SharedPreferences mPrefs;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private DolbyAudioEffect mEffect;
    private int mCreateAttempts;

    public static synchronized DolbyController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new DolbyController(context.getApplicationContext());
        }
        return sInstance;
    }

    private DolbyController(Context context) {
        mPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void init() {
        tryCreateEffect();
    }

    private void tryCreateEffect() {
        releaseEffect();
        try {
            mEffect = new DolbyAudioEffect();
            if (!mEffect.hasControl()) {
                throw new IllegalStateException("created without control");
            }
            mCreateAttempts = 0;
            applyBootState();
            Log.i(TAG, "DAP effect created, profiles=" + mEffect.getProfileCount());
        } catch (RuntimeException e) {
            releaseEffect();
            if (++mCreateAttempts <= MAX_CREATE_ATTEMPTS) {
                Log.w(TAG, "effect not ready, retry " + mCreateAttempts, e);
                mHandler.postDelayed(this::tryCreateEffect, CREATE_RETRY_DELAY_MS);
            } else {
                Log.e(TAG, "giving up creating DAP effect", e);
            }
        }
    }

    private void releaseEffect() {
        if (mEffect != null) {
            try {
                mEffect.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "release: " + e);
            }
            mEffect = null;
        }
    }

    private void applyBootState() {
        try {
            mEffect.setDsOn(mPrefs.getBoolean(KEY_DS_ON, true));
            int profile = mPrefs.getInt(KEY_PROFILE, 0);
            if (profile >= 0 && profile < mEffect.getProfileCount()) {
                mEffect.setActiveProfile(profile);
            }
            for (int i = 0; i < mEffect.getProfileCount(); i++) {
                String presetKey = profileKey(i, DolbyAudioEffect.DAP_PARAM_IEQ_PRESET);
                if (mPrefs.contains(presetKey)) {
                    int preset = mPrefs.getInt(presetKey, 2);
                    mEffect.setProfileParameter(i, DolbyAudioEffect.DAP_PARAM_IEQ_PRESET,
                            preset);
                    mEffect.setProfileParameter(i,
                            DolbyAudioEffect.DAP_PARAM_GRAPHIC_EQUALIZER,
                            preset == 0 ? 1 : 0);
                    if (preset == 0) {
                        mEffect.setProfileParameter(i,
                                DolbyAudioEffect.DAP_PARAM_GRAPHIC_EQUALIZER_GAINS,
                                toDapGains(getActiveGraphicEqualizerGains(i)));
                    }
                }
            }
            mEffect.commit();
        } catch (RuntimeException e) {
            Log.e(TAG, "applyBootState failed", e);
        }
    }

    public boolean isAvailable() {
        return mEffect != null && mEffect.hasControl();
    }

    public boolean isDsOn() {
        return mPrefs.getBoolean(KEY_DS_ON, true);
    }

    public void setDsOn(boolean on) {
        mPrefs.edit().putBoolean(KEY_DS_ON, on).apply();
        if (mEffect == null) return;
        try {
            mEffect.setDsOn(on);
            mEffect.commit();
        } catch (RuntimeException e) {
            Log.e(TAG, "setDsOn failed", e);
        }
    }

    public int getActiveProfile() {
        return mPrefs.getInt(KEY_PROFILE, 0);
    }

    public void setActiveProfile(int profile) {
        mPrefs.edit().putInt(KEY_PROFILE, profile).apply();
        if (mEffect == null) return;
        try {
            mEffect.setActiveProfile(profile);
            mEffect.commit();
        } catch (RuntimeException e) {
            Log.e(TAG, "setActiveProfile failed", e);
        }
    }

    public boolean getProfileBool(int profile, int param, boolean def) {
        return mPrefs.getInt(profileKey(profile, param), def ? 1 : 0) != 0;
    }

    public void setProfileBool(int profile, int param, boolean value) {
        setProfileInt(profile, param, value ? 1 : 0);
    }

    public int getProfileInt(int profile, int param, int def) {
        return mPrefs.getInt(profileKey(profile, param), def);
    }

    public void setProfileInt(int profile, int param, int value) {
        mPrefs.edit().putInt(profileKey(profile, param), value).apply();
        if (mEffect == null) return;
        try {
            mEffect.setProfileParameter(profile, param, value);
            mEffect.commit();
        } catch (RuntimeException e) {
            Log.e(TAG, "setProfileParameter failed", e);
        }
    }

    public void setIeqPreset(int profile, int preset) {
        mPrefs.edit().putInt(profileKey(profile, DolbyAudioEffect.DAP_PARAM_IEQ_PRESET),
                preset).apply();
        if (mEffect == null) return;
        try {
            mEffect.setProfileParameter(profile, DolbyAudioEffect.DAP_PARAM_IEQ_PRESET,
                    preset);
            mEffect.setProfileParameter(profile,
                    DolbyAudioEffect.DAP_PARAM_GRAPHIC_EQUALIZER, preset == 0 ? 1 : 0);
            if (preset == 0) {
                mEffect.setProfileParameter(profile,
                        DolbyAudioEffect.DAP_PARAM_GRAPHIC_EQUALIZER_GAINS,
                        toDapGains(getActiveGraphicEqualizerGains(profile)));
            }
            mEffect.commit();
        } catch (RuntimeException e) {
            Log.e(TAG, "setIeqPreset failed", e);
        }
    }

    /** Returns the saved custom curve in quarter-decibel units. */
    public int[] getGraphicEqualizerGains(int profile) {
        int[] gains = new int[GRAPHIC_EQUALIZER_CONTROL_COUNT];
        String encoded = mPrefs.getString(graphicEqualizerKey(profile), "");
        if (encoded.isEmpty()) return gains;

        String[] values = encoded.split(",", -1);
        if (values.length != GRAPHIC_EQUALIZER_CONTROL_COUNT) {
            Log.w(TAG, "Ignoring graphic equalizer curve with " + values.length
                    + " controls");
            return gains;
        }
        try {
            for (int i = 0; i < values.length; i++) {
                gains[i] = clampQuarterDb(Integer.parseInt(values[i]));
            }
        } catch (NumberFormatException e) {
            Log.w(TAG, "Ignoring malformed graphic equalizer curve", e);
            return new int[GRAPHIC_EQUALIZER_CONTROL_COUNT];
        }
        return gains;
    }

    public int getGraphicEqualizerPreset(int profile) {
        return mPrefs.getInt(graphicEqualizerPresetKey(profile), 0);
    }

    public int[] getActiveGraphicEqualizerGains(int profile) {
        int preset = getGraphicEqualizerPreset(profile);
        return preset == 0 ? getGraphicEqualizerGains(profile)
                : getGraphicEqualizerPresetGains(preset);
    }

    public int[] getGraphicEqualizerPresetGains(int preset) {
        if (preset < 0 || preset >= GRAPHIC_EQUALIZER_PRESET_COUNT) {
            throw new IllegalArgumentException("Invalid graphic equalizer preset " + preset);
        }
        return GRAPHIC_EQUALIZER_PRESET_GAINS[preset].clone();
    }

    public void setGraphicEqualizerPreset(int profile, int preset) {
        int[] gains = preset == 0 ? getGraphicEqualizerGains(profile)
                : getGraphicEqualizerPresetGains(preset);
        mPrefs.edit()
                .putInt(graphicEqualizerPresetKey(profile), preset)
                .putInt(profileKey(profile, DolbyAudioEffect.DAP_PARAM_IEQ_PRESET), 0)
                .apply();
        applyGraphicEqualizerGains(profile, gains, true);
    }

    public void setGraphicEqualizerGains(int profile, int[] gains) {
        int[] clamped = clampGains(gains);
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < clamped.length; i++) {
            if (i > 0) encoded.append(',');
            encoded.append(clamped[i]);
        }
        mPrefs.edit()
                .putString(graphicEqualizerKey(profile), encoded.toString())
                .putInt(graphicEqualizerPresetKey(profile), 0)
                .putInt(profileKey(profile, DolbyAudioEffect.DAP_PARAM_IEQ_PRESET), 0)
                .apply();
        applyGraphicEqualizerGains(profile, clamped, true);
    }

    public void previewGraphicEqualizerGains(int profile, int[] gains) {
        applyGraphicEqualizerGains(profile, clampGains(gains), false);
    }

    private void applyGraphicEqualizerGains(int profile, int[] gains, boolean commit) {
        if (mEffect == null) return;
        try {
            mEffect.setProfileParameter(profile, DolbyAudioEffect.DAP_PARAM_IEQ_PRESET, 0);
            mEffect.setProfileParameter(profile,
                    DolbyAudioEffect.DAP_PARAM_GRAPHIC_EQUALIZER, 1);
            mEffect.setProfileParameter(profile,
                    DolbyAudioEffect.DAP_PARAM_GRAPHIC_EQUALIZER_GAINS,
                    toDapGains(gains));
            if (commit) mEffect.commit();
        } catch (RuntimeException e) {
            Log.e(TAG, "applyGraphicEqualizerGains failed", e);
        }
    }

    private static int[] toDapGains(int[] quarterDbGains) {
        int[] dapGains = new int[20];
        for (int i = 0; i < quarterDbGains.length; i++) {
            int gain = clampQuarterDb(quarterDbGains[i]);
            int dapBand = i * 2;
            dapGains[dapBand] = gain * 4;
            if (i > 0) {
                dapGains[dapBand - 1] = (quarterDbGains[i - 1] + gain) * 2;
            }
        }
        dapGains[19] = dapGains[18];

        int sum = 0;
        for (int gain : dapGains) sum += gain;
        int average = sum / dapGains.length;
        for (int i = 0; i < dapGains.length; i++) {
            dapGains[i] -= average;
        }
        return dapGains;
    }

    private static int clampQuarterDb(int value) {
        return Math.max(GRAPHIC_EQUALIZER_MIN_QUARTER_DB,
                Math.min(GRAPHIC_EQUALIZER_MAX_QUARTER_DB, value));
    }

    private static int[] clampGains(int[] gains) {
        if (gains.length != GRAPHIC_EQUALIZER_CONTROL_COUNT) {
            throw new IllegalArgumentException("Expected " + GRAPHIC_EQUALIZER_CONTROL_COUNT
                    + " graphic equalizer controls");
        }
        int[] clamped = new int[GRAPHIC_EQUALIZER_CONTROL_COUNT];
        for (int i = 0; i < gains.length; i++) {
            clamped[i] = clampQuarterDb(gains[i]);
        }
        return clamped;
    }

    private static String graphicEqualizerKey(int profile) {
        return "p" + profile + "_" + KEY_GRAPHIC_EQUALIZER_GAINS;
    }

    private static String graphicEqualizerPresetKey(int profile) {
        return "p" + profile + "_" + KEY_GRAPHIC_EQUALIZER_PRESET;
    }

    private static String profileKey(int profile, int param) {
        return "p" + profile + "_" + param;
    }
}
