package com.luka.carplay.routeguidance;

import com.luka.carplay.framework.CarplayBus;
import com.luka.carplay.framework.Log;

/**
 * Second-generation v38 Amap engine for the vehicle-tested main backend.
 *
 * Unlike the first merge, this class never tries to HOLD a maneuver by
 * deleting a handful of delta fields. It owns a complete raw cache and a
 * separate committed display snapshot. During HOLD it serializes the whole
 * committed snapshot, so main RouteGuidance cannot observe mixed old/new
 * slot identity, angle, road text, lane or distance fields.
 */
final class AmapV38Compat {
    private static final String TAG = "AmapV38Compat2";
    private static final int MAX_MANEUVERS = 32;
    private static final long SOFT_INACTIVE_GRACE_MS = 5000L;

    private final RawState raw = new RawState();
    private Snapshot committed;
    private final AmapDisplayStabilizer stabilizer = new AmapDisplayStabilizer();
    private final AmapFormalLock formalLock = new AmapFormalLock();
    private final AmapProgressTracker progress = new AmapProgressTracker();
    private final AmapRolloverStateMachine rollover = new AmapRolloverStateMachine();

    private boolean enabled;
    private long softInactiveStartedMs;
    private long lastGuidanceEvidenceMs;
    private boolean softInactiveExpired;
    private int routeGeneration;
    private boolean rawRoutePreviouslyActive;

    AmapV38Compat() { reset(); }

    void reset() {
        enabled = false;
        raw.reset();
        committed = null;
        stabilizer.reset();
        formalLock.reset();
        progress.reset();
        rollover.reset();
        softInactiveStartedMs = 0L;
        lastGuidanceEvidenceMs = 0L;
        softInactiveExpired = false;
        routeGeneration = 0;
        rawRoutePreviouslyActive = false;
    }

    void observe(CarplayBus.Data d) {
        if (d == null) return;
        long now = System.currentTimeMillis();
        raw.observe(d);

        boolean activeNow = raw.routeState > 0 || raw.maneuverCount > 0
            || (raw.maneuverList != null && raw.maneuverList.length > 0);
        if (activeNow && !rawRoutePreviouslyActive) routeGeneration++;
        rawRoutePreviouslyActive = activeNow;

        if (hasFreshGuidanceEvidence(d)) {
            lastGuidanceEvidenceMs = now;
            if (softInactiveExpired && !isSoftInactive(raw.routeState,
                    raw.maneuverCount, raw.visibleInApp)) {
                softInactiveExpired = false;
            }
        }
    }

    void enable() {
        enabled = true;
        softInactiveExpired = false;
        seedFromRaw();
        Log.i(TAG, "v38 Amap engine enabled: gen=" + routeGeneration
            + " head=" + raw.head() + " visual=" + visualKey(raw, raw.head()));
    }

    void disable() {
        enabled = false;
        softInactiveStartedMs = 0L;
        softInactiveExpired = false;
        stabilizer.reset();
        formalLock.reset();
        progress.reset();
        rollover.reset();
    }

    boolean isEnabled() { return enabled; }

    boolean isSoftInactiveHolding() {
        return enabled && !softInactiveExpired && softInactiveStartedMs > 0L;
    }

    long softInactiveDeadlineMs() {
        if (!isSoftInactiveHolding()) return 0L;
        long anchor = softInactiveStartedMs;
        if (lastGuidanceEvidenceMs > anchor) anchor = lastGuidanceEvidenceMs;
        return anchor + SOFT_INACTIVE_GRACE_MS;
    }

    void noteSoftInactiveExpired() {
        softInactiveExpired = true;
        softInactiveStartedMs = 0L;
        Log.i(TAG, "v38 soft-inactive grace expired");
    }

    byte[] buildSoftInactiveExpiryFrame() {
        try {
            StringBuffer b = new StringBuffer(160);
            b.append("@routeguidance\n");
            b.append("route_state:n:1\n");
            b.append("maneuver_count:n:0\n");
            b.append("maneuver_list:s:\n");
            b.append("visible_in_app:n:0\n");
            if (raw.sourceSupportsRg >= 0)
                b.append("source_supports_rg:n:").append(raw.sourceSupportsRg).append('\n');
            return b.toString().getBytes("UTF-8");
        } catch (Exception e) {
            return new byte[0];
        }
    }

    byte[] process(byte[] original, int len, CarplayBus.Data d,
                   int routeState, int maneuverCount, int visibleInApp,
                   int sourceSupportsRg) {
        if (!enabled || original == null || len <= 0) return original;
        if (routeState == 0 || sourceSupportsRg == 0) {
            softInactiveStartedMs = 0L;
            softInactiveExpired = false;
            return original;
        }

        long now = System.currentTimeMillis();
        boolean soft = isSoftInactive(routeState, maneuverCount, visibleInApp);
        if (soft && !softInactiveExpired) {
            if (softInactiveStartedMs == 0L) {
                softInactiveStartedMs = now;
                Log.i(TAG, "v38 soft-inactive hold started");
            }
            if (committed != null) {
                Snapshot hold = committed.copy();
                copyLiveTopLevel(raw, hold);
                hold.routeState = routeState > 0 ? routeState : 1;
                hold.visibleInApp = -1;
                return hold.toPayload();
            }
            return protectVisibilityOnly(original, len);
        }

        if (!soft) {
            softInactiveStartedMs = 0L;
            softInactiveExpired = false;
        } else if (softInactiveExpired) {
            return original;
        }

        int head = raw.head();
        int ver = raw.value(raw.mVer, head, -1);
        int type = raw.value(raw.mType, head, -1);
        int dist = raw.primaryDistance();
        int nodeDistance = raw.value(raw.mDistance, head, -1);
        String visual = visualKey(raw, head);
        String road = roadKey(raw, head);
        boolean valid = validHead(raw, head, ver, type);
        boolean aligned = valid && raw.headAligned;
        boolean distanceFresh = raw.distanceFresh || (dist > 0 && committed == null);

        if (committed == null && valid && aligned) {
            commitRaw(head, ver, type, visual, road, dist, nodeDistance, false);
            return committed.toPayload();
        }
        if (committed == null) return original;

        boolean samePhysical = samePhysical(head, ver, committed.physicalHead,
            committed.physicalVer, routeGeneration, committed.physicalGeneration);

        if (valid && samePhysical && isFormalType(committed.rawType)
                && (isPromptType(type) || isNeutralStraight(raw, head, type))
                && !visual.equals(committed.visualKey)
                && formalLock.isLatePrompt(head, ver, routeGeneration, head, type, road)) {
            if (dist <= 0) {
                stabilizer.noteCompletion();
                formalLock.markCompletionFrame();
            }
            updateCommittedProgress(dist, nodeDistance, head, ver);
            Snapshot hold = committed.copy();
            copyLiveTopLevel(raw, hold);
            applyTrackedDistance(hold);
            Log.d(TAG, "[V38-FORMAL-LOCK] hold late prompt head=" + head
                + " type=" + type + " committedType=" + committed.rawType);
            return hold.toPayload();
        }

        if (valid && formalLock.sawCompletion() && visual.equals(committed.visualKey)
                && !samePhysical) {
            int rr = rollover.observe(aligned, distanceFresh, true, visual, dist,
                routeGeneration, head);
            if (rr == AmapRolloverStateMachine.COMMIT_SAME_VISUAL) {
                commitRaw(head, ver, type, visual, road, dist, nodeDistance, true);
                return committed.toPayload();
            }
        }

        int decision = stabilizer.evaluate(true, visual, head, ver, dist,
            aligned, distanceFresh, routeGeneration, head);

        if (decision == AmapDisplayStabilizer.PASS) {
            if (valid) {
                commitRaw(head, ver, type, visual, road, dist, nodeDistance, false);
                return committed.toPayload();
            }
            return original;
        }

        if (decision == AmapDisplayStabilizer.ACCEPT_SAME_VISUAL) {
            formalLock.updatePhysicalIdentity(head, ver, routeGeneration, head);
            committed.physicalHead = head;
            committed.physicalVer = ver;
            committed.physicalGeneration = routeGeneration;
            updateCommittedProgress(dist, nodeDistance, head, ver);
            Snapshot out = committed.copy();
            copyLiveTopLevel(raw, out);
            copyNonVisualPrimary(raw, head, out);
            applyTrackedDistance(out);
            return out.toPayload();
        }

        if (decision == AmapDisplayStabilizer.COMMIT_NEW_VISUAL
                || decision == AmapDisplayStabilizer.COMMIT_NEW_PHYSICAL) {
            commitRaw(head, ver, type, visual, road, dist, nodeDistance,
                decision == AmapDisplayStabilizer.COMMIT_NEW_PHYSICAL);
            return committed.toPayload();
        }

        if (dist <= 0) {
            stabilizer.noteCompletion();
            formalLock.markCompletionFrame();
            if (valid) rollover.begin(committed.visualKey,
                committed.distManeuverM, routeGeneration, committed.physicalHead);
        }
        updateCommittedProgress(dist, nodeDistance,
            committed.physicalHead, committed.physicalVer);
        Snapshot hold = committed.copy();
        copyLiveTopLevel(raw, hold);
        applyTrackedDistance(hold);
        Log.d(TAG, "[V38-STABILIZER] HOLD rawHead=" + head
            + " ver=" + ver + " visual=" + visual + " dist=" + dist);
        return hold.toPayload();
    }

    private void seedFromRaw() {
        int head = raw.head();
        int ver = raw.value(raw.mVer, head, -1);
        int type = raw.value(raw.mType, head, -1);
        if (!validHead(raw, head, ver, type)) return;
        int dist = raw.primaryDistance();
        int node = raw.value(raw.mDistance, head, -1);
        commitRaw(head, ver, type, visualKey(raw, head), roadKey(raw, head), dist, node, false);
    }

    private void commitRaw(int head, int ver, int type, String visual, String road,
                           int dist, int nodeDistance, boolean physicalRollover) {
        Snapshot next = Snapshot.fromRaw(raw);
        next.visualKey = visual == null ? "" : visual;
        next.rawType = type;
        next.physicalHead = head;
        next.physicalVer = ver;
        next.physicalGeneration = routeGeneration;
        committed = next;

        int den = nodeDistance > 0 ? nodeDistance : dist;
        progress.commit(dist, den, head, ver, routeGeneration, head, false);
        applyTrackedDistance(committed);
        stabilizer.seed(next.visualKey, head, ver, dist, routeGeneration, head);
        formalLock.commit(next.visualKey, road, head, ver, routeGeneration, head);
        rollover.reset();
        Log.i(TAG, (physicalRollover ? "[V38-ROLLOVER] " : "[V38-COMMIT] ")
            + "head=" + head + " ver=" + ver + " visual=" + next.visualKey
            + " dist=" + dist);
    }

    private void updateCommittedProgress(int dist, int nodeDistance, int head, int ver) {
        if (dist <= 0) return;
        int den = nodeDistance > 0 ? nodeDistance : progress.denominator();
        AmapProgressTracker.Result r = progress.update(dist, nodeDistance, den,
            head, ver, routeGeneration, head, false);
        if (r.suppressedIncrease)
            Log.d(TAG, "[V38-PROGRESS] suppress distance increase raw=" + dist
                + " kept=" + r.distanceM);
    }

    private void applyTrackedDistance(Snapshot s) {
        if (s != null && progress.distance() > 0) s.distManeuverM = progress.distance();
    }

    private static void copyLiveTopLevel(RawState r, Snapshot s) {
        if (r.routeState > 0) s.routeState = r.routeState;
        s.maneuverState = r.maneuverState;
        if (r.distDestM >= 0) s.distDestM = r.distDestM;
        if (r.etaSeconds >= 0) s.etaSeconds = r.etaSeconds;
        if (r.timeRemainingSeconds >= 0) s.timeRemainingSeconds = r.timeRemainingSeconds;
        if (r.currentRoad != null) s.currentRoad = r.currentRoad;
        if (r.destination != null) s.destination = r.destination;
        if (r.sourceSupportsRg >= 0) s.sourceSupportsRg = r.sourceSupportsRg;
        if (r.visibleInApp == 1) s.visibleInApp = 1;
    }

    private static void copyNonVisualPrimary(RawState r, int head, Snapshot s) {
        if (head < 0 || head >= MAX_MANEUVERS || s == null) return;
        int outHead = s.head();
        if (outHead < 0 || outHead >= MAX_MANEUVERS) return;
        s.mName[outHead] = r.mName[head];
        s.mAfterRoad[outHead] = r.mAfterRoad[head];
        s.mExitInfo[outHead] = r.mExitInfo[head];
        s.mDistance[outHead] = r.mDistance[head];
        s.mLaneCount[outHead] = r.mLaneCount[head];
        s.mLanePositions[outHead] = copy(r.mLanePositions[head]);
        s.mLaneDirections[outHead] = copy(r.mLaneDirections[head]);
        s.mLaneStatus[outHead] = copy(r.mLaneStatus[head]);
        s.mLaneAngles[outHead] = copyMatrix(r.mLaneAngles[head]);
    }

    private static boolean samePhysical(int h1, int v1, int h2, int v2,
                                        int gen1, int gen2) {
        if (gen1 >= 0 && gen2 >= 0 && gen1 != gen2) return false;
        return h1 >= 0 && h1 == h2 && v1 >= 0 && v1 == v2;
    }

    private static boolean validHead(RawState r, int head, int ver, int type) {
        return head >= 0 && head < MAX_MANEUVERS && ver >= 0
            && ManeuverMapper.isValidType(type);
    }

    static boolean isFormalType(int type) {
        return type == 1 || type == 2 || type == 4 || type == 6 || type == 7
            || type == 18 || type == 19 || type == 20 || type == 21
            || type == 26 || type == 47 || type == 48
            || (type >= 28 && type <= 46);
    }

    static boolean isPromptType(int type) {
        return type == 49 || type == 50 || type == 13 || type == 14
            || type == 51 || type == 52 || type == 53;
    }

    private static boolean isNeutralStraight(RawState r, int head, int type) {
        return type == 3 && head >= 0 && head < MAX_MANEUVERS
            && r.mTurnAngle[head] == 1000
            && r.mJunctionType[head] == 0
            && r.mLaneCount[head] <= 0;
    }

    private static String visualKey(RawState r, int head) {
        if (head < 0 || head >= MAX_MANEUVERS) return "";
        int type = r.mType[head];
        int angle = r.mTurnAngle[head];
        int junction = r.mJunctionType[head];
        int side = r.mDrivingSide[head];
        int[] mapped = ManeuverMapper.map(type, angle, junction, side);
        if (mapped == null || mapped.length < 2) return "";
        if (mapped[0] == ManeuverMapper.FOLLOW_STREET
                || mapped[0] == ManeuverMapper.NO_INFO
                || mapped[0] == ManeuverMapper.NO_SYMBOL)
            return "FOLLOW_STREET";

        byte[] streets = SideStreets.calcSideStreetsBytes(type, junction, side,
            r.mJunctionAngles[head], r.mExitAngle[head]);
        StringBuffer b = new StringBuffer(64);
        b.append("m=").append(mapped[0]);
        b.append("|d=").append(mapped[1]);
        b.append("|z=").append(r.mZLevel[head]);
        b.append("|s=");
        if (streets != null) {
            for (int i = 0; i < streets.length; i++) {
                if (i > 0) b.append(',');
                b.append(streets[i] & 0xff);
            }
        }
        return b.toString();
    }

    private static String roadKey(RawState r, int head) {
        if (head < 0 || head >= MAX_MANEUVERS) return "|";
        return safe(r.mName[head]) + "|" + safe(r.mAfterRoad[head]);
    }

    private static boolean isSoftInactive(int routeState, int count, int visible) {
        return routeState == 1 && count == 0 && visible == 0;
    }

    private static boolean hasFreshGuidanceEvidence(CarplayBus.Data d) {
        if (d == null) return false;
        if (d.has("route_state") && d.num("route_state", -1) > 1) return true;
        if (d.has("maneuver_count") && d.num("maneuver_count", 0) > 0) return true;
        if (d.has("maneuver_list")) {
            int[] list = d.intList("maneuver_list");
            if (list != null && list.length > 0) return true;
        }
        if (d.has("dist_maneuver_m") && d.num("dist_maneuver_m", -1) > 0) return true;
        if (d.has("dist_dest_m") && d.num("dist_dest_m", -1) > 0) return true;
        if (d.has("eta_seconds") || d.has("time_remaining_seconds")) return true;
        if (d.has("lane_guidance_showing") || d.has("lane_guidance_index")) return true;
        for (int i = 0; i < MAX_MANEUVERS; i++) {
            String p = "m" + i + "_";
            if (d.has(p + "type") || d.has(p + "ver") || d.has(p + "distance")
                    || d.has(p + "turn_angle") || d.has(p + "exit_angle")
                    || d.has(p + "name") || d.has(p + "after_road")) return true;
        }
        return false;
    }

    private static byte[] protectVisibilityOnly(byte[] payload, int len) {
        try {
            String text = new String(payload, 0, len, "UTF-8");
            StringBuffer out = new StringBuffer(text.length() + 16);
            int pos = 0;
            while (pos < text.length()) {
                int eol = text.indexOf('\n', pos);
                boolean nl = eol >= 0;
                if (!nl) eol = text.length();
                String line = text.substring(pos, eol);
                String n = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (n.startsWith("visible_in_app:")) out.append("visible_in_app:n:-1");
                else out.append(line);
                if (nl) out.append('\n');
                pos = eol + 1;
            }
            return out.toString().getBytes("UTF-8");
        } catch (Exception e) { return payload; }
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static int[] copy(int[] a) {
        if (a == null) return null;
        int[] b = new int[a.length];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }
    private static int[][] copyMatrix(int[][] a) {
        if (a == null) return null;
        int[][] b = new int[a.length][];
        for (int i = 0; i < a.length; i++) b[i] = copy(a[i]);
        return b;
    }

    private static final class RawState {
        int routeState, maneuverState, maneuverCount, visibleInApp, sourceSupportsRg;
        int[] maneuverList;
        int distDestM, distManeuverM, etaSeconds;
        long timeRemainingSeconds;
        String currentRoad, destination, sourceName;
        int laneGuidanceShowing, laneGuidanceTotal, laneGuidanceIndex, laneGuidanceSlot;
        boolean headAligned, distanceFresh;

        int[] mType = new int[MAX_MANEUVERS];
        int[] mVer = new int[MAX_MANEUVERS];
        int[] mTurnAngle = new int[MAX_MANEUVERS];
        int[] mExitAngle = new int[MAX_MANEUVERS];
        int[] mZLevel = new int[MAX_MANEUVERS];
        int[] mJunctionType = new int[MAX_MANEUVERS];
        int[] mDrivingSide = new int[MAX_MANEUVERS];
        int[] mDistance = new int[MAX_MANEUVERS];
        int[] mLaneCount = new int[MAX_MANEUVERS];
        int[] mLinkedLaneGuidanceIndex = new int[MAX_MANEUVERS];
        int[] mLinkedLaneGuidanceSlot = new int[MAX_MANEUVERS];
        String[] mName = new String[MAX_MANEUVERS];
        String[] mAfterRoad = new String[MAX_MANEUVERS];
        String[] mExitInfo = new String[MAX_MANEUVERS];
        int[][] mJunctionAngles = new int[MAX_MANEUVERS][];
        int[][] mLanePositions = new int[MAX_MANEUVERS][];
        int[][] mLaneDirections = new int[MAX_MANEUVERS][];
        int[][] mLaneStatus = new int[MAX_MANEUVERS][];
        int[][][] mLaneAngles = new int[MAX_MANEUVERS][][];

        int[] lgIndex = new int[MAX_MANEUVERS];
        int[] lgLaneCount = new int[MAX_MANEUVERS];
        int[][] lgLanePositions = new int[MAX_MANEUVERS][];
        int[][] lgLaneDirections = new int[MAX_MANEUVERS][];
        int[][] lgLaneStatus = new int[MAX_MANEUVERS][];
        int[][][] lgLaneAngles = new int[MAX_MANEUVERS][][];

        RawState() { reset(); }

        void reset() {
            routeState = -1; maneuverState = -1; maneuverCount = 0;
            visibleInApp = -1; sourceSupportsRg = -1; maneuverList = null;
            distDestM = -1; distManeuverM = -1; etaSeconds = -1;
            timeRemainingSeconds = -1L; currentRoad = null; destination = null; sourceName = null;
            laneGuidanceShowing = laneGuidanceTotal = laneGuidanceIndex = laneGuidanceSlot = -1;
            headAligned = false; distanceFresh = false;
            for (int i = 0; i < MAX_MANEUVERS; i++) {
                mType[i] = -1; mVer[i] = -1; mTurnAngle[i] = 1000; mExitAngle[i] = 1000;
                mZLevel[i] = -1; mJunctionType[i] = -1; mDrivingSide[i] = -1; mDistance[i] = -1;
                mLaneCount[i] = -1; mLinkedLaneGuidanceIndex[i] = -1; mLinkedLaneGuidanceSlot[i] = -1;
                mName[i] = null; mAfterRoad[i] = null; mExitInfo[i] = null;
                mJunctionAngles[i] = null; mLanePositions[i] = null; mLaneDirections[i] = null;
                mLaneStatus[i] = null; mLaneAngles[i] = null;
                lgIndex[i] = -1; lgLaneCount[i] = -1; lgLanePositions[i] = null;
                lgLaneDirections[i] = null; lgLaneStatus[i] = null; lgLaneAngles[i] = null;
            }
        }

        void observe(CarplayBus.Data d) {
            distanceFresh = false;
            if (d.has("route_state")) routeState = d.num("route_state", -1);
            if (d.has("maneuver_state")) maneuverState = d.num("maneuver_state", -1);
            if (d.has("maneuver_count")) maneuverCount = d.num("maneuver_count", 0);
            if (d.has("maneuver_list")) {
                String s = d.str("maneuver_list", null);
                maneuverList = s == null ? null : (s.length() == 0 ? new int[0] : copy(d.intList("maneuver_list")));
            }
            if (d.has("visible_in_app")) {
                int v = d.num("visible_in_app", -1);
                visibleInApp = (v == 0 || v == 1) ? v : -1;
            }
            if (d.has("source_supports_rg")) sourceSupportsRg = d.num("source_supports_rg", -1);
            if (d.has("source_name")) sourceName = d.str("source_name", null);
            if (d.has("dist_dest_m")) distDestM = d.num("dist_dest_m", -1);
            if (d.has("dist_maneuver_m")) { distManeuverM = d.num("dist_maneuver_m", -1); distanceFresh = true; }
            if (d.has("eta_seconds")) etaSeconds = d.num("eta_seconds", -1);
            if (d.has("time_remaining_seconds")) timeRemainingSeconds = d.num64("time_remaining_seconds", -1L);
            if (d.has("current_road")) currentRoad = d.str("current_road", null);
            if (d.has("destination")) destination = d.str("destination", null);
            if (d.has("lane_guidance_showing")) laneGuidanceShowing = d.num("lane_guidance_showing", -1);
            if (d.has("lane_guidance_total")) laneGuidanceTotal = d.num("lane_guidance_total", -1);
            if (d.has("lane_guidance_index")) laneGuidanceIndex = d.num("lane_guidance_index", -1);
            if (d.has("lane_guidance_slot")) laneGuidanceSlot = d.num("lane_guidance_slot", -1);

            for (int i = 0; i < MAX_MANEUVERS; i++) {
                String p = "m" + i + "_";
                if (d.has(p + "type")) mType[i] = d.num(p + "type", -1);
                if (d.has(p + "ver")) mVer[i] = d.num(p + "ver", -1);
                if (d.has(p + "turn_angle")) {
                    mTurnAngle[i] = d.num(p + "turn_angle", 1000);
                    mExitAngle[i] = mTurnAngle[i];
                }
                if (d.has(p + "exit_angle")) mExitAngle[i] = d.num(p + "exit_angle", 1000);
                if (d.has(p + "z_level")) mZLevel[i] = d.num(p + "z_level", -1);
                if (d.has(p + "zlevel")) mZLevel[i] = d.num(p + "zlevel", -1);
                if (d.has(p + "junction_type")) mJunctionType[i] = d.num(p + "junction_type", -1);
                if (d.has(p + "driving_side")) mDrivingSide[i] = d.num(p + "driving_side", -1);
                if (d.has(p + "distance")) {
                    mDistance[i] = d.num(p + "distance", -1);
                    if (i == head()) distanceFresh = true;
                }
                if (d.has(p + "name")) mName[i] = d.str(p + "name", null);
                if (d.has(p + "after_road")) mAfterRoad[i] = d.str(p + "after_road", null);
                if (d.has(p + "exit_info")) mExitInfo[i] = d.str(p + "exit_info", null);
                if (d.has(p + "junction_angles")) mJunctionAngles[i] = copy(d.intList(p + "junction_angles"));
                if (d.has(p + "lane_count")) mLaneCount[i] = d.num(p + "lane_count", -1);
                if (d.has(p + "linked_lane_guidance_index")) mLinkedLaneGuidanceIndex[i] = d.num(p + "linked_lane_guidance_index", -1);
                if (d.has(p + "linked_lane_guidance_slot")) mLinkedLaneGuidanceSlot[i] = d.num(p + "linked_lane_guidance_slot", -1);
                if (d.has(p + "lane_positions")) mLanePositions[i] = copy(d.intList(p + "lane_positions"));
                if (d.has(p + "lane_directions")) mLaneDirections[i] = copy(d.intList(p + "lane_directions"));
                if (d.has(p + "lane_status")) mLaneStatus[i] = copy(d.intList(p + "lane_status"));
                if (d.has(p + "lane_angles")) mLaneAngles[i] = parseMatrix(d.str(p + "lane_angles", null));

                String q = "lg" + i + "_";
                if (d.has(q + "index")) lgIndex[i] = d.num(q + "index", -1);
                if (d.has(q + "lane_count")) lgLaneCount[i] = d.num(q + "lane_count", -1);
                if (d.has(q + "lane_positions")) lgLanePositions[i] = copy(d.intList(q + "lane_positions"));
                if (d.has(q + "lane_directions")) lgLaneDirections[i] = copy(d.intList(q + "lane_directions"));
                if (d.has(q + "lane_status")) lgLaneStatus[i] = copy(d.intList(q + "lane_status"));
                if (d.has(q + "lane_angles")) lgLaneAngles[i] = parseMatrix(d.str(q + "lane_angles", null));
            }
            int h = head();
            headAligned = h >= 0 && h < MAX_MANEUVERS && mVer[h] >= 0
                && ManeuverMapper.isValidType(mType[h]);
        }

        int head() { return maneuverList == null || maneuverList.length == 0 ? -1 : maneuverList[0]; }
        int primaryDistance() {
            if (distManeuverM >= 0) return distManeuverM;
            int h = head();
            return value(mDistance, h, -1);
        }
        int value(int[] a, int i, int def) { return i >= 0 && i < a.length ? a[i] : def; }
    }

    private static final class Snapshot {
        int routeState, maneuverState, maneuverCount, visibleInApp, sourceSupportsRg;
        int[] maneuverList;
        int distDestM, distManeuverM, etaSeconds;
        long timeRemainingSeconds;
        String currentRoad, destination;
        int laneGuidanceShowing, laneGuidanceTotal, laneGuidanceIndex, laneGuidanceSlot;

        int[] mType = new int[MAX_MANEUVERS];
        int[] mVer = new int[MAX_MANEUVERS];
        int[] mTurnAngle = new int[MAX_MANEUVERS];
        int[] mExitAngle = new int[MAX_MANEUVERS];
        int[] mZLevel = new int[MAX_MANEUVERS];
        int[] mJunctionType = new int[MAX_MANEUVERS];
        int[] mDrivingSide = new int[MAX_MANEUVERS];
        int[] mDistance = new int[MAX_MANEUVERS];
        int[] mLaneCount = new int[MAX_MANEUVERS];
        int[] mLinkedLaneGuidanceIndex = new int[MAX_MANEUVERS];
        int[] mLinkedLaneGuidanceSlot = new int[MAX_MANEUVERS];
        String[] mName = new String[MAX_MANEUVERS];
        String[] mAfterRoad = new String[MAX_MANEUVERS];
        String[] mExitInfo = new String[MAX_MANEUVERS];
        int[][] mJunctionAngles = new int[MAX_MANEUVERS][];
        int[][] mLanePositions = new int[MAX_MANEUVERS][];
        int[][] mLaneDirections = new int[MAX_MANEUVERS][];
        int[][] mLaneStatus = new int[MAX_MANEUVERS][];
        int[][][] mLaneAngles = new int[MAX_MANEUVERS][][];

        int[] lgIndex = new int[MAX_MANEUVERS];
        int[] lgLaneCount = new int[MAX_MANEUVERS];
        int[][] lgLanePositions = new int[MAX_MANEUVERS][];
        int[][] lgLaneDirections = new int[MAX_MANEUVERS][];
        int[][] lgLaneStatus = new int[MAX_MANEUVERS][];
        int[][][] lgLaneAngles = new int[MAX_MANEUVERS][][];

        String visualKey = "";
        int rawType = -1;
        int physicalHead = -1, physicalVer = -1, physicalGeneration = -1;

        static Snapshot fromRaw(RawState r) {
            Snapshot s = new Snapshot();
            s.routeState = r.routeState; s.maneuverState = r.maneuverState;
            s.maneuverCount = r.maneuverCount; s.visibleInApp = r.visibleInApp;
            s.sourceSupportsRg = r.sourceSupportsRg;
            s.maneuverList = AmapV38Compat.copy(r.maneuverList);
            s.distDestM = r.distDestM; s.distManeuverM = r.distManeuverM;
            s.etaSeconds = r.etaSeconds; s.timeRemainingSeconds = r.timeRemainingSeconds;
            s.currentRoad = r.currentRoad; s.destination = r.destination;
            s.laneGuidanceShowing = r.laneGuidanceShowing;
            s.laneGuidanceTotal = r.laneGuidanceTotal;
            s.laneGuidanceIndex = r.laneGuidanceIndex;
            s.laneGuidanceSlot = r.laneGuidanceSlot;
            for (int i = 0; i < MAX_MANEUVERS; i++) {
                s.mType[i]=r.mType[i]; s.mVer[i]=r.mVer[i];
                s.mTurnAngle[i]=r.mTurnAngle[i]; s.mExitAngle[i]=r.mExitAngle[i];
                s.mZLevel[i]=r.mZLevel[i]; s.mJunctionType[i]=r.mJunctionType[i];
                s.mDrivingSide[i]=r.mDrivingSide[i]; s.mDistance[i]=r.mDistance[i];
                s.mLaneCount[i]=r.mLaneCount[i];
                s.mLinkedLaneGuidanceIndex[i]=r.mLinkedLaneGuidanceIndex[i];
                s.mLinkedLaneGuidanceSlot[i]=r.mLinkedLaneGuidanceSlot[i];
                s.mName[i]=r.mName[i]; s.mAfterRoad[i]=r.mAfterRoad[i];
                s.mExitInfo[i]=r.mExitInfo[i];
                s.mJunctionAngles[i]=AmapV38Compat.copy(r.mJunctionAngles[i]);
                s.mLanePositions[i]=AmapV38Compat.copy(r.mLanePositions[i]);
                s.mLaneDirections[i]=AmapV38Compat.copy(r.mLaneDirections[i]);
                s.mLaneStatus[i]=AmapV38Compat.copy(r.mLaneStatus[i]);
                s.mLaneAngles[i]=AmapV38Compat.copyMatrix(r.mLaneAngles[i]);
                s.lgIndex[i]=r.lgIndex[i]; s.lgLaneCount[i]=r.lgLaneCount[i];
                s.lgLanePositions[i]=AmapV38Compat.copy(r.lgLanePositions[i]);
                s.lgLaneDirections[i]=AmapV38Compat.copy(r.lgLaneDirections[i]);
                s.lgLaneStatus[i]=AmapV38Compat.copy(r.lgLaneStatus[i]);
                s.lgLaneAngles[i]=AmapV38Compat.copyMatrix(r.lgLaneAngles[i]);
            }
            return s;
        }

        Snapshot copy() {
            RawState r = new RawState();
            r.routeState=routeState; r.maneuverState=maneuverState;
            r.maneuverCount=maneuverCount; r.visibleInApp=visibleInApp;
            r.sourceSupportsRg=sourceSupportsRg;
            r.maneuverList=AmapV38Compat.copy(maneuverList);
            r.distDestM=distDestM; r.distManeuverM=distManeuverM;
            r.etaSeconds=etaSeconds; r.timeRemainingSeconds=timeRemainingSeconds;
            r.currentRoad=currentRoad; r.destination=destination;
            r.laneGuidanceShowing=laneGuidanceShowing; r.laneGuidanceTotal=laneGuidanceTotal;
            r.laneGuidanceIndex=laneGuidanceIndex; r.laneGuidanceSlot=laneGuidanceSlot;
            for (int i=0;i<MAX_MANEUVERS;i++) {
                r.mType[i]=mType[i]; r.mVer[i]=mVer[i];
                r.mTurnAngle[i]=mTurnAngle[i]; r.mExitAngle[i]=mExitAngle[i];
                r.mZLevel[i]=mZLevel[i]; r.mJunctionType[i]=mJunctionType[i];
                r.mDrivingSide[i]=mDrivingSide[i]; r.mDistance[i]=mDistance[i];
                r.mLaneCount[i]=mLaneCount[i];
                r.mLinkedLaneGuidanceIndex[i]=mLinkedLaneGuidanceIndex[i];
                r.mLinkedLaneGuidanceSlot[i]=mLinkedLaneGuidanceSlot[i];
                r.mName[i]=mName[i]; r.mAfterRoad[i]=mAfterRoad[i];
                r.mExitInfo[i]=mExitInfo[i];
                r.mJunctionAngles[i]=AmapV38Compat.copy(mJunctionAngles[i]);
                r.mLanePositions[i]=AmapV38Compat.copy(mLanePositions[i]);
                r.mLaneDirections[i]=AmapV38Compat.copy(mLaneDirections[i]);
                r.mLaneStatus[i]=AmapV38Compat.copy(mLaneStatus[i]);
                r.mLaneAngles[i]=AmapV38Compat.copyMatrix(mLaneAngles[i]);
                r.lgIndex[i]=lgIndex[i]; r.lgLaneCount[i]=lgLaneCount[i];
                r.lgLanePositions[i]=AmapV38Compat.copy(lgLanePositions[i]);
                r.lgLaneDirections[i]=AmapV38Compat.copy(lgLaneDirections[i]);
                r.lgLaneStatus[i]=AmapV38Compat.copy(lgLaneStatus[i]);
                r.lgLaneAngles[i]=AmapV38Compat.copyMatrix(lgLaneAngles[i]);
            }
            Snapshot s=fromRaw(r);
            s.visualKey=visualKey; s.rawType=rawType;
            s.physicalHead=physicalHead; s.physicalVer=physicalVer;
            s.physicalGeneration=physicalGeneration;
            return s;
        }

        int head() { return maneuverList == null || maneuverList.length == 0 ? -1 : maneuverList[0]; }

        byte[] toPayload() {
            try {
                StringBuffer b = new StringBuffer(4096);
                b.append("@routeguidance\n");
                appendNum(b,"route_state",routeState);
                appendNum(b,"maneuver_state",maneuverState);
                appendNum(b,"maneuver_count",maneuverCount);
                appendListAlways(b,"maneuver_list",maneuverList);
                appendNum(b,"visible_in_app",visibleInApp);
                appendNum(b,"source_supports_rg",sourceSupportsRg);
                appendNum(b,"dist_dest_m",distDestM);
                appendNum(b,"dist_maneuver_m",distManeuverM);
                appendNum(b,"eta_seconds",etaSeconds);
                if (timeRemainingSeconds>=0)
                    appendLong(b,"time_remaining_seconds",timeRemainingSeconds);
                appendStr(b,"current_road",currentRoad);
                appendStr(b,"destination",destination);
                appendNum(b,"lane_guidance_showing",laneGuidanceShowing);
                appendNum(b,"lane_guidance_total",laneGuidanceTotal);
                appendNum(b,"lane_guidance_index",laneGuidanceIndex);
                appendNum(b,"lane_guidance_slot",laneGuidanceSlot);

                for (int i=0;i<MAX_MANEUVERS;i++) {
                    if (!slotUsed(i)) continue;
                    String p="m"+i+"_";
                    appendNum(b,p+"type",mType[i]);
                    appendNum(b,p+"ver",mVer[i]);
                    appendNum(b,p+"turn_angle",mTurnAngle[i]);
                    appendNum(b,p+"exit_angle",mExitAngle[i]);
                    appendNum(b,p+"z_level",mZLevel[i]);
                    appendNum(b,p+"junction_type",mJunctionType[i]);
                    appendNum(b,p+"driving_side",mDrivingSide[i]);
                    appendNum(b,p+"distance",mDistance[i]);
                    appendStr(b,p+"name",mName[i]);
                    appendStr(b,p+"after_road",mAfterRoad[i]);
                    appendStr(b,p+"exit_info",mExitInfo[i]);
                    appendList(b,p+"junction_angles",mJunctionAngles[i]);
                    appendNum(b,p+"lane_count",mLaneCount[i]);
                    appendNum(b,p+"linked_lane_guidance_index",mLinkedLaneGuidanceIndex[i]);
                    appendNum(b,p+"linked_lane_guidance_slot",mLinkedLaneGuidanceSlot[i]);
                    appendList(b,p+"lane_positions",mLanePositions[i]);
                    appendList(b,p+"lane_directions",mLaneDirections[i]);
                    appendList(b,p+"lane_status",mLaneStatus[i]);
                    appendMatrix(b,p+"lane_angles",mLaneAngles[i]);
                }
                for (int i=0;i<MAX_MANEUVERS;i++) {
                    if (lgIndex[i]<0 && lgLaneCount[i]<0 && lgLanePositions[i]==null
                            && lgLaneDirections[i]==null && lgLaneStatus[i]==null
                            && lgLaneAngles[i]==null) continue;
                    String p="lg"+i+"_";
                    appendNum(b,p+"index",lgIndex[i]);
                    appendNum(b,p+"lane_count",lgLaneCount[i]);
                    appendList(b,p+"lane_positions",lgLanePositions[i]);
                    appendList(b,p+"lane_directions",lgLaneDirections[i]);
                    appendList(b,p+"lane_status",lgLaneStatus[i]);
                    appendMatrix(b,p+"lane_angles",lgLaneAngles[i]);
                }
                return b.toString().getBytes("UTF-8");
            } catch (Exception e) {
                Log.e(TAG,"snapshot serialization failed",e);
                return new byte[0];
            }
        }

        private boolean slotUsed(int i) {
            if (mVer[i]>=0 || mType[i]>=0) return true;
            if (maneuverList!=null)
                for(int j=0;j<maneuverList.length;j++) if(maneuverList[j]==i) return true;
            return false;
        }
    }

    private static void appendNum(StringBuffer b,String key,int v) {
        if(v>=0 || "visible_in_app".equals(key))
            b.append(key).append(":n:").append(v).append('\n');
    }
    private static void appendLong(StringBuffer b,String key,long v) {
        b.append(key).append(":n:").append(v).append('\n');
    }
    private static void appendStr(StringBuffer b,String key,String v) {
        if(v!=null) b.append(key).append(":s:").append(clean(v)).append('\n');
    }
    private static void appendListAlways(StringBuffer b,String key,int[] a) {
        b.append(key).append(":s:"); appendCsv(b,a); b.append('\n');
    }
    private static void appendList(StringBuffer b,String key,int[] a) {
        if(a==null)return; b.append(key).append(":s:"); appendCsv(b,a); b.append('\n');
    }
    private static void appendCsv(StringBuffer b,int[] a) {
        if(a==null)return;
        for(int i=0;i<a.length;i++){if(i>0)b.append(',');b.append(a[i]);}
    }
    private static void appendMatrix(StringBuffer b,String key,int[][] m) {
        if(m==null)return; b.append(key).append(":s:");
        for(int i=0;i<m.length;i++){if(i>0)b.append('|');appendCsv(b,m[i]);}
        b.append('\n');
    }
    private static String clean(String s) {
        return s == null ? "" : s.replace('\n',' ').replace('\r',' ');
    }

    private static int[][] parseMatrix(String raw) {
        if (raw == null) return null;
        int lanes=1;
        for(int i=0;i<raw.length();i++) if(raw.charAt(i)=='|') lanes++;
        int[][] out=new int[lanes][]; int start=0,lane=0;
        for(int i=0;i<=raw.length() && lane<lanes;i++) {
            if(i==raw.length() || raw.charAt(i)=='|') {
                out[lane++]=parseCsv(raw.substring(start,i)); start=i+1;
            }
        }
        return out;
    }
    private static int[] parseCsv(String s) {
        if(s==null)return null; s=s.trim(); if(s.length()==0)return new int[0];
        int commas=0;
        for(int i=0;i<s.length();i++) if(s.charAt(i)==',') commas++;
        int[] out=new int[commas+1]; int start=0,n=0;
        for(int i=0;i<=s.length() && n<out.length;i++) {
            if(i==s.length() || s.charAt(i)==',') {
                try { out[n++]=Integer.parseInt(s.substring(start,i).trim()); }
                catch(Exception e){return null;}
                start=i+1;
            }
        }
        return out;
    }
}
