/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.List;
import java.util.Locale;

public class LedSettingsActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new LedSettingsFragment())
                    .commit();
        }
    }

    public static class LedSettingsFragment extends SettingsBasePreferenceFragment
            implements Preference.OnPreferenceChangeListener {

        private MainSwitchPreference mMaster;
        private SwitchPreferenceCompat mNotifEnabled;
        private ListPreference mNotifColor;
        private PreferenceCategory mAppAccess;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.led_prefs, rootKey);

            mMaster = findPreference("master");
            mNotifEnabled = findPreference("notif_enabled");
            mNotifColor = findPreference("notif_color");
            mAppAccess = findPreference("app_access_category");

            mMaster.setOnPreferenceChangeListener(this);
            mNotifEnabled.setOnPreferenceChangeListener(this);
            mNotifColor.setOnPreferenceChangeListener(this);

            findPreference("notification_access").setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                new ComponentName(requireContext(), LedNotificationListener.class)
                                        .flattenToString()));
                return true;
            });

            Preference notifApps = findPreference("notif_apps");
            notifApps.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), NotificationAppsActivity.class));
                return true;
            });
        }

        @Override
        public void onResume() {
            super.onResume();
            LedCore.get(requireContext()).arbiter().onWake();
            refresh();
            refreshAppAccess();
        }

        private void refresh() {
            boolean hasAccess = requireContext().getSystemService(NotificationManager.class)
                    .isNotificationListenerAccessGranted(
                            new ComponentName(requireContext(), LedNotificationListener.class));
            findPreference("notification_access").setVisible(!hasAccess);
            mMaster.setChecked(DashLedPrefs.isMasterEnabled(requireContext()));
            mNotifEnabled.setChecked(DashLedPrefs.isNotifEnabled(requireContext()));
            mNotifColor.setValue(String.format(Locale.US, "%06X",
                    DashLedPrefs.getNotifColor(requireContext())));
            mNotifColor.setSummary(mNotifColor.getEntry());
        }

        private void refreshAppAccess() {
            mAppAccess.removeAll();
            PackageManager pm = requireContext().getPackageManager();
            List<PackageInfo> clients = DashLedPrefs.findClientApps(requireContext());
            if (clients.isEmpty()) {
                Preference empty = new Preference(requireContext());
                empty.setTitle(R.string.app_access_empty);
                empty.setEnabled(false);
                empty.setPersistent(false);
                mAppAccess.addPreference(empty);
                return;
            }
            for (PackageInfo info : clients) {
                ApplicationInfo app = info.applicationInfo;
                SwitchPreferenceCompat pref = new SwitchPreferenceCompat(requireContext());
                pref.setKey("allow_" + info.packageName);
                pref.setPersistent(false);
                pref.setTitle(app.loadLabel(pm));
                pref.setSummary(info.packageName);
                pref.setIcon(app.loadIcon(pm));
                pref.setChecked(
                        DashLedPrefs.isClientPackageAllowed(requireContext(), info.packageName));
                pref.setOnPreferenceChangeListener((preference, newValue) -> {
                    DashLedPrefs.setClientPackageAllowed(requireContext(), info.packageName,
                            (Boolean) newValue);
                    return true;
                });
                mAppAccess.addPreference(pref);
            }
        }

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            switch (preference.getKey()) {
                case "master":
                    boolean enabled = (Boolean) newValue;
                    DashLedPrefs.setMasterEnabled(requireContext(), enabled);
                    LedCore.get(requireContext()).arbiter().setOutputEnabled(enabled);
                    return true;
                case "notif_enabled":
                    DashLedPrefs.setNotifEnabled(requireContext(), (Boolean) newValue);
                    return true;
                case "notif_color":
                    DashLedPrefs.setNotifColor(requireContext(),
                            Integer.parseInt((String) newValue, 16));
                    mNotifColor.setSummary(mNotifColor.getEntries()[
                            mNotifColor.findIndexOfValue((String) newValue)]);
                    return true;
                default:
                    return false;
            }
        }
    }
}
