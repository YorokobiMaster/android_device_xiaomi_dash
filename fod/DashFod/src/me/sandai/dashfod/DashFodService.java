/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashfod;

import static android.hardware.biometrics.BiometricRequestConstants.*;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.biometrics.AuthenticationStateListener;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.biometrics.events.AuthenticationAcquiredInfo;
import android.hardware.biometrics.events.AuthenticationErrorInfo;
import android.hardware.biometrics.events.AuthenticationFailedInfo;
import android.hardware.biometrics.events.AuthenticationHelpInfo;
import android.hardware.biometrics.events.AuthenticationStartedInfo;
import android.hardware.biometrics.events.AuthenticationStoppedInfo;
import android.hardware.biometrics.events.AuthenticationSucceededInfo;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.DreamManagerInternal;
import android.util.Log;

import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.util.NoSuchElementException;
import java.util.function.Consumer;

import vendor.xiaomi.hardware.fingerprintextension.IXiaomiFingerprint;

/** Observes authentication in its owning process; never creates an authentication request. */
public final class DashFodService extends SystemService {
    private static final String TAG = "DashFod";
    // How long the recovery doze dream stays up before the display drops back to OFF.
    private static final long DOZE_PULSE_MS = 5_000;
    private static final long RETRY_MIN_MS = 1_000;
    private static final long RETRY_MAX_MS = 30_000;
    private Handler mHandler;
    private FodController mController;
    private VendorClient mClient;
    private FingerprintManager mFingerprint;
    private PowerManager mPower;
    private DreamManagerInternal mDreams;
    private int mUserId;
    private Boolean mKeyguardFodAvailable;
    private boolean mAvailabilityPending;
    private long mRetryDelay = RETRY_MIN_MS;
    private boolean mRetryScheduled;
    private final Runnable mRetry = () -> {
        mRetryScheduled = false;
        refreshPolicy();
        scheduleRetry();
    };

    public DashFodService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        Context context = getContext();
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mClient = new VendorClient(mHandler, this::onVendorDeath);
        mController = new FodController(mClient, message -> Log.i(TAG, message));
        mFingerprint = context.getSystemService(FingerprintManager.class);
        mPower = context.getSystemService(PowerManager.class);
        mDreams = LocalServices.getService(DreamManagerInternal.class);
        mUserId = ActivityManager.getCurrentUser();
        mHandler.post(() -> {
            mController.onStartup();
            refreshPolicy();
            scheduleRetry();
        });

        // Device-specific services start after AuthService and before SystemUI.
        // Register synchronously here, not behind a possibly blocked vendor call.
        context.getSystemService(BiometricManager.class)
                .registerAuthenticationStateListener(mAuthenticationListener);
        mFingerprint.registerBiometricStateListener(new BiometricStateListener() {
            @Override
            public void onEnrollmentsChanged(int userId, int sensorId, boolean enrolled) {
                mHandler.post(() -> {
                    if (userId == mUserId) onPolicyChanged();
                });
            }
        });
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onPolicyChanged();
            }
        }, filter, null, mHandler);
        context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.SCREEN_OFF_UNLOCK_UDFPS_ENABLED), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        onPolicyChanged();
                    }
                }, UserHandle.USER_ALL);
        Log.i(TAG, "system-service listener registered before SystemUI");
    }

    @Override
    public void onUserSwitching(TargetUser from, TargetUser to) {
        mHandler.post(() -> {
            mUserId = to.getUserIdentifier();
            mController.onUserChanged();
            mKeyguardFodAvailable = null;
            onPolicyChanged();
        });
    }

    private final AuthenticationStateListener mAuthenticationListener =
            new AuthenticationStateListener.Stub() {
        @Override
        public void onAuthenticationStarted(AuthenticationStartedInfo info) {
            post(info.getBiometricSourceType(), info.getRequestReason(), "START", operation -> {
                refreshPolicy();
                mController.onStart(operation);
            });
        }

        @Override
        public void onAuthenticationStopped(AuthenticationStoppedInfo info) {
            post(info.getBiometricSourceType(), info.getRequestReason(), "STOP",
                    mController::onStop);
        }

        @Override
        public void onAuthenticationError(AuthenticationErrorInfo info) {
            post(info.getBiometricSourceType(), info.getRequestReason(), "ERROR",
                    mController::onError);
        }

        @Override
        public void onAuthenticationFailed(AuthenticationFailedInfo info) {
            post(info.getBiometricSourceType(), info.getRequestReason(), "FAILED",
                    mController::onFailed);
        }

        @Override
        public void onAuthenticationSucceeded(AuthenticationSucceededInfo info) {
            post(info.getBiometricSourceType(), info.getRequestReason(), "SUCCEEDED",
                    mController::onSucceeded);
        }

        @Override
        public void onAuthenticationAcquired(AuthenticationAcquiredInfo info) {}

        @Override
        public void onAuthenticationHelp(AuthenticationHelpInfo info) {}
    };

    private void post(BiometricSourceType source, int reason, String edge,
            Consumer<FodController.Operation> event) {
        if (source != BiometricSourceType.FINGERPRINT) return;
        FodController.Operation operation;
        switch (reason) {
            case REASON_ENROLL_FIND_SENSOR:
            case REASON_ENROLL_ENROLLING:
                operation = FodController.Operation.ENROLLMENT;
                break;
            case REASON_AUTH_KEYGUARD:
                operation = FodController.Operation.KEYGUARD_AUTH;
                break;
            case REASON_AUTH_BP:
            case REASON_AUTH_SETTINGS:
            case REASON_AUTH_OTHER:
                operation = FodController.Operation.GENERIC_AUTH;
                break;
            default:
                return;
        }
        mHandler.post(() -> {
            Log.i(TAG, "lifecycle edge=" + edge + " operation=" + operation);
            event.accept(operation);
            if (operation == FodController.Operation.KEYGUARD_AUTH
                    && ("FAILED".equals(edge) || "ERROR".equals(edge))) {
                pulseDoze(edge);
            }
            scheduleRetry();
        });
    }

    // A terminal keyguard failure while noninteractive can leave the touch firmware replaying
    // a stale contact with no further framework signal. Only a display power cycle reaches the
    // panel resume/firmware-reload path that clears it. This device never dozes on screen-off,
    // so SystemUI's DozeTriggers are not listening; raise the AOD doze dream directly for a
    // OFF -> DOZE -> OFF cycle. The visible AOD also surfaces the failure the user could not
    // see with the screen off.
    private void pulseDoze(String edge) {
        if (mPower.isInteractive() || mDreams.isDreaming()) return;
        mDreams.startDream(true /* doze */, "dash-fod auth " + edge);
        mHandler.postDelayed(() -> mDreams.stopDream(false /* immediate */,
                "dash-fod pulse done"), DOZE_PULSE_MS);
        Log.i(TAG, "pulsed doze edge=" + edge);
    }

    private void onPolicyChanged() {
        refreshPolicy();
        scheduleRetry();
    }

    private void refreshPolicy() {
        boolean enrolled;
        try {
            enrolled = mFingerprint.hasEnrolledFingerprints(mUserId);
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot query enrollment for user=" + mUserId, e);
            mAvailabilityPending = true;
            mController.setKeyguardAllowed(false);
            return;
        }
        boolean defaultOn = getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_screen_off_udfps_default_on);
        boolean screenOff = Settings.Secure.getIntForUser(getContext().getContentResolver(),
                Settings.Secure.SCREEN_OFF_UNLOCK_UDFPS_ENABLED,
                defaultOn ? 1 : 0, mUserId) == 1;
        mAvailabilityPending = !syncKeyguardFodAvailability(enrolled);
        mController.setKeyguardAllowed(enrolled && !mAvailabilityPending
                && (mPower.isInteractive() || screenOff));
    }

    private boolean syncKeyguardFodAvailability(boolean available) {
        if (mKeyguardFodAvailable != null && mKeyguardFodAvailable == available) return true;
        if (!mClient.isConnected() && !mClient.connect()) return false;
        if (!mClient.extCmd(8, available ? 1 : 0)) return false;
        mKeyguardFodAvailable = available;
        return true;
    }

    private void onVendorDeath() {
        mKeyguardFodAvailable = null;
        mController.onVendorDeath();
        onPolicyChanged();
    }

    private void scheduleRetry() {
        if (!mAvailabilityPending && !mController.needsReconcile()) {
            mHandler.removeCallbacks(mRetry);
            mRetryScheduled = false;
            mRetryDelay = RETRY_MIN_MS;
        } else if (!mRetryScheduled) {
            mRetryScheduled = true;
            mHandler.postDelayed(mRetry, mRetryDelay);
            mRetryDelay = Math.min(mRetryDelay * 2, RETRY_MAX_MS);
        }
    }

    private static final class VendorClient implements FodController.Client {
        private static final String SERVICE = IXiaomiFingerprint.DESCRIPTOR + "/default";
        private static final String EXPECTED_HASH = "1ed45089cc89154986c15fc1c591d72e9ac7ae0d";
        private final Handler mHandler;
        private final Runnable mOnDeath;
        private IBinder mBinder;
        private IXiaomiFingerprint mRemote;

        VendorClient(Handler handler, Runnable onDeath) {
            mHandler = handler;
            mOnDeath = onDeath;
        }

        @Override
        public boolean connect() {
            if (mRemote != null) return true;
            IBinder binder = ServiceManager.checkService(SERVICE);
            if (binder == null) return false;
            Binder.allowBlocking(binder);
            IBinder.DeathRecipient recipient = () -> mHandler.post(() -> {
                if (mBinder != binder) return;
                mBinder = null;
                mRemote = null;
                Log.w(TAG, "vendor connection died");
                mOnDeath.run();
            });
            try {
                binder.linkToDeath(recipient, 0);
                IXiaomiFingerprint remote = IXiaomiFingerprint.Stub.asInterface(binder);
                if (remote.getInterfaceVersion() != 1
                        || !EXPECTED_HASH.equals(remote.getInterfaceHash())) {
                    throw new IllegalStateException("Unexpected fingerprint extension interface");
                }
                mBinder = binder;
                mRemote = remote;
                return true;
            } catch (RemoteException | RuntimeException e) {
                try {
                    binder.unlinkToDeath(recipient, 0);
                } catch (NoSuchElementException ignored) {}
                Log.e(TAG, "Cannot connect fingerprint extension", e);
                return false;
            }
        }

        @Override
        public boolean isConnected() {
            return mRemote != null;
        }

        @Override
        public boolean extCmd(int command, int parameter) {
            if (mRemote == null) return false;
            try {
                int result = mRemote.extCmd(command, parameter);
                Log.i(TAG, "extCmd command=" + command + " parameter=" + parameter
                        + " result=" + result);
                return result == 0;
            } catch (RemoteException | RuntimeException e) {
                Log.e(TAG, "extCmd command=" + command + " parameter=" + parameter
                        + " failed", e);
                return false;
            }
        }
    }
}
