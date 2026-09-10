# DashWake

One persistent APK handles double-tap configuration, pickup wake and gaze wake.
Pickup and gaze call `PowerManager.wakeUp()`; double-tap reports `KEY_WAKEUP`
through the existing touch driver. SystemUI owns the lockscreen and optional
always-on display. No gesture requests an AOD pulse.

`WakeSettingsProvider` supplies pickup and gaze switches under Settings > Display.
The standard double-tap switch uses `Settings.Secure.DOUBLE_TAP_TO_WAKE` and
continues to drive Xiaomi touch mode 14. The framework's
`config_dozePulsePickup=false` removes the former Doze pickup setting.

Pickup and gaze use per-user Secure keys `dash_pickup_wake_enabled` and
`dash_gaze_wake_enabled`, both defaulting to enabled. On first use, `WakeSettings`
migrates `doze_pick_up_gesture` and `dash_smart_aod_enabled`, preserving disabled
values and any existing new-key choice. It deletes each old key only after its
replacement exists. Normal gesture operation observes only the new keys.

The private `:aov` service performs vendor Binder calls and delivers one presence
result per request. Its Application startup skips gesture listeners and touch
I/O. Both processes retain system UID / `system_app`; this settings migration
adds no permissions or SELinux rules.

## Validation pending build approval

This settings migration has only received static checks, not compilation or
device verification. Build/install the APK and framework resource change
together. Verify both switches, preservation of a disabled old preference,
removal of the old pickup entry, pickup/gaze with always-on both off and on,
double-tap, and existing black-screen fingerprint authentication.
