/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.chargingspeed;

import lineageos.health.HealthInterface;
import me.sandai.chargingspeed.ui.ChargingSpeedSwitch;

public final class ChargingSpeedSettingsProvider extends ChargingSpeedSwitch {
    @Override
    protected boolean isSupported() {
        return HealthInterface.getInstance(getContext()).isFastChargeSupported();
    }

    @Override
    protected int getMode() {
        return HealthInterface.getInstance(getContext()).getFastChargeMode();
    }

    @Override
    protected boolean setMode(int mode) {
        return HealthInterface.getInstance(getContext()).setFastChargeMode(mode);
    }
}
