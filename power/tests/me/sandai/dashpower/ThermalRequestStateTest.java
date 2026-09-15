/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

/** Plain-JDK checks of production lifecycle decoding and request/retry bookkeeping. */
public final class ThermalRequestStateTest {
    private static int checks;

    private static void check(boolean value, String message) {
        ++checks;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(!ThermalLifecycle.isPresent(0), "absent property");
        check(ThermalLifecycle.isPresent(1), "serial zero present property");
        check(ThermalLifecycle.revision(1) == 0, "serial zero is valid");
        check(ThermalLifecycle.revision(0) == -1, "absent has no revision");
        check(!ThermalLifecycle.isRunning(-1), "unreadable is not running");
        check(ThermalLifecycle.revision((0xffffffffL << 2) | 3) == 0xffffffffL,
                "unsigned serial round trip");
        final long running = (17L << 2) | 3;
        final long restarted = (19L << 2) | 3;
        final long stopped = (20L << 2) | 1;
        ThermalRequestState state = new ThermalRequestState();
        check(!state.shouldSubmit(0, true), "unreadable state blocks forced write");
        state.observe(0);
        check(!state.running() && !state.shouldSubmit(0, false), "absent blocks write");
        state.observe(1);
        check(!state.running(), "present down at serial zero");
        check(!state.claimRetry(state.epoch(), 0), "down blocks retries");
        check(state.observe(running), "running starts epoch");
        long firstEpoch = state.epoch();
        check(state.shouldSubmit(19, false), "new epoch requests policy");
        check(state.submitted(firstEpoch, 19, running), "write records same lifecycle");
        check(state.requested() == 19, "successful request tracked");
        check(!state.observe(running) && state.epoch() == firstEpoch,
                "unchanged revision retains epoch");
        check(!state.shouldSubmit(19, false), "same revision deduplicates");
        check(state.shouldSubmit(19, true), "retry bypasses dedup");
        check(state.observe(restarted), "same running value new revision detected");
        check(state.requested() == -1 && state.shouldSubmit(19, false),
                "new running revision invalidates request");
        check(!state.claimRetry(firstEpoch, 0), "old epoch retry rejected");
        check(!state.submitted(firstEpoch, 19, restarted), "old write cannot become current");
        long epoch = state.epoch();
        check(state.submitted(epoch, 19, restarted), "new revision write accepted");
        check(!state.submitted(epoch, 19, stopped), "restart during write rejects completion");
        check(state.requested() == -1 && !state.shouldSubmit(19, true),
                "down invalidates request and blocks write");
        state.observe(restarted);
        epoch = state.epoch();
        check(!state.submitted(epoch, 19, running), "running revision change during write rejected");
        state.unavailable();
        check(state.requested() == -1 && !state.running(), "read failure invalidates request");
        state.observe(restarted);
        epoch = state.epoch();
        long[] delays = {1000, 3000, 10000};
        int latestPolicy = 19;
        for (int index = 0; index < ThermalRequestState.RETRY_COUNT; ++index) {
            check(ThermalRequestState.retryDelayMillis(index) == delays[index],
                    "retry absolute offset " + index);
            check(state.claimRetry(epoch, index), "bounded retry accepted " + index);
            // A changed foreground policy, then a disabled switch, are recomputed by the caller.
            latestPolicy = index == 0 ? 18 : 0;
            check(state.shouldSubmit(latestPolicy, true), "retry uses latest policy");
            check(state.submitted(epoch, latestPolicy, restarted), "fresh policy recorded");
            check(state.requested() == latestPolicy, "no captured old target replay");
            check(!state.claimRetry(epoch, index), "retry cannot be claimed twice");
        }
        for (int event = 0; event < 10; ++event) {
            state.observe(restarted);
            state.submitted(epoch, event, restarted);
            for (int index = 0; index < ThermalRequestState.RETRY_COUNT; ++index) {
                check(!state.claimRetry(epoch, index), "ordinary events do not replenish budget");
            }
        }
        state.resync();
        check(state.requested() == -1 && state.epoch() != epoch, "manual resync invalidates");
        check(!state.claimRetry(epoch, 0), "manual resync rejects previous callbacks");
        check(state.claimRetry(state.epoch(), 0), "manual resync restarts finite budget");
        try {
            state.claimRetry(state.epoch(), 3);
            throw new AssertionError("unexpected fourth retry");
        } catch (IllegalArgumentException expected) {
            ++checks;
        }
        System.out.println("PASS: " + checks + " thermal request lifecycle checks");
    }
}
