/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

/** Coherent init property revision, not daemon watch-ready or table-loaded acknowledgement. */
final class ThermalLifecycle {
    private static boolean loaded;

    private ThermalLifecycle() {}

    /**
     * Unsigned 32-bit property serial in bits 2..33; bit 0 present, bit 1 running.
     * Zero means absent (or inaccessible), distinct from a present property at serial zero.
     * No native loading in static initialization: callers can report load/link failures.
     */
    static long sample() throws Exception {
        try {
            ensureLoaded();
            return nativeSample();
        } catch (LinkageError | SecurityException e) {
            throw new Exception("Cannot read init.svc.mi_thermald lifecycle", e);
        }
    }

    /**
     * Wait without polling until the sample may have changed. Re-sample after returning:
     * absent properties wait on global changes, which may concern unrelated properties.
     * The native wait does not hold the class monitor and is not Java-interruptible.
     */
    static void awaitChange(long expectedSample) throws Exception {
        try {
            ensureLoaded();
            nativeAwaitChange(expectedSample);
        } catch (LinkageError | SecurityException e) {
            throw new Exception("Cannot wait for init.svc.mi_thermald lifecycle", e);
        }
    }

    private static synchronized void ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("dashthermal_jni");
            loaded = true;
        }
    }

    static boolean isPresent(long sample) {
        return sample >= 0 && (sample & 1) != 0;
    }

    static boolean isRunning(long sample) {
        return isPresent(sample) && (sample & 2) != 0;
    }

    /** Returns -1 if unavailable/absent. */
    static long revision(long sample) {
        return isPresent(sample) ? sample >>> 2 : -1;
    }

    private static native long nativeSample();
    private static native void nativeAwaitChange(long expectedSample) throws Exception;
}
