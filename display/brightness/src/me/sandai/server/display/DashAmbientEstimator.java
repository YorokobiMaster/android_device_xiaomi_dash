/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import android.util.Spline;

import java.util.ArrayList;

/** The assist ring/filter and source arbitration for dash's stock dual-ALS policy. */
final class DashAmbientEstimator {
    static final long NONE = Long.MAX_VALUE;
    static final int INVALID = 0;
    static final int MAIN = 1;
    static final int ASSIST = 2;
    private static final int RATE = 250;
    private static final long TORCH_COOLDOWN = 1800;
    private static final Spline BRIGHT = Spline.createLinearSpline(
            new float[]{0, 3, 4, 6, 7, 8, 9, 15, 30, 600, 1677.4f, 2000, 2500, 3000, 8500},
            new float[]{2, 6, 8.51f, 13.66f, 14.84f, 15.46f, 17.22f, 28.44f, 160,
                    992.58f, 3000, 4038.77f, 5738.77f, 7538.77f, 13038.77f});
    private static final Spline SMALL = Spline.createLinearSpline(
            new float[]{0, 3, 4, 6, 7, 8, 9, 15, 30, 600, 1677.4f, 2000, 2500, 3000, 8500},
            new float[]{2, 6, 8.51f, 13.66f, 14.84f, 15.46f, 17.22f, 23.064001f, 108,
                    835.54803f, 2470.96f, 3223.2622f, 4443.262f, 5738.77f, 11223.262f});
    private static final Spline DARK = Spline.createLinearSpline(
            new float[]{0, 1, 7, 8, 9, 10, 11, 12, 13, 14, 15, 30, 199, 200,
                    360, 600, 1010.47f, 3000, 20000},
            new float[]{0, .32f, 3.53f, 3.8f, 4.1f, 4.45f, 4.82f, 5.2f, 5.58f,
                    6.05f, 6.9f, 15.83f, 34.31f, 41.39f, 45.34f, 216.99f, 600,
                    2052.56f, 10011.96f});

    record MainState(boolean valid, float fast, float slow, float bright, float small, float dark,
            long nextBright, long nextDark) {}
    record Decision(int event, float lux, boolean updateLux, boolean updateBrightness,
            float thresholdLux, String reason) {
        static Decision none() { return new Decision(INVALID, Float.NaN, false, false,
                Float.NaN, null); }
    }

    private final History mAssistHistory = new History();
    private long mStart, mAssistNext = NONE, mStepUntil = NONE;
    private float mNonUi;
    private boolean mTorch;
    private long mTorchClosed = -1, mCooldownWake = NONE;
    private int mSelected = INVALID, mDroppedAssistRejected, mDroppedAssistTorch,
            mDroppedAssistCooldown;
    private long mLastSwitchTime = -1;
    private String mLastSwitchReason;
    private float mAssistFastLux = -1, mAssistSlowLux = -1, mAssistBright = -1,
            mAssistSmall = -1, mAssistDark = -1;
    private boolean mAssistValid;
    private final ArrayList<String> mTrace = new ArrayList<>();

    void start(long time) {
        mAssistHistory.samples.clear();
        mStart = time;
        mAssistNext = NONE;
        mNonUi = 0;
        mStepUntil = NONE;
        mTorch = false;
        mTorchClosed = -1;
        mCooldownWake = NONE;
        mSelected = INVALID;
        mLastSwitchTime = -1;
        mLastSwitchReason = null;
        mAssistFastLux = mAssistSlowLux = mAssistBright = mAssistSmall = mAssistDark = -1;
        mAssistValid = false;
        mDroppedAssistRejected = mDroppedAssistTorch = mDroppedAssistCooldown = 0;
        mTrace.clear();
    }

    void assist(long time, float lux) {
        mAssistNext = NONE;
        if (!assistAllowed(time)) {
            if (mTorch) mDroppedAssistTorch++; else mDroppedAssistCooldown++;
            return;
        }
        if (!mAssistHistory.push(time, lux)) { mDroppedAssistRejected++; return; }
        trace(time, "assist sample=" + lux);
    }
    boolean assistAllowed(long time) {
        return !mTorch && (mTorchClosed < 0 || time - mTorchClosed > TORCH_COOLDOWN);
    }
    long cooldownUntil() { return mTorchClosed < 0 ? -1 : mTorchClosed + TORCH_COOLDOWN + 1; }

    void torch(long time, boolean enabled) {
        if (enabled == mTorch) return;
        trace(time, "torch=" + (enabled ? "ON" : "OFF"));
        if (enabled) {
            mAssistHistory.samples.clear();
            mAssistValid = false;
            mAssistNext = NONE;
            mCooldownWake = NONE;
        } else {
            mTorchClosed = time;
            mCooldownWake = cooldownUntil();
        }
        mTorch = enabled;
    }
    void nonUi(long time, float value) {
        if (value == mNonUi) return;
        mNonUi = value;
        if (value == 0) trace(time, "pocket exit");
        else trace(time, "pocket enter");
    }
    void step(long time) { mStepUntil = time + 1300; trace(time, "step window"); }

    /** Called after ABC computes the main estimate and its own deadlines. */
    Decision onMain(long time, MainState main, boolean initial) {
        if (!main.valid()) return Decision.none();
        if (initial || mSelected == INVALID) {
            if (mSelected == INVALID || mAssistFastLux < 0 || main.fast() >= mAssistFastLux) {
                select(MAIN, time, "main-init");
                return new Decision(MAIN, main.fast(), true, false, main.fast(), "main-init");
            }
            return new Decision(MAIN, main.fast(), false, false, main.fast(),
                    "main-init-assist");
        }
        float brightThreshold = main.fast() < main.bright() ? main.small() : main.bright();
        boolean brightenDue = main.slow() >= brightThreshold && main.fast() >= brightThreshold
                && main.nextBright() <= time;
        // The stock slow gate blocks a dark transition while MS is still at the effective bright
        // threshold, even when MF has already fallen below the dark threshold.
        long darkDeadline = main.nextDark()
                + (mStepUntil != NONE && mStepUntil > time ? 3000 : 0);
        boolean darkenDue = main.slow() < brightThreshold && main.fast() <= main.dark()
                && darkDeadline <= time;
        if (brightenDue) {
            if (mSelected == MAIN || main.fast() >= mAssistFastLux) {
                select(MAIN, time, "main-brighten");
                return new Decision(MAIN, main.fast(), true, true, main.fast(), "main-brighten");
            }
            return new Decision(MAIN, main.fast(), false, false, main.fast(),
                    "main-brighten-blocked");
        }
        if (darkenDue) {
            if (main.fast() >= mAssistFastLux) {
                select(MAIN, time, "main-darken");
                return new Decision(MAIN, main.fast(), true, true, main.fast(), "main-darken");
            }
            if (mSelected == MAIN) {
                select(ASSIST, time, "main-dark-below-assist");
                return new Decision(MAIN, mAssistFastLux, true, true, main.fast(),
                        "main-dark-below-assist");
            }
            return new Decision(MAIN, main.fast(), false, false, main.fast(), "main-dark-assist");
        }
        if (main.fast() <= main.dark() && darkDeadline <= time
                && main.slow() >= brightThreshold) {
            return new Decision(INVALID, Float.NaN, false, false, main.fast(),
                    "main-dark-slow-gate");
        }
        return Decision.none();
    }

    /** Re-evaluates assist and returns its independent next deadline. */
    Decision onAssist(long time, MainState main) {
        mAssistHistory.prune(time - 5000);
        if (mAssistHistory.samples.isEmpty()) { mAssistNext = NONE; return Decision.none(); }
        mAssistFastLux = mAssistHistory.average(time, 1000);
        mAssistSlowLux = mAssistHistory.average(time, 1500);
        if (!mAssistValid) {
            mAssistValid = true;
            updateAssistThresholds();
            if (mSelected == MAIN && mAssistSlowLux > main.bright()
                    && mAssistFastLux > main.bright()) {
                select(ASSIST, time, "assist-init");
                Decision d = new Decision(ASSIST, mAssistFastLux, true, true, Float.NaN,
                        "assist-init");
                scheduleAssist(time);
                return d;
            }
        }
        float threshold = mAssistFastLux < mAssistBright ? mAssistSmall : mAssistBright;
        long bright = mAssistHistory.transition(time, threshold, true,
                mAssistFastLux < mAssistBright ? 5000 : 1000);
        long dark = mAssistHistory.transition(time, mAssistDark, false, 1000);
        boolean due = (mAssistSlowLux >= threshold && mAssistFastLux >= threshold && bright <= time)
                || (mAssistFastLux <= mAssistDark && dark <= time);
        Decision decision = Decision.none();
        if (due) {
            if (mSelected == MAIN && mAssistSlowLux > main.bright()
                    && mAssistFastLux > main.bright()) {
                select(ASSIST, time, "assist-overtake");
                decision = new Decision(ASSIST, mAssistFastLux, true, true, Float.NaN,
                        "assist-overtake");
            } else if (mSelected == ASSIST) {
                if (mAssistFastLux > main.fast()) {
                    decision = new Decision(ASSIST, mAssistFastLux, true, true, Float.NaN,
                            "assist-stays");
                } else {
                    select(MAIN, time, "main-regain");
                    decision = new Decision(ASSIST, main.fast(), true, true, main.fast(),
                            "main-regain");
                }
            }
            if (decision.reason() == null) {
                decision = new Decision(INVALID, Float.NaN, false, false, Float.NaN,
                        "assist-due-no-switch");
            }
            // Stock re-anchors assist hysteresis only after this channel's transition is due.
            updateAssistThresholds();
        }
        scheduleAssist(time);
        return decision;
    }
    private void updateAssistThresholds() {
        mAssistBright = brightThreshold(mAssistFastLux);
        mAssistSmall = smallThreshold(mAssistFastLux);
        mAssistDark = DARK.interpolate(mAssistFastLux);
    }
    private void scheduleAssist(long time) {
        float threshold = mAssistFastLux < mAssistBright ? mAssistSmall : mAssistBright;
        long bright = mAssistHistory.transition(time, threshold, true,
                mAssistFastLux < mAssistBright ? 5000 : 1000);
        long dark = mAssistHistory.transition(time, mAssistDark, false, 1000);
        mAssistNext = Math.min(bright, dark);
        if (mAssistNext <= time) mAssistNext = time + RATE;
    }

    long assistNext() { return mAssistNext; }
    int selected() { return mSelected; }
    int droppedAssistRejected() { return mDroppedAssistRejected; }
    int droppedAssistTorch() { return mDroppedAssistTorch; }
    int droppedAssistCooldown() { return mDroppedAssistCooldown; }
    void traceEvent(long time, String event) { trace(time, event); }
    void traceDecision(long time, Decision decision, MainState main) {
        if (decision.reason() == null) return;
        trace(time, "decision event=" + selectedName(decision.event())
                + " selected=" + selectedName(mSelected)
                + " updateLux=" + decision.updateLux()
                + " updateBrightness=" + decision.updateBrightness()
                + " lux=" + decision.lux()
                + " reason=" + decision.reason()
                + " MF=" + main.fast() + " MS=" + main.slow()
                + " MB=" + main.bright() + " MSB=" + main.small()
                + " MD=" + main.dark() + " nextMB=" + main.nextBright()
                + " nextMD=" + main.nextDark()
                + " AF=" + mAssistFastLux + " AS=" + mAssistSlowLux
                + " AB=" + mAssistBright + " ASB=" + mAssistSmall
                + " AD=" + mAssistDark + " nextA=" + mAssistNext);
    }
    private void select(int channel, long time, String reason) {
        if (mSelected == channel) return;
        mSelected = channel;
        mLastSwitchTime = time;
        mLastSwitchReason = reason;
        trace(time, "select " + (channel == MAIN ? "MAIN" : "ASSIST") + " (" + reason + ")");
    }
    private void trace(long time, String event) {
        if (mTrace.size() >= 400) mTrace.remove(0);
        mTrace.add(time + " " + event);
    }
    private static String selectedName(int selected) {
        if (selected == MAIN) return "MAIN";
        if (selected == ASSIST) return "ASSIST";
        return "INVALID";
    }

    static long eventUptime(long eventNanos, long startNanos, long nowNanos, long nowUptime) {
        if (eventNanos < startNanos || eventNanos > nowNanos) return -1;
        return nowUptime - (nowNanos - eventNanos) / 1000000L;
    }
    static float brightThreshold(float lux) { return lux > 7180 ? lux + 1 : BRIGHT.interpolate(lux); }
    static float smallThreshold(float lux) { return lux > 7180 ? lux + 1 : SMALL.interpolate(lux); }
    static float darkThreshold(float lux) { return DARK.interpolate(lux); }

    record Snapshot(long start, int selected, long lastSwitchTime, String lastSwitchReason,
            boolean assistValid, float assistFast, float assistSlow, float assistBright,
            float assistSmall, float assistDark, long assistNext, long assistLastEvent,
            boolean torch, long torchClosed, long cooldownUntil, int droppedAssistRejected,
            int droppedAssistTorch, int droppedAssistCooldown, String[] trace) {}
    Snapshot snapshot() {
        long last = mAssistHistory.samples.isEmpty() ? -1
                : mAssistHistory.samples.get(mAssistHistory.samples.size() - 1).time;
        return new Snapshot(mStart, mSelected, mLastSwitchTime, mLastSwitchReason, mAssistValid,
                mAssistFastLux, mAssistSlowLux, mAssistBright, mAssistSmall, mAssistDark,
                mAssistNext, last, mTorch, mTorchClosed, cooldownUntil(),
                mDroppedAssistRejected, mDroppedAssistTorch, mDroppedAssistCooldown,
                mTrace.toArray(new String[0]));
    }

    /** Stock AmbientLightRingBuffer arithmetic, including retained last sample and +100 ms. */
    static final class History {
        final ArrayList<Sample> samples = new ArrayList<>();
        boolean push(long time, float lux) {
            if (!Float.isFinite(lux) || lux < 0
                    || (!samples.isEmpty() && time < samples.get(samples.size() - 1).time)) return false;
            prune(time - 5000);
            samples.add(new Sample(time, lux));
            return true;
        }
        void prune(long horizon) {
            while (samples.size() > 1 && samples.get(1).time <= horizon) samples.remove(0);
            if (!samples.isEmpty() && samples.get(0).time < horizon) samples.get(0).time = horizon;
        }
        float average(long now, long horizon) {
            if (samples.isEmpty()) return -1;
            int first = 0;
            long start = now - horizon;
            while (first + 1 < samples.size() && samples.get(first + 1).time <= start) first++;
            float sum = 0, weights = 0;
            long end = 100;
            for (int i = samples.size() - 1; i >= first; i--) {
                Sample sample = samples.get(i);
                long begin = (i == first ? Math.max(sample.time, start) : sample.time) - now;
                float weight = integral(end) - integral(begin);
                sum += sample.lux * weight;
                weights += weight;
                end = begin;
            }
            return sum / weights;
        }
        long transition(long now, float threshold, boolean bright, long debounce) {
            long earliest = now;
            for (int i = samples.size() - 1; i >= 0; i--) {
                Sample sample = samples.get(i);
                if (bright ? sample.lux <= threshold : sample.lux >= threshold) break;
                earliest = sample.time;
            }
            return earliest + debounce;
        }
        private static float integral(long x) { return x * (x * .5f + 1500); }
    }
    private static final class Sample {
        long time;
        final float lux;
        Sample(long time, float lux) { this.time = time; this.lux = lux; }
    }
}
