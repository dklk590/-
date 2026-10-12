package com.wordweave.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native boundary. Only the bundled, offline page may use this bridge. */
public class MainActivity extends Activity {
    private static final int EXPORT = 410, IMPORT = 411, NOTIFY_PERMISSION = 412;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final AtomicBoolean aiBusy = new AtomicBoolean(false);
    private WebView web;
    private WordRepository repository;
    private SecureSettings settings;
    private AiClient ai;
    private SpeechManager speech;
    private ModelDownloadManager models;
    private volatile boolean destroyed;
    private String pendingExportId, pendingImportId;
    private JSONObject pendingBackup;
    private AlertDialog activeDialog;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new SecureSettings(this);
        repository = new WordRepository(this);
        ai = new AiClient(this);
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.rgb(247, 248, 243));
        container.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        getWindow().setStatusBarColor(Color.rgb(247, 248, 243));
        getWindow().setNavigationBarColor(Color.rgb(247, 248, 243));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        web = new WebView(this);
        container.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(container);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setSupportZoom(false);
        ws.setDefaultTextEncodingName("UTF-8");
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags &
                ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        web.addJavascriptInterface(new NativeBridge(), "Android");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView view, String url, String text, JsResult result) {
                if (!url.startsWith("file:///android_asset/")) { result.cancel(); return true; }
                activeDialog = new AlertDialog.Builder(MainActivity.this).setTitle("词链")
                        .setMessage(text).setPositiveButton("继续", (dialog, which) -> result.confirm())
                        .setNegativeButton("取消", (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel()).create();
                activeDialog.show(); return true;
            }
            @Override public boolean onJsAlert(WebView view, String url, String text, JsResult result) {
                if (!url.startsWith("file:///android_asset/")) { result.cancel(); return true; }
                activeDialog = new AlertDialog.Builder(MainActivity.this).setTitle("词链").setMessage(text)
                        .setPositiveButton("知道了", (dialog, which) -> result.confirm())
                        .setOnCancelListener(dialog -> result.cancel()).create();
                activeDialog.show(); return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().equals("file:///android_asset/index.html");
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request.getUrl().toString().startsWith("file:///android_asset/")) return null;
                return new WebResourceResponse("text/plain", "UTF-8",
                        new ByteArrayInputStream(new byte[0]));
            }
        });
        // This queued import finishes before any UI request can query the database.
        worker.execute(() -> {
            try { repository.importVocabulary(); }
            catch (Exception ex) { toast("词库加载失败：" + message(ex)); }
        });
        models = new ModelDownloadManager(this);
        speech = new SpeechManager(this, settings, models);
        speech.setListener(this::publishSpeechStatus);
        web.loadUrl("file:///android_asset/index.html");
        try { ReviewReminder.schedule(this, settings.loadPublic()); } catch (Exception ignored) { }
    }

    private final class NativeBridge {
        @JavascriptInterface public void request(String id, String action, String payload) {
            if (destroyed || id == null || id.length() > 100) return;
            worker.execute(() -> {
                try {
                    JSONObject args = new JSONObject(payload == null || payload.isEmpty() ? "{}" : payload);
                    switch (action) {
                        case "init": {
                            JSONObject value = new JSONObject();
                            value.put("dashboard", repository.dashboard());
                            value.put("settings", settings.loadPublic());
                            value.put("groups", repository.getGroups());
                            reply(id, true, value); break;
                        }
                        case "dashboard": reply(id, true, repository.dashboard()); break;
                        case "words": reply(id, true, repository.listWords(args.optString("query", ""),
                                args.optString("groupId", ""), Math.min(3500, Math.max(1, args.optInt("limit", 150))),
                                args.optBoolean("learnedOnly", false))); break;
                        case "word": reply(id, true, repository.getWord(args.getInt("id"))); break;
                        case "queue": {
                            JSONObject prefs = settings.loadPublic();
                            int mode = bounded(args.optInt("mode", 0), 0, 2);
                            int limit = bounded(args.optInt("limit", prefs.optInt("sessionSize", 12)), 1, 60);
                            int newLimit = prefs.optInt("newPerDay", 15);
                            // UI may lower the daily cap but may not bypass the saved setting.
                            newLimit = Math.min(newLimit, Math.max(0, args.optInt("newLimit", newLimit)));
                            reply(id, true, args.optBoolean("mixed", false)
                                    ? repository.getMixedQueue(limit, newLimit)
                                    : repository.getQueue(mode, limit, newLimit)); break;
                        }
                        case "lessonGet": reply(id, true, repository.getLesson(args.getInt("wordId"))); break;
                        case "lessonComplete": {
                            int wordId = args.getInt("wordId");
                            LearningScheduler.State state = repository.getState(wordId);
                            if (state.lessonAt == 0 && state.introducedAt == 0
                                    && repository.dashboard().optInt("newToday") >= settings.loadPublic().optInt("newPerDay", 15))
                                throw new IllegalStateException("今天的新词额度已满，请先复习到期词，或在设置中调整每日新词数。");
                            reply(id, true, repository.completeLesson(wordId)); break;
                        }
                        case "choiceGet": {
                            int wordId = args.getInt("wordId");
                            if (repository.getState(wordId).lessonAt == 0)
                                throw new IllegalStateException("请先阅读完整讲解，确认理解后再开始练习。");
                            reply(id, true, repository.getChoice(wordId, args.optInt("tier", 0))); break;
                        }
                        case "learningPrefetch": runAi(id, () -> prefetchLearning(id, args)); break;
                        case "review": {
                            int wordId = args.getInt("wordId"), grade = bounded(args.getInt("grade"), 0, 4);
                            int mode = bounded(args.getInt("mode"), 0, 2);
                            long started = args.optLong("questionStartedAt", 0);
                            LearningScheduler.State state = repository.getState(wordId);
                            if (state.lessonAt == 0)
                                throw new IllegalStateException("请先完成这个词的首次学习，再记录练习。");
                            if (mode == 0 && grade >= 1) {
                                JSONObject choice = repository.getChoice(wordId, bounded(args.getInt("choiceTier"), 1, 3));
                                if (args.optLong("choiceCachedAt", -1) != choice.optLong("cachedAt", 0))
                                    throw new IllegalStateException("这道题的选项已更新，请重新打开题目；本次不会记为遗忘。");
                                String answer = args.optString("choiceAnswer", "");
                                if (!answer.matches("[A-D]")) throw new IllegalArgumentException("请先选择一个词义选项。");
                                if (!answer.equals(choice.optString("answer"))) grade = 0;
                            }
                            if (args.optInt("hintLevel", 0) > 0 || args.optBoolean("wrong", false)) grade = Math.min(grade, 1);
                            if (mode == 2) {
                                reply(id, true, repository.recordReading(args.getLong("readingId"), wordId, grade));
                            } else if (started > 0 && state.lastReviewed >= started) {
                                JSONObject value = repository.getWord(wordId);
                                value.put("alreadyRecorded", true);
                                reply(id, true, value);
                            } else reply(id, true, repository.record(wordId, grade, mode));
                            break;
                        }
                        case "settingsGet": reply(id, true, settings.loadPublic()); break;
                        case "settingsSave": {
                            settings.save(args);
                            JSONObject prefs = settings.loadPublic();
                            runOnUiThread(() -> { updateReminder(prefs); speech.configure(prefs); });
                            reply(id, true, prefs); break;
                        }
                        case "modelCatalog": {
                            models.refreshAll();
                            reply(id, true, models.catalog()); break;
                        }
                        case "modelDownload": reply(id, true, models.start(args.getString("modelId"))); break;
                        case "modelCancel": reply(id, true, models.cancel(args.getString("modelId"))); break;
                        case "modelDelete": {
                            final String modelId = args.getString("modelId");
                            ModelCatalog.get(modelId); // Validate before scheduling native handle release.
                            runOnUiThread(() -> {
                                if (destroyed) return;
                                try {
                                    speech.releaseModel(modelId, () -> {
                                        if (destroyed) return;
                                        worker.execute(() -> {
                                            try { reply(id, true, models.delete(modelId)); }
                                            catch (Exception ex) { error(id, ex); }
                                            finally { speech.modelDeletionFinished(modelId); }
                                        });
                                    });
                                } catch (Exception ex) { error(id, ex); }
                            });
                            break;
                        }
                        case "speechPlay": {
                            long readingId = args.getLong("readingId");
                            if (readingId <= 0) throw new IllegalArgumentException("请先打开一篇阅读材料。");
                            JSONObject reading = repository.getReading(readingId);
                            String text = reading.getString("text");
                            // Read the saved passage; UI labels and translations are not speech input.
                            speakRequest(id, text, "reading:" + readingId); break;
                        }
                        case "speechPause": speechControl(id, "pause"); break;
                        case "speechResume": speechControl(id, "resume"); break;
                        case "speechStop": speechControl(id, "stop"); break;
                        case "speechStatus": reply(id, true, speech.status()); break;
                        case "speak": {
                            final String text = args.optString("text", "").trim();
                            if (text.isEmpty() || text.length() > 16000) throw new IllegalArgumentException("发音内容为空或过长。");
                            speakRequest(id, text); break;
                        }
                        case "speakTest": speakRequest(id, args.optString("text", "Learning a little every day makes a difference.")); break;
                        case "stopSpeak": runOnUiThread(() -> { if (speech != null) speech.stop(); });
                            reply(id, true, new JSONObject().put("stopped", true)); break;
                        case "ttsOptions": {
                            JSONObject voices = speech.options();
                            voices.put("selectedEngine", voices.optString("currentEngine"));
                            voices.put("selectedVoice", voices.optString("currentVoice"));
                            reply(id, true, voices); break;
                        }
                        case "downloadTtsVoice": runOnUiThread(() -> {
                            try { speech.downloadVoiceData(); reply(id, true, new JSONObject().put("opened", true)); }
                            catch (Exception ex) { error(id, ex); }
                        }); break;
                        case "openTtsSettings": runOnUiThread(() -> speech.openSettings());
                            reply(id, true, new JSONObject().put("opened", true)); break;
                        case "aiProbe": runAi(id, () -> reply(id, true, ai.probe(settings.loadInternal()))); break;
                        case "aiGenerate": runAi(id, () -> generateReading(id, args)); break;
                        case "aiEvaluate": runAi(id, () -> evaluateReading(id, args)); break;
                        case "readings": reply(id, true, repository.readingHistory()); break;
                        case "readingGet": reply(id, true, repository.getReading(args.getLong("id"))); break;
                        case "readingHint": reply(id, true, repository.markReadingHint(args.getLong("readingId"), args.getInt("wordId"))); break;
                        case "exportBackup": {
                            JSONObject backup = repository.exportBackup();
                            runOnUiThread(() -> chooseExport(id, backup)); break;
                        }
                        case "importBackup": runOnUiThread(() -> chooseImport(id)); break;
                        default: throw new IllegalArgumentException("未知操作。");
                    }
                } catch (Exception ex) { error(id, ex); }
            });
        }
    }

    private interface AiTask { void run() throws Exception; }
    private void runAi(String id, AiTask task) throws Exception {
        if (!aiBusy.compareAndSet(false, true)) throw new IllegalStateException("正在等待 AI 回复，请等本次请求完成。");
        network.execute(() -> {
            try { if (!destroyed) task.run(); }
            catch (Exception ex) { error(id, ex); }
            finally { aiBusy.set(false); }
        });
    }

    private void generateReading(String requestId, JSONObject args) throws Exception {
        JSONArray ids = args.getJSONArray("wordIds");
        if (ids.length() < 3 || ids.length() > 8) throw new IllegalArgumentException("请选择 3–8 个已学习的单词。");
        JSONArray targetWords = new JSONArray();
        Set<Integer> unique = new HashSet<>();
        for (int i = 0; i < ids.length(); i++) {
            int id = ids.getInt(i);
            if (!unique.add(id)) throw new IllegalArgumentException("目标单词不能重复。");
            JSONObject word = repository.getWord(id);
            if (repository.getState(id).reviews == 0) throw new IllegalArgumentException("请先记忆目标单词，再进行阅读复习。");
            targetWords.put(new JSONObject().put("id", id).put("word", word.getString("word"))
                    .put("meaning", word.getString("meaning")).put("pos", word.optString("pos")));
        }
        JSONObject prefs = settings.loadPublic();
        JSONObject config = args.optJSONObject("config");
        if (config == null) config = new JSONObject();
        String length = config.optString("length", args.optString("length", prefs.optString("readingLength", "short")));
        String difficulty = config.optString("difficulty", args.optString("difficulty", prefs.optString("readingDifficulty", "standard")));
        int newCount = bounded(config.optInt("newWordCount", args.optInt("newWordCount", prefs.optInt("readingNewWordCount", 0))), 0, 8);
        if (!length.matches("short|long") || !difficulty.matches("easy|standard|challenge"))
            throw new IllegalArgumentException("阅读长度或难度无效。");
        config = new JSONObject().put("length", length).put("difficulty", difficulty).put("newWordCount", newCount);
        JSONArray novelWords = new JSONArray();
        // Additional target words are picked from words this learner has never introduced.
        // This controls the requested target set, not every possible unfamiliar word in prose.
        JSONArray candidates = repository.getUnintroducedWords(newCount, ids);
        for (int i = 0; i < candidates.length() && novelWords.length() < newCount; i++) {
            JSONObject w = candidates.getJSONObject(i);
            LearningScheduler.State s = repository.getState(w.getInt("id"));
            if (s.lessonAt == 0 && s.reviews == 0)
                novelWords.put(new JSONObject().put("id", w.getInt("id")).put("word", w.getString("word"))
                        .put("meaning", w.getString("meaning")).put("pos", w.optString("pos")));
        }
        if (novelWords.length() != newCount) throw new IllegalStateException("当前未学词不足，请减少额外生词数。");
        settings.save(new JSONObject().put("readingLength", length).put("readingDifficulty", difficulty).put("readingNewWordCount", newCount));
        JSONObject reading = ai.generate(settings.loadInternal(), targetWords, novelWords, config);
        if (destroyed) return;
        reading.put("targetWords", targetWords);
        reading.put("novelWords", novelWords);
        reading.put("config", config);
        reading.put("createdAt", System.currentTimeMillis());
        long readingId = repository.saveReading(reading);
        reading.put("id", readingId);
        reply(requestId, true, reading);
    }

    private void prefetchLearning(String requestId, JSONObject args) throws Exception {
        int wordId = args.getInt("wordId");
        if (repository.getWord(wordId) == null) throw new IllegalArgumentException("单词不存在。");
        int futureLimit = bounded(args.optInt("limit", 6), 0, 7);
        JSONArray selection = new JSONArray().put(repository.getWord(wordId));
        JSONArray upcoming = futureLimit == 0 ? new JSONArray()
                : repository.getUnintroducedWords(futureLimit, new JSONArray().put(wordId));
        for (int i = 0; i < upcoming.length(); i++) selection.put(upcoming.getJSONObject(i));
        JSONArray missing = new JSONArray(), selectedIds = new JSONArray();
        int cached = 0;
        for (int i = 0; i < selection.length(); i++) {
            JSONObject word = selection.getJSONObject(i);
            int id = word.getInt("id");
            selectedIds.put(id);
            if (repository.getLesson(id).optBoolean("aiCached", false)) cached++;
            else missing.put(new JSONObject().put("id", id).put("word", word.getString("word"))
                    .put("meaning", word.getString("meaning")).put("pos", word.optString("pos"))
                    .put("relations", word.optString("relations")));
        }
        if (missing.length() > 0) {
            JSONArray packs = ai.generateLearningPacks(settings.loadInternal(), missing);
            if (destroyed) return;
            repository.saveLearningPacks(packs);
        }
        JSONObject value = new JSONObject().put("cachedCount", cached).put("generatedCount", missing.length())
                .put("wordIds", selectedIds).put("lesson", repository.getLesson(wordId));
        if (repository.getState(wordId).lessonAt > 0) value.put("choice", repository.getChoice(wordId));
        reply(requestId, true, value);
    }

    private void speakRequest(String requestId, String text) {
        speakRequest(requestId, text, "utterance");
    }

    private void speakRequest(String requestId, String text, String context) {
        runOnUiThread(() -> {
            try {
                if (text.trim().isEmpty() || text.length() > 16000) throw new IllegalArgumentException("发音内容为空或过长。");
                if (!speech.speak(text, context))
                    throw new IllegalStateException(speech.status().optString("message", "所选语音未就绪，请在设置中下载或切换。"));
                JSONObject status = speech.status();
                status.put("started", true);
                reply(requestId, true, status);
            } catch (Exception ex) { error(requestId, ex); }
        });
    }

    private void speechControl(String requestId, String operation) {
        runOnUiThread(() -> {
            try {
                if ("pause".equals(operation)) speech.pause();
                else if ("resume".equals(operation)) speech.resume();
                else speech.stop();
                reply(requestId, true, speech.status());
            } catch (Exception ex) { error(requestId, ex); }
        });
    }

    private void publishSpeechStatus(JSONObject status) {
        if (destroyed) return;
        // Listener snapshots contain only playback state, never API credentials.
        final String json = status.toString();
        runOnUiThread(() -> {
            if (!destroyed && web != null)
                web.evaluateJavascript("window.onSpeechStatus&&window.onSpeechStatus(" + json + ")", null);
        });
    }

    private void evaluateReading(String requestId, JSONObject args) throws Exception {
        long id = args.getLong("readingId");
        String translation = args.optString("translation", "").trim();
        if (translation.isEmpty() || translation.length() > 8000) throw new IllegalArgumentException("请输入中文翻译，最多 8000 字。");
        JSONObject reading = repository.getReading(id);
        JSONObject evaluation = ai.evaluate(settings.loadInternal(), reading, translation);
        if (destroyed) return;
        JSONObject update = new JSONObject().put("id", id).put("translation", translation)
                .put("evaluation", evaluation).put("evaluatedAt", System.currentTimeMillis());
        repository.saveReading(update);
        reply(requestId, true, evaluation);
    }

    private void reply(String id, boolean ok, Object value) {
        final String json = value == null ? "null" : value.toString();
        if (destroyed) return;
        runOnUiThread(() -> {
            if (!destroyed && web != null) {
                web.evaluateJavascript("window.onNativeResult&&window.onNativeResult(" + JSONObject.quote(id) + ","
                        + ok + "," + JSONObject.quote(json) + ")", null);
            }
        });
    }
    private void error(String id, Exception ex) {
        try { reply(id, false, new JSONObject().put("message", message(ex))); }
        catch (Exception ignored) { reply(id, false, "操作失败。"); }
    }
    private static String message(Exception ex) {
        String m = ex.getMessage();
        if (m == null || m.trim().isEmpty()) m = "操作未完成，请重试。";
        return m.length() > 400 ? m.substring(0, 400) : m;
    }
    private static int bounded(int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException("参数超出允许范围。");
        return value;
    }
    private void toast(String value) { if (!destroyed) runOnUiThread(() -> Toast.makeText(this, value, Toast.LENGTH_LONG).show()); }

    @Override protected void onResume() {
        super.onResume();
        if (speech != null) speech.onResume();
        if (models != null) models.refreshAll();
    }
    private void updateReminder(JSONObject prefs) {
        if (prefs.optBoolean("reminder") && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFY_PERMISSION);
        }
        ReviewReminder.schedule(this, prefs);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == NOTIFY_PERMISSION) {
            try { ReviewReminder.schedule(this, settings.loadPublic()); } catch (Exception ignored) { }
            if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)
                toast("复习提醒需要通知权限；你仍可在应用内查看到期词。");
        }
    }

    private void chooseExport(String id, JSONObject backup) {
        if (pendingExportId != null || pendingImportId != null) { error(id, new IllegalStateException("请先完成当前文件操作。")); return; }
        pendingExportId = id; pendingBackup = backup;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE,
                        "WordWeave-backup-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".json");
        try { startActivityForResult(intent, EXPORT); }
        catch (Exception ex) { pendingExportId = null; pendingBackup = null; error(id, ex); }
    }
    private void chooseImport(String id) {
        if (aiBusy.get()) { error(id, new IllegalStateException("请等 AI 请求完成后再恢复备份。")); return; }
        if (pendingExportId != null || pendingImportId != null) { error(id, new IllegalStateException("请先完成当前文件操作。")); return; }
        pendingImportId = id;
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json"), IMPORT); }
        catch (Exception ex) { pendingImportId = null; error(id, ex); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT && request != IMPORT) return;
        final String id = request == EXPORT ? pendingExportId : pendingImportId;
        final JSONObject backup = pendingBackup;
        if (request == EXPORT) { pendingExportId = null; pendingBackup = null; } else pendingImportId = null;
        if (id == null) return;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            error(id, new IllegalStateException("已取消文件操作。")); return;
        }
        final Uri uri = data.getData();
        worker.execute(() -> {
            try {
                if (request == EXPORT) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null || backup == null) throw new IllegalStateException("无法写入文件。");
                        out.write(backup.toString(2).getBytes(StandardCharsets.UTF_8));
                    }
                    reply(id, true, new JSONObject().put("message", "学习进度已导出，不包含 API Key。"));
                } else {
                    String content;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IllegalStateException("无法读取文件。");
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192]; int n;
                        while ((n = in.read(buffer)) != -1) {
                            if (out.size() + n > 20 * 1024 * 1024) throw new IllegalArgumentException("备份文件不能超过 20 MB。");
                            out.write(buffer, 0, n);
                        }
                        content = out.toString("UTF-8");
                    }
                    if (speech != null) speech.stop();
                    repository.importBackup(new JSONObject(content));
                    reply(id, true, new JSONObject().put("message", "学习进度已恢复。"));
                    runOnUiThread(() -> web.evaluateJavascript("window.dispatchEvent(new Event('wordweave:dataChanged'))", null));
                }
            } catch (Exception ex) { error(id, ex); }
        });
    }
    @Override public void onBackPressed() {
        web.evaluateJavascript("Boolean(window.nativeBack&&window.nativeBack())", handled -> {
            if (!"true".equals(handled)) MainActivity.super.onBackPressed();
        });
    }
    @Override protected void onDestroy() {
        destroyed = true;
        if (activeDialog != null) activeDialog.cancel();
        network.shutdownNow();
        if (speech != null) speech.destroy();
        if (models != null) models.close();
        if (web != null) { web.removeJavascriptInterface("Android"); web.destroy(); web = null; }
        worker.execute(() -> repository.close());
        worker.shutdown();
        super.onDestroy();
    }
}
