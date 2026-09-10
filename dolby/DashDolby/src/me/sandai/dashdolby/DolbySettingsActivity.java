/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashdolby;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

public class DolbySettingsActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new DolbySettingsFragment())
                    .commit();
        }
    }

    public static class DolbySettingsFragment extends SettingsBasePreferenceFragment
            implements Preference.OnPreferenceChangeListener {

        private DolbyController mController;

        private MainSwitchPreference mDsOn;
        private ListPreference mProfile;
        private ListPreference mIeqPreset;
        private Preference mEqualizer;
        private SwitchPreferenceCompat mDialogEnhancer;
        private SwitchPreferenceCompat mVolumeLeveler;
        private SwitchPreferenceCompat mHeadphoneVirtualizer;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.dolby_prefs, rootKey);
            mController = DolbyController.getInstance(requireContext());

            mDsOn = findPreference("ds_on");
            mProfile = findPreference("profile");
            mIeqPreset = findPreference("ieq_preset");
            mEqualizer = findPreference("equalizer");
            mDialogEnhancer = findPreference("dialog_enhancer");
            mVolumeLeveler = findPreference("volume_leveler");
            mHeadphoneVirtualizer = findPreference("headphone_virtualizer");

            if (!mController.isAvailable()) {
                Toast.makeText(requireContext(), R.string.dolby_unavailable, Toast.LENGTH_LONG)
                        .show();
            }

            mDsOn.setOnPreferenceChangeListener(this);
            mProfile.setOnPreferenceChangeListener(this);
            mIeqPreset.setOnPreferenceChangeListener(this);
            mEqualizer.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), EqualizerActivity.class));
                return true;
            });
            mDialogEnhancer.setOnPreferenceChangeListener(this);
            mVolumeLeveler.setOnPreferenceChangeListener(this);
            mHeadphoneVirtualizer.setOnPreferenceChangeListener(this);

            refresh();
        }

        private void refresh() {
            mDsOn.setChecked(mController.isDsOn());

            int profile = mController.getActiveProfile();
            String[] names = getResources().getStringArray(R.array.profile_names);
            if (profile < names.length) {
                mProfile.setSummary(names[profile]);
            }
            mProfile.setValue(String.valueOf(profile));

            refreshProfilePrefs(profile);
        }

        private void refreshProfilePrefs(int profile) {
            String ieq = String.valueOf(mController.getProfileInt(profile,
                    DolbyAudioEffect.DAP_PARAM_IEQ_PRESET, 2));
            mIeqPreset.setValue(ieq);
            mIeqPreset.setSummary(mIeqPreset.getEntry());
            mEqualizer.setVisible("0".equals(ieq));

            mDialogEnhancer.setChecked(mController.getProfileBool(profile,
                    DolbyAudioEffect.DAP_PARAM_DIALOG_ENHANCER, true));
            mVolumeLeveler.setChecked(mController.getProfileBool(profile,
                    DolbyAudioEffect.DAP_PARAM_VOLUME_LEVELER, true));
            mHeadphoneVirtualizer.setChecked(mController.getProfileBool(profile,
                    DolbyAudioEffect.DAP_PARAM_HEADPHONE_VIRTUALIZER, true));

            boolean on = mController.isDsOn();
            mProfile.setEnabled(on);
            mIeqPreset.setEnabled(on);
            mEqualizer.setEnabled(on);
            mDialogEnhancer.setEnabled(on);
            mVolumeLeveler.setEnabled(on);
            mHeadphoneVirtualizer.setEnabled(on);
        }

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            String key = preference.getKey();
            int profile = mController.getActiveProfile();
            switch (key) {
                case "ds_on":
                    mController.setDsOn((Boolean) newValue);
                    break;
                case "profile":
                    mController.setActiveProfile(Integer.parseInt((String) newValue));
                    break;
                case "ieq_preset":
                    int preset = Integer.parseInt((String) newValue);
                    mController.setIeqPreset(profile, preset);
                    if (preset == 0) {
                        requireView().post(() -> startActivity(
                                new Intent(requireContext(), EqualizerActivity.class)));
                    }
                    break;
                case "dialog_enhancer":
                    mController.setProfileBool(profile,
                            DolbyAudioEffect.DAP_PARAM_DIALOG_ENHANCER, (Boolean) newValue);
                    break;
                case "volume_leveler":
                    mController.setProfileBool(profile,
                            DolbyAudioEffect.DAP_PARAM_VOLUME_LEVELER, (Boolean) newValue);
                    break;
                case "headphone_virtualizer":
                    mController.setProfileBool(profile,
                            DolbyAudioEffect.DAP_PARAM_HEADPHONE_VIRTUALIZER,
                            (Boolean) newValue);
                    break;
                default:
                    return false;
            }
            refresh();
            return true;
        }
    }
}
