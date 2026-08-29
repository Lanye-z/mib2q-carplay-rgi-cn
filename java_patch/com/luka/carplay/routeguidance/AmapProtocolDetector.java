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
 *   Amap emitted the newer ambiguous lifecycle signature
 *   route_state=1 + maneuver_count=0 + visible_in_app=0 while a route had
 *   already become active. The caller temporarily protects the current RG
 *   session while waiting for real maneuver progress.
 *
 * V38_COMPAT
 *   The ambiguous zero was followed by positive maneuver/detail progress.
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

    void reset() {
        mode = MODE_LEGACY;
        sourceName = null;
        activeRouteSeen = false;
        probeStartedMs = 0L;
    }

    void resetRouteOnly() {
        mode = MODE_LEGACY;
        activeRouteSeen = false;
        probeStartedMs = 0L;
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
            && isAmap()
            && activeRouteSeen
            && routeState == 1
            && maneuverCount == 0
            && visibleInApp == 0
            && sourceSupportsRg != 0;
    }

    void beginProbe(long nowMs) {
        mode = MODE_PROBING;
        probeStartedMs = nowMs;
    }

    void confirmV38() {
        mode = MODE_V38_COMPAT;
        probeStartedMs = 0L;
    }

    void rejectProbe() {
        mode = MODE_LEGACY;
        probeStartedMs = 0L;
    }

    long probeStartedMs() {
        return probeStartedMs;
    }

    /**
     * Require real maneuver progress to confirm the new protocol. ETA/time
     * alone are intentionally not sufficient because they can coexist with a
     * legitimate legacy inactive transition.
     */
    boolean hasV38ConfirmationEvidence(CarplayBus.Data d) {
        if (d == null) return false;

        if (d.has("maneuver_count") && d.num("maneuver_count", 0) > 0) return true;
        if (d.has("maneuver_list")) {
            int[] list = d.intList("maneuver_list");
            if (list != null && list.length > 0) return true;
        }
        if (d.has("dist_maneuver_m") && d.num("dist_maneuver_m", -1) > 0) return true;

        for (int i = 0; i < 32; i++) {
            String p = "m" + i + "_";
            if (d.has(p + "type") || d.has(p + "ver") || d.has(p + "distance")
                    || d.has(p + "turn_angle") || d.has(p + "name")
                    || d.has(p + "after_road")) {
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
