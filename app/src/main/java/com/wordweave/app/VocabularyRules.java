package com.wordweave.app;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared rules for concise meaning practice and explicitly annotated word families. */
public final class VocabularyRules {
    public static final int POLICY_VERSION = 2;
    public static final String CHOICE_QUESTION = "选择这个词的大意。";
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]+");
    private static final Pattern WORD = Pattern.compile("[A-Za-z]+(?:[-'][A-Za-z]+)*");
    // Conservative exclusions: related concepts are unnecessary distractors for gist practice.
    private static final String[][] SIMILAR = {
            {"增加", "增多", "增大", "提高", "提升", "上升", "增长", "扩大"},
            {"减少", "减小", "降低", "下降", "缩小", "削减"},
            {"开始", "起始", "开端"}, {"结束", "终止", "完结"},
            {"重要", "重大", "关键"}, {"精确", "准确", "正确"},
            {"允许", "许可", "准许"}, {"拒绝", "否决", "不接受"},
            {"购买", "买入", "采购"}, {"帮助", "援助", "协助"},
            {"害怕", "恐惧", "畏惧"}, {"高兴", "愉快", "快乐"},
            {"聪明", "明智", "智慧"}, {"责任", "义务", "职责"},
            {"信息", "消息", "资讯"}, {"承认", "认可", "认同"}
    };

    private VocabularyRules() {}

    /** A practice cue; the authoritative dictionary entry is never changed. */
    public static String choiceMeaning(String fullMeaning) {
        String fallback = "";
        for (String raw : String.valueOf(fullMeaning == null ? "" : fullMeaning).split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.matches("^(?:补充词典义项|六级常用义补充|词典义项补充)[：:]?$") || !HAN.matcher(line).find()) continue;
            if (fallback.isEmpty()) fallback = line;
            if (line.startsWith("[")) continue;
            return shortSenses(line);
        }
        return shortSenses(fallback);
    }

    private static String shortSenses(String line) {
        String clean = line.replaceFirst("^\\[[^]]*]\\s*", "");
        java.util.ArrayList<String> senses = new java.util.ArrayList<>();
        int start = 0, depth = 0;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '(' || c == '（') depth++;
            if (c == ')' || c == '）') depth = Math.max(0, depth - 1);
            if (depth == 0 && "；;，,、。".indexOf(c) >= 0) { senses.add(clean.substring(start, i)); start = i + 1; }
        }
        senses.add(clean.substring(start));
        StringBuilder out = new StringBuilder();
        for (String sense : senses) {
            if (sense.trim().isEmpty()) continue;
            if (out.length() > 0) { out.append('；').append(sense.trim()); break; }
            out.append(sense.trim());
        }
        return out.toString();
    }

    /** Only explicit root annotations establish families; PDF groups and prefixes do not. */
    public static Set<String> rootWords(String relations) { return relatedWords(relations, true); }

    public static Set<String> relatedWords(String relations, boolean rootsOnly) {
        Set<String> words = new LinkedHashSet<>();
        for (String line : (relations == null ? "" : relations).split("\\r?\\n")) {
            String text = line.trim();
            if (!text.matches(rootsOnly ? "^同根(?:派生|词)?[：:].*" : "^(?:同根(?:派生|词)?|近义(?:词)?|易混(?:词)?)[：:].*")) continue;
            text = text.replaceAll("（[^）]*）|\\([^)]*\\)", "");
            Matcher matcher = WORD.matcher(text);
            while (matcher.find()) {
                String word = matcher.group().toLowerCase(Locale.ROOT);
                if (word.length() > 1 && !word.matches("adj|adv|vt|vi|prep|pron")) words.add(word);
            }
        }
        return words;
    }

    public static boolean meaningsOverlap(String a, String b) {
        if (normalMeaning(a).equals(normalMeaning(b))) return true;
        Set<String> left = senseTokens(a), right = senseTokens(b);
        for (String x : left) for (String y : right) {
            if (x.equals(y) || x.length() >= 2 && y.length() >= 2 && (x.contains(y) || y.contains(x))) return true;
        }
        for (String[] group : SIMILAR) {
            boolean foundLeft = false, foundRight = false;
            for (String concept : group) {
                for (String token : left) if (token.contains(concept)) foundLeft = true;
                for (String token : right) if (token.contains(concept)) foundRight = true;
            }
            if (foundLeft && foundRight) return true;
        }
        return false;
    }

    public static String normalMeaning(String text) { return text == null ? "" : text.replaceAll("\\s+", "").trim(); }

    private static Set<String> senseTokens(String text) {
        Set<String> tokens = new HashSet<>();
        Matcher matcher = HAN.matcher(text == null ? "" : text.replaceAll("[^\\n]*(?:补充词典义项|六级常用义补充)[：:]?", ""));
        while (matcher.find()) {
            String token = matcher.group().replaceFirst("^使", "").replaceFirst("的$", "");
            if (!token.isEmpty() && !token.matches("的|地|等|人名")) tokens.add(token);
        }
        return tokens;
    }
}
