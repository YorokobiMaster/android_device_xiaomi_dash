// Copyright (C) 2026 GitHub @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import me.sandai.dashpower.IDashThermalService;

/** Synchronous access for provider Binder threads and the tile's worker only. No cached state. */
final class PerformanceModeClient {
    static final Uri STATE_URI = Uri.parse(IDashThermalService.PERFORMANCE_STATE_URI);

    private static IDashThermalService service() throws RemoteException {
        IDashThermalService service = IDashThermalService.Stub.asInterface(
                ServiceManager.checkService(ThermalServiceClient.SERVICE_NAME));
        if (service == null || service.getApiVersion() < 2) {
            throw new IllegalStateException("Performance mode backend unavailable");
        }
        return service;
    }

    static Bundle read() throws RemoteException {
        return service().getState(UserHandle.myUserId());
    }

    static void set(boolean enabled) throws RemoteException {
        IDashThermalService service = service();
        Bundle state = service.getState(UserHandle.myUserId());
        if (!available(state)) throw new IllegalStateException("Thermal control unavailable");
        service.setPerformanceMode(UserHandle.myUserId(), enabled);
    }

    static boolean available(Bundle state) {
        return state != null && state.getBoolean("ready") && state.getBoolean("enabled");
    }

    private PerformanceModeClient() {}
}
