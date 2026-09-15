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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Registration and stale-callback checks on a real handler, with mocked hardware services. */
public class DashAmbientPolicyTest {
    private final SensorManager mSensors = mock(SensorManager.class);
    private final CameraManager mCameras = mock(CameraManager.class);
    private final Sensor mAssist = mock(Sensor.class);
    private final AtomicInteger mRequests = new AtomicInteger();
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
        mPolicy = new DashAmbientPolicy(context, mSensors, mHandler, mRequests::incrementAndGet,
                mCameraHandler);
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
            assertTrue(Float.isNaN(mPolicy.evaluate(now, 100).lux));
            assertEquals(Long.MAX_VALUE, mPolicy.evaluate(now, 100).nextEvaluationUptimeMillis);
            assertFalse(mPolicy.useFastRamp(.1f, .2f, 100, 160, false));
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
            assertEquals(0, mRequests.get());
            mPolicy.stop();
            mPolicy.start(SystemClock.uptimeMillis());
        });
        settleCamera();
        run(() -> {
            SensorEventListener current = listener();
            long now = SystemClock.uptimeMillis();
            mPolicy.onPrimarySample(now, 100);
            mPolicy.evaluate(now, Float.NaN);
            previous.get().onSensorChanged(new SensorEvent(mAssist, 0,
                    SystemClock.elapsedRealtimeNanos(), new float[]{999}));
            assertEquals(0, mRequests.get());
            assertTrue(Float.isNaN(mPolicy.evaluate(now, 100).lux));
            current.onSensorChanged(new SensorEvent(mAssist, 0,
                    SystemClock.elapsedRealtimeNanos(), new float[]{300}));
            assertEquals(1, mRequests.get());
            assertEquals(300, mPolicy.evaluate(SystemClock.uptimeMillis(), 100).lux, .001f);
        });
    }

    @Test
    public void failedAssistRegistrationUnregistersCameraAndThrowsForNativeFallback() throws Exception {
        when(mSensors.registerListener(any(SensorEventListener.class), eq(mAssist),
                anyInt(), eq(mHandler))).thenReturn(false);
        run(() -> {
            try {
                mPolicy.start(SystemClock.uptimeMillis());
                fail("Failed registration must trigger native fallback");
            } catch (IllegalStateException expected) {
                verify(mSensors).unregisterListener(any(SensorEventListener.class));
                assertEquals(Long.MAX_VALUE, mPolicy.evaluate(0, 100).nextEvaluationUptimeMillis);
            }
        });
        settleCamera();
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
}
