// Copyright (C) 2026 GitHub @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SystemUI owns the active/inactive tint and tile background; only the glyph is supplied. */
public final class PerformanceModeTileService extends TileService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean listening;
    private boolean destroyed;
    private boolean busy;
    private int generation;
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { refresh(false); }
    };

    @Override public void onStartListening() {
        if (listening) return;
        listening = true;
        getContentResolver().registerContentObserver(PerformanceModeClient.STATE_URI, false, observer);
        refresh(false);
    }

    @Override public void onStopListening() {
        listening = false;
        generation++;
        getContentResolver().unregisterContentObserver(observer);
    }

    @Override public void onClick() {
        if (busy) return;
        if (isLocked()) unlockAndRun(() -> refresh(true));
        else refresh(true);
    }

    private void refresh(boolean toggle) {
        if (destroyed || (!listening && !toggle) || (toggle && busy)) return;
        if (toggle) busy = true;
        final int request = ++generation;
        worker.execute(() -> {
            Bundle state = null;
            boolean failed = false;
            try {
                state = PerformanceModeClient.read();
                if (toggle) {
                    PerformanceModeClient.set(!state.getBoolean("performanceMode"));
                    state = PerformanceModeClient.read();
                }
            } catch (RemoteException | RuntimeException e) {
                failed = true;
            }
            final Bundle result = state;
            final boolean error = failed;
            main.post(() -> {
                if (toggle) busy = false;
                if (destroyed) return;
                if (toggle && error) Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
                if (!listening || request != generation) return;
                Tile tile = getQsTile();
                if (tile == null) return;
                tile.setLabel(getString(R.string.performance_mode_title));
                tile.setContentDescription(getString(R.string.performance_mode_title));
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_performance_mode));
                tile.setState(error || !PerformanceModeClient.available(result) ? Tile.STATE_UNAVAILABLE
                        : result.getBoolean("performanceMode") ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
                tile.updateTile();
            });
        });
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (listening) onStopListening();
        worker.shutdown();
        super.onDestroy();
    }
}
