/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower.compat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import me.sandai.dashpower.IDashThermalService;

/** Receives stock camera broadcasts; the manifest requires its signature permission. */
public final class CameraThermalReceiver extends BroadcastReceiver {
    private static final String TAG = "DashPowerKeeperCompat";
    private static final String SERVICE = "dash_thermal";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean recording;
        if ("record_start".equals(action)) recording = true;
        else if ("record_end".equals(action)) recording = false;
        else return;

        IDashThermalService service = IDashThermalService.Stub.asInterface(
                ServiceManager.checkService(SERVICE));
        if (service == null) {
            Slog.w(TAG, "Thermal backend unavailable");
            return;
        }
        try {
            if (service.getApiVersion() < 3) {
                Slog.w(TAG, "Thermal backend does not accept camera events");
                return;
            }
            service.notifyCameraRecordState(recording,
                    intent.getIntExtra("quality", -1), intent.getIntExtra("fps", -1));
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Cannot deliver camera thermal event", e);
        }
    }

}
