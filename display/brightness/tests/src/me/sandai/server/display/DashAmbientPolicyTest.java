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
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Registration and stale-callback checks on a real handler, with mocked hardware services. */
public class DashAmbientPolicyTest {
    private final SensorManager mSensors = mock(SensorManager.class);
    private final CameraManager mCameras = mock(CameraManager.class);
    private final Sensor mAssist = mock(Sensor.class);
    private final AtomicInteger mRequests = new AtomicInteger();
    private HandlerThread mThread;
    private Handler mHandler;
    private DashAmbientPolicy mPolicy;

    @Before
    public void setUp() throws Exception {
        mThread = new HandlerThread("DashAmbientPolicyTest");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        Context context = mock(Context.class);
        when(context.getSystemServiceName(CameraManager.class)).thenReturn(Context.CAMERA_SERVICE);
        when(context.getSystemService(Context.CAMERA_SERVICE)).thenReturn(mCameras);
        when(mCameras.getCameraIdList()).thenReturn(new String[0]);
        when(mAssist.getType()).thenReturn(33171055);
        when(mSensors.getDefaultSensor(33171055)).thenReturn(mAssist);
        when(mSensors.registerListener(any(SensorEventListener.class), any(Sensor.class),
                anyInt(), eq(mHandler))).thenReturn(true);
        mPolicy = new DashAmbientPolicy(context, mSensors, mHandler, mRequests::incrementAndGet);
    }

    @After
    public void tearDown() throws Exception {
        try {
            run(() -> mPolicy.stop());
        } finally {
            mThread.quitSafely();
            mThread.join(5000);
        }
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
        run(() -> {
            long now = SystemClock.uptimeMillis();
            mPolicy.start(now);
            SensorEventListener sensorListener = listener();
            ArgumentCaptor<CameraManager.TorchCallback> torch =
                    ArgumentCaptor.forClass(CameraManager.TorchCallback.class);
            verify(mCameras).registerTorchCallback(torch.capture(), eq(mHandler));
            mPolicy.stop();
            verify(mSensors).unregisterListener(sensorListener);
            verify(mCameras).unregisterTorchCallback(torch.getValue());
            assertTrue(Float.isNaN(mPolicy.evaluate(now, 100).lux));
            assertEquals(Long.MAX_VALUE, mPolicy.evaluate(now, 100).nextEvaluationUptimeMillis);
            assertFalse(mPolicy.useFastRamp(.1f, .2f, 100, 160, false));
        });
    }

    @Test
    public void priorGenerationAndPreStartEventsCannotReachEstimator() throws Exception {
        run(() -> {
            long beforeStart = SystemClock.elapsedRealtimeNanos() - 1;
            mPolicy.start(SystemClock.uptimeMillis());
            SensorEventListener previous = listener();
            previous.onSensorChanged(new SensorEvent(mAssist, 0, beforeStart, new float[]{999}));
            assertEquals(0, mRequests.get());
            mPolicy.stop();
            mPolicy.start(SystemClock.uptimeMillis());
            SensorEventListener current = listener();
            long now = SystemClock.uptimeMillis();
            mPolicy.onPrimarySample(now, 100);
            mPolicy.evaluate(now, Float.NaN);
            previous.onSensorChanged(new SensorEvent(mAssist, 0,
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
                verify(mCameras).unregisterTorchCallback(any(CameraManager.TorchCallback.class));
                verify(mSensors).unregisterListener(any(SensorEventListener.class));
                assertEquals(Long.MAX_VALUE, mPolicy.evaluate(0, 100).nextEvaluationUptimeMillis);
            }
        });
    }

    @Test
    public void cameraCleanupStillRunsIfSensorUnregisterFails() throws Exception {
        run(() -> {
            mPolicy.start(SystemClock.uptimeMillis());
            doThrow(new IllegalStateException("unregister failed"))
                    .when(mSensors).unregisterListener(any(SensorEventListener.class));
            try {
                mPolicy.stop();
                fail("Unregister failure must propagate");
            } catch (IllegalStateException expected) {
                verify(mCameras).unregisterTorchCallback(any(CameraManager.TorchCallback.class));
            }
        });
    }
}
