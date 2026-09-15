/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import java.util.HashMap;
import vendor.mediatek.hardware.mtkpower.IMtkPowerService;

/** All calls run on the adapter worker. Old handles never cross a HAL restart. */
final class MtkPowerClient implements PowerPolicy.Transport {
    private static final String NAME =
            "vendor.mediatek.hardware.mtkpower.IMtkPowerService/default";
    private final HashMap<Integer, IMtkPowerService> owners = new HashMap<>();
    private final Runnable death;
    private IMtkPowerService service;
    private volatile IBinder activeBinder;

    MtkPowerClient(Runnable death) {
        this.death = death;
    }

    @Override
    public int acquire(int hint, int durationMs) throws RemoteException {
        if (service != null && !service.asBinder().isBinderAlive()) {
            service = null;
            activeBinder = null;
            throw new RemoteException("MTK power restarted; discard this event");
        }
        if (service == null) {
            final IBinder binder = ServiceManager.checkService(NAME);
            if (binder == null) throw new RemoteException("MTK power service unavailable");
            activeBinder = binder;
            binder.linkToDeath(() -> {
                if (activeBinder == binder) death.run();
            }, 0);
            service = IMtkPowerService.Stub.asInterface(binder);
        }
        final IMtkPowerService owner = service;
        final int handle = owner.perfCusLockHint(hint, durationMs, Process.myPid());
        if (handle > 0) owners.put(handle, owner);
        Slog.d("DashPower", "acquire hint=" + hint + " duration=" + durationMs + " handle=" + handle);
        return handle;
    }

    @Override
    public void release(int handle) throws RemoteException {
        final IMtkPowerService owner = owners.remove(handle);
        if (owner != null && owner.asBinder().isBinderAlive()) {
            final int result = owner.perfLockReleaseSync(handle, 0);
            Slog.d("DashPower", "release handle=" + handle + " result=" + result);
        }
    }
}
