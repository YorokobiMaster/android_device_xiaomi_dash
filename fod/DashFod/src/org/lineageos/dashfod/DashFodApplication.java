/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.dashfod;

import static android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_BP;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_OTHER;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_SETTINGS;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_ENROLLING;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_FIND_SENSOR;
import static android.hardware.biometrics.BiometricStateListener.STATE_KEYGUARD_AUTH;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.biometrics.AuthenticationStateListener;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.biometrics.events.AuthenticationAcquiredInfo;
import android.hardware.biometrics.events.AuthenticationErrorInfo;
import android.hardware.biometrics.events.AuthenticationFailedInfo;
import android.hardware.biometrics.events.AuthenticationHelpInfo;
import android.hardware.biometrics.events.AuthenticationStartedInfo;
import android.hardware.biometrics.events.AuthenticationStoppedInfo;
import android.hardware.biometrics.events.AuthenticationSucceededInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.NoSuchElementException;
import java.util.function.Consumer;

import vendor.xiaomi.hardware.fingerprintextension.IXiaomiFingerprint;

public final class DashFodApplication extends Application {
    private static final String TAG = "DashFod";

    // xiaomi.sensor.pickup ("pickup  Wakeup"), the vendor pickup sensor.
    // There is no standard TYPE_PICK_UP_GESTURE sensor on this device, so
    // AOSP's pickup path is inert; HyperOS (KeyguardSensorInjector) wakes
    // directly on this sensor's raise value, and DashFod does the same.
    // values[0]: 1.0 = raise, 2.0 = put-down.
    //
    // The vendor Fod Wakeup sensor is deliberately NOT used for waking.
    // Per HyperOS's MiuiGxzwSensor its values are device-motion heuristics
    // (1.0 = move, 2.0 = stable, 3.0 = put up) that stock only uses to show
    // the AOD fingerprint icon; a real press reports the same 3.0 as a
    // raise, so presses and jostles are indistinguishable and any fod-based
    // wake is a false-wake source (observed on device: a pickup-unpaired
    // motion event woke the display as WAKE_REASON_BIOMETRIC). Presses on
    // AOD reach the keyguard through the touch path; with AOD off the
    // vendor's touch FOD dies seconds after screen-off anyway.
    private static final int PICKUP_SENSOR_TYPE = 33171036;

    // values[0] reported by the pickup sensor for a raise.
    private static final float PICKUP_VALUE_RAISE = 1.0f;

    private Handler mHandler;
    private FodController mController;
    private KeyguardAuthGate mKeyguardAuthGate;
    private VendorClient mClient;
    private BiometricManager mBiometricManager;
    private FingerprintManager mFingerprintManager;
    private PowerManager mPowerManager;
    private SensorManager mSensorManager;
    private Sensor mPickupSensor;
    private SensorEventListener mPickupSensorListener;
    private boolean mPickupSensorRegistered;
    private AuthenticationStateListener mListener;
    private BiometricStateListener mBiometricStateListener;
    private BroadcastReceiver mScreenOnReceiver;
    private volatile int mListenerGeneration;

    @Override
    public void onCreate() {
        super.onCreate();

        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mClient = new VendorClient(mHandler, this::onVendorDeath);
        mController = new FodController(mClient, message -> Log.i(TAG, message));
        mKeyguardAuthGate = new KeyguardAuthGate();
        mBiometricManager = getSystemService(BiometricManager.class);
        mFingerprintManager = getSystemService(FingerprintManager.class);
        mPowerManager = getSystemService(PowerManager.class);
        mSensorManager = getSystemService(SensorManager.class);
        if (mSensorManager != null) {
            for (Sensor sensor : mSensorManager.getSensorList(Sensor.TYPE_ALL)) {
                if (sensor.getType() == PICKUP_SENSOR_TYPE) {
                    mPickupSensor = sensor;
                    break;
                }
            }
        }
        Log.i(TAG, "pickup-sensor available=" + (mPickupSensor != null));
        mHandler.post(() -> {
            mController.onStartup();
            registerScreenOnReceiver();
            registerBiometricStateListener();
            rotateAuthenticationStateListener();
            // The screen broadcasts are not sticky: if this process (re)starts
            // while the screen is already off, no SCREEN_OFF will arrive, so
            // sync the sensor registration with the live interactive state.
            if (mPowerManager != null && !mPowerManager.isInteractive()) {
                maybeRegisterPickupSensor();
            }
        });

        if (mBiometricManager == null) {
            Log.e(TAG, "BiometricManager unavailable; listener not registered");
        }
    }

    private AuthenticationStateListener createAuthenticationStateListener(int generation) {
        return new AuthenticationStateListener.Stub() {
            @Override
            public void onAuthenticationStarted(AuthenticationStartedInfo info) {
                post(generation, "START", info.getBiometricSourceType(), info.getRequestReason(),
                        operation -> {
                            if (operation == FodController.Operation.KEYGUARD_AUTH) {
                                onKeyguardStart();
                            } else {
                                mController.onStart(operation);
                            }
                        });
            }

            @Override
            public void onAuthenticationStopped(AuthenticationStoppedInfo info) {
                post(generation, "STOP", info.getBiometricSourceType(), info.getRequestReason(),
                        operation -> {
                            if (operation == FodController.Operation.KEYGUARD_AUTH) {
                                clearKeyguardGate("STOP");
                            }
                            mController.onStop(operation);
                        });
            }

            @Override
            public void onAuthenticationError(AuthenticationErrorInfo info) {
                post(generation, "ERROR", info.getBiometricSourceType(), info.getRequestReason(),
                        operation -> {
                            if (operation == FodController.Operation.KEYGUARD_AUTH) {
                                clearKeyguardGate("ERROR");
                            }
                            mController.onError(operation);
                        });
            }

            @Override
            public void onAuthenticationAcquired(AuthenticationAcquiredInfo info) {}

            @Override
            public void onAuthenticationFailed(AuthenticationFailedInfo info) {
                post(generation, "FAILED", info.getBiometricSourceType(), info.getRequestReason(),
                        mController::onFailed);
            }

            @Override
            public void onAuthenticationHelp(AuthenticationHelpInfo info) {}

            @Override
            public void onAuthenticationSucceeded(AuthenticationSucceededInfo info) {
                post(generation, "SUCCEEDED", info.getBiometricSourceType(),
                        info.getRequestReason(), operation -> {
                            if (operation == FodController.Operation.KEYGUARD_AUTH) {
                                clearKeyguardGate("SUCCEEDED");
                            }
                            mController.onSucceeded(operation);
                        });
            }
        };
    }

    private void registerBiometricStateListener() {
        if (mFingerprintManager == null) {
            Log.e(TAG, "FingerprintManager unavailable; biometric listener not registered");
            return;
        }

        mBiometricStateListener = new BiometricStateListener() {
            @Override
            public void onStateChanged(int state) {
                int generation = mListenerGeneration;
                mHandler.post(() -> {
                    if (generation != mListenerGeneration) {
                        Log.i(TAG, "biometric-state generation=" + generation + " dropped=true");
                        return;
                    }
                    boolean keyguardAuth = state == STATE_KEYGUARD_AUTH;
                    KeyguardAuthGate.Action action =
                            mKeyguardAuthGate.onBiometricState(keyguardAuth);
                    Log.i(TAG, "biometric-state raw=" + state + " keyguard=" + keyguardAuth
                            + " generation=" + generation + " gate=" + mKeyguardAuthGate
                            + " action=" + action);
                    handleGateAction(action);
                });
            }
        };
        try {
            mFingerprintManager.registerBiometricStateListener(mBiometricStateListener);
            Log.i(TAG, "biometric-state-listener registered=true");
        } catch (RuntimeException e) {
            mBiometricStateListener = null;
            Log.e(TAG, "biometric-state-listener registered=false", e);
        }
    }

    private void registerScreenOnReceiver() {
        mScreenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    unregisterPickupSensor();
                    KeyguardAuthGate.Action gateAction = mKeyguardAuthGate.onScreenOn();
                    Log.i(TAG, "screen-on gate=" + mKeyguardAuthGate + " action=" + gateAction);
                    handleGateAction(gateAction);
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    maybeRegisterPickupSensor();
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(mScreenOnReceiver, filter, null, mHandler);
            Log.i(TAG, "screen-on-receiver registered=true");
        } catch (RuntimeException e) {
            mScreenOnReceiver = null;
            Log.e(TAG, "screen-on-receiver registered=false", e);
        }
    }

    private void maybeRegisterPickupSensor() {
        if (mSensorManager == null || mPickupSensor == null) return;
        // Registration stays gated on the screen-off UDFPS switch. The pickup
        // sensor itself fires regardless of vendor arming (verified: raises
        // still wake with AOD off, where doze-time arming is skipped), but
        // with the fingerprint switch off there is no doze fingerprint UX for
        // a raise to lead into, so the pickup gesture follows that switch.
        if (!isScreenOffUdfpsEnabled()) {
            Log.i(TAG, "pickup-sensor register skipped: setting disabled");
            return;
        }
        if (mPickupSensorListener == null) {
            mPickupSensorListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    onPickupSensorEvent(event);
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            };
        }
        if (!mPickupSensorRegistered) {
            mPickupSensorRegistered = mSensorManager.registerListener(mPickupSensorListener,
                    mPickupSensor, SensorManager.SENSOR_DELAY_NORMAL, mHandler);
        }
        Log.i(TAG, "pickup-sensor registered=" + mPickupSensorRegistered);
    }

    private void unregisterPickupSensor() {
        if (!mPickupSensorRegistered) return;
        mSensorManager.unregisterListener(mPickupSensorListener, mPickupSensor);
        mPickupSensorRegistered = false;
        Log.i(TAG, "pickup-sensor unregistered=true");
    }

    private void onPickupSensorEvent(SensorEvent event) {
        if (event.values[0] != PICKUP_VALUE_RAISE) return;
        Log.i(TAG, "pickup-sensor raise ts=" + event.timestamp);

        // HyperOS parity: raises wake directly from the pickup sensor.
        if (isInteractive() || !isPickupGestureEnabled()) return;
        wake(PowerManager.WAKE_REASON_LIFT, "org.lineageos.dashfod:pickup");
    }

    private void wake(int reason, String details) {
        try {
            mPowerManager.wakeUp(SystemClock.uptimeMillis(), reason, details);
            Log.i(TAG, "pickup-sensor wake=requested reason=" + reason + " details=" + details);
        } catch (RuntimeException e) {
            Log.e(TAG, "pickup-sensor wake=failed", e);
        }
    }

    private boolean isInteractive() {
        try {
            return mPowerManager != null && mPowerManager.isInteractive();
        } catch (RuntimeException e) {
            Log.e(TAG, "interactive state unavailable", e);
            return false;
        }
    }

    private boolean isPickupGestureEnabled() {
        // Same user-0 limitation as isScreenOffUdfpsEnabled(). The default
        // mirrors frameworks' config_dozePickupGestureEnabled (true).
        return Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.DOZE_PICK_UP_GESTURE, 1) == 1;
    }

    private boolean isScreenOffUdfpsEnabled() {
        // NOTE: reads user 0's value. This process cannot resolve the foreground
        // user (ActivityManager.getCurrentUser() requires MANAGE_USERS-class
        // permissions), so secondary users follow the primary user's toggle.
        return Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.SCREEN_OFF_UNLOCK_UDFPS_ENABLED, 0) == 1;
    }

    private void onKeyguardStart() {
        boolean interactive = isInteractive();
        // With screen-off UDFPS enabled, a doze-time keyguard auth start must
        // arm immediately: waiting for interactive deadlocks (no arm -> the
        // vendor's touch FOD never delivers finger-down in doze).
        // Arming in doze is skipped when AOD is off: the armed vendor keeps
        // its touch FOD alive for a few seconds after the display powers
        // down, which made real presses unlock from a fully black screen.
        // Stock ties its fingerprint UX to AOD, so do the same.
        boolean armAllowed = interactive
                || (isScreenOffUdfpsEnabled() && isAlwaysOnDisplayEnabled());
        KeyguardAuthGate.Action action =
                mKeyguardAuthGate.onAuthenticationStart(armAllowed);
        Log.i(TAG, "keyguard-start interactive=" + interactive + " arm=" + armAllowed
                + " gate=" + mKeyguardAuthGate + " action=" + action);
        handleGateAction(action);
    }

    private boolean isAlwaysOnDisplayEnabled() {
        // Same user-0 limitation as isScreenOffUdfpsEnabled(). The default
        // mirrors frameworks' config_dozeAlwaysOnEnabled (true).
        return Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON, 1) == 1;
    }

    private void handleGateAction(KeyguardAuthGate.Action action) {
        if (action == KeyguardAuthGate.Action.START) {
            mController.onStart(FodController.Operation.KEYGUARD_AUTH);
        } else if (action == KeyguardAuthGate.Action.STOP) {
            mController.onStop(FodController.Operation.KEYGUARD_AUTH);
        }
    }

    private void clearKeyguardGate(String reason) {
        mKeyguardAuthGate.onTerminal();
        Log.i(TAG, "gate clear=" + reason + " state=" + mKeyguardAuthGate);
    }

    private void rotateAuthenticationStateListener() {
        if (mBiometricManager == null) {
            ++mListenerGeneration;
            mListener = null;
            Log.e(TAG, "BiometricManager unavailable; listener generation invalidated");
            return;
        }

        AuthenticationStateListener oldListener = mListener;
        int generation = ++mListenerGeneration;
        AuthenticationStateListener newListener = createAuthenticationStateListener(generation);
        try {
            mBiometricManager.registerAuthenticationStateListener(newListener);
            mListener = newListener;
            Log.i(TAG, "listener generation=" + generation + " registered=true");
        } catch (RuntimeException e) {
            ++mListenerGeneration;
            mListener = null;
            Log.e(TAG, "listener generation=" + generation + " registered=false", e);
        }

        if (oldListener != null) {
            try {
                mBiometricManager.unregisterAuthenticationStateListener(oldListener);
            } catch (RuntimeException e) {
                Log.e(TAG, "old listener unregister failed", e);
            }
        }
    }

    private void onVendorDeath() {
        mKeyguardAuthGate.reset();
        int invalidatedGeneration = ++mListenerGeneration;
        Log.i(TAG, "gate reset=vendor-death state=" + mKeyguardAuthGate
                + " listener-generation=" + invalidatedGeneration + " invalidated=true");
        mController.onVendorDeath();
        rotateAuthenticationStateListener();
    }

    private void post(int generation, String edge, BiometricSourceType source, int reason,
            Consumer<FodController.Operation> event) {
        if (source != BiometricSourceType.FINGERPRINT) return;

        FodController.Operation operation;
        if (reason == REASON_ENROLL_FIND_SENSOR || reason == REASON_ENROLL_ENROLLING) {
            operation = FodController.Operation.ENROLLMENT;
        } else if (reason == REASON_AUTH_KEYGUARD) {
            operation = FodController.Operation.KEYGUARD_AUTH;
        } else if (reason == REASON_AUTH_BP || reason == REASON_AUTH_SETTINGS
                || reason == REASON_AUTH_OTHER) {
            operation = FodController.Operation.GENERIC_AUTH;
        } else {
            return;
        }

        mHandler.post(() -> {
            if (generation != mListenerGeneration) {
                Log.i(TAG, "lifecycle edge=" + edge + " generation=" + generation
                        + " dropped=true");
                return;
            }
            Log.i(TAG, "lifecycle edge=" + edge + " reason=" + reason
                    + " operation=" + operation + " generation=" + generation);
            event.accept(operation);
        });
    }

    private static final class VendorClient implements FodController.Client {
        private static final String SERVICE = IXiaomiFingerprint.DESCRIPTOR + "/default";
        private static final int EXPECTED_VERSION = 1;
        private static final String EXPECTED_HASH =
                "1ed45089cc89154986c15fc1c591d72e9ac7ae0d";

        private final Handler mHandler;
        private final Runnable mOnDeath;
        private IBinder mBinder;
        private IXiaomiFingerprint mRemote;
        private int mGeneration;

        VendorClient(Handler handler, Runnable onDeath) {
            mHandler = handler;
            mOnDeath = onDeath;
        }

        @Override
        public boolean connect() {
            IBinder binder = Binder.allowBlocking(ServiceManager.waitForDeclaredService(SERVICE));
            if (binder == null) {
                Log.e(TAG, "connection service=" + SERVICE + " result=not-declared");
                return false;
            }

            IBinder.DeathRecipient deathRecipient =
                    () -> mHandler.post(() -> binderDied(binder));
            try {
                binder.linkToDeath(deathRecipient, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "connection result=dead-before-link", e);
                mHandler.post(mOnDeath);
                return false;
            }

            mBinder = binder;
            IXiaomiFingerprint remote = IXiaomiFingerprint.Stub.asInterface(binder);
            if (remote == null) {
                reject(binder, deathRecipient);
                Log.e(TAG, "connection result=null-interface");
                return false;
            }

            try {
                int version = remote.getInterfaceVersion();
                String hash = remote.getInterfaceHash();
                int generation = mGeneration + 1;
                boolean verified = version == EXPECTED_VERSION && EXPECTED_HASH.equals(hash);
                Log.i(TAG, "connection generation=" + generation + " version=" + version
                        + " hash=" + hash + " verified=" + verified);
                if (!verified) {
                    reject(binder, deathRecipient);
                    return false;
                }
                mRemote = remote;
                mGeneration = generation;
                return true;
            } catch (RemoteException | RuntimeException e) {
                reject(binder, deathRecipient);
                Log.e(TAG, "connection result=verification-failed", e);
                return false;
            }
        }

        @Override
        public boolean isConnected() {
            return mRemote != null;
        }

        @Override
        public boolean extCmd(int command, int parameter) {
            if (mRemote == null) {
                Log.e(TAG, "extCmd generation=" + mGeneration + " command=" + command
                        + " parameter=" + parameter + " result=not-connected");
                return false;
            }
            try {
                int result = mRemote.extCmd(command, parameter);
                Log.i(TAG, "extCmd generation=" + mGeneration + " command=" + command
                        + " parameter=" + parameter + " result=" + result);
                return result == 0;
            } catch (RemoteException | RuntimeException e) {
                Log.e(TAG, "extCmd generation=" + mGeneration + " command=" + command
                        + " parameter=" + parameter + " result=transaction-failed", e);
                return false;
            }
        }

        private void binderDied(IBinder binder) {
            if (mBinder != binder) return;
            Log.w(TAG, "vendor death generation=" + mGeneration);
            mBinder = null;
            mRemote = null;
            mOnDeath.run();
        }

        private void reject(IBinder binder, IBinder.DeathRecipient deathRecipient) {
            if (mBinder == binder) {
                mBinder = null;
                mRemote = null;
            }
            try {
                binder.unlinkToDeath(deathRecipient, 0);
            } catch (NoSuchElementException ignored) {
            }
        }
    }
}
