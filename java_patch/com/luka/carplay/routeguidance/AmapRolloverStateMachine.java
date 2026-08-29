package com.luka.carplay.routeguidance;

/** Small v38-style same-visual rollover guard. */
final class AmapRolloverStateMachine {
    static final int IDLE = 0;
    static final int HOLD_PENDING = 1;
    static final int WAIT_DIFFERENT_VISUAL = 2;
    static final int COMMIT_SAME_VISUAL = 3;

    private int state;
    private String visual = "";
    private int boundaryDistance = -1;
    private int generation = -1;
    private int rawHead = -1;

    void reset() {
        state = IDLE;
        visual = "";
        boundaryDistance = -1;
        generation = -1;
        rawHead = -1;
    }

    void begin(String v, int distance, int gen, int head) {
        state = HOLD_PENDING;
        visual = v == null ? "" : v;
        boundaryDistance = distance;
        generation = gen;
        rawHead = head;
    }

    int observe(boolean headAligned, boolean distanceFresh, boolean detailValid,
                String nextVisual, int distance, int gen, int head) {
        if (state == IDLE) return IDLE;
        if (!headAligned || !distanceFresh || !detailValid || distance <= 0)
            return HOLD_PENDING;
        if (nextVisual == null || nextVisual.length() == 0) return HOLD_PENDING;
        if (!nextVisual.equals(visual)) {
            state = WAIT_DIFFERENT_VISUAL;
            return WAIT_DIFFERENT_VISUAL;
        }
        boolean later = generation >= 0 && rawHead >= 0 && gen >= 0 && head >= 0
            ? (gen != generation || head != rawHead) : false;
        if (later && (boundaryDistance <= 0 || distance <= boundaryDistance)) {
            state = COMMIT_SAME_VISUAL;
            return COMMIT_SAME_VISUAL;
        }
        return HOLD_PENDING;
    }
}
