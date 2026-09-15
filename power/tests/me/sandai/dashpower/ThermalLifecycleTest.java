/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

/** Host-only: the Android JNI library is intentionally unavailable on the plain JDK. */
public final class ThermalLifecycleTest {
    public static void main(String[] args) {
        for (int attempt = 0; attempt < 4; ++attempt) {
            try {
                if (attempt % 2 == 0) ThermalLifecycle.sample();
                else ThermalLifecycle.awaitChange(0);
                throw new AssertionError("Host unexpectedly loaded Android lifecycle JNI");
            } catch (Exception expected) {
                if (!(expected.getCause() instanceof LinkageError)
                        && !(expected.getCause() instanceof SecurityException)) {
                    throw new AssertionError("Load failure must retain reportable cause", expected);
                }
            }
        }
        if (!ThermalLifecycle.isRunning(3)) {
            throw new AssertionError("Failed loading must not poison class initialization");
        }
        System.out.println("PASS: lifecycle native failure is reportable and class remains usable");
    }
}
