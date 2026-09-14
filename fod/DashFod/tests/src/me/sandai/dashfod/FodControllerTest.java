/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashfod;

import static me.sandai.dashfod.FodController.Operation.ENROLLMENT;
import static me.sandai.dashfod.FodController.Operation.GENERIC_AUTH;
import static me.sandai.dashfod.FodController.Operation.KEYGUARD_AUTH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

@RunWith(JUnit4.class)
public final class FodControllerTest {
    @Test
    public void startupCreatesNoRequest() {
        FakeClient client = new FakeClient();
        FodController controller = new FodController(client, message -> {});

        controller.onStartup();
        controller.reconcile();

        assertFalse(controller.needsReconcile());
        assertCalls(client, "connect", "4:2", "7:0", "1:0");
    }

    @Test
    public void enrollmentHasExactOrderAndDuplicateStartIsSilent() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);

        controller.onStart(ENROLLMENT);
        controller.onStart(ENROLLMENT);
        controller.onStop(ENROLLMENT);

        assertCalls(client, "4:1", "7:0", "1:1", "4:2", "7:0", "1:0");
    }

    @Test
    public void keyguardHasExactOrderAndSucceededCleans() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);

        controller.onStart(KEYGUARD_AUTH);
        controller.onSucceeded(KEYGUARD_AUTH);

        assertCalls(client, "4:3", "7:1", "18:1", "1:1", "4:4", "7:0", "1:0");
    }

    @Test
    public void genericHasExactOrderAndErrorCleans() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);

        controller.onStart(GENERIC_AUTH);
        controller.onError(GENERIC_AUTH);

        assertCalls(client, "4:3", "7:0", "18:4", "1:1", "4:4", "7:0", "1:0");
    }

    @Test
    public void newOperationCleansAppliedOperationBeforeStarting() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);

        controller.onStart(ENROLLMENT);
        controller.onStart(GENERIC_AUTH);

        assertCalls(client, "4:1", "7:0", "1:1", "4:2", "7:0", "1:0",
                "4:3", "7:0", "18:4", "1:1");
    }

    @Test
    public void staleTerminalsForOtherOperationAreIgnored() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(ENROLLMENT);
        client.calls.clear();

        controller.onStop(KEYGUARD_AUTH);
        controller.onError(GENERIC_AUTH);
        controller.onSucceeded(KEYGUARD_AUTH);
        controller.onFailed(GENERIC_AUTH);

        assertCalls(client);
    }

    @Test
    public void matchingFailedKeepsAuthRequestActive() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);

        controller.onStart(KEYGUARD_AUTH);
        controller.onFailed(KEYGUARD_AUTH);
        controller.onStop(KEYGUARD_AUTH);

        assertCalls(client, "4:3", "7:1", "18:1", "1:1", "4:3", "7:0",
                "4:4", "7:0", "1:0");
    }

    @Test
    public void partialStartFailureCleansAndReconcileRetriesDesiredRequest() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        client.results.add(true);
        client.results.add(false);

        controller.onStart(ENROLLMENT);

        assertTrue(controller.needsReconcile());
        assertCalls(client, "4:1", "7:0", "4:2", "7:0", "1:0");
        client.calls.clear();

        controller.reconcile();

        assertFalse(controller.needsReconcile());
        assertCalls(client, "4:1", "7:0", "1:1");
    }

    @Test
    public void failedMatchFailureCleansAndReconcileRearmsDesiredRequest() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(KEYGUARD_AUTH);
        client.calls.clear();
        client.results.add(true);
        client.results.add(false);

        controller.onFailed(KEYGUARD_AUTH);

        assertTrue(controller.needsReconcile());
        assertCalls(client, "4:3", "7:0", "4:4", "7:0", "1:0");
        client.calls.clear();

        controller.reconcile();

        assertFalse(controller.needsReconcile());
        assertCalls(client, "4:3", "7:1", "18:1", "1:1");
    }

    @Test
    public void failedCleanupAttemptsEveryDisarmCommand() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(ENROLLMENT);
        client.calls.clear();
        client.results.add(false);

        controller.onStop(ENROLLMENT);

        assertTrue(controller.needsReconcile());
        assertCalls(client, "4:2", "7:0", "1:0");
    }

    @Test
    public void debtIsSettledBeforeNewDesiredOperationStarts() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        client.results.add(true);
        client.results.add(false);
        client.results.add(false);

        controller.onStart(ENROLLMENT);
        client.calls.clear();

        controller.onStart(GENERIC_AUTH);

        assertCalls(client, "4:2", "7:0", "1:0", "4:3", "7:0", "18:4", "1:1");
    }

    @Test
    public void stopWhileDebtAndRequestPendingPreventsReplay() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        client.results.add(true);
        client.results.add(false);
        client.results.add(false);

        controller.onStart(ENROLLMENT);
        client.calls.clear();

        controller.onStop(ENROLLMENT);
        controller.reconcile();

        assertFalse(controller.needsReconcile());
        assertCalls(client, "4:2", "7:0", "1:0");
    }

    @Test
    public void disablingKeyguardCleansButPreservesRequestForReenable() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(KEYGUARD_AUTH);
        client.calls.clear();

        controller.setKeyguardAllowed(false);
        controller.setKeyguardAllowed(true);

        assertCalls(client, "4:4", "7:0", "1:0", "4:3", "7:1", "18:1", "1:1");
    }

    @Test
    public void keyguardRequestWhileDisallowedAppliesWhenAllowed() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);

        controller.setKeyguardAllowed(false);
        controller.onStart(KEYGUARD_AUTH);
        assertFalse(controller.needsReconcile());
        assertCalls(client);

        controller.setKeyguardAllowed(true);

        assertFalse(controller.needsReconcile());
        assertCalls(client, "4:3", "7:1", "18:1", "1:1");
    }

    @Test
    public void enablingKeyguardWithoutRequestDoesNotInventOne() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);

        controller.setKeyguardAllowed(false);
        controller.setKeyguardAllowed(true);
        controller.setKeyguardAllowed(true);

        assertFalse(controller.needsReconcile());
        assertCalls(client);
    }

    @Test
    public void duplicateAllowedPolicyDoesNotEmitStop() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(KEYGUARD_AUTH);
        client.calls.clear();

        controller.setKeyguardAllowed(true);

        assertCalls(client);
    }

    @Test
    public void vendorDeathReappliesStillCurrentFrameworkRequest() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(ENROLLMENT);
        client.calls.clear();
        client.connected = false;

        controller.onVendorDeath();
        controller.reconcile();

        assertFalse(controller.needsReconcile());
        assertCalls(client, "connect", "4:2", "7:0", "1:0", "4:1", "7:0", "1:1");
    }

    @Test
    public void terminalBeforeVendorDeathDoesNotReplayEndedRequest() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(KEYGUARD_AUTH);
        controller.onError(KEYGUARD_AUTH);
        client.calls.clear();
        client.connected = false;

        controller.onVendorDeath();
        controller.reconcile();

        assertFalse(controller.needsReconcile());
        assertCalls(client, "connect", "4:2", "7:0", "1:0");
    }

    @Test
    public void terminalDuringReconnectPreventsPendingReplay() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(KEYGUARD_AUTH);
        client.calls.clear();
        client.connected = false;
        client.connectResults.add(false);
        controller.onVendorDeath();
        assertTrue(controller.needsReconcile());
        client.calls.clear();

        controller.onStop(KEYGUARD_AUTH);
        controller.reconcile();

        assertFalse(controller.needsReconcile());
        assertCalls(client, "connect", "4:2", "7:0", "1:0");
    }

    @Test
    public void delayedVendorDeathDoesNotEraseNewFrameworkStart() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(KEYGUARD_AUTH);
        controller.onStop(KEYGUARD_AUTH);
        controller.onStart(GENERIC_AUTH);
        client.calls.clear();
        client.connected = false;

        controller.onVendorDeath();
        controller.onStop(GENERIC_AUTH);
        controller.reconcile();

        assertFalse(controller.needsReconcile());
        assertCalls(client, "connect", "4:2", "7:0", "1:0",
                "4:3", "7:0", "18:4", "1:1", "4:4", "7:0", "1:0");
    }

    @Test
    public void userChangeDiscardsRequestAndFreshStartWorks() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(GENERIC_AUTH);
        client.calls.clear();

        controller.onUserChanged();
        controller.reconcile();
        controller.onStart(GENERIC_AUTH);

        assertFalse(controller.needsReconcile());
        assertCalls(client, "4:2", "7:0", "1:0", "4:3", "7:0", "18:4", "1:1");
    }

    private static FodController started(FakeClient client) {
        FodController controller = new FodController(client, message -> {});
        controller.onStartup();
        client.calls.clear();
        return controller;
    }

    private static void assertCalls(FakeClient client, String... calls) {
        assertEquals(Arrays.asList(calls), client.calls);
    }

    private static final class FakeClient implements FodController.Client {
        final List<String> calls = new ArrayList<>();
        final Deque<Boolean> connectResults = new ArrayDeque<>();
        final Deque<Boolean> results = new ArrayDeque<>();
        boolean connected;

        @Override
        public boolean connect() {
            calls.add("connect");
            connected = connectResults.isEmpty() || connectResults.removeFirst();
            return connected;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public boolean extCmd(int command, int parameter) {
            calls.add(command + ":" + parameter);
            return results.isEmpty() || results.removeFirst();
        }
    }
}
