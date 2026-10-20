package com.wordweave.app;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Context materials, viewed explanations and per-word grades share the progress database. */
public final class ContextLearningRepository {
    private final Context context;
    private final WordRepository repository;
    private JSONObject offlineLessons, basicWords;
    public ContextLearningRepository(Context context, WordRepository repository) {
        this.context = context.getApplicationContext(); this.repository = repository;
    }
    public JSONObject create(JSONArray ids) throws Exception { synchronized (repository) {
        JSONArray words = selectedWords(ids); String key = ContextRules.materialKey(words);
        JSONObject materials = null;
        try (Cursor c = db().query("context_sessions", new String[]{"payload"}, "cache_key=?", new String[]{key}, null, null, "id DESC", "1")) {
            if (c.moveToFirst()) materials = new JSONObject(c.getString(0));
        }
        if (materials == null) materials = offlineMaterials(words);
        materials = ContextRules.validateMaterials(materials, words, "ai".equals(materials.optString("source")));
        JSONObject session = new JSONObject(materials.toString()).put("cacheKey", key).put("targetWords", words)
                .put("createdAt", System.currentTimeMillis()).put("revision", 1)
                .put("viewed", new JSONObject()).put("hints", new JSONObject()).put("revealed", new JSONObject())
                .put("grades", new JSONObject()).put("gradeTimes", new JSONObject()).put("unknownWords", new JSONArray())
                .put("wordExplanations", new JSONObject());
        ContentValues values = new ContentValues(); values.put("cache_key", key); values.put("created_at", session.getLong("createdAt")); values.put("payload", session.toString());
        SQLiteDatabase database = db(); database.beginTransaction();
        try {
            long id = database.insertOrThrow("context_sessions", null, values); session.put("id", id); save(session);
            database.setTransactionSuccessful(); return session;
        } finally { database.endTransaction(); }
    }}
    public JSONObject get(long id) throws Exception { synchronized (repository) {
        JSONObject session = load(id); JSONArray originals = session.getJSONArray("targetWords"), words = new JSONArray();
        for (int i = 0; i < originals.length(); i++) words.put(requireWord(originals.getJSONObject(i).getInt("id")));
        session.put("targetWords", words); return session;
    }}
    public JSONObject visit(long id, String sentenceId) throws Exception { synchronized (repository) {
        JSONObject session = load(id); ContextRules.sentence(session, sentenceId);
        object(session, "viewed").put(sentenceId, System.currentTimeMillis()); save(session); return session;
    }}
    public JSONObject reveal(long id, String sentenceId) throws Exception { synchronized (repository) {
        JSONObject session = load(id), sentence = ContextRules.sentence(session, sentenceId);
        object(session, "viewed").put(sentenceId, System.currentTimeMillis()); object(session, "revealed").put(sentenceId, true);
        JSONArray ids = sentence.getJSONArray("targetIds");
        for (int i = 0; i < ids.length(); i++) object(session, "hints").put(String.valueOf(ids.getInt(i)), true);
        save(session); return session;
    }}
    public JSONObject word(long id, String sentenceId, String surface) throws Exception { synchronized (repository) {
        JSONObject session = load(id), sentence = ContextRules.sentence(session, sentenceId);
        String token = ContextRules.selectedToken(sentence.getString("text"), surface);
        markUnknown(session, sentence, token); save(session);
        JSONObject cached = object(session, "wordExplanations").optJSONObject(wordKey(sentenceId, token));
        JSONObject result = cached == null ? new JSONObject() : new JSONObject(cached.toString());
        JSONArray candidates = dictionaryCandidates(token);
        if (cached == null) {
            JSONArray words = session.getJSONArray("targetWords"), notes = sentence.optJSONArray("wordNotes");
            if (notes != null) for (int i = 0; i < notes.length(); i++) {
                JSONObject note = notes.getJSONObject(i), target = ContextRules.target(words, note.getInt("wordId"));
                if (ContextRules.touches(sentence.getString("text"), token, target)) {
                    result = new JSONObject(note.toString()).put("lemma", target.getString("word")).put("source", session.optString("source")); break;
                }
            }
            if (!result.has("meaning") && candidates.length() > 0) {
                JSONObject candidate = candidates.getJSONObject(0);
                result.put("lemma", candidate.getString("word")).put("pos", candidate.optString("pos")).put("meaning", candidate.getString("meaning"))
                        .put("usage", candidate.optString("usage", "")).put("ipa", candidate.optString("ipa", "")).put("source", "dictionary");
            }
        }
        result.put("surface", token).put("dictionaryCandidates", candidates).put("session", session); return result;
    }}
    public JSONObject replaceMaterials(long id, long revision, JSONObject materials) throws Exception { synchronized (repository) {
        JSONObject session = load(id); checkRevision(session, revision);
        if (object(session, "grades").length() > 0) throw new IllegalStateException("本组已有学习记录，请完成本组后再更换语境。");
        JSONObject safe = ContextRules.validateMaterials(materials, session.getJSONArray("targetWords"), true);
        for (String key : new String[]{"title", "sentences", "source", "schemaVersion"}) session.put(key, safe.get(key));
        session.put("revision", revision + 1).put("viewed", new JSONObject()).put("revealed", new JSONObject())
                .put("wordExplanations", new JSONObject()).put("unknownWords", new JSONArray());
        save(session); return session;
    }}
    public JSONObject saveSentenceExplanation(long id, String sentenceId, long revision, JSONObject explanation) throws Exception { synchronized (repository) {
        JSONObject session = load(id); checkRevision(session, revision);
        JSONObject sentence = ContextRules.sentence(session, sentenceId);
        JSONObject safe = ContextRules.validateExplanation(explanation, sentence);
        for (String field : new String[]{"translation", "explanation", "structure", "readingTip"}) sentence.put(field, safe.get(field));
        sentence.put("explainedByAi", true); save(session); return reveal(id, sentenceId);
    }}
    public JSONObject saveWordExplanation(long id, String sentenceId, String surface, long revision, JSONObject explanation) throws Exception { synchronized (repository) {
        JSONObject session = load(id); checkRevision(session, revision); JSONObject sentence = ContextRules.sentence(session, sentenceId);
        JSONObject safe = ContextRules.validateWordExplanation(explanation, sentence, surface);
        object(session, "wordExplanations").put(wordKey(sentenceId, surface), safe); save(session); return word(id, sentenceId, surface);
    }}
    public JSONObject grade(long id, int wordId, int grade, int dailyLimit) throws Exception { synchronized (repository) {
        if (grade < 0 || grade > 4) throw new IllegalArgumentException("学习评价应在 0–4 之间。");
        SQLiteDatabase db = db(); db.beginTransaction();
        try {
            JSONObject session = load(id); ContextRules.target(session.getJSONArray("targetWords"), wordId);
            String key = String.valueOf(wordId); JSONObject grades = object(session, "grades");
            if (grades.has(key)) { db.setTransactionSuccessful(); return new JSONObject().put("session", session).put("alreadyRecorded", true); }
            int effective = ContextRules.effectiveGrade(session, wordId, grade);
            LearningScheduler.State state = repository.getState(wordId);
            if (state.lastReviewed >= session.getLong("createdAt") || state.stage != LearningScheduler.RECOGNITION) {
                grades.put(key, -1); save(session); db.setTransactionSuccessful();
                return new JSONObject().put("session", session).put("alreadyRecorded", true);
            }
            if (state.lessonAt == 0) {
                if (state.introducedAt == 0 && repository.dashboard().optInt("newToday") >= dailyLimit)
                    throw new IllegalStateException("今天的新词额度已满，已完成的记录会保留。");
                repository.completeLesson(wordId);
            }
            JSONObject word = repository.record(wordId, effective, LearningScheduler.RECOGNITION);
            grades.put(key, effective); object(session, "gradeTimes").put(key, repository.getState(wordId).lastReviewed);
            save(session); db.setTransactionSuccessful(); return new JSONObject().put("session", session).put("word", word).put("grade", effective);
        } finally { db.endTransaction(); }
    }}
    private JSONArray selectedWords(JSONArray ids) throws Exception {
        if (ids == null || ids.length() < 1 || ids.length() > 6) throw new IllegalArgumentException("每组请选择 1–6 个词。");
        Set<Integer> seen = new HashSet<>(); JSONArray words = new JSONArray();
        for (int i = 0; i < ids.length(); i++) {
            int id = ids.getInt(i); if (!seen.add(id)) throw new IllegalArgumentException("目标词不能重复。");
            words.put(requireWord(id));
        }
        return words;
    }
    private JSONObject offlineMaterials(JSONArray words) throws Exception {
        if (offlineLessons == null) offlineLessons = asset("lessons.json").getJSONObject("lessons");
        JSONArray sentences = new JSONArray(); Set<String> seen = new HashSet<>();
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = words.getJSONObject(i), primary = offlineLessons.getJSONObject(String.valueOf(word.getInt("id")));
            addOffline(sentences, seen, primary, words);
        }
        // Reuse another real, attributed example when the same word appears elsewhere.
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = words.getJSONObject(i); int count = 0;
            for (int j = 0; j < sentences.length(); j++) if (ContextRules.represents(sentences.getJSONObject(j).getString("text"), word)) count++;
            if (count >= 2) continue;
            Iterator<String> keys = offlineLessons.keys();
            while (keys.hasNext()) {
                JSONObject candidate = offlineLessons.getJSONObject(keys.next()); String text = candidate.optString("example");
                if (!seen.contains(ContextRules.normalize(text)) && ContextRules.represents(text, word)) {
                    if (addOffline(sentences, seen, candidate, words)) break;
                }
            }
        }
        return ContextRules.validateMaterials(new JSONObject().put("title", "从句子中认识这一组词").put("sentences", sentences), words, false);
    }
    private boolean addOffline(JSONArray sentences, Set<String> seen, JSONObject lesson, JSONArray words) throws Exception {
        String text = lesson.optString("example"), translation = lesson.optString("exampleTranslation");
        if (text.isEmpty() || translation.isEmpty() || seen.contains(ContextRules.normalize(text)) || sentences.length() >= 16) return false;
        boolean covered = false;
        for (int i = 0; i < words.length(); i++) if (ContextRules.represents(text, words.getJSONObject(i))) covered = true;
        if (!covered) return false;
        String usage = lesson.optString("usage");
        if (usage.equals("结合本句与对照译文理解语境用法；上方词条保留完整义项。")) usage = "";
        seen.add(ContextRules.normalize(text));
        sentences.put(new JSONObject().put("text", text).put("translation", translation).put("scene", "例句 " + (sentences.length() + 1))
                .put("explanation", usage).put("source", lesson.optString("source", "离线例句")));
        return true;
    }
    private JSONArray dictionaryCandidates(String surface) throws Exception {
        JSONArray candidates = new JSONArray(); String normalized = ContextRules.normalize(surface);
        try (Cursor c = db().rawQuery("SELECT id,word,forms FROM words WHERE word=? COLLATE NOCASE OR forms LIKE ? ORDER BY CASE WHEN word=? COLLATE NOCASE THEN 0 ELSE 1 END,id LIMIT 80", new String[]{normalized, "%" + normalized + "%", normalized})) {
            while (c.moveToNext() && candidates.length() < 5) {
                JSONObject form = new JSONObject().put("word", c.getString(1)).put("forms", c.getString(2));
                if (ContextRules.forms(form).contains(normalized)) candidates.put(requireWord(c.getInt(0)));
            }
        }
        if (basicWords == null) basicWords = asset("context_basic_words.json").getJSONObject("words");
        JSONObject basic = basicWords.optJSONObject(normalized);
        if (basic != null) candidates.put(new JSONObject(basic.toString()).put("word", basic.optString("lemma", normalized)));
        return candidates;
    }
    private void markUnknown(JSONObject session, JSONObject sentence, String token) throws Exception {
        JSONArray words = session.getJSONArray("targetWords"), unknown = session.getJSONArray("unknownWords");
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = words.getJSONObject(i);
            if (ContextRules.touches(sentence.getString("text"), token, word)) object(session, "hints").put(String.valueOf(word.getInt("id")), true);
        }
        String key = wordKey(sentence.getString("id"), token);
        for (int i = 0; i < unknown.length(); i++) if (key.equals(unknown.getJSONObject(i).optString("key"))) return;
        if (unknown.length() < 500) unknown.put(new JSONObject().put("key", key).put("surface", token).put("sentenceId", sentence.getString("id")));
    }
    private JSONObject load(long id) throws Exception {
        try (Cursor c = db().query("context_sessions", new String[]{"payload"}, "id=?", new String[]{String.valueOf(id)}, null, null, null)) {
            if (!c.moveToFirst()) throw new IllegalArgumentException("这组语境学习已不存在，请重新开始。");
            return new JSONObject(c.getString(0)).put("id", id);
        }
    }
    private void save(JSONObject session) throws Exception {
        ContentValues values = new ContentValues(); values.put("payload", session.toString());
        if (db().update("context_sessions", values, "id=?", new String[]{String.valueOf(session.getLong("id"))}) != 1) throw new IllegalStateException("学习记录未能保存，请重试。");
    }
    private SQLiteDatabase db() { return repository.getWritableDatabase(); }
    private JSONObject requireWord(int id) {
        JSONObject word = repository.getWord(id); if (word == null) throw new IllegalArgumentException("词条不存在。"); return word;
    }
    private JSONObject asset(String name) throws Exception {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    private static JSONObject object(JSONObject session, String key) throws Exception {
        JSONObject value = session.optJSONObject(key); if (value == null) { value = new JSONObject(); session.put(key, value); } return value;
    }
    private static String wordKey(String sentence, String word) { return sentence + "|" + ContextRules.normalize(word); }
    private static void checkRevision(JSONObject session, long revision) {
        if (session.optLong("revision") != revision) throw new IllegalStateException("语境句已更新，请重新打开本句。");
    }
    public static JSONArray exportSessions(SQLiteDatabase db) throws Exception {
        JSONArray result = new JSONArray();
        try (Cursor c = db.rawQuery("SELECT payload FROM context_sessions ORDER BY id", null)) { while (c.moveToNext()) result.put(new JSONObject(c.getString(0))); }
        return result;
    }
    public static void validateBackup(JSONArray sessions, Set<Integer> validIds) throws Exception {
        if (sessions == null || sessions.length() > 20000) throw new IllegalArgumentException("语境学习备份无效或过大。");
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject s = sessions.getJSONObject(i); JSONArray words = s.getJSONArray("targetWords");
            if (s.optLong("id") <= 0 || !ids.add(s.getLong("id")) || s.optLong("createdAt", -1) < 0 || s.optLong("revision") < 1 || s.toString().length() > 250000) throw new IllegalArgumentException("语境学习记录无效。");
            ContextRules.materialKey(words); ContextRules.validateMaterials(s, words, false);
            for (String field : new String[]{"viewed", "hints", "revealed", "gradeTimes", "wordExplanations"}) s.getJSONObject(field);
            JSONArray sentences = s.getJSONArray("sentences");
            for (int j = 0; j < sentences.length(); j++) {
                JSONObject sentence = sentences.getJSONObject(j);
                if (!("s" + (j + 1)).equals(sentence.optString("id"))) throw new IllegalArgumentException("语境句编号无效。");
                JSONArray targetIds = sentence.getJSONArray("targetIds");
                for (int t = 0; t < targetIds.length(); t++) {
                    JSONObject target = ContextRules.target(words, targetIds.getInt(t));
                    if (!ContextRules.represents(sentence.getString("text"), target)) throw new IllegalArgumentException("语境目标词与原句不一致。");
                }
            }
            JSONArray unknown = s.getJSONArray("unknownWords");
            if (unknown.length() > 500) throw new IllegalArgumentException("语境点词记录过多。");
            for (int n = 0; n < unknown.length(); n++) {
                JSONObject note = unknown.getJSONObject(n);
                ContextRules.selectedToken(ContextRules.sentence(s, note.getString("sentenceId")).getString("text"), note.getString("surface"));
            }
            for (int w = 0; w < words.length(); w++) if (!validIds.contains(words.getJSONObject(w).getInt("id"))) throw new IllegalArgumentException("语境词条与当前词库不匹配。");
            JSONObject grades = s.getJSONObject("grades"); Iterator<String> keys = grades.keys();
            while (keys.hasNext()) { String key = keys.next(); ContextRules.target(words, Integer.parseInt(key)); int grade = grades.getInt(key); if (grade < -1 || grade > 4) throw new IllegalArgumentException("语境学习评价无效。"); }
        }
    }
    public static void restoreSessions(SQLiteDatabase db, JSONArray sessions) throws Exception {
        db.delete("context_sessions", null, null);
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject s = sessions.getJSONObject(i); ContentValues v = new ContentValues();
            v.put("id", s.getLong("id")); v.put("created_at", s.getLong("createdAt")); v.put("cache_key", ContextRules.materialKey(s.getJSONArray("targetWords"))); v.put("payload", s.toString());
            db.insertOrThrow("context_sessions", null, v);
        }
    }
}
