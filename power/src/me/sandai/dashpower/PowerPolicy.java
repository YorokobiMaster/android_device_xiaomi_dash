/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

/** Device policy. Ingress never blocks on vendor IPC; Worker must be serialized. */
final class PowerPolicy {
    static final int LAUNCH_HINT = 21;
    static final int FLING_HINT = 900;
    static final int LAUNCH_MS = 10_000;
    static final int MAX_FLING_MS = 5_000;
    interface Worker {
        void execute(Runnable task);
        void after(int durationMs, Runnable task);
    }
    interface Transport {
        int acquire(int hint, int durationMs) throws Exception;
        void release(int handle) throws Exception;
    }
    private final Worker worker;
    private final Transport transport;
    // Ingress state is guarded by this. Start fail-closed until PMS seeds state.
    private boolean enabled;
    private boolean interactive;
    private boolean blocked = true;
    private boolean launchActive;
    private long launchGeneration;
    private long flingGeneration;
    // Owned only by the serialized worker; no foreign/global hint handles.
    private int launchHandle;
    private int flingHandle;

    PowerPolicy(Worker worker, Transport transport) {
        this.worker = worker;
        this.transport = transport;
    }

    synchronized void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        invalidate();
    }

    synchronized void state(boolean awake, boolean launchDisabled) {
        if (interactive == awake && blocked == launchDisabled) return;
        interactive = awake;
        blocked = launchDisabled;
        invalidate(); // Never replay requests when waking or leaving saver.
    }

    synchronized void launch(boolean start) {
        if (!start) {
            launchActive = false;
            ++launchGeneration;
            worker.execute(() -> release(true));
        } else if (!launchActive && allowed()) {
            launchActive = true;
            final long generation = ++launchGeneration;
            worker.execute(() -> acquire(true, generation, LAUNCH_MS));
        }
    }

    synchronized void fling(int displayId, int durationMs) {
        // dash has one built-in display; do not boost off-device presentation gestures.
        if (displayId != 0 || durationMs <= 0 || !allowed()) return;
        final int duration = Math.min(durationMs, MAX_FLING_MS) + 160;
        final long generation = ++flingGeneration;
        worker.execute(() -> acquire(false, generation, duration));
    }

    synchronized void disconnected() {
        invalidate();
    }

    private boolean allowed() {
        return enabled && interactive && !blocked;
    }

    private synchronized boolean current(boolean launch, long generation) {
        return allowed() && generation == (launch ? launchGeneration : flingGeneration);
    }

    private void invalidate() {
        launchActive = false;
        ++launchGeneration;
        ++flingGeneration;
        worker.execute(() -> { release(true); release(false); });
    }

    private void acquire(boolean launch, long generation, int duration) {
        if (!current(launch, generation)) return;
        release(launch);
        try {
            final int handle = transport.acquire(launch ? LAUNCH_HINT : FLING_HINT, duration);
            if (handle <= 0) return;
            if (launch) launchHandle = handle;
            else flingHandle = handle;
            // A screen/policy/disable event can arrive while acquisition is in flight.
            if (!current(launch, generation)) {
                release(launch);
                return;
            }
            worker.after(duration, () -> {
                if (current(launch, generation)) release(launch);
            });
        } catch (Exception e) {
            disconnected();
        }
    }

    private void release(boolean launch) {
        final int handle = launch ? launchHandle : flingHandle;
        if (launch) launchHandle = 0;
        else flingHandle = 0;
        if (handle > 0) {
            try {
                transport.release(handle);
            } catch (Exception ignored) {
                // The finite backend timeout still applies. Never retry a possibly reused handle.
            }
        }
    }
}
