/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import android.content.Context;
import android.os.Binder;
import android.os.FileObserver;
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
import java.util.function.Supplier;

import vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature;
import vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeatureCallback;

/** One ID-0 callback owner. DPC remains the only framework brightness writer.
 *
 * Thermal sysfs observation runs on its own thread; HAL Binder calls and gray callbacks run on a
 * dedicated worker, so a stalled HAL cannot stop thermal sampling.
 */
public final class DashBrightnessPolicy implements DeviceBrightnessPolicy {
    private static final String TAG = "DashBrightnessPolicy";
    private static final String THERMAL = "/sys/class/thermal/thermal_message";
    private static final String TABLE =
            "/product/etc/displayconfig/multi_factor_thermal_brightness_control.xml";
    private static final long RECONNECT_MS = 1000;
    private static final int DISPLAY_STATE_NOTIFY = 13;
    private static final int DISPLAY_STATE_OFF = 0;
    private static final int DISPLAY_STATE_ON = 1;
    private static final int HIST_GRAY_STATE = 56;
    private static final int DISPLAY_FEATURE_COOKIE = 255;
    private final Handler mDisplayHandler;
    private final Runnable mChanged;
    private final Supplier<IBinder> mServiceLookup;
    private HandlerThread mThread;
    private Handler mWorker;
    private HandlerThread mPollThread;
    private volatile Handler mPoller;
    private boolean mInteractive;
    private boolean mAutomatic;
    private boolean mHdrLayerPresent;
    private volatile boolean mStopped;
    private float mLux;
    private int mGeneration;
    private volatile ThermalCaps.Inputs mThermal = ThermalCaps.Inputs.EMPTY;
    private volatile HalInputs mHal = HalInputs.IDLE;
    private final Runnable mReconnect = this::reconnect;

    private record HalInputs(boolean callbackRegistered, boolean displayStateSynced,
            boolean histogramEnabled, int gray, long lastGrayCallbackUptimeMillis,
            long grayCallbackCount) {
        static final HalInputs IDLE = new HalInputs(false, false, false,
                ContentBrightness.RESET_GRAY, -1, 0);
    }

    private boolean mRunning;
    private boolean mWorkerHistogramEligible;
    private int mWorkerGeneration;
    private IDisplayFeature mRemote;
    private IBinder mBinder;
    private IBinder.DeathRecipient mDeath;
    private IDisplayFeatureCallback mCallback;
    private boolean mCallbackRegistered;
    private boolean mDisplayStateSynced;
    private boolean mHistogramEnabled;
    private volatile long mHistogramGeneration;
    private int mGray = ContentBrightness.RESET_GRAY;
    private long mGrayCallbackCount;
    private long mLastGrayCallbackUptimeMillis;

    private volatile boolean mThermalStarted;
    private ThermalBrightnessTable mTable;
    private float mTemperature = Float.NaN;
    private volatile float mDisplayTemperature = Float.NaN;
    private float mSafety = Float.NaN;
    private Integer mCondition;
    private final ThermalNodeObserver mBoardSensorTempObserver =
            new ThermalNodeObserver("board_sensor_temp");
    private final ThermalNodeObserver mSconfigObserver = new ThermalNodeObserver("sconfig");
    private final ThermalNodeObserver mThermalMaxBrightnessObserver =
            new ThermalNodeObserver("thermal_max_brightness");
    private final ThermalNodeObserver mDisplayThermTempObserver =
            new ThermalNodeObserver("display_therm_temp");

    private final class ThermalNodeObserver extends FileObserver {
        private final String mNode;

        ThermalNodeObserver(String node) {
            super(THERMAL + "/" + node, FileObserver.MODIFY);
            mNode = node;
        }

        @Override
        public void onEvent(int event, String path) {
            if ((event & FileObserver.MODIFY) == 0) return;
            Handler poller = mPoller;
            if (poller != null) poller.post(() -> {
                if (mStopped || !mThermalStarted) return;
                readThermalNode(mNode);
            });
        }
    }

    public DashBrightnessPolicy(Context context, Handler handler, Runnable onChanged) {
        this(handler, onChanged,
                () -> ServiceManager.checkService(IDisplayFeature.DESCRIPTOR + "/default"));
    }

    /** Test seam: the service lookup is the only injectable dependency. */
    DashBrightnessPolicy(Handler handler, Runnable onChanged, Supplier<IBinder> serviceLookup) {
        mDisplayHandler = handler;
        mChanged = onChanged;
        mServiceLookup = serviceLookup;
    }

    @Override
    public void update(boolean interactive, boolean automatic, float ambientLux,
            boolean hdrLayerPresent) {
        if (mStopped) return;
        mAutomatic = automatic;
        mHdrLayerPresent = hdrLayerPresent;
        mLux = automatic ? ambientLux : 6000;
        ensureThermalObserver();
        final boolean histogramEligible = ContentBrightness.isSamplingEligible(
                interactive, automatic, ambientLux, hdrLayerPresent);
        if (mInteractive == interactive) {
            final int generation = mGeneration;
            if (mWorker != null) {
                mWorker.post(() -> {
                    if (!mRunning || mWorkerGeneration != generation) return;
                    mWorkerHistogramEligible = histogramEligible;
                    setHistogramEnabled(histogramEligible);
                });
            }
            return;
        }
        mInteractive = interactive;
        mHal = HalInputs.IDLE;
        final int generation = ++mGeneration;
        if (interactive) {
            if (mThread == null) {
                mThread = new HandlerThread(TAG);
                mThread.start();
                mWorker = new Handler(mThread.getLooper());
            }
        }
        if (mWorker == null) return;
        mWorker.post(() -> {
            mRunning = interactive;
            mWorkerHistogramEligible = histogramEligible;
            mWorkerGeneration = generation;
            mWorker.removeCallbacks(mReconnect);
            if (!interactive) {
                setHistogramEnabled(false);
                notifyDisplayOff();
            }
            disconnect();
            if (interactive) connect();
        });
    }

    @Override
    public Limits getLimits() {
        if (!mInteractive) return Limits.UNAVAILABLE;
        HalInputs hal = mHal;
        int gray = hal.gray();
        float sdr = mAutomatic && !mHdrLayerPresent
                ? ContentBrightness.sdrCap(mLux, gray) : Float.NaN;
        float thermal = resolveThermal(mThermal);
        return new Limits(thermal, sdr,
                HdrBrightness.cap(mAutomatic, mLux, gray));
    }

    private float resolveThermal(ThermalCaps.Inputs inputs) {
        Integer condition = HdrBrightness.thermalCondition(
                mHdrLayerPresent, inputs.condition());
        ThermalCaps.Inputs effective = new ThermalCaps.Inputs(inputs.table(),
                inputs.temperature(), inputs.safety(), condition);
        return ThermalCaps.resolve(effective, mLux);
    }

    private void ensureThermalObserver() {
        if (mPoller != null) return;
        mPollThread = new HandlerThread(TAG + "Thermal");
        mPollThread.start();
        mPoller = new Handler(mPollThread.getLooper());
        mPoller.post(this::startThermalObservation);
    }

    private void startThermalObservation() {
        if (mStopped || mThermalStarted) return;
        mThermalStarted = true;
        if (mTable == null) {
            try (FileInputStream input = new FileInputStream(TABLE)) {
                mTable = new ThermalBrightnessTable(input);
            } catch (Exception e) {
                Slog.w(TAG, "Thermal table unavailable; thermal cap unavailable", e);
            }
        }
        mBoardSensorTempObserver.startWatching();
        mSconfigObserver.startWatching();
        mThermalMaxBrightnessObserver.startWatching();
        mDisplayThermTempObserver.startWatching();
        readThermalNode("board_sensor_temp", false);
        readThermalNode("sconfig", false);
        readThermalNode("thermal_max_brightness", false);
        readThermalNode("display_therm_temp", false);
        publishThermal();
    }

    private void readThermalNode(String node) {
        readThermalNode(node, true);
    }

    private void readThermalNode(String node, boolean publish) {
        switch (node) {
            case "board_sensor_temp": {
                float raw = readNumber(node);
                if (!Float.isFinite(raw)) return;
                float temperature = raw / 1000;
                if (ThermalCaps.equivalentTemperature(mTemperature, temperature)) return;
                mTemperature = temperature;
                break;
            }
            case "thermal_max_brightness": {
                float safety = readNumber(node);
                if (!Float.isFinite(safety)) return;
                mSafety = safety > 0 ? safety : Float.NaN;
                break;
            }
            case "display_therm_temp": {
                float temperature = readNumber(node);
                if (!Float.isFinite(temperature)) return;
                mDisplayTemperature = temperature;
                return;
            }
            case "sconfig": {
                try {
                    mCondition = Integer.valueOf(
                            Files.readString(Path.of(THERMAL, node)).trim());
                } catch (Exception e) {
                    return;
                }
                break;
            }
            default:
                return;
        }
        if (publish) publishThermal();
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
        IBinder binder = mServiceLookup.get();
        if (binder == null) {
            scheduleReconnect();
            return;
        }
        final IDisplayFeature remote = IDisplayFeature.Stub.asInterface(Binder.allowBlocking(binder));
        final IBinder.DeathRecipient death = () -> mWorker.post(() -> {
            if (mBinder != binder) return;
            disconnect();
            publishHal();
            scheduleReconnect();
        });
        final IDisplayFeatureCallback callback = new IDisplayFeatureCallback.Stub() {
            @Override
            public void displayfeatureInfoChanged(int caseId, int value,
                    float red, float green, float blue) {
                if (caseId != 95000 || !ContentBrightness.isValidGray(value)) return;
                final long histogramGeneration = mHistogramGeneration;
                mWorker.post(() -> {
                    if (!mRunning || mCallback != this || mBinder != binder
                            || !mHistogramEnabled
                            || mHistogramGeneration != histogramGeneration) return;
                    mGray = value;
                    mGrayCallbackCount++;
                    mLastGrayCallbackUptimeMillis = SystemClock.uptimeMillis();
                    publishHal();
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
            setHistogramEnabled(mWorkerHistogramEligible);
            publishHal();
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "DisplayFeature setup failed", e);
            disconnect();
            publishHal();
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!mRunning || mRemote != null) return;
        mWorker.removeCallbacks(mReconnect);
        mWorker.postDelayed(mReconnect, RECONNECT_MS);
    }

    private void reconnect() {
        if (!mRunning || mRemote != null) return;
        connect();
    }

    private void setHistogramEnabled(boolean enabled) {
        if (mRemote == null) {
            if (!enabled) mGray = ContentBrightness.RESET_GRAY;
            return;
        }
        if (mHistogramEnabled == enabled) {
            if (!enabled) mGray = ContentBrightness.RESET_GRAY;
            return;
        }
        mHistogramGeneration++;
        try {
            mRemote.setFeature(0, HIST_GRAY_STATE, enabled ? 1 : 0, DISPLAY_FEATURE_COOKIE);
            mHistogramEnabled = enabled;
            if (!enabled) mGray = ContentBrightness.RESET_GRAY;
            publishHal();
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "DisplayFeature grayscale sampling update failed", e);
            disconnect();
            publishHal();
            scheduleReconnect();
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
        mGray = ContentBrightness.RESET_GRAY;
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

    private void publishHal() {
        final int generation = mWorkerGeneration;
        final HalInputs inputs = new HalInputs(mCallbackRegistered, mDisplayStateSynced,
                mHistogramEnabled, mGray, mLastGrayCallbackUptimeMillis, mGrayCallbackCount);
        mDisplayHandler.post(() -> {
            if (mStopped || !mInteractive || generation != mGeneration) return;
            boolean limitsChanged = contentLimitsChanged(mHal, inputs);
            mHal = inputs;
            if (limitsChanged) mChanged.run();
        });
    }

    private boolean contentLimitsChanged(HalInputs before, HalInputs after) {
        float beforeSdr = mAutomatic && !mHdrLayerPresent
                ? ContentBrightness.sdrCap(mLux, before.gray()) : Float.NaN;
        float afterSdr = mAutomatic && !mHdrLayerPresent
                ? ContentBrightness.sdrCap(mLux, after.gray()) : Float.NaN;
        float beforeHdr = HdrBrightness.cap(mAutomatic, mLux, before.gray());
        float afterHdr = HdrBrightness.cap(mAutomatic, mLux, after.gray());
        return Float.compare(beforeSdr, afterSdr) != 0
                || Float.compare(beforeHdr, afterHdr) != 0;
    }

    private void publishThermal() {
        final ThermalCaps.Inputs inputs = new ThermalCaps.Inputs(mTable, mTemperature,
                mSafety, mCondition);
        mDisplayHandler.post(() -> {
            if (mStopped) return;
            float before = resolveThermal(mThermal);
            float after = resolveThermal(inputs);
            mThermal = inputs;
            if (!ThermalCaps.sameCap(before, after)) mChanged.run();
        });
    }

    @Override
    public void stop() {
        if (mStopped) return;
        mStopped = true;
        mInteractive = false;
        mAutomatic = false;
        mHdrLayerPresent = false;
        mThermal = ThermalCaps.Inputs.EMPTY;
        mHal = HalInputs.IDLE;
        mGeneration++;
        if (mWorker != null) mWorker.post(() -> {
            mRunning = false;
            mWorkerHistogramEligible = false;
            mWorker.removeCallbacks(mReconnect);
            // Policy disposal does not imply that the physical display is off.
            disconnect();
            mThread.quitSafely();
        });
        if (mPoller != null) mPoller.post(() -> {
            mBoardSensorTempObserver.stopWatching();
            mSconfigObserver.stopWatching();
            mThermalMaxBrightnessObserver.stopWatching();
            mDisplayThermTempObserver.stopWatching();
            mPollThread.quitSafely();
        });
    }

    @Override
    public void dump(PrintWriter writer) {
        ThermalCaps.Inputs thermal = mThermal;
        HalInputs hal = mHal;
        long now = SystemClock.uptimeMillis();
        writer.println("DashBrightnessPolicy: interactive=" + mInteractive + " auto=" + mAutomatic
                + " lux=" + mLux + " hdrLayerPresent=" + mHdrLayerPresent
                + " limits=" + getLimits());
        writer.println("  thermal: temperature=" + thermal.temperature() + " safety="
                + thermal.safety() + " displayTemperature=" + mDisplayTemperature
                + " condition=" + thermal.condition()
                + " effectiveCondition="
                + HdrBrightness.thermalCondition(mHdrLayerPresent, thermal.condition()));
        writer.println("  hal: callbackRegistered=" + hal.callbackRegistered()
                + " displayStateSynced=" + hal.displayStateSynced() + " histogramEnabled="
                + hal.histogramEnabled() + " gray=" + hal.gray() + " grayCallbacks="
                + hal.grayCallbackCount() + " lastGray@"
                + age(hal.lastGrayCallbackUptimeMillis(), now));
        writer.println("  HBM authorization owned by HighBrightnessModeController; "
                + "HDR peak max=" + HdrBrightness.cap(mAutomatic, mLux,
                        hal.gray()));
    }

    private static String age(long at, long now) {
        return at < 0 ? "never" : (now - at) + "ms ago";
    }
}
