# DashLED

The settings entry belongs to Notifications → Manage. There is no launcher
activity. Notification alerts and third-party ring effects share one arbiter;
notifications temporarily take priority over third-party effects.

## Built-in notification effects

The master switch controls all ring output. Notification alerts have a separate
switch, seven default colors, and per-app switches for apps with launcher entries.
New notifications play three breathing cycles across all four zones; ongoing
notifications and DashLED's own notifications are ignored. A newer notification
replaces the previous pulse. Charging effects and camera countdowns are not
implemented as built-in producers.

## Third-party effects

The `aidl/` directory is the client API source. Include these files in an Android
app's AIDL source set, request `me.sandai.dashled.permission.CONTROL`, and declare
this application metadata so the app appears in the user grant list:

```xml
<meta-data android:name="me.sandai.dashled.CLIENT" android:value="true" />
```

After the user grants the app under “Apps allowed to control the ring light”,
bind with action `me.sandai.dashled.BIND` and package `me.sandai.dashled`.
Acquire `CATEGORY_THIRD_PARTY` with a client-owned `Binder` token; keep the
connection and token alive while using the session. Send four-zone frames or
breath effects. Call `release()` when finished. Client death also releases the
session. Merely dropping the session proxy does not release it.

The app can generate its own animations through `setFrame()`. `playEffect()`
offloads compatible breathing to the chip; more than 255 finite repeats or
unsupported periods use software rendering. Software animation pauses while
the app cannot run; hardware breathing continues while the CPU sleeps.

Notification access is managed by Android. The device overlay supplies the
default listener package for initial setup; the app never rewrites the user's
listener grants. Existing installs without access, and users who revoked it,
can open the system permission page from DashLED settings.

## Validation

`DashLedTests` is a device instrumentation test covering queued-command
cancellation, repeat-count overflow, completion after sleep, and stale
completion callbacks. Hardware and SELinux verification still require the
installed device build.

Verified on dash with SELinux Enforcing on September 11, 2026: four arbitration
tests pass; notification breathing starts and shuts down correctly across screen
transitions and simulated battery changes. Stock Lights HAL tracing found no
ring writes during those scenarios with the framework interception removed.
External client binding/grants and actual CPU suspend were not integration-tested.
