package com.luka.carplay.routeguidance;

import com.luka.carplay.framework.CarplayBus;
import com.luka.carplay.framework.Log;

/**
 * v38-derived Amap input stabilizer adapted to the current/main BAPBridge.
 *
 * The supplied v38 build has a richer Amap display state machine
 * (formal-lock, visual stabilization, physical rollover and progress pairing).
 * The current repository has newer renderer/BAP/native-hook fixes that we do
 * not want to replace.  This class therefore applies the same important v38
 * semantics at the RouteGuidance input boundary and then lets the current
 * BAPBridge/render path do the actual output.
 *
 * Physical identity is represented by current-hook slot + mVer.  The v38
 * binary additionally carried native amap_route_generation/raw-head metadata;
 * current main does not expose those fields, while mVer already changes on
 * slot reassignment and is the safest JAR-only identity available here.
 */
final class AmapV38Compat {
    private static final String TAG = "AmapV38Compat";
    private static final int MAX_MANEUVERS = 32;
    private static final int DISTANCE_JITTER_M = 8;

    private int[] mType = new int[MAX_MANEUVERS];
    private int[] mVer = new int[MAX_MANEUVERS];
    private int[] mTurnAngle = new int[MAX_MANEUVERS];
    private int[] mJunctionType = new int[MAX_MANEUVERS];
    private int[] mDrivingSide = new int[MAX_MANEUVERS];
    private int[] mZLevel = new int[MAX_MANEUVERS];
    private int[] mDistance = new int[MAX_MANEUVERS];
    private int[] mLaneCount = new int[MAX_MANEUVERS];
    private String[] mName = new String[MAX_MANEUVERS];
    private String[] mAfterRoad = new String[MAX_MANEUVERS];

    private int[] rawList = null;
    private int rawDistM = -1;
    private String destination = null;

    private boolean enabled = false;

    /* Committed physical maneuver (v38 FormalLock/DisplayFrame equivalent). */
    private int committedHead = -1;
    private int committedVer = -1;
    private int committedType = -1;
    private String committedVisualKey = "";
    private String committedRoadKey = "";
    private int committedDistanceM = -1;
    private boolean committedSawCompletion = false;

    /* Candidate visual/physical maneuver (v38 DisplayStabilizer equivalent). */
    private int candidateHead = -1;
    private int candidateVer = -1;
    private String candidateVisualKey = "";
    private int candidateFrames = 0;
    private int candidateLastDistanceM = -1;
    private boolean candidatePhysicalRollover = false;

    AmapV38Compat() {
        reset();
    }

    void reset() {
        enabled = false;
        rawList = null;
        rawDistM = -1;
        destination = null;
        committedHead = -1;
        committedVer = -1;
        committedType = -1;
        committedVisualKey = "";
        committedRoadKey = "";
        committedDistanceM = -1;
        committedSawCompletion = false;
        clearCandidate();

        for (int i = 0; i < MAX_MANEUVERS; i++) {
            mType[i] = -1;
            mVer[i] = -1;
            mTurnAngle[i] = 1000;
            mJunctionType[i] = -1;
            mDrivingSide[i] = -1;
            mZLevel[i] = -1;
            mDistance[i] = -1;
            mLaneCount[i] = -1;
            mName[i] = null;
            mAfterRoad[i] = null;
        }
    }

    /** Cache raw data even before V38 mode is confirmed. */
    void observe(CarplayBus.Data d) {
        if (d == null) return;

        if (d.has("maneuver_list")) {
            int[] list = d.intList("maneuver_list");
            rawList = copy(list);
        }
        if (d.has("dist_maneuver_m")) {
            rawDistM = d.num("dist_maneuver_m", -1);
        }
        if (d.has("destination")) {
            destination = d.str("destination", null);
        }

        for (int i = 0; i < MAX_MANEUVERS; i++) {
            String p = "m" + i + "_";
            if (d.has(p + "type")) mType[i] = d.num(p + "type", -1);
            if (d.has(p + "ver")) mVer[i] = d.num(p + "ver", -1);
            if (d.has(p + "turn_angle")) mTurnAngle[i] = d.num(p + "turn_angle", 1000);
            if (d.has(p + "exit_angle")) mTurnAngle[i] = d.num(p + "exit_angle", 1000);
            if (d.has(p + "junction_type")) mJunctionType[i] = d.num(p + "junction_type", -1);
            if (d.has(p + "driving_side")) mDrivingSide[i] = d.num(p + "driving_side", -1);
            if (d.has(p + "z_level")) mZLevel[i] = d.num(p + "z_level", -1);
            if (d.has(p + "zlevel")) mZLevel[i] = d.num(p + "zlevel", -1);
            if (d.has(p + "distance")) mDistance[i] = d.num(p + "distance", -1);
            if (d.has(p + "lane_count")) mLaneCount[i] = d.num(p + "lane_count", -1);
            if (d.has(p + "name")) mName[i] = d.str(p + "name", null);
            if (d.has(p + "after_road")) mAfterRoad[i] = d.str(p + "after_road", null);
        }
    }

    void enable() {
        enabled = true;
        seedCommittedFromRaw();
        Log.i(TAG, "V38 display compatibility enabled: head=" + committedHead
            + " ver=" + committedVer + " visual=" + committedVisualKey
            + " dist=" + committedDistanceM);
    }

    void disable() {
        enabled = false;
        clearCandidate();
    }

    boolean isEnabled() {
        return enabled;
    }

    /**
     * Apply v38 lifecycle/display semantics and return the frame that should be
     * presented to the existing RouteGuidance/main BAPBridge.
     */
    byte[] process(byte[] payload, int len, CarplayBus.Data d,
                   int routeState, int maneuverCount, int visibleInApp,
                   int sourceSupportsRg) {
        if (!enabled || payload == null || len <= 0) return payload;
        if (routeState <= 0 || sourceSupportsRg == 0) return payload;

        boolean normalizeVisibleZero = (visibleInApp == 0);
        boolean suppressTransientCountClear = maneuverCount == 0;
        boolean suppressTransientListClear = isRawListEmpty();
        boolean holdHead = false;
        boolean holdDistance = false;
        int holdVisualSlot = -1;

        int head = rawHead();
        int ver = value(mVer, head, -1);
        int type = value(mType, head, -1);
        int dist = rawDistM;
        int nodeDist = value(mDistance, head, -1);
        if (dist <= 0 && nodeDist > 0) dist = nodeDist;
        String visual = visualKey(head, type);
        String road = roadKey(head);
        boolean validHead = isValidHead(head, ver, type);

        if (committedHead < 0 && validHead) {
            commit(head, ver, type, visual, road, dist, false);
        } else if (validHead && sameIdentity(head, ver, committedHead, committedVer)) {
            /* Same physical node, but Amap may briefly downgrade the formal
             * arrow to a prompt/neutral visual. v38 FormalLock keeps the
             * committed formal maneuver in this case. */
            if (isFormalType(committedType)
                    && (isPromptType(type) || isNeutralStraightNode(head, type))
                    && !visual.equals(committedVisualKey)) {
                holdVisualSlot = committedHead;
                Log.d(TAG, "[V38-FORMAL-LOCK] hold late prompt head=" + head
                    + " type=" + type + " committedType=" + committedType);
            } else if (!visual.equals(committedVisualKey)) {
                int decision = observeCandidate(head, ver, visual, dist, false);
                if (decision == 0) {
                    holdVisualSlot = committedHead;
                } else {
                    committedType = type;
                    committedVisualKey = visual;
                    committedRoadKey = road;
                }
            } else {
                clearCandidate();
            }

            if (rawDistM <= 0) {
                committedSawCompletion = true;
                /* v38 keeps the committed frame through the zero-distance
                 * boundary until the next physical maneuver is stable. */
                holdDistance = true;
            } else if (committedDistanceM > 0
                    && rawDistM > committedDistanceM + DISTANCE_JITTER_M) {
                holdDistance = true;
                Log.d(TAG, "[V38-PROGRESS] suppress distance increase old="
                    + committedDistanceM + " raw=" + rawDistM
                    + " head=" + committedHead + " ver=" + committedVer);
            } else if (rawDistM > 0) {
                committedDistanceM = rawDistM;
            }
        } else if (validHead) {
            /* Different slot/version = different physical maneuver. Keep the
             * previous committed frame until the new identity is observed in
             * two stable frames with positive distance, matching the v38
             * DisplayStabilizer/rollover intent. */
            boolean physicalRollover = committedSawCompletion
                || !visual.equals(committedVisualKey)
                || !road.equals(committedRoadKey);
            int decision = observeCandidate(head, ver, visual, dist, physicalRollover);
            if (decision == 0) {
                holdHead = true;
                holdDistance = true;
            } else {
                commit(head, ver, type, visual, road, dist, physicalRollover);
                Log.i(TAG, "[V38-ROLLOVER] commit new head=" + head
                    + " ver=" + ver + " visual=" + visual + " dist=" + dist);
            }
        } else if (committedHead >= 0) {
            /* Incomplete detail frame: v38 holds the committed display frame
             * instead of flashing NO_SYMBOL. */
            holdHead = true;
            holdDistance = true;
        }

        return filterPayload(payload, len,
            normalizeVisibleZero,
            suppressTransientCountClear || holdHead,
            suppressTransientListClear || holdHead,
            holdDistance,
            holdVisualSlot);
    }

    private void seedCommittedFromRaw() {
        int head = rawHead();
        int ver = value(mVer, head, -1);
        int type = value(mType, head, -1);
        if (!isValidHead(head, ver, type)) return;
        int dist = rawDistM;
        if (dist <= 0) dist = value(mDistance, head, -1);
        commit(head, ver, type, visualKey(head, type), roadKey(head), dist, false);
    }

    private void commit(int head, int ver, int type, String visual,
                        String road, int dist, boolean rollover) {
        committedHead = head;
        committedVer = ver;
        committedType = type;
        committedVisualKey = visual == null ? "" : visual;
        committedRoadKey = road == null ? "" : road;
        committedDistanceM = dist;
        committedSawCompletion = false;
        clearCandidate();
        if (rollover) {
            Log.d(TAG, "[V38-FORMAL-LOCK] lock physical head=" + head
                + " ver=" + ver + " visual=" + committedVisualKey);
        }
    }

    /**
     * Returns 1 when the candidate is stable enough to commit, 0 while output
     * should remain on the previous committed frame.
     */
    private int observeCandidate(int head, int ver, String visual, int dist,
                                 boolean physicalRollover) {
        if (visual == null || visual.length() == 0 || dist <= 0) {
            return 0;
        }

        if (head != candidateHead || ver != candidateVer
                || !visual.equals(candidateVisualKey)) {
            candidateHead = head;
            candidateVer = ver;
            candidateVisualKey = visual;
            candidateFrames = 1;
            candidateLastDistanceM = dist;
            candidatePhysicalRollover = physicalRollover;
            Log.d(TAG, "[V38-STABILIZER] candidate head=" + head
                + " ver=" + ver + " visual=" + visual + " dist=" + dist);
            return 0;
        }

        if (candidateLastDistanceM > 0
                && dist > candidateLastDistanceM + DISTANCE_JITTER_M) {
            candidateFrames = 1;
            candidateLastDistanceM = dist;
            return 0;
        }

        candidateFrames++;
        candidateLastDistanceM = dist;
        if (candidateFrames < 2) return 0;

        if (candidatePhysicalRollover) {
            Log.d(TAG, "[V38-STABILIZER] physical rollover stable after "
                + candidateFrames + " frames");
        }
        return 1;
    }

    private void clearCandidate() {
        candidateHead = -1;
        candidateVer = -1;
        candidateVisualKey = "";
        candidateFrames = 0;
        candidateLastDistanceM = -1;
        candidatePhysicalRollover = false;
    }

    private boolean sameIdentity(int h1, int v1, int h2, int v2) {
        return h1 >= 0 && h1 == h2 && v1 >= 0 && v1 == v2;
    }

    private int rawHead() {
        if (rawList == null || rawList.length == 0) return -1;
        return rawList[0];
    }

    private boolean isRawListEmpty() {
        return rawList != null && rawList.length == 0;
    }

    private boolean isValidHead(int head, int ver, int type) {
        return head >= 0 && head < MAX_MANEUVERS && ver >= 0
            && ManeuverMapper.isValidType(type);
    }

    private String visualKey(int head, int type) {
        if (head < 0 || head >= MAX_MANEUVERS) return "";
        int angle = value(mTurnAngle, head, 1000);
        int junction = value(mJunctionType, head, -1);
        int side = value(mDrivingSide, head, -1);
        int z = value(mZLevel, head, 0);

        int[] mapped = ManeuverMapper.map(type, angle, junction, side);
        if (mapped != null && mapped.length >= 2
                && (mapped[0] == ManeuverMapper.FOLLOW_STREET
                    || mapped[0] == ManeuverMapper.NO_INFO
                    || mapped[0] == ManeuverMapper.NO_SYMBOL)) {
            return "FOLLOW_STREET";
        }

        StringBuffer b = new StringBuffer();
        b.append("m=").append(mapped != null && mapped.length > 0 ? mapped[0] : type);
        b.append("|d=").append(mapped != null && mapped.length > 1 ? mapped[1] : angle);
        b.append("|z=").append(z);
        return b.toString();
    }

    private String roadKey(int head) {
        if (head < 0 || head >= MAX_MANEUVERS) return "|";
        String a = mName[head] == null ? "" : mName[head];
        String b = mAfterRoad[head] == null ? "" : mAfterRoad[head];
        return a + "|" + b;
    }

    private boolean isNeutralStraightNode(int head, int type) {
        if (type != 3 || head < 0 || head >= MAX_MANEUVERS) return false;
        return value(mTurnAngle, head, 1000) == 1000
            && value(mJunctionType, head, -1) == 0
            && value(mLaneCount, head, -1) <= 0;
    }

    /* Matches the formal/prompt split observed in the supplied v38 bytecode. */
    private static boolean isFormalType(int type) {
        if (type == 1 || type == 2 || type == 4 || type == 6 || type == 7
                || type == 18 || type == 19 || type == 20 || type == 21
                || type == 26 || type == 47 || type == 48) return true;
        return type >= 28 && type <= 46;
    }

    private static boolean isPromptType(int type) {
        return type == 49 || type == 50 || type == 13 || type == 14
            || type == 51 || type == 52 || type == 53;
    }

    private boolean isPlaceholderDestination() {
        return AmapCompatibility.isPlaceholderDestination(destination)
            || "未知位置".equals(destination);
    }

    /**
     * Frame filter.  Only fields that would prematurely change the active
     * display are held; all other route data still flows to current main.
     */
    private byte[] filterPayload(byte[] payload, int len,
                                 boolean normalizeVisibleZero,
                                 boolean holdCount,
                                 boolean holdList,
                                 boolean holdDistance,
                                 int holdVisualSlot) {
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
                boolean replace = false;

                if (normalizeVisibleZero && normalized.startsWith("visible_in_app:")) {
                    replace = true;
                }
                if (holdCount && normalized.startsWith("maneuver_count:")) drop = true;
                if (holdList && normalized.startsWith("maneuver_list:")) drop = true;
                if (holdDistance && normalized.startsWith("dist_maneuver_m:")) drop = true;

                if (!drop && holdVisualSlot >= 0
                        && isVisualFieldForSlot(normalized, holdVisualSlot)) {
                    drop = true;
                }

                if (!drop) {
                    if (replace) {
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
            Log.e(TAG, "Failed to filter V38 Amap frame", e);
            return payload;
        }
    }

    private boolean isVisualFieldForSlot(String line, int slot) {
        String p = "m" + slot + "_";
        if (!line.startsWith(p)) return false;
        String key = line.substring(p.length());
        int colon = key.indexOf(':');
        if (colon >= 0) key = key.substring(0, colon);
        return "type".equals(key)
            || "turn_angle".equals(key)
            || "exit_angle".equals(key)
            || "junction_type".equals(key)
            || "driving_side".equals(key)
            || "z_level".equals(key)
            || "zlevel".equals(key)
            || "junction_angles".equals(key)
            || "name".equals(key)
            || "after_road".equals(key);
    }

    private static int value(int[] a, int idx, int def) {
        if (a == null || idx < 0 || idx >= a.length) return def;
        return a[idx];
    }

    private static int[] copy(int[] src) {
        if (src == null) return null;
        int[] out = new int[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[i];
        return out;
    }
}
