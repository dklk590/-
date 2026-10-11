package com.wordweave.app;

/**
 * Offline, deterministic learning schedule. This is an adaptive interval heuristic,
 * not a measured individual forgetting curve or a promise of a retention rate.
 */
public final class LearningScheduler {
    public static final int RECOGNITION = 0;
    public static final int SPELLING = 1;
    public static final int READING = 2;
    public static final long MINUTE = 60_000L;
    public static final long DAY = 86_400_000L;
    private static final long DISTINCT_DAY_GAP = 20L * 60L * MINUTE;
    private static final double MAX_INTERVAL_DAYS = 3650.0;

    private LearningScheduler() {}

    public static final class State {
        public int wordId;
        public int stage;
        public int recognitionStreak;
        public int spellingStreak;
        public int reviews;
        public int lapses;
        public double ease = 2.5;
        public double intervalDays;
        public long dueAt;
        public long lastReviewed;
        public long introducedAt;
        public long recognitionLastAt;
        public long spellingLastAt;
        /** Reading a lesson is exposure, not a successful retrieval review. */
        public long lessonAt;

        public State() {}
        public State(int wordId) { this.wordId = wordId; }

        public State copy() {
            State s = new State(wordId);
            s.stage = stage;
            s.recognitionStreak = recognitionStreak;
            s.spellingStreak = spellingStreak;
            s.reviews = reviews;
            s.lapses = lapses;
            s.ease = ease;
            s.intervalDays = intervalDays;
            s.dueAt = dueAt;
            s.lastReviewed = lastReviewed;
            s.introducedAt = introducedAt;
            s.recognitionLastAt = recognitionLastAt;
            s.spellingLastAt = spellingLastAt;
            s.lessonAt = lessonAt;
            return s;
        }
    }

    /** Grades: 0 forgotten, 1 needed a hint, 2 hard, 3 good, 4 easy. */
    public static State apply(State previous, int grade, int mode, long now) {
        if (previous == null) throw new IllegalArgumentException("State is required");
        if (grade < 0 || grade > 4) throw new IllegalArgumentException("Grade must be 0..4");
        if (mode < RECOGNITION || mode > READING) throw new IllegalArgumentException("Mode must be 0..2");
        if (now < 0) throw new IllegalArgumentException("Review time cannot be negative");
        State s = previous.copy();
        s.stage = Math.max(RECOGNITION, Math.min(READING, s.stage));
        s.ease = finiteClamp(s.ease, 1.3, 3.2, 2.5);
        s.intervalDays = finiteClamp(s.intervalDays, 0.0, MAX_INTERVAL_DAYS, 0.0);
        s.reviews = s.reviews == Integer.MAX_VALUE ? s.reviews : Math.max(0, s.reviews) + 1;
        if (s.introducedAt == 0) s.introducedAt = now;

        // Optional practice of a different skill does not replace the delayed
        // retrieval still required by this word's current stage. Keep an
        // overdue dueAt too, so early reading cannot hide a required review.
        if (grade >= 2 && mode != s.stage) {
            s.lastReviewed = now;
            return s;
        }

        if (grade == 0) {
            s.lapses = s.lapses == Integer.MAX_VALUE ? s.lapses : Math.max(0, s.lapses) + 1;
            s.ease = Math.max(1.3, s.ease - 0.20);
            s.intervalDays = 10.0 / (24.0 * 60.0);
            s.dueAt = addDuration(now, 10 * MINUTE);
            s.stage = Math.max(RECOGNITION, s.stage - 1);
            // A lapse breaks the evidence used to graduate either skill.
            s.recognitionStreak = 0;
            s.spellingStreak = 0;
            s.recognitionLastAt = 0;
            s.spellingLastAt = 0;
        } else if (grade == 1) {
            s.ease = Math.max(1.3, s.ease - 0.10);
            s.intervalDays = 30.0 / (24.0 * 60.0);
            s.dueAt = addDuration(now, 30 * MINUTE);
            // Hints are useful scaffolding, but never count as independent recall.
        } else {
            double next = nextInterval(s.intervalDays);
            if (grade == 2) {
                s.ease = Math.max(1.3, s.ease - 0.15);
                // Keep the first delayed check short; later hard recall slows expansion.
                if (next >= 1.0) next = Math.max(1.0, next * 0.65);
            } else if (grade == 4) {
                s.ease = Math.min(3.2, s.ease + 0.12);
                if (next >= 1.0) next *= 1.20;
            }
            if (grade >= 3 && previous.dueAt > 0 && now > previous.dueAt
                    && previous.intervalDays >= 1.0) {
                double overdueDays = (now - previous.dueAt) / (double) DAY;
                next += Math.min(overdueDays * 0.20, next * 0.20);
            }
            // At longer intervals the learner's previous difficulty affects growth.
            if (next > 30.0) next *= s.ease / 2.5;
            s.intervalDays = finiteClamp(next, 20.0 / (24.0 * 60.0), MAX_INTERVAL_DAYS, 1.0);
            s.dueAt = addDuration(now, Math.round(s.intervalDays * DAY));

            if (mode == RECOGNITION && s.stage == RECOGNITION
                    && separateDay(s.recognitionLastAt, s.recognitionStreak, now)) {
                s.recognitionStreak++;
                s.recognitionLastAt = now;
                if (s.recognitionStreak >= 3) {
                    s.stage = SPELLING;
                    s.spellingStreak = 0;
                    s.spellingLastAt = 0;
                    // A new production skill deserves an early first check.
                    s.dueAt = Math.min(s.dueAt, addDuration(now, DAY));
                    s.intervalDays = (s.dueAt - now) / (double) DAY;
                }
            } else if (mode == SPELLING && s.stage == SPELLING
                    && separateDay(s.spellingLastAt, s.spellingStreak, now)) {
                s.spellingStreak++;
                s.spellingLastAt = now;
                if (s.spellingStreak >= 2) {
                    s.stage = READING;
                    s.dueAt = Math.min(s.dueAt, addDuration(now, 3 * DAY));
                    s.intervalDays = (s.dueAt - now) / (double) DAY;
                }
            }
        }
        s.lastReviewed = now;
        return s;
    }

    private static boolean separateDay(long lastAt, int streak, long now) {
        return streak <= 0 || (now >= lastAt && now - lastAt >= DISTINCT_DAY_GAP);
    }

    private static double nextInterval(double current) {
        if (current <= 0.0) return 20.0 / (24.0 * 60.0);
        if (current < 0.10) return 1.0;
        if (current < 2.0) return 3.0;
        if (current < 5.0) return 7.0;
        if (current < 10.0) return 14.0;
        if (current < 22.0) return 30.0;
        return current * 2.2;
    }

    /** UI-only heuristic for an urgency cue; do not display it as a proven probability. */
    public static double retentionEstimate(State s, long now) {
        if (s == null || s.reviews <= 0 || s.intervalDays <= 0) return 0.0;
        double elapsedDays = Math.max(0.0, (now - (double) s.lastReviewed) / DAY);
        double interval = finiteClamp(s.intervalDays, 1.0 / 1440.0, MAX_INTERVAL_DAYS, 1.0);
        return Math.max(0.0, Math.min(1.0, Math.exp(Math.log(0.90) * elapsedDays / interval)));
    }

    private static double finiteClamp(double value, double low, double high, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return fallback;
        return Math.max(low, Math.min(high, value));
    }

    private static long addDuration(long time, long duration) {
        long positive = Math.max(0L, duration);
        return time > Long.MAX_VALUE - positive ? Long.MAX_VALUE : time + positive;
    }
}
