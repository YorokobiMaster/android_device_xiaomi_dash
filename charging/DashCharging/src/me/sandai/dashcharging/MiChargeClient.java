/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashcharging;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/** Minimal client for the frozen Xiaomi MiCharge v2 contract shipped on dash. */
final class MiChargeClient {
    private static final String TAG = "DashCharging.MiCharge";
    private static final String SERVICE_NAME =
            "vendor.xiaomi.hardware.micharge.IMiCharge/default";
    private static final String SERVICE_DESCRIPTOR =
            "vendor.xiaomi.hardware.micharge.IMiCharge";
    private static final String SMART_CHG_PATH = "smart_chg";

    private static final int TRANSACTION_GET_MI_CHARGE_PATH = 18;
    private static final int TRANSACTION_SET_MI_CHARGE_PATH = 43;
    private static final int FLAG_PRIVATE_VENDOR = 0x10000000;

    private static final int SMART_CHG_BYPASS_MASK = 1 << 10;
    private static final String BYPASS_DISABLED_VALUE = "1024";
    private static final String BYPASS_ENABLED_VALUE = "1025";

    private MiChargeClient() {}

    static Boolean isBypassRequested() {
        String value = getPath(SMART_CHG_PATH);
        if (value == null) {
            return null;
        }
        try {
            int flags = Integer.decode(value.trim());
            return (flags & SMART_CHG_BYPASS_MASK) != 0;
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid smart_chg value: " + value, e);
            return null;
        }
    }

    static boolean setBypassRequested(boolean enabled) {
        IBinder service = ServiceManager.checkService(SERVICE_NAME);
        if (service == null) {
            Log.e(TAG, "MiCharge service is unavailable");
            return false;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeString(SMART_CHG_PATH);
            data.writeString(enabled ? BYPASS_ENABLED_VALUE : BYPASS_DISABLED_VALUE);
            if (!service.transact(TRANSACTION_SET_MI_CHARGE_PATH, data, reply,
                    FLAG_PRIVATE_VENDOR)) {
                Log.e(TAG, "MiCharge setMiChargePath transaction is unavailable");
                return false;
            }
            reply.readException();
            reply.readInt();
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Unable to update smart_chg", e);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }

        Boolean requested = isBypassRequested();
        if (requested == null || requested != enabled) {
            Log.e(TAG, "smart_chg readback did not match requested state");
            return false;
        }
        return true;
    }

    private static String getPath(String path) {
        IBinder service = ServiceManager.checkService(SERVICE_NAME);
        if (service == null) {
            return null;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeString(path);
            if (!service.transact(TRANSACTION_GET_MI_CHARGE_PATH, data, reply,
                    FLAG_PRIVATE_VENDOR)) {
                Log.e(TAG, "MiCharge getMiChargePath transaction is unavailable");
                return null;
            }
            reply.readException();
            return reply.readString();
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Unable to read " + path, e);
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
