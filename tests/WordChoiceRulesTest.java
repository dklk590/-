import com.wordweave.app.LearningScheduler;
import com.wordweave.app.WordRepository;
import java.lang.reflect.Method;

/** Exercises production pure choice rules; Android SDK classes are not instantiated. */
public final class WordChoiceRulesTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Method tier = accessible("choiceTier", LearningScheduler.State.class);
        Method overlap = accessible("meaningsOverlap", String.class, String.class);
        Method position = accessible("choiceAnswerIndex", int.class, int.class);
        Method newer = accessible("newerCacheTimestamp", long.class, long.class);
        Method meaning = accessible("canonicalMeaning", String.class, String.class, String.class);
        LearningScheduler.State s = new LearningScheduler.State(12);
        equal(tier.invoke(null, s), 1, "unreviewed word starts at the easiest tier");
        s.recognitionStreak = 1; s.reviews = 1;
        equal(tier.invoke(null, s), 2, "independent recall raises difficulty");
        s.recognitionStreak = 2; s.reviews = 3;
        equal(tier.invoke(null, s), 3, "separate-day recognition evidence reaches advanced tier");
        s.recognitionStreak = 0; s.reviews = 20; s.lapses = 1;
        equal(tier.invoke(null, s), 1, "a recognition lapse restores the easiest tier");
        s.stage = 1;
        equal(tier.invoke(null, s), 3, "qualified spelling-stage words retain advanced recognition practice");
        check((Boolean) overlap.invoke(null, "v. 承担；承认", "n. 承认；认可"), "shared Chinese senses cannot be distractors");
        check((Boolean) overlap.invoke(null, "a. 精确的", "a. 非常精确的"), "embedded equivalent phrase cannot be a distractor");
        check((Boolean) overlap.invoke(null, "n. 锚", "v. 抛锚；n. 锚"), "single-character identical senses are caught");
        check((Boolean) overlap.invoke(null, "n. 责任\n义务", "n. 责任 义务"), "format differences do not create a distinct option");
        check(!(Boolean) overlap.invoke(null, "v. 增加；扩大", "v. 减少；缩小"), "different opposing meanings may be distractors");
        check(!(Boolean) overlap.invoke(null, "n. 信息；消息", "n. 工具；设备"), "unrelated meaning candidates remain available");
        equal(newer.invoke(null, 1000L, 900L), 1000L, "normal cache writes use current time");
        equal(newer.invoke(null, 1000L, 1000L), 1001L, "same-millisecond replacement gets a distinct snapshot timestamp");
        equal(newer.invoke(null, 100L, 1000L), 1001L, "clock rollback cannot reuse an old snapshot timestamp");
        equal(newer.invoke(null, 1L, Long.MAX_VALUE - 1), Long.MAX_VALUE, "timestamp growth remains overflow-safe");
        try { newer.invoke(null, 1L, Long.MAX_VALUE); throw new AssertionError("saturated cache timestamp must not wrap"); }
        catch (java.lang.reflect.InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalStateException, "saturated cache timestamp rejects the impossible increment");
        }
        String original = "n. 资助；发起";
        String addition = "n. 赞助；赞助款。";
        String supplemented = (String) meaning.invoke(null, original, "", addition);
        check(supplemented.startsWith(original) && supplemented.contains(addition), "supplement preserves every original dictionary sense");
        equal(meaning.invoke(null, supplemented, "", addition), supplemented, "same supplement is idempotent");
        String labeledAddition = "六级常用义补充：\nn. 赞助；赞助款。";
        equal(meaning.invoke(null, original, "", labeledAddition), original + "\n" + labeledAddition, "bundled supplement retains only one section heading");
        equal(meaning.invoke(null, original + "\n" + labeledAddition, "", labeledAddition), original + "\n" + labeledAddition, "a labeled bundled supplement remains idempotent");
        equal(meaning.invoke(null, "误义原文", "adj. 支持的；给予帮助的。", ""), "adj. 支持的；给予帮助的。", "a verified correction becomes the authoritative full meaning");
        for (int difficulty = 1; difficulty <= 3; difficulty++) {
            int[] totals = new int[4];
            for (int id = 1; id <= 3500; id++) {
                int index = (Integer) position.invoke(null, id, difficulty);
                check(index >= 0 && index < 4, "correct option index is bounded");
                totals[index]++;
                if (difficulty < 3) check(index != (Integer) position.invoke(null, id, difficulty + 1), "tier progression changes the correct location");
            }
            for (int total : totals) equal(total, 875, "all 3500 local questions balance A/B/C/D equally");
        }
        System.out.println("WordChoiceRules: " + checks + " production-rule checks passed");
    }
    private static Method accessible(String name, Class<?>... parameters) throws Exception {
        Method m = WordRepository.class.getDeclaredMethod(name, parameters); m.setAccessible(true); return m;
    }
    private static void equal(Object value, Object expected, String message) { check(expected.equals(value), message); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
