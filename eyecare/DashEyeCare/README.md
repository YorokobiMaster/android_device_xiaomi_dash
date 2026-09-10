# Night Light → Xiaomi displayfeature bridge

`DashEyeCare` (`me.sandai.dasheyecare`) is now a platform-signed persistent,
direct-boot-aware application with no Activity, tile, alarm or SettingsLib dependency.
R8 remains enabled. It leaves Night Light's native page, continuous slider, custom/
sunset scheduling, manual override and QS tile in charge of the user experience.

The sole configuration source is the foreground user's `Settings.Secure`
`night_display_activated` and `night_display_color_temperature`. One bridge process
in user 0 observes these keys for all users and resynchronizes on user switch,
unlock, process startup and changes. Cross-user reads use the signature permission
`INTERACT_ACROSS_USERS_FULL`; `MANAGE_USERS` is required to receive the protected
USER_SWITCHED broadcast. Unlock/switch broadcasts are observed across all users.
It never writes Night Light settings.

The CCT is clamped to the framework's resource range and mapped monotonically:
maximum CCT → vendor level 58, default CCT → 156, minimum CCT → 255. The two spans
are linear around the default, preserving both systems' defaults. This maps a UI
control; it does not claim the vendor levels are measured Kelvin values.
Off → 0. `sys.dash.livedisplay.eyecare` is a transient, derived HAL request with an
exact integer property context, written only by the `dash_eyecare` app domain.
There is no duplicate preference store or persistent property write on dragging.
Legacy `persist.dash.livedisplay.eyecare` and old app preferences are unused.

`DashFrameworkResOverlay` sets both Night Light coefficient arrays (linear/native)
to `[0,0,1, 0,0,1, 0,0,1]`, making its matrix identity at every temperature. Other
framework display matrices remain under their original owners. Actual warm tint
comes from `dash-livedisplay` → vendor displayfeature feature 3, cookie 255.

Native on/off fades last 300 ms, driven at approximately 16 ms intervals only
while active. Strength updates are immediate; reverse toggles start at the last
applied level. Client/HAL reconnection directly restores the latest request,
including off. Ordinary errors also wake the retry worker. Bridge read/write
failures retry after one second; queued slider events are coalesced.

Deploy the APK, native service, matching system_ext property contexts and product
framework RRO together. Reboot for the persistent process and framework resource
change. A standalone APK update is not a complete transition from the previous
implementation. Only the native Night Light controls should remain visible.
Old custom scheduling is not imported into Night Light; its existing settings win.

From the source root, use the existing `lineage_dash-bp4a-userdebug` environment
and shared `out`; modules are `DashEyeCare`, `dash-livedisplay`,
`DashFrameworkResOverlay`, `DashEyeCareHostTest`, and `DashLiveDisplayHostTest`.
Validation and deployment limits: `docs/dash/eyecare-2026-09-10.md`.
