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

    // BPC (hardware autonomous pattern) registers.
    private static final int REG_PATCFG = 0xa0;
    private static final int REG_PATGO = 0xa1;
    private static final int REG_PATT0 = 0xa2;
    private static final int REG_PATT1 = 0xa3;
    private static final int REG_PATT2 = 0xa4;
    private static final int REG_PATT3 = 0xa5;
    private static final int REG_FADEH = 0xa6;
    private static final int REG_FADEL = 0xa7;
    private static final int REG_GCOLR = 0xa8;
    private static final int REG_GCOLG = 0xa9;
    private static final int REG_GCOLB = 0xaa;

    /** GCFG0 bits 3-6: chip groups 3-6 = public zones 0-3. */
    private static final int BPC_ZONE_MASK = 0x78;

    /** Driver quantization steps (leds-color21024.h breath_timerms_map_reg). */
    private static final int[] TIMER_MS = {
            0, 130, 260, 380, 510, 770, 1040, 1600,
            2100, 2600, 3100, 4200, 5200, 6200, 7300, 8300};
    /** T1/T3 nibble 0 is not 0 ms on this chip; the datasheet says 40 ms. */
    private static final int HOLD_ZERO_MS = 40;

    private final Object mLock = new Object();
    private final HandlerThread mThread;
    private final Handler mHandler;

    private boolean mPowered;
    private boolean mBpcActive;
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
                if (!mPowered || mBpcActive) {
                    powerOn();
                }
                mBpcActive = false;
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
        if (mBpcActive) {
            // BPC never programmed the BR registers; dropping hwen stops the
            // pattern and skips the 85-write normal shutdown.
            writeNode(NODE_HWEN, "0");
        } else {
            for (int k = 0; k < CHANNEL_COUNT; k++) {
                writeReg(0x01 + 2 * k, 0);
            }
            writeReg(REG_UPDATE, 0);
            writeNode(NODE_RUN, "0");
            writeNode(NODE_HWEN, "0");
        }
        mPowered = false;
        mBpcActive = false;
    }

    /**
     * One-shot handoff to the chip's autonomous pattern controller: after this
     * sequence the LED breathes with no further CPU involvement, across deep
     * sleep. Register order verified on-device; see
     * tmp/disposable-anytime/led-cal/aw21024-bpc-investigation.md.
     */
    public void submitBpcBreath(int[] colors, int brightness, int periodMs, int repeatCount) {
        final int[] copy = colors.clone();
        synchronized (mLock) {
            mPendingColors = null;
            mPendingOff = false;
        }
        mHandler.post(() -> {
            try {
                startBpc(copy, brightness, periodMs, repeatCount);
            } catch (IOException e) {
                Log.e(TAG, "BPC start failed", e);
            }
        });
    }

    private void startBpc(int[] colors, int brightness, int periodMs, int repeatCount)
            throws IOException {
        int rise = quantizeTimerNibble(periodMs / 2);
        int fall = quantizeTimerNibble(periodMs - periodMs / 2);
        brightness = Math.max(0, Math.min(255, brightness));

        writeNode(NODE_HWEN, "0");
        SystemClock.sleep(10);
        writeNode(NODE_HWEN, "1");
        writeReg(REG_GCCR, 0x80);
        writeReg(REG_PATGO, 0x00);
        writeReg(REG_FADEH, brightness);
        writeReg(REG_FADEL, 0x00);
        boolean uniform = true;
        for (int zone = 1; zone < ZONE_COUNT; zone++) {
            if (colors[zone] != colors[0]) {
                uniform = false;
                break;
            }
        }
        if (uniform) {
            writeReg(REG_GCOLR, (colors[0] >> 16) & 0xff);
            writeReg(REG_GCOLG, (colors[0] >> 8) & 0xff);
            writeReg(REG_GCOLB, colors[0] & 0xff);
            writeReg(REG_GCOLDIS, 0x00);
        } else {
            // Verified on-device: in pattern mode GCOLDIS is AC bit 4, per the
            // datasheet; the per-channel COLs then carry each zone's color.
            for (int zone = 0; zone < ZONE_COUNT; zone++) {
                int pixel = zone + 3;
                int[] components = {(colors[zone] >> 16) & 0xff,
                        (colors[zone] >> 8) & 0xff, colors[zone] & 0xff};
                for (int c = 0; c < 3; c++) {
                    writeReg(0x4a + 3 * pixel + c, components[c]);
                }
            }
            writeReg(REG_GCOLDIS, 0x10);
        }
        writeReg(REG_GCFG0, BPC_ZONE_MASK);
        writeReg(REG_PATT0, (rise << 4) | 0x00);
        writeReg(REG_PATT1, (fall << 4) | 0x00);
        writeReg(REG_PATT2, 0x00);
        writeReg(REG_PATT3, repeatCount > 0 ? Math.min(repeatCount, 255) : 0);
        writeReg(REG_PATCFG, 0x07);
        writeReg(REG_PATGO, 0x01);
        mPowered = true;
        mBpcActive = true;
    }

    /** True when a triangle breath of this shape can run on the chip: both
     *  ramps must land on a non-zero quantization step. */
    static boolean isBreathHardwareCompatible(int periodMs, int brightness) {
        if (brightness <= 0) {
            return false;
        }
        return quantizeTimerNibble(periodMs / 2) > 0
                && quantizeTimerNibble(periodMs - periodMs / 2) > 0;
    }

    /** Upper-bound wall time of a finite hardware breath, with margin. The
     *  chip self-stops; this only schedules the arbiter's cleanup callback. */
    static long estimateBpcDurationMs(int periodMs, int repeatCount) {
        int rise = quantizeTimerNibble(periodMs / 2);
        int fall = quantizeTimerNibble(periodMs - periodMs / 2);
        long cycleMs = TIMER_MS[rise] + HOLD_ZERO_MS + TIMER_MS[fall] + HOLD_ZERO_MS;
        return cycleMs * repeatCount + 500;
    }

    /** Mirrors the driver's midpoint rounding in period_store. -1 when the
     *  value exceeds the largest step. */
    private static int quantizeTimerNibble(int ms) {
        if (ms <= 0) {
            return 0;
        }
        if (ms <= TIMER_MS[1]) {
            return 1;
        }
        if (ms > TIMER_MS[TIMER_MS.length - 1]) {
            return -1;
        }
        int nibble = 1;
        for (int i = 1; i < TIMER_MS.length; i++) {
            int lo = TIMER_MS[i - 1];
            int hi = TIMER_MS[i];
            if (ms >= lo && ms <= hi) {
                nibble = ms < (lo + hi) / 2 ? i - 1 : i;
            }
        }
        return nibble;
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
