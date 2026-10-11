package com.wordweave.app;

import java.util.HashSet;
import java.util.Set;

/** Tracks the contiguous spoken prefix even when provider completion callbacks arrive out of order. */
final class SpeechProgress {
    private int completed = -1;
    private final Set<Integer> pending = new HashSet<>();
    void complete(int index) {
        if (index > completed) pending.add(index);
        while (pending.remove(completed + 1)) completed++;
    }
    int nextIndex() { return completed + 1; }
    int retryFrom() { pending.clear(); return nextIndex(); }
    void reset() { completed = -1; pending.clear(); }
}
