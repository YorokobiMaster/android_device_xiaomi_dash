/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dasheyecare;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemProperties;
import android.widget.CompoundButton;
import android.widget.Switch;

// Standalone warm eye-care toggle. Writes the persist property that
// dash-livedisplay watches and applies as displayfeature SCREEN_EYECARE.
// Deliberately separate from LineageOS' own reading enhancement.
public class EyeCareActivity extends Activity
        implements CompoundButton.OnCheckedChangeListener {

    private static final String PROPERTY = "persist.dash.livedisplay.eyecare";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.eyecare_activity);

        Switch toggle = findViewById(R.id.eyecare_switch);
        toggle.setChecked(SystemProperties.getBoolean(PROPERTY, false));
        toggle.setOnCheckedChangeListener(this);
    }

    @Override
    public void onCheckedChanged(CompoundButton button, boolean enabled) {
        SystemProperties.set(PROPERTY, enabled ? "1" : "0");
    }
}
