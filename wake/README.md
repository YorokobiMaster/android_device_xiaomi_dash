# DashWake

One persistent APK currently handles double-tap configuration and pickup wake.
Double-tap reports `KEY_WAKEUP` through the existing touch driver and reaches
Lineage's proximity gate. After a pickup wake, the controller keeps listening
while the keyguard is still up and calls `PowerManager.goToSleep()` when the
phone is put back down (sensor values 2.0/0.0), matching the stock keyguard
put-down loop; unlocking disengages it. A gaze-to-wake implementation
following stock's `miui_people_near_screen_on` lifecycle remains in source, but its controller
entry point and Settings switch are intentionally commented out pending a clear
product decision and physical false-wake testing.

`WakeSettingsProvider` supplies pickup and put-down switches under Settings > Display.
The standard double-tap switch uses `Settings.Secure.DOUBLE_TAP_TO_WAKE` and
continues to drive Xiaomi touch mode 14.

Pickup and put-down use per-user Secure keys `dash_pickup_wake_enabled` and
`dash_put_down_sleep_enabled`, both defaulting to enabled. On first use,
`WakeSettings` migrates `doze_pick_up_gesture`, preserving a disabled value and
any existing new-key choice. The parked `dash_gaze_wake_enabled` key is neither
created nor observed.

The private `:aov` process hosts the vendor client and AOSP Screen attention
service. Its Application startup skips gesture listeners and touch I/O. The
parked gaze-to-wake controller does not bind its bridge.

## Screen attention

The framework overlay exposes AOSP `adaptive_sleep` and selects
`DashAttentionService` as the system attention backend. For each framework
request, the service starts the unlocked AOV route and reports PRESENT if gaze
is detected. It does not turn the first non-gaze frame into ABSENT; if no gaze
arrives, `AttentionManagerService` cancels the request at the end of its check
window and the service tears AOV down. The standard Settings switch requires
the package's default CAMERA grant and follows camera privacy and battery-saver
policy in the framework.

The AOV bridge reports presence before tearing the vendor session down.

## Parked gaze-to-wake

The stock-style continuous gaze-wake lifecycle is disabled and has not been
tested on a device. If restored, physical validation must cover screen-off
session start, first-hit latency, false wakes, camera privacy, wake teardown and
service-death retry.

## Prior settings migration validation

The previous validation record reports an APK and JNI build with
`lineage_dash bp4a userdebug`. The built JNI
also accepted the existing enabled DT2W setting in an isolated process on dash;
this did not replace the installed APK/library or verify physical gestures.
Settings migration and the rejected-wake pickup path still need device testing.
Deploy APK and JNI together (the APK uses `/system_ext/lib64/libdashwake_jni.so`),
plus the framework resource change when validating the settings migration.
Verify both exposed switches, preservation of a disabled old preference,
removal of the old pickup entry, pickup with always-on both off and on,
double-tap, and existing black-screen fingerprint authentication.
