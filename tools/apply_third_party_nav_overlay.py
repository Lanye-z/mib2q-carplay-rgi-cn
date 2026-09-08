#!/usr/bin/env python3
"""Apply narrow third-party navigation compatibility fixes to a build copy.

This overlay intentionally leaves the vehicle-tested readable main/Amap-v38
sources untouched.  It applies two upstream Luka fixes at build time:

1. RouteGuidanceBeingShownInApp (visible_in_app) is a visibility flag, not a
   route-liveness flag.  A route remains active while route/maneuver evidence
   still exists, even when a third-party navigation UI is not foregrounded.
2. If a navigation app omits CPManeuver.initialDistance, keep the cluster
   bargraph alive by falling back to the normal policy cap.

The local Amap-v38 compatibility engine historically used an internally
synthesized route_state=1 / maneuver_count=0 / visible_in_app=0 frame to expire
its 5-second soft-inactive hold.  Upstream fix #1 would otherwise keep that
synthetic frame alive forever because route_state is still 1.  To preserve the
existing Amap behavior, the two Amap timeout paths add an internal-only
amap_force_inactive=1 marker, and RouteGuidance honors that marker as an
explicit shutdown request.

All replacements are anchor-checked: a source drift fails the build instead of
silently producing a partial patch.
"""

from __future__ import print_function
import argparse
import io
import sys


def read_text(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write_text(path, text):
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError("%s: expected one anchor, found %d" % (label, count))
    return text.replace(old, new, 1)


def patch_route_guidance(path):
    text = read_text(path)
    if "amap_force_inactive" in text and "routeLooksActive" in text:
        print("third-party RouteGuidance overlay already present: %s" % path)
        return

    text = replace_once(
        text,
        "         * - Prefer explicit active authority from visible_in_app (native-like TBT_Active semantics).\n"
        "         * - Fall back to route/maneuver heuristics only when visible_in_app is unknown.\n",
        "         * - visible_in_app describes whether guidance is currently shown in the app;\n"
        "         *   it is not authoritative route-liveness for third-party navigation.\n"
        "         * - Keep guidance active while route/maneuver evidence still exists.\n",
        "route-guidance-comment")

    old = (
        "            boolean hasActiveAuthority = (state.visibleInApp >= 0);\n"
        "            boolean wantActive;\n"
        "            if (hasActiveAuthority) {\n"
        "                wantActive = (state.visibleInApp != 0);\n"
        "            } else {\n"
        "                wantActive = (state.routeState >= ROUTE_STATE_ROUTE_SET)\n"
        "                    || (state.maneuverCount > 0)\n"
        "                    || (state.maneuverOrder != null && state.maneuverOrder.length > 0);\n"
        "                if (explicitClear && state.routeState < ROUTE_STATE_ROUTE_SET) {\n"
        "                    wantActive = false;\n"
        "                }\n"
        "            }\n"
        "            if (state.sourceSupportsRg == 0) wantActive = false;\n"
    )
    new = (
        "            boolean routeLooksActive = (state.routeState >= ROUTE_STATE_ROUTE_SET)\n"
        "                || (state.maneuverCount > 0)\n"
        "                || (state.maneuverOrder != null && state.maneuverOrder.length > 0);\n"
        "            boolean hasActiveAuthority = (state.visibleInApp >= 0);\n"
        "            boolean wantActive;\n"
        "            if (hasActiveAuthority) {\n"
        "                /* visible_in_app=0 only means the guidance UI is not currently\n"
        "                 * shown. Third-party apps may report 0 throughout a valid route. */\n"
        "                wantActive = (state.visibleInApp != 0) || routeLooksActive;\n"
        "            } else {\n"
        "                wantActive = routeLooksActive;\n"
        "                if (explicitClear && state.routeState < ROUTE_STATE_ROUTE_SET) {\n"
        "                    wantActive = false;\n"
        "                }\n"
        "            }\n"
        "            /* Local Amap-v38 timers use an internal synthetic expiry frame.\n"
        "             * Preserve that established 5-second soft-inactive shutdown even\n"
        "             * though generic visible_in_app=0 is no longer a stop signal. */\n"
        "            if (d.num(\"amap_force_inactive\", 0) != 0) wantActive = false;\n"
        "            if (state.sourceSupportsRg == 0) wantActive = false;\n"
    )
    text = replace_once(text, old, new, "route-guidance-active-semantics")

    text = text.replace(
        "                 * visibleInApp=0, or real route end).  Full shutdown. */",
        "                 * explicit Amap timeout, or real route end).  Full shutdown. */")

    write_text(path, text)
    print("applied third-party RouteGuidance overlay: %s" % path)


def patch_amap_v38(path):
    text = read_text(path)
    if "amap_force_inactive:n:1" in text:
        print("Amap-v38 timeout marker already present: %s" % path)
        return

    text = replace_once(
        text,
        "            b.append(\"maneuver_list:s:\\n\");\n"
        "            b.append(\"visible_in_app:n:0\\n\");\n"
        "            if (raw.sourceSupportsRg >= 0)\n",
        "            b.append(\"maneuver_list:s:\\n\");\n"
        "            b.append(\"visible_in_app:n:0\\n\");\n"
        "            b.append(\"amap_force_inactive:n:1\\n\");\n"
        "            if (raw.sourceSupportsRg >= 0)\n",
        "amap-v38-soft-expiry-marker")

    write_text(path, text)
    print("preserved Amap-v38 soft-expiry semantics: %s" % path)


def patch_amap_router(path):
    text = read_text(path)
    if "amap_force_inactive:n:1" in text:
        print("Amap probe timeout marker already present: %s" % path)
        return

    text = replace_once(
        text,
        "            b.append(\"maneuver_list:s:\\n\");\n"
        "            b.append(\"visible_in_app:n:0\\n\");\n"
        "            if (rawSourceSupportsRg >= 0)\n",
        "            b.append(\"maneuver_list:s:\\n\");\n"
        "            b.append(\"visible_in_app:n:0\\n\");\n"
        "            b.append(\"amap_force_inactive:n:1\\n\");\n"
        "            if (rawSourceSupportsRg >= 0)\n",
        "amap-probe-expiry-marker")

    write_text(path, text)
    print("preserved Amap probe-expiry semantics: %s" % path)


def patch_bap_bridge(path):
    text = read_text(path)
    if "Unknown step length -> run the bargraph over the policy cap" in text:
        print("third-party bargraph overlay already present: %s" % path)
        return

    old = (
        "        int denominator = s.mDistance[manIdx];\n"
        "        if (denominator <= 0) return -1;\n"
        "        int policyCap = (prepareThresholdM * BARGRAPH_ACTION_PERCENT_OF_PREPARE) / 100;\n"
        "        if (policyCap <= 0) return -1;\n"
        "        if (denominator > policyCap) {\n"
        "            return policyCap;\n"
        "        }\n"
        "        return denominator;\n"
    )
    new = (
        "        int policyCap = (prepareThresholdM * BARGRAPH_ACTION_PERCENT_OF_PREPARE) / 100;\n"
        "        if (policyCap <= 0) return -1;\n"
        "        int denominator = s.mDistance[manIdx];\n"
        "        /* Third-party navigation apps may omit CPManeuver.initialDistance,\n"
        "         * leaving mDistance at -1 even though live distance-to-maneuver\n"
        "         * updates continue. Unknown step length -> run the bargraph over\n"
        "         * the policy cap, the same window used when a known step is longer. */\n"
        "        if (denominator <= 0 || denominator > policyCap) {\n"
        "            return policyCap;\n"
        "        }\n"
        "        return denominator;\n"
    )
    text = replace_once(text, old, new, "bap-bargraph-fallback")

    write_text(path, text)
    print("applied third-party bargraph overlay: %s" % path)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--route-guidance", metavar="ROUTE_GUIDANCE_JAVA")
    p.add_argument("--amap-v38", metavar="AMAP_V38_COMPAT_JAVA")
    p.add_argument("--amap-router", metavar="AMAP_ROUTE_GUIDANCE_JAVA")
    p.add_argument("--bap-bridge", metavar="BAP_BRIDGE_JAVA")
    args = p.parse_args()

    if not (args.route_guidance or args.amap_v38 or args.amap_router or args.bap_bridge):
        p.error("at least one overlay target is required")

    try:
        if args.route_guidance:
            patch_route_guidance(args.route_guidance)
        if args.amap_v38:
            patch_amap_v38(args.amap_v38)
        if args.amap_router:
            patch_amap_router(args.amap_router)
        if args.bap_bridge:
            patch_bap_bridge(args.bap_bridge)
    except Exception as e:
        print("ERROR: %s" % e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
