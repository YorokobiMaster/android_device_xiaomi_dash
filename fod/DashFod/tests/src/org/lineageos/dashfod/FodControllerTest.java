/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.dashfod;

import static org.junit.Assert.assertEquals;
import static org.lineageos.dashfod.FodController.Operation.ENROLLMENT;
import static org.lineageos.dashfod.FodController.Operation.GENERIC_AUTH;
import static org.lineageos.dashfod.FodController.Operation.KEYGUARD_AUTH;

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
    public void exactOrderAndDuplicateStartStop() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);

        controller.onStart(ENROLLMENT);
        controller.onStart(ENROLLMENT);
        controller.onStop(ENROLLMENT);
        controller.onStop(ENROLLMENT);

        assertCalls(client, "4:1", "7:0", "1:1", "4:2", "7:0", "1:0");
    }

    @Test
    public void errorThenStopCleansOnce() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(ENROLLMENT);
        client.calls.clear();

        controller.onError(ENROLLMENT);
        controller.onStop(ENROLLMENT);

        assertCalls(client, "4:2", "7:0", "1:0");
    }

    @Test
    public void vendorReturnFailureStillAttemptsFullCleanup() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(ENROLLMENT);
        client.calls.clear();
        client.results.add(false);

        controller.onStop(ENROLLMENT);

        assertCalls(client, "4:2", "7:0", "1:0");
    }

    @Test
    public void partialStartFailureCleansUp() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        client.results.add(true);
        client.results.add(false);

        controller.onStart(ENROLLMENT);

        assertCalls(client, "4:1", "7:0", "4:2", "7:0", "1:0");
    }

    @Test
    public void failedErrorCleanupStopRetriesThenLaterStopIsDuplicate() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(ENROLLMENT);
        client.calls.clear();
        client.results.add(false);
        client.results.add(true);
        client.results.add(true);

        controller.onError(ENROLLMENT);
        controller.onStop(ENROLLMENT);
        controller.onStop(ENROLLMENT);

        assertCalls(client, "4:2", "7:0", "1:0", "4:2", "7:0", "1:0");
    }

    @Test
    public void failedStartupCleanupStartCleansBeforeArming() {
        FakeClient client = new FakeClient();
        client.results.add(false);
        client.results.add(true);
        client.results.add(true);
        FodController controller = new FodController(client, message -> {});
        controller.onStartup();
        client.calls.clear();

        controller.onStart(ENROLLMENT);

        assertCalls(client, "4:2", "7:0", "1:0", "4:1", "7:0", "1:1");
    }

    @Test
    public void partialStartCleanupFailureTerminalEventRetries() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        client.results.add(true);
        client.results.add(false);
        client.results.add(false);
        client.results.add(true);
        client.results.add(true);

        controller.onStart(ENROLLMENT);
        controller.onStop(ENROLLMENT);

        assertCalls(client, "4:1", "7:0", "4:2", "7:0", "1:0",
                "4:2", "7:0", "1:0");
    }

    @Test
    public void failedStartupConnectStartReconnectsCleansThenArms() {
        FakeClient client = new FakeClient();
        client.connectResults.add(false);
        client.connectResults.add(true);
        FodController controller = new FodController(client, message -> {});
        controller.onStartup();
        client.calls.clear();

        controller.onStart(ENROLLMENT);

        assertCalls(client, "connect", "4:2", "7:0", "1:0",
                "4:1", "7:0", "1:1");
    }

    @Test
    public void startupOnlyCleansUp() {
        FakeClient client = new FakeClient();
        new FodController(client, message -> {}).onStartup();

        assertCalls(client, "connect", "4:2", "7:0", "1:0");
    }

    @Test
    public void vendorDeathReconnectsCleansWithoutReplayAndFreshStartArms() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(ENROLLMENT);
        client.calls.clear();

        controller.onVendorDeath();
        controller.onStart(ENROLLMENT);

        assertCalls(client, "connect", "4:2", "7:0", "1:0",
                "4:1", "7:0", "1:1");
    }

    @Test
    public void keyguardFailedThenSucceededCleansOnce() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);

        controller.onStart(KEYGUARD_AUTH);
        controller.onFailed(KEYGUARD_AUTH);
        controller.onSucceeded(KEYGUARD_AUTH);
        controller.onStop(KEYGUARD_AUTH);

        assertCalls(client, "4:3", "7:1", "18:1", "1:1",
                "4:3", "7:0", "4:4", "7:0", "1:0");
    }

    @Test
    public void genericSucceededUsesClassFourAndCleansOnce() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);

        controller.onStart(GENERIC_AUTH);
        controller.onSucceeded(GENERIC_AUTH);

        assertCalls(client, "4:3", "7:0", "18:4", "1:1",
                "4:4", "7:0", "1:0");
    }

    @Test
    public void genericFailedThenSucceededStaysActiveAndCleansOnce() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);

        controller.onStart(GENERIC_AUTH);
        controller.onFailed(GENERIC_AUTH);
        controller.onSucceeded(GENERIC_AUTH);
        controller.onStop(GENERIC_AUTH);

        assertCalls(client, "4:3", "7:0", "18:4", "1:1",
                "4:3", "7:0", "4:4", "7:0", "1:0");
    }

    @Test
    public void genericStopThenLateTerminalsCleanOnce() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(GENERIC_AUTH);
        client.calls.clear();

        controller.onStop(GENERIC_AUTH);
        controller.onError(GENERIC_AUTH);
        controller.onSucceeded(GENERIC_AUTH);

        assertCalls(client, "4:4", "7:0", "1:0");
    }

    @Test
    public void genericErrorThenStopCleansOnce() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(GENERIC_AUTH);
        client.calls.clear();

        controller.onError(GENERIC_AUTH);
        controller.onStop(GENERIC_AUTH);

        assertCalls(client, "4:4", "7:0", "1:0");
    }

    @Test
    public void keyguardErrorThenStopCleansOnce() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(KEYGUARD_AUTH);
        client.calls.clear();

        controller.onError(KEYGUARD_AUTH);
        controller.onStop(KEYGUARD_AUTH);

        assertCalls(client, "4:4", "7:0", "1:0");
    }

    @Test
    public void keyguardStopThenLateTerminalsCleanOnce() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(KEYGUARD_AUTH);
        client.calls.clear();

        controller.onStop(KEYGUARD_AUTH);
        controller.onError(KEYGUARD_AUTH);
        controller.onSucceeded(KEYGUARD_AUTH);

        assertCalls(client, "4:4", "7:0", "1:0");
    }

    @Test
    public void crossOperationCallbacksDoNotDisarmEnrollment() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);

        controller.onStart(ENROLLMENT);
        controller.onStart(KEYGUARD_AUTH);
        controller.onStart(GENERIC_AUTH);
        controller.onFailed(KEYGUARD_AUTH);
        controller.onFailed(GENERIC_AUTH);
        controller.onSucceeded(KEYGUARD_AUTH);
        controller.onSucceeded(GENERIC_AUTH);
        controller.onStop(KEYGUARD_AUTH);
        controller.onStop(GENERIC_AUTH);
        controller.onStop(ENROLLMENT);

        assertCalls(client, "4:1", "7:0", "1:1", "4:2", "7:0", "1:0");
    }

    @Test
    public void genericCallbacksCannotDisarmKeyguard() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);

        controller.onStart(KEYGUARD_AUTH);
        controller.onFailed(GENERIC_AUTH);
        controller.onSucceeded(GENERIC_AUTH);
        controller.onStop(GENERIC_AUTH);
        controller.onStop(KEYGUARD_AUTH);

        assertCalls(client, "4:3", "7:1", "18:1", "1:1",
                "4:4", "7:0", "1:0");
    }

    @Test
    public void keyguardCallbacksCannotDisarmGeneric() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);

        controller.onStart(GENERIC_AUTH);
        controller.onFailed(KEYGUARD_AUTH);
        controller.onSucceeded(KEYGUARD_AUTH);
        controller.onStop(KEYGUARD_AUTH);
        controller.onStop(GENERIC_AUTH);

        assertCalls(client, "4:3", "7:0", "18:4", "1:1",
                "4:4", "7:0", "1:0");
    }

    @Test
    public void partialGenericStartUsesAuthCleanup() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        client.results.add(true);
        client.results.add(true);
        client.results.add(true);
        client.results.add(false);

        controller.onStart(GENERIC_AUTH);

        assertCalls(client, "4:3", "7:0", "18:4", "1:1", "4:4", "7:0", "1:0");
    }

    @Test
    public void partialKeyguardStartUsesAuthCleanup() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        client.results.add(true);
        client.results.add(true);
        client.results.add(true);
        client.results.add(false);

        controller.onStart(KEYGUARD_AUTH);

        assertCalls(client, "4:3", "7:1", "18:1", "1:1", "4:4", "7:0", "1:0");
    }

    @Test
    public void failedMatchCleanupDebtRetriesOnStop() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(KEYGUARD_AUTH);
        client.calls.clear();
        client.results.add(true);
        client.results.add(false);
        client.results.add(false);
        client.results.add(true);
        client.results.add(true);

        controller.onFailed(KEYGUARD_AUTH);
        controller.onStop(KEYGUARD_AUTH);

        assertCalls(client, "4:3", "7:0", "4:4", "7:0", "1:0",
                "4:4", "7:0", "1:0");
    }

    @Test
    public void genericFailedMatchCleanupDebtRetriesOnStop() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(GENERIC_AUTH);
        client.calls.clear();
        client.results.add(true);
        client.results.add(false);
        client.results.add(false);
        client.results.add(true);
        client.results.add(true);

        controller.onFailed(GENERIC_AUTH);
        controller.onStop(GENERIC_AUTH);

        assertCalls(client, "4:3", "7:0", "4:4", "7:0", "1:0",
                "4:4", "7:0", "1:0");
    }

    @Test
    public void authDebtBlocksStartUntilRetrySucceedsThenProcessesSameStart() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(KEYGUARD_AUTH);
        client.results.add(true);
        client.results.add(false);
        client.results.add(false);
        client.results.add(true);
        client.results.add(true);
        controller.onFailed(KEYGUARD_AUTH);
        client.calls.clear();
        client.results.add(false);
        client.results.add(true);
        client.results.add(true);

        controller.onStart(ENROLLMENT);
        controller.onStart(ENROLLMENT);

        assertCalls(client, "4:4", "7:0", "1:0", "4:4", "7:0", "1:0",
                "4:1", "7:0", "1:1");
    }

    @Test
    public void genericDebtRetriesBeforeFailedEventThenDropsAsDuplicate() {
        FakeClient client = new FakeClient();
        client.results.add(false);
        client.results.add(true);
        client.results.add(true);
        FodController controller = new FodController(client, message -> {});
        controller.onStartup();
        client.calls.clear();

        controller.onFailed(KEYGUARD_AUTH);

        assertCalls(client, "4:2", "7:0", "1:0");
    }

    @Test
    public void vendorDeathDiscardsAuthDebtAndUsesGenericDisarm() {
        FakeClient client = new FakeClient();
        FodController controller = started(client);
        controller.onStart(KEYGUARD_AUTH);
        client.results.add(true);
        client.results.add(false);
        client.results.add(false);
        client.results.add(true);
        client.results.add(true);
        controller.onFailed(KEYGUARD_AUTH);
        client.calls.clear();

        controller.onVendorDeath();
        controller.onStart(KEYGUARD_AUTH);

        assertCalls(client, "connect", "4:2", "7:0", "1:0",
                "4:3", "7:1", "18:1", "1:1");
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
