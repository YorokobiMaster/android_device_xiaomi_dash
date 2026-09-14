/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Slog;

import com.android.server.display.DeviceBrightnessPolicy;

import java.io.FileInputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;

import vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature;
import vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeatureCallback;

/** One ID-0 callback owner. DPC remains the only framework brightness writer. */
public final class DashBrightnessPolicy implements DeviceBrightnessPolicy {
    private static final String TAG = "DashBrightnessPolicy";
    private static final String THERMAL = "/sys/class/thermal/thermal_message/";
    private static final String TABLE =
            "/product/etc/displayconfig/multi_factor_thermal_brightness_control.xml";
    // Local polling/health bounds, not claimed to be stock native notification timings.
    private static final long POLL_MS = 1000;
    private static final long INPUT_LEASE_MS = 5000;
    private static final int DISPLAY_STATE_NOTIFY = 13;
    private static final int DISPLAY_STATE_OFF = 0;
    private static final int DISPLAY_STATE_ON = 1;
    private static final int HIST_GRAY_STATE = 56;
    private static final int DISPLAY_FEATURE_COOKIE = 255;
    private final Handler mDisplayHandler;
    private final Runnable mChanged;
    private HandlerThread mThread;
    private Handler mWorker;
    private boolean mInteractive;
    private boolean mAutomatic;
    private boolean mStopped;
    private float mLux;
    private int mGeneration;
    private Inputs mInputs;
    private final Runnable mExpire;

    // Worker-owned fields, never used directly by the display or Binder threads.
    private boolean mRunning;
    private boolean mWorkerAutomatic;
    private int mWorkerGeneration;
    private ThermalBrightnessTable mTable;
    private float mTemperature = Float.NaN;
    private float mSafety = Float.NaN;
    private Integer mCondition;
    private int mGray = -1;
    private IDisplayFeature mRemote;
    private IBinder mBinder;
    private IBinder.DeathRecipient mDeath;
    private IDisplayFeatureCallback mCallback;
    private boolean mCallbackRegistered;
    private boolean mDisplayStateSynced;
    private boolean mHistogramEnabled;
    private volatile long mHistogramGeneration;
    private long mGrayCallbackCount;
    private long mLastGrayCallbackUptimeMillis;
    private final Runnable mPoll = this::poll;

    private record Inputs(ThermalBrightnessTable table, float temperature, float safety,
            Integer condition, int gray, boolean callbackRegistered,
            boolean displayStateSynced, boolean histogramEnabled, long grayCallbackCount,
            long lastGrayCallbackUptimeMillis) { }

    public DashBrightnessPolicy(Context context, Handler handler, Runnable onChanged) {
        mDisplayHandler = handler;
        mChanged = onChanged;
        mExpire = () -> {
            mInputs = null;
            mChanged.run();
        };
    }

    @Override
    public void update(boolean interactive, boolean automatic, float ambientLux) {
        if (mStopped) return;
        mAutomatic = automatic;
        mLux = automatic ? ambientLux : 6000;
        if (mInteractive == interactive) {
            final int generation = mGeneration;
            if (mWorker != null) {
                mWorker.post(() -> {
                    if (!mRunning || mWorkerGeneration != generation) return;
                    mWorkerAutomatic = automatic;
                    setHistogramEnabled(automatic);
                });
            }
            return;
        }
        mInteractive = interactive;
        mInputs = null;
        mDisplayHandler.removeCallbacks(mExpire);
        final int generation = ++mGeneration;
        if (mWorker == null && interactive) {
            mThread = new HandlerThread(TAG);
            mThread.start();
            mWorker = new Handler(mThread.getLooper());
        }
        if (mWorker == null) return;
        mWorker.post(() -> {
            mRunning = interactive;
            mWorkerAutomatic = automatic;
            mWorkerGeneration = generation;
            mWorker.removeCallbacks(mPoll);
            if (!interactive) {
                setHistogramEnabled(false);
                notifyDisplayOff();
            }
            disconnect();
            mTemperature = mSafety = Float.NaN;
            mCondition = null;
            if (interactive) poll();
        });
    }

    @Override
    public Limits getLimits() {
        Inputs input = mInputs;
        if (!mInteractive || input == null) return Limits.UNAVAILABLE;
        float tableCap = input.table == null ? Float.NaN : input.table.cap(
                input.condition == null ? 0 : input.condition, mLux, input.temperature);
        float thermal = tableCap;
        // Even an incomplete sample must preserve any valid tighter safety cap.
        if (Float.isFinite(input.safety) && input.safety > 0) {
            thermal = Float.isNaN(thermal) ? input.safety : Math.min(thermal, input.safety);
        }
        float sdr = ContentBrightness.sdrCap(mLux, input.gray);
        // Stock HDR OPR is disabled and its arrays are empty. Keep HDR in normal range;
        // no invented peak-video/app classification or BCBC cloud app allowlist.
        // Until that full boundary is recovered, do not authorize HBM on either channel.
        return new Limits(thermal, sdr, Float.NaN, false);
    }

    private void poll() {
        if (!mRunning) return;
        if (mTable == null) {
            try (FileInputStream input = new FileInputStream(TABLE)) {
                mTable = new ThermalBrightnessTable(input);
            } catch (Exception e) {
                Slog.w(TAG, "Thermal table unavailable; HBM disabled", e);
            }
        }
        mTemperature = readNumber("board_sensor_temp") / 1000;
        mSafety = readNumber("thermal_max_brightness");
        try {
            mCondition = Integer.valueOf(Files.readString(Path.of(THERMAL, "sconfig")).trim());
        } catch (Exception e) {
            mCondition = null;
        }
        publish();
        if (mRemote == null) connect();
        if (mRunning) mWorker.postDelayed(mPoll, POLL_MS);
    }

    private static float readNumber(String node) {
        try {
            float value = Float.parseFloat(Files.readString(Path.of(THERMAL, node)).trim());
            return Float.isFinite(value) ? value : Float.NaN;
        } catch (Exception e) {
            return Float.NaN;
        }
    }

    private void connect() {
        IBinder binder = ServiceManager.checkService(IDisplayFeature.DESCRIPTOR + "/default");
        if (binder == null) return;
        final IDisplayFeature remote = IDisplayFeature.Stub.asInterface(Binder.allowBlocking(binder));
        final IBinder.DeathRecipient death = () -> mWorker.post(() -> {
            if (mBinder != binder) return;
            disconnect();
            publish();
        });
        final IDisplayFeatureCallback callback = new IDisplayFeatureCallback.Stub() {
            @Override
            public void displayfeatureInfoChanged(int caseId, int value,
                    float red, float green, float blue) {
                if (caseId != 95000) return;
                final long histogramGeneration = mHistogramGeneration;
                mWorker.post(() -> {
                    if (!mRunning || mCallback != this || mBinder != binder
                            || !mHistogramEnabled
                            || mHistogramGeneration != histogramGeneration) return;
                    mGray = value >= 0 && value <= 255 ? value : -1;
                    mGrayCallbackCount++;
                    mLastGrayCallbackUptimeMillis = SystemClock.uptimeMillis();
                    publish();
                });
            }
            @Override
            public int getInterfaceVersion() { return VERSION; }
            @Override
            public String getInterfaceHash() { return HASH; }
        };
        mBinder = binder;
        mRemote = remote;
        mDeath = death;
        mCallback = callback;
        try {
            binder.linkToDeath(death, 0);
            remote.registerCallback(0, callback);
            mCallbackRegistered = true;
            remote.setFeature(0, DISPLAY_STATE_NOTIFY, DISPLAY_STATE_ON,
                    DISPLAY_FEATURE_COOKIE);
            mDisplayStateSynced = true;
            setHistogramEnabled(mWorkerAutomatic);
            publish();
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "DisplayFeature setup failed", e);
            disconnect();
            publish();
        }
    }

    private void setHistogramEnabled(boolean enabled) {
        if (mRemote == null || mHistogramEnabled == enabled) return;
        mHistogramGeneration++;
        try {
            mRemote.setFeature(0, HIST_GRAY_STATE, enabled ? 1 : 0,
                    DISPLAY_FEATURE_COOKIE);
            mHistogramEnabled = enabled;
            if (!enabled) mGray = -1;
            publish();
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "DisplayFeature grayscale sampling update failed", e);
            disconnect();
            publish();
        }
    }

    private void notifyDisplayOff() {
        if (mRemote == null || !mDisplayStateSynced) return;
        try {
            mRemote.setFeature(0, DISPLAY_STATE_NOTIFY, DISPLAY_STATE_OFF,
                    DISPLAY_FEATURE_COOKIE);
            mDisplayStateSynced = false;
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "DisplayFeature state update failed", e);
        }
    }

    private void disconnect() {
        IDisplayFeature remote = mRemote;
        IDisplayFeatureCallback callback = mCallback;
        IBinder binder = mBinder;
        IBinder.DeathRecipient death = mDeath;
        mRemote = null;
        mBinder = null;
        mCallback = null;
        mDeath = null;
        mGray = -1;
        mHistogramGeneration++;
        boolean histogramEnabled = mHistogramEnabled;
        mCallbackRegistered = false;
        mHistogramEnabled = false;
        mDisplayStateSynced = false;
        if (binder == null) return;
        if (binder.isBinderAlive()) {
            if (histogramEnabled) {
                try {
                    remote.setFeature(0, HIST_GRAY_STATE, 0, DISPLAY_FEATURE_COOKIE);
                } catch (RemoteException | RuntimeException e) {
                    Slog.w(TAG, "DisplayFeature grayscale sampling cleanup failed", e);
                }
            }
            try {
                remote.unregisterCallback(0, callback);
            } catch (RemoteException | RuntimeException e) {
                Slog.w(TAG, "DisplayFeature callback unregister failed", e);
            }
        }
        try {
            binder.unlinkToDeath(death, 0);
        } catch (NoSuchElementException ignored) { }
    }

    private void publish() {
        final int generation = mWorkerGeneration;
        final Inputs input = new Inputs(mTable, mTemperature, mSafety, mCondition, mGray,
                mCallbackRegistered, mDisplayStateSynced, mHistogramEnabled,
                mGrayCallbackCount, mLastGrayCallbackUptimeMillis);
        mDisplayHandler.post(() -> {
            if (mStopped || !mInteractive || generation != mGeneration) return;
            mInputs = input;
            mDisplayHandler.removeCallbacks(mExpire);
            mDisplayHandler.postDelayed(mExpire, INPUT_LEASE_MS);
            mChanged.run();
        });
    }

    @Override
    public void stop() {
        if (mStopped) return;
        mStopped = true;
        mInteractive = false;
        mAutomatic = false;
        mInputs = null;
        mGeneration++;
        mDisplayHandler.removeCallbacks(mExpire);
        if (mWorker != null) mWorker.post(() -> {
            mRunning = false;
            mWorkerAutomatic = false;
            mWorker.removeCallbacks(mPoll);
            // Policy disposal does not imply that the physical display is off.
            disconnect();
            mThread.quitSafely();
        });
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("DashBrightnessPolicy: interactive=" + mInteractive + " auto=" + mAutomatic
                + " lux=" + mLux + " inputs=" + mInputs + " limits=" + getLimits());
        writer.println("  HDR OPR/peak and BCBC disabled: full applicability contract unavailable");
    }
}
