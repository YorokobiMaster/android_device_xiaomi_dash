/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Serialized, rate-limited writer for /sys/class/leds/aw21024_led.
 *
 * Register semantics verified on-device (docs/hardware.md): per channel k,
 * COL (0x4a+k) is the DC current and carries the color mix, BR (0x01+2k) is
 * the PWM duty and carries brightness. Zone i drives chip pixel i+3, i.e.
 * channels 3*(i+3) .. 3*(i+3)+2 in R, G, B order.
 */
public final class Aw21024Backend {

    private static final String TAG = "Aw21024Backend";

    private static final String SYSFS_ROOT = "/sys/class/leds/aw21024_led";
    private static final String NODE_HWEN = SYSFS_ROOT + "/hwen";
    private static final String NODE_RUN = SYSFS_ROOT + "/run";
    private static final String NODE_REG = SYSFS_ROOT + "/reg";

    static final int ZONE_COUNT = 4;
    static final int MIN_FRAME_INTERVAL_MS = 50;

    private static final int REG_UPDATE = 0x49;
    private static final int REG_GCCR = 0x6e;
    private static final int REG_GCFG0 = 0xab;
    private static final int REG_GCOLDIS = 0xac;
    private static final int REG_RGBMD = 0x7a;
    private static final int CHANNEL_COUNT = 24;

    private final Object mLock = new Object();
    private final HandlerThread mThread;
    private final Handler mHandler;

    private boolean mPowered;
    private long mLastFlushUptimeMs;
    private boolean mFlushScheduled;
    private int[] mPendingColors;
    private int mPendingBrightness;
    private boolean mPendingOff;

    public Aw21024Backend() {
        mThread = new HandlerThread(TAG);
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
    }

    /** The single LED thread; the arbiter renders effects on it too so all
     *  hardware-affecting work stays serialized. */
    Handler handler() {
        return mHandler;
    }

    /** Latest frame wins; intermediate frames submitted inside one coalescing
     *  window are dropped without touching the hardware. */
    public void submitFrame(int[] colors, int brightness) {
        synchronized (mLock) {
            mPendingColors = colors.clone();
            mPendingBrightness = brightness;
            mPendingOff = false;
            scheduleFlushLocked();
        }
    }

    public void submitOff() {
        synchronized (mLock) {
            mPendingColors = null;
            mPendingOff = true;
            scheduleFlushLocked();
        }
    }

    private void scheduleFlushLocked() {
        if (mFlushScheduled) {
            return;
        }
        mFlushScheduled = true;
        long delay = Math.max(0,
                mLastFlushUptimeMs + MIN_FRAME_INTERVAL_MS - SystemClock.uptimeMillis());
        mHandler.postDelayed(mFlushRunnable, delay);
    }

    private final Runnable mFlushRunnable = this::flush;

    private void flush() {
        final int[] colors;
        final int brightness;
        final boolean off;
        synchronized (mLock) {
            mFlushScheduled = false;
            mLastFlushUptimeMs = SystemClock.uptimeMillis();
            colors = mPendingColors;
            brightness = mPendingBrightness;
            off = mPendingOff;
            mPendingColors = null;
            mPendingOff = false;
            if (colors == null && !off) {
                return;
            }
        }
        try {
            if (off) {
                powerOff();
            } else {
                if (!mPowered) {
                    powerOn();
                }
                applyFrame(colors, brightness);
            }
        } catch (IOException e) {
            Log.e(TAG, "sysfs write failed", e);
        }
    }

    private void powerOn() throws IOException {
        // No run=0 here: it replays the 62-register led_off array in the
        // driver, which can take seconds when this process sits in a cached
        // cgroup. The hwen cycle below fully resets the chip anyway.
        writeNode(NODE_HWEN, "0");
        SystemClock.sleep(10);
        writeNode(NODE_HWEN, "1");
        writeReg(REG_GCCR, 0x80);
        writeReg(REG_GCFG0, 0);
        writeReg(REG_GCOLDIS, 1);
        writeReg(REG_RGBMD, 0);
        mPowered = true;
    }

    private void powerOff() throws IOException {
        for (int k = 0; k < CHANNEL_COUNT; k++) {
            writeReg(0x01 + 2 * k, 0);
        }
        writeReg(REG_UPDATE, 0);
        writeNode(NODE_RUN, "0");
        writeNode(NODE_HWEN, "0");
        mPowered = false;
    }

    private void applyFrame(int[] colors, int brightness) throws IOException {
        brightness = Math.max(0, Math.min(255, brightness));
        for (int zone = 0; zone < ZONE_COUNT; zone++) {
            int pixel = zone + 3;
            int color = colors[zone];
            int[] components = {
                    (color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff};
            for (int c = 0; c < 3; c++) {
                int k = 3 * pixel + c;
                writeReg(0x01 + 2 * k, brightness);
                writeReg(0x4a + k, components[c]);
            }
        }
        writeReg(REG_UPDATE, 0);
    }

    private static void writeReg(int addr, int value) throws IOException {
        writeNode(NODE_REG, String.format(Locale.US, "%02x %02x", addr, value));
    }

    private static void writeNode(String path, String value) throws IOException {
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write((value + "\n").getBytes(StandardCharsets.US_ASCII));
        }
    }
}
