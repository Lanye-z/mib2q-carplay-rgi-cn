# Amap legacy / v38 dual-protocol compatibility

Branch: `ios27-amap-v38-merge`

## Goal

Keep the current repository's vehicle-tested `main` behavior unchanged for legacy/older Amap protocol sessions, while enabling the supplied `20260813_v38.zip`-derived compatibility state machine only when the newer Amap lifecycle behavior is actually observed.

This is intentionally **behavior based**, not hard-coded to an iOS version number. That avoids changing a working iOS 17/18/26 Amap path simply because a phone OS version matches a range, and it also allows the same compatibility path to survive later iOS releases if they keep the new behavior.

The current repository's first-frame renderer startup, U-turn direction mapping, China-specific approach thresholds, native-navigation handoff, BAP cleanup, renderer cleanup and native `route_state=0` debounce remain the output/backend implementation in both modes.

## Protocol router

`AmapRouteGuidance` now has three session states:

```text
LEGACY
  |
  | already-active route later reports
  | route_state=1, maneuver_count=0, visible_in_app=0
  v
PROBING  (max 5 s)
  |\
  | \ no continuing maneuver evidence / visible returns to 1
  |  +--------------------------------------> LEGACY
  |
  | maneuver_count/list, maneuver distance or maneuver-slot update continues
  v
V38_COMPAT
  |
  | route_state=0 / source_supports_rg=0 / disconnect
  v
LEGACY for the next route
```

### LEGACY

Frames are passed directly to the original/current `RouteGuidance` implementation without normalization or maneuver stabilization. This is the same path as `main`.

A known non-Amap `source_name` is never switched into the Amap v38 path.

### PROBING

The exact ambiguous lifecycle signature is not enough by itself to permanently enable v38. During the probe only:

- `visible_in_app=0` is presented to the base class as `-1` (unknown), never rewritten to `1`;
- transient `maneuver_count=0` / empty list clears are held;
- the existing BAP/renderer session therefore is not destroyed before the next Amap update can arrive.

The probe is confirmed as the newer protocol only if real maneuver progress follows, such as:

- `maneuver_count > 0`;
- non-empty `maneuver_list`;
- positive `dist_maneuver_m`;
- a real `mX_*` maneuver-slot update.

ETA/time alone intentionally do **not** confirm v38 mode because they can coexist with a legitimate legacy inactive transition.

If no confirmation arrives within 5 seconds, the original `visible_in_app=0` semantic is restored and the normal `main` shutdown path runs.

### V38_COMPAT

Once confirmed, the mode is latched only for the current route session. It does not repeatedly switch between main and v38 on every frame.

The compatibility layer adapts the important v38 Amap behavior to the current repository backend:

1. **Lifecycle tolerance**
   - `visible_in_app=0` is not a hard stop while route/maneuver evidence remains active.
   - No `visibleInApp=1` state writeback is used, avoiding the previous 0 -> 1 -> 0 dirty-state feedback loop.

2. **Formal maneuver lock**
   - a committed formal turn is not immediately replaced by a late prompt/neutral straight node for the same physical maneuver.

3. **Display stabilization**
   - a changed visual/physical maneuver must be stable before it replaces the currently committed frame;
   - the current adaptation uses the v38 two-frame stabilization intent.

4. **Physical rollover**
   - the current native hook's `slot + mVer` is used as physical maneuver identity;
   - this avoids importing the older v38 native binary only to obtain its generation/raw-head metadata.

5. **Progress stabilization**
   - a transient zero-distance completion frame does not immediately destroy the committed maneuver;
   - distance increases larger than the v38 8 m jitter threshold are suppressed for the same physical node.

The stabilized result is then sent through the **current main `RouteGuidance -> BAPBridge -> RendererServer` path**, preserving all current vehicle-tested rendering and handoff behavior.

## Why the native hook / renderer are not replaced

The current repository's `rgd_hook.c` already contains newer route-state-zero debounce, maneuver-slot versioning and cache-preservation logic. Replacing it with the older supplied v38 `libcarplay_hook.so` would risk regressing those fixes.

Likewise, the current `BAPBridge` and renderer contain later vehicle-tested fixes that should not be overwritten by the older v38 binaries.

Therefore this branch remains a **JAR-only compatibility change** when the vehicle already has the current `main` native hook and renderer installed.

## Expected log behavior

### Older/legacy Amap

No protocol transition should appear. The session remains in `LEGACY`, and frames follow the original `main` route-guidance path.

### Newer/iOS27-like Amap

The failing sequence should now produce:

```text
[AmapRouteGuidance] Protocol LEGACY -> PROBING: ...
[AmapRouteGuidance] Protocol PROBING -> V38_COMPAT: continuing maneuver data ...
[AmapV38Compat] V38 display compatibility enabled ...
```

There should be no immediate:

```text
[RouteGuidance] RG deactivate: route_state=1 ... visible_in_app=0
[RendererServer] Sent CMD_SHUTDOWN
```

while maneuver data is still advancing.

During navigation, optional diagnostic lines may include:

```text
[V38-FORMAL-LOCK]
[V38-STABILIZER]
[V38-PROGRESS]
[V38-ROLLOVER]
```

A true route end still follows the existing `main` teardown path.

## Java files changed/added by this branch

- `java_patch/com/luka/carplay/CarPlayHook.java`
- `java_patch/com/luka/carplay/routeguidance/AmapCompatibility.java`
- `java_patch/com/luka/carplay/routeguidance/AmapProtocolDetector.java`
- `java_patch/com/luka/carplay/routeguidance/AmapRouteGuidance.java`
- `java_patch/com/luka/carplay/routeguidance/AmapV38Compat.java`

No build-script change is required: `build_java.sh` already compiles every `.java` file below `java_patch` into `carplay_hook.jar`.
