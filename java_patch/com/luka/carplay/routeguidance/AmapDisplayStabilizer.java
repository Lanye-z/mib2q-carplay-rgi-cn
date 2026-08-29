package com.luka.carplay.routeguidance;

/**
 * v38-style display stabilizer. Physical identity and visible identity are
 * deliberately separate: a new slot/version with the same visual can be
 * accepted internally without forcing a renderer recommit.
 */
final class AmapDisplayStabilizer {
    static final int PASS = 0;
    static final int HOLD = 1;
    static final int ACCEPT_SAME_VISUAL = 2;
    static final int COMMIT_NEW_VISUAL = 3;
    static final int COMMIT_NEW_PHYSICAL = 4;

    private static final int DISTANCE_JITTER_M = 8;

    private boolean committed;
    private String visual = "";
    private int idx = -1;
    private int ver = -1;
    private int distance = -1;
    private int generation = -1;
    private int rawHead = -1;
    private boolean sawCompletion;

    private String candidateVisual = "";
    private int candidateIdx = -1;
    private int candidateVer = -1;
    private int candidateDistance = -1;
    private int candidateGeneration = -1;
    private int candidateRawHead = -1;
    private int candidateFrames;

    void reset() {
        committed = false;
        visual = "";
        idx = -1;
        ver = -1;
        distance = -1;
        generation = -1;
        rawHead = -1;
        sawCompletion = false;
        clearCandidate();
    }

    void seed(String v, int i, int version, int dist, int gen, int head) {
        if (v == null || v.length() == 0) return;
        committed = true;
        visual = v;
        idx = i;
        ver = version;
        distance = dist;
        generation = gen;
        rawHead = head;
        sawCompletion = dist <= 0;
        clearCandidate();
    }

    int evaluate(boolean active, String nextVisual, int nextIdx, int nextVer,
                 int dist, boolean headAligned, boolean distanceFresh,
                 int nextGeneration, int nextRawHead) {
        if (!active) {
            reset();
            return PASS;
        }
        if (!headAligned || !distanceFresh) return HOLD;
        if (nextVisual == null || nextVisual.length() == 0) return HOLD;

        if (!committed) {
            seed(nextVisual, nextIdx, nextVer, dist, nextGeneration, nextRawHead);
            return PASS;
        }

        boolean sameVisual = nextVisual.equals(visual);
        boolean samePhysical = samePhysical(nextIdx, nextVer, nextGeneration, nextRawHead);
        boolean laterPhysical = !samePhysical;

        if (sameVisual && samePhysical) {
            clearCandidate();
            if (dist <= 0) {
                sawCompletion = true;
                return HOLD;
            }
            if (distance > 0 && dist > distance + DISTANCE_JITTER_M) return HOLD;
            distance = dist;
            return ACCEPT_SAME_VISUAL;
        }

        /* v38 important case: slot/version may advance while the display
         * visual is unchanged. Accept physical identity internally but do
         * not make main repaint the same arrow unless this is a completed
         * physical rollover boundary. */
        if (sameVisual && laterPhysical && !sawCompletion) {
            idx = nextIdx;
            ver = nextVer;
            generation = nextGeneration;
            rawHead = nextRawHead;
            if (dist > 0) distance = dist;
            clearCandidate();
            return ACCEPT_SAME_VISUAL;
        }

        boolean physicalCommit = sameVisual && laterPhysical && sawCompletion;
        if (!sameCandidate(nextVisual, nextIdx, nextVer, nextGeneration, nextRawHead)) {
            candidateVisual = nextVisual;
            candidateIdx = nextIdx;
            candidateVer = nextVer;
            candidateDistance = dist;
            candidateGeneration = nextGeneration;
            candidateRawHead = nextRawHead;
            candidateFrames = 1;
            return HOLD;
        }

        if (candidateDistance > 0 && dist > candidateDistance + DISTANCE_JITTER_M) {
            candidateDistance = dist;
            candidateFrames = 1;
            return HOLD;
        }

        candidateDistance = dist;
        candidateFrames++;
        if (candidateFrames < 2) return HOLD;

        visual = nextVisual;
        idx = nextIdx;
        ver = nextVer;
        generation = nextGeneration;
        rawHead = nextRawHead;
        distance = dist;
        sawCompletion = false;
        clearCandidate();
        return physicalCommit ? COMMIT_NEW_PHYSICAL : COMMIT_NEW_VISUAL;
    }

    void noteCompletion() { if (committed) sawCompletion = true; }

    private boolean samePhysical(int i, int v, int gen, int head) {
        if (generation >= 0 && rawHead >= 0 && gen >= 0 && head >= 0)
            return generation == gen && rawHead == head;
        return idx == i && ver >= 0 && ver == v;
    }

    private boolean sameCandidate(String v, int i, int version, int gen, int head) {
        if (candidateFrames <= 0 || !v.equals(candidateVisual)) return false;
        if (candidateGeneration >= 0 && candidateRawHead >= 0 && gen >= 0 && head >= 0)
            return candidateGeneration == gen && candidateRawHead == head;
        return candidateIdx == i && candidateVer == version;
    }

    private void clearCandidate() {
        candidateVisual = "";
        candidateIdx = -1;
        candidateVer = -1;
        candidateDistance = -1;
        candidateGeneration = -1;
        candidateRawHead = -1;
        candidateFrames = 0;
    }
}
