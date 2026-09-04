/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashdolby;

import android.os.Bundle;

import androidx.preference.ListPreference;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

public class EqualizerActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new EqualizerFragment())
                    .commit();
        }
    }

    public static class EqualizerFragment extends SettingsBasePreferenceFragment {

        private DolbyController mController;
        private int mProfile;
        private int[] mGains;
        private EqualizerPreference mEqualizer;
        private ListPreference mPreset;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.equalizer_prefs, rootKey);
            mController = DolbyController.getInstance(requireContext());
            mProfile = mController.getActiveProfile();
            mGains = mController.getActiveGraphicEqualizerGains(mProfile);

            mPreset = findPreference("equalizer_preset");
            int preset = mController.getGraphicEqualizerPreset(mProfile);
            mPreset.setValue(String.valueOf(preset));
            mPreset.setSummary(mPreset.getEntry());
            mPreset.setOnPreferenceChangeListener((preference, newValue) -> {
                int selected = Integer.parseInt((String) newValue);
                mController.setGraphicEqualizerPreset(mProfile, selected);
                mGains = mController.getActiveGraphicEqualizerGains(mProfile);
                mEqualizer.setGains(mGains);
                mPreset.setValue((String) newValue);
                mPreset.setSummary(mPreset.getEntry());
                return true;
            });

            mEqualizer = findPreference("equalizer_view");
            mEqualizer.setGains(mGains);
            mEqualizer.setOnGainsChangedListener((gains, finished) -> {
                mGains = gains;
                if (!"0".equals(mPreset.getValue())) {
                    mPreset.setValue("0");
                    mPreset.setSummary(mPreset.getEntry());
                }
                if (finished) {
                    mController.setGraphicEqualizerGains(mProfile, mGains);
                } else {
                    mController.previewGraphicEqualizerGains(mProfile, mGains);
                }
            });

        }
    }
}
