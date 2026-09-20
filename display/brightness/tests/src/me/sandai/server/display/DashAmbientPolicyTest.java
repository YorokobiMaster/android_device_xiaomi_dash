/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

import com.android.server.display.DeviceAmbientPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Registration and stale-callback checks on a real handler, with mocked hardware services. */
public class DashAmbientPolicyTest {
    private final SensorManager mSensors = mock(SensorManager.class);
    private final CameraManager mCameras = mock(CameraManager.class);
    private final Sensor mAssist = mock(Sensor.class);
    private final AtomicInteger mFailures = new AtomicInteger();
    private final AtomicReference<DeviceAmbientPolicy.Evaluation> mApplied =
            new AtomicReference<>();
    private HandlerThread mThread;
    private HandlerThread mCameraThread;
    private Handler mHandler;
    private Handler mCameraHandler;
    private DashAmbientPolicy mPolicy;

    @Before
    public void setUp() throws Exception {
        mThread = new HandlerThread("DashAmbientPolicyTest");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mCameraThread = new HandlerThread("DashAmbientPolicyCameraTest");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());
        Context context = mock(Context.class);
        when(context.getSystemService(CameraManager.class)).thenReturn(mCameras);
        when(mCameras.getCameraIdList()).thenReturn(new String[0]);
        when(mAssist.getType()).thenReturn(33171055);
        when(mSensors.getDefaultSensor(33171055)).thenReturn(mAssist);
        when(mSensors.registerListener(any(SensorEventListener.class), any(Sensor.class),
                anyInt(), eq(mHandler))).thenReturn(true);
        mPolicy = new DashAmbientPolicy(context, mSensors, mHandler, mCameraHandler);
        mPolicy.setCallbacks(new DeviceAmbientPolicy.Callbacks() {
            @Override
            public void applyAmbientLux(int event, float lux, boolean needUpdateLux,
                    boolean needUpdateBrightness, float mainThresholdLux) {
                mApplied.set(new DeviceAmbientPolicy.Evaluation(event, lux, needUpdateLux,
                        needUpdateBrightness, mainThresholdLux, null));
            }

            @Override
            public void reportFailure(RuntimeException failure) { mFailures.incrementAndGet(); }

            @Override
            public float getAmbientLux() { return Float.NaN; }
        });
    }

    @After
    public void tearDown() throws Exception {
        try {
            run(() -> mPolicy.stop());
            settleCamera();
        } finally {
            mCameraThread.quitSafely();
            mCameraThread.join(5000);
            mThread.quitSafely();
            mThread.join(5000);
        }
    }

    /** Blocks the camera-side torch registration until the returned latch is released. */
    private CountDownLatch holdTorchRegistration() {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertTrue("torch registration hold", latch.await(5, TimeUnit.SECONDS));
            return null;
        }).when(mCameras).registerTorchCallback(any(CameraManager.TorchCallback.class),
                any(Handler.class));
        return latch;
    }

    private void settleCamera() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> null);
        mCameraHandler.post(task);
        task.get(5, TimeUnit.SECONDS);
        run(() -> {});
    }

    private void run(Runnable body) throws Exception {
        FutureTask<Void> task = new FutureTask<>(body, null);
        mHandler.post(task);
        task.get(5, TimeUnit.SECONDS);
    }

    private SensorEventListener listener() {
        ArgumentCaptor<SensorEventListener> captor = ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensors, atLeastOnce()).registerListener(captor.capture(), eq(mAssist),
                eq(250000), eq(mHandler));
        return captor.getValue();
    }

    private DeviceAmbientPolicy.Evaluation main(long now, float lux, boolean initial) {
        return mPolicy.onMainAmbientLux(now, lux, lux, 160, 108, 40,
                now, now, initial);
    }

    @Test
    public void constructionDoesNotSubscribeAndStopCancelsBothOwners() throws Exception {
        verifyNoInteractions(mSensors, mCameras);
        long now = SystemClock.uptimeMillis();
        run(() -> mPolicy.start(now));
        settleCamera();
        AtomicReference<SensorEventListener> sensorListener = new AtomicReference<>();
        AtomicReference<CameraManager.TorchCallback> torch = new AtomicReference<>();
        run(() -> {
            sensorListener.set(listener());
            ArgumentCaptor<CameraManager.TorchCallback> captor =
                    ArgumentCaptor.forClass(CameraManager.TorchCallback.class);
            verify(mCameras).registerTorchCallback(captor.capture(), eq(mCameraHandler));
            torch.set(captor.getValue());
            mPolicy.stop();
        });
        settleCamera();
        run(() -> {
            verify(mSensors).unregisterListener(sensorListener.get());
            verify(mCameras).unregisterTorchCallback(torch.get());
        });
    }

    @Test
    public void priorGenerationAndPreStartEventsCannotReachEstimator() throws Exception {
        long beforeStart = SystemClock.elapsedRealtimeNanos() - 1;
        run(() -> mPolicy.start(SystemClock.uptimeMillis()));
        settleCamera();
        AtomicReference<SensorEventListener> previous = new AtomicReference<>();
        run(() -> {
            previous.set(listener());
            previous.get().onSensorChanged(
                    new SensorEvent(mAssist, 0, beforeStart, new float[]{999}));
            assertEquals(0, mFailures.get());
            mPolicy.stop();
            mPolicy.start(SystemClock.uptimeMillis());
        });
        settleCamera();
        run(() -> {
            SensorEventListener current = listener();
            long now = SystemClock.uptimeMillis();
            main(now, 100, true);
            previous.get().onSensorChanged(new SensorEvent(mAssist, 0,
                    SystemClock.elapsedRealtimeNanos(), new float[]{999}));
            assertEquals(0, mFailures.get());
            assertEquals(null, mApplied.get());
            current.onSensorChanged(new SensorEvent(mAssist, 0,
                    SystemClock.elapsedRealtimeNanos(), new float[]{300}));
            assertEquals(300, mApplied.get().lux, .001f);
        });
    }

    /**
     * The required assist sensor failing its deferred registration must still trigger the
     * native fallback through the ABC failure callback, with camera and sensor cleanup.
     */
    @Test
    public void failedAssistRegistrationReportsNativeFallback()
            throws Exception {
        when(mSensors.registerListener(any(SensorEventListener.class), eq(mAssist),
                anyInt(), eq(mHandler))).thenReturn(false);
        run(() -> mPolicy.start(SystemClock.uptimeMillis()));
        settleCamera(); // Torch ready with zero cameras: deferred registration runs and fails.
        run(() -> {
            assertEquals(1, mFailures.get());
            mPolicy.stop();
        });
        settleCamera();
        verify(mSensors).unregisterListener(any(SensorEventListener.class));
        verify(mCameras).unregisterTorchCallback(any(CameraManager.TorchCallback.class));
    }

    @Test
    public void cameraCleanupStillRunsIfSensorUnregisterFails() throws Exception {
        run(() -> {
            mPolicy.start(SystemClock.uptimeMillis());
        });
        settleCamera();
        run(() -> {
            doThrow(new IllegalStateException("unregister failed"))
                    .when(mSensors).unregisterListener(any(SensorEventListener.class));
            try {
                mPolicy.stop();
                fail("Unregister failure must propagate");
            } catch (IllegalStateException expected) {
            }
        });
        settleCamera();
        verify(mCameras).unregisterTorchCallback(any(CameraManager.TorchCallback.class));
    }

    @Test
    public void cameraDiscoveryNeverRunsOnBrightnessHandler() throws Exception {
        when(mCameras.getCameraIdList()).thenAnswer(invocation -> {
            assertEquals(mCameraHandler.getLooper(), Looper.myLooper());
            return new String[0];
        });
        run(() -> mPolicy.start(SystemClock.uptimeMillis()));
        settleCamera();
        verify(mCameras).getCameraIdList();
    }

    /**
     * Fix-plan problem 1: the on-change assist sensor subscribes only after the initial
     * camera state is known, so its sole initial sample can no longer be dropped into a
     * closing gate.
     */
    @Test
    public void assistSubscriptionWaitsForKnownTorchState() throws Exception {
        // Hold the camera-side registration so the negative check is deterministic.
        CountDownLatch torchRegistration = holdTorchRegistration();
        run(() -> mPolicy.start(SystemClock.uptimeMillis()));
        run(() -> verify(mSensors, never()).registerListener(any(SensorEventListener.class),
                eq(mAssist), anyInt(), any(Handler.class)));
        torchRegistration.countDown();
        settleCamera();
        run(() -> verify(mSensors).registerListener(any(SensorEventListener.class), eq(mAssist),
                eq(250000), eq(mHandler)));
    }

    /**
     * Fix-plan problem 2: one camera discovery error marks the assist channel unavailable
     * (one bounded retry per session) while the main channel keeps working; the policy is
     * no longer permanently failed.
     */
    @Test
    public void cameraDiscoveryFailureKeepsMainChannelAlive() throws Exception {
        when(mCameras.getCameraIdList()).thenThrow(
                new CameraAccessException(CameraAccessException.CAMERA_ERROR));
        run(() -> mPolicy.start(SystemClock.uptimeMillis()));
        settleCamera();
        run(() -> {
            long now = SystemClock.uptimeMillis();
            assertEquals(100, main(now, 100, true).lux, .001f);
            StringWriter out = new StringWriter();
            mPolicy.dump(new PrintWriter(out));
            assertTrue(out.toString().contains("torch=UNAVAILABLE"));
        });
    }

    /**
     * Fix-plan problem 2: an optional NonUi/step sensor that exists but rejects registration
     * only disables that enhancement, never the dual-sensor policy.
     */
    @Test
    public void optionalSensorRegistrationFailureDisablesOnlyThatInput() throws Exception {
        Sensor nonUi = mock(Sensor.class);
        when(nonUi.getType()).thenReturn(33171027);
        when(mSensors.getDefaultSensor(33171027)).thenReturn(nonUi);
        when(mSensors.registerListener(any(SensorEventListener.class), eq(nonUi), anyInt(),
                eq(mHandler))).thenReturn(false);
        run(() -> mPolicy.start(SystemClock.uptimeMillis()));
        settleCamera();
        run(() -> {
            long now = SystemClock.uptimeMillis();
            assertEquals(100, main(now, 100, true).lux, .001f);
            listener().onSensorChanged(new SensorEvent(mAssist, 0,
                    SystemClock.elapsedRealtimeNanos(), new float[]{300}));
            assertEquals(300, mApplied.get().lux, .001f);
            StringWriter out = new StringWriter();
            mPolicy.dump(new PrintWriter(out));
            assertTrue(out.toString().contains("nonUi=FAILED"));
        });
    }

    /**
     * sensorservice can replay the last on-change value with its original timestamp on
     * (re-)subscription; it is not a fresh sample and must be dropped and counted.
     */
    @Test
    public void staleCachedAssistEventIsDroppedAndCounted() throws Exception {
        AtomicLong cachedTimestamp = new AtomicLong();
        run(() -> {
            mPolicy.start(SystemClock.uptimeMillis());
            cachedTimestamp.set(SystemClock.elapsedRealtimeNanos());
        });
        settleCamera(); // Deferred assist registration happens here.
        run(() -> {
            long now = SystemClock.uptimeMillis();
            assertEquals(100, main(now, 100, true).lux, .001f);
            listener().onSensorChanged(new SensorEvent(mAssist, 0, cachedTimestamp.get(),
                    new float[]{300}));
            assertEquals(null, mApplied.get());
            StringWriter out = new StringWriter();
            mPolicy.dump(new PrintWriter(out));
            assertTrue(out.toString().contains("staleCache=1"));
            listener().onSensorChanged(new SensorEvent(mAssist, 0,
                    SystemClock.elapsedRealtimeNanos(), new float[]{300}));
            assertEquals(300, mApplied.get().lux, .001f);
        });
    }

    /** Stale torch callbacks must not cross a stop or a restart boundary. */
    @Test
    public void torchCallbackFromStoppedSessionIsIgnored() throws Exception {
        CameraCharacteristics info = mock(CameraCharacteristics.class);
        when(info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)).thenReturn(Boolean.TRUE);
        when(mCameras.getCameraIdList()).thenReturn(new String[]{"0"});
        when(mCameras.getCameraCharacteristics("0")).thenReturn(info);
        run(() -> mPolicy.start(SystemClock.uptimeMillis()));
        settleCamera();
        AtomicReference<CameraManager.TorchCallback> stale = new AtomicReference<>();
        run(() -> {
            ArgumentCaptor<CameraManager.TorchCallback> captor =
                    ArgumentCaptor.forClass(CameraManager.TorchCallback.class);
            verify(mCameras).registerTorchCallback(captor.capture(), eq(mCameraHandler));
            stale.set(captor.getValue());
            stale.get().onTorchModeChanged("0", false); // Initial state: torch OFF.
        });
        run(() -> assertEquals(0, mFailures.get()));
        run(() -> mPolicy.stop());
        settleCamera();
        final int failures = mFailures.get();
        stale.get().onTorchModeChanged("0", true);
        run(() -> assertEquals(failures, mFailures.get()));
        run(() -> mPolicy.start(SystemClock.uptimeMillis()));
        settleCamera();
        stale.get().onTorchModeChanged("0", true); // Stale generation across a restart.
        run(() -> assertEquals(failures, mFailures.get()));
    }

    /** The dump must explain the torch state and the deferred assist subscription. */
    @Test
    public void dumpReportsTorchStatesAndDeferredAssistSubscription() throws Exception {
        // Hold the camera-side registration so the pre-torch dump is deterministic.
        CountDownLatch torchRegistration = holdTorchRegistration();
        run(() -> mPolicy.start(SystemClock.uptimeMillis()));
        run(() -> {
            StringWriter out = new StringWriter();
            mPolicy.dump(new PrintWriter(out));
            String dump = out.toString();
            assertTrue(dump, dump.contains("torch=UNKNOWN"));
            assertTrue(dump, dump.contains("assistRegistered=false"));
            assertTrue(dump, dump.contains("assistWait=torch state unknown"));
        });
        torchRegistration.countDown();
        settleCamera();
        run(() -> {
            StringWriter out = new StringWriter();
            mPolicy.dump(new PrintWriter(out));
            String dump = out.toString();
            assertTrue(dump, dump.contains("torch=OFF"));
            assertTrue(dump, dump.contains("assistRegistered=true"));
        });
    }
}
