/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;

/** Plain-JDK lifecycle tests; run using tools/test.sh. */
public final class PowerPolicyTest {
    private static int checks;
    static void check(boolean result, String message) {
        ++checks;
        if (!result) throw new AssertionError(message);
    }
    static final class Fixture implements PowerPolicy.Worker, PowerPolicy.Transport {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final ArrayList<Runnable> timers = new ArrayList<>();
        final ArrayList<Integer> hints = new ArrayList<>();
        final ArrayList<Integer> durations = new ArrayList<>();
        final HashSet<Integer> owned = new HashSet<>();
        final PowerPolicy policy = new PowerPolicy(this, this);
        int next;
        boolean fail;
        Runnable duringAcquire;
        public void execute(Runnable task) { tasks.add(task); }
        public void after(int ms, Runnable task) { timers.add(task); }
        public int acquire(int hint, int ms) throws Exception {
            if (fail) throw new Exception("HAL unavailable");
            hints.add(hint);
            durations.add(ms);
            owned.add(++next);
            if (duringAcquire != null) duringAcquire.run();
            return next;
        }
        public void release(int handle) {
            check(owned.remove(handle), "release only a live owned handle");
        }
        void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
        void ready() { policy.setEnabled(true); policy.state(true, false); drain(); }
    }

    public static void main(String[] args) {
        Fixture f = new Fixture();
        f.policy.launch(true); f.policy.fling(0, 100); f.drain();
        check(f.hints.isEmpty(), "initial state fails closed");
        f.ready();
        f.policy.launch(true); f.policy.launch(true); f.drain();
        check(f.hints.size() == 1 && f.hints.get(0) == 21, "duplicate launch is idempotent");
        check(f.durations.get(0) == 10000, "launch vendor timeout finite");
        f.policy.launch(false); f.drain();
        check(f.owned.isEmpty(), "launch end releases");
        f.timers.get(0).run();
        check(f.owned.isEmpty(), "stale timeout harmless");

        f = new Fixture(); f.ready();
        f.policy.launch(true); f.policy.launch(false); f.drain();
        check(f.hints.isEmpty(), "queued start canceled before IPC");
        f.policy.fling(0, Integer.MAX_VALUE); f.drain();
        check(f.hints.get(0) == 900 && f.durations.get(0) == 5160, "fling clamp cannot overflow");
        f.policy.fling(0, 0); f.policy.fling(0, -1); f.policy.fling(1, 100); f.drain();
        check(f.hints.size() == 1, "invalid/other-display fling ignored");
        f.policy.fling(0, 200); f.drain();
        f.timers.get(0).run();
        check(f.owned.size() == 1, "old fling timeout cannot release replacement");
        f.timers.get(1).run();
        check(f.owned.isEmpty(), "current fling timeout releases");

        f = new Fixture(); f.ready();
        f.policy.launch(true); f.policy.fling(0, 300); f.drain();
        f.policy.state(false, false); f.policy.state(true, false); f.drain();
        check(f.owned.isEmpty() && f.hints.size() == 2, "sleep/wake releases without replay");
        f.policy.launch(true); f.policy.state(true, true); f.drain();
        check(f.hints.size() == 2, "adaptive saver cancels queued launch");
        f.policy.fling(0, 100); f.policy.launch(true); f.drain();
        check(f.hints.size() == 2, "saver filters both enhancements");
        f.policy.state(true, false); f.drain();
        check(f.hints.size() == 2, "leaving saver does not replay");
        f.policy.launch(true); f.drain();
        f.policy.setEnabled(false); f.drain();
        check(f.owned.isEmpty(), "runtime opt-out releases");

        f = new Fixture(); f.ready();
        f.policy.launch(true); f.drain();
        f.policy.disconnected(); f.drain();
        check(f.owned.isEmpty(), "death clears owned state");
        f.fail = true; f.policy.launch(true); f.drain();
        f.fail = false; f.drain();
        check(f.hints.size() == 1, "recovery does not replay failed event");
        f.policy.launch(true); f.drain();
        check(f.hints.size() == 2, "fresh event can recover");

        final Fixture race = new Fixture(); race.ready();
        race.duringAcquire = () -> race.policy.state(false, false);
        race.policy.launch(true); race.drain();
        check(race.owned.isEmpty(), "cancel during IPC releases returned handle immediately");
        System.out.println("PASS: " + checks + " lifecycle checks");
    }
}
