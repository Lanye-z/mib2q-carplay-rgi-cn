# iOS27 / Amap v38 second-generation merge

Branch: `ios27-amap-v38-merge`

## Design goal

This branch uses the vehicle-tested repository `main` as the product baseline. It does **not** replace the main BAP, renderer or native route-guidance implementation with the supplied v38 binaries.

The design rule is:

```text
trusted main output/lifecycle logic
        +
v38-derived Amap input/state semantics only
```

The main implementation therefore remains authoritative for:

- first-real-maneuver renderer startup and PRELOAD / FRAME_READY timing;
- 350 m / 1000 m / >2 km China approach-window policy;
- Always-On / FOLLOW_STREET display behavior;
- signed U-turn direction mapping;
- renderer / BAP synchronization;
- route-stop cleanup and native-navigation handoff;
- existing main native route-state-zero debounce, slot cache and `mVer` behavior.

## Why this is different from the first merge

The first merge used `AmapV38Compat` mainly as a text-payload filter in front of `RouteGuidance`. During HOLD it removed selected delta fields and tried to make the remaining payload look like v38 output.

That design could leak a mixture of old and new state because CarPlay route-guidance traffic is incremental. For example, an old maneuver list could coexist with a new `mVer`, exit angle, road field or lane field if only part of a candidate frame was suppressed.

The second-generation engine no longer treats HOLD as "drop a few lines".

```text
raw Amap deltas
      |
      v
complete RawState cache
      |
      +--> FormalLock
      +--> DisplayStabilizer
      +--> RolloverStateMachine
      +--> ProgressTracker
      |
      v
complete committed display snapshot
      |
      v
main RouteGuidance -> main BAPBridge -> main RendererServer
```

During HOLD, a complete committed snapshot is serialized. Main therefore sees one self-consistent state instead of a partially filtered delta.

## Protocol routing

Compatibility is behavior-based rather than hard-coded to an iOS version.

```text
LEGACY
  |
  | active route later reports
  | route_state=1 + maneuver_count=0 + visible_in_app=0
  v
PROBING (up to 5 s)
  |\
  | \ no continuing maneuver evidence / visible returns to 1
  |  +------------------------------------------> LEGACY
  |
  | real maneuver/list/distance/slot evidence continues
  v
V38_COMPAT
  |
  | route_state=0 / source_supports_rg=0 / disconnect
  v
reset for next route
```

### LEGACY

Frames go directly to the original main `RouteGuidance`. No Amap display-state filtering is applied. A known non-Amap source never enters the Amap compatibility path.

This is intentionally kept for older/legacy Amap behavior so lower-iOS sessions can retain the tested main path unless the newer ambiguous lifecycle is actually observed.

### PROBING

The ambiguous `1 / 0 / 0` transition is temporarily protected without permanently selecting v38 mode:

- `visible_in_app=0` is exposed to main as unknown (`-1`), not rewritten to active (`1`);
- transient zero maneuver count/list clears are held;
- real maneuver progress must follow before V38 mode is confirmed;
- ETA/time by themselves do not confirm the newer behavior.

If no confirmation arrives within five seconds, normal main inactive semantics are restored.

### V38_COMPAT

Once confirmed for the route, the v38-derived state engine owns Amap display acceptance while the main backend remains unchanged.

## v38 semantics implemented

### Complete raw cache

The engine independently caches top-level route fields, full maneuver-slot fields, exit angle, junction angles, road text, lane data and lane-guidance cache data. `mX_exit_angle` is kept separate from `mX_turn_angle`.

### Visual identity

The visual key includes:

```text
BAP main element
+ BAP direction
+ z-level
+ SideStreets bytes
```

`FOLLOW_STREET`, `NO_INFO` and `NO_SYMBOL` collapse to the same FOLLOW_STREET visual identity, matching the v38 intent.

### Formal lock

A committed formal turn is protected from a late prompt or neutral-straight node for the same physical maneuver.

### Display stabilizer

The v38 decision model is retained:

```text
PASS
HOLD
ACCEPT_SAME_VISUAL
COMMIT_NEW_VISUAL
COMMIT_NEW_PHYSICAL
```

A changed visual candidate must remain stable before replacing the committed snapshot. Distance jumps above the 8 m jitter allowance reset/hold the candidate.

A same-visual physical identity change can be accepted internally without changing the visible `mVer`, preventing main from unnecessarily reanimating the same arrow.

### Same-visual rollover

A completed maneuver can advance to a later physical maneuver with the same visual identity without requiring an artificial different arrow between them.

### Progress pairing

Distance is tracked against the accepted physical identity and is normally monotonic decreasing. A transient distance increase is suppressed instead of pairing a new distance with the old visual maneuver.

### Soft inactive lifecycle

After V38 mode is confirmed, `route_state=1 + maneuver_count=0 + visible_in_app=0` becomes a soft-inactive state rather than an immediate teardown.

The committed snapshot is retained during a five-second grace period. Fresh guidance evidence can extend the grace. An independent timer expires the hold even if no further bus frame arrives, after which the real inactive state is forwarded to main.

Hard clear remains immediate for `route_state=0`, `source_supports_rg=0` or disconnect.

## Main files deliberately not changed

Relative to `main`, this branch does not modify:

```text
RouteGuidance.java
BAPBridge.java
ManeuverMapper.java
RendererMapper.java
RendererServer.java
SideStreets.java
c_hook/*
c_render/*
```

`CarPlayHook` only selects `AmapRouteGuidance` as the RouteGuidance wrapper. Output behavior still comes from the existing main classes.

## Native metadata choice

The supplied v38 native binary exposes additional Amap metadata such as route generation/raw head/alignment/fresh-distance state. This branch deliberately does **not** replace main `libcarplay_hook.so` in the first second-generation implementation.

Physical identity is therefore derived from the main hook's stable slot + `mVer` information plus a route-session generation maintained by the Java Amap engine. Head alignment and distance freshness are conservatively derived from the complete Java raw cache.

This keeps the native layer identical to the tested main build. If road testing later identifies an edge case that specifically requires the v38 raw-head metadata, those fields can be added to main native as additive bus metadata without replacing its existing debounce/cache logic.

## Build

This branch returns to the normal main source build. No v38 ZIP is required.

```bash
./build_java.sh
```

The result is the normal main-style `carplay_hook.jar`. If the vehicle already runs main native/renderer files, this second-generation merge is intended to be a JAR-only update.

## Validation performed before commit

The new Amap classes were compiled against the existing main `carplay_hook.jar` as the actual classpath. This verifies the real method/API contracts used by the merge, including `CarplayBus.Data`, `ManeuverMapper` and `SideStreets`.

A full MHI2Q Java-1.2 release build still needs the repository's normal Java 8 / `lsd.jar` build environment.

## First vehicle-test checklist

### Main regression

- Apple Maps first arrow still follows the main first-real-maneuver startup path;
- no new black/FOLLOW_STREET placeholder is exposed before the first real arrow;
- 350 m / 1000 m / >2 km behavior is unchanged;
- signed U-turn remains unchanged;
- CarPlay stop/disconnect handoff remains unchanged.

### Lower-iOS / legacy Amap

- no `LEGACY -> PROBING` transition during normal operation unless the `1/0/0` signature actually appears;
- normal turn/rollover behavior remains the main path;
- route end remains immediate when the old protocol sends `route_state=0`.

### iOS27-like Amap

Expected transition:

```text
[AmapRouteGuidance2] LEGACY -> PROBING ...
[AmapRouteGuidance2] PROBING -> V38_COMPAT ...
[AmapV38Compat2] v38 Amap engine enabled ...
```

During navigation, diagnostics can include:

```text
[V38-FORMAL-LOCK]
[V38-STABILIZER]
[V38-PROGRESS]
[V38-ROLLOVER]
```

The former immediate `RG deactivate` on the transient `1/0/0` frame should not occur while valid maneuver guidance resumes within the grace period.
