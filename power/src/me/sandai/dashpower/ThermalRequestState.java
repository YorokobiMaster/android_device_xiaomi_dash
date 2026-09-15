/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

/** Worker-owned request bookkeeping; callers must serialize access, including diagnostic reads. */
final class ThermalRequestState {
    static final int RETRY_COUNT = 3;
    private long lifecycle = -1;
    private long epoch;
    private int requested = -1;
    private int claimedRetries;

    /** Sample immediately before dedup and after writing. True means old work was invalidated. */
    boolean observe(long sample) {
        if (lifecycle == sample) return false;
        lifecycle = sample;
        resync();
        return true;
    }

    void unavailable() {
        observe(-1);
    }

    /** Manual resync alone may replenish retries without a new property revision. */
    void resync() {
        ++epoch;
        requested = -1;
        claimedRetries = 0;
    }

    long epoch() {
        return epoch;
    }

    boolean running() {
        return ThermalLifecycle.isRunning(lifecycle);
    }

    int requested() {
        return requested;
    }

    boolean shouldSubmit(int target, boolean force) {
        return running() && (force || requested != target);
    }

    /** afterSample must be a fresh native sample, not the pre-write token. */
    boolean submitted(long writeEpoch, int target, long afterSample) {
        observe(afterSample);
        if (writeEpoch != epoch || !running()) return false;
        requested = target;
        return true;
    }

    /** Absolute offsets from the new running epoch's scheduling time, not chained delays. */
    static long retryDelayMillis(int index) {
        switch (index) {
            case 0: return 1000;
            case 1: return 3000;
            case 2: return 10000;
            default: throw new IllegalArgumentException("Invalid thermal retry index " + index);
        }
    }

    /**
     * Schedule each index once on a new running epoch/manual resync only. On callback: freshly
     * observe lifecycle, claim, then recompute latest policy and force submission. Never capture
     * a package or target. Ordinary policy events must not resync or replenish this budget.
     */
    boolean claimRetry(long retryEpoch, int index) {
        if (index < 0 || index >= RETRY_COUNT) {
            throw new IllegalArgumentException("Invalid thermal retry index " + index);
        }
        int bit = 1 << index;
        if (retryEpoch != epoch || !running() || (claimedRetries & bit) != 0) return false;
        claimedRetries |= bit;
        return true;
    }
}
