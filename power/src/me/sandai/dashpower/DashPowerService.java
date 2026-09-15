/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.power.DevicePowerManagerInternal;

/** Opt-in system-server extension loaded using config_deviceSpecificSystemServices. */
public final class DashPowerService extends SystemService {
    private static final String ENABLED = "persist.sys.dash.power.enabled";
    private PowerPolicy policy;
    private ThermalBackend thermal;

    public DashPowerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        final HandlerThread thread = new HandlerThread("DashPower", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        final Handler handler = new Handler(thread.getLooper());
        final HandlerThread thermalThread =
                new HandlerThread("DashThermal", Process.THREAD_PRIORITY_BACKGROUND);
        thermalThread.start();
        thermal = new ThermalBackend(getContext(), new Handler(thermalThread.getLooper()));
        publishBinderService(ThermalBackend.SERVICE, thermal);
        final DevicePowerManagerInternal source =
                LocalServices.getService(DevicePowerManagerInternal.class);
        if (source == null) {
            Slog.e("DashPower", "Power hooks unavailable; adapter inactive");
            return;
        }
        policy = new PowerPolicy(new PowerPolicy.Worker() {
            @Override public void execute(Runnable task) { handler.post(task); }
            @Override public void after(int ms, Runnable task) { handler.postDelayed(task, ms); }
        }, new MtkPowerClient(() -> policy.disconnected()));
        SystemProperties.addChangeCallback(() ->
                policy.setEnabled(SystemProperties.getBoolean(ENABLED, true)));
        policy.setEnabled(SystemProperties.getBoolean(ENABLED, true));
        source.setListener(new DevicePowerManagerInternal.Listener() {
            @Override public void onStateChanged(boolean interactive, boolean disabled) {
                policy.state(interactive, disabled);
            }
            @Override public void onLaunch(boolean enabled) { policy.launch(enabled); }
            @Override public void onFling(int displayId, int durationMs) {
                policy.fling(displayId, durationMs);
            }
        });
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) thermal.start();
    }

    @Override
    public void onUserSwitching(TargetUser from, TargetUser to) {
        thermal.switching(to.getUserIdentifier());
    }

    @Override
    public void onUserUnlocked(TargetUser user) {
        thermal.changed();
    }
}
