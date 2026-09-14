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
    private final Runnable mPoll = this::poll;

    private record Inputs(ThermalBrightnessTable table, float temperature, float safety,
            Integer condition, int gray) { }

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
        if (mInteractive == interactive) return;
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
            mWorkerGeneration = generation;
            mWorker.removeCallbacks(mPoll);
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
                mWorker.post(() -> {
                    if (!mRunning || mCallback != this || mBinder != binder) return;
                    mGray = value >= 0 && value <= 255 ? value : -1;
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
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "DisplayFeature callback registration failed", e);
            disconnect();
            publish();
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
        if (binder == null) return;
        try {
            if (binder.isBinderAlive()) remote.unregisterCallback(0, callback);
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "DisplayFeature callback unregister failed", e);
        } finally {
            try {
                binder.unlinkToDeath(death, 0);
            } catch (NoSuchElementException ignored) { }
        }
    }

    private void publish() {
        final int generation = mWorkerGeneration;
        final Inputs input = new Inputs(mTable, mTemperature, mSafety, mCondition, mGray);
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
        update(false, false, Float.NaN);
        mStopped = true;
        if (mWorker != null) mWorker.post(() -> mThread.quitSafely());
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("DashBrightnessPolicy: interactive=" + mInteractive + " auto=" + mAutomatic
                + " lux=" + mLux + " inputs=" + mInputs + " limits=" + getLimits());
        writer.println("  HDR OPR/peak and BCBC disabled: full applicability contract unavailable");
    }
}
