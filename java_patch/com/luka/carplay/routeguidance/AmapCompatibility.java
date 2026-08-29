package com.luka.carplay.routeguidance;

/**
 * Compatibility helpers for Amap/CarPlay route-guidance lifecycle.
 *
 * Newer iOS/Amap combinations can report visible_in_app=0 while the route is
 * still alive and maneuver/distance updates continue.  Treat that state as a
 * soft inactive transition instead of rewriting the raw CarPlay state.
 */
final class AmapCompatibility {
    private AmapCompatibility() {}

    static boolean isHardClear(int routeState, int sourceSupportsRg) {
        return routeState == 0 || sourceSupportsRg == 0;
    }

    static boolean isSoftInactive(int routeState, int maneuverCount, int visibleInApp) {
        return routeState == 1 && maneuverCount == 0 && visibleInApp == 0;
    }

    static long graceRemaining(long nowMs, long softInactiveStartedMs,
                               long lastGuidanceEvidenceMs, long graceMs) {
        long anchor = softInactiveStartedMs;
        if (lastGuidanceEvidenceMs > anchor) anchor = lastGuidanceEvidenceMs;
        if (anchor <= 0) return 0;

        long elapsed = nowMs - anchor;
        if (elapsed < 0) elapsed = 0;
        long remaining = graceMs - elapsed;
        return remaining > 0 ? remaining : 0;
    }

    static boolean isPlaceholderDestination(String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.length() == 0) return true;
        return "????".equals(text)
            || "Unknown Location".equalsIgnoreCase(text)
            || "Unknown destination".equalsIgnoreCase(text);
    }
}
