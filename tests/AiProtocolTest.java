package com.wordweave.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Host integration checks against a loopback fake provider. No external API or actual credentials. */
public final class AiProtocolTest {
    private static String mode = "reading";
    private static int checked = 0;
    private static final AtomicInteger requests = new AtomicInteger();
    private static final AtomicInteger leaked = new AtomicInteger();
    private static volatile String serverFailure;
    private static final String PASSAGE = "Researchers explore how people adapt to a changing environment. "
            + "They ask volunteers to observe a small garden every morning and record the plants they see. "
            + "The volunteers initially notice very little, but gradually learn to recognise small differences. "
            + "This simple practice helps them appreciate the relationship between weather, soil, and growth. "
            + "A careful observation is often more useful than a quick guess. "
            + "The project also shows why regular practice can make unfamiliar tasks easier. "
            + "They share their careful records with the other people in the group.";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the project root directory");
        check(AiClient.resolveEndpoint("https://example.com", false).toString().equals("https://example.com/v1/chat/completions"), "host endpoint");
        check(AiClient.resolveEndpoint("https://example.com/v1/", false).toString().equals("https://example.com/v1/chat/completions"), "v1 endpoint");
        check(AiClient.resolveEndpoint("https://example.com/api/v1/chat/completions?version=1", false).toString()
                .equals("https://example.com/api/v1/chat/completions?version=1"), "exact endpoint and query");
        fails(() -> AiClient.resolveEndpoint("http://example.com/v1", true), "remote HTTP rejected");
        fails(() -> AiClient.resolveEndpoint("http://127.0.0.1/v1", false), "development toggle required");
        fails(() -> AiClient.resolveEndpoint("https://user:secret@example.com/v1", false), "userinfo rejected");
        fails(() -> AiClient.resolveEndpoint("https://example.com/v1#part", false), "fragment rejected");
        fails(() -> AiClient.resolveEndpoint("https://example.com/%2e%2e/v1", false), "traversal rejected");
        fails(() -> AiClient.resolveEndpoint("file:///tmp/token", false), "scheme rejected");
        fails(() -> SecureSettings.validateHeaderValue("abc\r\nX-Secret: leak", "Key", 8192), "header injection rejected");
        check(AiClient.containsWholeWord("They ADAPT quickly.", "adapt"), "case insensitive whole word");
        check(!AiClient.containsWholeWord("They adapted quickly.", "adapt"), "inflection not mistaken for base word");
        check(!AiClient.containsWholeWord("readaptation", "adapt"), "substring rejected");
        check(AiClient.containsWholeWord("Their well-being improves.", "well-being"), "hyphen word");
        check(AiClient.parseModelObject("```json\n{\"ok\":true}\n```").getBoolean("ok"), "code fence");
        fails(() -> AiClient.parseModelObject("{\"ok\":true} extra text"), "trailing prose rejected");
        fails(() -> AiClient.parseModelObject("[1,2,3]"), "JSON array rejected");
        fails(() -> AiClient.parseModelObject("```json\n{\"ok\":true}"), "partial fence rejected");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.createContext("/", AiProtocolTest::serve);
        server.start();
        int port = server.getAddress().getPort();
        JSONObject settings = new JSONObject().put("apiBaseUrl", "http://127.0.0.1:" + port + "/v1")
                .put("apiKey", "test-only-nonsecret-key").put("apiModel", "mock-model")
                .put("allowLocalHttp", true).put("apiTimeoutSec", 10);
        AiClient client = new AiClient((AiClient.PromptSource) filename ->
                new String(Files.readAllBytes(Paths.get(args[0], "app/src/main/assets/prompts", filename)), StandardCharsets.UTF_8));
        JSONArray words = new JSONArray().put(word("adapt", "适应", "v."))
                .put(word("observe", "观察", "v.")).put(word("appreciate", "欣赏；理解", "v."));
        try {
            JSONObject reading = client.generate(settings, words);
            check(reading.getJSONArray("used_words").length() == 3, "verified reading targets");
            check(!reading.has("reference_translation"), "translation removed before submission");
            check(reading.getString("length").equals("short") && reading.getString("difficulty").equals("standard"), "legacy overload defaults");
            JSONArray novel = new JSONArray().put(word("collaborate", "合作", "v."));
            JSONObject extended = client.generate(settings, words, novel, config("long", "challenge", 1));
            check(AiClient.englishWordCount(extended.getString("text")) >= 260
                    && AiClient.englishWordCount(extended.getString("text")) <= 420, "long word count");
            check(extended.getJSONArray("used_words").length() == 3 && extended.getJSONArray("new_words").length() == 1,
                    "review and additional target sets remain separate");
            check(extended.getJSONArray("new_words").getJSONObject(0).getString("word").equals("collaborate"), "novel supplied by native selection");
            client.generate(settings, words, new JSONArray(), config("short", "easy", 0));
            checked++;
            fails(() -> client.generate(settings, words, novel, config("short", "standard", 0)), "novel count mismatch rejected");
            fails(() -> client.generate(settings, words, new JSONArray().put(words.getJSONObject(0)), config("short", "standard", 1)), "overlapping sets rejected");
            fails(() -> client.generate(settings, words, novel, config("short", "graduate", 1)), "unsupported difficulty rejected");
            fails(() -> client.generate(settings, words, novel, config("huge", "standard", 1)), "unsupported length rejected");
            fails(() -> client.generate(settings, words, novel, config("short", "standard", 1).put("newWordCount", "1")), "string count rejected");
            JSONArray review8 = new JSONArray(words.toString());
            for (String name : new String[]{"establish", "consider", "contribute", "maintain", "determine"}) review8.put(word(name, "测试词义", "v."));
            JSONArray novel8 = new JSONArray();
            for (String name : new String[]{"collaborate", "demonstrate", "investigate", "allocate", "clarify", "retain", "assess", "evaluate"}) novel8.put(word(name, "测试额外义项", "v."));
            JSONObject maxReading = client.generate(settings, review8, novel8, config("long", "challenge", 8));
            check(maxReading.getJSONArray("used_words").length() == 8 && maxReading.getJSONArray("new_words").length() == 8, "maximum 8 plus 8 sets");
            fails(() -> client.generate(settings, new JSONArray(review8.toString()).put(word("reveal", "揭示", "v.")), new JSONArray(), config("long", "standard", 0)), "nine review words rejected");
            mode = "missingNovel";
            fails(() -> client.generate(settings, words, novel, config("short", "standard", 1)), "absent novel target rejected");
            mode = "extraNovel";
            fails(() -> client.generate(settings, words, novel, config("short", "standard", 1)), "invented novel target rejected");
            mode = "wrongDifficulty";
            fails(() -> client.generate(settings, words, new JSONArray(), config("short", "easy", 0)), "difficulty echo mismatch rejected");
            mode = "tooShort";
            fails(() -> client.generate(settings, words), "short lower limit enforced");
            mode = "tooLong";
            fails(() -> client.generate(settings, words), "short upper limit enforced");
            mode = "packs";
            JSONArray packs = client.generateLearningPacks(settings, words);
            check(packs.length() == 3 && packs.getJSONObject(0).getInt("word_id") == words.getJSONObject(0).getInt("id"), "batch input order restored");
            check(packs.getJSONObject(2).getJSONArray("meanings").getString(0).equals("欣赏；理解"), "dictionary full meanings preserved despite model alterations");
            check(packs.getJSONObject(0).getJSONArray("tiers").length() == 3
                    && packs.getJSONObject(0).getJSONArray("tiers").getJSONObject(2).getJSONArray("options").length() == 4, "three four-choice tiers");
            mode = "packMissing";
            fails(() -> client.generateLearningPacks(settings, words), "missing batch word rejected");
            mode = "packWrongMeaning";
            fails(() -> client.generateLearningPacks(settings, words), "altered correct dictionary option rejected");
            mode = "packTrueSubmeaning";
            fails(() -> client.generateLearningPacks(settings, words), "valid dictionary subsense distractor rejected");
            mode = "packDuplicateOptions";
            fails(() -> client.generateLearningPacks(settings, words), "duplicate choices rejected");
            mode = "packRepeatedTier";
            fails(() -> client.generateLearningPacks(settings, words), "repeated distractor sets rejected");
            mode = "packWrongId";
            fails(() -> client.generateLearningPacks(settings, words), "invented word id rejected");
            mode = "packMissingExampleWord";
            fails(() -> client.generateLearningPacks(settings, words), "example lacking actual target rejected");
            mode = "packWrongQuestion";
            fails(() -> client.generateLearningPacks(settings, words), "contextual question versus whole meaning rejected");
            mode = "packLetterExplanation";
            fails(() -> client.generateLearningPacks(settings, words), "fixed option-letter explanation rejected before shuffle");
            mode = "packCompactLetterExplanation";
            fails(() -> client.generateLearningPacks(settings, words), "compact Chinese option-letter explanation rejected");
            mode = "evaluation";
            JSONObject feedback = client.evaluate(settings, reading, "研究人员探索人们如何适应不断变化的环境。");
            check(feedback.getInt("score") == 92 && feedback.getJSONArray("corrections").length() == 1, "typed evaluation");
            fails(() -> client.evaluate(settings, reading, "   "), "empty translation rejected before request");
            mode = "badCitation";
            fails(() -> client.evaluate(settings, reading, "研究人员探索人们如何适应不断变化的环境。"), "hallucinated citation rejected");
            mode = "badScore";
            fails(() -> client.evaluate(settings, reading, "研究人员探索人们如何适应不断变化的环境。"), "out of range score rejected");
            mode = "missingTarget";
            fails(() -> client.generate(settings, words), "claimed but absent target rejected");
            mode = "chineseLeak";
            fails(() -> client.generate(settings, words), "premature Chinese clue rejected");
            mode = "malformed";
            fails(() -> client.generate(settings, words), "malformed JSON rejected");
            mode = "401";
            failsContaining(() -> client.generate(settings, words), "401", "auth error mapped");
            mode = "429";
            failsContaining(() -> client.generate(settings, words), "429", "rate error mapped");
            mode = "503";
            failsContaining(() -> client.generate(settings, words), "503", "provider error mapped");
            mode = "redirect";
            failsContaining(() -> client.generate(settings, words), "跳转", "redirect refused");
            check(leaked.get() == 0, "bearer key not forwarded to redirect");
            mode = "oversized";
            fails(() -> client.generate(settings, words), "2 MiB limit enforced");
            mode = "probe";
            check(client.probe(settings).getBoolean("ok"), "probe real round trip");
            mode = "timeout";
            failsContaining(() -> client.probe(settings), "超时", "read timeout enforced");
            check(serverFailure == null, "request method headers body and prompt contract: " + serverFailure);
            System.out.println("AI protocol checks passed: " + checked + "; mock requests: " + requests.get() + "; redirected requests: " + leaked.get());
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
    }

    private static JSONObject word(String word, String meaning, String pos) {
        return new JSONObject().put("id", (word.hashCode() & Integer.MAX_VALUE) + 1).put("word", word).put("meaning", meaning).put("pos", pos);
    }

    private static JSONObject config(String length, String difficulty, int novelCount) {
        return new JSONObject().put("length", length).put("difficulty", difficulty).put("newWordCount", novelCount);
    }

    private static void serve(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        try {
            if (exchange.getRequestURI().getPath().equals("/leak")) {
                leaked.incrementAndGet();
                send(exchange, 200, "{}");
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")
                    || !exchange.getRequestURI().getPath().equals("/v1/chat/completions")
                    || !"Bearer test-only-nonsecret-key".equals(exchange.getRequestHeaders().getFirst("Authorization")))
                serverFailure = "Wrong method, endpoint or authorization";
            JSONObject request = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (!request.getString("model").equals("mock-model") || request.getBoolean("stream")
                    || request.getDouble("temperature") != 0.35 || request.getJSONArray("messages").length() != 2)
                serverFailure = "Wrong request parameters";
            String current = mode;
            if (current.matches("[0-9]{3}")) { send(exchange, Integer.parseInt(current), "{\"error\":\"do not echo provider body\"}"); return; }
            if (current.equals("redirect")) {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + exchange.getLocalAddress().getPort() + "/leak");
                send(exchange, 302, "{}"); return;
            }
            if (current.equals("oversized")) { send(exchange, 200, " ".repeat(2 * 1024 * 1024 + 1)); return; }
            if (current.equals("timeout")) {
                try { Thread.sleep(12000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return;
            }
            JSONObject result;
            JSONObject data = request.getJSONArray("messages").getJSONObject(1).getString("content").startsWith("{")
                    ? new JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content")) : new JSONObject();
            if (current.equals("probe")) result = new JSONObject().put("ok", true);
            else if (current.startsWith("pack")) result = mockPacks(data.getJSONArray("words"), current);
            else if (current.equals("evaluation") || current.equals("badCitation") || current.equals("badScore")) {
                result = new JSONObject().put("score", current.equals("badScore") ? 101 : 92)
                        .put("summary", "目标词含义基本准确，可补充实验方法。")
                        .put("reference_translation", "研究人员探索人们如何适应不断变化的环境。")
                        .put("corrections", new JSONArray().put(new JSONObject()
                                .put("source_span", current.equals("badCitation") ? "made up source" : "Researchers")
                                .put("user_span", "研究人员").put("issue", "本测试的格式验证项目")
                                .put("correction", "研究人员").put("explanation", "引用需出自本次原文与译文。")))
                        .put("word_notes", new JSONArray().put(new JSONObject().put("word", "adapt")
                                .put("meaning_in_context", "适应").put("note", "adapt to 是适应某事。")))
                        .put("next_tip", "遮住译文，回想 adapt to 的意思。");
            } else {
                JSONObject readingConfig = data.getJSONObject("config");
                JSONArray requested = data.getJSONArray("target_words");
                JSONArray novelRequested = data.getJSONArray("novel_words");
                JSONArray used = new JSONArray();
                JSONArray generatedNovel = new JSONArray();
                String readingText = readingConfig.getString("length").equals("long") ? (PASSAGE + " ").repeat(3) : PASSAGE;
                StringBuilder extras = new StringBuilder(" They also ");
                for (int i = 0; i < requested.length(); i++) {
                    String name = requested.getJSONObject(i).getString("word");
                    used.put(name);
                    if (!AiClient.containsWholeWord(readingText, name)) extras.append(name).append(" and ");
                }
                for (int i = 0; i < novelRequested.length(); i++) {
                    String name = novelRequested.getJSONObject(i).getString("word");
                    generatedNovel.put(new JSONObject().put("word", name).put("meaning_in_context", "本段的额外目标义项"));
                    if (!AiClient.containsWholeWord(readingText, name)) extras.append(name).append(" and ");
                }
                readingText += extras + "share the findings carefully.";
                if (current.equals("missingNovel")) readingText = readingText.replace("collaborate", "work");
                if (current.equals("extraNovel")) generatedNovel.getJSONObject(0).put("word", "invented");
                if (current.equals("missingTarget")) readingText = readingText.replace("appreciate", "understand");
                if (current.equals("tooShort")) readingText = "We adapt observe and appreciate this garden.";
                if (current.equals("tooLong")) readingText = (PASSAGE + " ").repeat(6);
                result = new JSONObject().put("title", "A Garden Study")
                        .put("text", readingText + (current.equals("chineseLeak") ? " 中文译文提示。" : ""))
                        .put("used_words", used).put("new_words", generatedNovel)
                        .put("length", readingConfig.getString("length"))
                        .put("difficulty", current.equals("wrongDifficulty") ? "standard" : readingConfig.getString("difficulty"))
                        .put("questions", new JSONArray().put("What do the volunteers observe?"))
                        .put("reference_translation", "This unsolicited field must not be exposed.");
            }
            String content = current.equals("malformed") ? "not JSON at all" : "```json\n" + result + "\n```";
            JSONObject envelope = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                    .put("finish_reason", "stop").put("message", new JSONObject().put("content", content))));
            send(exchange, 200, envelope.toString());
        } catch (Exception exception) {
            serverFailure = exception.getClass().getSimpleName();
            try { send(exchange, 500, "{}"); } catch (IOException ignored) { }
        } finally { exchange.close(); }
    }

    private static JSONObject mockPacks(JSONArray words, String current) {
        JSONArray packs = new JSONArray();
        for (int i = words.length() - 1; i >= 0; i--) {
            JSONObject word = words.getJSONObject(i);
            JSONArray tiers = new JSONArray();
            for (int level = 1; level <= 3; level++) {
                JSONArray options = new JSONArray();
                String correct = new String[]{"A", "B", "C"}[level - 1];
                for (int option = 0; option < 4; option++) {
                    String id = new String[]{"A", "B", "C", "D"}[option];
                    int distractorLevel = current.equals("packRepeatedTier") ? 1 : level;
                    String text = id.equals(correct) ? word.getString("meaning") : "测试错误概念" + distractorLevel + "之" + option;
                    if (current.equals("packWrongMeaning") && id.equals(correct)) text = "被模型误改的释义";
                    if (current.equals("packTrueSubmeaning") && !id.equals(correct)) text = word.getString("meaning").split("；")[0];
                    if (current.equals("packDuplicateOptions") && !id.equals(correct)) text = "完全相同错误概念";
                    options.put(new JSONObject().put("id", id).put("text", text));
                }
                // All repeated tiers must have identical distractor sets regardless of answer slot.
                if (current.equals("packRepeatedTier")) {
                    correct = "A";
                    for (int option = 0; option < 4; option++) options.getJSONObject(option).put("text", option == 0
                            ? word.getString("meaning") : "测试错误概念一之" + option);
                }
                tiers.put(new JSONObject().put("level", level)
                        .put("question", current.equals("packWrongQuestion") ? "该词在本句中的义项是哪项？" : "选出该词完整且准确的词典义项。")
                        .put("options", options).put("answer", correct)
                        .put("explanation", current.equals("packLetterExplanation") ? "A 正确，B 错误。"
                                : current.equals("packCompactLetterExplanation") ? "选项A为正确答案。" : "这是测试的正确义项与三个错误概念辨析。"));
            }
            packs.put(new JSONObject().put("word_id", current.equals("packWrongId") ? 987654321 : word.getInt("id"))
                    .put("word", word.getString("word")).put("meanings", new JSONArray().put("模型尝试遗漏的义项"))
                    .put("example", new JSONObject().put("en", "We can " + (current.equals("packMissingExampleWord") ? "do" : word.getString("word"))
                            + " the useful project with care and patience.").put("zh", "我们可以耐心仔细地处理这项有用的项目。")
                            .put("usage", "这里解释本段使用的义项和搭配。"))
                    .put("tiers", tiers));
        }
        if (current.equals("packMissing")) packs.remove(packs.length() - 1);
        return new JSONObject().put("packs", packs);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private interface Action { void run() throws Exception; }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checked++;
    }
    private static void fails(Action action, String name) throws Exception {
        try { action.run(); } catch (Exception expected) { checked++; return; }
        throw new AssertionError(name);
    }
    private static void failsContaining(Action action, String needle, String name) throws Exception {
        try { action.run(); } catch (Exception expected) {
            check(expected.getMessage().contains(needle), name + ": " + expected.getMessage()); return;
        }
        throw new AssertionError(name);
    }
}
