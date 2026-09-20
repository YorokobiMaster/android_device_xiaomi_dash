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
import android.os.Message;
import android.util.Slog;

import com.android.server.display.DeviceAmbientPolicy;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/** Default internal-display policy. ABC owns main ALS evaluation; this owns the assist timer. */
public final class DashAmbientPolicy implements DeviceAmbientPolicy {
    private static final String TAG = "DashAmbientPolicy";
    private static final int ASSIST_TYPE = 33171055;
    private static final int NON_UI_TYPE = 33171027;
    private static final int ASSIST_DELAY_US = 250000;
    private static final int MAX_CAMERA_RETRIES = 1;
    private static final long CAMERA_RETRY_DELAY_MS = 2000;
    private static final int MSG_UPDATE_ASSIST = 1;
    private static Handler sCameraHandler;
    private final SensorManager mSensors;
    private final CameraManager mCameras;
    private final Handler mHandler;
    private final Handler mAssistHandler;
    private final Handler mCameraHandler;
    private final DashAmbientEstimator mEstimator = new DashAmbientEstimator();
    private final Map<String, Boolean> mTorchStates = new HashMap<>();
    private SensorEventListener mListener;
    private Sensor mAssistSensor;
    private boolean mAssistRegistered;
    private long mAssistRegisteredNanos;
    private String mAssistWaitReason;
    private CameraManager.TorchCallback mWorkerTorchCallback;
    private int mWorkerTorchGeneration;
    private int mCameraRetriesLeft;
    private Runnable mPendingCameraRetry;
    private String mNonUiState;
    private String mStepState;
    private RuntimeException mFailure;
    private DeviceAmbientPolicy.Callbacks mCallbacks;
    private DashAmbientEstimator.MainState mMainState =
            new DashAmbientEstimator.MainState(false, -1, -1, -1, -1, -1,
                    Long.MAX_VALUE, Long.MAX_VALUE);
    private boolean mTorchReady;
    private boolean mTorchUnavailable;
    private boolean mActive;
    private int mGeneration;
    private long mStartUptime;
    private long mStartElapsedNanos;
    private int mDroppedInactive;
    private int mDroppedPreSession;
    private int mDroppedMalformed;
    private int mDroppedOutOfOrder;
    private int mDroppedTorchUnknown;
    private int mDroppedStaleCache;
    private volatile DumpState mDumpState;

    private record DumpState(boolean active, int generation, long startUptime, String failure,
            String torch, DashAmbientEstimator.Snapshot estimator,
            DashAmbientEstimator.MainState main,
            boolean assistRegistered, String assistWaitReason, String nonUi, String step,
            int cameraRetriesLeft,
            int droppedInactive, int droppedPreSession, int droppedMalformed,
            int droppedOutOfOrder, int droppedTorchUnknown, int droppedStaleCache) { }

    // Construction must not register sensors or camera callbacks.
    public DashAmbientPolicy(Context context, SensorManager sensorManager, Handler handler) {
        this(context, sensorManager, handler, getCameraHandler());
    }

    DashAmbientPolicy(Context context, SensorManager sensorManager, Handler handler,
            Handler cameraHandler) {
        mSensors = sensorManager;
        mCameras = context.getSystemService(CameraManager.class);
        mHandler = handler;
        mAssistHandler = new Handler(handler.getLooper(), null, true /* async */) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == MSG_UPDATE_ASSIST) {
                    evaluateAssist(SystemClock.uptimeMillis());
                }
            }
        };
        mCameraHandler = cameraHandler;
    }

    @Override
    public void setCallbacks(DeviceAmbientPolicy.Callbacks callbacks) {
        mCallbacks = callbacks;
    }

    @Override
    public DeviceAmbientPolicy.Thresholds getMainThresholds(float lux) {
        return new DeviceAmbientPolicy.Thresholds(DashAmbientEstimator.brightThreshold(lux),
                DashAmbientEstimator.smallThreshold(lux), DashAmbientEstimator.darkThreshold(lux),
                5000);
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
        mTorchUnavailable = false;
        mAssistRegistered = false;
        mAssistRegisteredNanos = 0;
        mAssistWaitReason = null;
        mCameraRetriesLeft = MAX_CAMERA_RETRIES;
        mNonUiState = mStepState = null;
        mDroppedInactive = mDroppedPreSession = mDroppedMalformed = 0;
        mDroppedOutOfOrder = mDroppedTorchUnknown = mDroppedStaleCache = 0;
        mEstimator.start(uptimeMillis);
        mMainState = new DashAmbientEstimator.MainState(false, -1, -1, -1, -1, -1,
                Long.MAX_VALUE, Long.MAX_VALUE);
        mAssistHandler.removeMessages(MSG_UPDATE_ASSIST);
        mEstimator.traceEvent(uptimeMillis, "session start gen=" + generation);
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
                    try {
                        if (!mActive || generation != mGeneration || mFailure != null) {
                            mDroppedInactive++;
                            return;
                        }
                        long now = SystemClock.uptimeMillis();
                        long time = DashAmbientEstimator.eventUptime(event.timestamp,
                                mStartElapsedNanos, SystemClock.elapsedRealtimeNanos(), now);
                        if (time < mStartUptime) {
                            mDroppedPreSession++;
                            return;
                        }
                        if (event.values.length == 0 || !Float.isFinite(event.values[0])) {
                            mDroppedMalformed++;
                            return;
                        }
                        try {
                            switch (event.sensor.getType()) {
                                case ASSIST_TYPE:
                                    if (event.timestamp < mAssistRegisteredNanos) {
                                        // sensorservice can replay the last on-change value with
                                        // its original timestamp; it cannot be proven to belong
                                        // to this subscription.
                                        mDroppedStaleCache++;
                                        mAssistWaitReason =
                                                "stale cached sample dropped; waiting for fresh"
                                                + " assist sample";
                                        return;
                                    }
                                    if (event.timestamp < mLastAssistNanos) {
                                        mDroppedOutOfOrder++;
                                        return;
                                    }
                                    mLastAssistNanos = event.timestamp;
                                    // Camera callbacks are asynchronous: wait for torch state.
                                    if (!mTorchReady || mTorchStates.containsValue(null)) {
                                        mDroppedTorchUnknown++;
                                        return;
                                    }
                                    mEstimator.assist(time, event.values[0]);
                                    mAssistWaitReason = null;
                                    evaluateAssist(time);
                                    break;
                                case NON_UI_TYPE:
                                    if (event.timestamp < mLastNonUiNanos) {
                                        mDroppedOutOfOrder++;
                                        return;
                                    }
                                    mLastNonUiNanos = event.timestamp;
                                    mEstimator.nonUi(time, event.values[0]);
                                    break;
                                case Sensor.TYPE_STEP_DETECTOR:
                                    if (event.timestamp < mLastStepNanos) {
                                        mDroppedOutOfOrder++;
                                        return;
                                    }
                                    mLastStepNanos = event.timestamp;
                                    mEstimator.step(time);
                                    break;
                                default:
                                    return;
                            }
                        } catch (RuntimeException failure) {
                            mFailure = failure;
                        }
                        if (mFailure != null) reportFailure(mFailure);
                    } finally {
                        publishDumpState();
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            };
            // The on-change assist sensor subscribes only once the initial torch state is
            // known and any torch cooldown has completed; its sole initial sample must not
            // be dropped into a closing gate.
            mAssistSensor = mSensors.getDefaultSensor(ASSIST_TYPE);
            if (mAssistSensor == null) {
                throw new IllegalStateException("Dash assist ambient sensor unavailable");
            }
            maybeRegisterAssist(uptimeMillis);
            Sensor nonUi = mSensors.getDefaultSensor(NON_UI_TYPE);
            if (nonUi == null) nonUi = mSensors.getDefaultSensor(NON_UI_TYPE, true);
            mNonUiState = registerOptional(nonUi, "nonUi");
            mStepState = registerOptional(mSensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR),
                    "step");
            publishDumpState();
        } catch (RuntimeException failure) {
            try {
                stop();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private String registerOptional(Sensor sensor, String name) {
        // Stock skips absent optional NonUi/step sensors; a failed registration disables only
        // that enhancement, never the dual-sensor policy.
        if (sensor == null) return "ABSENT";
        try {
            if (mSensors.registerListener(mListener, sensor, SensorManager.SENSOR_DELAY_NORMAL,
                    mHandler)) {
                return "OK";
            }
            Slog.e(TAG, "Optional dash ambient sensor registration rejected: " + name);
        } catch (RuntimeException failure) {
            Slog.e(TAG, "Optional dash ambient sensor registration failed: " + name, failure);
        }
        return "FAILED";
    }

    private void maybeRegisterAssist(long now) {
        if (!mActive || mFailure != null || mAssistRegistered || mAssistSensor == null) return;
        if (!mTorchReady || mTorchStates.containsValue(null)) {
            mAssistWaitReason = mTorchUnavailable ? "camera unavailable" : "torch state unknown";
            return;
        }
        if (mTorchStates.containsValue(true)) {
            mAssistWaitReason = "torch on";
            return;
        }
        if (!mEstimator.assistAllowed(now)) {
            mAssistWaitReason = "torch cooldown until " + mEstimator.cooldownUntil();
            return;
        }
        long registeredNanos = SystemClock.elapsedRealtimeNanos();
        boolean registered;
        try {
            registered = mSensors.registerListener(mListener, mAssistSensor, ASSIST_DELAY_US,
                    mHandler);
        } catch (RuntimeException failure) {
            registered = false;
        }
        if (!registered) {
            // The assist channel is a required input; keep the native-fallback contract.
            mFailure = new IllegalStateException(
                    "Cannot register dash ambient sensor " + mAssistSensor);
            reportFailure(mFailure);
            return;
        }
        mAssistRegistered = true;
        mAssistRegisteredNanos = registeredNanos;
        mAssistWaitReason = "waiting for first assist sample";
        mEstimator.traceEvent(SystemClock.uptimeMillis(), "assist registered");
    }

    private void unregisterAssist(String reason) {
        if (!mAssistRegistered) return;
        try {
            mSensors.unregisterListener(mListener, mAssistSensor);
        } catch (RuntimeException failure) {
            Slog.w(TAG, "Cannot unregister dash assist sensor", failure);
        }
        mAssistRegistered = false;
        mAssistWaitReason = reason;
        mEstimator.traceEvent(SystemClock.uptimeMillis(), "assist unregistered: " + reason);
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
        mTorchUnavailable = false;
        maybeRegisterAssist(SystemClock.uptimeMillis());
        publishDumpState();
    }

    private void failTorchRegistration(int generation, Exception failure) {
        if (!mActive || generation != mGeneration) return;
        // Camera errors are recoverable: the assist channel goes unavailable while the main
        // channel keeps working. One bounded retry per session, never per ALS event.
        mTorchUnavailable = true;
        mAssistWaitReason = "camera unavailable: " + failure;
        if (mCameraRetriesLeft-- > 0) {
            Slog.w(TAG, "Dash torch monitoring failed; one retry follows", failure);
            mPendingCameraRetry = () -> retryTorch(generation);
            mCameraHandler.postDelayed(mPendingCameraRetry, CAMERA_RETRY_DELAY_MS);
        } else {
            Slog.e(TAG, "Dash torch monitoring unavailable for this session", failure);
        }
        publishDumpState();
    }

    private void retryTorch(int generation) {
        mHandler.post(() -> {
            mPendingCameraRetry = null;
            if (!mActive || generation != mGeneration || !mTorchUnavailable) return;
            if (!mCameraHandler.post(() -> registerTorch(generation))) {
                failTorchRegistration(generation,
                        new IllegalStateException("Dash camera worker unavailable"));
            }
            publishDumpState();
        });
    }

    private void updateTorch(int generation, String id, boolean enabled) {
        if (!mActive || generation != mGeneration || !mTorchReady
                || !mTorchStates.containsKey(id)) return;
        mTorchStates.put(id, enabled);
        boolean torchOn = mTorchStates.containsValue(true);
        if (torchOn) unregisterAssist("torch on");
        long now = SystemClock.uptimeMillis();
        mEstimator.torch(now, torchOn);
        if (!torchOn) maybeRegisterAssist(now);
        mAssistHandler.removeMessages(MSG_UPDATE_ASSIST);
        long cooldown = mEstimator.cooldownUntil();
        if (cooldown > now) mAssistHandler.sendEmptyMessageAtTime(MSG_UPDATE_ASSIST, cooldown);
        publishDumpState();
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
        mTorchUnavailable = false;
        mTorchStates.clear();
        mAssistRegistered = false;
        mAssistWaitReason = null;
        if (mPendingCameraRetry != null) {
            mCameraHandler.removeCallbacks(mPendingCameraRetry);
            mPendingCameraRetry = null;
        }
        mAssistHandler.removeMessages(MSG_UPDATE_ASSIST);
        try {
            if (listener != null) mSensors.unregisterListener(listener);
        } finally {
            mCameraHandler.post(() -> stopTorch(generation));
            publishDumpState();
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
    public Evaluation onMainAmbientLux(long uptimeMillis, float mainSlowLux, float mainFastLux,
            float mainBrighteningThreshold, float mainSmallBrighteningThreshold,
            float mainDarkeningThreshold, long nextBrighteningTransition,
            long nextDarkeningTransition, boolean initial) {
        checkThread();
        throwIfFailed();
        mMainState = new DashAmbientEstimator.MainState(true, mainFastLux, mainSlowLux,
                mainBrighteningThreshold, mainSmallBrighteningThreshold, mainDarkeningThreshold,
                nextBrighteningTransition, nextDarkeningTransition);
        DashAmbientEstimator.Decision decision = mEstimator.onMain(uptimeMillis, mMainState,
                initial);
        mEstimator.traceDecision(uptimeMillis, decision, mMainState);
        publishDumpState();
        return new Evaluation(decision.event(), decision.lux(), decision.updateLux(),
                decision.updateBrightness(), decision.thresholdLux(), decision.reason());
    }

    private void evaluateAssist(long uptimeMillis) {
        if (!mActive || mFailure != null || mCallbacks == null) return;
        mAssistHandler.removeMessages(MSG_UPDATE_ASSIST);
        try {
            // The torch cooldown timer must also restore the on-change subscription.
            maybeRegisterAssist(uptimeMillis);
            if (mFailure != null || !mActive) return;
            DashAmbientEstimator.Decision decision = mEstimator.onAssist(uptimeMillis, mMainState);
            mEstimator.traceDecision(uptimeMillis, decision, mMainState);
            if (decision.event() != DashAmbientEstimator.INVALID) {
                mCallbacks.applyAmbientLux(decision.event(), decision.lux(), decision.updateLux(),
                        decision.updateBrightness(), decision.thresholdLux());
            }
            long next = mEstimator.assistNext();
            if (next != Long.MAX_VALUE) {
                mAssistHandler.sendEmptyMessageAtTime(MSG_UPDATE_ASSIST,
                        next > uptimeMillis ? next : uptimeMillis + 250);
            }
            publishDumpState();
        } catch (RuntimeException failure) {
            mFailure = failure;
            reportFailure(failure);
        }
    }

    private void reportFailure(RuntimeException failure) {
        if (mCallbacks != null) mCallbacks.reportFailure(failure);
    }

    private void publishDumpState() {
        mDumpState = new DumpState(mActive, mGeneration, mStartUptime,
                mFailure == null ? null : mFailure.toString(), torchState(), mEstimator.snapshot(),
                mMainState,
                mAssistRegistered, mAssistWaitReason, mNonUiState, mStepState, mCameraRetriesLeft,
                mDroppedInactive, mDroppedPreSession, mDroppedMalformed,
                mDroppedOutOfOrder, mDroppedTorchUnknown, mDroppedStaleCache);
    }

    private String torchState() {
        if (mTorchUnavailable) return "UNAVAILABLE";
        if (!mTorchReady || mTorchStates.containsValue(null)) return "UNKNOWN";
        return mTorchStates.containsValue(true) ? "ON" : "OFF";
    }

    /** Runs on the dumpsys thread; reads only the immutable snapshot from the ABC handler. */
    @Override
    public void dump(PrintWriter writer) {
        DumpState state = mDumpState;
        if (state == null) {
            writer.println("DashAmbientPolicy: no session");
            return;
        }
        DashAmbientEstimator.Snapshot s = state.estimator();
        DashAmbientEstimator.MainState main = state.main();
        writer.println("DashAmbientPolicy: active=" + state.active()
                + " generation=" + state.generation() + " startUptime=" + state.startUptime());
        writer.println("  failure=" + state.failure());
        writer.println("  torch=" + state.torch() + " torchClosed=" + s.torchClosed()
                + " cooldownUntil=" + s.cooldownUntil());
        writer.println("  assistRegistered=" + state.assistRegistered() + " assistWait="
                + state.assistWaitReason() + " nonUi=" + state.nonUi() + " step=" + state.step()
                + " cameraRetriesLeft=" + state.cameraRetriesLeft());
        writer.println("  selected=" + selectedName(s.selected()) + " lastSwitch="
                + s.lastSwitchReason() + "@" + s.lastSwitchTime());
        writer.println("  main: owned-by-ABC fast=" + main.fast() + " slow="
                + main.slow() + " bright=" + main.bright() + " small="
                + main.small() + " dark=" + main.dark() + " nextBright="
                + main.nextBright() + " nextDark=" + main.nextDark());
        writer.println("  assist: valid=" + s.assistValid() + " fast=" + s.assistFast()
                + " slow=" + s.assistSlow() + " bright=" + s.assistBright() + " small="
                + s.assistSmall() + " dark=" + s.assistDark() + " next=" + s.assistNext()
                + " lastEvent=" + s.assistLastEvent());
        writer.println("  dropped: inactive=" + state.droppedInactive() + " preSession="
                + state.droppedPreSession() + " malformed=" + state.droppedMalformed()
                + " outOfOrder=" + state.droppedOutOfOrder() + " torchUnknown="
                + state.droppedTorchUnknown() + " assistRejected="
                + s.droppedAssistRejected() + " assistTorch="
                + s.droppedAssistTorch() + " assistCooldown=" + s.droppedAssistCooldown()
                + " staleCache=" + state.droppedStaleCache());
        writer.println("  trace (" + s.trace().length + " events, uptime ms):");
        for (String event : s.trace()) writer.println("    " + event);
    }

    private static String selectedName(int selected) {
        switch (selected) {
            case DashAmbientEstimator.MAIN: return "MAIN";
            case DashAmbientEstimator.ASSIST: return "ASSIST";
            default: return "NONE";
        }
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
