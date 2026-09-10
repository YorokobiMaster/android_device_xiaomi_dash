/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashwake;

import android.os.Bundle;

import com.android.settingslib.drawer.EntriesProvider;
import com.android.settingslib.drawer.EntryController;
import com.android.settingslib.drawer.ProviderSwitch;

import java.util.List;

/** Supplies the pickup and gaze wake switches on Settings > Display. */
public final class WakeSettingsProvider extends EntriesProvider {
    @Override
    protected List<? extends EntryController> createEntryControllers() {
        WakeSettings.migrate(getContext().getContentResolver(), getContext().getUserId());
        return List.of(
                new WakeSwitchController(WakeSettings.PICKUP_ENABLED,
                        R.string.pickup_wake_title, R.string.pickup_wake_summary, -10),
                new WakeSwitchController(WakeSettings.GAZE_ENABLED,
                        R.string.gaze_wake_title, R.string.gaze_wake_summary, -11));
    }

    private final class WakeSwitchController extends EntryController implements ProviderSwitch {
        private final String mKey;
        private final int mTitle;
        private final int mSummary;
        private final int mOrder;

        WakeSwitchController(String key, int title, int summary, int order) {
            mKey = key;
            mTitle = title;
            mSummary = summary;
            mOrder = order;
        }

        @Override
        public String getKey() {
            return mKey;
        }

        @Override
        protected MetaData getMetaData() {
            return new MetaData("com.android.settings.category.ia.display") {
                @Override
                protected Bundle build() {
                    Bundle bundle = super.build();
                    bundle.putString("com.android.settings.group_key", "category_other");
                    return bundle;
                }
            }
                    .setOrder(mOrder)
                    .setTitle(mTitle)
                    .setSummary(mSummary);
        }

        @Override
        public boolean isSwitchChecked() {
            return WakeSettings.isEnabled(getContext().getContentResolver(), mKey,
                    getContext().getUserId());
        }

        @Override
        public boolean onSwitchCheckedChanged(boolean checked) {
            return WakeSettings.setEnabled(getContext().getContentResolver(), mKey,
                    getContext().getUserId(), checked);
        }

        @Override
        public String getSwitchErrorMessage(boolean attemptedChecked) {
            return null;
        }
    }
}
