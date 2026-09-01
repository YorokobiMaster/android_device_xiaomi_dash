/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashcharging;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class BypassChargingTileService extends TileService {
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
        boolean enabled = BypassChargingSettings.isEnabled(this);
        if (!BypassChargingSettings.setEnabled(this, !enabled)) {
            showUnavailable();
            return;
        }
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }

        Boolean requested = BypassChargingSettings.getRequestedState();
        if (requested == null) {
            showUnavailable();
            return;
        }

        tile.setState(requested ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(getString(requested ? R.string.tile_enabled : R.string.tile_disabled));
        tile.updateTile();
    }

    private void showUnavailable() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(Tile.STATE_UNAVAILABLE);
        tile.setSubtitle(getString(R.string.tile_unavailable));
        tile.updateTile();
    }
}
