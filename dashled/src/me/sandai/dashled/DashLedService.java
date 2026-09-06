/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;

import me.sandai.dashled.aidl.DashLedCapabilities;
import me.sandai.dashled.aidl.DashLedEffect;
import me.sandai.dashled.aidl.DashLedFrame;
import me.sandai.dashled.aidl.IDashLedManager;
import me.sandai.dashled.aidl.IDashLedSession;

public class DashLedService extends Service {

    private LedArbiter mArbiter;

    @Override
    public void onCreate() {
        super.onCreate();
        mArbiter = LedCore.get(this).arbiter();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mManager;
    }

    private final IDashLedManager.Stub mManager = new IDashLedManager.Stub() {
        @Override
        public int getApiVersion() {
            return API_VERSION;
        }

        @Override
        public DashLedCapabilities getCapabilities() {
            DashLedCapabilities caps = new DashLedCapabilities();
            caps.apiVersion = API_VERSION;
            caps.zoneCount = Aw21024Backend.ZONE_COUNT;
            caps.rgbSupported = true;
            caps.brightnessSupported = true;
            caps.hardwareBreathingSupported = false;
            caps.hardwareGradientSupported = false;
            caps.minFrameIntervalMs = Aw21024Backend.MIN_FRAME_INTERVAL_MS;
            return caps;
        }

        @Override
        public IDashLedSession acquireSession(int category) {
            int uid = Binder.getCallingUid();
            if (uid != Process.myUid()) {
                if (category != CATEGORY_THIRD_PARTY) {
                    throw new SecurityException("category reserved for built-in producers");
                }
                enforceClientAllowed(uid);
            }
            return new SessionBinder(mArbiter.acquire(category));
        }
    };

    private void enforceClientAllowed(int uid) {
        final long token = Binder.clearCallingIdentity();
        try {
            if (DashLedPrefs.isClientAllowed(this, uid)) {
                return;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        throw new SecurityException("grant this app in Settings > Accessibility > "
                + "LED apertures > App access first");
    }

    private final class SessionBinder extends IDashLedSession.Stub
            implements IBinder.DeathRecipient {
        private final LedArbiter.Session mSession;
        private boolean mReleased;

        SessionBinder(LedArbiter.Session session) {
            mSession = session;
            linkToDeath(this, 0);
        }

        @Override
        public void setFrame(DashLedFrame frame) {
            mArbiter.setFrame(mSession, frame.colors, frame.brightness);
        }

        @Override
        public void playEffect(DashLedEffect effect) {
            if (effect.periodMs <= 0) {
                throw new IllegalArgumentException("periodMs must be > 0");
            }
            mArbiter.playEffect(mSession, effect, null);
        }

        @Override
        public void clear() {
            mArbiter.clear(mSession);
        }

        @Override
        public void release() {
            synchronized (this) {
                if (mReleased) {
                    return;
                }
                mReleased = true;
            }
            unlinkToDeath(this, 0);
            mArbiter.release(mSession);
        }

        @Override
        public void binderDied() {
            release();
        }
    }
}
