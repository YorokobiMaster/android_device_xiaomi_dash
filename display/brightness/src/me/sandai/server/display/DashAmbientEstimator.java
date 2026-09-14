/*
 * Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import android.util.Spline;

import java.util.ArrayList;

/** Stock dash 305 ambient arithmetic; no sensor registrations or timers. */
final class DashAmbientEstimator {
    static final long NONE = Long.MAX_VALUE;
    static final int MAIN = 1;
    static final int ASSIST = 2;
    // Stock display dump: 250 ms sampling, 1500/1000 ms windows, 1000/5000 ms debounce.
    private static final int RATE = 250;
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

    private final Channel mMain = new Channel();
    private final Channel mAssist = new Channel();
    private long mStart;
    private long mPocketExit = -1;
    private float mNonUi;
    private long mStepUntil = NONE;
    private boolean mTorch;
    private long mTorchClosed = -1;
    private int mSelected;
    private float mLux = Float.NaN;
    private float mPendingLux = Float.NaN;
    private boolean mFastRamp;
    private float mFastRampTarget;
    private long mFastRampUntil = NONE;

    void start(long time) {
        mMain.clear();
        mAssist.clear();
        mStart = time;
        mPocketExit = -1;
        mNonUi = 0;
        mStepUntil = NONE;
        mTorch = false;
        mTorchClosed = -1;
        mSelected = 0;
        mLux = mPendingLux = Float.NaN;
        mFastRamp = false;
        mFastRampUntil = NONE;
    }

    void primary(long time, float lux) {
        if (!mMain.history.push(time, lux)) return;
        updateMain(time);
    }

    void assist(long time, float lux) {
        // Stock cancels the assist evaluation before rejecting torch-contaminated events.
        mAssist.next = NONE;
        if (!assistAllowed(time) || !mAssist.history.push(time, lux)) return;
        updateAssist(time);
    }

    void torch(long time, boolean enabled) {
        if (mTorch && !enabled) mTorchClosed = time;
        mTorch = enabled;
    }

    boolean assistAllowed(long time) {
        return !mTorch && (mTorchClosed < 0 || time - mTorchClosed > 1800);
    }

    void nonUi(long time, float value) {
        if (value != mNonUi) {
            mNonUi = value;
            if (value == 0) mPocketExit = time;
        }
    }

    void step(long time) {
        // framework-ext-res: debounce=3000, effectiveness=1300; product enables step.
        mStepUntil = time + 1300;
    }

    private boolean fastWindow(long time) {
        return time <= mStart + 3000 || (mPocketExit >= 0 && time <= mPocketExit + 2000);
    }

    boolean fastRamp(long time, float currentBrightness, float targetBrightness,
            float currentNits, float targetNits) {
        if (time >= mFastRampUntil) {
            mFastRamp = false;
            mFastRampUntil = NONE;
        }
        if (!Float.isFinite(currentNits) || !Float.isFinite(targetNits)
                || currentNits < 0 || targetNits < 0) return false;
        // AutomaticBrightnessControllerImpl.shouldUseFastRate, SDR channel only.
        if (!mFastRamp && fastWindow(time) && targetNits >= currentNits * .6f + currentNits
                && targetNits >= currentNits + 55.5f) {
            mFastRamp = true;
            mFastRampTarget = targetBrightness;
            mFastRampUntil = time + 2000;
        }
        if (mFastRamp && (targetBrightness < mFastRampTarget
                || (targetBrightness > mFastRampTarget && currentBrightness >= mFastRampTarget))) {
            mFastRamp = false;
        }
        return mFastRamp;
    }

    float evaluate(long time) {
        if (mStepUntil <= time) mStepUntil = NONE;
        if (mFastRampUntil <= time) {
            mFastRamp = false;
            mFastRampUntil = NONE;
        }
        // Keep the independent stock deadlines, serviced by ABC's single timer.
        boolean mainDue = mMain.next <= time;
        boolean assistDue = mAssist.next <= time;
        if (assistDue && mAssist.next < mMain.next) {
            updateAssist(time);
            if (mainDue) updateMain(time);
        } else {
            if (mainDue) updateMain(time);
            if (assistDue) updateAssist(time);
        }
        float result = mPendingLux;
        mPendingLux = Float.NaN;
        return result;
    }

    long nextEvaluation() {
        return Math.min(Math.min(mMain.next, mAssist.next),
                Math.min(mStepUntil, mFastRampUntil));
    }

    int selected() {
        return mSelected;
    }

    private void publish(float lux, boolean mainEvent) {
        mLux = mPendingLux = lux;
        // ABC.setAmbientLux(event,...): main thresholds also follow rejected main updates.
        if (mainEvent || mSelected == MAIN) mMain.thresholds();
    }

    private void updateMain(long time) {
        mMain.calculate(time);
        if (!mMain.valid) {
            mMain.valid = true;
            if (mSelected != ASSIST || mMain.fast >= mAssist.fast) {
                mSelected = MAIN;
                publish(mMain.fast, true);
            } else {
                mMain.thresholds();
            }
        }
        float raw = mMain.history.latest();
        if (fastWindow(time) && raw >= mMain.bright && raw >= mLux + 20
                && raw >= mLux * .6f + mLux && (mSelected == MAIN || raw >= mAssist.fast)) {
            mSelected = MAIN;
            mMain.fast = mMain.slow = raw;
            publish(raw, true);
            // Stock returns here without scheduling its main evaluation.
            mMain.next = NONE;
            return;
        }
        long darkDebounce = 1000 + (mStepUntil != NONE && time < mStepUntil ? 3000 : 0);
        long brighten = mMain.brighten(time, false);
        long darken = mMain.history.transition(time, mMain.dark, false, darkDebounce);
        float brightThreshold = mMain.fast < mMain.bright ? mMain.small : mMain.bright;
        boolean changed = false;
        if (mMain.slow >= brightThreshold && mMain.fast >= brightThreshold && brighten <= time) {
            if (mSelected == MAIN || (mSelected == ASSIST && mMain.fast >= mAssist.fast)) {
                mSelected = MAIN;
                publish(mMain.fast, true);
            }
            mMain.thresholds();
            changed = true;
        } else if (mMain.fast <= mMain.dark && darken <= time) {
            if (mMain.fast >= mAssist.fast) {
                mSelected = MAIN;
                publish(mMain.fast, true);
            } else if (mSelected == MAIN) {
                mSelected = ASSIST;
                mAssist.thresholds();
                publish(mAssist.fast, true);
            }
            mMain.thresholds();
            changed = true;
        }
        if (changed) {
            brighten = mMain.brighten(time, true);
            darken = mMain.history.transition(time, mMain.dark, false, darkDebounce);
        }
        mMain.schedule(time, brighten, darken);
    }

    private void updateAssist(long time) {
        mAssist.calculate(time);
        if (!mAssist.valid) {
            mAssist.valid = true;
            // No off-screen daemon history: assist cannot initialize an invalid main session.
            if ((mSelected == MAIN && mAssist.fast > mMain.bright)
                    || (mSelected == ASSIST && mAssist.fast >= mAssist.bright
                    && mAssist.fast > mMain.fast)) {
                mSelected = ASSIST;
                publish(mAssist.fast, false);
            }
            mAssist.thresholds();
        }
        long brighten = mAssist.brighten(time, false);
        long darken = mAssist.history.transition(time, mAssist.dark, false, 1000);
        float threshold = mAssist.fast < mAssist.bright ? mAssist.small : mAssist.bright;
        if ((mAssist.slow >= threshold && mAssist.fast >= threshold && brighten <= time)
                || (mAssist.fast <= mAssist.dark && darken <= time)) {
            if ((mSelected == MAIN && mAssist.slow > mMain.bright && mAssist.fast > mMain.bright)
                    || (mSelected == ASSIST && mAssist.fast > mMain.fast)) {
                mSelected = ASSIST;
                publish(mAssist.fast, false);
            } else if (mSelected == ASSIST) {
                mSelected = MAIN;
                publish(mMain.fast, false);
            }
            mAssist.thresholds();
            brighten = mAssist.brighten(time, true);
            darken = mAssist.history.transition(time, mAssist.dark, false, 1000);
        }
        mAssist.schedule(time, brighten, darken);
    }

    static long eventUptime(long eventNanos, long startNanos, long nowNanos, long nowUptime) {
        if (eventNanos < startNanos || eventNanos > nowNanos) return -1;
        return nowUptime - (nowNanos - eventNanos) / 1000000L;
    }

    static float brightThreshold(float lux) {
        // HysteresisLevelsImpl: stock HBM minimumLux is 7180 (strict comparison).
        return lux > 7180 ? lux + 1 : BRIGHT.interpolate(lux);
    }

    static float smallThreshold(float lux) {
        return lux > 7180 ? lux + 1 : SMALL.interpolate(lux);
    }

    private static final class Channel {
        final History history = new History();
        boolean valid;
        float fast = -1, slow = -1, bright = -1, small = -1, dark = -1;
        long next = NONE;

        void clear() {
            history.samples.clear();
            valid = false;
            fast = slow = bright = small = dark = -1;
            next = NONE;
        }

        void calculate(long time) {
            history.prune(time - 5000);
            fast = history.average(time, 1000);
            slow = history.average(time, 1500);
        }

        void thresholds() {
            bright = brightThreshold(fast);
            small = smallThreshold(fast);
            dark = DARK.interpolate(fast);
        }

        long brighten(long time, boolean normalOnly) {
            boolean smallChange = !normalOnly && fast < bright;
            return history.transition(time, smallChange ? small : bright, true,
                    smallChange ? 5000 : 1000);
        }

        void schedule(long time, long brighten, long darken) {
            next = Math.min(brighten, darken);
            if (next <= time) next = time + RATE;
        }
    }

    /** Stock AmbientLightRingBuffer arithmetic, including retained last sample and +100 ms. */
    static final class History {
        final ArrayList<Sample> samples = new ArrayList<>();

        boolean push(long time, float lux) {
            if (!Float.isFinite(lux) || lux < 0) return false;
            if (!samples.isEmpty() && time < samples.get(samples.size() - 1).time) return false;
            prune(time - 5000);
            samples.add(new Sample(time, lux));
            return true;
        }

        void prune(long horizon) {
            while (samples.size() > 1 && samples.get(1).time <= horizon) samples.remove(0);
            if (!samples.isEmpty() && samples.get(0).time < horizon) samples.get(0).time = horizon;
        }

        float latest() {
            return samples.get(samples.size() - 1).lux;
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

        private static float integral(long x) {
            return x * (x * .5f + 1500);
        }
    }

    private static final class Sample {
        long time;
        final float lux;

        Sample(long time, float lux) {
            this.time = time;
            this.lux = lux;
        }
    }
}
