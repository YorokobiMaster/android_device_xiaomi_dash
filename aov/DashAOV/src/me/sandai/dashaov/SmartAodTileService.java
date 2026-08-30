/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashaov;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

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
        boolean enabled = SmartAodSettings.isEnabled(getContentResolver());
        if (!enabled && SmartAodSettings.isAlwaysOnEnabled(getContentResolver())) {
            Toast.makeText(this, R.string.tile_always_on_conflict, Toast.LENGTH_LONG).show();
            updateTile();
            return;
        }
        SmartAodSettings.setEnabled(getContentResolver(), !enabled);
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }

        boolean enabled = SmartAodSettings.isEnabled(getContentResolver());
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (enabled) {
            tile.setSubtitle(getString(R.string.tile_enabled));
        } else {
            tile.setSubtitle(getString(R.string.tile_disabled));
        }
        tile.updateTile();
    }
}
