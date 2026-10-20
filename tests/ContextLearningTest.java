package com.wordweave.app;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Production validation and AI client against local fixtures; never calls a real provider. */
public final class ContextLearningTest {
    private static int checks;
    private static String mode = "materials";
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        JSONArray words = words(); JSONObject materials = materials();
        JSONObject safe = ContextRules.validateMaterials(materials, words, true);
        check(safe.getJSONArray("sentences").length() == 2, "multiple words appear across two distinct situations");
        check(ContextRules.represents("The team adapts quickly.", words.getJSONObject(0)), "dictionary inflections count as target occurrences");
        check(ContextRules.selectedToken("It's useful.", "it’s").equals("It's"), "curly apostrophes normalize safely");
        fails(() -> ContextRules.selectedToken("They adapt quickly.", "observe"), "out-of-sentence requests rejected");
        fails(() -> ContextRules.selectedToken("They adapt quickly.", "<script>"), "non-token selection rejected");
        JSONObject sameScene = copy(materials); sameScene.getJSONArray("sentences").getJSONObject(1).put("scene", "校园");
        fails(() -> ContextRules.validateMaterials(sameScene, words, true), "each target needs two genuinely labeled situations");
        JSONObject missing = copy(materials); missing.getJSONArray("sentences").getJSONObject(1).put("text", "Doctors observe the patient carefully during recovery.");
        fails(() -> ContextRules.validateMaterials(missing, words, true), "one target absent from the second context rejected");
        JSONObject duplicate = copy(materials); duplicate.getJSONArray("sentences").getJSONObject(1).put("text", duplicate.getJSONArray("sentences").getJSONObject(0).getString("text"));
        fails(() -> ContextRules.validateMaterials(duplicate, words, true), "duplicate sentences rejected");
        JSONObject badNote = copy(materials); badNote.getJSONArray("sentences").getJSONObject(0).getJSONArray("wordNotes").getJSONObject(0).put("wordId", 999);
        fails(() -> ContextRules.validateMaterials(badNote, words, true), "invented dictionary IDs rejected");
        JSONObject chinese = copy(materials); chinese.getJSONArray("sentences").getJSONObject(0).put("text", "Students adapt 调整 their plans and observe the garden every morning.");
        fails(() -> ContextRules.validateMaterials(chinese, words, true), "hidden translations do not leak into English text");
        JSONObject session = copy(safe).put("targetWords", words).put("viewed", new JSONObject()).put("hints", new JSONObject());
        fails(() -> ContextRules.effectiveGrade(session, 1, 3), "unread material is not independently understood");
        session.getJSONObject("viewed").put("s1", 1);
        fails(() -> ContextRules.effectiveGrade(session, 1, 3), "both contexts must be visited before recording");
        session.getJSONObject("viewed").put("s2", 2);
        check(ContextRules.effectiveGrade(session, 1, 3) == 3, "unhinted self-grade remains independent");
        session.getJSONObject("hints").put("1", true);
        check(ContextRules.effectiveGrade(session, 1, 4) == 1, "word or sentence explanation caps only the exposed word");
        check(ContextRules.effectiveGrade(session, 2, 3) == 3, "another target retains independent evidence");
        check(ContextRules.effectiveGrade(session, 1, 0) == 0, "failure remains failure after explanation");
        session.put("id", 10).put("createdAt", 1).put("revision", 1).put("grades", new JSONObject().put("1", 1))
                .put("gradeTimes", new JSONObject().put("1", 2)).put("revealed", new JSONObject())
                .put("wordExplanations", new JSONObject()).put("unknownWords", new JSONArray().put(new JSONObject().put("surface", "Students").put("sentenceId", "s1")));
        java.util.Set<Integer> validIds = new java.util.HashSet<>(java.util.Arrays.asList(1, 2));
        ContextLearningRepository.validateBackup(new JSONArray().put(session), validIds);
        check(session.getJSONObject("hints").optBoolean("1"), "backup validation preserves word hint evidence");
        JSONObject badBackup = copy(session); badBackup.getJSONArray("unknownWords").getJSONObject(0).put("surface", "invented");
        fails(() -> ContextLearningRepository.validateBackup(new JSONArray().put(badBackup), validIds), "backup cannot introduce a word absent from the sentence");
        fails(() -> ContextLearningRepository.validateBackup(new JSONArray().put(session), new java.util.HashSet<Integer>()), "backup vocabulary IDs must exist");
        JSONArray vocabulary = new JSONObject(read(root.resolve("app/src/main/assets/vocabulary.json"))).getJSONArray("words");
        JSONObject lessons = new JSONObject(read(root.resolve("app/src/main/assets/lessons.json"))).getJSONObject("lessons");
        for (int i = 0; i < vocabulary.length(); i++) {
            JSONObject word = vocabulary.getJSONObject(i), lesson = lessons.getJSONObject(String.valueOf(word.getInt("id")));
            check(ContextRules.represents(lesson.getString("example"), word), "offline target or its dictionary form is present: " + word.getString("word"));
        }
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                requests.incrementAndGet(); JSONObject request = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                JSONObject data = new JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"));
                JSONObject answer;
                if (mode.equals("materials")) { check(data.getJSONArray("words").length() == 2, "AI receives the actual target set"); answer = materials(); }
                else if (mode.equals("sentence")) answer = new JSONObject().put("text", data.getString("sentence")).put("translation", "学生们调整计划，每天早晨观察花园。")
                        .put("explanation", "并列的两个动作说明学生如何开展活动。").put("structure", "Students是主语，adapt和observe为并列谓语。").put("readingTip", "两个并列动作可分为两个意群。");
                else answer = new JSONObject().put("surface", data.getString("surface")).put("lemma", "student").put("pos", "n.").put("meaning", "学生们")
                        .put("usage", "本句作主语，指开展观察活动的学生。").put("formNote", "这是student的复数形式。");
                byte[] bytes = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message", new JSONObject().put("content", answer.toString())))).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Exception e) { exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            JSONObject settings = new JSONObject().put("apiBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1").put("allowLocalHttp", true)
                    .put("apiKey", "test-only-key").put("apiModel", "fixture-model").put("apiTimeoutSec", 10);
            AiClient client = new AiClient((AiClient.PromptSource) filename -> read(root.resolve("app/src/main/assets/prompts/" + filename)));
            JSONObject generated = client.generateContextLessons(settings, words);
            check(generated.optString("source").equals("ai"), "actual client validates and labels generated material");
            JSONObject sentence = generated.getJSONArray("sentences").getJSONObject(0);
            mode = "sentence"; JSONObject explanation = client.explainContextSentence(settings, sentence);
            check(explanation.getString("structure").contains("并列谓语"), "sentence structure is returned through real protocol parsing");
            mode = "word"; JSONObject word = client.explainContextWord(settings, sentence, "Students", new JSONArray());
            check(word.getString("meaning").equals("学生们"), "words outside the selected vocabulary can be explained in context");
            fails(() -> ContextRules.validateWordExplanation(word.put("surface", "invented"), sentence, "Students"), "mismatched selected word rejected");
            check(requests.get() == 3, "only three explicitly requested fixture API calls");
        } finally { server.stop(0); }
        System.out.println("ContextLearning: " + checks + " checks; offline coverage of all " + vocabulary.length() + " words and 3 local AI requests passed");
    }
    private static JSONArray words() throws Exception { return new JSONArray().put(new JSONObject().put("id", 1).put("word", "adapt").put("meaning", "v. 适应；调整").put("pos", "v.").put("forms", "第三人称单数：adapts；过去式：adapted"))
            .put(new JSONObject().put("id", 2).put("word", "observe").put("meaning", "v. 观察；遵守").put("pos", "v.").put("forms", "过去式：observed")); }
    private static JSONObject materials() throws Exception {
        JSONArray sentences = new JSONArray();
        for (String[] pair : new String[][]{{"Students adapt their plans and observe the garden every morning.", "校园"}, {"Doctors adapt the treatment and observe each patient during recovery.", "医疗"}}) {
            sentences.put(new JSONObject().put("text", pair[0]).put("scene", pair[1]).put("translation", "参与者调整计划并观察相关对象。")
                    .put("explanation", "两个并列动作表达调整与观察。").put("structure", "主语后接两个由and连接的谓语。").put("readingTip", "可按两个并列动作划分意群。")
                    .put("wordNotes", new JSONArray().put(new JSONObject().put("wordId", 1).put("pos", "v.").put("meaning", "调整").put("usage", "说明针对对象进行调整。"))
                            .put(new JSONObject().put("wordId", 2).put("pos", "v.").put("meaning", "观察").put("usage", "说明观察对象的情况。"))));
        }
        return new JSONObject().put("title", "不同场景中的调整与观察").put("sentences", sentences);
    }
    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private static String read(Path file) throws java.io.IOException { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
    private interface Checked { void run() throws Exception; }
    private static void fails(Checked call, String message) throws Exception { boolean failed=false;try{call.run();}catch(Exception expected){failed=true;}check(failed,message); }
    private static void check(boolean condition, String message) { checks++;if(!condition)throw new AssertionError(message); }
}
