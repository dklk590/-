package com.wordweave.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates sentence materials against the selected dictionary words; no Android runtime needed. */
public final class ContextRules {
    public static final int VERSION = 1;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z]+(?:['’\\-][A-Za-z]+)*");
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private ContextRules() {}

    public static String normalize(String text) { return text.replace('’', '\'').toLowerCase(Locale.ROOT).trim(); }
    public static boolean contains(String text, String word) {
        return Pattern.compile("(?<![A-Za-z])" + Pattern.quote(normalize(word)) + "(?![A-Za-z])", Pattern.CASE_INSENSITIVE)
                .matcher(normalize(text)).find();
    }
    public static List<String> tokens(String text) {
        ArrayList<String> out = new ArrayList<>(); Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) out.add(matcher.group()); return out;
    }
    public static Set<String> forms(JSONObject word) {
        Set<String> result = new LinkedHashSet<>(); result.add(normalize(word.optString("word")));
        for (String token : tokens(word.optString("forms"))) if (token.length() > 1) result.add(normalize(token));
        return result;
    }
    public static boolean represents(String text, JSONObject word) {
        for (String form : forms(word)) if (!form.isEmpty() && contains(text, form)) return true;
        return false;
    }
    public static boolean touches(String text, String surface, JSONObject word) {
        for (String form : forms(word)) if (contains(text, form) && contains(form, surface)) return true;
        return false;
    }
    public static String selectedToken(String text, String surface) {
        if (surface == null || surface.length() > 80 || !TOKEN.matcher(surface).matches()) throw new IllegalArgumentException("请选择句子中的一个英文词。");
        for (String token : tokens(text)) if (normalize(token).equals(normalize(surface))) return token;
        throw new IllegalArgumentException("这个词不在当前句子中。");
    }
    public static String materialKey(JSONArray words) {
        ArrayList<Integer> ids = new ArrayList<>();
        if (words == null || words.length() < 1 || words.length() > 6) throw new IllegalArgumentException("每组请选择 1–6 个词。");
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = words.optJSONObject(i); int id = word == null ? 0 : word.optInt("id");
            if (id <= 0 || ids.contains(id) || word.optString("word").trim().isEmpty()) throw new IllegalArgumentException("目标词编号无效或重复。");
            ids.add(id);
        }
        java.util.Collections.sort(ids); return "context-v" + VERSION + ":" + ids.toString();
    }
    public static JSONObject sentence(JSONObject session, String sentenceId) {
        JSONArray sentences = session.optJSONArray("sentences");
        if (sentences != null) for (int i = 0; i < sentences.length(); i++) {
            JSONObject sentence = sentences.optJSONObject(i);
            if (sentence != null && sentenceId.equals(sentence.optString("id"))) return sentence;
        }
        throw new IllegalArgumentException("请重新打开当前语境句。");
    }
    public static JSONObject target(JSONArray words, int id) {
        if (words != null) for (int i = 0; i < words.length(); i++) if (words.optJSONObject(i) != null && words.optJSONObject(i).optInt("id") == id) return words.optJSONObject(i);
        throw new IllegalArgumentException("这个词不属于本组学习内容。");
    }
    public static int effectiveGrade(JSONObject session, int wordId, int grade) throws Exception {
        if (grade < 0 || grade > 4) throw new IllegalArgumentException("学习评价应在 0–4 之间。");
        target(session.getJSONArray("targetWords"), wordId);
        boolean found = false; JSONArray sentences = session.getJSONArray("sentences");
        JSONObject viewed = session.optJSONObject("viewed");
        for (int i = 0; i < sentences.length(); i++) {
            JSONObject sentence = sentences.getJSONObject(i); JSONArray ids = sentence.getJSONArray("targetIds");
            for (int j = 0; j < ids.length(); j++) if (ids.getInt(j) == wordId) {
                found = true;
                if (viewed == null || !viewed.has(sentence.getString("id"))) throw new IllegalStateException("请先读完包含这个词的语境句。");
            }
        }
        if (!found) throw new IllegalStateException("这个词尚未出现在语境句中。");
        JSONObject hints = session.optJSONObject("hints");
        return hints != null && hints.optBoolean(String.valueOf(wordId)) ? Math.min(grade, 1) : grade;
    }
    public static JSONObject validateMaterials(JSONObject supplied, JSONArray words, boolean ai) throws Exception {
        materialKey(words);
        JSONArray input = supplied.optJSONArray("sentences");
        if (input == null || input.length() < (ai ? 2 : 1) || input.length() > 16) throw new IllegalArgumentException("语境句数量应为 2–16 句。");
        Set<String> texts = new HashSet<>(); java.util.Map<Integer, Set<String>> scenes = new java.util.HashMap<>();
        JSONArray safe = new JSONArray();
        for (int i = 0; i < input.length(); i++) {
            JSONObject raw = input.getJSONObject(i);
            String text = required(raw, "text", 1200, false);
            int count = tokens(text).size();
            if (HAN.matcher(text).find() || count < (ai ? 6 : 1) || count > 65 || !texts.add(normalize(text).replaceAll("\\s+", " ")))
                throw new IllegalArgumentException("语境句应为不重复的自然英文句子，最多 65 个英文词。");
            String scene = required(raw, "scene", 80, ai);
            JSONArray present = new JSONArray();
            for (int w = 0; w < words.length(); w++) {
                JSONObject word = words.getJSONObject(w); int id = word.getInt("id");
                if (represents(text, word)) {
                    present.put(id); scenes.computeIfAbsent(id, key -> new HashSet<>()).add(normalize(scene));
                }
            }
            if (present.length() == 0) throw new IllegalArgumentException("每句至少应包含一个本组目标词。");
            JSONObject sentence = new JSONObject().put("id", "s" + (i + 1)).put("text", text).put("scene", scene)
                    .put("translation", required(raw, "translation", 2000, true)).put("targetIds", present)
                    .put("explanation", ai ? required(raw, "explanation", 1600, true) : raw.optString("explanation", ""))
                    .put("structure", ai ? required(raw, "structure", 1600, true) : "")
                    .put("readingTip", ai ? required(raw, "readingTip", 600, true) : raw.optString("readingTip", ""))
                    .put("source", ai ? "AI 语境句" : raw.optString("source", "离线例句"));
            JSONArray notes = raw.optJSONArray("wordNotes"), safeNotes = new JSONArray(); Set<Integer> noted = new HashSet<>();
            if (notes != null) for (int n = 0; n < notes.length(); n++) {
                JSONObject note = notes.getJSONObject(n); int id = note.optInt("wordId"); JSONObject word = target(words, id);
                if (!represents(text, word) || !noted.add(id)) throw new IllegalArgumentException("词语讲解与本句目标词不一致。");
                safeNotes.put(new JSONObject().put("wordId", id).put("word", word.getString("word"))
                        .put("pos", required(note, "pos", 100, false)).put("meaning", required(note, "meaning", 1000, true))
                        .put("usage", required(note, "usage", 1200, true)));
            }
            if (ai && noted.size() != present.length()) throw new IllegalArgumentException("请为本句每个目标词补齐语境义和用法。");
            sentence.put("wordNotes", safeNotes); safe.put(sentence);
        }
        for (int w = 0; w < words.length(); w++) {
            JSONObject word = words.getJSONObject(w); Set<String> contexts = scenes.get(word.getInt("id"));
            if (contexts == null || contexts.size() < (ai ? 2 : 1)) throw new IllegalArgumentException("每个目标词至少需要两个不同情境的句子：" + word.getString("word"));
        }
        String title = supplied.optString("title", "在语境中理解单词");
        if (title.length() > 160) throw new IllegalArgumentException("语境标题过长。");
        return new JSONObject().put("title", title).put("sentences", safe)
                .put("source", ai ? "ai" : "local").put("schemaVersion", VERSION);
    }
    public static JSONObject validateExplanation(JSONObject raw, JSONObject sentence) throws Exception {
        if (!normalize(required(raw, "text", 1200, false)).equals(normalize(sentence.getString("text")))) throw new IllegalArgumentException("讲解的句子与原文不一致。");
        return new JSONObject().put("text", sentence.getString("text")).put("translation", required(raw, "translation", 2000, true))
                .put("explanation", required(raw, "explanation", 2000, true)).put("structure", required(raw, "structure", 1800, true))
                .put("readingTip", required(raw, "readingTip", 600, true));
    }
    public static JSONObject validateWordExplanation(JSONObject raw, JSONObject sentence, String surface) throws Exception {
        selectedToken(sentence.getString("text"), surface);
        if (!normalize(required(raw, "surface", 80, false)).equals(normalize(surface))) throw new IllegalArgumentException("讲解的单词与所选词不一致。");
        String lemma = required(raw, "lemma", 100, false);
        if (!TOKEN.matcher(lemma).matches()) throw new IllegalArgumentException("词条原形格式无效。");
        return new JSONObject().put("surface", surface).put("lemma", lemma).put("pos", required(raw, "pos", 100, false))
                .put("meaning", required(raw, "meaning", 1000, true)).put("usage", required(raw, "usage", 1600, true))
                .put("formNote", required(raw, "formNote", 600, true)).put("source", "ai");
    }
    private static String required(JSONObject object, String key, int maximum, boolean chinese) throws Exception {
        Object value = object.opt(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("讲解缺少字段：" + key);
        String text = ((String)value).trim();
        if (text.isEmpty() || text.length() > maximum || chinese && !HAN.matcher(text).find()) throw new IllegalArgumentException("讲解字段为空、过长或语言不正确：" + key);
        return text;
    }
}
