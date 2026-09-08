#!/usr/bin/env python3
"""Apply the two upstream Luka third-party navigation compatibility fixes.

This branch intentionally contains no Amap-specific compatibility behavior.
The overlay changes only two generic Route Guidance behaviors at build time:

1. RouteGuidanceBeingShownInApp (visible_in_app) is a UI visibility flag,
   not authoritative route liveness. Keep guidance active while independent
   route/maneuver evidence still exists.
2. If a navigation app omits CPManeuver.initialDistance, keep the cluster
   bargraph alive by falling back to the normal policy cap.

All replacements are anchor-checked: source drift fails the build instead of
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
    if "boolean routeLooksActive =" in text:
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
        "            if (state.sourceSupportsRg == 0) wantActive = false;\n"
    )
    text = replace_once(text, old, new, "route-guidance-active-semantics")

    text = text.replace(
        "                 * visibleInApp=0, or real route end).  Full shutdown. */",
        "                 * source_supports_rg=0 or real route end).  Full shutdown. */")

    write_text(path, text)
    print("applied third-party RouteGuidance overlay: %s" % path)


def patch_bap_bridge(path):
    text = read_text(path)
    if "Unknown step length -> run the bargraph over" in text:
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
    p.add_argument("--route-guidance", required=True, metavar="ROUTE_GUIDANCE_JAVA")
    p.add_argument("--bap-bridge", required=True, metavar="BAP_BRIDGE_JAVA")
    args = p.parse_args()

    try:
        patch_route_guidance(args.route_guidance)
        patch_bap_bridge(args.bap_bridge)
    except Exception as e:
        print("ERROR: %s" % e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
