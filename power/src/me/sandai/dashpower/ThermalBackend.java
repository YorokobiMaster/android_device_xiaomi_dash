/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.KeyguardManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.hardware.camera2.CameraManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.UEventObserver;
import android.os.UserHandle;
import android.os.UserManager;
import android.telephony.TelephonyManager;
import android.util.Slog;
import android.view.Display;

import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/** Configuration and request state are guarded by this; queries/sysfs use the thermal worker. */
final class ThermalBackend extends IDashThermalService.Stub {
    static final String SERVICE = "dash_thermal";
    private static final String TAG = "DashThermal";
    private static final File NODE = new File("/sys/class/thermal/thermal_message/sconfig");
    private static final File LOW_TEMP_CHARGE =
            new File("/sys/class/power_supply/battery/extreme_cold_chg");
    private static final File REVERSE_CHARGE =
            new File("/sys/class/power_supply/usb/otg_enable");
    private static final String POWER_KEEPER_PACKAGE = "com.miui.powerkeeper";
    private final Context context;
    private final Handler worker;
    private final UserManager users;
    private final IntFunction<ThermalConfigStore> stores;
    private final Map<String, String> groups = new HashMap<>();
    private final Map<Integer, Config> configs = new HashMap<>();
    private final Map<Integer, String> persistenceErrors = new HashMap<>();
    private final ThermalRequestState requests = new ThermalRequestState();
    private final Runnable[] retries = new Runnable[ThermalRequestState.RETRY_COUNT];
    private final AtomicBoolean lifecycleCheckQueued = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final Runnable refresh = this::recompute;
    private final Set<String> unavailableCameras = new HashSet<>();
    private final UEventObserver powerSupplyObserver = new UEventObserver() {
        @Override public void onUEvent(UEventObserver.UEvent event) { changed(); }
    };
    private final CameraManager.AvailabilityCallback cameraAvailability =
            new CameraManager.AvailabilityCallback() {
                @Override public void onCameraAvailable(String cameraId) {
                    synchronized (ThermalBackend.this) {
                        unavailableCameras.remove(cameraId);
                        if (unavailableCameras.isEmpty()) clearCameraRecordStateLocked();
                    }
                }

                @Override public void onCameraUnavailable(String cameraId) {
                    synchronized (ThermalBackend.this) {
                        unavailableCameras.add(cameraId);
                    }
                }
            };
    private volatile int currentUser;
    private volatile boolean ready;
    private boolean forceNextRequest;
    private String lifecycleError = "not sampled";
    private String watcherError = "not started";
    private String cameraWatcherError = "not started";
    private int foregroundUser = -1;
    private String foregroundPackage = "";
    private String reason = "starting";
    private String error = "";
    private String eventError = "not sampled";
    private int scenario;
    private int cameraElement = ThermalScenarioPolicy.OFF;
    private int cameraUser = -1;
    private boolean offHook;
    private boolean lowTempCharge;
    private boolean reverseCharge;

    private static final class Config {
        boolean enabled = true;
        boolean performanceMode;
        final Map<String, Integer> overrides = new HashMap<>();
    }

    ThermalBackend(Context context, Handler worker) {
        this(context, worker, user -> new ThermalConfigStore(
                new File(Environment.getDataSystemDeDirectory(user), "dash-thermal.json").toPath()));
    }

    ThermalBackend(Context context, Handler worker, IntFunction<ThermalConfigStore> stores) {
        this.context = context;
        this.worker = worker;
        this.stores = stores;
        users = context.getSystemService(UserManager.class);
    }

    void start() {
        worker.post(() -> {
            startLifecycleObserver();
            synchronized (this) { sampleLifecycle(); }
            try {
                JSONObject root = new JSONObject(Files.readString(
                        new File("/system_ext/etc/dash-power/thermal-packages.json").toPath()));
                if (root.getInt("version") != 1) throw new IllegalStateException("Default schema");
                JSONObject entries = root.getJSONObject("packages");
                synchronized (this) {
                    for (Iterator<String> it = entries.keys(); it.hasNext();) {
                        String pkg = it.next();
                        groups.put(pkg, entries.getString(pkg));
                    }
                }
                currentUser = ActivityManager.getCurrentUser();
                ActivityTaskManager.getInstance().registerTaskStackListener(new TaskStackListener() {
                    @Override public void onTaskStackChanged() { changed(); }
                    @Override public void onTaskMovedToFront(ActivityManager.RunningTaskInfo t) {
                        changed();
                    }
                    @Override public void onTaskFocusChanged(int taskId, boolean focused) { changed(); }
                    @Override public void onTaskRemoved(int taskId) { changed(); }
                    @Override public void onTaskCreated(int taskId, android.content.ComponentName c) {
                        changed();
                    }
                    @Override public void onActivityPinned(String pkg, int userId, int taskId,
                            int stackId) { changed(); }
                    @Override public void onActivityUnpinned() { changed(); }
                });
                IntentFilter states = new IntentFilter();
                states.addAction(Intent.ACTION_SCREEN_ON);
                states.addAction(Intent.ACTION_SCREEN_OFF);
                states.addAction(Intent.ACTION_USER_PRESENT);
                states.addAction(Intent.ACTION_USER_UNLOCKED);
                states.addAction(Intent.ACTION_USER_STOPPED);
                states.addAction(Intent.ACTION_USER_REMOVED);
                states.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
                context.registerReceiverAsUser(new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        if (Intent.ACTION_USER_REMOVED.equals(i.getAction())
                                || Intent.ACTION_USER_STOPPED.equals(i.getAction())) {
                            synchronized (ThermalBackend.this) {
                                int affectedUser = i.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                                if (Intent.ACTION_USER_REMOVED.equals(i.getAction())) {
                                    configs.remove(affectedUser);
                                    persistenceErrors.remove(affectedUser);
                                }
                                if (cameraUser == affectedUser) clearCameraRecordStateLocked();
                            }
                        }
                        changed();
                    }
                }, UserHandle.ALL, states, null, worker);
                IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
                packages.addDataScheme("package");
                context.registerReceiverAsUser(new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        if (!i.getBooleanExtra(Intent.EXTRA_REPLACING, false) && i.getData() != null) {
                            int uid = i.getIntExtra(Intent.EXTRA_UID, -1);
                            if (uid >= 0) removePackage(UserHandle.getUserId(uid),
                                    i.getData().getSchemeSpecificPart());
                        }
                    }
                }, UserHandle.ALL, packages, null, worker);
                powerSupplyObserver.startObserving("SUBSYSTEM=power_supply");
                CameraManager cameras = context.getSystemService(CameraManager.class);
                if (cameras == null) {
                    synchronized (this) { cameraWatcherError = "camera service absent"; }
                } else {
                    try {
                        cameras.registerAvailabilityCallback(cameraAvailability, worker);
                        synchronized (this) { cameraWatcherError = ""; }
                    } catch (RuntimeException e) {
                        synchronized (this) { cameraWatcherError = e.toString(); }
                    }
                }
                context.getSystemService(KeyguardManager.class)
                        .addKeyguardLockedStateListener(worker::post, locked -> changed());
                synchronized (this) {
                    // Request normal before tracking tasks, if the daemon is running.
                    ready = true;
                    writeProfile(0, true);
                }
                changed();
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    synchronized void switching(int userId) {
        currentUser = userId;
        if (cameraUser >= 0 && cameraUser != userId) {
            UserInfo parent = users.getProfileParent(cameraUser);
            if (parent == null || parent.id != userId) clearCameraRecordStateLocked();
        }
        changed();
    }

    void changed() {
        generation.incrementAndGet();
        worker.removeCallbacks(refresh);
        worker.post(refresh);
    }

    private void recompute() {
        if (!ready) return;
        long observed = generation.get();
        try {
            int user = currentUser;
            int appUser = -1;
            String pkg = "";
            String why = "no-focused-app";
            boolean interactive = context.getSystemService(PowerManager.class).isInteractive();
            if (!interactive) {
                why = "screen-off";
            } else if (context.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                why = "locked";
            } else if (!users.isUserUnlocked(user)) {
                why = "user-locked";
            } else {
                // RunningTasks puts the focused, visibility-requested leaf first on this display.
                for (ActivityManager.RunningTaskInfo task : ActivityTaskManager.getInstance()
                        .getTasks(1, false, false, Display.DEFAULT_DISPLAY)) {
                    if (!isForegroundTask(task)) continue;
                    UserInfo parent = users.getProfileParent(task.userId);
                    if (task.userId != user && (parent == null || parent.id != user)) continue;
                    if (!users.isUserUnlocked(task.userId)) continue;
                    pkg = task.topActivity.getPackageName();
                    appUser = task.userId;
                    why = "automatic";
                }
            }
            StringBuilder eventFailures = new StringBuilder();
            boolean sampledOffHook = sampleOffHook(eventFailures);
            boolean sampledLowTemp = readEventFlag(LOW_TEMP_CHARGE, eventFailures);
            boolean sampledReverse = readEventFlag(REVERSE_CHARGE, eventFailures);
            synchronized (this) {
                if (observed != generation.get()) return;
                int target = 0;
                int selectedScenario = 0;
                if (!config(user).enabled) {
                    why = "disabled";
                } else if (appUser >= 0) {
                    Config appConfig = config(appUser);
                    if (!appConfig.enabled) {
                        why = "profile-disabled";
                    } else {
                        Integer override = appConfig.overrides.get(pkg);
                        if (override != null) {
                            target = override;
                            selectedScenario = -1;
                            why = "override";
                        } else {
                            String group = groups.getOrDefault(pkg, "unclassified");
                            boolean performance = !"unclassified".equals(group)
                                    && config(modeUser(appUser)).performanceMode;
                            int camera = cameraUser == appUser
                                    ? cameraElement : ThermalScenarioPolicy.OFF;
                            ThermalScenarioPolicy.Result selected = ThermalScenarioPolicy.select(
                                    group, pkg, interactive, sampledOffHook, camera,
                                    performance, sampledLowTemp, sampledReverse);
                            selectedScenario = selected.scenario;
                            target = selected.profile;
                        }
                    }
                } else {
                    // Preserve normal when idle while allowing global stock event scenarios.
                    ThermalScenarioPolicy.Result selected = ThermalScenarioPolicy.select(
                            "unclassified", "", interactive, sampledOffHook,
                            ThermalScenarioPolicy.OFF,
                            false, sampledLowTemp, sampledReverse);
                    selectedScenario = selected.scenario;
                    target = selected.profile;
                }
                foregroundPackage = pkg;
                foregroundUser = appUser;
                reason = why;
                scenario = selectedScenario;
                offHook = sampledOffHook;
                lowTempCharge = sampledLowTemp;
                reverseCharge = sampledReverse;
                if (!cameraWatcherError.isEmpty()) {
                    appendEventFailure(eventFailures, "camera", cameraWatcherError);
                }
                eventError = eventFailures.toString();
                boolean force = forceNextRequest;
                forceNextRequest = false;
                writeProfile(target, force);
                error = "";
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    private synchronized void fail(Exception e) {
        error = e.toString();
        reason = "error";
        Slog.e(TAG, "Thermal request failed; requesting normal", e);
        try {
            writeProfile(0, true);
        } catch (Exception recovery) {
            error += "; normal request failed: " + recovery;
        }
    }

    static boolean isForegroundTask(ActivityManager.RunningTaskInfo task) {
        return task.isFocused && task.isVisibleRequested && task.topActivity != null;
    }

    private boolean sampleOffHook(StringBuilder failures) {
        try {
            TelephonyManager telephony = context.getSystemService(TelephonyManager.class);
            if (telephony == null) throw new IllegalStateException("telephony service absent");
            return telephony.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK;
        } catch (RuntimeException e) {
            appendEventFailure(failures, "call", e);
            return false;
        }
    }

    private static boolean readEventFlag(File node, StringBuilder failures) {
        try {
            String value = Files.readString(node.toPath()).trim();
            if ("0".equals(value)) return false;
            if ("1".equals(value)) return true;
            throw new IllegalStateException("unexpected value " + value);
        } catch (Exception e) {
            appendEventFailure(failures, node.getName(), e);
            return false;
        }
    }

    private static void appendEventFailure(StringBuilder failures, String source, Exception e) {
        appendEventFailure(failures, source, e.toString());
    }

    private static void appendEventFailure(StringBuilder failures, String source, String detail) {
        if (failures.length() != 0) failures.append("; ");
        failures.append(source).append(": ").append(detail);
    }

    // Called with this held. Camera availability is the authoritative crash/close fallback.
    private void clearCameraRecordStateLocked() {
        if (cameraElement == ThermalScenarioPolicy.OFF && cameraUser == -1) return;
        cameraElement = ThermalScenarioPolicy.OFF;
        cameraUser = -1;
        changed();
    }

    private void startLifecycleObserver() {
        synchronized (this) { watcherError = ""; }
        Thread observer = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                long sample = ThermalLifecycle.sample();
                while (true) {
                    if (lifecycleCheckQueued.compareAndSet(false, true)) {
                        worker.post(() -> {
                            lifecycleCheckQueued.set(false);
                            synchronized (ThermalBackend.this) { sampleLifecycle(); }
                        });
                    }
                    // No backend lock is held across the native futex wait.
                    ThermalLifecycle.awaitChange(sample);
                    sample = ThermalLifecycle.sample();
                }
            } catch (Exception e) {
                worker.post(() -> {
                    synchronized (ThermalBackend.this) {
                        watcherError = "Lifecycle observer stopped: " + e;
                        sampleLifecycle();
                        Slog.e(TAG, watcherError, e);
                    }
                });
                // Stop on failure: unavailable native access must not become a retry spin.
            }
        }, "DashThermalLifecycle");
        observer.setDaemon(true);
        observer.start();
    }

    // Called only on the thermal worker, with this held.
    private long sampleLifecycle() {
        long sample = -1;
        try {
            sample = ThermalLifecycle.sample();
            lifecycleError = !ThermalLifecycle.isPresent(sample) ? "daemon property absent/inaccessible"
                    : !ThermalLifecycle.isRunning(sample) ? "daemon not running" : "";
        } catch (Exception e) {
            lifecycleError = e.toString();
        }
        if (requests.observe(sample)) lifecycleChanged();
        return sample;
    }

    private void lifecycleChanged() {
        forceNextRequest = false;
        for (Runnable retry : retries) {
            if (retry != null) worker.removeCallbacks(retry);
        }
        if (requests.running()) {
            long epoch = requests.epoch();
            for (int i = 0; i < retries.length; i++) {
                final int index = i;
                retries[i] = () -> {
                    synchronized (ThermalBackend.this) {
                        sampleLifecycle();
                        if (!requests.claimRetry(epoch, index)) return;
                        // Retain the force if a concurrent policy event invalidates the query.
                        forceNextRequest = true;
                    }
                    recompute();
                };
                worker.postDelayed(retries[i], ThermalRequestState.retryDelayMillis(i));
            }
        }
        changed();
    }

    private void resync() {
        synchronized (this) {
            sampleLifecycle();
            requests.resync();
            lifecycleChanged();
        }
    }

    private void writeProfile(int target, boolean force) throws Exception {
        sampleLifecycle();
        if (!requests.shouldSubmit(target, force)) return;
        long epoch = requests.epoch();
        long afterSample;
        try (FileOutputStream out = new FileOutputStream(NODE)) {
            out.write((target + "\n").getBytes(StandardCharsets.US_ASCII));
        } finally {
            afterSample = sampleLifecycle();
        }
        if (requests.submitted(epoch, target, afterSample)) {
            Slog.i(TAG, "request=" + target + " scenario=" + scenario + " reason=" + reason
                    + " user=" + foregroundUser + " package=" + foregroundPackage);
        }
    }

    private int defaultProfile(String pkg, boolean performance) {
        String group = groups.getOrDefault(pkg, "unclassified");
        return ThermalScenarioPolicy.select(group, pkg, true, false, ThermalScenarioPolicy.OFF,
                performance && !"unclassified".equals(group), false, false).profile;
    }

    private Config config(int user) throws Exception {
        Config config = configs.get(user);
        if (config != null) return config;
        config = new Config();
        byte[] bytes = stores.apply(user).read();
        if (bytes != null) {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (root.getInt("version") != 1) throw new IllegalStateException("User schema");
            config.enabled = root.getBoolean("enabled");
            config.performanceMode = root.optBoolean("performanceMode", false);
            JSONObject overrides = root.getJSONObject("overrides");
            for (Iterator<String> it = overrides.keys(); it.hasNext();) {
                String pkg = it.next();
                int id = overrides.getInt(pkg);
                if (!ThermalProfiles.selectable(id) || id == ThermalProfiles.AUTO) {
                    throw new IllegalStateException("Invalid saved profile " + id);
                }
                config.overrides.put(pkg, id);
            }
        }
        configs.put(user, config);
        return config;
    }

    private void save(int user, Config config) throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("enabled", config.enabled);
        root.put("performanceMode", config.performanceMode);
        root.put("overrides", new JSONObject(config.overrides));
        try {
            stores.apply(user).write(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Rename may already have published the new bytes. Reconcile, never promise rollback.
            configs.remove(user);
            persistenceErrors.put(user, e.toString());
            changed();
            notifyConfigurationChanged(user);
            throw e;
        }
        configs.put(user, config);
        persistenceErrors.remove(user);
        notifyConfigurationChanged(user);
    }

    private void notifyConfigurationChanged(int user) {
        if (ready) worker.post(() -> {
            try {
                context.getContentResolver().notifyChange(
                        android.net.Uri.parse(PERFORMANCE_STATE_URI), null, 0, user);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Cannot notify configuration observers", e);
            }
        });
    }

    private void enforce(int user) {
        int uid = Binder.getCallingUid();
        if (!(Build.IS_DEBUGGABLE && (uid == Process.ROOT_UID || uid == Process.SHELL_UID))) {
            context.enforceCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS, SERVICE);
            if (context.getPackageManager().checkSignatures(Process.SYSTEM_UID, uid)
                    != PackageManager.SIGNATURE_MATCH) {
                throw new SecurityException("Platform signature required");
            }
            if (UserHandle.getUserId(uid) != user) {
                context.enforceCallingOrSelfPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS_FULL, SERVICE);
            }
        }
        if (user < 0) throw new IllegalArgumentException("Concrete userId required");
    }

    private void enforcePowerKeeperCompat() {
        int uid = Binder.getCallingUid();
        PackageManager packages = context.getPackageManager();
        if (packages.checkSignatures(Process.SYSTEM_UID, uid) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Platform signature required");
        }
        String[] names = packages.getPackagesForUid(uid);
        if (names != null) {
            for (String name : names) {
                if (POWER_KEEPER_PACKAGE.equals(name)) return;
            }
        }
        throw new SecurityException("PowerKeeper compatibility package required");
    }

    private void installed(int user, String pkg) throws Exception {
        if (users.getUserInfo(user) == null) throw new IllegalArgumentException("Unknown user");
        if (pkg == null || pkg.isEmpty()) throw new IllegalArgumentException("Package required");
        context.getPackageManager().getApplicationInfoAsUser(pkg, 0, user);
    }

    @Override public int getApiVersion() {
        enforce(UserHandle.getCallingUserId());
        return API_VERSION;
    }

    @Override public synchronized Bundle getState(int user) {
        enforce(user);
        long identity = Binder.clearCallingIdentity();
        try {
            if (users.getUserInfo(user) == null) throw new IllegalArgumentException("Unknown user");
            Config config = config(user);
            Bundle out = new Bundle();
            out.putInt("version", API_VERSION);
            out.putBoolean("ready", ready);
            out.putBoolean("daemonRunning", requests.running());
            out.putLong("requestEpoch", requests.epoch());
            out.putString("lifecycleError", lifecycleError);
            out.putString("watcherError", watcherError);
            out.putString("cameraWatcherError", cameraWatcherError);
            out.putString("persistenceError", persistenceErrors.getOrDefault(user, ""));
            out.putBoolean("enabled", config.enabled);
            out.putBoolean("performanceMode", config(modeUser(user)).performanceMode);
            out.putInt("currentUserId", currentUser);
            out.putInt("foregroundUserId", foregroundUser == user ? user : -1);
            out.putString("foregroundPackage", foregroundUser == user ? foregroundPackage : "");
            out.putInt("requestedProfile", requests.requested());
            out.putBoolean("appliedConfirmed", false);
            out.putString("reason", reason);
            out.putString("error", error);
            out.putString("eventError", eventError);
            out.putInt("scenarioId", scenario);
            out.putInt("cameraElement", cameraElement);
            out.putInt("cameraUserId", cameraUser);
            out.putBoolean("offHook", offHook);
            out.putBoolean("lowTempCharge", lowTempCharge);
            out.putBoolean("reverseCharge", reverseCharge);
            out.putIntArray("selectableProfiles",
                    new int[] {-1, 0, 1, 6, 7, 9, 10, 11, 12, 15, 19, 20, 25});
            Bundle overrides = new Bundle();
            config.overrides.forEach(overrides::putInt);
            out.putBundle("overrides", overrides);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override public synchronized Bundle getAppPolicy(int user, String pkg) {
        enforce(user);
        long identity = Binder.clearCallingIdentity();
        try {
            installed(user, pkg);
            Config config = config(user);
            int override = config.overrides.getOrDefault(pkg, ThermalProfiles.AUTO);
            Bundle out = new Bundle();
            out.putInt("defaultProfile", defaultProfile(pkg, config(modeUser(user)).performanceMode));
            out.putInt("overrideProfile", override);
            out.putInt("selectedProfile", override == ThermalProfiles.AUTO
                    ? defaultProfile(pkg, config(modeUser(user)).performanceMode) : override);
            out.putString("stockGroup", groups.getOrDefault(pkg, "unclassified"));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    // Managed-profile apps follow their parent user's mode; their app overrides stay separate.
    private int modeUser(int user) {
        UserInfo parent = users.getProfileParent(user);
        return parent == null ? user : parent.id;
    }

    @Override public synchronized void setPerformanceMode(int user, boolean enabled) {
        enforce(user);
        long identity = Binder.clearCallingIdentity();
        try {
            if (users.getUserInfo(user) == null) throw new IllegalArgumentException("Unknown user");
            if (modeUser(user) != user) throw new IllegalArgumentException("Use parent user mode");
            Config previous = config(user);
            Config updated = new Config();
            updated.enabled = previous.enabled;
            updated.performanceMode = enabled;
            updated.overrides.putAll(previous.overrides);
            save(user, updated);
            changed();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override public synchronized void notifyCameraRecordState(
            boolean recording, int quality, int fps) {
        enforcePowerKeeperCompat();
        cameraElement = ThermalScenarioPolicy.cameraRecordElement(recording, quality, fps);
        cameraUser = cameraElement == ThermalScenarioPolicy.OFF
                ? -1 : UserHandle.getUserId(Binder.getCallingUid());
        changed();
    }

    @Override public synchronized void setAppProfile(int user, String pkg, int id) {
        enforce(user);
        if (!ThermalProfiles.selectable(id)) throw new IllegalArgumentException("Unsupported profile");
        long identity = Binder.clearCallingIdentity();
        try {
            installed(user, pkg);
            Config previous = config(user);
            Config updated = new Config();
            updated.enabled = previous.enabled;
            updated.performanceMode = previous.performanceMode;
            updated.overrides.putAll(previous.overrides);
            if (id == ThermalProfiles.AUTO) updated.overrides.remove(pkg);
            else updated.overrides.put(pkg, id);
            save(user, updated);
            changed();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override public synchronized void setEnabled(int user, boolean enabled) {
        enforce(user);
        long identity = Binder.clearCallingIdentity();
        try {
            if (users.getUserInfo(user) == null) throw new IllegalArgumentException("Unknown user");
            Config updated = new Config();
            updated.enabled = enabled;
            updated.performanceMode = config(user).performanceMode;
            updated.overrides.putAll(config(user).overrides);
            save(user, updated);
            changed();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private synchronized void removePackage(int user, String pkg) {
        try {
            Config previous = config(user);
            if (!previous.overrides.containsKey(pkg)) return;
            Config updated = new Config();
            updated.enabled = previous.enabled;
            updated.performanceMode = previous.performanceMode;
            updated.overrides.putAll(previous.overrides);
            updated.overrides.remove(pkg);
            save(user, updated);
            changed();
        } catch (Exception e) {
            Slog.e(TAG, "Cannot remove package override", e);
        }
    }

    @Override protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        context.enforceCallingOrSelfPermission(Manifest.permission.DUMP, SERVICE);
        pw.println(getState(currentUser));
    }

    @Override public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver receiver) {
        int uid = Binder.getCallingUid();
        if (!Build.IS_DEBUGGABLE || (uid != Process.ROOT_UID && uid != Process.SHELL_UID)) {
            throw new SecurityException("Debug shell only");
        }
        new ShellCommand() {
            @Override public int onCommand(String cmd) {
                if (cmd == null) return handleDefaultCommands(cmd);
                try {
                    switch (cmd) {
                        case "status": getOutPrintWriter().println(getState(currentUser)); return 0;
                        case "get": {
                            int user = Integer.parseInt(getNextArgRequired());
                            getOutPrintWriter().println(getAppPolicy(user, getNextArgRequired()));
                            return 0;
                        }
                        case "set": {
                            int user = Integer.parseInt(getNextArgRequired());
                            String pkg = getNextArgRequired();
                            setAppProfile(user, pkg, Integer.parseInt(getNextArgRequired()));
                            return 0;
                        }
                        case "enable": {
                            int user = Integer.parseInt(getNextArgRequired());
                            String value = getNextArgRequired();
                            if (!value.equals("true") && !value.equals("false")) {
                                throw new IllegalArgumentException("true or false required");
                            }
                            setEnabled(user, Boolean.parseBoolean(value));
                            return 0;
                        }
                        case "resync":
                            worker.post(ThermalBackend.this::resync);
                            return 0;
                        default: return handleDefaultCommands(cmd);
                    }
                } catch (Exception e) {
                    getErrPrintWriter().println(e);
                    return -1;
                }
            }
            @Override public void onHelp() {
                getOutPrintWriter().println("status | get USER PACKAGE | set USER PACKAGE ID\n"
                        + "enable USER true|false | resync\n"
                        + "ID: -1 auto, 0 normal, 7 class0, 11 video, 19 mgame, "
                        + "20 yuanshen, 25 xingtie. Writes acknowledge saved config, not actuation.");
            }
        }.exec(this, in, out, err, args, callback, receiver);
    }
}
