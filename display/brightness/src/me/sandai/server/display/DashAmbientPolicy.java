/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Slog;

import com.android.server.display.DeviceAmbientPolicy;

import java.util.HashMap;
import java.util.Map;

/** Default internal-display policy. ABC owns the primary ALS and the only evaluation timer. */
public final class DashAmbientPolicy implements DeviceAmbientPolicy {
    private static final String TAG = "DashAmbientPolicy";
    private static final int ASSIST_TYPE = 33171055;
    private static final int NON_UI_TYPE = 33171027;
    private static Handler sCameraHandler;
    private final SensorManager mSensors;
    private final CameraManager mCameras;
    private final Handler mHandler;
    private final Handler mCameraHandler;
    private final Runnable mRequestEvaluation;
    private final DashAmbientEstimator mEstimator = new DashAmbientEstimator();
    private final Map<String, Boolean> mTorchStates = new HashMap<>();
    private SensorEventListener mListener;
    private CameraManager.TorchCallback mWorkerTorchCallback;
    private int mWorkerTorchGeneration;
    private RuntimeException mFailure;
    private boolean mTorchReady;
    private boolean mActive;
    private int mGeneration;
    private long mStartUptime;
    private long mStartElapsedNanos;

    // Construction must not register sensors or camera callbacks.
    public DashAmbientPolicy(Context context, SensorManager sensorManager, Handler handler,
            Runnable requestEvaluation) {
        this(context, sensorManager, handler, requestEvaluation, getCameraHandler());
    }

    DashAmbientPolicy(Context context, SensorManager sensorManager, Handler handler,
            Runnable requestEvaluation, Handler cameraHandler) {
        mSensors = sensorManager;
        mCameras = context.getSystemService(CameraManager.class);
        mHandler = handler;
        mCameraHandler = cameraHandler;
        mRequestEvaluation = requestEvaluation;
    }

    private static synchronized Handler getCameraHandler() {
        if (sCameraHandler == null) {
            HandlerThread thread = new HandlerThread(TAG + "Camera");
            thread.start();
            sCameraHandler = new Handler(thread.getLooper());
        }
        return sCameraHandler;
    }

    @Override
    public void start(long uptimeMillis) {
        checkThread();
        if (mActive) return;
        mActive = true;
        final int generation = ++mGeneration;
        mStartUptime = uptimeMillis;
        mStartElapsedNanos = SystemClock.elapsedRealtimeNanos();
        mFailure = null;
        mTorchReady = false;
        mEstimator.start(uptimeMillis);
        try {
            if (!mCameraHandler.post(() -> registerTorch(generation))) {
                throw new IllegalStateException("Dash camera worker unavailable");
            }
            mListener = new SensorEventListener() {
                private long mLastAssistNanos = -1;
                private long mLastNonUiNanos = -1;
                private long mLastStepNanos = -1;

                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (!mActive || generation != mGeneration || mFailure != null) return;
                    long now = SystemClock.uptimeMillis();
                    long time = DashAmbientEstimator.eventUptime(event.timestamp, mStartElapsedNanos,
                            SystemClock.elapsedRealtimeNanos(), now);
                    if (time < mStartUptime || event.values.length == 0
                            || !Float.isFinite(event.values[0])) return;
                    try {
                        switch (event.sensor.getType()) {
                            case ASSIST_TYPE:
                                if (event.timestamp < mLastAssistNanos) return;
                                mLastAssistNanos = event.timestamp;
                                // Camera callbacks are asynchronous: wait for initial torch state.
                                if (!mTorchReady || mTorchStates.containsValue(null)) return;
                                mEstimator.assist(time, event.values[0]);
                                break;
                            case NON_UI_TYPE:
                                if (event.timestamp < mLastNonUiNanos) return;
                                mLastNonUiNanos = event.timestamp;
                                mEstimator.nonUi(time, event.values[0]);
                                break;
                            case Sensor.TYPE_STEP_DETECTOR:
                                if (event.timestamp < mLastStepNanos) return;
                                mLastStepNanos = event.timestamp;
                                mEstimator.step(time);
                                break;
                            default:
                                return;
                        }
                    } catch (RuntimeException failure) {
                        mFailure = failure;
                    }
                    // Failures cross ABC's guarded evaluate() call, not a sensor callback stack.
                    mRequestEvaluation.run();
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            };
            register(mSensors.getDefaultSensor(ASSIST_TYPE), 250000, true);
            Sensor nonUi = mSensors.getDefaultSensor(NON_UI_TYPE);
            if (nonUi == null) nonUi = mSensors.getDefaultSensor(NON_UI_TYPE, true);
            register(nonUi, SensorManager.SENSOR_DELAY_NORMAL, false);
            register(mSensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR),
                    SensorManager.SENSOR_DELAY_NORMAL, false);
        } catch (RuntimeException failure) {
            try {
                stop();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void register(Sensor sensor, int periodUs, boolean required) {
        // Stock skips absent optional NonUi/step sensors; a failed registration is not success.
        if (sensor == null && !required) return;
        if (sensor == null || !mSensors.registerListener(mListener, sensor, periodUs, mHandler)) {
            throw new IllegalStateException("Cannot register dash ambient sensor " + sensor);
        }
    }

    private void registerTorch(int generation) {
        CameraManager.TorchCallback callback = null;
        try {
            if (mCameras == null) throw new IllegalStateException("CameraManager unavailable");
            Map<String, Boolean> states = new HashMap<>();
            for (String id : mCameras.getCameraIdList()) {
                CameraCharacteristics info = mCameras.getCameraCharacteristics(id);
                if (Boolean.TRUE.equals(info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
                        && !isVirtualCamera(info)) {
                    states.put(id, null);
                }
            }
            callback = new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeUnavailable(String cameraId) {
                    changed(cameraId, true);
                }

                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    changed(cameraId, enabled);
                }

                private void changed(String id, boolean enabled) {
                    mHandler.post(() -> updateTorch(generation, id, enabled));
                }
            };
            mCameras.registerTorchCallback(callback, mCameraHandler);
            mWorkerTorchCallback = callback;
            mWorkerTorchGeneration = generation;
            mHandler.post(() -> finishTorchRegistration(generation, states));
        } catch (CameraAccessException | RuntimeException failure) {
            if (callback != null) unregisterTorch(callback);
            mHandler.post(() -> failTorchRegistration(generation, failure));
        }
    }

    private void finishTorchRegistration(int generation, Map<String, Boolean> states) {
        if (!mActive || generation != mGeneration) return;
        mTorchStates.clear();
        mTorchStates.putAll(states);
        mTorchReady = true;
    }

    private void failTorchRegistration(int generation, Exception failure) {
        if (!mActive || generation != mGeneration) return;
        mFailure = new IllegalStateException("Cannot monitor dash torch cameras", failure);
        mRequestEvaluation.run();
    }

    private void updateTorch(int generation, String id, boolean enabled) {
        if (!mActive || generation != mGeneration || !mTorchReady
                || !mTorchStates.containsKey(id)) return;
        mTorchStates.put(id, enabled);
        mEstimator.torch(SystemClock.uptimeMillis(), mTorchStates.containsValue(true));
        mRequestEvaluation.run();
    }

    private static boolean isVirtualCamera(CameraCharacteristics info) {
        // Stock ignores roles 100/101/102. Its excluded-facing-camera resource is empty on dash.
        for (String name : new String[]{"com.xiaomi.cameraid.role.cameraIds",
                "com.xiaomi.cameraid.role.cameraId"}) {
            Integer[] roles;
            try {
                roles = info.get(new CameraCharacteristics.Key<>(name, Integer[].class));
            } catch (IllegalArgumentException unavailable) {
                continue;
            }
            if (roles == null) continue;
            for (Integer role : roles) {
                if (role != null && (role == 100 || role == 101 || role == 102)) return true;
            }
            return false;
        }
        return false;
    }

    @Override
    public void stop() {
        checkThread();
        mActive = false;
        final int generation = ++mGeneration;
        SensorEventListener listener = mListener;
        mListener = null;
        mFailure = null;
        mTorchReady = false;
        mTorchStates.clear();
        try {
            if (listener != null) mSensors.unregisterListener(listener);
        } finally {
            mCameraHandler.post(() -> stopTorch(generation));
        }
    }

    private void stopTorch(int generation) {
        if (mWorkerTorchCallback == null || mWorkerTorchGeneration > generation) return;
        CameraManager.TorchCallback callback = mWorkerTorchCallback;
        mWorkerTorchCallback = null;
        mWorkerTorchGeneration = 0;
        unregisterTorch(callback);
    }

    private void unregisterTorch(CameraManager.TorchCallback callback) {
        try {
            mCameras.unregisterTorchCallback(callback);
        } catch (RuntimeException failure) {
            Slog.w(TAG, "Cannot unregister dash torch callback", failure);
        }
    }

    @Override
    public void onPrimarySample(long uptimeMillis, float lux) {
        checkThread();
        throwIfFailed();
        if (mActive && uptimeMillis >= mStartUptime) mEstimator.primary(uptimeMillis, lux);
    }

    @Override
    public Evaluation evaluate(long uptimeMillis, float currentLux) {
        checkThread();
        throwIfFailed();
        if (!mActive) return new Evaluation(Float.NaN, Long.MAX_VALUE);
        float lux = mEstimator.evaluate(uptimeMillis);
        return new Evaluation(lux, mEstimator.nextEvaluation());
    }

    @Override
    public boolean useFastRamp(float currentBrightness, float targetBrightness,
            float currentNits, float targetNits, boolean hdr) {
        checkThread();
        throwIfFailed();
        if (!mActive || hdr) return false;
        return mEstimator.fastRamp(SystemClock.uptimeMillis(), currentBrightness,
                targetBrightness, currentNits, targetNits);
    }

    private void checkThread() {
        if (Looper.myLooper() != mHandler.getLooper()) {
            throw new IllegalStateException("Dash ambient policy must run on ABC handler");
        }
    }

    private void throwIfFailed() {
        if (mFailure != null) throw mFailure;
    }
}
