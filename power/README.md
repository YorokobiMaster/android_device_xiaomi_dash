# dash power adapter and per-app thermal backend

Copyright (C) 2026 GitHub @YorokobiMaster. Apache-2.0.

This optional system-server extension uses the **retained** MTK power service
for finite launch/fling requests and the retained thermal daemon for per-app
scene selection, with a shared balanced/performance mode and selected stock event inputs
for automatic app policies. It keeps vendor binaries and the standard Power HAL path.

The policies have separate `DashPower` and `DashThermal` workers: synchronous
vendor boost IPC must not block thermal recomputation. Binder configuration
saves still serialize with thermal decisions; separate workers do not provide
a bounded recovery guarantee when storage or the thermal worker stops progressing.
See `THERMAL_API.md` for configuration, daemon resubmission and confirmation limits.

## Supported baseline and integration

* Framework: Lineage 23.2 `frameworks/base` commit
  `ce690697d1f6d60ddf2785374432fa812e0c7c7b`.
* Device implementation started at dash tree commit
  `84e6374b453776d3946591b5b007b204f889b449`.
* Vendor contract: dash OS3.0.305.0.WPLCNXM, IMtkPowerService V3. The local AIDL
  is an explicitly numbered **client subset**, not a complete frozen interface.
  `perfCusLockHint` is method 0 and `perfLockReleaseSync` is method 3. Do not
  reuse this wire contract on another vendor without checking its implementation.

From the Android source root, on a clean matching framework baseline:

```sh
git -C frameworks/base apply --check ../../device/xiaomi/dash/power/patches/frameworks-base.patch
git -C frameworks/base apply ../../device/xiaomi/dash/power/patches/frameworks-base.patch
```

The device product explicitly opts in using `DASH_ENABLE_POWER_HOOKS ?= true`.
Set this variable to `false` before product evaluation to exclude the backend,
thermal Settings app, native lifecycle reader, defaults, system-server classpath
entry and service-loading resource. The source interface
can remain installed with no listener. An absent adapter causes no extra vendor
requests. No SystemServer patch or foreground/gesture polling is used.

The overlay owns `config_deviceSpecificSystemServices`. If another device already
uses that array, merge its existing entries; replacing them is not supported.
The classpath library installs to `system_ext/framework/dash-power.jar`.

## Policy and ownership

PMS sends state and launch lifecycle after its exact launch-disabled battery
policy, including adaptive policy; its native calls retain their return values.
Mode calls and callbacks are ordered under its existing lock. Device callbacks
only enqueue work. State is seeded at registration without replaying an active
launch. DisplayPolicy supplies a true fling, not all INTERACTION events.

* Coarse accepted LAUNCH maps intentionally to ordinary process-start hint **21**,
  bounded to 10 seconds. Launch end releases it. This is not cold/warm stock parity.
* Built-in-display fling maps to **900**, capped to 5000 ms plus the recovered
  stock 160 ms tail. Nonpositive durations and non-default displays are ignored.
* Noninteractive, launch-disabled policy, runtime disable, or HAL failure cancel
  queued requests and release owned active handles. Waking, leaving saver and
  reconnecting do not replay previous events. Fresh events can acquire again.
* Both requests have finite vendor timeouts and worker cleanup. Generation checks
  prevent stale timers or queued events from releasing a replacement request.
* Handles retain their exact Binder owner. Old handles are never released through
  a reconnected service; HAL restart can reset its numeric handle allocation.
* No global `mtkPowerHint` slots or its end-tail reacquisition API are used.

The launch/fling path adds no wake lock, sconfig/balance write or raw frequency
override. It adds system_server lookup of `mtk_hal_power_service`; existing vendor
policy already permits the Binder calls. The separate thermal path writes only
sconfig and reads the fixed `init.svc.mi_thermald` property with its revision.
Neither the revision nor successful node writes confirm that a table was loaded.

## Disable and remove

On a root-enabled development device:

```sh
adb -s Y5NBWWBMVCYXTG9X shell setprop persist.sys.dash.power.enabled false
```

This disables only launch/fling: it asynchronously releases the adapter's handles
and keeps the standard HAL calls. Thermal selection uses its own per-user enable
setting. Finite vendor timeout remains the boost fallback if IPC fails.
Setting it true accepts **new** events only. The property defaults to true only
when the opted-in adapter is loaded. Verify runtime property access under Enforcing;
source declarations and host tests alone do not establish device behavior.

To remove, exclude the device feature and build coherent system/system_ext images;
then reverse the isolated framework patch (use `git apply -R --check` first).
Do not mix arbitrary old framework and new adapter JARs. EROFS deployment uses
partition images, not replacing a mounted JAR. No user-data schema migration or
vendor configuration restore is required. Existing OTA packaging is outside this
feature; a patch file is not an OTA installer.

## Build and verification

Reuse the normal source-root build environment and output cache:

```sh
bash device/xiaomi/dash/power/tools/test.sh
m dash-power dash-thermal-client dash-thermal-packages libdashthermal_jni \
    DashThermal DashThermalBackendTests services framework-res selinux_policy
bash device/xiaomi/dash/power/tools/check-services.sh
```

Then use the normal coordinated image build/deployment path. `services` contains
hooks in system; dash-power installs in system_ext; the framework resource and
overlay packaging must be included from the same build. Build validation, installed
artifact validation and thermal/performance benefit are separate acceptance gates.

Host tests cover boost lifecycle/generation, the fixed dash 305 scenario tree, the production NIO configuration
store (including injected commit failures), request revision/retry bookkeeping,
and reportable native load/wait failures. `DashThermalBackendTests` uses real
RunningTaskInfo and backend setters with test-owned storage; it does not start
live lifecycle/sysfs work. Existing PMS tests cover initial state, no replay,
exact launch filtering, adaptive policy and separation from generic interaction.
Compiling instrumentation tests is not executing them on a suitable target.

The framework patch also preserves the cross-JAR interface and its Listener in
services/proguard.flags. Ordinary SYSTEM_OPTIMIZE_JAVA shrinking does not enable
the downstream reference tracing used by FULL_SYSTEM_OPTIMIZE_JAVA. Do not omit
these keep rules: compilation can pass while R8 removes the registration method
and callbacks. check-services.sh checks all five signatures in the installed DEX
directly; it fails on the initial broken build. It does not replace live tests.

Live acceptance under Enforcing must observe one actual launch and fling request,
temperature-qualified backend action, end/timeout release, sleep/wake without
replay, battery policy and runtime opt-out, plus normal touch/ADPF/vendor consumers.
Use DashPower logcat acquire/release records alongside backend output; frequency
alone is not a latency, jank or energy benefit measurement. Do not advertise benefit
before those measurements.

Regenerate distribution material from this source root after editing hooks:
`bash device/xiaomi/dash/power/tools/export-hooks.sh`. Keep framework changes in a
separate review/commit from the device implementation when publishing. Nothing in
these scripts commits or pushes.

### Repair verification — 2026-09-14

The current working-tree repair passed 27 boost, 73 durable-store and 78 thermal
request-state host checks, plus repeated reportable JNI sample/wait load failures.
The module command above completed successfully for `lineage_dash bp4a userdebug`
in the existing `out` (4m24s). The first attempt failed during lunch parsing due
to the session's grep wrapper; using system grep in that build shell resolved it.
Log: `tmp/disposable-anytime/thermal-repair-build.log`.

Installed services DEX retained all five required hook signatures. Both JNI exports
were present in `system_ext/lib64/libdashthermal_jni.so`. Product-variable checks
confirmed the frontend/backend/defaults and classpath entry are included together
when enabled and absent together when disabled. The compiled merged policy permits
system_server to read the lifecycle property and write the specific sconfig node;
no additional permission was needed.

No device execution or flashing was performed. Android tests were compiled only.
Successful Android native loading, observer/retry execution, actual table loading,
Enforcing-device behavior and physical power-loss durability remain unverified.
The finite retry sequence is best-effort delivery, not a readiness or loading ACK.

### Performance mode and event scenarios — 2026-09-16

Battery now has an injected native Performance Mode switch; the same APK exposes a
standard Quick Settings tile with a tintable 24dp gauge icon. Both use API version 3
of the existing dash_thermal service. Automatic profiles switch with the mode;
manual overrides win, unknown apps always use normal, and the default is balanced.
The mode is persisted alongside existing per-user configuration. See THERMAL_API.md.

Automatic policies now arbitrate the retained dash 305 scenario tree. Calls, stock-camera
recording modes, extreme-cold charging, active reverse charging and the Douyin foreground
state have Lineage-side inputs. Manual app overrides bypass event arbitration. IEC,
SpecialCScenario, playback high-FPS and SPTM_2 remain without a Lineage source.

The Java rule table was checked against all 63 rules in the retained 305 setting.xml. The
module and new host test were not compiled at the user's request, and no device verification
is claimed for this change.
