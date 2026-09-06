/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

import me.sandai.dashled.aidl.DashLedCapabilities;
import me.sandai.dashled.aidl.DashLedEffect;
import me.sandai.dashled.aidl.DashLedFrame;
import me.sandai.dashled.aidl.IDashLedManager;
import me.sandai.dashled.aidl.IDashLedSession;

public class DashLedService extends Service {

    private static final int MAX_EFFECT_PERIOD_MS = 60_000;

    private LedArbiter mArbiter;
    private final List<SessionBinder> mLiveSessions = new ArrayList<>();

    private final SharedPreferences.OnSharedPreferenceChangeListener mGrantListener =
            (prefs, key) -> {
                if (!DashLedPrefs.KEY_ALLOWED_CLIENTS.equals(key)) {
                    return;
                }
                final List<SessionBinder> snapshot;
                synchronized (mLiveSessions) {
                    snapshot = new ArrayList<>(mLiveSessions);
                }
                for (SessionBinder session : snapshot) {
                    if (!DashLedPrefs.isClientAllowed(this, session.mOwnerUid)) {
                        session.doRelease();
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        mArbiter = LedCore.get(this).arbiter();
        DashLedPrefs.registerOnChangeListener(this, mGrantListener);
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
        public IDashLedSession acquireSession(int category, IBinder clientToken) {
            int uid = Binder.getCallingUid();
            if (uid != Process.myUid()) {
                if (category != CATEGORY_THIRD_PARTY) {
                    throw new SecurityException("category reserved for built-in producers");
                }
                enforceClientAllowed(uid);
                if (clientToken == null) {
                    throw new IllegalArgumentException(
                            "clientToken required so client death releases the session");
                }
            }
            return new SessionBinder(mArbiter.acquire(category), uid, clientToken);
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
        throw new SecurityException("grant this app in Settings > Notifications > "
                + "LED apertures > App access first");
    }

    private final class SessionBinder extends IDashLedSession.Stub
            implements IBinder.DeathRecipient {
        private final LedArbiter.Session mSession;
        private final int mOwnerUid;
        private final IBinder mClientToken;
        private boolean mReleased;

        SessionBinder(LedArbiter.Session session, int ownerUid, IBinder clientToken) {
            mSession = session;
            mOwnerUid = ownerUid;
            mClientToken = clientToken;
            if (clientToken != null) {
                try {
                    clientToken.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    mArbiter.release(session);
                    throw new IllegalStateException("client token is already dead");
                }
            }
            synchronized (mLiveSessions) {
                mLiveSessions.add(this);
            }
        }

        private void checkOwner() {
            if (Binder.getCallingUid() != mOwnerUid) {
                throw new SecurityException("session is owned by uid " + mOwnerUid);
            }
        }

        @Override
        public void setFrame(DashLedFrame frame) {
            checkOwner();
            if (frame == null || frame.colors == null
                    || frame.colors.length != Aw21024Backend.ZONE_COUNT) {
                throw new IllegalArgumentException(
                        "frame needs exactly " + Aw21024Backend.ZONE_COUNT + " zone colors");
            }
            mArbiter.setFrame(mSession, frame.colors,
                    Math.max(0, Math.min(255, frame.brightness)));
        }

        @Override
        public void playEffect(DashLedEffect effect) {
            checkOwner();
            if (effect == null || effect.colors == null
                    || effect.colors.length != Aw21024Backend.ZONE_COUNT) {
                throw new IllegalArgumentException(
                        "effect needs exactly " + Aw21024Backend.ZONE_COUNT + " zone colors");
            }
            if (effect.type != DashLedEffect.TYPE_BREATH) {
                throw new IllegalArgumentException("unknown effect type " + effect.type);
            }
            if (effect.periodMs <= 0 || effect.periodMs > MAX_EFFECT_PERIOD_MS) {
                throw new IllegalArgumentException(
                        "periodMs must be in 1.." + MAX_EFFECT_PERIOD_MS);
            }
            effect.brightness = Math.max(0, Math.min(255, effect.brightness));
            mArbiter.playEffect(mSession, effect, null);
        }

        @Override
        public void clear() {
            checkOwner();
            mArbiter.clear(mSession);
        }

        @Override
        public void release() {
            checkOwner();
            doRelease();
        }

        private void doRelease() {
            synchronized (this) {
                if (mReleased) {
                    return;
                }
                mReleased = true;
            }
            synchronized (mLiveSessions) {
                mLiveSessions.remove(this);
            }
            if (mClientToken != null) {
                mClientToken.unlinkToDeath(this, 0);
            }
            mArbiter.release(mSession);
        }

        @Override
        public void binderDied() {
            doRelease();
        }
    }
}
