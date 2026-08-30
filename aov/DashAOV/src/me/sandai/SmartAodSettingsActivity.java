/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai;

import android.app.Activity;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.widget.Toast;

/** Settings entry for gaze-triggered AOD on dash. */
public final class SmartAodSettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.settings_title);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new SettingsFragment())
                    .commit();
        }
    }

    public static final class SettingsFragment extends PreferenceFragment {
        private SwitchPreference mSwitch;
        private ContentObserver mObserver;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            mSwitch = new SwitchPreference(getActivity());
            mSwitch.setPersistent(false);
            mSwitch.setTitle(R.string.settings_switch_title);
            mSwitch.setSummary(R.string.settings_summary);
            mSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = (Boolean) newValue;
                ContentResolver resolver = getActivity().getContentResolver();
                if (enabled && SmartAodSettings.isAlwaysOnEnabled(resolver)) {
                    Toast.makeText(getActivity(), R.string.tile_always_on_conflict,
                            Toast.LENGTH_LONG).show();
                    return false;
                }
                SmartAodSettings.setEnabled(resolver, enabled);
                return true;
            });

            PreferenceScreen screen = getPreferenceManager()
                    .createPreferenceScreen(getActivity());
            screen.addPreference(mSwitch);
            setPreferenceScreen(screen);

            mObserver = new ContentObserver(new Handler(getActivity().getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    updateState();
                }
            };
        }

        @Override
        public void onResume() {
            super.onResume();
            ContentResolver resolver = getActivity().getContentResolver();
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(SmartAodSettings.ENABLED), false, mObserver);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DOZE_ALWAYS_ON), false, mObserver);
            updateState();
        }

        @Override
        public void onPause() {
            getActivity().getContentResolver().unregisterContentObserver(mObserver);
            super.onPause();
        }

        private void updateState() {
            ContentResolver resolver = getActivity().getContentResolver();
            boolean enabled = SmartAodSettings.isEnabled(resolver);
            boolean alwaysOnEnabled = SmartAodSettings.isAlwaysOnEnabled(resolver);
            mSwitch.setChecked(enabled);
            mSwitch.setEnabled(enabled || !alwaysOnEnabled);
            mSwitch.setSummary(alwaysOnEnabled
                    ? R.string.tile_always_on_conflict : R.string.settings_summary);
        }
    }
}
