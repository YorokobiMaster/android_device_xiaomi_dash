/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.os.Handler;

import java.util.ArrayList;
import java.util.List;

import me.sandai.dashled.aidl.DashLedEffect;

/**
 * Session registry + priority arbitration. The highest-category session that
 * currently holds content drives the hardware; when it clears or dies the
 * next one is restored. Effects are rendered here on the LED thread so
 * clients never stream frames for built-in-style animations.
 *
 * External lock order: all public methods are synchronized; hardware writes
 * go through the backend's own serialized handler. Effect completion
 * callbacks always run after the arbiter lock is released, so callbacks may
 * re-enter the arbiter.
 */
public final class LedArbiter {

    private final Aw21024Backend mBackend;
    private final Handler mHandler;
    private final List<Session> mSessions = new ArrayList<>();

    private boolean mOutputEnabled = true;
    private long mNextSeq;
    private Session mRendered;
    private long mRenderedSeq = -1;
    private EffectRunner mEffectRunner;
    private Runnable mBpcDone;

    public final class Session {
        private final int category;
        private long seq;
        private int[] colors;
        private int brightness;
        private DashLedEffect effect;
        private Runnable onEffectDone;
        private boolean released;

        private Session(int category) {
            this.category = category;
        }

        public int getCategory() {
            return category;
        }
    }

    public LedArbiter(Aw21024Backend backend, Handler handler) {
        mBackend = backend;
        mHandler = handler;
    }

    public synchronized Session acquire(int category) {
        Session session = new Session(category);
        mSessions.add(session);
        return session;
    }

    public synchronized void setFrame(Session session, int[] colors, int brightness) {
        checkLive(session);
        session.colors = colors.clone();
        session.brightness = brightness;
        session.effect = null;
        session.onEffectDone = null;
        session.seq = mNextSeq++;
        renderLocked();
    }

    public synchronized void playEffect(Session session, DashLedEffect effect,
            Runnable onDone) {
        checkLive(session);
        session.effect = effect;
        session.onEffectDone = onDone;
        session.seq = mNextSeq++;
        renderLocked();
    }

    public synchronized void clear(Session session) {
        if (!mSessions.contains(session)) {
            return;
        }
        session.colors = null;
        session.effect = null;
        session.onEffectDone = null;
        renderLocked();
    }

    public synchronized void release(Session session) {
        if (session.released) {
            return;
        }
        session.released = true;
        mSessions.remove(session);
        renderLocked();
    }

    /** Master switch. Content is kept, so re-enabling restores the last state. */
    public synchronized void setOutputEnabled(boolean enabled) {
        if (mOutputEnabled == enabled) {
            return;
        }
        mOutputEnabled = enabled;
        renderLocked();
    }

    private void checkLive(Session session) {
        if (session.released || !mSessions.contains(session)) {
            throw new IllegalStateException("session released");
        }
    }

    private void renderLocked() {
        Session active = null;
        for (Session session : mSessions) {
            if (session.colors == null && session.effect == null) {
                continue;
            }
            if (active == null || session.category > active.category
                    || (session.category == active.category && session.seq > active.seq)) {
                active = session;
            }
        }

        if (!mOutputEnabled || active == null) {
            stopEffectLocked();
            if (mRendered != null) {
                mBackend.submitOff();
            }
            mRendered = null;
            mRenderedSeq = -1;
            return;
        }

        if (active == mRendered && active.seq == mRenderedSeq) {
            return;
        }

        stopEffectLocked();
        mRendered = active;
        mRenderedSeq = active.seq;
        if (active.effect != null) {
            if (isBpcCompatible(active.effect)) {
                playBpcLocked(active);
            } else {
                mEffectRunner = new EffectRunner(active);
                mHandler.post(mEffectRunner);
            }
        } else {
            mBackend.submitFrame(active.colors, active.brightness);
        }
    }

    /** The chip runs a single shared timing envelope with one global color, so
     *  only a uniform triangle breath can be offloaded. */
    private static boolean isBpcCompatible(DashLedEffect effect) {
        if (effect.type != DashLedEffect.TYPE_BREATH) {
            return false;
        }
        int color = effect.colors[0];
        for (int i = 1; i < effect.colors.length; i++) {
            if (effect.colors[i] != color) {
                return false;
            }
        }
        return Aw21024Backend.isBreathHardwareCompatible(effect.periodMs,
                effect.brightness);
    }

    private void playBpcLocked(Session session) {
        DashLedEffect effect = session.effect;
        mBackend.submitBpcBreath(effect.colors[0], effect.brightness,
                effect.periodMs, effect.repeatCount);
        if (effect.repeatCount > 0) {
            // The chip stops by itself; this only performs the arbiter-side
            // cleanup, and may fire late after deep sleep without harm.
            Runnable done = new Runnable() {
                @Override
                public void run() {
                    final Runnable completed;
                    synchronized (LedArbiter.this) {
                        if (mBpcDone != this) {
                            return;
                        }
                        mBpcDone = null;
                        completed = session.onEffectDone;
                        clear(session);
                    }
                    if (completed != null) {
                        completed.run();
                    }
                }
            };
            mBpcDone = done;
            mHandler.postDelayed(done, Aw21024Backend.estimateBpcDurationMs(
                    effect.periodMs, effect.repeatCount));
        }
    }

    private void stopEffectLocked() {
        if (mEffectRunner != null) {
            mHandler.removeCallbacks(mEffectRunner);
            mEffectRunner = null;
        }
        if (mBpcDone != null) {
            mHandler.removeCallbacks(mBpcDone);
            mBpcDone = null;
        }
    }

    /** Triangle-wave brightness loop stepping at the backend coalescing rate. */
    private final class EffectRunner implements Runnable {
        private final Session mSession;
        private final int mStepsPerCycle;
        private int mStep;
        private int mCycle;

        EffectRunner(Session session) {
            mSession = session;
            mStepsPerCycle = Math.max(2,
                    session.effect.periodMs / Aw21024Backend.MIN_FRAME_INTERVAL_MS);
        }

        @Override
        public void run() {
            final Runnable done;
            synchronized (LedArbiter.this) {
                if (mEffectRunner != this || mSession.effect == null) {
                    return;
                }
                DashLedEffect effect = mSession.effect;
                float phase = (float) mStep / mStepsPerCycle;
                float triangle = phase < 0.5f ? phase * 2f : (1f - phase) * 2f;
                int level = Math.round(effect.brightness * triangle);
                mBackend.submitFrame(effect.colors, level);

                Runnable completed = null;
                if (++mStep >= mStepsPerCycle) {
                    mStep = 0;
                    if (effect.repeatCount > 0 && ++mCycle >= effect.repeatCount) {
                        completed = mSession.onEffectDone;
                        clear(mSession);
                    }
                }
                done = completed;
                if (done == null) {
                    mHandler.postDelayed(this, Aw21024Backend.MIN_FRAME_INTERVAL_MS);
                }
            }
            if (done != null) {
                done.run();
            }
        }
    }
}
