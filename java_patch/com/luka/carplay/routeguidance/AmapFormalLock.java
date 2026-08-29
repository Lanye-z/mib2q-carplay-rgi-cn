package com.luka.carplay.routeguidance;

/** Keeps a committed formal maneuver from being replaced by a late Amap prompt. */
final class AmapFormalLock {
    private boolean locked;
    private String road = "";
    private int idx = -1;
    private int ver = -1;
    private int generation = -1;
    private int rawHead = -1;
    private boolean completion;

    void reset() {
        locked = false;
        road = "";
        idx = -1;
        ver = -1;
        generation = -1;
        rawHead = -1;
        completion = false;
    }

    void commit(String visual, String r, int i, int version, int gen, int head) {
        locked = true;
        road = r == null ? "" : r;
        idx = i;
        ver = version;
        generation = gen;
        rawHead = head;
        completion = false;
    }

    void updatePhysicalIdentity(int i, int version, int gen, int head) {
        if (!locked) return;
        idx = i;
        ver = version;
        generation = gen;
        rawHead = head;
    }

    void markCompletionFrame() { if (locked) completion = true; }
    boolean sawCompletion() { return completion; }

    boolean isLatePrompt(int i, int version, int gen, int head,
                         int type, String candidateRoad) {
        if (!locked || !AmapV38Compat.isPromptType(type)) return false;
        if (!samePhysical(i, version, gen, head)) return false;
        if (road.length() > 0 && candidateRoad != null && candidateRoad.length() > 0
                && !road.equals(candidateRoad)) return false;
        return true;
    }

    private boolean samePhysical(int i, int version, int gen, int head) {
        if (generation >= 0 && rawHead >= 0 && gen >= 0 && head >= 0)
            return generation == gen && rawHead == head;
        return idx == i && ver >= 0 && ver == version;
    }
}
