package com.luka.carplay.routeguidance;

import com.luka.carplay.framework.CarplayBus;
import com.luka.carplay.framework.Log;

/**
 * Amap router for the second-generation main + v38 merge.
 *
 * LEGACY is byte-for-byte main input behavior. Only the observed Amap
 * 1/0/0 lifecycle signature starts a protected probe. When continuing real
 * maneuver data confirms the newer behavior, the route switches to the v38
 * Amap state engine for that route while main BAPBridge/renderer stay intact.
 */
public class AmapRouteGuidance extends RouteGuidance {
    private static final String TAG = "AmapRouteGuidance2";
    private static final long PROBE_GRACE_MS = 5000L;

    private final AmapProtocolDetector detector = new AmapProtocolDetector();
    private final AmapV38Compat v38 = new AmapV38Compat();

    private int rawRouteState = -1;
    private int rawManeuverCount = 0;
    private int rawVisibleInApp = -1;
    private int rawSourceSupportsRg = -1;

    private int probeGeneration;
    private boolean probeTimerRunning;
    private int softGeneration;
    private boolean softTimerRunning;

    public synchronized void start() {
        resetRouterState();
        super.start();
    }

    public synchronized void stop() {
        resetRouterState();
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

        detector.observeSource(d);
        updateRawState(d);
        v38.observe(d);

        if (detector.mode() != AmapProtocolDetector.MODE_LEGACY
                && detector.sourceChangedAwayFromAmap(d)) {
            Log.i(TAG, "Amap source changed; compatibility state released");
            cancelProbeTimer();
            cancelSoftTimer();
            v38.disable();
            detector.resetRouteOnly();
            super.onFrame(type, flags, payload, len);
            return;
        }

        if (detector.isHardClear(rawRouteState, rawSourceSupportsRg, d)) {
            Log.i(TAG, "hard route clear; reset Amap compatibility state");
            cancelProbeTimer();
            cancelSoftTimer();
            v38.reset();
            detector.resetRouteOnly();
            super.onFrame(type, flags, payload, len);
            return;
        }

        detector.observeActiveRoute(rawRouteState, rawManeuverCount,
            rawVisibleInApp, rawSourceSupportsRg);

        int mode = detector.mode();
        if (mode == AmapProtocolDetector.MODE_LEGACY) {
            if (!detector.shouldBeginProbe(rawRouteState, rawManeuverCount,
                    rawVisibleInApp, rawSourceSupportsRg)) {
                super.onFrame(type, flags, payload, len);
                return;
            }

            detector.beginProbe(System.currentTimeMillis(), d);
            Log.i(TAG, "LEGACY -> PROBING source=" + safe(detector.sourceName())
                + " route=" + rawRouteState + " count=" + rawManeuverCount
                + " visible=" + rawVisibleInApp);
            byte[] protectedFrame = protectProbeFrame(payload, len, d);
            super.onFrame(type, flags, protectedFrame, protectedFrame.length);
            ensureProbeTimer();
            return;
        }

        if (mode == AmapProtocolDetector.MODE_PROBING) {
            if (rawVisibleInApp == 1) {
                Log.i(TAG, "PROBING -> LEGACY: visible recovered");
                cancelProbeTimer();
                detector.rejectProbe();
                v38.disable();
                super.onFrame(type, flags, payload, len);
                return;
            }

            if (rawRouteState > 0 && rawSourceSupportsRg != 0
                    && detector.hasV38ConfirmationEvidence(d)) {
                cancelProbeTimer();
                detector.confirmV38();
                v38.enable();
                Log.i(TAG, "PROBING -> V38_COMPAT: continuing maneuver evidence");
                byte[] frame = v38.process(payload, len, d, rawRouteState,
                    rawManeuverCount, rawVisibleInApp, rawSourceSupportsRg);
                frame = normalizeActiveV38Visibility(frame);
                super.onFrame(type, flags, frame, frame.length);
                syncSoftInactiveTimer();
                return;
            }

            byte[] protectedFrame = protectProbeFrame(payload, len, d);
            super.onFrame(type, flags, protectedFrame, protectedFrame.length);
            ensureProbeTimer();
            return;
        }

        byte[] frame = v38.process(payload, len, d, rawRouteState,
            rawManeuverCount, rawVisibleInApp, rawSourceSupportsRg);
        frame = normalizeActiveV38Visibility(frame);
        super.onFrame(type, flags, frame, frame.length);
        syncSoftInactiveTimer();
    }

    private void updateRawState(CarplayBus.Data d) {
        if (d.has("route_state")) rawRouteState = d.num("route_state", -1);
        if (d.has("maneuver_count")) rawManeuverCount = d.num("maneuver_count", 0);
        if (d.has("visible_in_app")) {
            int v = d.num("visible_in_app", -1);
            rawVisibleInApp = (v == 0 || v == 1) ? v : -1;
        }
        if (d.has("source_supports_rg")) rawSourceSupportsRg = d.num("source_supports_rg", -1);
    }

    /** Protect just the ambiguous probe frame; this is not the v38 engine. */
    private byte[] protectProbeFrame(byte[] payload, int len, CarplayBus.Data d) {
        boolean removeCount = d.has("maneuver_count") && rawManeuverCount == 0;
        boolean removeList = false;
        if (d.has("maneuver_list")) {
            int[] list = d.intList("maneuver_list");
            removeList = list == null || list.length == 0;
        }
        try {
            String text = new String(payload, 0, len, "UTF-8");
            StringBuffer out = new StringBuffer(text.length() + 32);
            int pos = 0;
            while (pos < text.length()) {
                int eol = text.indexOf('\n', pos);
                boolean nl = eol >= 0;
                if (!nl) eol = text.length();
                String line = text.substring(pos, eol);
                String n = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                boolean drop = (removeCount && n.startsWith("maneuver_count:"))
                    || (removeList && n.startsWith("maneuver_list:"));
                if (!drop) {
                    if (rawVisibleInApp == 0 && n.startsWith("visible_in_app:"))
                        out.append("visible_in_app:n:-1");
                    else out.append(line);
                    if (nl) out.append('\n');
                }
                pos = eol + 1;
            }
            return out.toString().getBytes("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "probe-frame protection failed", e);
            return payload;
        }
    }

    /**
     * In v38 mode visible_in_app=0 is not authoritative while real guidance
     * is active. The original v38 lifecycle keeps the route alive when
     * maneuver/route evidence continues. Present 0 to main only for the exact
     * 1/0/0 soft-inactive state (where AmapV38Compat either holds it as -1 or,
     * after grace expiry, deliberately lets the real 0 through).
     */
    private byte[] normalizeActiveV38Visibility(byte[] payload) {
        if (payload == null || payload.length == 0) return payload;
        if (rawVisibleInApp != 0 || rawRouteState <= 0 || rawSourceSupportsRg == 0)
            return payload;

        /* Exact soft-inactive lifecycle is owned by AmapV38Compat. Before
         * expiry it already emits -1; after expiry it must be allowed to emit
         * the real 0 so main performs its normal shutdown. */
        if (rawRouteState == 1 && rawManeuverCount == 0)
            return payload;

        try {
            String text = new String(payload, "UTF-8");
            StringBuffer out = new StringBuffer(text.length() + 24);
            int pos = 0;
            boolean replaced = false;
            while (pos < text.length()) {
                int eol = text.indexOf('\n', pos);
                boolean nl = eol >= 0;
                if (!nl) eol = text.length();
                String line = text.substring(pos, eol);
                String n = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (n.startsWith("visible_in_app:")) {
                    out.append("visible_in_app:n:-1");
                    replaced = true;
                } else {
                    out.append(line);
                }
                if (nl) out.append('\n');
                pos = eol + 1;
            }
            if (!replaced) out.append("visible_in_app:n:-1\n");
            return out.toString().getBytes("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "active-v38 visibility normalization failed", e);
            return payload;
        }
    }

    private void ensureProbeTimer() {
        if (probeTimerRunning) return;
        probeTimerRunning = true;
        final int gen = ++probeGeneration;
        Thread t = new Thread(new Runnable() {
            public void run() { runProbeTimer(gen); }
        }, "AmapProtocolProbe");
        t.setDaemon(true);
        t.start();
    }

    private void runProbeTimer(int gen) {
        long sleep;
        synchronized (this) {
            if (!probeTimerRunning || gen != probeGeneration
                    || detector.mode() != AmapProtocolDetector.MODE_PROBING) return;
            long elapsed = System.currentTimeMillis() - detector.probeStartedMs();
            if (elapsed < 0) elapsed = 0;
            sleep = PROBE_GRACE_MS - elapsed;
        }
        if (sleep > 0) {
            try { Thread.sleep(sleep); } catch (InterruptedException e) { return; }
        }
        synchronized (this) {
            if (!probeTimerRunning || gen != probeGeneration
                    || detector.mode() != AmapProtocolDetector.MODE_PROBING) return;
            probeTimerRunning = false;
            detector.rejectProbe();
            v38.disable();
            Log.i(TAG, "PROBING -> LEGACY: no confirmation within 5 s");
            /* Keep timeout decision + forwarded inactive state atomic with
             * synchronized onFrame(). A recovery frame cannot race between
             * deciding to expire and delivering the corresponding clear. */
            forwardProbeInactiveState();
        }
    }

    private void syncSoftInactiveTimer() {
        if (!v38.isSoftInactiveHolding()) {
            cancelSoftTimer();
            return;
        }
        if (softTimerRunning) return;
        softTimerRunning = true;
        final int gen = ++softGeneration;
        Thread t = new Thread(new Runnable() {
            public void run() { runSoftTimer(gen); }
        }, "AmapSoftInactive");
        t.setDaemon(true);
        t.start();
    }

    private void runSoftTimer(int gen) {
        while (true) {
            long deadline;
            synchronized (this) {
                if (!softTimerRunning || gen != softGeneration
                        || detector.mode() != AmapProtocolDetector.MODE_V38_COMPAT
                        || !v38.isSoftInactiveHolding()) return;
                deadline = v38.softInactiveDeadlineMs();
            }
            long left = deadline - System.currentTimeMillis();
            if (left > 0) {
                try { Thread.sleep(left); } catch (InterruptedException e) { return; }
                continue;
            }

            synchronized (this) {
                if (!softTimerRunning || gen != softGeneration
                        || !v38.isSoftInactiveHolding()) return;
                long current = v38.softInactiveDeadlineMs();
                if (current > System.currentTimeMillis()) continue;
                v38.noteSoftInactiveExpired();
                byte[] expiry = v38.buildSoftInactiveExpiryFrame();
                softTimerRunning = false;
                /* Same atomicity rule as probe expiry: either a recovery
                 * onFrame wins the monitor first, or this timeout clear does. */
                if (expiry != null && expiry.length > 0) {
                    AmapRouteGuidance.super.onFrame(
                        CarplayBus.EVT_RGD_UPDATE, 0, expiry, expiry.length);
                }
            }
            return;
        }
    }

    /** Restore every field suppressed by protectProbeFrame before handing a
     * legitimate inactive transition back to unchanged main RouteGuidance. */
    private void forwardProbeInactiveState() {
        try {
            StringBuffer b = new StringBuffer(128);
            b.append("@routeguidance\n");
            if (rawRouteState >= 0)
                b.append("route_state:n:").append(rawRouteState).append('\n');
            b.append("maneuver_count:n:0\n");
            b.append("maneuver_list:s:\n");
            b.append("visible_in_app:n:0\n");
            if (rawSourceSupportsRg >= 0)
                b.append("source_supports_rg:n:").append(rawSourceSupportsRg).append('\n');
            byte[] frame = b.toString().getBytes("UTF-8");
            AmapRouteGuidance.super.onFrame(CarplayBus.EVT_RGD_UPDATE, 0, frame, frame.length);
        } catch (Exception e) {
            Log.e(TAG, "probe expiry forward failed", e);
        }
    }

    private void cancelProbeTimer() {
        if (probeTimerRunning) probeGeneration++;
        probeTimerRunning = false;
    }

    private void cancelSoftTimer() {
        if (softTimerRunning) softGeneration++;
        softTimerRunning = false;
    }

    private void resetRouterState() {
        cancelProbeTimer();
        cancelSoftTimer();
        detector.reset();
        v38.reset();
        rawRouteState = -1;
        rawManeuverCount = 0;
        rawVisibleInApp = -1;
        rawSourceSupportsRg = -1;
    }

    private static String safe(String s) { return s == null ? "<unknown>" : s; }
}
