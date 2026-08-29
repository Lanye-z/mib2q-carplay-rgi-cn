#!/usr/bin/env python3
"""Apply the proven v38 Amap metadata overlay to main-derived sources.

The repository keeps the vehicle-tested main native/Java source as the base.
Build scripts copy that source into build/ and run this patcher there, so the
resulting binaries add only the v38 Amap metadata contract without replacing
main route debounce, slot cache, mVer, BAPBridge, renderer or handoff logic.

The patch is deliberately anchor-checked. If upstream/main source drifts, the
build fails instead of silently producing a half-patched binary.
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


def patch_native(path):
    text = read_text(path)
    if "amap_route_generation" in text:
        print("native overlay already present: %s" % path)
        return

    text = replace_once(
        text,
        "\t    /* Last merged 0x5201 snapshot (used to make bus writes full-state). */\n"
        "\t    rgd_update_t update_cache;\n"
        "\t\t} g_rgd = {",
        "\t    /* Last merged 0x5201 snapshot (used to make bus writes full-state). */\n"
        "\t    rgd_update_t update_cache;\n"
        "\n"
        "\t    /* v38 Amap metadata. Appended to main state on purpose: no\n"
        "\t     * existing main field/layout or control flow is replaced. */\n"
        "\t    uint32_t amap_route_generation;\n"
        "\t    uint32_t amap_route_update_seq;\n"
        "\t    uint16_t amap_head_iap_index;\n"
        "\t    bool amap_head_aligned;\n"
        "\t    bool amap_distance_fresh;\n"
        "\t\t} g_rgd = {",
        "native-state-fields")

    text = replace_once(
        text,
        "\t    .seq_counter = 0,\n"
        "\t    .ver_counter = 0,\n"
        "\t    .highest_list_index = 0\n"
        "\t};",
        "\t    .seq_counter = 0,\n"
        "\t    .ver_counter = 0,\n"
        "\t    .highest_list_index = 0,\n"
        "\t    .amap_route_generation = 1,\n"
        "\t    .amap_route_update_seq = 0,\n"
        "\t    .amap_head_iap_index = 0xFFFF,\n"
        "\t    .amap_head_aligned = false,\n"
        "\t    .amap_distance_fresh = false\n"
        "\t};",
        "native-state-init")

    helper = r'''
/* ============================================================
 * v38 Amap physical-head metadata
 * ============================================================
 * Observational only: this does not alter main's route-zero debounce, slot
 * allocation/cache eviction, mVer, BAP or renderer control flow.
 */
static uint32_t amap_meta_next_nonzero(uint32_t value) {
    value++;
    return value == 0 ? 1 : value;
}

static void amap_meta_bump_generation(void) {
    g_rgd.amap_route_generation = amap_meta_next_nonzero(g_rgd.amap_route_generation);
}

static void amap_meta_reset_head(bool bump_generation) {
    if (bump_generation)
        amap_meta_bump_generation();
    g_rgd.amap_head_iap_index = 0xFFFF;
    g_rgd.amap_head_aligned = false;
    g_rgd.amap_distance_fresh = false;
}

static void amap_meta_refresh_alignment(void) {
    g_rgd.amap_head_aligned = false;
    if (g_rgd.amap_head_iap_index == 0xFFFF || !g_rgd.amap_distance_fresh)
        return;

    int slot = rgd_find_slot_for_iap_index_no_touch(g_rgd.amap_head_iap_index);
    if (slot < 0 || slot >= MANEUVER_CACHE_SIZE)
        return;
    if (g_rgd.slot_cache[slot].present & RGD_MAN_TYPE)
        g_rgd.amap_head_aligned = true;
}

/* One accepted 0x5201 = one update sequence step. A hard clear is authoritative
 * even if source_supports_rg=0 arrives without route_state=0 in that frame. */
static void amap_meta_note_route_update(const rgd_update_t* upd, bool hard_clear) {
    g_rgd.amap_route_update_seq = amap_meta_next_nonzero(g_rgd.amap_route_update_seq);

    if (hard_clear) {
        amap_meta_reset_head(true);
        return;
    }
    if (!upd) {
        amap_meta_refresh_alignment();
        return;
    }

    if (upd->present & RGD_UPD_MANEUVER_LIST) {
        uint16_t new_head = upd->maneuver_list_count > 0
                          ? upd->maneuver_list[0] : (uint16_t)0xFFFF;
        if (new_head != g_rgd.amap_head_iap_index) {
            amap_meta_bump_generation();
            g_rgd.amap_head_iap_index = new_head;
        }
        /* v38 pairs a freshly selected head only with distance carried by
         * the same 0x5201. A later distance-only update can refresh it. */
        g_rgd.amap_distance_fresh =
            (upd->present & RGD_UPD_DIST_TO_MANEUVER) != 0;
    } else if (upd->present & RGD_UPD_DIST_TO_MANEUVER) {
        g_rgd.amap_distance_fresh = true;
    }

    amap_meta_refresh_alignment();
}

'''
    text = replace_once(
        text,
        "static bool rgd_is_active_lane_index(uint16_t lane_idx) {",
        helper + "static bool rgd_is_active_lane_index(uint16_t lane_idx) {",
        "native-meta-helpers")

    text = replace_once(
        text,
        "    if (bus_text_begin_heap(b, \"routeguidance\", BUS_TEXT_BUILDER_LARGE_CAP) != HOOK_OK) {\n"
        "        LOG_WARN(LOG_MODULE, \"snapshot: text builder alloc failed\");\n"
        "        return;\n"
        "    }\n"
        "\n"
        "    if (present & RGD_UPD_ROUTE_STATE)",
        "    if (bus_text_begin_heap(b, \"routeguidance\", BUS_TEXT_BUILDER_LARGE_CAP) != HOOK_OK) {\n"
        "        LOG_WARN(LOG_MODULE, \"snapshot: text builder alloc failed\");\n"
        "        return;\n"
        "    }\n"
        "\n"
        "    /* v38 publishes these on every normal snapshot, including\n"
        "     * 0x5202-only snapshots. Re-evaluate after slot-cache updates. */\n"
        "    amap_meta_refresh_alignment();\n"
        "    bus_text_uint(b, \"amap_route_generation\", g_rgd.amap_route_generation);\n"
        "    bus_text_uint(b, \"amap_route_update_seq\", g_rgd.amap_route_update_seq);\n"
        "    bus_text_int(b, \"amap_head_iap_index\",\n"
        "                 g_rgd.amap_head_iap_index == 0xFFFF\n"
        "                    ? -1 : (int)g_rgd.amap_head_iap_index);\n"
        "    bus_text_int(b, \"amap_head_aligned\", g_rgd.amap_head_aligned ? 1 : 0);\n"
        "    bus_text_int(b, \"amap_distance_fresh\", g_rgd.amap_distance_fresh ? 1 : 0);\n"
        "\n"
        "    if (present & RGD_UPD_ROUTE_STATE)",
        "native-snapshot-publish")

    text = replace_once(
        text,
        "    rgd_maneuver_map_reset();\n"
        "    rgd_update_cache_reset();\n"
        "\n"
        "    {\n",
        "    rgd_maneuver_map_reset();\n"
        "    rgd_update_cache_reset();\n"
        "    amap_meta_reset_head(true);\n"
        "\n"
        "    {\n",
        "native-clear-reset")

    text = replace_once(
        text,
        "        if (!suppress_update)\n"
        "            write_bus_update_partial(&upd);",
        "        if (!suppress_update) {\n"
        "            amap_meta_note_route_update(&upd, hard_clear);\n"
        "            write_bus_update_partial(&upd);\n"
        "        }",
        "native-route-update-meta")

    write_text(path, text)
    print("applied v38 Amap native metadata overlay: %s" % path)


def patch_java(path):
    text = read_text(path)
    if "hasNativeAmapMetadata" in text:
        print("java native-metadata overlay already present: %s" % path)
        return

    text = replace_once(
        text,
        "        boolean activeNow = raw.routeState > 0 || raw.maneuverCount > 0\n"
        "            || (raw.maneuverList != null && raw.maneuverList.length > 0);\n"
        "        if (activeNow && !rawRoutePreviouslyActive) routeGeneration++;\n"
        "        rawRoutePreviouslyActive = activeNow;",
        "        boolean activeNow = raw.routeState > 0 || raw.maneuverCount > 0\n"
        "            || (raw.maneuverList != null && raw.maneuverList.length > 0);\n"
        "        if (raw.hasNativeAmapMetadata && raw.amapRouteGeneration > 0) {\n"
        "            routeGeneration = raw.amapRouteGeneration;\n"
        "        } else if (activeNow && !rawRoutePreviouslyActive) {\n"
        "            routeGeneration++;\n"
        "        }\n"
        "        rawRoutePreviouslyActive = activeNow;",
        "java-native-generation")

    text = replace_once(
        text,
        "        int head = raw.head();\n"
        "        int ver = raw.value(raw.mVer, head, -1);",
        "        int head = raw.head();\n"
        "        int physicalRawHead = raw.physicalHead();\n"
        "        int ver = raw.value(raw.mVer, head, -1);",
        "java-physical-raw-head")

    text = replace_once(
        text,
        "        boolean samePhysical = samePhysical(head, ver, committed.physicalHead,\n"
        "            committed.physicalVer, routeGeneration, committed.physicalGeneration);",
        "        boolean samePhysical = samePhysical(head, ver, committed.physicalHead,\n"
        "            committed.physicalVer, routeGeneration, committed.physicalGeneration,\n"
        "            physicalRawHead, committed.physicalRawHead);",
        "java-same-physical-call")

    pairs = [
        ("formalLock.isLatePrompt(head, ver, routeGeneration, head, type, road)",
         "formalLock.isLatePrompt(head, ver, routeGeneration, physicalRawHead, type, road)",
         "java-formal-lock-raw-head"),
        ("routeGeneration, head);\n            if (rr == AmapRolloverStateMachine.COMMIT_SAME_VISUAL)",
         "routeGeneration, physicalRawHead);\n            if (rr == AmapRolloverStateMachine.COMMIT_SAME_VISUAL)",
         "java-rollover-raw-head"),
        ("aligned, distanceFresh, routeGeneration, head);",
         "aligned, distanceFresh, routeGeneration, physicalRawHead);",
         "java-stabilizer-raw-head"),
        ("formalLock.updatePhysicalIdentity(head, ver, routeGeneration, head);",
         "formalLock.updatePhysicalIdentity(head, ver, routeGeneration, physicalRawHead);",
         "java-formal-identity-raw-head"),
        ("            committed.physicalGeneration = routeGeneration;\n"
         "            updateCommittedProgress(dist, nodeDistance, head, ver);",
         "            committed.physicalGeneration = routeGeneration;\n"
         "            committed.physicalRawHead = physicalRawHead;\n"
         "            updateCommittedProgress(dist, nodeDistance, head, ver, physicalRawHead);",
         "java-accept-same-identity"),
        ("            updateCommittedProgress(dist, nodeDistance, head, ver);\n"
         "            Snapshot hold = committed.copy();",
         "            updateCommittedProgress(dist, nodeDistance, head, ver, physicalRawHead);\n"
         "            Snapshot hold = committed.copy();",
         "java-formal-progress-raw-head"),
        ("                committed.distManeuverM, routeGeneration, committed.physicalHead);",
         "                committed.distManeuverM, routeGeneration, committed.physicalRawHead);",
         "java-rollover-begin-raw-head"),
        ("        updateCommittedProgress(dist, nodeDistance,\n"
         "            committed.physicalHead, committed.physicalVer);",
         "        updateCommittedProgress(dist, nodeDistance,\n"
         "            committed.physicalHead, committed.physicalVer,\n"
         "            committed.physicalRawHead);",
         "java-hold-progress-raw-head"),
    ]
    for old, new, label in pairs:
        text = replace_once(text, old, new, label)

    text = replace_once(
        text,
        "        next.physicalGeneration = routeGeneration;\n"
        "        committed = next;\n"
        "\n"
        "        int den = nodeDistance > 0 ? nodeDistance : dist;\n"
        "        progress.commit(dist, den, head, ver, routeGeneration, head, false);\n"
        "        applyTrackedDistance(committed);\n"
        "        stabilizer.seed(next.visualKey, head, ver, dist, routeGeneration, head);\n"
        "        formalLock.commit(next.visualKey, road, head, ver, routeGeneration, head);",
        "        next.physicalGeneration = routeGeneration;\n"
        "        next.physicalRawHead = raw.physicalHead();\n"
        "        committed = next;\n"
        "\n"
        "        int den = nodeDistance > 0 ? nodeDistance : dist;\n"
        "        progress.commit(dist, den, head, ver, routeGeneration, next.physicalRawHead, false);\n"
        "        applyTrackedDistance(committed);\n"
        "        stabilizer.seed(next.visualKey, head, ver, dist, routeGeneration, next.physicalRawHead);\n"
        "        formalLock.commit(next.visualKey, road, head, ver, routeGeneration, next.physicalRawHead);",
        "java-commit-native-identity")

    text = replace_once(
        text,
        "    private void updateCommittedProgress(int dist, int nodeDistance, int head, int ver) {\n"
        "        if (dist <= 0) return;\n"
        "        int den = nodeDistance > 0 ? nodeDistance : progress.denominator();\n"
        "        AmapProgressTracker.Result r = progress.update(dist, nodeDistance, den,\n"
        "            head, ver, routeGeneration, head, false);",
        "    private void updateCommittedProgress(int dist, int nodeDistance, int head, int ver,\n"
        "                                         int physicalRawHead) {\n"
        "        if (dist <= 0) return;\n"
        "        int den = nodeDistance > 0 ? nodeDistance : progress.denominator();\n"
        "        AmapProgressTracker.Result r = progress.update(dist, nodeDistance, den,\n"
        "            head, ver, routeGeneration, physicalRawHead, false);",
        "java-progress-method")

    text = replace_once(
        text,
        "    private static boolean samePhysical(int h1, int v1, int h2, int v2,\n"
        "                                        int gen1, int gen2) {\n"
        "        if (gen1 >= 0 && gen2 >= 0 && gen1 != gen2) return false;\n"
        "        return h1 >= 0 && h1 == h2 && v1 >= 0 && v1 == v2;\n"
        "    }",
        "    private static boolean samePhysical(int h1, int v1, int h2, int v2,\n"
        "                                        int gen1, int gen2,\n"
        "                                        int rawHead1, int rawHead2) {\n"
        "        if (gen1 >= 0 && gen2 >= 0 && rawHead1 >= 0 && rawHead2 >= 0)\n"
        "            return gen1 == gen2 && rawHead1 == rawHead2;\n"
        "        if (gen1 >= 0 && gen2 >= 0 && gen1 != gen2) return false;\n"
        "        return h1 >= 0 && h1 == h2 && v1 >= 0 && v1 == v2;\n"
        "    }",
        "java-same-physical-method")

    text = replace_once(
        text,
        "        int laneGuidanceShowing, laneGuidanceTotal, laneGuidanceIndex, laneGuidanceSlot;\n"
        "        boolean headAligned, distanceFresh;",
        "        int laneGuidanceShowing, laneGuidanceTotal, laneGuidanceIndex, laneGuidanceSlot;\n"
        "        boolean headAligned, distanceFresh;\n"
        "\n"
        "        /* Exact v38 native identity/freshness contract. When these keys\n"
        "         * are absent (old/main .so), the existing Java fallback remains. */\n"
        "        boolean hasNativeAmapMetadata;\n"
        "        int amapRouteGeneration, amapRouteUpdateSeq, amapHeadIapIndex;\n"
        "        int amapHeadAligned, amapDistanceFresh;",
        "java-raw-native-fields")

    text = replace_once(
        text,
        "            laneGuidanceShowing = laneGuidanceTotal = laneGuidanceIndex = laneGuidanceSlot = -1;\n"
        "            headAligned = false; distanceFresh = false;",
        "            laneGuidanceShowing = laneGuidanceTotal = laneGuidanceIndex = laneGuidanceSlot = -1;\n"
        "            headAligned = false; distanceFresh = false;\n"
        "            hasNativeAmapMetadata = false;\n"
        "            amapRouteGeneration = amapRouteUpdateSeq = amapHeadIapIndex = -1;\n"
        "            amapHeadAligned = amapDistanceFresh = -1;",
        "java-raw-native-reset")

    text = replace_once(
        text,
        "        void observe(CarplayBus.Data d) {\n"
        "            distanceFresh = false;\n"
        "            if (d.has(\"route_state\")) routeState = d.num(\"route_state\", -1);",
        "        void observe(CarplayBus.Data d) {\n"
        "            distanceFresh = false;\n"
        "            boolean nativeMetaThisFrame = false;\n"
        "            if (d.has(\"amap_route_generation\")) {\n"
        "                amapRouteGeneration = d.num(\"amap_route_generation\", -1);\n"
        "                nativeMetaThisFrame = true;\n"
        "            }\n"
        "            if (d.has(\"amap_route_update_seq\")) {\n"
        "                amapRouteUpdateSeq = d.num(\"amap_route_update_seq\", -1);\n"
        "                nativeMetaThisFrame = true;\n"
        "            }\n"
        "            if (d.has(\"amap_head_iap_index\")) {\n"
        "                amapHeadIapIndex = d.num(\"amap_head_iap_index\", -1);\n"
        "                nativeMetaThisFrame = true;\n"
        "            }\n"
        "            if (d.has(\"amap_head_aligned\")) {\n"
        "                amapHeadAligned = d.num(\"amap_head_aligned\", -1);\n"
        "                nativeMetaThisFrame = true;\n"
        "            }\n"
        "            if (d.has(\"amap_distance_fresh\")) {\n"
        "                amapDistanceFresh = d.num(\"amap_distance_fresh\", -1);\n"
        "                nativeMetaThisFrame = true;\n"
        "            }\n"
        "            if (nativeMetaThisFrame) hasNativeAmapMetadata = true;\n"
        "\n"
        "            if (d.has(\"route_state\")) routeState = d.num(\"route_state\", -1);",
        "java-raw-native-parse")

    text = replace_once(
        text,
        "            int h = head();\n"
        "            headAligned = h >= 0 && h < MAX_MANEUVERS && mVer[h] >= 0\n"
        "                && ManeuverMapper.isValidType(mType[h]);\n"
        "        }\n"
        "\n"
        "        int head() { return maneuverList == null || maneuverList.length == 0 ? -1 : maneuverList[0]; }",
        "            int h = head();\n"
        "            boolean fallbackAligned = h >= 0 && h < MAX_MANEUVERS && mVer[h] >= 0\n"
        "                && ManeuverMapper.isValidType(mType[h]);\n"
        "            if (hasNativeAmapMetadata) {\n"
        "                headAligned = amapHeadAligned == 1;\n"
        "                distanceFresh = amapDistanceFresh == 1;\n"
        "            } else {\n"
        "                headAligned = fallbackAligned;\n"
        "            }\n"
        "        }\n"
        "\n"
        "        int head() { return maneuverList == null || maneuverList.length == 0 ? -1 : maneuverList[0]; }\n"
        "        int physicalHead() {\n"
        "            return hasNativeAmapMetadata ? amapHeadIapIndex : head();\n"
        "        }",
        "java-raw-native-authority")

    text = replace_once(
        text,
        "        int physicalHead = -1, physicalVer = -1, physicalGeneration = -1;",
        "        int physicalHead = -1, physicalVer = -1, physicalGeneration = -1;\n"
        "        int physicalRawHead = -1;",
        "java-snapshot-raw-head-field")

    text = replace_once(
        text,
        "            s.physicalHead=physicalHead; s.physicalVer=physicalVer;\n"
        "            s.physicalGeneration=physicalGeneration;",
        "            s.physicalHead=physicalHead; s.physicalVer=physicalVer;\n"
        "            s.physicalGeneration=physicalGeneration;\n"
        "            s.physicalRawHead=physicalRawHead;",
        "java-snapshot-copy-raw-head")

    text = replace_once(
        text,
        "        Log.i(TAG, \"v38 Amap engine enabled: gen=\" + routeGeneration\n"
        "            + \" head=\" + raw.head() + \" visual=\" + visualKey(raw, raw.head()));",
        "        Log.i(TAG, \"v38 Amap engine enabled: gen=\" + routeGeneration\n"
        "            + \" head=\" + raw.head()\n"
        "            + \" rawHead=\" + raw.physicalHead()\n"
        "            + \" seq=\" + raw.amapRouteUpdateSeq\n"
        "            + \" nativeMeta=\" + raw.hasNativeAmapMetadata\n"
        "            + \" visual=\" + visualKey(raw, raw.head()));",
        "java-enable-log")

    write_text(path, text)
    print("applied v38 Amap Java native-metadata overlay: %s" % path)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--native", metavar="RGD_HOOK_C")
    p.add_argument("--java", metavar="AMAP_V38_COMPAT_JAVA")
    args = p.parse_args()
    if not args.native and not args.java:
        p.error("at least one of --native/--java is required")
    try:
        if args.native:
            patch_native(args.native)
        if args.java:
            patch_java(args.java)
    except Exception as e:
        print("ERROR: %s" % e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
