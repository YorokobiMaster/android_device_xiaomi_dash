/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature;
import vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeatureCallback;

/** DisplayFeature HAL lifecycle checks against a fake local binder, on real handler threads. */
public class DashBrightnessPolicyTest {
    private HandlerThread mThread;
    private Handler mHandler;
    private FakeHal mHal;
    private AtomicReference<IBinder> mService;
    private final AtomicInteger mChanged = new AtomicInteger();
    private DashBrightnessPolicy mPolicy;

    @Before
    public void setUp() {
        mThread = new HandlerThread("DashBrightnessPolicyTest");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mHal = new FakeHal();
        mService = new AtomicReference<>(mHal);
        mPolicy = new DashBrightnessPolicy(mHandler, () -> {
            mChanged.incrementAndGet();
        }, mService::get);
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

    private String dump() throws Exception {
        StringWriter out = new StringWriter();
        run(() -> mPolicy.dump(new PrintWriter(out)));
        return out.toString();
    }

    private float sdrCap() throws Exception {
        float[] out = new float[1];
        run(() -> out[0] = mPolicy.getLimits().sdrMaxNits());
        return out[0];
    }

    private float hdrCap() throws Exception {
        float[] out = new float[1];
        run(() -> out[0] = mPolicy.getLimits().hdrMaxBrightness());
        return out[0];
    }

    private void waitForHdrCap(float expected) throws Exception {
        for (int i = 0; i < 50; i++) {
            if (hdrCap() == expected) return;
            Thread.sleep(100);
        }
        fail("HDR cap never became " + expected + ", last=" + hdrCap());
    }

    private void waitForSdrCap(float expected) throws Exception {
        for (int i = 0; i < 50; i++) {
            float value = sdrCap();
            if (Float.isNaN(expected) ? Float.isNaN(value) : value == expected) return;
            Thread.sleep(100);
        }
        fail("sdr cap never became " + expected + ", last=" + sdrCap());
    }

    private void waitForDumpContaining(String expected) throws Exception {
        for (int i = 0; i < 50; i++) {
            String value = dump();
            if (value.contains(expected)) return;
            Thread.sleep(100);
        }
        fail("dump never contained " + expected + ":\n" + dump());
    }

    private void waitForChangedAfter(int previous) throws Exception {
        for (int i = 0; i < 50; i++) {
            if (mChanged.get() > previous) return;
            Thread.sleep(100);
        }
        fail("onChanged did not run after " + previous + ", count=" + mChanged.get());
    }

    private void assertSdrCapStays(float expected) throws Exception {
        for (int i = 0; i < 5; i++) {
            Thread.sleep(100);
            float value = sdrCap();
            if (Float.isNaN(expected)) {
                assertTrue("unexpected sdr cap: " + value, Float.isNaN(value));
            } else {
                assertEquals(expected, value, 0);
            }
        }
    }

    private void assertSdrCapStaysNaN() throws Exception {
        assertSdrCapStays(Float.NaN);
    }

    private void connectAutomatic() throws Exception {
        run(() -> mPolicy.update(true, true, 30000, false));
        assertEquals("register", mHal.awaitEvent());
        assertEquals("setFeature:13:1", mHal.awaitEvent());
        assertEquals("setFeature:56:1", mHal.awaitEvent());
    }

    @Test
    public void connectRegistersCallbackThenNotifiesOnThenEnablesHistogram() throws Exception {
        connectAutomatic();
        for (int i = 0; i < 50; i++) {
            String dump = dump();
            if (dump.contains("callbackRegistered=true")
                    && dump.contains("displayStateSynced=true")
                    && dump.contains("histogramEnabled=true")) return;
            Thread.sleep(100);
        }
        fail("HAL session state never published:\n" + dump());
    }

    @Test
    public void manualModeDisablesHistogramAndClearsGray() throws Exception {
        connectAutomatic();
        mHal.callback.get().displayfeatureInfoChanged(95000, 200, 0, 0, 0);
        waitForSdrCap(1060);
        run(() -> mPolicy.update(true, false, 30000, false));
        assertEquals("setFeature:56:0", mHal.awaitEvent());
        waitForSdrCap(Float.NaN);
    }

    @Test
    public void staleSessionCallbackCannotResurrectGray() throws Exception {
        connectAutomatic();
        FakeHal first = mHal;
        first.callback.get().displayfeatureInfoChanged(95000, 200, 0, 0, 0);
        waitForSdrCap(1060);
        first.death.get().binderDied();
        // Death cleanup on a live fake unregisters and stops sampling; it never fakes display off.
        assertEquals("setFeature:56:0", first.awaitEvent());
        assertEquals("unregister", first.awaitEvent());
        FakeHal second = new FakeHal();
        mHal = second;
        mService.set(second);
        assertEquals("register", second.awaitEvent());
        assertEquals("setFeature:13:1", second.awaitEvent());
        assertEquals("setFeature:56:1", second.awaitEvent());
        // A gray reading from the dead session's callback must be dropped.
        first.callback.get().displayfeatureInfoChanged(95000, 100, 0, 0, 0);
        assertSdrCapStays(1060);
        second.callback.get().displayfeatureInfoChanged(95000, 200, 0, 0, 0);
        waitForSdrCap(1060);
    }

    @Test
    public void histogramSamplingUsesRawLuxAndHdrEligibility() throws Exception {
        run(() -> mPolicy.update(true, true, 20000, false));
        assertEquals("register", mHal.awaitEvent());
        assertEquals("setFeature:13:1", mHal.awaitEvent());
        mHal.assertNoEvent(300);

        run(() -> mPolicy.update(true, true, 20000.01f, false));
        assertEquals("setFeature:56:1", mHal.awaitEvent());

        run(() -> mPolicy.update(true, true, 0, true));
        mHal.assertNoEvent(300);
        run(() -> mPolicy.update(true, true, 0, false));
        assertEquals("setFeature:56:0", mHal.awaitEvent());
    }

    @Test
    public void resetGrayRemainsLegalForHdrPeakWithoutCallback() throws Exception {
        run(() -> mPolicy.update(true, true, 100001, true));
        assertEquals("register", mHal.awaitEvent());
        assertEquals("setFeature:13:1", mHal.awaitEvent());
        assertEquals("setFeature:56:1", mHal.awaitEvent());
        waitForHdrCap(1.0f);
        waitForDumpContaining("gray=163");
    }

    @Test
    public void hdrLayerHidesSdrCapButKeepsHistogramEnabled() throws Exception {
        connectAutomatic();
        mHal.callback.get().displayfeatureInfoChanged(95000, 0, 0, 0, 0);
        waitForSdrCap(3500);

        run(() -> mPolicy.update(true, true, 30000, true));
        waitForSdrCap(Float.NaN);
        assertTrue(dump().contains("histogramEnabled=true"));

        run(() -> mPolicy.update(true, true, 30000, false));
        waitForSdrCap(3500);
    }

    @Test
    public void ordinarySetFeatureFailureDoesNotFakeDisplayOff() throws Exception {
        connectAutomatic();
        FakeHal first = mHal;
        first.failSetFeatureOnValue = 0;
        run(() -> mPolicy.update(true, false, 30000, false));
        // Toggle attempt, cleanup attempt, unregister; no display-off notify on this path.
        assertEquals("setFeature:56:0", first.awaitEvent());
        assertEquals("setFeature:56:0", first.awaitEvent());
        assertEquals("unregister", first.awaitEvent());
        first.assertNoEvent(300);
        FakeHal second = new FakeHal();
        mHal = second;
        mService.set(second);
        assertEquals("register", second.awaitEvent());
        assertEquals("setFeature:13:1", second.awaitEvent());
        second.assertNoEvent(300);
    }

    @Test
    public void stopDisposesWithoutDisplayOffAndCallbacksAreIgnored() throws Exception {
        connectAutomatic();
        run(() -> mPolicy.stop());
        // Disposal cleans up sampling and the callback; the physical display is not off.
        assertEquals("setFeature:56:0", mHal.awaitEvent());
        assertEquals("unregister", mHal.awaitEvent());
        mHal.assertNoEvent(300);
        mHal.callback.get().displayfeatureInfoChanged(95000, 200, 0, 0, 0);
        assertSdrCapStaysNaN();
    }

    @Test
    public void realNonInteractiveTransitionNotifiesDisplayOff() throws Exception {
        connectAutomatic();
        run(() -> mPolicy.update(false, true, 30000, false));
        assertEquals("setFeature:56:0", mHal.awaitEvent());
        assertEquals("setFeature:13:0", mHal.awaitEvent());
        assertEquals("unregister", mHal.awaitEvent());
    }

    @Test
    public void highGrayReadingIsAcceptedAndNegativeIsRejected() throws Exception {
        connectAutomatic();
        mHal.callback.get().displayfeatureInfoChanged(95000, 300, 0, 0, 0);
        waitForDumpContaining("gray=300");
        assertTrue(dump().contains("grayCallbacks=1"));
        assertSdrCapStays(1060);
        mHal.callback.get().displayfeatureInfoChanged(95000, -1, 0, 0, 0);
        Thread.sleep(100);
        assertTrue(dump().contains("gray=300"));
        assertTrue(dump().contains("grayCallbacks=1"));
    }

    @Test
    public void unchangedContentCapsOnlyUpdateGrayDiagnostics() throws Exception {
        connectAutomatic();
        int before = mChanged.get();
        mHal.callback.get().displayfeatureInfoChanged(95000, 200, 0, 0, 0);
        waitForDumpContaining("gray=200");
        assertEquals(before, mChanged.get());
        assertTrue(dump().contains("grayCallbacks=1"));

        mHal.callback.get().displayfeatureInfoChanged(95000, 0, 0, 0, 0);
        waitForDumpContaining("gray=0");
        waitForChangedAfter(before);
    }

    @Test
    public void repeatedUpdateDoesNotRetoggleHistogram() throws Exception {
        connectAutomatic();
        run(() -> mPolicy.update(true, true, 30000, false));
        run(() -> mPolicy.update(true, true, 31000, false));
        mHal.assertNoEvent(300);
    }

    @Test
    public void hdrPeakRequiresAutoLuxAndLegalDarkContent() throws Exception {
        connectAutomatic();
        mHal.callback.get().displayfeatureInfoChanged(95000, 219, 0, 0, 0);
        run(() -> mPolicy.update(true, true, 100000, true));
        waitForHdrCap(HdrBrightness.NORMAL_MAX);
        run(() -> mPolicy.update(true, true, 100001, true));
        waitForHdrCap(1.0f);
        mHal.callback.get().displayfeatureInfoChanged(95000, 220, 0, 0, 0);
        waitForHdrCap(HdrBrightness.NORMAL_MAX);
        run(() -> mPolicy.update(true, false, 100001, true));
        waitForHdrCap(HdrBrightness.NORMAL_MAX);
    }

    private static final class FakeHal extends IDisplayFeature.Stub {
        final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        final AtomicReference<IDisplayFeatureCallback> callback = new AtomicReference<>();
        final AtomicReference<IBinder.DeathRecipient> death = new AtomicReference<>();
        volatile Integer failSetFeatureOnValue;

        String awaitEvent() throws InterruptedException {
            String event = events.poll(5, TimeUnit.SECONDS);
            if (event == null) fail("timed out waiting for HAL event, pending=" + events);
            return event;
        }

        void assertNoEvent(long millis) throws InterruptedException {
            String event = events.poll(millis, TimeUnit.MILLISECONDS);
            if (event != null) fail("unexpected HAL event: " + event);
        }

        @Override
        public void linkToDeath(IBinder.DeathRecipient recipient, int flags) {
            death.set(recipient);
        }

        @Override
        public boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) {
            return true;
        }

        @Override
        public void notifyBrightness(int brightness) { }

        @Override
        public void registerCallback(int displayId, IDisplayFeatureCallback cb) {
            callback.set(cb);
            events.add("register");
        }

        @Override
        public void unregisterCallback(int displayId, IDisplayFeatureCallback cb) {
            events.add("unregister");
        }

        @Override
        public void setFeature(int displayId, int mode, int value, int cookie)
                throws RemoteException {
            events.add("setFeature:" + mode + ":" + value);
            Integer failValue = failSetFeatureOnValue;
            if (failValue != null && failValue == value) throw new RemoteException("injected");
        }

        @Override
        public void sendMessage(int displayId, int type, String message) { }

        @Override
        public void sendPanelCommand(String command) { }

        @Override
        public void sendPostProcCommand(int displayId, int command) { }

        @Override
        public void sendRefreshCommand() { }

        @Override
        public void setFunction(int displayId, int function, int value, int cookie) { }

        @Override
        public void sendGamePkgName(int displayId, int mode, int value, String name) { }

        @Override
        public int getInterfaceVersion() {
            return IDisplayFeature.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return IDisplayFeature.HASH;
        }
    }
}
