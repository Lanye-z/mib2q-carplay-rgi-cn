package com.luka.carplay.routeguidance;

/** v38-style monotonic distance pairing for the committed physical node. */
final class AmapProgressTracker {
    static final class Result {
        final int distanceM;
        final int denominatorM;
        final boolean correctedInitialPair;
        final boolean suppressedIncrease;
        Result(int d, int den, boolean corrected, boolean suppressed) {
            distanceM = d;
            denominatorM = den;
            correctedInitialPair = corrected;
            suppressedIncrease = suppressed;
        }
    }

    private int distanceM = -1;
    private int denominatorM = -1;
    private int idx = -1;
    private int ver = -1;
    private int generation = -1;
    private int rawHead = -1;
    private boolean provisional;
    /* The first usable route-level distance after a physical-node commit can
     * still belong to the previous Amap head.  Keep exactly one pairing
     * window open so a node-distance -> stale-short-distance -> corrected
     * node-distance sequence can be repaired without allowing arbitrary later
     * distance increases. */
    private boolean awaitingFirstUsableSample;

    void reset() {
        distanceM = -1;
        denominatorM = -1;
        idx = -1;
        ver = -1;
        generation = -1;
        rawHead = -1;
        provisional = false;
        awaitingFirstUsableSample = false;
    }

    Result commit(int distance, int denominator, int i, int v,
                  int gen, int head, boolean correctionAllowed) {
        distanceM = distance;
        denominatorM = denominator > 0 ? denominator : distance;
        idx = i;
        ver = v;
        generation = gen;
        rawHead = head;
        provisional = correctionAllowed && distance > 0;
        awaitingFirstUsableSample = true;
        return result(false, false);
    }

    Result update(int distance, int nodeDistance, int denominator,
                  int i, int v, int gen, int head, boolean allowCorrection) {
        if (distance <= 0) return result(false, false);

        boolean same = samePhysical(i, v, gen, head);
        if (!same) {
            /* Real Amap logs show a common rollover ordering where the new
             * physical head/detail arrives first, while dist_maneuver_m still
             * carries the previous head's shorter distance for one snapshot
             * (e.g. new nodeDistance=100 with transient distance=75, followed
             * by the corrected 100).  v38 treats that first pair as
             * provisional instead of freezing the stale 75 forever. */
            boolean inferredInitialPair = nodeDistance > 0
                && distance > 0 && distance < nodeDistance;
            return commit(distance, denominator > 0 ? denominator : nodeDistance,
                i, v, gen, head, allowCorrection || inferredInitialPair);
        }

        if (distanceM <= 0) {
            distanceM = distance;
            if (denominatorM <= 0)
                denominatorM = denominator > 0 ? denominator : nodeDistance;
            if (awaitingFirstUsableSample && nodeDistance > 0
                    && distance < nodeDistance) {
                provisional = true;
            }
            awaitingFirstUsableSample = false;
            return result(false, false);
        }

        /* Keep an inferred/explicit provisional pair alive until the next
         * usable sample resolves it.  Do not clear it merely because callers
         * use allowCorrection=false for ordinary monotonic updates. */
        if (provisional && nodeDistance > 0
                && distance > distanceM && distance <= nodeDistance) {
            distanceM = distance;
            if (denominator > 0) denominatorM = denominator;
            provisional = false;
            awaitingFirstUsableSample = false;
            return result(true, false);
        }

        if (distance < distanceM) {
            /* Startup logs can also arrive as: node detail/denominator first,
             * then one stale shorter global distance, then the corrected
             * global distance.  Example: commit 104 from m0_distance, receive
             * dist_maneuver=27, then receive the real 104.  Only the first
             * usable sample after commit may open this correction path. */
            boolean inferredInitialPair = awaitingFirstUsableSample
                && nodeDistance > 0
                && distanceM == nodeDistance
                && distance < nodeDistance;
            distanceM = distance;
            if (denominator > 0) denominatorM = denominator;
            provisional = inferredInitialPair;
            awaitingFirstUsableSample = false;
            return result(false, false);
        }
        if (distance == distanceM) {
            idx = i;
            ver = v;
            generation = gen;
            rawHead = head;
            if (denominator > 0) denominatorM = denominator;
            awaitingFirstUsableSample = false;
            return result(false, false);
        }

        /* A larger value that cannot be explained as the initial head/detail
         * pairing correction remains suppressed.  Resolve provisional state
         * here so a later arbitrary increase cannot be accepted. */
        provisional = false;
        awaitingFirstUsableSample = false;
        return result(false, true);
    }

    int distance() { return distanceM; }
    int denominator() { return denominatorM; }

    private boolean samePhysical(int i, int v, int gen, int head) {
        if (generation >= 0 && rawHead >= 0 && gen >= 0 && head >= 0)
            return generation == gen && rawHead == head;
        return idx == i && ver >= 0 && ver == v;
    }

    private Result result(boolean corrected, boolean suppressed) {
        return new Result(distanceM, denominatorM, corrected, suppressed);
    }
}
