package com.luka.carplay.routeguidance;

import com.luka.carplay.framework.CarplayBus;
import com.luka.carplay.framework.Log;

/**
 * v38-derived Amap lifecycle compatibility adapter.
 *
 * iOS 27 + Amap can publish visible_in_app=0 while route_state remains 1 and
 * real maneuver/distance/ETA data continues to arrive.  The stock/current
 * RouteGuidance implementation intentionally treats an explicit
 * visible_in_app=0 as authoritative, which tears down BAP + renderer before
 * Amap's maneuver list catches up.
 *
 * This adapter keeps the current RouteGuidance/BAPBridge implementation intact
 * and only normalizes the lifecycle edge case:
 *   - route_state=0 or source_supports_rg=0 remains a hard clear;
 *   - route=1,count=0,visible=0 becomes a 5 s soft-inactive window;
 *   - fresh maneuver/distance/ETA/lane evidence extends/cancels that window;
 *   - raw visible_in_app is preserved here, but a non-authoritative Amap zero
 *     is presented to RouteGuidance as -1 (unknown), never rewritten to 1;
 *   - transient count/list clears are held during the grace window to avoid a
 *     NO_SYMBOL flash before Amap publishes the real maneuver data;
 *   - after a genuine soft-inactive expiry, repeated zero-only frames cannot
 *     continuously re-activate the renderer.
 */
public class AmapRouteGuidance extends RouteGuidance {
    private static final String TAG = "AmapRouteGuidance";
    private static final long SOFT_INACTIVE_GRACE_MS = 5000L;

    private int rawRouteState = -1;
    private int rawManeuverCount = 0;
    private int rawVisibleInApp = -1;
    private int rawSourceSupportsRg = -1;

    private long lastGuidanceEvidenceMs = 0L;
    private long softInactiveStartedMs = 0L;
    private int softInactiveGeneration = 0;
    private boolean softInactiveTimerRunning = false;
    private boolean softInactiveLatched = false;
    private boolean compatActive = false;

    public synchronized void start() {
        resetCompatState();
        super.start();
    }

    public synchronized void stop() {
        resetCompatState();
        super.stop();
    }

    public synchronized void onFrame(int type, int flags, byte[] payload, int len) {
        if (type != CarplayBus.EVT_RGD_UPDATE || payload == null || len <= 0) {
            super.onFrame(type, flags, payload, len);
            return;
        }

        CarplayBus.Data d = CarplayBus.parseText(payload, len);
        if (d == null) {
            super.onFrame(type, flags, payload, len);
            return;
        }

        updateRawState(d);

        long now = System.currentTimeMillis();
        boolean freshEvidence = hasFreshGuidanceEvidence(d);
        if (freshEvidence) {
            lastGuidanceEvidenceMs = now;
        }

        boolean hardClear = AmapCompatibility.isHardClear(rawRouteState, rawSourceSupportsRg);
        boolean softInactive = !hardClear && AmapCompatibility.isSoftInactive(
            rawRouteState, rawManeuverCount, rawVisibleInApp);
        boolean positiveActivationDelta = hasPositiveActivationDelta(d);

        if (hardClear) {
            compatActive = false;
            softInactiveLatched = false;
        } else if (positiveActivationDelta
                || (rawVisibleInApp < 0 && rawRouteState >= 1)) {
            compatActive = true;
            softInactiveLatched = false;
        }

        /* Match v38: only an already-active route gets the soft-inactive grace. */
        if (softInactive && compatActive) {
            softInactiveLatched = true;
            if (softInactiveStartedMs == 0L) {
                softInactiveStartedMs = now;
                Log.i(TAG, "RG soft-inactive window started: route_state=" + rawRouteState
                    + " maneuver_count=" + rawManeuverCount
                    + " visible_in_app=" + rawVisibleInApp
                    + " source=" + rawSourceSupportsRg
                    + " grace_ms=" + SOFT_INACTIVE_GRACE_MS);
            }
        }

        long graceRemaining = softInactiveLatched
            ? AmapCompatibility.graceRemaining(now, softInactiveStartedMs,
                lastGuidanceEvidenceMs, SOFT_INACTIVE_GRACE_MS)
            : 0L;
        boolean graceActive = graceRemaining > 0L;

        if (hardClear) {
            cancelSoftInactiveTimer();
            super.onFrame(type, flags, payload, len);
            return;
        }

        /*
         * v38 behaviour: visible=0 is not an immediate teardown when other
         * route evidence says the session is alive.  Keep the raw zero in this
         * adapter, but present it to the existing RouteGuidance as -1/unknown
         * so route/maneuver evidence decides activity.  This also clears a
         * stale visible=0 left in the base class from the previous route.
         */
        boolean normalizeVisibleZero = rawVisibleInApp == 0 && compatActive
            && (graceActive || rawRouteState > 0 || rawManeuverCount > 0 || freshEvidence);
        boolean suppressTransientCountClear = graceActive && softInactive
            && d.has("maneuver_count") && rawManeuverCount == 0;
        boolean suppressTransientListClear = false;
        if (graceActive && softInactive && d.has("maneuver_list")) {
            int[] list = d.intList("maneuver_list");
            suppressTransientListClear = (list == null || list.length == 0);
        }

        byte[] forwarded = payload;
        int forwardedLen = len;
        if (normalizeVisibleZero || suppressTransientCountClear || suppressTransientListClear) {
            forwarded = normalizePayload(payload, len, normalizeVisibleZero,
                suppressTransientCountClear, suppressTransientListClear);
            forwardedLen = forwarded.length;
        }

        super.onFrame(type, flags, forwarded, forwardedLen);

        if (softInactiveLatched && graceActive) {
            ensureSoftInactiveTimer();
        } else {
            cancelSoftInactiveTimer();
        }
    }

    private void updateRawState(CarplayBus.Data d) {
        if (d.has("route_state")) {
            rawRouteState = d.num("route_state", -1);
        }
        if (d.has("maneuver_count")) {
            rawManeuverCount = d.num("maneuver_count", 0);
        }
        if (d.has("visible_in_app")) {
            int raw = d.num("visible_in_app", -1);
            rawVisibleInApp = (raw == 0 || raw == 1) ? raw : -1;
        }
        if (d.has("source_supports_rg")) {
            rawSourceSupportsRg = d.num("source_supports_rg", -1);
        }
    }

    private boolean hasPositiveActivationDelta(CarplayBus.Data d) {
        if (d.has("visible_in_app") && rawVisibleInApp == 1) return true;
        if (d.has("route_state") && rawRouteState > 1) return true;
        if (d.has("maneuver_count") && rawManeuverCount > 0) return true;
        if (d.has("maneuver_list")) {
            int[] list = d.intList("maneuver_list");
            if (list != null && list.length > 0) return true;
        }
        return false;
    }

    private boolean hasFreshGuidanceEvidence(CarplayBus.Data d) {
        if (d.has("route_state") && rawRouteState > 1) return true;
        if (d.has("maneuver_count") && rawManeuverCount > 0) return true;
        if (d.has("maneuver_list")) {
            int[] list = d.intList("maneuver_list");
            if (list != null && list.length > 0) return true;
        }
        if (d.has("dist_maneuver_m") && d.num("dist_maneuver_m", -1) > 0) return true;
        if (d.has("dist_dest_m") && d.num("dist_dest_m", -1) > 0) return true;
        if (d.has("eta_seconds") && d.num("eta_seconds", -1) > 0) return true;
        if (d.has("time_remaining_seconds") && d.num64("time_remaining_seconds", -1) > 0) return true;
        if (d.has("lane_guidance_total") && d.num("lane_guidance_total", -1) > 0) return true;
        if (d.has("lane_guidance_showing") && d.num("lane_guidance_showing", -1) > 0) return true;

        /* A maneuver-slot update is also positive guidance evidence. */
        for (int i = 0; i < 32; i++) {
            String p = "m" + i + "_";
            if (d.has(p + "type") || d.has(p + "turn_angle") || d.has(p + "distance")
                || d.has(p + "name") || d.has(p + "after_road") || d.has(p + "ver")) {
                return true;
            }
        }
        return false;
    }

    private byte[] normalizePayload(byte[] payload, int len, boolean normalizeVisibleZero,
                                    boolean removeCountClear, boolean removeListClear) {
        try {
            String text = new String(payload, 0, len, "UTF-8");
            StringBuffer out = new StringBuffer(text.length() + 32);
            int pos = 0;
            boolean sawVisible = false;

            while (pos < text.length()) {
                int eol = text.indexOf('\n', pos);
                boolean hadNewline = eol >= 0;
                if (!hadNewline) eol = text.length();

                String line = text.substring(pos, eol);
                String normalized = line;
                boolean hadCR = normalized.endsWith("\r");
                if (hadCR) {
                    normalized = normalized.substring(0, normalized.length() - 1);
                }

                boolean drop = false;
                boolean replaceVisible = false;
                if (normalized.startsWith("visible_in_app:")) {
                    sawVisible = true;
                    if (normalizeVisibleZero) replaceVisible = true;
                }
                if (removeCountClear && normalized.startsWith("maneuver_count:")) {
                    drop = true;
                }
                if (removeListClear && normalized.startsWith("maneuver_list:")) {
                    drop = true;
                }

                if (!drop) {
                    if (replaceVisible) {
                        out.append("visible_in_app:n:-1");
                        if (hadCR) out.append('\r');
                    } else {
                        out.append(line);
                    }
                    if (hadNewline) out.append('\n');
                }
                pos = eol + 1;
            }

            /* A previous genuine stop may have left visible=0 cached in the
             * base RouteGuidance.  Positive route evidence can arrive before
             * Amap sends another visible field, so explicitly clear authority. */
            if (normalizeVisibleZero && !sawVisible) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
                out.append("visible_in_app:n:-1\n");
            }

            return out.toString().getBytes("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "Failed to normalize Amap lifecycle frame", e);
            return payload;
        }
    }

    private void ensureSoftInactiveTimer() {
        if (softInactiveTimerRunning) return;
        softInactiveTimerRunning = true;
        final int generation = ++softInactiveGeneration;

        Thread t = new Thread(new Runnable() {
            public void run() {
                runSoftInactiveTimer(generation);
            }
        }, "AmapRGSoftInactive");
        t.setDaemon(true);
        t.start();
    }

    private void runSoftInactiveTimer(int generation) {
        while (true) {
            long sleepMs;
            synchronized (this) {
                if (generation != softInactiveGeneration || !softInactiveTimerRunning
                    || !softInactiveLatched || !compatActive) {
                    return;
                }
                if (AmapCompatibility.isHardClear(rawRouteState, rawSourceSupportsRg)) {
                    softInactiveTimerRunning = false;
                    return;
                }

                sleepMs = AmapCompatibility.graceRemaining(System.currentTimeMillis(),
                    softInactiveStartedMs, lastGuidanceEvidenceMs, SOFT_INACTIVE_GRACE_MS);
                if (sleepMs <= 0L) {
                    softInactiveTimerRunning = false;
                    softInactiveLatched = false;
                    softInactiveStartedMs = 0L;
                    compatActive = false;
                    Log.i(TAG, "RG soft-inactive grace expired; forwarding visible_in_app=0");
                    forwardSoftInactiveExpiry();
                    return;
                }
            }

            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void forwardSoftInactiveExpiry() {
        try {
            String text = "@routeguidance\nvisible_in_app:n:0\n";
            byte[] frame = text.getBytes("UTF-8");
            AmapRouteGuidance.super.onFrame(CarplayBus.EVT_RGD_UPDATE, 0, frame, frame.length);
        } catch (Exception e) {
            Log.e(TAG, "Failed to forward soft-inactive expiry", e);
        }
    }

    private void cancelSoftInactiveTimer() {
        if (softInactiveTimerRunning || softInactiveStartedMs != 0L || softInactiveLatched) {
            softInactiveGeneration++;
        }
        softInactiveTimerRunning = false;
        softInactiveStartedMs = 0L;
        softInactiveLatched = false;
    }

    private void resetCompatState() {
        cancelSoftInactiveTimer();
        rawRouteState = -1;
        rawManeuverCount = 0;
        rawVisibleInApp = -1;
        rawSourceSupportsRg = -1;
        lastGuidanceEvidenceMs = 0L;
        compatActive = false;
    }
}
