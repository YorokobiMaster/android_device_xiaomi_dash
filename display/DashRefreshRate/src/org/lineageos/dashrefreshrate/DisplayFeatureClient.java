/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashrefreshrate;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

final class DisplayFeatureClient {
    private static final String TAG = "DashRefreshRate";
    private static final String SERVICE_NAME =
            "vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature/default";
    private static final String INTERFACE_DESCRIPTOR =
            "vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature";
    private static final int TRANSACTION_SET_FEATURE = 7;
    private static final int PRIMARY_DISPLAY = 0;
    private static final int FEATURE_DOZE_BRIGHTNESS = 25;
    private static final int COOKIE = 255;

    boolean setDozeBrightness(int value) {
        IBinder service = ServiceManager.checkService(SERVICE_NAME);
        if (service == null) {
            Log.w(TAG, "displayfeature service unavailable");
            return false;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            String descriptor = service.getInterfaceDescriptor();
            data.writeInterfaceToken(TextUtils.isEmpty(descriptor)
                    ? INTERFACE_DESCRIPTOR : descriptor);
            data.writeInt(PRIMARY_DISPLAY);
            data.writeInt(FEATURE_DOZE_BRIGHTNESS);
            data.writeInt(value);
            data.writeInt(COOKIE);
            if (!service.transact(TRANSACTION_SET_FEATURE, data, reply, 0)) {
                Log.w(TAG, "displayfeature rejected setFeature transaction");
                return false;
            }
            reply.readException();
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "failed to set doze brightness=" + value, e);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
