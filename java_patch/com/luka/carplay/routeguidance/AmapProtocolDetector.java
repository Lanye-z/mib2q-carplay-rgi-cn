package com.luka.carplay.routeguidance;

import com.luka.carplay.framework.CarplayBus;

/**
 * Session-scoped Amap protocol detector.
 *
 * The important distinction is behavioral, not an iOS version string:
 *
 * LEGACY
 *   Existing/main behavior. Frames are forwarded unchanged.
 *
 * PROBING
 *   The newer ambiguous lifecycle signature appeared:
 *   route_state=1 + maneuver_count=0 + visible_in_app=0 while a route had
 *   already become active. The caller temporarily protects the current RG
 *   session while waiting for real maneuver progress.
 *
 * V38_COMPAT
 *   The ambiguous zero was followed by actual route/maneuver progress.
 *   The session is therefore treated with the v38-derived compatibility
 *   state machine until a hard route end or source change.
 */
final class AmapProtocolDetector {
    static final int MODE_LEGACY = 0;
    static final int MODE_PROBING = 1;
    static final int MODE_V38_COMPAT = 2;

    private int mode = MODE_LEGACY;
    private String sourceName = null;
    private boolean activeRouteSeen = false;
    private long probeStartedMs = 0L;

    /* Baseline captured from the ambiguous 1/0/0 snapshot.  Current main
     * native publishes full-state snapshots, so mere key presence on the next
     * bus frame is NOT proof of continuing guidance.  Confirmation must show
     * a real state change relative to this baseline. */
    private int probeRouteSeq = -1;
    private int probeRouteGeneration = -1;
    private int probeHeadIapIndex = -1;
    private int probeDistanceM = -1;

    void reset() {
        mode = MODE_LEGACY;
        sourceName = null;
        activeRouteSeen = false;
        clearProbe();
    }

    void resetRouteOnly() {
        mode = MODE_LEGACY;
        activeRouteSeen = false;
        clearProbe();
    }

    private void clearProbe() {
        probeStartedMs = 0L;
        probeRouteSeq = -1;
        probeRouteGeneration = -1;
        probeHeadIapIndex = -1;
        probeDistanceM = -1;
    }

    int mode() {
        return mode;
    }

    String modeName() {
        if (mode == MODE_PROBING) return "PROBING";
        if (mode == MODE_V38_COMPAT) return "V38_COMPAT";
        return "LEGACY";
    }

    String sourceName() {
        return sourceName;
    }

    void observeSource(CarplayBus.Data d) {
        if (d == null || !d.has("source_name")) return;
        String value = d.str("source_name", null);
        if (value != null && value.trim().length() > 0) {
            sourceName = value.trim();
        }
    }

    boolean isAmap() {
        return isAmapSourceName(sourceName);
    }

    private boolean sourceAllowsBehaviorProbe() {
        /* Some current-hook snapshots do not expose source_name to Java even
         * though the 0x5200 option was requested. In that case keep the strict
         * behavior fallback. A known non-Amap source is never probed. */
        return sourceName == null || sourceName.length() == 0 || isAmap();
    }

    static boolean isAmapSourceName(String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.length() == 0) return false;
        String lower = text.toLowerCase();
        return text.indexOf("高德") >= 0
            || lower.indexOf("amap") >= 0
            || lower.indexOf("gaode") >= 0;
    }

    void observeActiveRoute(int routeState, int maneuverCount, int visibleInApp,
                            int sourceSupportsRg) {
        if (routeState > 1 || visibleInApp == 1 || maneuverCount > 0) {
            activeRouteSeen = true;
            return;
        }
        if (routeState == 1 && visibleInApp < 0 && sourceSupportsRg != 0) {
            activeRouteSeen = true;
        }
    }

    boolean shouldBeginProbe(int routeState, int maneuverCount, int visibleInApp,
                             int sourceSupportsRg) {
        return mode == MODE_LEGACY
            && sourceAllowsBehaviorProbe()
            && activeRouteSeen
            && routeState == 1
            && maneuverCount == 0
            && visibleInApp == 0
            && sourceSupportsRg != 0;
    }

    void beginProbe(long nowMs, CarplayBus.Data d) {
        mode = MODE_PROBING;
        probeStartedMs = nowMs;
        if (d != null) {
            probeRouteSeq = d.num("amap_route_update_seq", -1);
            probeRouteGeneration = d.num("amap_route_generation", -1);
            probeHeadIapIndex = d.num("amap_head_iap_index", -1);
            probeDistanceM = d.num("dist_maneuver_m", -1);
        }
    }

    void confirmV38() {
        mode = MODE_V38_COMPAT;
        clearProbe();
    }

    void rejectProbe() {
        mode = MODE_LEGACY;
        clearProbe();
    }

    long probeStartedMs() {
        return probeStartedMs;
    }

    /**
     * Confirm only real progress relative to the protected 1/0/0 baseline.
     *
     * main's native hook emits full-state snapshots, so cached mX_* fields or
     * an unchanged positive distance can appear on every bus frame. They must
     * not by themselves switch a legacy/older session into V38_COMPAT.
     */
    boolean hasV38ConfirmationEvidence(CarplayBus.Data d) {
        if (d == null) return false;

        if (d.has("route_state") && d.num("route_state", -1) > 1) return true;
        if (d.has("maneuver_count") && d.num("maneuver_count", 0) > 0) return true;
        if (d.has("maneuver_list")) {
            int[] list = d.intList("maneuver_list");
            if (list != null && list.length > 0) return true;
        }

        int gen = d.num("amap_route_generation", -1);
        if (gen >= 0 && probeRouteGeneration >= 0 && gen != probeRouteGeneration)
            return true;

        int head = d.num("amap_head_iap_index", -1);
        if (head >= 0 && probeHeadIapIndex >= 0 && head != probeHeadIapIndex)
            return true;
        if (head >= 0 && probeHeadIapIndex < 0)
            return true;

        int dist = d.num("dist_maneuver_m", -1);
        if (dist > 0) {
            if (probeDistanceM <= 0) return true;
            if (dist != probeDistanceM) {
                int seq = d.num("amap_route_update_seq", -1);
                /* With native metadata, require a later 0x5201. Without it,
                 * a changed positive distance is still useful fallback proof. */
                if (seq < 0 || probeRouteSeq < 0 || seq != probeRouteSeq)
                    return true;
            }
        }
        return false;
    }

    boolean isHardClear(int routeState, int sourceSupportsRg, CarplayBus.Data d) {
        return routeState == 0
            || sourceSupportsRg == 0
            || (d != null && d.has("disconnect_reason")
                && d.str("disconnect_reason", null) != null);
    }

    boolean sourceChangedAwayFromAmap(CarplayBus.Data d) {
        if (d == null || !d.has("source_name")) return false;
        String value = d.str("source_name", null);
        if (value == null || value.trim().length() == 0) return false;
        return !isAmapSourceName(value);
    }
}
