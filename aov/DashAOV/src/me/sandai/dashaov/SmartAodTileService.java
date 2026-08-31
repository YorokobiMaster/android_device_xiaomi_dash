/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashaov;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class SmartAodTileService extends TileService {
    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        if (isLocked()) {
            unlockAndRun(this::toggle);
        } else {
            toggle();
        }
    }

    private void toggle() {
        boolean enabled = SmartAodSettings.isActive(getContentResolver());
        SmartAodSettings.setEnabled(getContentResolver(), !enabled);
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }

        boolean enabled = SmartAodSettings.isActive(getContentResolver());
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (enabled) {
            tile.setSubtitle(getString(R.string.tile_enabled));
        } else {
            tile.setSubtitle(getString(R.string.tile_disabled));
        }
        tile.updateTile();
    }
}
