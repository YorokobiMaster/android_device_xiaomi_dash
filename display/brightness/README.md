# dash ambient and brightness cap policies

Copyright (C) 2026 GitHub @YorokobiMaster. Apache-2.0.

This optional system-server extension routes dash's stock dual-ALS ambient
fusion, user-curve shaping, short-term reset, automatic-brightness ramp, and vendor
content/thermal brightness ceilings through five
framework seams added by `patches/frameworks-base.patch`:

* `DashAmbientPolicy` subscribes the assist light sensor and torch state, and
  feeds a pure, host-testable `DashAmbientEstimator` that arbitrates the final
  source for the framework's `AutomaticBrightnessController` (ABC). ABC owns
  the primary sensor, main ring/filter/threshold/deadline state, final lux and
  brightness update. The policy owns only the assist ring/filter and its
  independent assist evaluation message on the same looper.
* `DashBrightnessPolicy` observes the four stock thermal sysfs nodes with
  `FileObserver` and drives the vendor DisplayFeature HAL (display-state notify,
  grayscale histogram sampling) so the framework `DisplayPowerController`
  clamper sees content and thermal nits ceilings. Thermal observation and HAL
  Binder calls run on separate workers. The thermal observer survives screen
  off, failed reads retain the last input, and only an effective-cap change
  requests another display evaluation.
* `DashBrightnessRampPolicy` supplies stock reason-8 brighten/darken rates in
  linear brightness space. `DisplayPowerController` arms it only for an
  unmodified automatic slow transition, while `RampAnimator` retains the frame
  clock, exact current/target state, property write and completion callback.
* `DashBrightnessCurvePolicy` implements stock `smoothNewCurveV2()` in physical
  nits across the five stock lux regions. It is used only by the default
  brightness configuration; custom configurations retain AOSP smoothing.
* `DashShortTermModelPolicy` replaces AOSP's lux-window reset decision with the
  stock screen-off rules: 30 minutes unconditionally, or 6 minutes with a lux
  delta greater than 20, followed by the stock lux/nits envelope clamp.

The product opts in through `brightness.mk` (system_ext JAR, displayconfig
files) and `rro/DashFrameworkResOverlay/res/values/brightness.xml`
(`config_deviceAmbientPolicy`, `config_deviceBrightnessPolicy`,
`config_deviceBrightnessRampPolicy`, `config_deviceBrightnessCurvePolicy`,
`config_deviceShortTermModelPolicy`). Policies load only for the default
internal display; an unavailable configured policy falls back to normal AOSP
behavior.

## Supported baseline and integration

* Framework: Lineage 23.2 `frameworks/base` commit
  `ce690697d1f6d60ddf2785374432fa812e0c7c7b` ("aapt2: Sanitize Javadoc
comments to prevent code injection"). The patch contains only this
feature's hunks: the five policy interfaces, ABC/DPC/mapper/animator/clamper wiring, config
  symbols, this feature's ProGuard keeps and its framework tests.

From the Android source root, on a clean matching framework baseline:

```sh
git -C frameworks/base apply --check ../../device/xiaomi/dash/display/brightness/patches/frameworks-base.patch
git -C frameworks/base apply ../../device/xiaomi/dash/display/brightness/patches/frameworks-base.patch
```

Patch order with `device/xiaomi/dash/power/patches/frameworks-base.patch`
(power hooks): either order applies cleanly — verified 2026-09-20 with
`git apply --cached` on a temporary index at the baseline in both orders.
The two features both extend `services/proguard.flags`; this patch appends its
keep block at end of file so neither hunk depends on the other's context.

### Regenerating the patch

The patch was re-exported on 2026-09-20 with a temporary git index, never a
whole-worktree diff, so unrelated uncommitted work (power hooks, SystemUI
doze) stays out of it:

```sh
cd frameworks/base
export GIT_INDEX_FILE=$(pwd)/../../tmp/disposable-anytime/patch-index
git read-tree ce690697d1f6
# Overlay the current feature file contents (21 files), then append this
# feature's keep block at the end of a clean-baseline services/proguard.flags
# and overlay that. The file list is exactly the patch's diff --name-only.
git diff --cached ce690697d1f6 > ../../device/xiaomi/dash/display/brightness/patches/frameworks-base.patch
```

Replay verification: applying the exported patch with `git apply --cached` on
a fresh temporary index at the baseline reproduces the export reference tree
byte-for-byte (`git write-tree` identical). Reverse-apply against the live
worktree is expected to fail on `services/proguard.flags`: the worktree mixes
in the power-hooks keep rules, which this patch deliberately does not carry.

## Tests

Host (from the Android source root, no device):

```sh
bash device/xiaomi/dash/display/brightness/tests/ambient-host.sh
python3 device/xiaomi/dash/display/brightness/tests/verify_resources.py
```

`ambient-host.sh` compiles the real platform spline and cap tables and runs
`DashAmbientEstimatorTest`, `DashBrightnessCurvePolicyTest`,
`DashShortTermModelPolicyTest`, and `ThermalCapsTest` under JUnit, then the
main()-style `BrightnessCapsTest` against the shipped stock thermal XML.
`BrightnessCapsTest` lives in `tests/me/...`, outside the `tests/src/**` glob
of the `DashBrightnessTests` android_test module: running the instrumentation
suite never runs it, so this shell entry is its only repeatable host entry.

Device (instrumentation, requires a booted dash build):

```sh
atest DashBrightnessTests
```

This covers `DashAmbientPolicyTest` (sensor/torch/camera lifecycle with mocked
services) and `DashBrightnessPolicyTest` (DisplayFeature HAL connect, register,
enable/disable, death, reconnect, stale-callback and stop semantics against a
fake local binder), plus `DashBrightnessRampPolicyTest` (stock brighten/darken
branches, low-light tail, HBM conversion and invalid-input fallback).

## Capabilities

Implemented and host/device-test covered:

* Dual-ALS arbitration with per-input freshness, switch reasons, dropped-sample
  counters and torch masking (assist channel closed while torch is on, cooldown
  deadline after torch-off); on-change sensor silence is never treated as
  failure; stale cached sensor events predating registration are rejected. ABC
  retains the stock main timer while the policy schedules assist deadlines
  independently.
* ABC dump of the device policy state and failure; device-policy retry on a new
  bright session.
* Thermal caps from the stock multi-factor XML, including the 0.5 C temperature
  gate, default-condition fallback, manual 6000-lux input, HDR condition -2,
  safety-zero clear, and stock rule that safety only tightens a finite table
  cap. There are no invented leases or periodic refreshes.
* Content SDR caps through DisplayFeature case 95000. Sampling runs only for an
  interactive automatic display with raw HDR content or lux above 20000;
  disabling it restores the stock gray sentinel 163. Valid callbacks update
  diagnostics, but only an SDR cap-bin or HDR peak-gate change requests another
  display evaluation. HDR scenes do not receive the SDR OPR cap.
Implemented and source-tested, awaiting on-device ramp timing validation:

* Stock automatic reason-8 dynamic ramp with 35/87.450005/265-nit brighten
  anchors and the stock 24-second darkening tables. The policy is queried every
  animation frame in linear space; manual, HDR, HBM authorization, BCBC,
  thermal and the dormant refactor animator remain outside this ramp phase.
* Stock five-region V2 user-curve shaping and short-term reset/envelope policy.
  Host tests cover brighten/darken anchors in every region and both reset-time
  branches; framework and instrumentation APKs compile, but these two paths
  have not yet been exercised on a physical device.

Signed off on-device (Batches 5/6, evidence under
`tmp/disposable-anytime/brightness-b5/` and `brightness-b6/`):

* End-to-end lux/nits behavior: flashlight masking + cooldown, front-sensor
  occlusion failover with all four switch reasons, pocket in/out fast channel,
  walking debounce, 20x screen on/off with clean generations, 60s on-change
  silence, grayscale caps, RBC/night-display non-interference (bit-identical
  brightness).
* `DashBrightnessTests` all green on device; `BrightnessMappingStrategyTest`
  21/21 after removing the long-term learner tests. `AutomaticBrightnessControllerTest` is blocked
  by the environment
  (`MANAGE_ACTIVITY_TASKS` signature|recents hard limit under release-keys;
  upstream cases fail identically) — not a code failure.

Implemented and source-tested, awaiting on-device HDR/HBM validation:

* Sunlight HBM authorization is owned by the native
  `HighBrightnessModeController`: automatic mode, the stock 7180-lux threshold,
  low-power policy and the DDC time allowance determine entry. The dash DDC
  carries zero HBM time quotas, so no parallel device timer is added.
* Stock HDR peak gating: normal HDR maximum 0.805701, unlocked to 1.0 only in
  automatic mode above 100000 lux with legal grayscale at most 219. Raw HDR
  layer presence selects thermal condition -2; HDR OPR remains disabled as in
  stock.

Not yet covered (daily-use items):

* HDR content playback, AOD, UDFPS interactions.
* On-device thermal node-change delivery and content/thermal cap transitions
  after the stock-parity observer rewrite.

Deliberately not implemented:

* BCBC cloud app allowlist, Gallery HDR, Dolby/CineLook application policy.
* Any per-experiment build isolation: builds reuse the shared `out/` caches.

## NEXT_CHECK.md closure

`NEXT_CHECK.md` (2026-09-14 grayscale-callback follow-up list) is retired.
Every checklist item is now covered by `DashBrightnessPolicyTest`:

1. Interactive + automatic shows callbackRegistered/displayStateSynced/
   histogramEnabled -> `connectRegistersCallbackThenNotifiesOnThenEnablesHistogram`.
2. Screen-off/on replays 56=off then 13=ON -> 56=on ->
   `realNonInteractiveTransitionNotifiesDisplayOff` plus the reconnect half of
   `staleSessionCallbackCannotResurrectGray`.
3. Auto->manual->auto clears gray and rejects queued stale callbacks ->
   `manualModeDisablesHistogramAndClearsGray`.
4. HAL reconnect re-registers, replays enable order, recovers gray ->
   `staleSessionCallbackCannotResurrectGray` (real service restart on device
   still requires explicit authorization, unchanged).
5. Ordinary enable failure and `stop()` never send 13=OFF; expired callbacks
   stop publishing -> `ordinarySetFeatureFailureDoesNotFakeDisplayOff`,
   `stopDisposesWithoutDisplayOffAndCallbacksAreIgnored`.
6. Enforcing AVC / crash audit remains a device-side Batch 5 item.
