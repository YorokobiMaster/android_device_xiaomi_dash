/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import android.app.AlarmManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import me.sandai.dashled.aidl.DashLedEffect;

/** Exercises production arbitration and register sequencing without touching sysfs. */
@RunWith(AndroidJUnit4.class)
public class LedArbiterTest {
    private static final int[] WHITE = {0xffffff, 0xffffff, 0xffffff, 0xffffff};
    private final List<String> mWrites = new ArrayList<>();
    private final AtomicLong mElapsed = new AtomicLong(100_000);
    private final AtomicReference<AlarmManager.OnAlarmListener> mCompletion = new AtomicReference<>();
    private final CountDownLatch mAlarmScheduled = new CountDownLatch(1);
    private HandlerThread mThread;
    private Handler mHandler;
    private final AtomicBoolean mFailNextWrite = new AtomicBoolean();
    private Aw21024Backend mBackend;
    private LedArbiter mArbiter;

    @Before
    public void setUp() {
        mThread = new HandlerThread("DashLedTest");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mBackend = new Aw21024Backend(mHandler, (path, value) -> {
            if (mFailNextWrite.getAndSet(false)) {
                throw new IOException("injected write failure");
            }
            synchronized (mWrites) {
                mWrites.add(path.substring(path.lastIndexOf('/') + 1) + "=" + value);
                mWrites.notifyAll();
            }
        });
        AlarmManager alarms = mock(AlarmManager.class);
        doAnswer(invocation -> {
            mCompletion.set(invocation.getArgument(3));
            mAlarmScheduled.countDown();
            return null;
        }).when(alarms).setExact(anyInt(), anyLong(), anyString(),
                any(AlarmManager.OnAlarmListener.class), any(Handler.class));
        mArbiter = new LedArbiter(mBackend, mHandler, alarms, mElapsed::get);
    }

    @After
    public void tearDown() throws Exception {
        mHandler.removeCallbacksAndMessages(null);
        mThread.quitSafely();
        mThread.join(2000);
    }

    @Test
    public void offSupersedesQueuedHardwareBreath() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        mHandler.post(() -> {
            blocked.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blocked.await(2, TimeUnit.SECONDS));
        try {
            mBackend.submitFrame(WHITE, 100);
            mBackend.submitBpcBreath(WHITE, 255, 260, 3, () -> {}, () -> {});
            mBackend.submitOff();
        } finally {
            release.countDown();
        }
        awaitWrite("hwen=0");
        drain();
        synchronized (mWrites) {
            assertFalse(mWrites.contains("reg=a1 01"));
            assertEquals("hwen=0", mWrites.get(mWrites.size() - 1));
        }
    }

    @Test
    public void moreThan255RepeatsUsesSoftwareInsteadOfTruncating() throws Exception {
        LedArbiter.Session session = mArbiter.acquire(0);
        mArbiter.playEffect(session, effect(256), null);
        awaitWrite("reg=53 ff");
        mArbiter.release(session);
        drain();
        synchronized (mWrites) {
            assertFalse(mWrites.contains("reg=a1 01"));
        }
    }

    @Test
    public void wakeAfterHardwareCompletionRestoresLowerPriorityFrame() throws Exception {
        LedArbiter.Session background = mArbiter.acquire(0);
        mArbiter.setFrame(background, WHITE, 42);
        awaitWrite("reg=13 2a");
        drain();
        LedArbiter.Session foreground = mArbiter.acquire(1);
        AtomicInteger completed = new AtomicInteger();
        mArbiter.playEffect(foreground, effect(1), completed::incrementAndGet);
        assertTrue(mAlarmScheduled.await(2, TimeUnit.SECONDS));
        synchronized (mWrites) {
            mWrites.clear();
        }
        // Advance elapsed time alone, as during suspend, without delivering the alarm.
        mElapsed.addAndGet(10_000);
        mArbiter.onWake();
        awaitWrite("reg=13 2a");
        drain();
        assertEquals(1, completed.get());
        mCompletion.get().onAlarm();
        assertEquals(1, completed.get());
    }

    @Test
    public void failedHardwareBreathRestoresLowerPriorityFrame() throws Exception {
        LedArbiter.Session background = mArbiter.acquire(0);
        mArbiter.setFrame(background, WHITE, 42);
        awaitWrite("reg=13 2a");
        drain();
        synchronized (mWrites) {
            mWrites.clear();
        }
        mFailNextWrite.set(true);
        LedArbiter.Session foreground = mArbiter.acquire(1);
        AtomicInteger completed = new AtomicInteger();
        mArbiter.playEffect(foreground, effect(3), () -> {
            completed.incrementAndGet();
            mArbiter.release(foreground);
        });
        awaitWrite("reg=13 2a");
        drain();
        assertEquals(1, completed.get());
        assertEquals(1L, mAlarmScheduled.getCount());
    }

    @Test
    public void staleCompletionCannotClearReplacementFrame() throws Exception {
        LedArbiter.Session session = mArbiter.acquire(0);
        mArbiter.playEffect(session, effect(1), null);
        assertTrue(mAlarmScheduled.await(2, TimeUnit.SECONDS));
        AlarmManager.OnAlarmListener old = mCompletion.get();
        mArbiter.setFrame(session, WHITE, 42);
        awaitWrite("reg=13 2a");
        drain();
        synchronized (mWrites) {
            mWrites.clear();
        }
        old.onAlarm();
        drain();
        synchronized (mWrites) {
            assertTrue(mWrites.isEmpty());
        }
    }

    private void awaitWrite(String write) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 2000;
        synchronized (mWrites) {
            while (!mWrites.contains(write) && SystemClock.uptimeMillis() < deadline) {
                mWrites.wait(Math.max(1, deadline - SystemClock.uptimeMillis()));
            }
            assertTrue("Missing " + write + " in " + mWrites, mWrites.contains(write));
        }
    }

    private void drain() {
        assertTrue(mHandler.runWithScissors(() -> {}, 2000));
    }

    private static DashLedEffect effect(int repeats) {
        DashLedEffect effect = new DashLedEffect();
        effect.type = DashLedEffect.TYPE_BREATH;
        effect.colors = WHITE.clone();
        effect.brightness = 255;
        effect.periodMs = 260;
        effect.repeatCount = repeats;
        return effect;
    }
}
