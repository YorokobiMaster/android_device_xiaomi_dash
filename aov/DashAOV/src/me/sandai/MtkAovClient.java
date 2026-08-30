/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/** Minimal client for the frozen MediaTek IAovService v3 contract shipped on dash. */
final class MtkAovClient implements AutoCloseable {
    interface Listener {
        void onPresenceDetected();
        void onServiceDied();
    }

    private static final String TAG = "DashAOV.MtkClient";
    private static final String SERVICE_NAME =
            "vendor.mediatek.hardware.camera.aovservice.IAovService/default";
    private static final String SERVICE_DESCRIPTOR =
            "vendor.mediatek.hardware.camera.aovservice.IAovService";
    private static final String CALLBACK_DESCRIPTOR =
            "vendor.mediatek.hardware.camera.aovservice.IAovServiceCallback";
    private static final String INTERFACE_HASH =
            "8fc062dd44ace89c38a6d5e821f5711c3dc2fbbc";

    private static final int TRANSACTION_CONNECT = 1;
    private static final int TRANSACTION_DISCONNECT = 2;
    private static final int TRANSACTION_START = 6;
    private static final int TRANSACTION_STOP = 7;
    private static final int TRANSACTION_GET_HASH = 16777214;
    private static final int TRANSACTION_GET_VERSION = 16777215;

    private static final int SENSOR_ID_FRONT = 1;
    private static final int SENSOR_WIDTH = 640;
    private static final int SENSOR_HEIGHT = 480;
    private static final int FRAME_RATE = 10;
    private static final int DETECTION_MODE_FACE_AND_GAZE = 6;
    private static final int START_ATTEMPTS = 6;
    private static final long START_RETRY_DELAY_MS = 100;

    private final Listener mListener;
    private final AovCallback mCallback;
    private final IBinder.DeathRecipient mDeathRecipient = this::handleServiceDeath;

    private IBinder mService;
    private boolean mConnected;
    private boolean mStarted;

    MtkAovClient(Listener listener) {
        mListener = listener;
        mCallback = new AovCallback(this::handleEvent);
    }

    boolean start() {
        if (mStarted) {
            return true;
        }

        IBinder service = ServiceManager.checkService(SERVICE_NAME);
        if (service == null) {
            Log.w(TAG, "IAovService is not registered");
            return false;
        }

        try {
            service.linkToDeath(mDeathRecipient, 0);
            mService = service;
            int connectResult = transactInt(TRANSACTION_CONNECT, data ->
                    data.writeStrongBinder(mCallback.asBinder()));
            if (connectResult != 0) {
                Log.e(TAG, "IAovService connect failed: " + connectResult);
                close();
                return false;
            }
            mConnected = true;

            AovInitParams params = new AovInitParams();
            params.sensorId = SENSOR_ID_FRONT;
            params.sensorWidth = SENSOR_WIDTH;
            params.sensorHeight = SENSOR_HEIGHT;
            params.frameRate = FRAME_RATE;
            params.detectionMode = DETECTION_MODE_FACE_AND_GAZE;
            params.debugDisableCallback = false;
            params.xmParam1 = 1; // Select the stock locked/screen-off AOV route.

            int result = -1;
            for (int attempt = 0; attempt < START_ATTEMPTS && result != 0; attempt++) {
                result = transactInt(TRANSACTION_START, data ->
                        data.writeTypedObject(params, 0));
                if (result != 0 && attempt + 1 < START_ATTEMPTS) {
                    Thread.sleep(START_RETRY_DELAY_MS);
                }
            }
            if (result != 0) {
                Log.e(TAG, "IAovService start failed: " + result);
                close();
                return false;
            }

            mStarted = true;
            Log.i(TAG, "AOV gaze detection started");
            return true;
        } catch (RemoteException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.e(TAG, "Unable to start AOV", e);
            close();
            return false;
        }
    }

    @Override
    public void close() {
        IBinder service = mService;
        if (service == null) {
            mConnected = false;
            mStarted = false;
            return;
        }

        if (mStarted) {
            try {
                transactVoid(TRANSACTION_STOP, null);
            } catch (RemoteException e) {
                Log.w(TAG, "IAovService stop failed", e);
            }
        }
        if (mConnected) {
            try {
                transactVoid(TRANSACTION_DISCONNECT, null);
            } catch (RemoteException e) {
                Log.w(TAG, "IAovService disconnect failed", e);
            }
        }
        service.unlinkToDeath(mDeathRecipient, 0);
        mService = null;
        mConnected = false;
        mStarted = false;
    }

    private void handleEvent(AovEvent event) {
        if (!mStarted || event == null || event.results == null) {
            return;
        }
        for (AovResult result : event.results) {
            if (result == null || !"apu_output".equals(result.key) || result.data == null) {
                continue;
            }
            byte[] output = result.data.getByteVector();
            if (output != null && output.length > 24 && Byte.toUnsignedInt(output[24]) == 1) {
                Log.i(TAG, "AOV gaze detected");
                mListener.onPresenceDetected();
                return;
            }
        }
    }

    private void handleServiceDeath() {
        Log.w(TAG, "IAovService died");
        mService = null;
        mConnected = false;
        mStarted = false;
        mListener.onServiceDied();
    }

    private int transactInt(int code, ParcelWriter writer) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            if (writer != null) writer.write(data);
            if (!mService.transact(code, data, reply, 0)) {
                throw new RemoteException("IAovService transaction " + code + " is unavailable");
            }
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void transactVoid(int code, ParcelWriter writer) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            if (writer != null) writer.write(data);
            if (!mService.transact(code, data, reply, 0)) {
                throw new RemoteException("IAovService transaction " + code + " is unavailable");
            }
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private interface ParcelWriter {
        void write(Parcel data);
    }

    private static final class AovCallback extends Binder implements IInterface {
        interface EventListener {
            void onEvent(AovEvent event);
        }

        private final EventListener mListener;

        AovCallback(EventListener listener) {
            mListener = listener;
            markVintfStability();
            attachInterface(this, CALLBACK_DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code >= 1 && code <= TRANSACTION_GET_VERSION) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
            }
            switch (code) {
                case INTERFACE_TRANSACTION:
                    reply.writeString(CALLBACK_DESCRIPTOR);
                    return true;
                case TRANSACTION_GET_VERSION:
                    reply.writeNoException();
                    reply.writeInt(3);
                    return true;
                case TRANSACTION_GET_HASH:
                    reply.writeNoException();
                    reply.writeString(INTERFACE_HASH);
                    return true;
                case 1:
                    AovEvent event = data.readTypedObject(AovEvent.CREATOR);
                    data.enforceNoDataAvail();
                    mListener.onEvent(event);
                    reply.writeNoException();
                    return true;
                case 2: // onPause; stock client intentionally leaves this as a no-op.
                case 3: // onResume; stock client intentionally leaves this as a no-op.
                    data.enforceNoDataAvail();
                    reply.writeNoException();
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }
}
