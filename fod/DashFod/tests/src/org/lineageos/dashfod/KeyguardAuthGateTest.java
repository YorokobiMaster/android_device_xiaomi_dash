/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.dashfod;

import static org.junit.Assert.assertEquals;
import static org.lineageos.dashfod.KeyguardAuthGate.Action.NONE;
import static org.lineageos.dashfod.KeyguardAuthGate.Action.START;
import static org.lineageos.dashfod.KeyguardAuthGate.Action.STOP;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class KeyguardAuthGateTest {
    @Test
    public void interactiveQualificationStartsOnceInEitherOrder() {
        KeyguardAuthGate gate = new KeyguardAuthGate();
        assertEquals(NONE, gate.onAuthenticationStart(true));
        assertEquals(START, gate.onBiometricState(true));

        gate = new KeyguardAuthGate();
        assertEquals(NONE, gate.onBiometricState(true));
        assertEquals(START, gate.onAuthenticationStart(true));
    }

    @Test
    public void nonInteractiveFastReversalStartsOnScreenOn() {
        KeyguardAuthGate gate = new KeyguardAuthGate();
        assertEquals(NONE, gate.onAuthenticationStart(false));
        assertEquals(NONE, gate.onBiometricState(true));
        assertEquals(START, gate.onScreenOn());

        gate = new KeyguardAuthGate();
        assertEquals(NONE, gate.onBiometricState(true));
        assertEquals(NONE, gate.onAuthenticationStart(false));
        assertEquals(START, gate.onScreenOn());
    }

    @Test
    public void strayScreenOnDoesNotQualifyFutureStart() {
        KeyguardAuthGate gate = new KeyguardAuthGate();
        assertEquals(NONE, gate.onScreenOn());
        assertEquals(NONE, gate.onBiometricState(true));
        assertEquals(NONE, gate.onAuthenticationStart(false));
        assertEquals(START, gate.onScreenOn());
    }

    @Test
    public void duplicateSignalsAreIdempotentAfterDispatch() {
        KeyguardAuthGate gate = new KeyguardAuthGate();
        assertEquals(NONE, gate.onBiometricState(true));
        assertEquals(START, gate.onAuthenticationStart(true));
        assertEquals(NONE, gate.onAuthenticationStart(true));
        assertEquals(NONE, gate.onBiometricState(true));
        assertEquals(NONE, gate.onScreenOn());
    }

    @Test
    public void staleBiometricIdleDoesNotClearPendingStart() {
        KeyguardAuthGate gate = new KeyguardAuthGate();
        assertEquals(NONE, gate.onAuthenticationStart(false));
        assertEquals("phase=PENDING biometric=false wake=false", gate.toString());
        assertEquals(NONE, gate.onBiometricState(false));
        assertEquals("phase=PENDING biometric=false wake=false", gate.toString());
        assertEquals(NONE, gate.onBiometricState(true));
        assertEquals("phase=PENDING biometric=true wake=false", gate.toString());
        assertEquals(START, gate.onScreenOn());
        assertEquals("phase=DISPATCHED biometric=true wake=true", gate.toString());
        assertEquals(NONE, gate.onBiometricState(true));
        assertEquals(NONE, gate.onScreenOn());
    }

    @Test
    public void biometricLossStopsDispatchedOnce() {
        KeyguardAuthGate gate = new KeyguardAuthGate();
        assertEquals(NONE, gate.onBiometricState(true));
        assertEquals(START, gate.onAuthenticationStart(true));
        assertEquals(STOP, gate.onBiometricState(false));
        assertEquals("phase=IDLE biometric=false wake=false", gate.toString());
        assertEquals(NONE, gate.onBiometricState(false));
        assertEquals(NONE, gate.onScreenOn());
    }

    @Test
    public void screenOffStopsAndNextVisiblePulseRearms() {
        KeyguardAuthGate gate = new KeyguardAuthGate();
        assertEquals(NONE, gate.onBiometricState(true));
        assertEquals(NONE, gate.onAuthenticationStart(false));
        assertEquals(START, gate.onScreenOn());

        assertEquals(STOP, gate.onScreenOff());
        assertEquals("phase=PENDING biometric=true wake=false", gate.toString());
        assertEquals(NONE, gate.onScreenOff());
        assertEquals(START, gate.onScreenOn());
        assertEquals("phase=DISPATCHED biometric=true wake=true", gate.toString());
    }

    @Test
    public void terminalAndResetClearWithoutReplay() {
        KeyguardAuthGate gate = new KeyguardAuthGate();
        assertEquals(NONE, gate.onBiometricState(true));
        assertEquals(NONE, gate.onAuthenticationStart(false));
        gate.onTerminal();
        assertEquals(NONE, gate.onScreenOn());
        assertEquals(NONE, gate.onBiometricState(true));

        assertEquals(START, gate.onAuthenticationStart(true));
        gate.reset();
        assertEquals(NONE, gate.onScreenOn());
        assertEquals(NONE, gate.onBiometricState(true));
        assertEquals(NONE, gate.onAuthenticationStart(false));
        assertEquals(START, gate.onScreenOn());
    }
}
