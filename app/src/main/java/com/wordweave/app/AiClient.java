package com.wordweave.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** OpenAI-compatible, non-streaming Chat Completions. Call only on a background thread. */
public final class AiClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private final PromptSource promptSource;

    public AiClient(Context context) {
        final Context application = context.getApplicationContext();
        promptSource = filename -> {
            try (InputStream input = application.getAssets().open("prompts/" + filename)) {
                return new String(readBounded(input, 32 * 1024), StandardCharsets.UTF_8);
            }
        };
    }

    interface PromptSource { String load(String filename) throws IOException; }

    // Package-private host-test seam: networking and parsing still execute the real production code.
    AiClient(PromptSource source) { this.promptSource = source; }

    public JSONObject generateContextLessons(JSONObject settings, JSONArray words) throws Exception {
        validateWords(words, 1, 6, true);
        JSONObject raw = chat(settings, prompt("generate_context_lessons.txt"), new JSONObject().put("words", words).toString(), 9000);
        return ContextRules.validateMaterials(raw, words, true);
    }

    public JSONObject explainContextSentence(JSONObject settings, JSONObject sentence) throws Exception {
        JSONObject raw = chat(settings, prompt("explain_context_sentence.txt"), new JSONObject().put("sentence", sentence.getString("text")).toString(), 2200);
        return ContextRules.validateExplanation(raw, sentence);
    }

    public JSONObject explainContextWord(JSONObject settings, JSONObject sentence, String surface, JSONArray dictionary) throws Exception {
        ContextRules.selectedToken(sentence.getString("text"), surface);
        JSONObject data = new JSONObject().put("sentence", sentence.getString("text")).put("surface", surface).put("dictionaryCandidates", dictionary);
        return ContextRules.validateWordExplanation(chat(settings, prompt("explain_context_word.txt"), data.toString(), 1400), sentence, surface);
    }

    public JSONObject generate(JSONObject settings, JSONArray targetWords) throws Exception {
        return generate(settings, targetWords, new JSONArray(),
                new JSONObject().put("length", "short").put("difficulty", "standard").put("newWordCount", 0));
    }

    /** Native selection supplies novelWords; this count is an explicit target set, not a global unknown-word guarantee. */
    public JSONObject generate(JSONObject settings, JSONArray targetWords, JSONArray novelWords,
                               JSONObject config) throws Exception {
        List<String> words = validateWords(targetWords, 3, 8, false);
        if (novelWords == null) novelWords = new JSONArray();
        List<String> novel = validateWords(novelWords, 0, 8, false);
        String length = configValue(config, "length", "short", "long");
        String difficulty = configValue(config, "difficulty", "easy", "standard", "challenge");
        int newCount = integerValue(config, "newWordCount", 0, 8);
        if (novel.size() != newCount) throw new IllegalArgumentException("额外目标生词数量与配置不一致。");
        Set<String> requested = normalizedSet(words);
        for (String word : novel) if (requested.contains(word.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("复习词与额外目标生词不能重复。");
        JSONObject requestData = new JSONObject().put("target_words", targetWords).put("novel_words", novelWords)
                .put("config", new JSONObject().put("length", length).put("difficulty", difficulty).put("newWordCount", newCount));
        JSONObject answer = chat(settings, prompt("generate_reading.txt"), requestData.toString(),
                length.equals("long") ? 3500 : 2200);
        String title = requiredString(answer, "title", 160);
        String text = requiredString(answer, "text", 10000);
        if (!length.equals(requiredString(answer, "length", 16))
                || !difficulty.equals(requiredString(answer, "difficulty", 20)))
            throw new Exception("AI 阅读长度或难度标记与请求不一致，请重新生成。");
        if (HAN.matcher(title + text).find())
            throw new Exception("阅读结果包含中文提示，已拒绝展示，请重新生成。");
        int textWords = englishWordCount(text);
        int minimum = length.equals("long") ? 260 : 80;
        int maximum = length.equals("long") ? 420 : 140;
        if (textWords < minimum || textWords > maximum)
            throw new Exception("AI 阅读长度应为 " + minimum + "–" + maximum + " 个英文词，请重新生成。");
        JSONArray claimed = requiredArray(answer, "used_words", 3, 8);
        Set<String> claims = new HashSet<>();
        for (int i = 0; i < claimed.length(); i++) {
            Object item = claimed.get(i);
            if (!(item instanceof String)) throw new Exception("AI 的 used_words 格式不正确。");
            String claim = ((String) item).toLowerCase(Locale.ROOT);
            if (!requested.contains(claim) || !claims.add(claim))
                throw new Exception("AI 返回了未指定或重复的复习词，请重新生成。");
        }
        JSONArray verifiedWords = new JSONArray();
        for (String word : words) {
            if (!containsWholeWord(text, word))
                throw new Exception("AI 阅读未包含完整复习词 “" + word + "”，请重新生成。");
            verifiedWords.put(word);
        }
        if (!claims.equals(requested)) throw new Exception("AI 复习词清单不完整，请重新生成。");
        JSONArray generatedNovel = requiredArray(answer, "new_words", newCount, newCount);
        Set<String> wantedNovel = normalizedSet(novel);
        Set<String> seenNovel = new HashSet<>();
        java.util.Map<String, JSONObject> novelByWord = new java.util.HashMap<>();
        for (int i = 0; i < generatedNovel.length(); i++) {
            JSONObject item = generatedNovel.optJSONObject(i);
            if (item == null) throw new Exception("AI 额外目标生词列表格式不正确。");
            String word = requiredString(item, "word", 100);
            String normalized = word.toLowerCase(Locale.ROOT);
            if (!wantedNovel.contains(normalized) || !seenNovel.add(normalized) || !containsWholeWord(text, word))
                throw new Exception("AI 额外目标生词集合或正文覆盖不正确，请重新生成。");
            novelByWord.put(normalized, item);
        }
        if (!seenNovel.equals(wantedNovel)) throw new Exception("AI 额外目标生词清单不完整，请重新生成。");
        JSONArray safeNovel = new JSONArray();
        for (String word : novel) {
            String contextMeaning = requiredString(novelByWord.get(word.toLowerCase(Locale.ROOT)), "meaning_in_context", 800);
            if (!HAN.matcher(contextMeaning).find()) throw new Exception("AI 额外目标生词义项需为中文。");
            safeNovel.put(new JSONObject().put("word", word).put("meaning_in_context", contextMeaning));
        }
        JSONArray questions = requiredArray(answer, "questions", 1, 4);
        JSONArray safeQuestions = new JSONArray();
        for (int i = 0; i < questions.length(); i++) {
            Object question = questions.get(i);
            if (!(question instanceof String) || ((String) question).trim().isEmpty()
                    || ((String) question).length() > 300 || HAN.matcher((String) question).find())
                throw new Exception("AI 自测问题格式不正确，请重新生成。");
            safeQuestions.put(((String) question).trim());
        }
        return new JSONObject().put("title", title).put("text", text).put("used_words", verifiedWords)
                .put("new_words", safeNovel).put("difficulty", difficulty).put("length", length).put("questions", safeQuestions);
    }

    /** One explicit paid batch; the repository decides which future-word packs are uncached. */
    public JSONArray generateLearningPacks(JSONObject settings, JSONArray words) throws Exception {
        validateWords(words, 1, 8, true);
        JSONArray learningWords = new JSONArray();
        for (int i = 0; i < words.length(); i++) {
            JSONObject entry = new JSONObject(words.getJSONObject(i).toString());
            entry.put("choiceMeaning", VocabularyRules.choiceMeaning(entry.getString("meaning")));
            learningWords.put(entry);
        }
        JSONObject answer = chat(settings, prompt("generate_learning_packs.txt"),
                new JSONObject().put("words", learningWords).toString(), Math.min(14000, 1200 + words.length() * 1600));
        JSONArray generated = requiredArray(answer, "packs", words.length(), words.length());
        java.util.Map<Integer, JSONObject> dictionary = new java.util.HashMap<>();
        for (int i = 0; i < words.length(); i++) dictionary.put(integerValue(words.getJSONObject(i), "id", 1, Integer.MAX_VALUE), words.getJSONObject(i));
        java.util.Map<Integer, JSONObject> safeById = new java.util.HashMap<>();
        for (int i = 0; i < generated.length(); i++) {
            JSONObject pack = generated.optJSONObject(i);
            if (pack == null) throw new Exception("AI 学习包格式不正确。");
            int id = integerValue(pack, "word_id", 1, Integer.MAX_VALUE);
            JSONObject original = dictionary.get(id);
            if (original == null || safeById.containsKey(id)) throw new Exception("AI 学习包词条 ID 未指定或重复。");
            String word = original.getString("word");
            if (!word.equalsIgnoreCase(requiredString(pack, "word", 100))) throw new Exception("AI 学习包拼写与词条不一致。");
            String fullMeaning = original.getString("meaning");
            String practiceMeaning = VocabularyRules.choiceMeaning(fullMeaning);
            JSONObject example = pack.optJSONObject("example");
            if (example == null) throw new Exception("AI 学习包缺少自然例句。");
            String en = requiredString(example, "en", 900);
            if (HAN.matcher(en).find() || !containsWholeWord(en, word)
                    || englishWordCount(en) < 6 || englishWordCount(en) > 55)
                throw new Exception("AI 例句长度、语言或目标词覆盖不正确。");
            String zh = requiredString(example, "zh", 1200);
            String usage = requiredString(example, "usage", 1500);
            if (!HAN.matcher(zh).find() || !HAN.matcher(usage).find()) throw new Exception("AI 例句翻译和用法需为中文。");
            JSONArray tiers = requiredArray(pack, "tiers", 3, 3);
            java.util.Map<Integer, JSONObject> tiersByLevel = new java.util.HashMap<>();
            Set<Set<String>> seenDistractorGroups = new HashSet<>();
            for (int j = 0; j < tiers.length(); j++) {
                JSONObject tier = tiers.optJSONObject(j);
                if (tier == null) throw new Exception("AI 选择题层级格式不正确。");
                int level = integerValue(tier, "level", 1, 3);
                if (tiersByLevel.containsKey(level)) throw new Exception("AI 选择题层级重复。");
                String question = requiredString(tier, "question", 500);
                if (!HAN.matcher(question).find() || question.contains("语境") || question.contains("句中") || question.contains("本句"))
                    throw new Exception("AI 选择题应询问单词大意。");
                String correct = requiredString(tier, "answer", 1);
                if (!correct.matches("[ABCD]")) throw new Exception("AI 选择题答案必须为 A、B、C 或 D。");
                JSONArray options = requiredArray(tier, "options", 4, 4);
                Set<String> ids = new HashSet<>();
                Set<String> texts = new HashSet<>();
                Set<String> distractors = new HashSet<>();
                java.util.Map<String, JSONObject> byOption = new java.util.HashMap<>();
                for (int k = 0; k < options.length(); k++) {
                    JSONObject option = options.optJSONObject(k);
                    if (option == null) throw new Exception("AI 选择题选项格式不正确。");
                    String optionId = requiredString(option, "id", 1);
                    String optionText = requiredString(option, "text", 160);
                    if (optionText.contains("\n") || optionText.matches(".*(?:补充词典|常用义补充).*")) throw new Exception("AI 选项应为简短词义。");
                    String normalized = normalizeMeaning(optionText);
                    if (!optionId.matches("[ABCD]") || !ids.add(optionId) || !texts.add(normalized)
                            || !HAN.matcher(optionText).find()) throw new Exception("AI 选择题选项须为四个不同的中文义项。");
                    if (optionId.equals(correct)) {
                        if (!normalized.equals(normalizeMeaning(practiceMeaning))) throw new Exception("AI 正确选项与给定大意不一致，已拒绝缓存。");
                        optionText = practiceMeaning;
                    } else {
                        if (normalized.equals(normalizeMeaning(fullMeaning))) throw new Exception("AI 错误选项也包含完整正确义项，已拒绝缓存。");
                        if (VocabularyRules.meaningsOverlap(optionText, fullMeaning))
                            throw new Exception("AI 干扰项与正确词义过于接近，已拒绝缓存。");
                        for (String other : distractors) if (VocabularyRules.meaningsOverlap(optionText, other))
                            throw new Exception("AI 干扰项意思过于接近，已拒绝缓存。");
                        distractors.add(normalized);
                    }
                    byOption.put(optionId, new JSONObject().put("id", optionId).put("text", optionText));
                }
                if (!seenDistractorGroups.add(new HashSet<>(distractors))) throw new Exception("AI 不同难度层重复使用全部干扰项，已拒绝缓存。");
                JSONArray safeOptions = new JSONArray();
                for (String optionId : new String[]{"A", "B", "C", "D"}) safeOptions.put(byOption.get(optionId));
                String explanation = requiredString(tier, "explanation", 2000);
                if (Pattern.compile("(?:选项|答案|正确项|错误项)\\s*[:：为是]?\\s*[A-D](?![A-Za-z])|(?<![A-Za-z])[A-D]\\s*(?:项|选项|正确|错误|是|为|表示)|第[一二三四1234]个?选?项")
                        .matcher(explanation).find())
                    throw new Exception("AI 题目解释引用固定选项位置，无法安全洗牌，已拒绝缓存。");
                tiersByLevel.put(level, new JSONObject().put("level", level)
                        .put("question", VocabularyRules.CHOICE_QUESTION).put("policyVersion", VocabularyRules.POLICY_VERSION)
                        .put("options", safeOptions).put("answer", correct).put("explanation", explanation));
            }
            JSONArray safeTiers = new JSONArray();
            for (int level = 1; level <= 3; level++) safeTiers.put(tiersByLevel.get(level));
            // The dictionary is authoritative; AI-supplied meanings are discarded, never replacing/deleting senses.
            safeById.put(id, new JSONObject().put("word_id", id).put("word", word)
                    .put("policyVersion", VocabularyRules.POLICY_VERSION)
                    .put("meanings", new JSONArray().put(fullMeaning))
                    .put("example", new JSONObject().put("en", en).put("zh", zh).put("usage", usage)).put("tiers", safeTiers));
        }
        JSONArray result = new JSONArray();
        for (int i = 0; i < words.length(); i++) result.put(safeById.get(words.getJSONObject(i).getInt("id")));
        return result;
    }

    public JSONObject evaluate(JSONObject settings, JSONObject reading, String translation) throws Exception {
        if (translation == null || translation.trim().isEmpty())
            throw new IllegalArgumentException("请先提交自己的中文翻译。");
        if (translation.length() > 8000) throw new IllegalArgumentException("翻译太长，请缩短到 8000 字以内。");
        String text = requiredString(reading, "text", 10000);
        JSONArray words = new JSONArray(requiredArray(reading, "used_words", 3, 8).toString());
        JSONArray novel = reading.optJSONArray("new_words");
        if (novel != null) for (int i = 0; i < novel.length(); i++) words.put(requiredString(novel.getJSONObject(i), "word", 100));
        JSONObject requestData = new JSONObject().put("reading_text", text).put("target_words", words)
                .put("learner_translation", translation.trim());
        JSONObject answer = chat(settings, prompt("evaluate_translation.txt"), requestData.toString(),
                "long".equals(reading.optString("length")) ? 5000 : 3000);
        Object scoreRaw = answer.get("score");
        if (!(scoreRaw instanceof Number)) throw new Exception("AI 评分必须是数值。");
        double score = ((Number) scoreRaw).doubleValue();
        if (!Double.isFinite(score) || score < 0 || score > 100)
            throw new Exception("AI 评分超出 0–100 范围。");
        JSONArray corrections = requiredArray(answer, "corrections", 0, 8);
        JSONArray safeCorrections = new JSONArray();
        for (int i = 0; i < corrections.length(); i++) {
            Object raw = corrections.get(i);
            if (!(raw instanceof JSONObject)) throw new Exception("AI 纠正列表格式不正确。");
            JSONObject item = (JSONObject) raw;
            String sourceSpan = requiredString(item, "source_span", 800);
            String userSpan = stringAllowEmpty(item, "user_span", 1000);
            if (!text.contains(sourceSpan))
                throw new Exception("AI 纠正引用了原文不存在的片段，请重新评价。");
            if (!userSpan.isEmpty() && !translation.contains(userSpan))
                throw new Exception("AI 纠正引用了译文不存在的片段，请重新评价。");
            safeCorrections.put(new JSONObject().put("source_span", sourceSpan).put("user_span", userSpan)
                    .put("issue", requiredString(item, "issue", 800))
                    .put("correction", requiredString(item, "correction", 1000))
                    .put("explanation", requiredString(item, "explanation", 1500)));
        }
        JSONArray notes = requiredArray(answer, "word_notes", 0, 16);
        Set<String> requested = new HashSet<>();
        for (int i = 0; i < words.length(); i++) {
            Object word = words.get(i);
            if (!(word instanceof String)) throw new Exception("阅读的目标词数据损坏。");
            requested.add(((String) word).toLowerCase(Locale.ROOT));
        }
        Set<String> seenNotes = new HashSet<>();
        JSONArray safeNotes = new JSONArray();
        for (int i = 0; i < notes.length(); i++) {
            Object raw = notes.get(i);
            if (!(raw instanceof JSONObject)) throw new Exception("AI 词义提示格式不正确。");
            JSONObject note = (JSONObject) raw;
            String word = requiredString(note, "word", 100);
            String normalized = word.toLowerCase(Locale.ROOT);
            if (!requested.contains(normalized) || !seenNotes.add(normalized))
                throw new Exception("AI 词义提示包含未指定或重复的词。");
            safeNotes.put(new JSONObject().put("word", word)
                    .put("meaning_in_context", requiredString(note, "meaning_in_context", 800))
                    .put("note", requiredString(note, "note", 1000)));
        }
        return new JSONObject().put("score", Math.round(score))
                .put("summary", requiredString(answer, "summary", 1500))
                .put("reference_translation", requiredString(answer, "reference_translation", 12000))
                .put("corrections", safeCorrections).put("word_notes", safeNotes)
                .put("next_tip", requiredString(answer, "next_tip", 1200));
    }

    /** A connection check still makes one very small billable API request. */
    public JSONObject probe(JSONObject settings) throws Exception {
        JSONObject result = chat(settings,
                "Return only the JSON object {\"ok\":true}. No markdown or explanation.", "Connection check.", 40);
        if (!(result.opt("ok") instanceof Boolean) || !result.getBoolean("ok"))
            throw new Exception("接口有响应，但未返回约定 JSON；请确认支持 Chat Completions 的模型。");
        return new JSONObject().put("ok", true).put("message", "连接成功，模型可返回 JSON。");
    }

    private JSONObject chat(JSONObject settings, String system, String user, int maxTokens) throws Exception {
        String base = settings.optString("apiBaseUrl", "").trim();
        String key = settings.optString("apiKey", "").trim();
        String model = settings.optString("apiModel", "").trim();
        if (base.isEmpty() || key.isEmpty() || model.isEmpty())
            throw new IllegalArgumentException("请先配置 API 地址、Key 和模型名称。");
        SecureSettings.validateHeaderValue(key, "API Key", 8192);
        SecureSettings.validateHeaderValue(model, "模型名称", 200);
        URL endpoint = resolveEndpoint(base, settings.optBoolean("allowLocalHttp", false));
        int timeout = settings.optInt("apiTimeoutSec", 45);
        if (timeout < 10 || timeout > 120) throw new IllegalArgumentException("API 超时应在 10–120 秒之间。");
        JSONObject request = new JSONObject().put("model", model).put("stream", false)
                .put("temperature", 0.35).put("max_tokens", maxTokens)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", system))
                        .put(new JSONObject().put("role", "user").put("content", user)));
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        try {
            connection.setInstanceFollowRedirects(false); // Never forward the bearer key to a redirected host.
            connection.setConnectTimeout(timeout * 1000);
            connection.setReadTimeout(timeout * 1000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setFixedLengthStreamingMode(body.length);
            ensureNotCancelled();
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                // Do not echo the remote body: it may contain credentials or the learner's submitted text.
                closeQuietly(connection.getErrorStream());
                throw new Exception(httpMessage(status));
            }
            String response;
            try (InputStream input = connection.getInputStream()) {
                response = new String(readBounded(input, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
            }
            JSONObject envelope = parseObject(response);
            JSONArray choices = envelope.optJSONArray("choices");
            if (choices == null || choices.length() == 0 || !(choices.opt(0) instanceof JSONObject))
                throw new Exception("接口未返回 choices；请确认兼容 Chat Completions。");
            JSONObject choice = choices.getJSONObject(0);
            if ("length".equals(choice.optString("finish_reason")))
                throw new Exception("AI 输出被截断，请重新尝试或换用支持足够输出长度的模型。");
            JSONObject message = choice.optJSONObject("message");
            if (message == null) throw new Exception("接口未返回 message。");
            if (message.has("refusal") && !message.isNull("refusal")
                    && !message.optString("refusal", "").trim().isEmpty())
                throw new Exception("模型未完成学习任务，请重新尝试。");
            Object content = message.opt("content");
            String answer;
            if (content instanceof String) answer = (String) content;
            else if (content instanceof JSONArray) {
                StringBuilder parts = new StringBuilder();
                JSONArray blocks = (JSONArray) content;
                for (int i = 0; i < blocks.length(); i++) {
                    JSONObject block = blocks.optJSONObject(i);
                    if (block != null && "text".equals(block.optString("type"))
                            && block.opt("text") instanceof String) parts.append(block.getString("text"));
                }
                answer = parts.toString();
            } else throw new Exception("模型没有返回可解析的文本。");
            return parseModelObject(answer);
        } catch (java.net.SocketTimeoutException e) {
            throw new Exception("API 请求超时，请检查网络或增加超时秒数。", e);
        } catch (IOException e) {
            if (e instanceof InterruptedIOException) throw new Exception("API 请求已取消。", e);
            throw new Exception("API 网络连接失败，请检查地址、网络和证书。", e);
        } catch (JSONException e) {
            throw new Exception("模型返回的数据格式不符合要求，请重新尝试。", e);
        } finally {
            connection.disconnect();
        }
    }

    /** Host -> /v1/chat/completions; /v1 -> /v1/chat/completions; an explicit endpoint remains unchanged. */
    public static URL resolveEndpoint(String base, boolean allowLocalHttp) {
        try {
            if (base == null || base.length() > 2048 || !base.equals(base.trim()))
                throw new IllegalArgumentException("API 地址不正确。");
            URI uri = new URI(base);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isEmpty() || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || uri.getPort() < -1 || uri.getPort() == 0
                    || uri.getPort() > 65535) throw new IllegalArgumentException("API 地址不正确。");
            boolean https = "https".equalsIgnoreCase(scheme);
            boolean http = "http".equalsIgnoreCase(scheme);
            boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "10.0.2.2".equals(host);
            if (!https && !(http && allowLocalHttp && local))
                throw new IllegalArgumentException("API 地址必须使用 HTTPS；仅开发模式允许指定本地 HTTP 地址。");
            String path = uri.getRawPath();
            if (path == null) path = "";
            if (path.contains("//") || path.contains("..") || path.toLowerCase(Locale.ROOT).contains("%2e"))
                throw new IllegalArgumentException("API 路径不正确。");
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (path.isEmpty()) path = "/v1/chat/completions";
            else if (!path.endsWith("/chat/completions")) path += "/chat/completions";
            // Reuse the raw authority/path/query rather than re-encoding percent escapes.
            String resolved = scheme.toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority() + path;
            if (uri.getRawQuery() != null) resolved += "?" + uri.getRawQuery();
            return new URL(resolved);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("API 地址格式不正确。", e);
        }
    }

    static boolean containsWholeWord(String text, String word) {
        return Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }

    static JSONObject parseModelObject(String content) throws Exception {
        String text = content.trim();
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            if (newline < 0 || !text.endsWith("```")) throw new Exception("模型的 JSON 代码块不完整。");
            String language = text.substring(3, newline).trim();
            if (!language.isEmpty() && !"json".equalsIgnoreCase(language))
                throw new Exception("模型未返回 JSON 代码块。");
            text = text.substring(newline + 1, text.length() - 3).trim();
        }
        return parseObject(text);
    }

    private static JSONObject parseObject(String text) throws Exception {
        JSONTokener tokener = new JSONTokener(text);
        Object value = tokener.nextValue();
        if (!(value instanceof JSONObject) || tokener.nextClean() != 0)
            throw new Exception("模型未返回独立 JSON 对象，请重新尝试。");
        return (JSONObject) value;
    }

    private static List<String> validateWords(JSONArray targets, int min, int max, boolean requireIds) throws Exception {
        if (targets == null || targets.length() < min || targets.length() > max)
            throw new IllegalArgumentException("本次目标词数量应在 " + min + "–" + max + " 之间。");
        List<String> words = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.optJSONObject(i);
            if (target == null) throw new IllegalArgumentException("目标词格式不正确。");
            String word = requiredString(target, "word", 100);
            if (!word.matches("[A-Za-z]+(?:[-' ][A-Za-z]+)*") || !seen.add(word.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("目标词包含不支持的字符或重复词。");
            requiredString(target, "meaning", 6000);
            requiredString(target, "pos", 200);
            if (requireIds && !ids.add(integerValue(target, "id", 1, Integer.MAX_VALUE)))
                throw new IllegalArgumentException("学习包词条 ID 重复。");
            words.add(word);
        }
        return words;
    }

    private static Set<String> normalizedSet(List<String> words) {
        Set<String> result = new HashSet<>();
        for (String word : words) result.add(word.toLowerCase(Locale.ROOT));
        return result;
    }

    static int englishWordCount(String text) {
        java.util.regex.Matcher matcher = Pattern.compile("[A-Za-z]+(?:[-'][A-Za-z]+)*").matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static String configValue(JSONObject object, String key, String... choices) throws Exception {
        if (object == null || !(object.opt(key) instanceof String)) throw new IllegalArgumentException("阅读配置 " + key + " 格式不正确。");
        String value = object.getString(key);
        for (String choice : choices) if (choice.equals(value)) return value;
        throw new IllegalArgumentException("阅读配置 " + key + " 不在允许范围。");
    }

    private static int integerValue(JSONObject object, String key, int min, int max) throws Exception {
        Object value = object.opt(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException(key + " 必须是整数。");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max)
            throw new IllegalArgumentException(key + " 超出允许范围。");
        return (int) number;
    }

    private static String normalizeMeaning(String value) { return value.trim().replaceAll("\\s+", " "); }

    private String prompt(String filename) throws IOException {
        return promptSource.load(filename);
    }

    private static String requiredString(JSONObject object, String key, int max) throws Exception {
        String text = stringAllowEmpty(object, key, max);
        if (text.trim().isEmpty()) throw new Exception("AI 字段 " + key + " 不能为空。");
        return text.trim();
    }

    private static String stringAllowEmpty(JSONObject object, String key, int max) throws Exception {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).length() > max)
            throw new Exception("AI 字段 " + key + " 格式或长度不正确。");
        return (String) value;
    }

    private static JSONArray requiredArray(JSONObject object, String key, int min, int max) throws Exception {
        Object value = object.opt(key);
        if (!(value instanceof JSONArray) || ((JSONArray) value).length() < min || ((JSONArray) value).length() > max)
            throw new Exception("AI 列表 " + key + " 格式或长度不正确。");
        return (JSONArray) value;
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            ensureNotCancelled();
            if (output.size() + count > limit) throw new IOException("Response exceeds configured byte limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void ensureNotCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Request cancelled");
    }

    private static void closeQuietly(InputStream input) {
        if (input != null) try { input.close(); } catch (IOException ignored) { }
    }

    private static String httpMessage(int status) {
        if (status == 401) return "API 认证失败（401），请检查 Key。";
        if (status == 403) return "API 拒绝访问（403），请检查模型权限或服务商限制。";
        if (status == 404) return "API 地址或模型不存在（404），请检查地址和模型名称。";
        if (status == 429) return "API 请求受限（429），请检查额度或稍后手动重试。";
        if (status >= 500) return "API 服务暂时失败（" + status + "），请稍后手动重试。";
        if (status >= 300 && status < 400) return "API 返回跳转（" + status + "），为保护 Key 已停止；请填入最终 HTTPS 接口地址。";
        return "API 请求未成功（" + status + "），请检查接口是否兼容 Chat Completions。";
    }
}
