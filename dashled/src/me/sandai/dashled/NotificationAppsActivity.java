/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.List;

/** Per-app switches for the notification light. */
public class NotificationAppsActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new NotificationAppsFragment())
                    .commit();
        }
    }

    public static class NotificationAppsFragment extends SettingsBasePreferenceFragment {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            PackageManager pm = requireContext().getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = pm.queryIntentActivities(launcher,
                    PackageManager.ResolveInfoFlags.of(0));
            apps.sort((a, b) -> a.loadLabel(pm).toString()
                    .compareToIgnoreCase(b.loadLabel(pm).toString()));

            PreferenceScreen screen =
                    getPreferenceManager().createPreferenceScreen(requireContext());
            setPreferenceScreen(screen);
            for (ResolveInfo app : apps) {
                String pkg = app.activityInfo.packageName;
                SwitchPreferenceCompat pref = new SwitchPreferenceCompat(requireContext());
                pref.setKey("notif_app_" + pkg);
                pref.setTitle(app.loadLabel(pm));
                pref.setSummary(pkg);
                pref.setIcon(app.loadIcon(pm));
                pref.setChecked(DashLedPrefs.isNotifAppEnabled(requireContext(), pkg));
                pref.setOnPreferenceChangeListener((preference, newValue) -> {
                    DashLedPrefs.setNotifAppEnabled(requireContext(), pkg, (Boolean) newValue);
                    return true;
                });
                screen.addPreference(pref);
            }
        }
    }
}
