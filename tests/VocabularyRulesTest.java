package com.wordweave.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Uses production rules and bundled dictionary entries; no Android runtime is required. */
public final class VocabularyRulesTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        equal(VocabularyRules.choiceMeaning("adj. 精确的；准确的；严密的。\n补充词典义项：\nadj. 正确无误的"), "adj. 精确的；准确的", "concise answer excludes editorial supplements");
        equal(VocabularyRules.choiceMeaning("v. 补充"), "v. 补充", "a real meaning containing supplement is retained");
        equal(VocabularyRules.choiceMeaning("n. 设施（如水、电等）；便利；方便"), "n. 设施（如水、电等）；便利", "punctuation inside parentheses does not split a sense");
        equal(VocabularyRules.choiceMeaning("[医] 锚，固定装置"), "锚；固定装置", "specialist-only entries have a usable gist");
        check(VocabularyRules.meaningsOverlap("v. 提高", "v. 增加"), "equivalent gist is not a distractor");
        check(VocabularyRules.meaningsOverlap("adj. 准确的", "adj. 精确的"), "synonymous definitions are not opposed");
        check(VocabularyRules.meaningsOverlap("n. 承认", "v. 认可"), "different parts of speech do not make equivalent meanings wrong");
        check(!VocabularyRules.meaningsOverlap("v. 增加", "v. 减少"), "different concepts remain available");
        Set<String> roots = VocabularyRules.rootWords("PDF同群联想：parliament, aggressive\n近义：assertive（坚定自信的）\n同根派生：aggression\n同根：aggression（攻击行为；侵略）");
        check(roots.size() == 1 && roots.contains("aggression"), "only explicit roots form a family");
        check(VocabularyRules.rootWords("PDF同群联想：congressional, parliament").isEmpty(), "a shared topic is not a root annotation");
        JSONArray words = new JSONObject(new String(Files.readAllBytes(Paths.get(args[0], "app/src/main/assets/vocabulary.json")), StandardCharsets.UTF_8)).getJSONArray("words");
        int maxLength = 0;
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = words.getJSONObject(i);
            String full = word.getString("meaning"), cue = VocabularyRules.choiceMeaning(full);
            check(!cue.isEmpty(), "every bundled word has a meaning cue: " + word.getString("word"));
            check(!cue.contains("\n") && !cue.contains("补充词典") && !cue.contains("常用义补充"), "no editorial answer marker: " + word.getString("word"));
            check(cue.length() <= 160, "cue fits choice limits: " + word.getString("word"));
            maxLength = Math.max(maxLength, cue.length());
        }
        System.out.println("VocabularyRules: " + checks + " checks; " + words.length() + " dictionary cues, maximum " + maxLength + " characters");
    }
    private static void equal(String actual, String expected, String message) { check(actual.equals(expected), message + ": " + actual); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
