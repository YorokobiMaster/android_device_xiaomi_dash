/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashcharging;

import android.os.Bundle;

import com.android.settingslib.drawer.EntriesProvider;
import com.android.settingslib.drawer.EntryController;
import com.android.settingslib.drawer.ProviderSwitch;

import java.util.Collections;
import java.util.List;

/** Supplies the inline bypass charging switch on Settings > Battery. */
public final class BypassChargingSettingsProvider extends EntriesProvider {
    @Override
    protected List<? extends EntryController> createEntryControllers() {
        return Collections.singletonList(new BypassChargingController());
    }

    private final class BypassChargingController extends EntryController
            implements ProviderSwitch {
        @Override
        public String getKey() {
            return BypassChargingSettings.ENABLED;
        }

        @Override
        protected MetaData getMetaData() {
            return new MetaData("com.android.settings.category.ia.battery") {
                @Override
                protected Bundle build() {
                    Bundle bundle = super.build();
                    bundle.putString("com.android.settings.group_key", "charging_category");
                    return bundle;
                }
            }
                    .setOrder(-10)
                    .setTitle(R.string.bypass_charging_title)
                    .setSummary(R.string.bypass_charging_summary);
        }

        @Override
        public boolean isSwitchChecked() {
            return BypassChargingSettings.isEnabled(getContext());
        }

        @Override
        public boolean onSwitchCheckedChanged(boolean checked) {
            return BypassChargingSettings.setEnabled(getContext(), checked);
        }

        @Override
        public String getSwitchErrorMessage(boolean attemptedChecked) {
            return getContext().getString(R.string.bypass_charging_error);
        }
    }
}
