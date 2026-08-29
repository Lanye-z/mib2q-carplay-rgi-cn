# iOS 27 / Amap v38 compatibility merge

Branch: `ios27-amap-v38-merge`

## Goal

Preserve the current repository's vehicle-tested fixes (first-frame renderer startup, U-turn direction mapping, China-specific approach thresholds, native-navigation handoff, renderer/BAP cleanup) while restoring the Amap lifecycle tolerance found in the supplied `20260813_v38.zip` build.

## Why iOS 27 fails on the current lifecycle gate

Observed iOS 27 + Amap sequence:

1. Route Guidance starts normally (`route_state=3`, `source_supports_rg=1`).
2. Renderer connects and produces its first frame.
3. Amap reports `route_state=1`, `maneuver_count=0`, `visible_in_app=0`.
4. Maneuver count/list/distance arrive shortly afterward and continue updating.

The current `RouteGuidance` treats an explicit `visible_in_app=0` as authoritative and tears down BAP + renderer before step 4 arrives.

## v38 behavior adapted here

The merge intentionally does not overwrite the current `BAPBridge`, renderer, or native hook with older binaries. Instead it adds a compatibility adapter around the current `RouteGuidance`:

- `route_state=0` or `source_supports_rg=0` is a hard clear.
- `route_state=1`, `maneuver_count=0`, `visible_in_app=0` is a soft-inactive state.
- An already active route gets a 5-second grace window.
- Maneuver/list/distance/ETA/lane updates count as fresh guidance evidence and keep the route alive.
- A non-authoritative Amap `visible_in_app=0` is interpreted by the existing `RouteGuidance` as `-1` (unknown), not rewritten to `1`.
- During the grace window, transient `maneuver_count=0` and empty maneuver-list clears are held so the current maneuver snapshot does not flash to `NO_SYMBOL`.
- If no fresh guidance evidence arrives before the grace expires, the real `visible_in_app=0` is forwarded and the existing full shutdown path runs.
- An internal active latch prevents repeated zero-only frames from re-spawning the renderer after a genuine soft-inactive expiry.

## Native hook decision

The current repository's `rgd_hook.c` already contains route-state-zero debounce and maneuver-cache preservation. Those changes are newer/more suitable than replacing the hook with the supplied v38 binary, so the native hook is intentionally left unchanged.

## Expected iOS 27 log behavior

For the previously failing sequence, the branch should show:

```text
[AmapRouteGuidance] RG soft-inactive window started: route_state=1 maneuver_count=0 visible_in_app=0 ...
```

Then, when `maneuver_count=1/2` and distance/ETA updates arrive within the window, there should be **no immediate**:

```text
[RouteGuidance] RG deactivate: route_state=1 ... visible_in_app=0
[RendererServer] Sent CMD_SHUTDOWN
```

A true route end (`route_state=0`, `source_supports_rg=0`, or actual disconnect) still uses the existing teardown path.

## Files changed

- `java_patch/com/luka/carplay/CarPlayHook.java`
- `java_patch/com/luka/carplay/routeguidance/AmapCompatibility.java`
- `java_patch/com/luka/carplay/routeguidance/AmapRouteGuidance.java`

The normal Java build script already compiles every `.java` file under `java_patch`, so no build-script change is required.
