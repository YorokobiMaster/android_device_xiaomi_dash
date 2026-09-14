# Dash fingerprint bridge

`dash-fod` is a device-specific `system_server` service. It registers the
framework authentication listener in `onStart()`, after AuthService exists but
before SystemUI starts. The framework authentication client and its observer
therefore have the same process lifetime. No restart snapshot, synthetic
visible-Doze authentication, or APK-owned vendor state is needed.

The `DashFod` privileged APK now owns only PIN-layout presentation. Restarting
it does not stop/recreate a vendor authentication session. Keep its WAKE_LOCK
permission: FingerprintManager's lockout-reset callback uses a wake lock.

## State ownership

- Framework START/STOP/ERROR/SUCCEEDED events own the requested operation.
- `FodController` separately records successfully applied vendor state and any
  outstanding cleanup. A failed command cannot turn a surviving request into
  an already-dispatched-but-disarmed gate.
- Keyguard arming requires current-user enrollment and either interactive state
  or the screen-off UDFPS switch. AOD, display visibility, pickup and gaze do not
  own authentication. Disabling screen-off UDFPS pauses an existing keyguard
  request while noninteractive; returning to allowed policy reconciles it.
- Vendor death invalidates applied state, not the framework's request. Recovery
  first cleans the new connection and only reapplies a request that has not
  received a terminal event. User switching explicitly discards the old user's
  request. A terminal received while reconnecting prevents replay.
- Failed availability synchronization, arming or cleanup retries on the worker
  with 1–30 second backoff. Stable or policy-paused states do not poll. Vendor
  lookup is nonblocking; registration is never queued behind vendor IPC.
- Native mfp/touch still owns finger input. DashWake still owns wake gestures;
  the ambiguous FOD-motion sensor is not restored as a physical-finger trigger.

The FOD overlay is inherited before other device overlays. Resource arrays do
not merge, so the optional power-enabled variant includes both device service
classes; the power-disabled variant includes only FOD. Neither variant changes
DashPower's implementation or its feature switch.

## Targeted checks

From the Android source root, with existing platform build dependencies:

```sh
device/xiaomi/dash/fod/test.sh
```

This compiles all FOD service/APK sources against cached platform, services and
vendor-interface jars, then compiles and runs the current controller tests.
The existing host test jar supplies JUnit only; its old test/production classes
are not used. Temporary javac outputs are removed. No Android image build is
started.

Checked during this change:

- Source compilation and controller regression tests (see commit/test output).
- Product dumpvars with power hooks on/off: `system_ext:dash-fod` is on the
  server classpath and the appropriate FOD overlay has highest device priority.
- Live combined SELinux policy permits system_server to find
  `vendor_hal_fingerprint_service_xiaomi` and call/transfer to
  `hal_fingerprint_server`; no policy widening was necessary.

Full image build and device validation are deferred at the user's request.
Do not claim the earlier intermittent awake-unlock incident is reproduced or
fixed by host tests. The later device matrix must cover black-screen/AOD-off
unlock, awake unlock, disabling screen-off UDFPS, lockout/strong-auth, PIN APK
restart, vendor restart, and secondary-user transitions. This process-boundary
change requires the jar, framework resource registration and PIN APK together;
installing only an APK is not a valid deployment.
