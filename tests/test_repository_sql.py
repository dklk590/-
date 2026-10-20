"""Checks production SQL on real SQLite; Android UI tests require a device/emulator."""
from pathlib import Path
import json
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/com/wordweave/app/WordRepository.java").read_text()
CREATE_BODY = re.search(r'void onCreate\([^)]*\) \{(.*?)\n    \}', SOURCE, re.S).group(1)
CACHE_BODY = re.search(r'void createLearningTables\([^)]*\) \{(.*?)\n    \}', SOURCE, re.S).group(1)
UPGRADE_BODY = re.search(r'void onUpgrade\([^)]*\) \{(.*?)\n    \}', SOURCE, re.S).group(1)
CONTEXT_BODY = re.search(r'void createContextTables\([^)]*\) \{(.*?)\n    \}', SOURCE, re.S).group(1)
DDL = re.findall(r'db\.execSQL\("([^"\n]*)"\)', CREATE_BODY + CACHE_BODY + CONTEXT_BODY)
MIGRATIONS = re.findall(r'db\.execSQL\("([^"\n]*)"\)', UPGRADE_BODY)
DUE_QUERY = re.findall(r'String sql = "([^"\n]*)";', SOURCE)[1]
NEW_QUERY = re.search(r'String fresh = "([^"\n]*)";', SOURCE).group(1)
PENDING_QUERY = re.search(r'String pending = "([^"\n]*)";', SOURCE).group(1)
FUTURE_QUERY = re.search(r'StringBuilder sql = new StringBuilder\("([^"\n]*)"\)', SOURCE).group(1)
UNCACHED_FILTER = re.search(r'if \(uncachedOnly\) sql.append\("([^"\n]*)"\)', SOURCE).group(1)
NEW_TODAY_QUERY = re.search(r'"(SELECT COUNT\(\*\) FROM states WHERE introduced_at[^"\n]*)"', SOURCE).group(1)


class RepositorySqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys=ON")
        for statement in DDL:
            self.db.execute(statement)
        self.db.executemany("INSERT INTO groups_data VALUES(?,?,?,?)", [
            ("a", "形近词群", 1, "{}"), ("b", "近义词群", 0, "{}")])
        for number in range(1, 9):
            group = "a" if number < 5 else "b"
            word = "literal%word" if number == 8 else f"word{number}"
            self.db.execute("INSERT INTO words VALUES(?,?,?,?,?,?,?,?,?,?)",
                            (number, word, "中文多义项", "IPA", "n.", group, group, "同根词", "", "PDF"))
        # Two overdue recognition words, one future word and one spelling word.
        for word_id, stage, due in [(1, 0, 900), (2, 0, 800), (3, 0, 2000), (5, 1, 700)]:
            self.db.execute("INSERT INTO states VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            (word_id, stage, 0, 0, 1, 0, 2.5, 1.0, due, 600, 600, 0, 0, 600))
        self.db.commit()

    def test_context_upgrade_keeps_existing_progress(self):
        before = self.db.execute("SELECT * FROM states ORDER BY word_id").fetchall()
        self.db.execute("DROP TABLE context_sessions")
        for statement in re.findall(r'db\.execSQL\("([^"\n]*)"\)', CONTEXT_BODY):
            self.db.execute(statement)
        self.assertEqual(before, self.db.execute("SELECT * FROM states ORDER BY word_id").fetchall())
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM context_sessions").fetchone()[0], 0)

    def test_context_grade_and_lesson_payload_rollback_together(self):
        payload = {"id": 1, "targetWords": [{"id": 1}], "grades": {}, "hints": {"1": True},
                   "viewed": {"s1": 1}, "unknownWords": [{"surface": "word1", "sentenceId": "s1"}]}
        self.db.execute("INSERT INTO context_sessions VALUES(?,?,?,?)", (1, 600, "context-v1:[1]", json.dumps(payload)))
        self.db.execute("CREATE TRIGGER reject_context_update BEFORE UPDATE ON context_sessions BEGIN SELECT RAISE(ABORT,'disk-write-failure'); END")
        self.db.commit()
        before = self.db.execute("SELECT reviews,due_at FROM states WHERE word_id=1").fetchone()
        self.db.execute("BEGIN")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE states SET reviews=reviews+1,due_at=2000 WHERE word_id=1")
            self.db.execute("INSERT INTO learning_log(word_id,grade,mode,reviewed_at,interval_days,stage) VALUES(1,1,0,1000,1,0)")
            payload["grades"]["1"] = 1
            self.db.execute("UPDATE context_sessions SET payload=? WHERE id=1", (json.dumps(payload),))
        self.db.rollback()
        self.assertEqual(before, self.db.execute("SELECT reviews,due_at FROM states WHERE word_id=1").fetchone())
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM learning_log").fetchone()[0], 0)
        saved = json.loads(self.db.execute("SELECT payload FROM context_sessions WHERE id=1").fetchone()[0])
        self.assertEqual(saved["grades"], {})
        self.assertEqual(saved["hints"], {"1": True})
        self.assertEqual(saved["unknownWords"][0]["surface"], "word1")

    def tearDown(self):
        self.db.close()

    def test_due_candidates_only_use_eligible_stage(self):
        rows = self.db.execute(DUE_QUERY, (0, 0, 1000, 20)).fetchall()
        self.assertEqual([r[0] for r in rows], [2, 1])
        rows = self.db.execute(DUE_QUERY, (1, 1, 1000, 20)).fetchall()
        self.assertEqual([r[0] for r in rows], [5])

    def test_new_words_follow_group_then_family_order(self):
        rows = self.db.execute(NEW_QUERY, (3,)).fetchall()
        self.assertEqual([r[0] for r in rows], [6, 7, 8])
        self.assertEqual([r[0] for r in self.db.execute(NEW_QUERY, (10,))], [6, 7, 8, 4])

    def test_literal_query_wildcard_is_escaped(self):
        rows = self.db.execute("SELECT id FROM words WHERE word LIKE ? ESCAPE '\\'", ("%literal\\%word%",)).fetchall()
        self.assertEqual(rows, [(8,)])

    def test_daily_count_uses_introduction_not_repeat(self):
        sql = NEW_TODAY_QUERY
        self.assertEqual(self.db.execute(sql, (0, 1000)).fetchone()[0], 4)
        self.db.execute("UPDATE states SET reviews=reviews+1,last_reviewed=1500 WHERE word_id=1")
        self.assertEqual(self.db.execute(sql, (1000, 2000)).fetchone()[0], 0)

    def test_learned_filter_runs_before_limit(self):
        clause = re.search(r'if \(learnedOnly\) where.append\("([^"\n]*)"\)', SOURCE).group(1)
        rows = self.db.execute("SELECT id FROM words WHERE 1=1" + clause + " ORDER BY id ASC LIMIT ?", (2,)).fetchall()
        self.assertEqual(rows, [(1,), (2,)])
        # A learned word beyond the initial unfiltered page must remain reachable.
        self.db.execute("UPDATE states SET reviews=0,lesson_at=0 WHERE word_id IN (1,2,3)")
        rows = self.db.execute("SELECT id FROM words WHERE 1=1" + clause + " ORDER BY id ASC LIMIT ?", (2,)).fetchall()
        self.assertEqual(rows, [(5,)])
        self.db.execute("INSERT INTO states VALUES(8,0,0,0,0,0,2.5,0,0,0,750,0,0,750)")
        rows = self.db.execute("SELECT id FROM words WHERE 1=1" + clause + " ORDER BY id ASC LIMIT ?", (10,)).fetchall()
        self.assertEqual(rows, [(5,), (8,)])

    def test_reading_hint_and_grade_ledgers_survive_database_round_trip(self):
        # Real SQLite persistence of the production reading payload shape. This
        # does not execute Android Java; device tests cover the Java bridge.
        payload = {
            "id": 7, "createdAt": 600, "text": "word1 and word2 appear beside word3.",
            "targetWords": [{"id": i, "word": f"word{i}"} for i in (1, 2, 3)],
            "evaluation": {"score": 90}, "reviewHints": {"1": True},
            "reviewGrades": {"1": 1, "2": 3}, "reviewGradeTimes": {"1": 900, "2": 950},
        }
        self.db.execute("INSERT INTO readings(id,created_at,payload) VALUES(?,?,?)", (7, 600, json.dumps(payload)))
        exported = json.loads(self.db.execute("SELECT payload FROM readings WHERE id=?", (7,)).fetchone()[0])
        self.db.execute("DELETE FROM readings")
        self.db.execute("INSERT INTO readings(id,created_at,payload) VALUES(?,?,?)", (exported["id"], exported["createdAt"], json.dumps(exported)))
        restored = json.loads(self.db.execute("SELECT payload FROM readings WHERE id=?", (7,)).fetchone()[0])
        self.assertEqual(restored["reviewGrades"], {"1": 1, "2": 3})
        self.assertEqual(restored["reviewHints"], {"1": True})
        self.assertEqual(restored["reviewGradeTimes"], {"1": 900, "2": 950})
        self.assertEqual([w["id"] for w in restored["targetWords"]], [1, 2, 3])

    def test_failed_reading_ledger_write_rolls_back_schedule_and_log(self):
        payload = {"id": 7, "createdAt": 600, "targetWords": [{"id": 1}], "evaluation": {"score": 90}}
        self.db.execute("INSERT INTO readings(id,created_at,payload) VALUES(?,?,?)", (7, 600, json.dumps(payload)))
        self.db.execute("CREATE TRIGGER reject_reading_update BEFORE UPDATE ON readings BEGIN SELECT RAISE(ABORT,'disk-write-failure'); END")
        self.db.commit()
        before = self.db.execute("SELECT reviews,due_at FROM states WHERE word_id=1").fetchone()
        self.db.execute("BEGIN")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE states SET reviews=reviews+1,due_at=2000 WHERE word_id=1")
            self.db.execute("INSERT INTO learning_log(word_id,grade,mode,reviewed_at,interval_days,stage) VALUES(1,3,2,1000,1,0)")
            payload["reviewGrades"] = {"1": 3}
            self.db.execute("UPDATE readings SET payload=? WHERE id=7", (json.dumps(payload),))
        self.db.rollback()
        self.assertEqual(self.db.execute("SELECT reviews,due_at FROM states WHERE word_id=1").fetchone(), before)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM learning_log").fetchone()[0], 0)
        stored = json.loads(self.db.execute("SELECT payload FROM readings WHERE id=7").fetchone()[0])
        self.assertNotIn("reviewGrades", stored)

    def test_foreign_keys_and_rollback_preserve_progress(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO states VALUES(99,0,0,0,1,0,2.5,1,0,0,0,0,0,0)")
        self.db.rollback()
        count = self.db.execute("SELECT COUNT(*) FROM states").fetchone()[0]
        self.db.execute("BEGIN")
        self.db.execute("DELETE FROM states")
        self.db.rollback()
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM states").fetchone()[0], count)

    def test_mixed_queue_keeps_each_word_stage_and_excludes_reading(self):
        self.db.execute("UPDATE states SET stage=2 WHERE word_id=1")
        rows = self.db.execute(DUE_QUERY, (-1, -1, 1000, 20)).fetchall()
        self.assertEqual([r[0] for r in rows], [5, 2])

    def test_confirmed_lesson_counts_introduction_not_review(self):
        self.db.execute("INSERT INTO states VALUES(6,0,0,0,0,0,2.5,0,0,0,750,0,0,750)")
        self.assertEqual(self.db.execute(NEW_TODAY_QUERY, (0, 1000)).fetchone()[0], 5)
        self.assertEqual(self.db.execute("SELECT SUM(reviews) FROM states").fetchone()[0], 4)
        pending = self.db.execute(PENDING_QUERY, (20,)).fetchall()
        self.assertEqual([r[0] for r in pending], [6])
        self.assertNotIn(6, [r[0] for r in self.db.execute(NEW_QUERY, (20,))])

    def test_future_prefetch_and_unintroduced_selectors_are_distinct(self):
        self.db.execute("INSERT INTO learning_packs VALUES(6,'{}',100,'ai')")
        for tier in (1, 2, 3):
            self.db.execute("INSERT INTO choice_cache VALUES(?,?,?,?,?)", (6, tier, "{}", 100, "ai"))
        suffix = " ORDER BY COALESCE(g.sort_order,999999),w.id LIMIT ?"
        ordinary = self.db.execute(FUTURE_QUERY + suffix, (20,)).fetchall()
        prefetch = self.db.execute(FUTURE_QUERY + UNCACHED_FILTER + suffix, (20,)).fetchall()
        self.assertIn(6, [r[0] for r in ordinary])
        self.assertNotIn(6, [r[0] for r in prefetch])
        excluded = self.db.execute(FUTURE_QUERY + " AND w.id NOT IN (?,?)" + suffix, (6, 7, 20)).fetchall()
        self.assertEqual([r[0] for r in excluded], [8, 4])

    def test_choice_cache_is_per_word_per_tier_and_persistent(self):
        for tier in (1, 2, 3):
            self.db.execute("INSERT INTO choice_cache VALUES(?,?,?,?,?)", (6, tier, '{"answer":"A"}', 100, "local"))
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM choice_cache WHERE word_id=6").fetchone()[0], 3)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO choice_cache VALUES(6,4,'{}',100,'local')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO choice_cache VALUES(6,1,'{}',100,'local')")

    def test_v1_migration_keeps_existing_progress_and_marks_old_lessons(self):
        legacy = sqlite3.connect(":memory:")
        for statement in DDL:
            if "learning_packs" in statement or "choice_cache" in statement:
                continue
            legacy.execute(statement.replace(",lesson_at INTEGER NOT NULL DEFAULT 0", ""))
        legacy.execute("INSERT INTO states VALUES(1,1,3,1,8,2,2.1,14,2000,700,600,500,650)")
        legacy.execute("INSERT INTO states VALUES(2,0,0,0,0,0,2.5,0,0,0,0,0,0)")
        before = legacy.execute("SELECT * FROM states WHERE word_id=1").fetchone()
        for statement in MIGRATIONS:
            legacy.execute(statement)
        for statement in re.findall(r'db\.execSQL\("([^"\n]*)"\)', CACHE_BODY):
            legacy.execute(statement)
        after = legacy.execute("SELECT * FROM states WHERE word_id=1").fetchone()
        self.assertEqual(after[:-1], before)
        self.assertEqual(after[-1], 600)
        self.assertEqual(legacy.execute("SELECT lesson_at FROM states WHERE word_id=2").fetchone()[0], 0)
        self.assertEqual(legacy.execute("SELECT COUNT(*) FROM choice_cache").fetchone()[0], 0)
        legacy.close()

    def test_dictionary_update_keeps_progress_and_invalidates_only_changed_caches(self):
        sync = re.search(r'void syncDictionary\([^)]*\) \{(.*?)\n    \}', SOURCE, re.S).group(1)
        self.assertIn('db.update("words", v, "id=?"', sync)
        self.assertNotIn('CONFLICT_REPLACE', sync)
        before = self.db.execute("SELECT * FROM states WHERE word_id=1").fetchone()
        for word_id in (1, 2):
            self.db.execute("INSERT INTO learning_packs VALUES(?,?,?,?)", (word_id, "{}", 1000, "ai"))
            self.db.execute("INSERT INTO choice_cache VALUES(?,?,?,?,?)", (word_id, 1, "{}", 1000, "ai"))
        self.db.execute("UPDATE words SET meaning=? WHERE id=?", ("完整旧义项\n六级常用义补充：\n新增义项", 1))
        self.db.execute("DELETE FROM learning_packs WHERE word_id=?", (1,))
        self.db.execute("DELETE FROM choice_cache WHERE word_id=?", (1,))
        self.assertEqual(self.db.execute("SELECT * FROM states WHERE word_id=1").fetchone(), before)
        self.assertEqual(self.db.execute("SELECT word_id FROM learning_packs").fetchall(), [(2,)])
        self.assertEqual(self.db.execute("SELECT word_id FROM choice_cache").fetchall(), [(2,)])

    def test_cache_timestamp_selects_maximum_across_both_tables(self):
        self.db.execute("INSERT INTO learning_packs VALUES(1,'{}',1100,'ai')")
        self.db.execute("INSERT INTO choice_cache VALUES(2,1,'{}',1250,'local')")
        sql = re.search(r'"(SELECT COALESCE\(MAX\(cached_at\)[^"\n]*)"', SOURCE).group(1)
        self.assertEqual(self.db.execute(sql).fetchone()[0], 1250)

    def test_bundled_dataset_matches_repository_contract(self):
        data = json.loads((ROOT / "app/src/main/assets/vocabulary.json").read_text())
        self.assertEqual(len(data["words"]), 3500)
        self.assertEqual(len({w["id"] for w in data["words"]}), 3500)
        self.assertEqual(len({w["word"] for w in data["words"]}), 3500)
        for word in data["words"]:
            self.assertTrue(word["word"] and word["meaning"] and word["ipa"])
            self.assertIsInstance(word["relations"], str)

    def test_bundled_meaning_supplements_are_canonical_and_traceable(self):
        assets = ROOT / "app/src/main/assets"
        words = {w["id"]: w for w in json.loads((assets / "vocabulary.json").read_text())["words"]}
        supplements = json.loads((assets / "meaning_supplements.json").read_text())
        self.assertIsInstance(supplements, list)
        self.assertEqual(len({s["id"] for s in supplements}), len(supplements))
        for entry in supplements:
            word = words[entry["id"]]
            self.assertEqual(entry["word"], word["word"])
            self.assertTrue(entry.get("meaningAppend") or entry.get("correctedMeaning"))
            self.assertTrue(entry["referenceUrls"])
            if entry.get("correctedMeaning"):
                self.assertEqual(entry["originalMeaning"], word["meaning"])
            if entry.get("meaningAppend"):
                self.assertNotIn(entry["meaningAppend"], word["meaning"])
            if entry.get("correctedPos"):
                self.assertTrue(entry["correctedPos"].strip())
        by_word = {s["word"]: s for s in supplements}
        self.assertEqual(by_word["hardy"]["correctedPos"], "adj.")
        self.assertEqual(by_word["fluorescent"]["correctedPos"], "adj. / n.")


if __name__ == "__main__":
    unittest.main(verbosity=2)
