# Amap v38 state engine on the vehicle-tested main backend

Branch: `ios27-amap-v38-merge`

## Goal

Keep the current repository's vehicle-tested `main` display/backend behavior intact, and add only the v38 Amap input semantics that are needed for newer/iOS27-like Amap route-guidance behavior.

The output side remains `main`: first-real-maneuver renderer startup, signed U-turn direction, China-specific 350 m / 1000 m / >2 km approach policy, Always-On/FOLLOW_STREET behavior, BAP/renderer synchronization and native-navigation handoff are not replaced by the older v38 display/backend implementation.

The v38-derived part is limited to Amap route-input trust and stabilization: soft-inactive tolerance, physical maneuver identity, formal-lock, display stabilization, rollover and progress pairing.

## Architecture

```text
CarPlay iAP2
    |
    v
main native route-guidance hook
    |  + v38 Amap metadata overlay
    |    - amap_route_generation
    |    - amap_route_update_seq
    |    - amap_head_iap_index
    |    - amap_head_aligned
    |    - amap_distance_fresh
    v
AmapRouteGuidance
    |
    +-- legacy / known non-Amap ----------------------+
    |                                                 |
    |                                                 v
    +-- iOS27-like Amap -> v38 Amap state engine -> main RouteGuidance
                                                      |
                                                      v
                                                 main BAPBridge
                                                      |
                                                      v
                                                main Renderer
```

## Why this is different from the first merge

The first merge implemented a compatibility shim around text deltas. HOLD was achieved by dropping selected `maneuver_list`, distance and `mX_*` keys. Because the bus is incremental, that could leak a new `mVer`, distance, exit-info or other field into an old display state, or permanently lose a field that iOS did not resend.

The second-generation engine owns a complete raw cache and a separate complete committed display snapshot. A HOLD returns the whole committed snapshot atomically. The main backend therefore cannot receive mixed old/new maneuver identity, visual, text, lane and distance fields.

The visual key also includes `SideStreets`, and `turn_angle` / `exit_angle` are cached separately.

## Amap lifecycle routing

`AmapRouteGuidance` remains behavior-based rather than hard-coded to an iOS version.

```text
LEGACY
  |
  | already-active route later reports
  | route_state=1, maneuver_count=0, visible_in_app=0
  v
PROBING (max 5 s)
  |\
  | \ no continuing maneuver evidence / visible recovers
  |  +---------------------------------------------> LEGACY
  |
  | real maneuver/list/distance/detail evidence continues
  v
V38_COMPAT
  |
  | route_state=0 / source_supports_rg=0 / disconnect
  v
reset for the next route
```

A known non-Amap source stays on the unmodified main path.

## v38 soft-inactive behavior

For Amap in V38 mode:

- `route_state=0` and `source_supports_rg=0` remain hard clears;
- `route_state=1`, `maneuver_count=0`, `visible_in_app=0` is soft inactive;
- the previous committed display is retained for a 5-second grace window;
- maneuver, distance, ETA/time and other genuine guidance evidence refresh the grace anchor;
- an independent timer expires the hold even when no new bus update arrives.

No synthetic `visible_in_app=1` writeback is used.

## v38 native Amap metadata

The supplied v38 binary exposes five metadata values on every normal route-guidance snapshot. They are now added as a build overlay on top of the current main native source rather than replacing the complete main `libcarplay_hook.so`.

### `amap_route_generation`

Starts non-zero and advances on authoritative Amap route/head identity changes and hard route resets. Java uses it together with the raw iAP head to distinguish physical maneuvers across reroute/rollover boundaries.

### `amap_route_update_seq`

Advances on each accepted 0x5201 route update. It is retained for v38-compatible diagnostics/replay identity; it is not artificially used as a replacement for the v38 two-frame display-stabilizer logic.

### `amap_head_iap_index`

The raw iAP2 index at the head of the most recent ManeuverList, before main remaps maneuver indexes to bounded Java slots.

### `amap_distance_fresh`

For a newly listed head, this is true only when the same 0x5201 also supplies `DistToManeuver`. A later distance-bearing 0x5201 can refresh it.

### `amap_head_aligned`

Recomputed whenever a bus snapshot is built. It is true only when:

1. a raw iAP head exists;
2. distance is fresh for that head; and
3. that raw head currently maps to a cached maneuver slot containing a valid maneuver type.

This prevents a new ManeuverList head from being paired with stale detail/distance data.

## Main native behavior deliberately preserved

The overlay does **not** replace or rewrite the current main implementations of:

- route_state=0 debounce;
- maneuver slot mapping and active-slot eviction protection;
- `mVer` assignment versions;
- maneuver/lane caches;
- current bus snapshot structure;
- existing source-support hard clear;
- renderer/BAP/handoff logic.

The native additions are appended observational metadata plus their v38 update bookkeeping.

## Java metadata authority and fallback

The generated Java build prefers the five native v38 metadata fields when they are present:

```text
physical identity  -> native generation + raw iAP head
head validity      -> native amap_head_aligned
distance pairing   -> native amap_distance_fresh
```

If the JAR is accidentally paired with an older/main `.so` that does not publish these keys, the second-generation Amap engine falls back to the existing `slot + mVer + Java cache/generation` behavior rather than failing completely.

For full v38-equivalent Amap identity/freshness behavior, deploy the newly built JAR and newly built `libcarplay_hook.so` together.

## Build overlays

The readable source trees remain based on the tested main implementation. `build_java.sh` and `compile_hook.sh` make disposable copies under `build/` and apply:

```text
tools/apply_v38_amap_overlay.py
```

The patcher uses exact source anchors and aborts if the main source has drifted, preventing a silent half-applied build.

### Java

```bash
./build_java.sh
```

The builder patches only the temporary copy of `AmapV38Compat.java`, then compiles with the normal Java 8 / target-1.2 toolchain.

### Native hook

```bash
./compile_hook.sh
```

The builder patches only the temporary copy of `c_hook/routeguidance/rgd_hook.c`, then uses the existing QNX 6.5 ARM toolchain flow.

The renderer is unchanged and does not need to be rebuilt for this metadata change.

## Expected native bus fields

With the new `.so`, route-guidance snapshots should contain lines similar to:

```text
amap_route_generation:n:...
amap_route_update_seq:n:...
amap_head_iap_index:n:...
amap_head_aligned:n:0|1
amap_distance_fresh:n:0|1
```

## Expected Amap logs

For the previously failing iOS27-like sequence:

```text
[AmapRouteGuidance] Protocol LEGACY -> PROBING: ...
[AmapRouteGuidance] Protocol PROBING -> V38_COMPAT: continuing maneuver data ...
[AmapV38Compat2] v38 Amap engine enabled: gen=... rawHead=... seq=... nativeMeta=true ...
```

During navigation diagnostics may include:

```text
[V38-FORMAL-LOCK]
[V38-STABILIZER]
[V38-PROGRESS]
[V38-ROLLOVER]
```

A true route end still follows the current main teardown/handoff path.

## Deployment note

Because this revision adds native metadata, it is no longer a JAR-only change when upgrading from plain main. For the intended full behavior, update both:

```text
carplay_hook.jar
libcarplay_hook.so
```

`maneuver_render` and `flag_atlas.rgba` remain the current main versions and are unchanged by this branch.