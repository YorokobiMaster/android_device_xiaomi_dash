// Copyright (C) 2026 GitHub @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.android.settingslib.drawer.EntriesProvider;
import com.android.settingslib.drawer.EntryController;
import com.android.settingslib.drawer.ProviderSwitch;
import com.android.settingslib.drawer.TileUtils;

import java.util.List;

/** Settings' existing provider protocol renders a native switch directly on the Battery page. */
public final class PerformanceModeProvider extends EntriesProvider {
    @Override
    protected List<? extends EntryController> createEntryControllers() {
        return List.of(new Controller());
    }

    private final class Controller extends EntryController implements ProviderSwitch {
        @Override public String getKey() { return "performance_mode"; }

        @Override protected MetaData getMetaData() {
            return new MetaData("com.android.settings.category.ia.battery") {
                @Override protected Bundle build() {
                    Bundle data = super.build();
                    data.putString(TileUtils.META_DATA_PREFERENCE_GROUP_KEY,
                            "power_usage_summary_category");
                    return data;
                }
            }.setTitle(R.string.performance_mode_title)
                    .setSummary(R.string.performance_mode_summary)
                    .setIcon(R.drawable.ic_performance_mode).setIconTintable(true);
        }

        @Override public boolean isSwitchChecked() {
            try {
                return PerformanceModeClient.read().getBoolean("performanceMode");
            } catch (RemoteException | RuntimeException e) {
                Log.w("DashPerformance", "Cannot read mode", e);
                return false;
            }
        }

        @Override public boolean onSwitchCheckedChanged(boolean checked) {
            try {
                PerformanceModeClient.set(checked);
                return true;
            } catch (RemoteException | RuntimeException e) {
                Log.w("DashPerformance", "Cannot save mode", e);
                return false;
            }
        }

        @Override public String getSwitchErrorMessage(boolean checked) {
            return getContext().getString(R.string.save_failed);
        }
    }
}
