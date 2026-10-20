package com.wordweave.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;

/** Local-only word data and progress. All bulk calls should run off the UI thread. */
public final class WordRepository extends SQLiteOpenHelper {
    private static final String[] STATE_COLUMNS = {
            "word_id", "stage", "recognition_streak", "spelling_streak", "reviews", "lapses",
            "ease", "interval_days", "due_at", "last_reviewed", "introduced_at",
            "recognition_last_at", "spelling_last_at", "lesson_at"
    };
    private final Context context;
    private JSONObject offlineLessons;
    private final HashMap<Integer, JSONObject> meaningSupplements = new HashMap<>();
    private final HashMap<String, Set<String>> rootLinks = new HashMap<>();
    private final HashMap<String, Integer> familyWordIds = new HashMap<>();

    public WordRepository(Context context) {
        super(context.getApplicationContext(), "wordweave.db", null, 3);
        this.context = context.getApplicationContext();
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE groups_data (id TEXT PRIMARY KEY,label TEXT NOT NULL,sort_order INTEGER NOT NULL,extra TEXT NOT NULL)");
        db.execSQL("CREATE TABLE words (id INTEGER PRIMARY KEY,word TEXT NOT NULL UNIQUE,meaning TEXT NOT NULL,ipa TEXT NOT NULL,pos TEXT NOT NULL,group_id TEXT NOT NULL,group_label TEXT NOT NULL,relations TEXT NOT NULL,forms TEXT NOT NULL,source TEXT NOT NULL)");
        db.execSQL("CREATE INDEX words_group ON words(group_id,id)");
        db.execSQL("CREATE TABLE states (word_id INTEGER PRIMARY KEY REFERENCES words(id) ON DELETE CASCADE,stage INTEGER NOT NULL,recognition_streak INTEGER NOT NULL,spelling_streak INTEGER NOT NULL,reviews INTEGER NOT NULL,lapses INTEGER NOT NULL,ease REAL NOT NULL,interval_days REAL NOT NULL,due_at INTEGER NOT NULL,last_reviewed INTEGER NOT NULL,introduced_at INTEGER NOT NULL,recognition_last_at INTEGER NOT NULL,spelling_last_at INTEGER NOT NULL,lesson_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX states_due ON states(stage,due_at)");
        db.execSQL("CREATE INDEX states_introduced ON states(introduced_at)");
        db.execSQL("CREATE TABLE learning_log (id INTEGER PRIMARY KEY AUTOINCREMENT,word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,grade INTEGER NOT NULL,mode INTEGER NOT NULL,reviewed_at INTEGER NOT NULL,interval_days REAL NOT NULL,stage INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX log_time ON learning_log(reviewed_at)");
        db.execSQL("CREATE TABLE readings (id INTEGER PRIMARY KEY AUTOINCREMENT,created_at INTEGER NOT NULL,payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        createLearningTables(db);
        createContextTables(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2 && newVersion >= 2) {
            db.execSQL("ALTER TABLE states ADD COLUMN lesson_at INTEGER NOT NULL DEFAULT 0");
            // Existing v1 learners already studied these words; keep their progress usable.
            db.execSQL("UPDATE states SET lesson_at=CASE WHEN introduced_at>0 THEN introduced_at WHEN last_reviewed>0 THEN last_reviewed ELSE 1 END WHERE reviews>0");
            createLearningTables(db);
        }
        if (oldVersion < 3 && newVersion >= 3) createContextTables(db);
        if (newVersion > 3) throw new IllegalStateException("Unsupported database upgrade");
    }

    private static void createContextTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS context_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT,created_at INTEGER NOT NULL,cache_key TEXT NOT NULL,payload TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS context_materials ON context_sessions(cache_key,id)");
    }

    private static void createLearningTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS learning_packs (word_id INTEGER PRIMARY KEY REFERENCES words(id) ON DELETE CASCADE,payload TEXT NOT NULL,cached_at INTEGER NOT NULL,source TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS choice_cache (word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,tier INTEGER NOT NULL CHECK(tier BETWEEN 1 AND 3),payload TEXT NOT NULL,cached_at INTEGER NOT NULL,source TEXT NOT NULL,PRIMARY KEY(word_id,tier))");
    }

    /** Imports the bundled curated vocabulary once, in one atomic transaction. */
    public synchronized void importVocabulary() {
        SQLiteDatabase db = getWritableDatabase();
        JSONObject data;
        String vocabularyText;
        try (InputStream in = context.getAssets().open("vocabulary.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            vocabularyText = new String(out.toByteArray(), StandardCharsets.UTF_8);
            data = new JSONObject(vocabularyText);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read bundled vocabulary", e);
        }
        JSONArray words = data.optJSONArray("words");
        if (words == null || words.length() == 0) throw new IllegalStateException("Vocabulary is empty");
        String supplementsText = optionalAsset("meaning_supplements.json");
        loadSupplements(supplementsText);
        String fingerprint = fingerprint(vocabularyText + "\n" + supplementsText);
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = words.optJSONObject(i);
            if (word == null) throw new IllegalStateException("Invalid bundled word");
            JSONObject supplement = meaningSupplements.get(word.optInt("id", i + 1));
            if (supplement != null) {
                if (supplement.has("word") && !supplement.optString("word").equals(word.optString("word"))) throw new IllegalStateException("词义补充的单词编号不匹配");
                String corrected = supplement.optString("correctedMeaning", "").trim();
                String addition = first(supplement, "meaningAppend", "append", "text", "supplement");
                put(word, "meaning", canonicalMeaning(word.optString("meaning", ""), corrected, addition));
                String correctedPos = supplement.optString("correctedPos", "").trim();
                if (!correctedPos.isEmpty()) put(word, "pos", correctedPos);
            }
        }
        if (scalar(db, "SELECT COUNT(*) FROM words", null) > 0) {
            if (!fingerprint.equals(metaValue(db, "dictionaryAssetFingerprint"))) syncDictionary(db, words, fingerprint);
            refreshLearningPolicy(db);
            return;
        }
        JSONArray groups = data.optJSONArray("groups");
        db.beginTransaction();
        try {
            if (groups != null) {
                for (int i = 0; i < groups.length(); i++) {
                    JSONObject g = groups.optJSONObject(i);
                    if (g == null) continue;
                    String id = first(g, "id", "groupId", "group_id");
                    if (id.isEmpty()) continue;
                    ContentValues v = new ContentValues();
                    v.put("id", id);
                    v.put("label", first(g, "label", "groupLabel", "title", "name"));
                    v.put("sort_order", i);
                    v.put("extra", g.toString());
                    db.insertWithOnConflict("groups_data", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            Set<String> inferred = new HashSet<>();
            for (int i = 0; i < words.length(); i++) {
                JSONObject w = words.optJSONObject(i);
                if (w == null) throw new IllegalStateException("Invalid word at row " + i);
                String word = w.optString("word", "").trim();
                String meaning = w.optString("meaning", "").trim();
                if (word.isEmpty() || meaning.isEmpty()) throw new IllegalStateException("Missing word or meaning at row " + i);
                String groupId = first(w, "groupId", "group_id");
                if (groupId.isEmpty()) groupId = "ungrouped";
                String groupLabel = first(w, "groupLabel", "group_label");
                if (groupLabel.isEmpty()) groupLabel = "未分组";
                ContentValues v = new ContentValues();
                v.put("id", w.optInt("id", i + 1));
                v.put("word", word);
                v.put("meaning", meaning);
                v.put("ipa", w.optString("ipa", ""));
                v.put("pos", w.optString("pos", ""));
                v.put("group_id", groupId);
                v.put("group_label", groupLabel);
                v.put("relations", valueString(w, "relations"));
                v.put("forms", valueString(w, "forms"));
                v.put("source", valueString(w, "source"));
                db.insertOrThrow("words", null, v);
                if (inferred.add(groupId)) {
                    ContentValues g = new ContentValues();
                    g.put("id", groupId);
                    g.put("label", groupLabel);
                    g.put("sort_order", groups == null ? i : groups.length() + i);
                    g.put("extra", "{}");
                    db.insertWithOnConflict("groups_data", null, g, SQLiteDatabase.CONFLICT_IGNORE);
                }
            }
            putMeta(db, "vocabularyVersion", "1.1");
            putMeta(db, "dictionaryAssetFingerprint", fingerprint);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        refreshLearningPolicy(db);
    }

    /** Invalidate derived questions after a policy change, retaining all learning records. */
    private void refreshLearningPolicy(SQLiteDatabase db) {
        rootLinks.clear(); familyWordIds.clear();
        String version = String.valueOf(VocabularyRules.POLICY_VERSION);
        if (version.equals(metaValue(db, "learningPolicyVersion"))) return;
        db.beginTransaction();
        try {
            db.delete("choice_cache", null, null);
            db.delete("learning_packs", null, null);
            putMeta(db, "learningPolicyVersion", version);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized JSONObject getWord(int wordId) {
        try (Cursor c = getReadableDatabase().query("words", null, "id=?", new String[]{String.valueOf(wordId)}, null, null, null)) {
            return c.moveToFirst() ? wordJson(c, System.currentTimeMillis()) : null;
        }
    }

    public synchronized JSONArray listWords(String query, String groupId, int limit) {
        return listWords(query, groupId, limit, false);
    }

    public synchronized JSONArray listWords(String query, String groupId, int limit, boolean learnedOnly) {
        StringBuilder where = new StringBuilder("1=1");
        if (learnedOnly) where.append(" AND EXISTS (SELECT 1 FROM states s WHERE s.word_id=words.id AND (s.lesson_at>0 OR s.reviews>0))");
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        if (query != null && !query.trim().isEmpty()) {
            where.append(" AND (word LIKE ? ESCAPE '\\' OR meaning LIKE ? ESCAPE '\\')");
            String q = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(q); args.add(q);
        }
        if (groupId != null && !groupId.trim().isEmpty() && !"all".equals(groupId)) {
            where.append(" AND group_id=?"); args.add(groupId);
        }
        JSONArray result = new JSONArray();
        long now = System.currentTimeMillis();
        try (Cursor c = getReadableDatabase().query("words", null, where.toString(), args.toArray(new String[0]), null, null, "id ASC", String.valueOf(boundedLimit(limit, 50000)))) {
            while (c.moveToNext()) result.put(wordJson(c, now));
        }
        return result;
    }

    public synchronized JSONArray getGroups() {
        JSONArray result = new JSONArray();
        String sql = "SELECT g.id,g.label,g.extra,COUNT(w.id) AS word_count,SUM(CASE WHEN s.reviews>0 OR s.lesson_at>0 THEN 1 ELSE 0 END) AS learned_count,SUM(CASE WHEN s.due_at<=? AND s.reviews>0 THEN 1 ELSE 0 END) AS due_count FROM groups_data g LEFT JOIN words w ON w.group_id=g.id LEFT JOIN states s ON s.word_id=w.id GROUP BY g.id ORDER BY g.sort_order,g.id";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(System.currentTimeMillis())})) {
            while (c.moveToNext()) {
                JSONObject g = parseObject(c.getString(2));
                put(g, "id", c.getString(0)); put(g, "groupId", c.getString(0));
                put(g, "label", c.getString(1)); put(g, "groupLabel", c.getString(1));
                put(g, "count", c.getInt(3)); put(g, "learned", c.getInt(4)); put(g, "due", c.getInt(5));
                result.put(g);
            }
        }
        return result;
    }

    public synchronized JSONObject dashboard() {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        long[] day = todayBounds(now);
        int total = (int) scalar(db, "SELECT COUNT(*) FROM words", null);
        int studied = (int) scalar(db, "SELECT COUNT(*) FROM states WHERE reviews>0 OR lesson_at>0", null);
        int due = (int) scalar(db, "SELECT COUNT(*) FROM states WHERE reviews>0 AND due_at<=?", new String[]{String.valueOf(now)});
        JSONArray stages = new JSONArray();
        JSONArray dueStages = new JSONArray();
        for (int stage = 0; stage < 3; stage++) {
            stages.put(scalar(db, "SELECT COUNT(*) FROM states WHERE (reviews>0 OR lesson_at>0) AND stage=?", new String[]{String.valueOf(stage)}));
            dueStages.put(scalar(db, "SELECT COUNT(*) FROM states WHERE reviews>0 AND stage=? AND due_at<=?", new String[]{String.valueOf(stage), String.valueOf(now)}));
        }
        long todayReviews = scalar(db, "SELECT COUNT(*) FROM learning_log WHERE reviewed_at>=? AND reviewed_at<?", new String[]{String.valueOf(day[0]), String.valueOf(day[1])});
        long reviews = scalar(db, "SELECT COUNT(*) FROM learning_log", null);
        long independent = scalar(db, "SELECT COUNT(*) FROM learning_log WHERE grade>=2", null);
        JSONObject result = new JSONObject();
        put(result, "totalWords", total); put(result, "studiedWords", studied);
        put(result, "dueWords", due); put(result, "newToday", newToday(db, now));
        put(result, "stageCounts", stages); put(result, "dueByStage", dueStages);
        put(result, "todayReviews", todayReviews); put(result, "streak", studyStreak(db, day[0]));
        put(result, "accuracy", reviews == 0 ? 0.0 : independent / (double) reviews);
        put(result, "reviews", reviews); put(result, "now", now);
        return result;
    }

    /** Returns overdue words first. New words retain bundled group and family order. */
    public synchronized JSONArray getQueue(int mode, int limit, int newLimit) {
        checkMode(mode);
        return buildQueue(mode, limit, newLimit);
    }

    public synchronized JSONArray getMixedQueue(int limit, int newLimit) {
        return buildQueue(-1, limit, newLimit);
    }

    private JSONArray buildQueue(int mode, int limit, int newLimit) {
        int max = boundedLimit(limit, 500);
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        JSONArray result = new JSONArray();
        String sql = "SELECT w.* FROM words w JOIN states s ON s.word_id=w.id WHERE ((?=-1 AND s.stage<2) OR s.stage=?) AND s.reviews>0 AND s.due_at<=? ORDER BY s.due_at,w.id LIMIT ?";
        LinkedHashMap<String, ArrayDeque<JSONObject>> dueGroups = new LinkedHashMap<>();
        int candidateLimit = Math.min(50000, Math.max(100, max * 8));
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(mode), String.valueOf(mode), String.valueOf(now), String.valueOf(candidateLimit)})) {
            while (c.moveToNext()) {
                JSONObject w = wordJson(c, now);
                String group = w.optString("groupId");
                ArrayDeque<JSONObject> bucket = dueGroups.get(group);
                if (bucket == null) { bucket = new ArrayDeque<>(); dueGroups.put(group, bucket); }
                bucket.add(w);
            }
        }
        // Initial learning keeps related items adjacent. Later reviews alternate groups
        // so recognition is tested without a run of nearly identical contextual cues.
        boolean added = true;
        while (result.length() < max && added) {
            added = false;
            for (ArrayDeque<JSONObject> group : dueGroups.values()) {
                if (result.length() >= max) break;
                if (!group.isEmpty()) { result.put(group.removeFirst()); added = true; }
            }
        }
        if ((mode == LearningScheduler.RECOGNITION || mode == -1) && result.length() < max) {
            // Understanding a lesson has consumed the new-word slot, but its first
            // retrieval question must remain available even after the daily cap.
            String pending = "SELECT w.* FROM words w JOIN states s ON s.word_id=w.id WHERE s.stage=0 AND s.lesson_at>0 AND s.reviews=0 ORDER BY s.lesson_at,w.id LIMIT ?";
            try (Cursor c = db.rawQuery(pending, new String[]{String.valueOf(max - result.length())})) {
                while (c.moveToNext()) result.put(wordJson(c, now));
            }
            int remaining = Math.max(0, Math.min(200, newLimit) - newToday(db, now));
            int newCount = Math.min(max - result.length(), remaining);
            if (newCount > 0) {
                String fresh = "SELECT w.* FROM words w LEFT JOIN states s ON s.word_id=w.id LEFT JOIN groups_data g ON g.id=w.group_id WHERE s.word_id IS NULL OR (s.reviews=0 AND s.lesson_at=0) ORDER BY COALESCE(g.sort_order,999999),w.id LIMIT ?";
                try (Cursor c = db.rawQuery(fresh, new String[]{String.valueOf(newCount)})) {
                    while (c.moveToNext()) result.put(wordJson(c, now));
                }
            }
        }
        return result;
    }

    /** Only an explicit user self-grade should call this, including after AI reading. */
    public synchronized JSONObject record(int wordId, int grade, int mode) {
        checkMode(mode);
        SQLiteDatabase db = getWritableDatabase();
        if (scalar(db, "SELECT COUNT(*) FROM words WHERE id=?", new String[]{String.valueOf(wordId)}) == 0) throw new IllegalArgumentException("Unknown word");
        if (getState(wordId).lessonAt <= 0) throw new IllegalArgumentException("请先阅读完整讲解与例句，并确认已理解，再开始练习。");
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            LearningScheduler.State s = LearningScheduler.apply(getState(wordId), grade, mode, now);
            db.insertWithOnConflict("states", null, stateValues(s), SQLiteDatabase.CONFLICT_REPLACE);
            ContentValues log = new ContentValues();
            log.put("word_id", wordId); log.put("grade", grade); log.put("mode", mode);
            log.put("reviewed_at", now); log.put("interval_days", s.intervalDays); log.put("stage", s.stage);
            db.insertOrThrow("learning_log", null, log);
            newToday(db, now);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        JSONObject word = getWord(wordId);
        put(word, "grade", grade);
        put(word, "feedback", grade == 0 ? "先看关系或词根提示，再尝试回忆；10 分钟后再复习。" : grade == 1 ? "这次借助了提示，30 分钟后再独立回忆。" : "已安排下一次间隔复习。");
        return word;
    }

    /**
     * Saves the learner's per-word reading self-grade and schedule together.
     * The grade ledger is included in backups with the reading payload.
     */
    public synchronized JSONObject recordReading(long readingId, int wordId, int grade) {
        if (grade < 0 || grade > 4) throw new IllegalArgumentException("复习评价应在 0–4 之间。");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONObject reading = readingForWord(readingId, wordId);
            if (reading.optJSONObject("evaluation") == null)
                throw new IllegalArgumentException("请先提交翻译并完成 AI 评价，再记录本篇阅读的回忆情况。");
            String key = String.valueOf(wordId);
            JSONObject grades = reading.optJSONObject("reviewGrades");
            if (grades == null) grades = new JSONObject();
            if (grades.has(key)) {
                JSONObject word = getWord(wordId);
                put(word, "alreadyRecorded", true);
                db.setTransactionSuccessful();
                return word;
            }
            JSONObject hints = reading.optJSONObject("reviewHints");
            int effectiveGrade = hints != null && hints.optBoolean(key, false) ? Math.min(grade, 1) : grade;
            JSONObject word = record(wordId, effectiveGrade, LearningScheduler.READING);
            put(grades, key, effectiveGrade);
            JSONObject times = reading.optJSONObject("reviewGradeTimes");
            if (times == null) times = new JSONObject();
            put(times, key, getState(wordId).lastReviewed);
            put(reading, "reviewGrades", grades);
            put(reading, "reviewGradeTimes", times);
            saveReading(reading);
            db.setTransactionSuccessful();
            return word;
        } finally {
            db.endTransaction();
        }
    }

    /** Persists a viewed meaning as a hint before the full word is displayed. */
    public synchronized JSONObject markReadingHint(long readingId, int wordId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONObject reading = readingForWord(readingId, wordId);
            JSONObject hints = reading.optJSONObject("reviewHints");
            if (hints == null) hints = new JSONObject();
            put(hints, String.valueOf(wordId), true);
            put(reading, "reviewHints", hints);
            saveReading(reading);
            db.setTransactionSuccessful();
            return reading;
        } finally {
            db.endTransaction();
        }
    }

    private JSONObject readingForWord(long readingId, int wordId) {
        JSONObject reading = getReading(readingId);
        if (reading == null) throw new IllegalArgumentException("阅读记录不存在，请重新选择阅读练习。");
        JSONArray targets = reading.optJSONArray("targetWords");
        boolean contains = false;
        if (targets != null) for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.optJSONObject(i);
            if (target != null && target.optInt("id", -1) == wordId) { contains = true; break; }
        }
        if (!contains || getWord(wordId) == null)
            throw new IllegalArgumentException("该单词不是本篇阅读的目标词。");
        return reading;
    }

    public synchronized LearningScheduler.State getState(int wordId) {
        try (Cursor c = getReadableDatabase().query("states", STATE_COLUMNS, "word_id=?", new String[]{String.valueOf(wordId)}, null, null, null)) {
            if (!c.moveToFirst()) return new LearningScheduler.State(wordId);
            return readState(c);
        }
    }

    /** Viewing or prefetching a lesson never changes learning progress. */
    public synchronized JSONObject getLesson(int wordId) {
        JSONObject word = requireWord(wordId);
        put(word, "dictionarySource", word.optString("source", ""));
        JSONObject pack = cachedPack(wordId);
        JSONObject example = new JSONObject();
        String usage = "";
        if (pack != null) {
            JSONObject supplied = pack.optJSONObject("example");
            if (supplied != null) example = parseObject(supplied.toString());
            usage = example.optString("usage", "");
            put(word, "source", "ai"); put(word, "aiCached", true);
            put(word, "memoryHint", pack.optString("memoryHint", ""));
        } else {
            JSONObject offline = offlineLesson(wordId);
            put(example, "en", offline.optString("example", ""));
            put(example, "zh", offline.optString("exampleTranslation", ""));
            usage = offline.optString("usage", "");
            if ("结合本句与对照译文理解语境用法；上方词条保留完整义项。".equals(usage)) usage = "";
            put(example, "usage", usage);
            put(example, "source", offline.optString("source", "离线例句"));
            put(word, "source", "local"); put(word, "aiCached", false);
            put(word, "lessonSource", offline.optString("source", "离线例句"));
            put(word, "memoryHint", word.optString("relations", ""));
        }
        put(word, "meanings", new JSONArray().put(word.optString("meaning")));
        put(word, "explanation", word.optString("meaning") + (usage.isEmpty() ? "" : "\n\n用法：" + usage));
        put(word, "example", example);
        JSONArray examples = new JSONArray();
        if (!example.optString("en", "").trim().isEmpty()) examples.put(example);
        put(word, "examples", examples);
        put(word, "familyWords", rootFamily(wordId, false));
        return word;
    }

    /** Joined spelling exercises include introduced relatives, without advancing their schedules. */
    public synchronized JSONObject getSpellingExercise(int wordId) {
        JSONObject exercise = new JSONObject();
        put(exercise, "wordId", wordId);
        put(exercise, "policyVersion", VocabularyRules.POLICY_VERSION);
        put(exercise, "words", rootFamily(wordId, true));
        return exercise;
    }

    private JSONArray rootFamily(int wordId, boolean introducedOnly) {
        JSONObject target = requireWord(wordId);
        if (familyWordIds.isEmpty()) {
            try (Cursor c = getReadableDatabase().rawQuery("SELECT id,word,relations FROM words ORDER BY id", null)) {
                while (c.moveToNext()) {
                    String name = c.getString(1).toLowerCase(Locale.ROOT);
                    familyWordIds.put(name, c.getInt(0));
                    rootLinks.computeIfAbsent(name, key -> new HashSet<>());
                    for (String related : VocabularyRules.rootWords(c.getString(2))) {
                        rootLinks.get(name).add(related);
                        rootLinks.computeIfAbsent(related, key -> new HashSet<>()).add(name);
                    }
                }
            }
        }
        Set<String> seen = new HashSet<>(); ArrayDeque<String> pending = new ArrayDeque<>();
        String name = target.optString("word").toLowerCase(Locale.ROOT);
        pending.add(name); seen.add(name);
        while (!pending.isEmpty()) {
            Set<String> links = rootLinks.get(pending.removeFirst());
            if (links != null) for (String related : links) {
                if (familyWordIds.containsKey(related) && seen.add(related)) pending.addLast(related);
            }
        }
        ArrayList<Integer> ids = new ArrayList<>();
        for (String related : seen) {
            Integer id = familyWordIds.get(related);
            if (id != null && id != wordId) ids.add(id);
        }
        Collections.sort(ids); ids.add(0, wordId);
        JSONArray words = new JSONArray();
        for (Integer id : ids) {
            JSONObject word = requireWord(id);
            JSONObject state = word.optJSONObject("state");
            if (introducedOnly && id != wordId && (state == null || state.optLong("lessonAt") == 0 && state.optInt("reviews") == 0)) continue;
            JSONObject entry = new JSONObject();
            for (String field : new String[]{"id", "word", "meaning", "pos", "ipa", "forms"}) put(entry, field, word.opt(field));
            put(entry, "cue", VocabularyRules.choiceMeaning(word.optString("meaning")));
            words.put(entry);
        }
        return words;
    }

    /** Exposure confirmation is idempotent; it is never scored as independent recall. */
    public synchronized JSONObject completeLesson(int wordId) {
        LearningScheduler.State s = getState(wordId);
        if (s.lessonAt > 0) return requireWord(wordId);
        JSONObject lesson = getLesson(wordId);
        JSONObject example = lesson.optJSONObject("example");
        if (example == null || example.optString("en").trim().isEmpty() || example.optString("zh").trim().isEmpty())
            throw new IllegalStateException("例句或中文解释尚未准备完成，请重试或生成 AI 讲解。");
        long now = System.currentTimeMillis();
        s.lessonAt = now;
        if (s.introducedAt <= 0) s.introducedAt = now;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.insertWithOnConflict("states", null, stateValues(s), SQLiteDatabase.CONFLICT_REPLACE);
            newToday(db, now);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return requireWord(wordId);
    }

    public synchronized JSONObject getChoice(int wordId) { return getChoice(wordId, 0); }

    /** Cache difficulty is fixed per tier; the selected tier advances per word. */
    public synchronized JSONObject getChoice(int wordId, int requestedTier) {
        JSONObject word = requireWord(wordId);
        LearningScheduler.State s = getState(wordId);
        int tier = requestedTier <= 0 ? choiceTier(s) : Math.max(1, Math.min(3, requestedTier));
        SQLiteDatabase db = getWritableDatabase();
        JSONObject choice = null;
        String source = "local";
        long cachedAt = 0;
        try (Cursor c = db.query("choice_cache", new String[]{"payload", "source", "cached_at"}, "word_id=? AND tier=?", new String[]{String.valueOf(wordId), String.valueOf(tier)}, null, null, null)) {
            if (c.moveToFirst()) { choice = parseObject(c.getString(0)); source = c.getString(1); cachedAt = c.getLong(2); }
        }
        if (choice == null) {
            choice = localChoice(word, tier);
            db.beginTransaction();
            try {
                // Another repository instance may have filled the same cache while
                // distractors were selected. Preserve that completed cache snapshot.
                try (Cursor c = db.query("choice_cache", new String[]{"payload", "source", "cached_at"}, "word_id=? AND tier=?", new String[]{String.valueOf(wordId), String.valueOf(tier)}, null, null, null)) {
                    if (c.moveToFirst()) { choice = parseObject(c.getString(0)); source = c.getString(1); cachedAt = c.getLong(2); }
                    else { source = "local"; cachedAt = nextCacheTimestamp(db, System.currentTimeMillis()); saveChoice(db, wordId, tier, choice, source, cachedAt); }
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        put(choice, "wordId", wordId); put(choice, "word", word.optString("word"));
        put(choice, "tier", tier); put(choice, "level", tier); put(choice, "difficulty", tier);
        put(choice, "source", source); put(choice, "cachedAt", cachedAt); put(choice, "review", s.reviews > 0);
        String answer = choice.optString("answer");
        put(choice, "correctId", answer); put(choice, "answerIndex", answer.charAt(0) - 'A'); put(choice, "correctIndex", answer.charAt(0) - 'A');
        return choice;
    }

    /** Saves validated AI lessons and all three question tiers in one transaction. */
    public synchronized void saveLearningPacks(JSONArray packs) {
        if (packs == null || packs.length() == 0 || packs.length() > 32) throw new IllegalArgumentException("学习包数量无效");
        ArrayList<JSONObject> normalized = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < packs.length(); i++) {
            JSONObject pack = packs.optJSONObject(i);
            if (pack == null) throw new IllegalArgumentException("学习包格式无效");
            int id = pack.optInt("word_id", pack.optInt("wordId", 0));
            if (!ids.add(id)) throw new IllegalArgumentException("学习包单词重复");
            normalized.add(validatePack(pack, requireWord(id)));
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long now = nextCacheTimestamp(db, System.currentTimeMillis());
            for (JSONObject pack : normalized) {
                int id = pack.optInt("word_id");
                ContentValues v = new ContentValues(); v.put("word_id", id); v.put("payload", pack.toString()); v.put("cached_at", now); v.put("source", "ai");
                db.insertWithOnConflict("learning_packs", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                JSONArray tiers = pack.optJSONArray("tiers");
                for (int t = 0; t < tiers.length(); t++) {
                    JSONObject choice = tiers.optJSONObject(t);
                    saveChoice(db, id, choice.optInt("level"), choice, "ai", now);
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized JSONArray getUpcomingWords(int limit) { return getUpcomingWords(limit, new JSONArray()); }

    public synchronized JSONArray getUnintroducedWords(int limit, JSONArray excludedIds) {
        return futureWords(limit, excludedIds, false);
    }

    /** Future, unintroduced words without a complete AI pack; no progress mutation. */
    public synchronized JSONArray getUpcomingWords(int limit, JSONArray excludedIds) {
        return futureWords(limit, excludedIds, true);
    }

    private JSONArray futureWords(int limit, JSONArray excludedIds, boolean uncachedOnly) {
        if (limit <= 0) return new JSONArray();
        int max = boundedLimit(limit, 100);
        StringBuilder sql = new StringBuilder("SELECT w.* FROM words w LEFT JOIN states s ON s.word_id=w.id LEFT JOIN groups_data g ON g.id=w.group_id WHERE (s.word_id IS NULL OR (s.reviews=0 AND s.lesson_at=0))");
        if (uncachedOnly) sql.append(" AND (NOT EXISTS (SELECT 1 FROM learning_packs p WHERE p.word_id=w.id AND p.source='ai') OR (SELECT COUNT(*) FROM choice_cache c WHERE c.word_id=w.id AND c.source='ai')<3)");
        ArrayList<String> args = new ArrayList<>();
        if (excludedIds != null && excludedIds.length() > 0) {
            sql.append(" AND w.id NOT IN (");
            int count = Math.min(500, excludedIds.length());
            for (int i = 0; i < count; i++) { if (i > 0) sql.append(','); sql.append('?'); args.add(String.valueOf(excludedIds.optInt(i, -1))); }
            sql.append(')');
        }
        sql.append(" ORDER BY COALESCE(g.sort_order,999999),w.id LIMIT ?"); args.add(String.valueOf(max));
        JSONArray result = new JSONArray();
        long now = System.currentTimeMillis();
        try (Cursor c = getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]))) { while (c.moveToNext()) result.put(wordJson(c, now)); }
        return result;
    }

    public synchronized JSONObject exportBackup() {
        SQLiteDatabase db = getReadableDatabase();
        JSONObject backup = new JSONObject();
        put(backup, "format", "WordWeaveBackup"); put(backup, "version", 2);
        put(backup, "exportedAt", System.currentTimeMillis());
        put(backup, "wordCount", scalar(db, "SELECT COUNT(*) FROM words", null));
        JSONArray states = new JSONArray();
        try (Cursor c = db.query("states", STATE_COLUMNS, null, null, null, null, "word_id")) {
            while (c.moveToNext()) states.put(stateJson(readState(c), System.currentTimeMillis()));
        }
        JSONArray log = new JSONArray();
        try (Cursor c = db.query("learning_log", null, null, null, null, null, "id")) {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                put(row, "wordId", c.getInt(c.getColumnIndexOrThrow("word_id")));
                put(row, "grade", c.getInt(c.getColumnIndexOrThrow("grade")));
                put(row, "mode", c.getInt(c.getColumnIndexOrThrow("mode")));
                put(row, "reviewedAt", c.getLong(c.getColumnIndexOrThrow("reviewed_at")));
                put(row, "intervalDays", c.getDouble(c.getColumnIndexOrThrow("interval_days")));
                put(row, "stage", c.getInt(c.getColumnIndexOrThrow("stage")));
                log.put(row);
            }
        }
        put(backup, "states", states); put(backup, "learningLog", log);
        put(backup, "readings", readingHistoryFull());
        put(backup, "learningPacks", cachedRows("learning_packs"));
        put(backup, "choiceCache", cachedRows("choice_cache"));
        try { put(backup, "contextSessions", ContextLearningRepository.exportSessions(db)); }
        catch (Exception ex) { throw new IllegalStateException("语境学习记录无法导出", ex); }
        return backup;
    }

    /** Restores a complete progress snapshot atomically after validating every row. */
    public synchronized void importBackup(JSONObject backup) {
        if (backup == null || !"WordWeaveBackup".equals(backup.optString("format")) || backup.optInt("version") < 1 || backup.optInt("version") > 2) throw new IllegalArgumentException("不是受支持的词链备份");
        int version = backup.optInt("version");
        JSONArray states = backup.optJSONArray("states");
        JSONArray log = backup.optJSONArray("learningLog");
        JSONArray readings = backup.optJSONArray("readings");
        JSONArray packs = version >= 2 ? backup.optJSONArray("learningPacks") : new JSONArray();
        JSONArray choices = version >= 2 ? backup.optJSONArray("choiceCache") : new JSONArray();
        JSONArray contexts = backup.optJSONArray("contextSessions");
        if (backup.has("contextSessions") && contexts == null) throw new IllegalArgumentException("语境学习备份格式无效");
        if (contexts == null) contexts = new JSONArray();
        if (states == null || log == null || readings == null) throw new IllegalArgumentException("备份缺少学习记录");
        if (packs == null || choices == null) throw new IllegalArgumentException("备份缺少学习缓存");
        if (states.length() > 50000 || log.length() > 1000000 || readings.length() > 20000) throw new IllegalArgumentException("备份过大");
        SQLiteDatabase db = getWritableDatabase();
        Set<Integer> validIds = new HashSet<>();
        try (Cursor c = db.rawQuery("SELECT id FROM words", null)) { while (c.moveToNext()) validIds.add(c.getInt(0)); }
        Set<Integer> seenIds = new HashSet<>();
        try { ContextLearningRepository.validateBackup(contexts, validIds); }
        catch (Exception ex) { throw new IllegalArgumentException("语境学习备份无效", ex); }
        for (int i = 0; i < states.length(); i++) {
            JSONObject row = states.optJSONObject(i);
            if (row == null || !validIds.contains(row.optInt("wordId")) || !seenIds.add(row.optInt("wordId"))) throw new IllegalArgumentException("备份词条不匹配或重复");
            validateState(stateFromJson(row));
        }
        for (int i = 0; i < log.length(); i++) {
            JSONObject row = log.optJSONObject(i);
            if (row == null || !validIds.contains(row.optInt("wordId")) || row.optInt("grade", -1) < 0 || row.optInt("grade", -1) > 4 || row.optInt("mode", -1) < 0 || row.optInt("mode", -1) > 2 || row.optLong("reviewedAt", -1) < 0
                    || row.optInt("stage", -1) < 0 || row.optInt("stage", -1) > 2
                    || !finiteInterval(row.optDouble("intervalDays", Double.NaN))) throw new IllegalArgumentException("备份复习记录无效");
        }
        Set<Long> readingIds = new HashSet<>();
        for (int i = 0; i < readings.length(); i++) {
            JSONObject row = readings.optJSONObject(i);
            if (row == null || row.optLong("id", 0) <= 0 || !readingIds.add(row.optLong("id")) || row.optLong("createdAt", -1) < 0) throw new IllegalArgumentException("备份阅读记录无效或重复");
        }
        Set<Integer> staleCacheWords = validateCachedBackup(packs, choices, validIds);
        db.beginTransaction();
        try {
            db.delete("states", null, null); db.delete("learning_log", null, null); db.delete("readings", null, null);
            db.delete("learning_packs", null, null); db.delete("choice_cache", null, null);
            for (int i = 0; i < states.length(); i++) db.insertOrThrow("states", null, stateValues(stateFromJson(states.optJSONObject(i))));
            for (int i = 0; i < log.length(); i++) {
                JSONObject row = log.optJSONObject(i);
                ContentValues v = new ContentValues();
                v.put("word_id", row.optInt("wordId")); v.put("grade", row.optInt("grade")); v.put("mode", row.optInt("mode"));
                v.put("reviewed_at", row.optLong("reviewedAt")); v.put("interval_days", row.optDouble("intervalDays", 0)); v.put("stage", row.optInt("stage", 0));
                db.insertOrThrow("learning_log", null, v);
            }
            for (int i = 0; i < readings.length(); i++) saveReading(readings.optJSONObject(i));
            restoreCachedRows("learning_packs", packs, staleCacheWords);
            restoreCachedRows("choice_cache", choices, staleCacheWords);
            try { ContextLearningRepository.restoreSessions(db, contexts); }
            catch (Exception ex) { throw new IllegalStateException("语境学习记录恢复失败", ex); }
            newToday(db, System.currentTimeMillis());
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Upserts by id so a generated reading can later receive a saved evaluation. */
    public synchronized long saveReading(JSONObject reading) {
        if (reading == null) throw new IllegalArgumentException("Reading is required");
        SQLiteDatabase db = getWritableDatabase();
        JSONObject copy = parseObject(reading.toString());
        long id = copy.optLong("id", 0);
        long created = copy.optLong("createdAt", System.currentTimeMillis());
        if (created <= 0) created = System.currentTimeMillis();
        if (id > 0) {
            JSONObject old = getReading(id);
            if (old != null) {
                created = old.optLong("createdAt", created);
                java.util.Iterator<String> fields = copy.keys();
                while (fields.hasNext()) { String field = fields.next(); put(old, field, copy.opt(field)); }
                copy = old;
            }
        }
        put(copy, "createdAt", created);
        put(copy, "evaluated", hasEvaluation(copy));
        ContentValues v = new ContentValues();
        if (id > 0) v.put("id", id);
        v.put("created_at", created); v.put("payload", copy.toString());
        long saved = db.insertWithOnConflict("readings", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        if (saved <= 0) throw new IllegalStateException("Unable to save reading");
        put(copy, "id", saved);
        v.clear(); v.put("payload", copy.toString());
        db.update("readings", v, "id=?", new String[]{String.valueOf(saved)});
        put(reading, "id", saved); put(reading, "createdAt", created); put(reading, "evaluated", hasEvaluation(copy));
        return saved;
    }

    public synchronized JSONArray readingHistory() {
        return readingHistoryFull();
    }

    public synchronized JSONObject getReading(long id) {
        try (Cursor c = getReadableDatabase().query("readings", new String[]{"id", "created_at", "payload"}, "id=?", new String[]{String.valueOf(id)}, null, null, null)) {
            if (!c.moveToFirst()) return null;
            JSONObject item = parseObject(c.getString(2));
            put(item, "id", c.getLong(0)); put(item, "createdAt", c.getLong(1)); put(item, "evaluated", hasEvaluation(item));
            return item;
        }
    }

    private JSONArray readingHistoryFull() {
        JSONArray result = new JSONArray();
        try (Cursor c = getReadableDatabase().query("readings", new String[]{"id", "created_at", "payload"}, null, null, null, null, "created_at DESC,id DESC")) {
            while (c.moveToNext()) {
                JSONObject item = parseObject(c.getString(2));
                put(item, "id", c.getLong(0)); put(item, "createdAt", c.getLong(1)); put(item, "evaluated", hasEvaluation(item));
                result.put(item);
            }
        }
        return result;
    }

    private JSONObject requireWord(int id) {
        JSONObject word = getWord(id);
        if (word == null) throw new IllegalArgumentException("Unknown word");
        return word;
    }

    private String optionalAsset(String name) {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16 * 1024]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (java.io.FileNotFoundException absent) { return "{}"; }
        catch (Exception e) { throw new IllegalStateException("无法读取词义补充资源", e); }
    }

    private void loadSupplements(String json) {
        meaningSupplements.clear(); JSONArray entries;
        try {
            if (json.trim().startsWith("[")) entries = new JSONArray(json);
            else {
                JSONObject root = parseObject(json); entries = root.optJSONArray("entries");
                if (entries == null) entries = root.optJSONArray("supplements");
            }
        } catch (JSONException e) { throw new IllegalStateException("词义补充文件格式无效", e); }
        if (entries == null) return;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i); int id = entry == null ? 0 : entry.optInt("id", entry.optInt("wordId", 0));
            if (entry == null || id <= 0 || meaningSupplements.put(id, entry) != null) throw new IllegalStateException("词义补充编号无效或重复");
        }
    }

    private static String fingerprint(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b & 255)); return hex.toString();
        } catch (Exception e) { throw new IllegalStateException("Unable to fingerprint dictionary", e); }
    }

    private static String canonicalMeaning(String original, String corrected, String addition) {
        String base = corrected == null || corrected.trim().isEmpty() ? original : corrected.trim();
        String add = addition == null ? "" : addition.trim();
        return add.isEmpty() || base.contains(add) ? base
                : base + "\n" + (add.startsWith("六级常用义补充：") ? add : "六级常用义补充：\n" + add);
    }

    /** Safe in-place update: never REPLACE words, which would cascade-delete progress. */
    private static void syncDictionary(SQLiteDatabase db, JSONArray words, String fingerprint) {
        db.beginTransaction();
        try {
            for (int i = 0; i < words.length(); i++) {
                JSONObject word = words.optJSONObject(i); int id = word.optInt("id", i + 1);
                ContentValues v = dictionaryValues(word, id);
                boolean exists = false; boolean semanticChange = false; boolean anyChange = false;
                try (Cursor c = db.query("words", null, "id=?", new String[]{String.valueOf(id)}, null, null, null)) {
                    if (c.moveToFirst()) {
                        exists = true;
                        for (String field : new String[]{"word", "meaning", "ipa", "pos", "group_id", "group_label", "relations", "forms", "source"}) {
                            boolean changed = !v.getAsString(field).equals(c.getString(c.getColumnIndexOrThrow(field)));
                            anyChange |= changed;
                            if (changed && ("word".equals(field) || "meaning".equals(field) || "pos".equals(field))) semanticChange = true;
                        }
                    }
                }
                if (!exists) db.insertOrThrow("words", null, v);
                else if (anyChange) db.update("words", v, "id=?", new String[]{String.valueOf(id)});
                if (semanticChange) {
                    db.delete("learning_packs", "word_id=?", new String[]{String.valueOf(id)});
                    db.delete("choice_cache", "word_id=?", new String[]{String.valueOf(id)});
                }
            }
            putMeta(db, "dictionaryAssetFingerprint", fingerprint); putMeta(db, "vocabularyVersion", "1.1");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static ContentValues dictionaryValues(JSONObject word, int id) {
        ContentValues v = new ContentValues(); v.put("id", id); v.put("word", word.optString("word").trim()); v.put("meaning", word.optString("meaning").trim());
        v.put("ipa", word.optString("ipa", "")); v.put("pos", word.optString("pos", ""));
        String groupId = first(word, "groupId", "group_id"); String groupLabel = first(word, "groupLabel", "group_label");
        v.put("group_id", groupId.isEmpty() ? "ungrouped" : groupId); v.put("group_label", groupLabel.isEmpty() ? "未分组" : groupLabel);
        v.put("relations", valueString(word, "relations")); v.put("forms", valueString(word, "forms")); v.put("source", valueString(word, "source")); return v;
    }

    private static long nextCacheTimestamp(SQLiteDatabase db, long now) {
        long stored = 0;
        try { stored = Long.parseLong(metaValue(db, "cacheTimestamp")); } catch (NumberFormatException ignored) {}
        long maximum = scalar(db, "SELECT COALESCE(MAX(cached_at),0) FROM (SELECT cached_at FROM learning_packs UNION ALL SELECT cached_at FROM choice_cache)", null);
        long last = Math.max(stored, maximum);
        long next = newerCacheTimestamp(now, last); putMeta(db, "cacheTimestamp", String.valueOf(next)); return next;
    }

    private static long newerCacheTimestamp(long now, long last) {
        if (last == Long.MAX_VALUE) throw new IllegalStateException("缓存时间已超出范围");
        return Math.max(now, Math.max(0L, last) + 1L);
    }

    private static String metaValue(SQLiteDatabase db, String key) {
        try (Cursor c = db.query("meta", new String[]{"value"}, "key=?", new String[]{key}, null, null, null)) { return c.moveToFirst() ? c.getString(0) : ""; }
    }

    private JSONObject cachedPack(int wordId) {
        try (Cursor c = getReadableDatabase().query("learning_packs", new String[]{"payload"}, "word_id=?", new String[]{String.valueOf(wordId)}, null, null, null)) {
            return c.moveToFirst() ? parseObject(c.getString(0)) : null;
        }
    }

    private JSONObject offlineLesson(int wordId) {
        if (offlineLessons == null) {
            try (InputStream in = context.getAssets().open("lessons.json")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                JSONObject root = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
                offlineLessons = root.optJSONObject("lessons");
                if (offlineLessons == null) offlineLessons = root;
            } catch (Exception e) { throw new IllegalStateException("离线例句资源无法读取", e); }
        }
        JSONObject lesson = offlineLessons.optJSONObject(String.valueOf(wordId));
        if (lesson == null) throw new IllegalStateException("该单词的离线例句暂缺，请生成 AI 讲解。");
        return lesson;
    }

    private static int choiceTier(LearningScheduler.State s) {
        if (s.stage >= LearningScheduler.SPELLING || s.recognitionStreak >= 2) return 3;
        if (s.recognitionStreak >= 1 || (s.lapses == 0 && s.reviews >= 2)) return 2;
        return 1;
    }

    private static final class ChoiceCandidate {
        final int id; final String word; final String meaning; final int score;
        ChoiceCandidate(int id, String word, String meaning, int score) { this.id = id; this.word = word; this.meaning = meaning; this.score = score; }
    }

    private JSONObject localChoice(JSONObject target, int tier) {
        final int targetId = target.optInt("id");
        String fullMeaning = target.optString("meaning");
        String meaning = VocabularyRules.choiceMeaning(fullMeaning);
        String word = target.optString("word");
        String pos = target.optString("pos");
        String group = target.optString("groupId");
        Set<String> relatedWords = VocabularyRules.relatedWords(target.optString("relations"), false);
        ArrayList<ChoiceCandidate> candidates = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,word,meaning,pos,group_id,relations FROM words WHERE id<>? ORDER BY id", new String[]{String.valueOf(targetId)})) {
            while (c.moveToNext()) {
                String otherMeaning = c.getString(2);
                if (meaningsOverlap(fullMeaning, otherMeaning) || relatedWords.contains(c.getString(1).toLowerCase(Locale.ROOT))
                        || VocabularyRules.relatedWords(c.getString(5), false).contains(word.toLowerCase(Locale.ROOT))) continue;
                boolean samePos = pos.equals(c.getString(3));
                boolean sameGroup = group.equals(c.getString(4));
                int score = (tier == 1 ? (samePos ? 0 : 20) : (samePos ? 30 : 0)) + (sameGroup ? 0 : 10);
                // Stable dispersion within a score band prevents every word sharing
                // the same three distractors while keeping cached questions reproducible.
                score = score * 1000 + Math.floorMod(c.getInt(0) * 1103 + targetId * 97 + tier * 31, 997);
                candidates.add(new ChoiceCandidate(c.getInt(0), c.getString(1), VocabularyRules.choiceMeaning(otherMeaning), score));
            }
        }
        Collections.sort(candidates, Comparator.comparingInt((ChoiceCandidate c) -> c.score).reversed());
        ArrayList<String> wrong = new ArrayList<>(); Set<String> unique = new HashSet<>(); unique.add(normalMeaning(meaning));
        for (ChoiceCandidate c : candidates) {
            boolean overlap = false;
            for (String chosen : wrong) if (meaningsOverlap(chosen, c.meaning)) { overlap = true; break; }
            if (!c.meaning.isEmpty() && !overlap && unique.add(normalMeaning(c.meaning))) wrong.add(c.meaning);
            if (wrong.size() == 3) break;
        }
        if (wrong.size() < 3) throw new IllegalStateException("无法找到三个无歧义的本地干扰义项");
        int answerIndex = choiceAnswerIndex(targetId, tier);
        JSONArray options = new JSONArray(); int wrongIndex = 0;
        for (int i = 0; i < 4; i++) {
            JSONObject option = new JSONObject(); put(option, "id", String.valueOf((char) ('A' + i)));
            put(option, "text", i == answerIndex ? meaning : wrong.get(wrongIndex++)); options.put(option);
        }
        JSONObject choice = new JSONObject();
        put(choice, "wordId", targetId); put(choice, "word", word); put(choice, "level", tier); put(choice, "tier", tier);
        put(choice, "question", VocabularyRules.CHOICE_QUESTION);
        put(choice, "policyVersion", VocabularyRules.POLICY_VERSION);
        put(choice, "options", options); put(choice, "answer", String.valueOf((char) ('A' + answerIndex)));
        put(choice, "explanation", "词义：" + meaning);
        return choice;
    }

    private static int choiceAnswerIndex(int wordId, int tier) { return Math.floorMod(wordId + tier - 1, 4); }

    private static String normalMeaning(String text) { return text == null ? "" : text.replaceAll("\\s+", "").trim(); }

    /** Gist-equivalent and lexically overlapping meanings are excluded from distractors. */
    private static boolean meaningsOverlap(String a, String b) {
        return VocabularyRules.meaningsOverlap(a, b);
    }

    private JSONObject validatePack(JSONObject supplied, JSONObject word) {
        JSONObject pack = parseObject(supplied.toString());
        int id = word.optInt("id");
        boolean stale = pack.has("word") && !word.optString("word").equals(pack.optString("word"));
        JSONObject example = pack.optJSONObject("example");
        if (example == null || example.optString("en").trim().isEmpty() || example.optString("zh").trim().isEmpty()
                || example.optString("en").length() > 8000 || example.optString("zh").length() > 8000) throw new IllegalArgumentException("学习包例句缺失或过长");
        JSONArray tiers = pack.optJSONArray("tiers");
        if (tiers == null || tiers.length() != 3) throw new IllegalArgumentException("学习包必须包含三个题目难度");
        Set<Integer> levels = new HashSet<>(); JSONArray validated = new JSONArray();
        for (int i = 0; i < tiers.length(); i++) {
            JSONObject choice = tiers.optJSONObject(i);
            int level = choice == null ? 0 : choice.optInt("level", choice.optInt("tier", 0));
            if (level < 1 || level > 3 || !levels.add(level)) throw new IllegalArgumentException("题目难度重复或无效");
            try { validated.put(validateChoice(choice, word, level)); }
            catch (StaleDictionaryCacheException dictionaryChanged) { stale = true; }
        }
        if (stale) throw new StaleDictionaryCacheException("学习包的词义已更新，请重新生成");
        put(pack, "word_id", id); put(pack, "word", word.optString("word"));
        put(pack, "policyVersion", VocabularyRules.POLICY_VERSION);
        put(pack, "meanings", new JSONArray().put(word.optString("meaning"))); put(pack, "tiers", validated);
        return pack;
    }

    private JSONObject validateChoice(JSONObject supplied, JSONObject word, int tier) {
        if (supplied == null) throw new IllegalArgumentException("选项缓存无效");
        JSONObject choice = parseObject(supplied.toString());
        JSONArray options = choice.optJSONArray("options");
        String answer = choice.optString("answer", choice.optString("correctId", ""));
        if (options == null || options.length() != 4 || !answer.matches("[A-D]")) throw new IllegalArgumentException("题目须有四项及唯一答案");
        Set<String> texts = new HashSet<>(); Set<String> letters = new HashSet<>();
        String correct = null;
        for (int i = 0; i < 4; i++) {
            JSONObject option = options.optJSONObject(i);
            String letter = option == null ? "" : option.optString("id");
            String text = option == null ? "" : option.optString("text").trim();
            if (!letter.matches("[A-D]") || !letters.add(letter) || text.isEmpty() || text.length() > 4000 || !texts.add(normalMeaning(text))) throw new IllegalArgumentException("四个选项须唯一且非空");
            if (letter.equals(answer)) correct = text;
        }
        String expected = VocabularyRules.choiceMeaning(word.optString("meaning"));
        if (!normalMeaning(expected).equals(normalMeaning(correct))) throw new StaleDictionaryCacheException("词义练习已更新，请重新准备题目");
        ArrayList<String> distractors = new ArrayList<>();
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i); String text = option.optString("text");
            if (text.contains("\n") || text.matches(".*(?:补充词典|常用义补充).*") || text.length() > 160) throw new IllegalArgumentException("选项应为简短词义");
            if (answer.equals(option.optString("id"))) continue;
            boolean overlap = meaningsOverlap(word.optString("meaning"), text);
            for (String other : distractors) if (meaningsOverlap(other, text)) overlap = true;
            if (overlap) throw new IllegalArgumentException("选项意思过于接近，请重新准备题目");
            distractors.add(text);
        }
        JSONArray ordered = new JSONArray();
        for (char letter = 'A'; letter <= 'D'; letter++) for (int i = 0; i < 4; i++) {
            JSONObject option = options.optJSONObject(i);
            if (String.valueOf(letter).equals(option.optString("id"))) ordered.put(option);
        }
        put(choice, "options", ordered);
        put(choice, "level", tier); put(choice, "tier", tier); put(choice, "wordId", word.optInt("id")); put(choice, "word", word.optString("word"));
        put(choice, "question", VocabularyRules.CHOICE_QUESTION);
        put(choice, "policyVersion", VocabularyRules.POLICY_VERSION);
        return choice;
    }

    private static void saveChoice(SQLiteDatabase db, int wordId, int tier, JSONObject choice, String source, long cachedAt) {
        ContentValues v = new ContentValues(); v.put("word_id", wordId); v.put("tier", tier); v.put("payload", choice.toString()); v.put("source", source); v.put("cached_at", cachedAt);
        db.insertWithOnConflict("choice_cache", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private JSONArray cachedRows(String table) {
        JSONArray rows = new JSONArray();
        try (Cursor c = getReadableDatabase().query(table, null, null, null, null, null, "word_id")) {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject(); put(row, "wordId", c.getInt(c.getColumnIndexOrThrow("word_id")));
                if ("choice_cache".equals(table)) put(row, "tier", c.getInt(c.getColumnIndexOrThrow("tier")));
                put(row, "payload", parseObject(c.getString(c.getColumnIndexOrThrow("payload"))));
                put(row, "cachedAt", c.getLong(c.getColumnIndexOrThrow("cached_at"))); put(row, "source", c.getString(c.getColumnIndexOrThrow("source"))); rows.put(row);
            }
        }
        return rows;
    }

    /** Dictionary corrections invalidate derived material, never the learner's progress. */
    private static final class StaleDictionaryCacheException extends IllegalArgumentException {
        StaleDictionaryCacheException(String message) { super(message); }
    }

    private Set<Integer> validateCachedBackup(JSONArray packs, JSONArray choices, Set<Integer> validIds) {
        if (packs.length() > 50000 || choices.length() > 150000) throw new IllegalArgumentException("备份学习缓存过大");
        Set<Integer> seenPacks = new HashSet<>(); Set<String> seenChoices = new HashSet<>();
        Set<Integer> staleWords = new HashSet<>();
        for (int i = 0; i < packs.length(); i++) {
            JSONObject row = packs.optJSONObject(i);
            int id = row == null ? 0 : row.optInt("wordId");
            if (row == null || !validIds.contains(id) || !seenPacks.add(id) || row.optLong("cachedAt", -1) < 0 || !"ai".equals(row.optString("source"))) throw new IllegalArgumentException("备份学习包无效");
            JSONObject payload = row.optJSONObject("payload"); if (payload == null) throw new IllegalArgumentException("备份学习包内容缺失");
            if (payload.optInt("policyVersion") != VocabularyRules.POLICY_VERSION) { staleWords.add(id); continue; }
            try { put(row, "payload", validatePack(payload, requireWord(id))); }
            catch (StaleDictionaryCacheException stale) { staleWords.add(id); }
        }
        for (int i = 0; i < choices.length(); i++) {
            JSONObject row = choices.optJSONObject(i);
            int id = row == null ? 0 : row.optInt("wordId"); int tier = row == null ? 0 : row.optInt("tier");
            String source = row == null ? "" : row.optString("source");
            if (row == null || !validIds.contains(id) || tier < 1 || tier > 3 || !seenChoices.add(id + ":" + tier) || row.optLong("cachedAt", -1) < 0 || (!"ai".equals(source) && !"local".equals(source))) throw new IllegalArgumentException("备份选项缓存无效");
            JSONObject payload = row.optJSONObject("payload");
            if (payload == null) throw new IllegalArgumentException("备份选项内容缺失");
            if (payload.optInt("policyVersion") != VocabularyRules.POLICY_VERSION) { staleWords.add(id); continue; }
            try { put(row, "payload", validateChoice(row.optJSONObject("payload"), requireWord(id), tier)); }
            catch (StaleDictionaryCacheException stale) { staleWords.add(id); }
        }
        return staleWords;
    }

    private void restoreCachedRows(String table, JSONArray rows, Set<Integer> staleWords) {
        SQLiteDatabase db = getWritableDatabase();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (staleWords.contains(row.optInt("wordId"))) continue;
            ContentValues v = new ContentValues();
            v.put("word_id", row.optInt("wordId")); if ("choice_cache".equals(table)) v.put("tier", row.optInt("tier"));
            v.put("payload", row.optJSONObject("payload").toString()); v.put("cached_at", row.optLong("cachedAt")); v.put("source", row.optString("source")); db.insertOrThrow(table, null, v);
        }
    }

    private JSONObject wordJson(Cursor c, long now) {
        int id = c.getInt(c.getColumnIndexOrThrow("id"));
        LearningScheduler.State s = getState(id);
        JSONObject w = new JSONObject();
        put(w, "id", id);
        put(w, "word", c.getString(c.getColumnIndexOrThrow("word")));
        put(w, "meaning", c.getString(c.getColumnIndexOrThrow("meaning")));
        put(w, "ipa", c.getString(c.getColumnIndexOrThrow("ipa")));
        put(w, "pos", c.getString(c.getColumnIndexOrThrow("pos")));
        put(w, "groupId", c.getString(c.getColumnIndexOrThrow("group_id")));
        put(w, "groupLabel", c.getString(c.getColumnIndexOrThrow("group_label")));
        put(w, "relations", c.getString(c.getColumnIndexOrThrow("relations")));
        put(w, "forms", c.getString(c.getColumnIndexOrThrow("forms")));
        put(w, "source", c.getString(c.getColumnIndexOrThrow("source")));
        JSONObject supplement = meaningSupplements.get(id);
        if (supplement != null && supplement.has("originalMeaning")) put(w, "originalMeaning", supplement.optString("originalMeaning"));
        put(w, "state", stateJson(s, now));
        put(w, "stage", s.stage); put(w, "isNew", s.reviews == 0);
        put(w, "lessonAt", s.lessonAt); put(w, "needsLesson", s.lessonAt <= 0);
        put(w, "due", s.reviews > 0 && s.dueAt <= now);
        put(w, "retention", LearningScheduler.retentionEstimate(s, now));
        return w;
    }

    private static JSONObject stateJson(LearningScheduler.State s, long now) {
        JSONObject o = new JSONObject();
        put(o, "wordId", s.wordId); put(o, "stage", s.stage);
        put(o, "recognitionStreak", s.recognitionStreak); put(o, "spellingStreak", s.spellingStreak);
        put(o, "reviews", s.reviews); put(o, "lapses", s.lapses); put(o, "ease", s.ease);
        put(o, "intervalDays", s.intervalDays); put(o, "dueAt", s.dueAt); put(o, "lastReviewed", s.lastReviewed);
        put(o, "introducedAt", s.introducedAt); put(o, "recognitionLastAt", s.recognitionLastAt); put(o, "spellingLastAt", s.spellingLastAt);
        put(o, "lessonAt", s.lessonAt);
        put(o, "retention", LearningScheduler.retentionEstimate(s, now));
        return o;
    }

    private static ContentValues stateValues(LearningScheduler.State s) {
        ContentValues v = new ContentValues();
        v.put("word_id", s.wordId); v.put("stage", s.stage);
        v.put("recognition_streak", s.recognitionStreak); v.put("spelling_streak", s.spellingStreak);
        v.put("reviews", s.reviews); v.put("lapses", s.lapses); v.put("ease", s.ease);
        v.put("interval_days", s.intervalDays); v.put("due_at", s.dueAt); v.put("last_reviewed", s.lastReviewed);
        v.put("introduced_at", s.introducedAt); v.put("recognition_last_at", s.recognitionLastAt); v.put("spelling_last_at", s.spellingLastAt);
        v.put("lesson_at", s.lessonAt);
        return v;
    }

    private static LearningScheduler.State readState(Cursor c) {
        LearningScheduler.State s = new LearningScheduler.State(c.getInt(0));
        s.stage = c.getInt(1); s.recognitionStreak = c.getInt(2); s.spellingStreak = c.getInt(3);
        s.reviews = c.getInt(4); s.lapses = c.getInt(5); s.ease = c.getDouble(6); s.intervalDays = c.getDouble(7);
        s.dueAt = c.getLong(8); s.lastReviewed = c.getLong(9); s.introducedAt = c.getLong(10);
        s.recognitionLastAt = c.getLong(11); s.spellingLastAt = c.getLong(12);
        s.lessonAt = c.getLong(13);
        return s;
    }

    private static LearningScheduler.State stateFromJson(JSONObject row) {
        LearningScheduler.State s = new LearningScheduler.State(row.optInt("wordId"));
        s.stage = row.optInt("stage", -1); s.recognitionStreak = row.optInt("recognitionStreak"); s.spellingStreak = row.optInt("spellingStreak");
        s.reviews = row.optInt("reviews"); s.lapses = row.optInt("lapses"); s.ease = row.optDouble("ease", 2.5); s.intervalDays = row.optDouble("intervalDays", 0);
        s.dueAt = row.optLong("dueAt"); s.lastReviewed = row.optLong("lastReviewed"); s.introducedAt = row.optLong("introducedAt");
        s.recognitionLastAt = row.optLong("recognitionLastAt"); s.spellingLastAt = row.optLong("spellingLastAt");
        s.lessonAt = row.optLong("lessonAt", s.reviews > 0 ? Math.max(1L, s.introducedAt > 0 ? s.introducedAt : s.lastReviewed) : 0L);
        return s;
    }

    private static void validateState(LearningScheduler.State s) {
        if (s.stage < 0 || s.stage > 2 || s.recognitionStreak < 0 || s.spellingStreak < 0 || s.reviews < 0 || s.lapses < 0
                || Double.isNaN(s.ease) || Double.isInfinite(s.ease) || s.ease < 1.3 || s.ease > 3.2
                || !finiteInterval(s.intervalDays) || s.lapses > s.reviews || s.recognitionStreak > s.reviews || s.spellingStreak > s.reviews
                || s.dueAt < 0 || s.lastReviewed < 0 || s.introducedAt < 0 || s.recognitionLastAt < 0 || s.spellingLastAt < 0 || s.lessonAt < 0
                || (s.reviews > 0 && s.lessonAt <= 0)) throw new IllegalArgumentException("备份学习状态无效");
    }

    private static boolean finiteInterval(double interval) {
        return !Double.isNaN(interval) && !Double.isInfinite(interval) && interval >= 0.0 && interval <= 3650.0;
    }

    private static int newToday(SQLiteDatabase db, long now) {
        long[] day = todayBounds(now);
        int count = (int) scalar(db, "SELECT COUNT(*) FROM states WHERE introduced_at>=? AND introduced_at<? AND (reviews>0 OR lesson_at>0)", new String[]{String.valueOf(day[0]), String.valueOf(day[1])});
        // These values are durable; the timestamp query also survives imports/time-zone changes.
        putMeta(db, "dailyDay", new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(now)));
        putMeta(db, "dailyNewCount", String.valueOf(count));
        return count;
    }

    private static long[] todayBounds(long now) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        long start = c.getTimeInMillis(); c.add(Calendar.DAY_OF_MONTH, 1);
        return new long[]{start, c.getTimeInMillis()};
    }

    private static int studyStreak(SQLiteDatabase db, long todayStart) {
        Set<String> days = new HashSet<>();
        try (Cursor c = db.rawQuery("SELECT DISTINCT strftime('%Y-%m-%d', reviewed_at / 1000, 'unixepoch', 'localtime') FROM learning_log", null)) {
            while (c.moveToNext()) days.add(c.getString(0));
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(todayStart);
        if (!days.contains(f.format(c.getTime()))) c.add(Calendar.DAY_OF_MONTH, -1);
        int streak = 0;
        while (days.contains(f.format(c.getTime()))) { streak++; c.add(Calendar.DAY_OF_MONTH, -1); }
        return streak;
    }

    private static long scalar(SQLiteDatabase db, String sql, String[] args) {
        try (Cursor c = db.rawQuery(sql, args)) { return c.moveToFirst() ? c.getLong(0) : 0; }
    }

    private static void putMeta(SQLiteDatabase db, String key, String value) {
        ContentValues v = new ContentValues(); v.put("key", key); v.put("value", value);
        db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static boolean hasEvaluation(JSONObject reading) {
        return (reading.has("evaluation") && !reading.isNull("evaluation"))
                || (reading.has("feedback") && !reading.isNull("feedback"))
                || reading.optBoolean("evaluated", false);
    }

    private static JSONObject parseObject(String json) {
        try { return new JSONObject(json == null ? "{}" : json); }
        catch (JSONException e) { throw new IllegalArgumentException("Invalid stored JSON", e); }
    }

    private static String first(JSONObject o, String... names) {
        for (String name : names) { String v = o.optString(name, "").trim(); if (!v.isEmpty() && !"null".equals(v)) return v; }
        return "";
    }

    private static String valueString(JSONObject o, String name) {
        Object value = o.opt(name); return value == null || value == JSONObject.NULL ? "" : value.toString();
    }

    private static int boundedLimit(int value, int maximum) { return Math.max(1, Math.min(maximum, value <= 0 ? 100 : value)); }

    private static void checkMode(int mode) { if (mode < 0 || mode > 2) throw new IllegalArgumentException("Invalid learning mode"); }

    private static void put(JSONObject o, String key, Object value) {
        try { o.put(key, value == null ? JSONObject.NULL : value); }
        catch (JSONException e) { throw new IllegalArgumentException("Invalid JSON value for " + key, e); }
    }
}
