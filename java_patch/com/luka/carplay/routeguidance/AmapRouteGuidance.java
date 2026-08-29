package com.luka.carplay.routeguidance;

import com.luka.carplay.framework.CarplayBus;
import com.luka.carplay.framework.Log;

/**
 * Amap protocol router.
 *
 * Design goal:
 *   - legacy/old Amap protocol -> exactly the current main RouteGuidance path;
 *   - newer ambiguous Amap protocol -> v38-derived compatibility path.
 *
 * We intentionally detect behavior instead of hard-coding an iOS version.
 * The newer protocol is identified by the observed lifecycle sequence where an
 * already-active route reports route_state=1, maneuver_count=0 and
 * visible_in_app=0, then continues to publish real maneuver progress.
 *
 * State machine:
 *
 *   LEGACY  --raw frame--> current main
 *      |
 *      | route=1,count=0,visible=0 after active route
 *      v
 *   PROBING --hold ambiguous zero for <=5 s--+
 *      |                                      |
 *      | real maneuver progress               | no progress / timeout
 *      v                                      v
 *   V38_COMPAT ----------------------------> LEGACY shutdown
 *      |
 *      | route_state=0 / source_supports_rg=0 / disconnect
 *      v
 *   reset for next route
 *
 * This keeps iOS27-below/legacy behavior out of the v38 display state machine.
 */
public class AmapRouteGuidance extends RouteGuidance {
    private static final String TAG = "AmapRouteGuidance";
    private static final long PROBE_GRACE_MS = 5000L;

    private final AmapProtocolDetector detector = new AmapProtocolDetector();
    private final AmapV38Compat v38 = new AmapV38Compat();

    private int rawRouteState = -1;
    private int rawManeuverCount = 0;
    private int rawVisibleInApp = -1;
    private int rawSourceSupportsRg = -1;

    private int probeGeneration = 0;
    private boolean probeTimerRunning = false;

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

        /* If CarPlay switches away from Amap during the same connection, do
         * not let an Amap compatibility latch leak into the new source. */
        if (detector.mode() != AmapProtocolDetector.MODE_LEGACY
                && detector.sourceChangedAwayFromAmap(d)) {
            Log.i(TAG, "Amap source changed; V38 compatibility released");
            cancelProbeTimer();
            v38.disable();
            detector.resetRouteOnly();
            super.onFrame(type, flags, payload, len);
            return;
        }

        boolean hardClear = detector.isHardClear(
            rawRouteState, rawSourceSupportsRg, d);
        if (hardClear) {
            if (detector.mode() != AmapProtocolDetector.MODE_LEGACY) {
                Log.i(TAG, "Protocol mode " + detector.modeName()
                    + " -> LEGACY on hard route clear");
            }
            cancelProbeTimer();
            v38.reset();
            detector.resetRouteOnly();
            super.onFrame(type, flags, payload, len);
            return;
        }

        detector.observeActiveRoute(rawRouteState, rawManeuverCount,
            rawVisibleInApp, rawSourceSupportsRg);

        int mode = detector.mode();

        /* --------------------------------------------------------
         * LEGACY: preserve current main behavior byte-for-byte.
         * -------------------------------------------------------- */
        if (mode == AmapProtocolDetector.MODE_LEGACY) {
            if (!detector.shouldBeginProbe(rawRouteState, rawManeuverCount,
                    rawVisibleInApp, rawSourceSupportsRg)) {
                super.onFrame(type, flags, payload, len);
                return;
            }

            detector.beginProbe(System.currentTimeMillis());
            Log.i(TAG, "Protocol LEGACY -> PROBING: source="
                + safe(detector.sourceName())
                + " route_state=" + rawRouteState
                + " maneuver_count=" + rawManeuverCount
                + " visible_in_app=" + rawVisibleInApp
                + " source_supports_rg=" + rawSourceSupportsRg);

            byte[] protectedFrame = protectProbeFrame(payload, len, d);
            super.onFrame(type, flags, protectedFrame, protectedFrame.length);
            ensureProbeTimer();
            return;
        }

        /* --------------------------------------------------------
         * PROBING: protect the active renderer but do not commit to
         * V38 until actual maneuver progress proves the zero was not
         * a legitimate legacy inactive transition.
         * -------------------------------------------------------- */
        if (mode == AmapProtocolDetector.MODE_PROBING) {
            /* A normal legacy recovery (visible=1) means the probe was a
             * transient; immediately return to unmodified main behavior. */
            if (rawVisibleInApp == 1) {
                Log.i(TAG, "Protocol PROBING -> LEGACY: visible_in_app recovered to 1");
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
                Log.i(TAG, "Protocol PROBING -> V38_COMPAT: continuing maneuver data"
                    + " source=" + safe(detector.sourceName()));

                byte[] frame = v38.process(payload, len, d,
                    rawRouteState, rawManeuverCount,
                    rawVisibleInApp, rawSourceSupportsRg);
                super.onFrame(type, flags, frame, frame.length);
                return;
            }

            byte[] protectedFrame = protectProbeFrame(payload, len, d);
            super.onFrame(type, flags, protectedFrame, protectedFrame.length);
            ensureProbeTimer();
            return;
        }

        /* --------------------------------------------------------
         * V38_COMPAT: lifecycle + maneuver/display stabilization.
         * Current main BAPBridge/renderer remain the output backend.
         * -------------------------------------------------------- */
        byte[] frame = v38.process(payload, len, d,
            rawRouteState, rawManeuverCount,
            rawVisibleInApp, rawSourceSupportsRg);
        super.onFrame(type, flags, frame, frame.length);
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

    /** Protect only the exact ambiguous probe transition. */
    private byte[] protectProbeFrame(byte[] payload, int len, CarplayBus.Data d) {
        boolean removeCountClear = d.has("maneuver_count") && rawManeuverCount == 0;
        boolean removeListClear = false;
        if (d.has("maneuver_list")) {
            int[] list = d.intList("maneuver_list");
            removeListClear = (list == null || list.length == 0);
        }

        try {
            String text = new String(payload, 0, len, "UTF-8");
            StringBuffer out = new StringBuffer(text.length() + 32);
            int pos = 0;

            while (pos < text.length()) {
                int eol = text.indexOf('\n', pos);
                boolean hadNewline = eol >= 0;
                if (!hadNewline) eol = text.length();

                String line = text.substring(pos, eol);
                String normalized = line;
                boolean hadCR = normalized.endsWith("\r");
                if (hadCR) normalized = normalized.substring(0, normalized.length() - 1);

                boolean drop = false;
                boolean replaceVisible = false;

                if (rawVisibleInApp == 0 && normalized.startsWith("visible_in_app:")) {
                    replaceVisible = true;
                }
                if (removeCountClear && normalized.startsWith("maneuver_count:")) {
                    drop = true;
                }
                if (removeListClear && normalized.startsWith("maneuver_list:")) {
                    drop = true;
                }

                if (!drop) {
                    if (replaceVisible) {
                        /* Unknown, not active=1: avoids the old 0->1 dirty loop
                         * while allowing base RouteGuidance to use route evidence. */
                        out.append("visible_in_app:n:-1");
                        if (hadCR) out.append('\r');
                    } else {
                        out.append(line);
                    }
                    if (hadNewline) out.append('\n');
                }
                pos = eol + 1;
            }

            return out.toString().getBytes("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "Failed to protect protocol probe frame", e);
            return payload;
        }
    }

    private void ensureProbeTimer() {
        if (probeTimerRunning) return;
        probeTimerRunning = true;
        final int generation = ++probeGeneration;

        Thread t = new Thread(new Runnable() {
            public void run() {
                runProbeTimer(generation);
            }
        }, "AmapProtocolProbe");
        t.setDaemon(true);
        t.start();
    }

    private void runProbeTimer(int generation) {
        long sleepMs;
        synchronized (this) {
            if (!probeTimerRunning || generation != probeGeneration
                    || detector.mode() != AmapProtocolDetector.MODE_PROBING) {
                return;
            }
            long elapsed = System.currentTimeMillis() - detector.probeStartedMs();
            if (elapsed < 0) elapsed = 0;
            sleepMs = PROBE_GRACE_MS - elapsed;
        }

        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                return;
            }
        }

        synchronized (this) {
            if (!probeTimerRunning || generation != probeGeneration
                    || detector.mode() != AmapProtocolDetector.MODE_PROBING) {
                return;
            }
            probeTimerRunning = false;
            detector.rejectProbe();
            v38.disable();
            Log.i(TAG, "Protocol PROBING -> LEGACY: no V38 evidence within "
                + PROBE_GRACE_MS + "ms; forwarding real visible_in_app=0");
            forwardProbeExpiry();
        }
    }

    private void forwardProbeExpiry() {
        try {
            String text = "@routeguidance\nvisible_in_app:n:0\n";
            byte[] frame = text.getBytes("UTF-8");
            AmapRouteGuidance.super.onFrame(
                CarplayBus.EVT_RGD_UPDATE, 0, frame, frame.length);
        } catch (Exception e) {
            Log.e(TAG, "Failed to forward protocol probe expiry", e);
        }
    }

    private void cancelProbeTimer() {
        if (probeTimerRunning) probeGeneration++;
        probeTimerRunning = false;
    }

    private void resetRouterState() {
        cancelProbeTimer();
        detector.reset();
        v38.reset();
        rawRouteState = -1;
        rawManeuverCount = 0;
        rawVisibleInApp = -1;
        rawSourceSupportsRg = -1;
    }

    private static String safe(String value) {
        return value == null ? "<unknown>" : value;
    }
}
