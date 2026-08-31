/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashaov;

import android.os.Bundle;

import com.android.settingslib.drawer.EntriesProvider;
import com.android.settingslib.drawer.EntryController;
import com.android.settingslib.drawer.ProviderSwitch;

import java.util.Collections;
import java.util.List;

/** Supplies the native inline Smart display switch on Settings > Display. */
public final class SmartDisplaySettingsProvider extends EntriesProvider {
    @Override
    protected List<? extends EntryController> createEntryControllers() {
        return Collections.singletonList(new SmartDisplayController());
    }

    private final class SmartDisplayController extends EntryController implements ProviderSwitch {
        @Override
        public String getKey() {
            return SmartAodSettings.ENABLED;
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
                    .setOrder(-10)
                    .setTitle(R.string.smart_display_title)
                    .setSummary(R.string.smart_display_summary);
        }

        @Override
        public boolean isSwitchChecked() {
            return SmartAodSettings.isActive(getContext().getContentResolver());
        }

        @Override
        public boolean onSwitchCheckedChanged(boolean checked) {
            SmartAodSettings.setEnabled(getContext().getContentResolver(), checked);
            return true;
        }

        @Override
        public String getSwitchErrorMessage(boolean attemptedChecked) {
            return null;
        }
    }
}
