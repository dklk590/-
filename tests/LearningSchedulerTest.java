import com.wordweave.app.LearningScheduler;
import com.wordweave.app.LearningScheduler.State;

/** Run with javac/java; no Android runtime or JUnit dependency is required. */
public final class LearningSchedulerTest {
    private static final long START = 1_780_000_000_000L;
    private static final long DAY = LearningScheduler.DAY;
    private static int checks;

    public static void main(String[] args) {
        introductionsAndIntervals();
        lessonExposureIsSeparateFromRetrieval();
        graduationNeedsSeparateDays();
        hintedRecallCannotGraduate();
        modesCannotGraduateEachOther();
        wrongModeSuccessPreservesTheRequiredSkillSchedule();
        wrongModeDifficultyStillTriggersRelearning();
        forgottenRecallRegressesAndRelearns();
        lateReviewsUseReviewTime();
        estimatesAreOnlyBoundedHints();
        invalidInputsAndExtremeTime();
        System.out.println("LearningScheduler: " + checks + " deterministic checks passed");
    }

    private static void introductionsAndIntervals() {
        State original = new State(41);
        State first = LearningScheduler.apply(original, 3, 0, START);
        check(original.reviews == 0 && original.dueAt == 0, "apply must not mutate its input");
        check(first.reviews == 1 && first.introducedAt == START, "first review introduces a word");
        check(first.dueAt == START + 20 * LearningScheduler.MINUTE, "initial delayed check is twenty minutes");
        State second = LearningScheduler.apply(first, 3, 0, first.dueAt);
        near(second.intervalDays, 1.0, "second independent retrieval gets one day");
        State third = LearningScheduler.apply(second, 3, 0, second.dueAt);
        near(third.intervalDays, 3.0, "three-day interval follows one day");
        // Exercise interval expansion without changing stages; reading does not graduate.
        State reading = new State(3); reading.stage = 2; reading.intervalDays = 3; reading.reviews = 3;
        reading = LearningScheduler.apply(reading, 3, 2, START);
        near(reading.intervalDays, 7.0, "three days advances to seven");
        reading = LearningScheduler.apply(reading, 3, 2, reading.dueAt);
        near(reading.intervalDays, 14.0, "seven days advances to fourteen");
        reading = LearningScheduler.apply(reading, 3, 2, reading.dueAt);
        near(reading.intervalDays, 30.0, "fourteen days advances to thirty");
        reading = LearningScheduler.apply(reading, 3, 2, reading.dueAt);
        check(reading.intervalDays > 30 && reading.stage == 2, "later intervals adapt and reading remains reading");
    }

    private static void graduationNeedsSeparateDays() {
        State s = LearningScheduler.apply(new State(1), 3, 0, START);
        s = LearningScheduler.apply(s, 4, 0, START + 30 * LearningScheduler.MINUTE);
        s = LearningScheduler.apply(s, 3, 0, START + 19 * 60 * LearningScheduler.MINUTE);
        check(s.stage == 0 && s.recognitionStreak == 1, "same-day repetitions do not graduate recognition");
        s = LearningScheduler.apply(s, 2, 0, START + DAY);
        check(s.stage == 0 && s.recognitionStreak == 2, "hard but independent recall counts on a separate day");
        s = LearningScheduler.apply(s, 3, 0, START + 2 * DAY);
        check(s.stage == 1 && s.recognitionStreak == 3, "three separate-day successes graduate recognition");
        check(s.dueAt <= START + 3 * DAY, "the new production skill gets an early check");
        s = LearningScheduler.apply(s, 3, 1, START + 3 * DAY);
        s = LearningScheduler.apply(s, 3, 1, START + 3 * DAY + 30 * LearningScheduler.MINUTE);
        check(s.stage == 1 && s.spellingStreak == 1, "same-day spelling repetitions do not graduate");
        s = LearningScheduler.apply(s, 3, 1, START + 4 * DAY);
        check(s.stage == 2 && s.spellingStreak == 2, "two separate-day spelling successes graduate");
    }

    private static void lessonExposureIsSeparateFromRetrieval() {
        State lessonOnly = new State(19);
        lessonOnly.lessonAt = START;
        lessonOnly.introducedAt = START;
        check(lessonOnly.reviews == 0 && lessonOnly.recognitionStreak == 0 && lessonOnly.lastReviewed == 0,
                "lesson exposure does not constitute recall or a review");
        State recalled = LearningScheduler.apply(lessonOnly, 3, 0, START + 5 * LearningScheduler.MINUTE);
        check(recalled.lessonAt == START && recalled.introducedAt == START,
                "first retrieval preserves the separate lesson and introduction timestamps");
        check(recalled.reviews == 1 && recalled.recognitionStreak == 1,
                "only the first explicit scored retrieval contributes review and qualification evidence");
        check(recalled.dueAt == START + 25 * LearningScheduler.MINUTE,
                "the first retrieval still receives the initial twenty-minute retry");
    }

    private static void hintedRecallCannotGraduate() {
        State s = LearningScheduler.apply(new State(1), 3, 0, START);
        s = LearningScheduler.apply(s, 3, 0, START + DAY);
        s = LearningScheduler.apply(s, 1, 0, START + 2 * DAY);
        check(s.stage == 0 && s.recognitionStreak == 2, "hinted recognition does not increment graduation evidence");
        check(s.dueAt == START + 2 * DAY + 30 * LearningScheduler.MINUTE, "hinted recall is retried in thirty minutes");
        State spelling = new State(2); spelling.stage = 1; spelling.spellingStreak = 1; spelling.spellingLastAt = START;
        spelling = LearningScheduler.apply(spelling, 1, 1, START + DAY);
        check(spelling.stage == 1 && spelling.spellingStreak == 1, "hinted spelling cannot graduate");
    }

    private static void modesCannotGraduateEachOther() {
        State s = new State(1);
        for (int day = 0; day < 8; day++) s = LearningScheduler.apply(s, 4, 1, START + day * DAY);
        check(s.stage == 0 && s.recognitionStreak == 0 && s.spellingStreak == 0, "spelling mode cannot graduate a recognition-stage word");
        s.stage = 1;
        for (int day = 8; day < 12; day++) s = LearningScheduler.apply(s, 4, 0, START + day * DAY);
        check(s.stage == 1 && s.spellingStreak == 0, "recognition mode cannot graduate a spelling-stage word");
        for (int day = 12; day < 16; day++) s = LearningScheduler.apply(s, 4, 2, START + day * DAY);
        check(s.stage == 1 && s.spellingStreak == 0, "AI reading self-grades cannot bypass spelling qualification");
    }

    private static void forgottenRecallRegressesAndRelearns() {
        State s = new State(7); s.stage = 2; s.reviews = 10; s.intervalDays = 30; s.ease = 2.8; s.introducedAt = START - 40 * DAY;
        s.recognitionStreak = 3; s.spellingStreak = 2;
        State forgotten = LearningScheduler.apply(s, 0, 2, START);
        check(forgotten.stage == 1 && forgotten.lapses == 1, "a forgotten word regresses one stage");
        check(forgotten.recognitionStreak == 0 && forgotten.spellingStreak == 0, "a lapse clears independent-recall evidence");
        check(forgotten.dueAt == START + 10 * LearningScheduler.MINUTE, "forgotten words get a ten-minute retry");
        check(forgotten.introducedAt == s.introducedAt, "relearning does not consume a new-word slot");
        State relearned = LearningScheduler.apply(forgotten, 3, 1, forgotten.dueAt);
        near(relearned.intervalDays, 1.0, "long previous intervals do not survive a lapse");
        State forgottenAgain = LearningScheduler.apply(relearned, 0, 1, START + DAY);
        check(forgottenAgain.stage == 0 && forgottenAgain.lapses == 2, "a second lapse returns to recognition");
        State third = LearningScheduler.apply(forgottenAgain, 0, 0, START + 2 * DAY);
        check(third.stage == 0, "recognition stage cannot regress below zero");
    }

    private static void wrongModeSuccessPreservesTheRequiredSkillSchedule() {
        for (int stage = 0; stage < 3; stage++) {
            for (int mode = 0; mode < 3; mode++) {
                if (mode == stage) continue;
                for (int grade = 2; grade <= 4; grade++) {
                    for (boolean overdue : new boolean[]{false, true}) {
                        State old = establishedState(stage);
                        long now = START + (overdue ? 9 : 1) * DAY;
                        State result = LearningScheduler.apply(old, grade, mode, now);
                        String label = "wrong-mode success stage=" + stage + " mode=" + mode + " grade=" + grade + " overdue=" + overdue;
                        check(result.dueAt == old.dueAt, label + " must preserve the required skill's due date");
                        near(result.intervalDays, old.intervalDays, label + " must preserve its interval");
                        near(result.ease, old.ease, label + " must preserve its ease");
                        check(result.stage == old.stage && result.recognitionStreak == old.recognitionStreak && result.spellingStreak == old.spellingStreak,
                                label + " cannot manufacture graduation evidence");
                        check(result.recognitionLastAt == old.recognitionLastAt && result.spellingLastAt == old.spellingLastAt,
                                label + " cannot update qualification timestamps");
                        check(result.reviews == old.reviews + 1 && result.lastReviewed == now,
                                label + " still logs the explicit practice");
                        check(result.lapses == old.lapses && result.introducedAt == old.introducedAt,
                                label + " is not a lapse or a new introduction");
                        check(old.lastReviewed == START - DAY && old.reviews == 8, label + " cannot mutate the input");
                    }
                }
            }
        }
    }

    private static void wrongModeDifficultyStillTriggersRelearning() {
        for (int stage = 0; stage < 3; stage++) {
            for (int mode = 0; mode < 3; mode++) {
                if (mode == stage) continue;
                State old = establishedState(stage);
                long now = START + 9 * DAY;
                State forgotten = LearningScheduler.apply(old, 0, mode, now);
                check(forgotten.dueAt == now + 10 * LearningScheduler.MINUTE && forgotten.stage == Math.max(0, stage - 1),
                        "wrong-mode forgetting still schedules a short retry and a stage regression");
                check(forgotten.lapses == old.lapses + 1 && forgotten.recognitionStreak == 0 && forgotten.spellingStreak == 0,
                        "wrong-mode forgetting clears independent-recall evidence");
                check(forgotten.ease < old.ease && forgotten.intervalDays < 1.0,
                        "wrong-mode forgetting lowers ease and rebuilds the interval");
                State hinted = LearningScheduler.apply(old, 1, mode, now);
                check(hinted.dueAt == now + 30 * LearningScheduler.MINUTE && hinted.stage == stage,
                        "wrong-mode hinted recall still schedules a thirty-minute retry");
                check(hinted.recognitionStreak == old.recognitionStreak && hinted.spellingStreak == old.spellingStreak
                                && hinted.recognitionLastAt == old.recognitionLastAt && hinted.spellingLastAt == old.spellingLastAt,
                        "wrong-mode hinted recall cannot count as independent qualification");
                check(hinted.ease < old.ease && hinted.intervalDays < 1.0 && hinted.lapses == old.lapses,
                        "wrong-mode hinted recall slows the schedule without counting a lapse");
            }
        }
    }

    private static State establishedState(int stage) {
        State s = new State(12);
        s.stage = stage;
        s.reviews = 8;
        s.lapses = 1;
        s.ease = 2.9;
        s.intervalDays = 7.0;
        s.dueAt = START + 3 * DAY;
        s.lastReviewed = START - DAY;
        s.introducedAt = START - 20 * DAY;
        s.recognitionStreak = 2;
        s.spellingStreak = 1;
        s.recognitionLastAt = START - 2 * DAY;
        s.spellingLastAt = START - 3 * DAY;
        return s;
    }

    private static void lateReviewsUseReviewTime() {
        State s = new State(3); s.stage = 2; s.intervalDays = 7; s.reviews = 5; s.lastReviewed = START; s.dueAt = START + 7 * DAY;
        long late = START + 100 * DAY;
        State lateSuccess = LearningScheduler.apply(s, 3, 2, late);
        check(lateSuccess.dueAt > late, "late review schedules from the actual review, not the missed due date");
        check(lateSuccess.intervalDays >= 14 && lateSuccess.intervalDays <= 16.8 + 1e-9, "overdue bonus is bounded at twenty percent");
        State lateFailure = LearningScheduler.apply(s, 0, 2, late);
        check(lateFailure.dueAt == late + 10 * LearningScheduler.MINUTE, "late failure does not receive a long bonus");
    }

    private static void estimatesAreOnlyBoundedHints() {
        State s = new State(1);
        near(LearningScheduler.retentionEstimate(s, START), 0, "unseen words have no retention estimate");
        s.reviews = 2; s.intervalDays = 3; s.lastReviewed = START;
        double early = LearningScheduler.retentionEstimate(s, START + DAY);
        double late = LearningScheduler.retentionEstimate(s, START + 30 * DAY);
        check(early >= late && early <= 1 && late >= 0, "retention urgency is bounded and declines with elapsed time");
        near(LearningScheduler.retentionEstimate(s, START - DAY), 1, "clock moving backwards cannot produce more than one");
    }

    private static void invalidInputsAndExtremeTime() {
        expectInvalid(() -> LearningScheduler.apply(new State(1), -1, 0, START), "invalid grade rejected");
        expectInvalid(() -> LearningScheduler.apply(new State(1), 3, 3, START), "invalid mode rejected");
        expectInvalid(() -> LearningScheduler.apply(new State(1), 3, 0, -1), "negative clock rejected");
        State extreme = new State(1); extreme.stage = 2; extreme.intervalDays = Double.POSITIVE_INFINITY; extreme.ease = Double.NaN;
        extreme = LearningScheduler.apply(extreme, 4, 2, Long.MAX_VALUE - 5);
        check(extreme.dueAt == Long.MAX_VALUE && !Double.isNaN(extreme.ease), "durations saturate safely near timestamp limits");
    }

    private static void expectInvalid(Runnable action, String message) {
        try { action.run(); throw new AssertionError(message); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 1e-8, message + ": " + actual);
    }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
