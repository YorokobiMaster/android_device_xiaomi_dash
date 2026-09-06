/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DashLedPrefs {

    public static final String METADATA_CLIENT = "me.sandai.dashled.CLIENT";
    public static final String METADATA_SETTINGS_ACTIVITY =
            "me.sandai.dashled.SETTINGS_ACTIVITY";

    public static final int DEFAULT_NOTIF_COLOR = 0xffffff;

    private static final String PREFS_NAME = "dashled";
    private static final String KEY_MASTER = "master_enabled";
    private static final String KEY_NOTIF_ENABLED = "notif_enabled";
    private static final String KEY_NOTIF_COLOR = "notif_color";
    private static final String KEY_NOTIF_APP_PREFIX = "notif_app_";
    static final String KEY_ALLOWED_CLIENTS = "allowed_clients";

    private DashLedPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The framework holds listeners weakly; callers must keep a strong ref. */
    public static void registerOnChangeListener(Context context,
            SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener);
    }

    public static boolean isMasterEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MASTER, true);
    }

    public static void setMasterEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_MASTER, enabled).apply();
    }

    public static boolean isNotifEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NOTIF_ENABLED, true);
    }

    public static void setNotifEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_NOTIF_ENABLED, enabled).apply();
    }

    public static int getNotifColor(Context context) {
        return prefs(context).getInt(KEY_NOTIF_COLOR, DEFAULT_NOTIF_COLOR);
    }

    public static void setNotifColor(Context context, int color) {
        prefs(context).edit().putInt(KEY_NOTIF_COLOR, color).apply();
    }

    public static boolean isNotifAppEnabled(Context context, String packageName) {
        return prefs(context).getBoolean(KEY_NOTIF_APP_PREFIX + packageName, true);
    }

    public static void setNotifAppEnabled(Context context, String packageName, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_NOTIF_APP_PREFIX + packageName, enabled).apply();
    }

    /** Third-party access gate: same-UID and system calls are always allowed,
     *  anything else needs a user grant from the App access page. */
    public static boolean isClientAllowed(Context context, int uid) {
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID) {
            return true;
        }
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) {
            return false;
        }
        Set<String> allowed =
                prefs(context).getStringSet(KEY_ALLOWED_CLIENTS, Collections.emptySet());
        for (String pkg : packages) {
            if (allowed.contains(pkg)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isClientPackageAllowed(Context context, String packageName) {
        return prefs(context).getStringSet(KEY_ALLOWED_CLIENTS, Collections.emptySet())
                .contains(packageName);
    }

    public static void setClientPackageAllowed(Context context, String packageName,
            boolean allowed) {
        Set<String> set = new HashSet<>(
                prefs(context).getStringSet(KEY_ALLOWED_CLIENTS, Collections.emptySet()));
        if (allowed) {
            set.add(packageName);
        } else {
            set.remove(packageName);
        }
        prefs(context).edit().putStringSet(KEY_ALLOWED_CLIENTS, set).apply();
    }

    /** Installed packages that declare me.sandai.dashled.CLIENT metadata. */
    public static List<PackageInfo> findClientApps(Context context) {
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> clients = new ArrayList<>();
        List<PackageInfo> installed = pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA));
        for (PackageInfo info : installed) {
            if (info.applicationInfo != null && info.applicationInfo.metaData != null
                    && info.applicationInfo.metaData.getBoolean(METADATA_CLIENT, false)) {
                clients.add(info);
            }
        }
        return clients;
    }
}
