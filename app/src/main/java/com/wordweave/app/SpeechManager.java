package com.wordweave.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One playback session for words and readings: system TTS or downloaded native models. */
public final class SpeechManager {
    public interface Listener { void onStatus(JSONObject status); }
    private final Context owner, application;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SpeechPlayback playback = new SpeechPlayback();
    private final OfflineSpeechManager offline;
    private Listener listener;
    private TextToSpeech speech;
    private final List<Voice> voices = new ArrayList<>();
    private JSONArray engines = new JSONArray();
    private volatile String provider = "system";
    private String requestedEngine = "", requestedVoice = "", locale = "en-GB";
    private String currentEngine = "", currentVoice = "", message = "正在初始化设备语音引擎。";
    private float rate = .85f;
    private volatile boolean ready, initialized, destroyed;
    private volatile String snapshot = "{\"engines\":[],\"voices\":[],\"ready\":false}";
    private int bindingGeneration, utteranceEpoch, utteranceIndex;
    private long utteranceToken;
    private String currentUtterance = "";
    private boolean returningFromProvider, usedFallback;

    public SpeechManager(Context context) { this(context, null, new ModelDownloadManager(context)); }
    public SpeechManager(Context context, SecureSettings settings) { this(context, settings, new ModelDownloadManager(context)); }
    public SpeechManager(Context context, SecureSettings settings, ModelDownloadManager downloads) {
        owner = context; application = context.getApplicationContext();
        offline = new OfflineSpeechManager(application, downloads, playback, () -> onMain(this::publish));
        JSONObject preferences = new JSONObject();
        if (settings != null) try { preferences = settings.loadPublic(); } catch (Exception ignored) { }
        configure(preferences);
    }

    public void setListener(Listener value) { onMain(() -> { listener = value; publish(); }); }

    /** Model manifests are rechecked on every read, including just-completed downloads. */
    public JSONObject options() {
        try {
            JSONObject result = new JSONObject(snapshot);
            result.put("provider", provider).put("ttsProvider", provider).put("systemReady", ready)
                    .put("ready", "offline".equals(provider) ? offline.isReady() : ready)
                    .put("offlineModels", offline.models()).put("activeModelId", offline.modelId())
                    .put("activeSpeakerId", offline.speakerId());
            return result;
        } catch (Exception error) { throw new IllegalStateException("无法读取语音选项。", error); }
    }

    public JSONObject status() {
        SpeechPlayback.Snapshot s = playback.snapshot();
        try {
            long readingId = 0;
            if (s.context.startsWith("reading:")) try { readingId = Long.parseLong(s.context.substring(8)); } catch (NumberFormatException ignored) { }
            String note = s.message;
            if (s.text.isEmpty() && "idle".equals(s.state)) note = "offline".equals(provider)
                    ? offline.isDeleting() ? OfflineSpeechManager.DELETING_MESSAGE : offline.isReady()
                        ? "离线模型已下载，可开始发音。" : "请先在 App 中下载所选离线语音模型。" : message;
            if (OfflineSpeechManager.DELETING_MESSAGE.equals(note) && !offline.isDeleting())
                note = offline.isReady() ? "删除操作已结束，可以重新尝试播放。" : "模型不可用，请先在 App 中下载所选模型。";
            return new JSONObject().put("state", s.state).put("provider", provider).put("context", s.context)
                    .put("readingId", readingId).put("segmentIndex", s.segmentIndex).put("segmentCount", s.segmentCount)
                    .put("completedSegments", s.completedSegments).put("completedChars", s.completedChars).put("totalChars", s.totalChars)
                    .put("completed", s.completed).put("canPause", s.canPause).put("canResume", s.canResume)
                    .put("pauseBoundary", "offline".equals(provider) ? "sample" : "sentence").put("message", note)
                    .put("modelId", "offline".equals(provider) ? offline.modelId() : "")
                    .put("speakerId", "offline".equals(provider) ? offline.speakerId() : 0);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public void configure(JSONObject settings) {
        final JSONObject copy;
        try { copy = new JSONObject(settings.toString()); } catch (Exception e) { throw new IllegalArgumentException("语音设置无效。", e); }
        final String engine = clean(copy.optString("ttsEngine", ""), 250);
        final String voice = clean(copy.optString("ttsVoice", ""), 512);
        final String language = "en-US".equals(copy.optString("ttsLocale")) ? "en-US" : "en-GB";
        final String selectedProvider = "offline".equals(copy.optString("ttsProvider")) ? "offline" : "system";
        double value = copy.optDouble("ttsRate", .85);
        final float speed = (float) (Double.isFinite(value) ? Math.max(.5, Math.min(1.5, value)) : .85);
        onMain(() -> {
            if (destroyed) return;
            String oldModel = offline.modelId(); int oldSpeaker = offline.speakerId();
            offline.configure(copy);
            boolean engineChanged = !requestedEngine.equals(engine);
            boolean changed = !provider.equals(selectedProvider) || engineChanged || !requestedVoice.equals(voice)
                    || !locale.equals(language) || rate != speed || !oldModel.equals(offline.modelId()) || oldSpeaker != offline.speakerId();
            if (changed) { playback.stop(); stopHardware(); }
            provider = selectedProvider; requestedEngine = engine; requestedVoice = voice; locale = language; rate = speed;
            if ("system".equals(provider)) {
                if (speech == null || engineChanged || !initialized) restart();
                else { selectPreferredVoice(); publish(); }
            } else { listInstalledEngines(); publish(); }
        });
    }

    private void restart() {
        ready = false; initialized = false; currentVoice = ""; voices.clear();
        final int ticket = ++bindingGeneration;
        currentUtterance = ""; utteranceEpoch++;
        if (speech != null) { speech.stop(); speech.shutdown(); speech = null; }
        listInstalledEngines();
        String selected = requestedEngine;
        if (!selected.isEmpty() && !engineInstalled(selected)) { message = "所选系统引擎未安装，正在使用默认引擎。"; selected = ""; }
        else message = "正在连接系统语音引擎。";
        currentEngine = selected; publish();
        final String selectedEngine = selected;
        try {
            TextToSpeech.OnInitListener callback = result -> main.post(() -> initialized(ticket, result));
            speech = selectedEngine.isEmpty() ? new TextToSpeech(application, callback) : new TextToSpeech(application, callback, selectedEngine);
        } catch (RuntimeException error) { message = "系统语音引擎启动失败，请安装或切换英语引擎。"; publish(); }
    }

    private void initialized(int ticket, int result) {
        if (destroyed || ticket != bindingGeneration || speech == null) return;
        initialized = result == TextToSpeech.SUCCESS;
        if (!initialized) { ready = false; message = "系统语音未就绪，请检查引擎与英语数据。"; publish(); return; }
        if (currentEngine.isEmpty()) currentEngine = safe(speech.getDefaultEngine());
        listInstalledEngines(); voices.clear();
        try {
            Set<Voice> available = speech.getVoices();
            if (available != null) for (Voice voice : available)
                if (voice != null && voice.getLocale() != null && "en".equalsIgnoreCase(voice.getLocale().getLanguage())) voices.add(voice);
        } catch (RuntimeException ignored) { }
        voices.sort(Comparator.comparing(Voice::getName));
        speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { main.post(() -> {
                if (matching(ticket, id) && playback.started(utteranceToken, utteranceIndex)) publish();
            }); }
            @Override public void onDone(String id) { main.post(() -> {
                if (!matching(ticket, id)) return;
                long token = utteranceToken; int index = utteranceIndex; currentUtterance = "";
                if (!playback.complete(token, index)) return;
                publish();
                if (playback.isCurrent(token)) speakSystem(token);
            }); }
            @Override public void onError(String id) { onError(id, TextToSpeech.ERROR); }
            @Override public void onError(String id, int code) { main.post(() -> handleSpeechError(ticket, id, code)); }
        });
        selectPreferredVoice(); publish();
    }
    private boolean matching(int ticket, String id) {
        return !destroyed && "system".equals(provider) && ticket == bindingGeneration && !currentUtterance.isEmpty()
                && currentUtterance.equals(id) && playback.isCurrent(utteranceToken) && !playback.isPaused(utteranceToken);
    }

    private void selectPreferredVoice() {
        if (!initialized || speech == null) { ready = false; return; }
        speech.setSpeechRate(rate);
        Voice wanted = null;
        for (Voice voice : voices) if (voice.getName().equals(requestedVoice)) { wanted = voice; break; }
        if (wanted != null && !missingData(wanted) && speech.setVoice(wanted) == TextToSpeech.SUCCESS) {
            ready = true; currentVoice = wanted.getName();
            message = wanted.isNetworkConnectionRequired() ? "已选系统网络音色，发音需要提供商网络服务。" : "已选系统离线音色。"; return;
        }
        Voice fallback = bestVoice(false, "");
        if (fallback == null) fallback = bestVoice(true, "");
        if (fallback != null && speech.setVoice(fallback) == TextToSpeech.SUCCESS) {
            ready = true; currentVoice = fallback.getName();
            message = requestedVoice.isEmpty() ? "已自动选择可用英语音色。" : "所选系统音色不可用，暂时改用可用英语音色。"; return;
        }
        int result = speech.setLanguage(Locale.forLanguageTag(locale));
        Voice actual = speech.getVoice();
        ready = result >= TextToSpeech.LANG_AVAILABLE && actual != null && actual.getLocale() != null && "en".equalsIgnoreCase(actual.getLocale().getLanguage());
        currentVoice = ready ? safe(actual.getName()) : "";
        message = ready ? "已使用系统引擎默认英语音色。" : "没有可用英语音色，请下载语音数据或切换引擎。";
    }
    private Voice bestVoice(boolean allowNetwork, String avoidName) {
        List<SpeechVoice.Candidate> choices = new ArrayList<>();
        for (Voice voice : voices) choices.add(new SpeechVoice.Candidate(voice.getName(), voice.getLocale().getCountry(),
                voice.isNetworkConnectionRequired(), !missingData(voice), voice.getQuality(), voice.getLatency()));
        String name = SpeechVoice.choose(choices, Locale.forLanguageTag(locale).getCountry(), allowNetwork, avoidName);
        for (Voice voice : voices) if (voice.getName().equals(name)) return voice;
        return null;
    }

    public boolean speak(String text) { return speak(text, "word"); }
    public boolean speak(String text, String context) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("发音操作请在主线程执行。");
        if (destroyed) throw new IllegalStateException("语音播放器已关闭。");
        int maximum = "offline".equals(provider) ? 420 : Math.min(2500, TextToSpeech.getMaxSpeechInputLength());
        // begin validates and splits before replacing its token; invalid input leaves old audio intact.
        long token = playback.begin(text, context, maximum);
        stopHardware(); usedFallback = false;
        try {
            if ("offline".equals(provider)) { offline.speak(token); publish(); return true; }
            if (!ready || speech == null) throw new IllegalStateException(message);
            selectPreferredVoice();
            if (!ready) throw new IllegalStateException(message);
            return speakSystem(token);
        } catch (RuntimeException error) { playback.fail(token, error.getMessage()); publish(); return false; }
    }
    private boolean speakSystem(long token) {
        if (!playback.isCurrent(token) || playback.isPaused(token)) return false;
        int index = playback.nextIndex(token);
        List<SpeechPlayback.Segment> segments = playback.segments(token);
        if (index < 0 || index >= segments.size() || speech == null) return false;
        currentUtterance = "ww_" + bindingGeneration + "_" + token + "_" + (++utteranceEpoch) + "_" + index;
        utteranceToken = token; utteranceIndex = index;
        int result = speech.speak(segments.get(index).text, TextToSpeech.QUEUE_FLUSH, null, currentUtterance);
        if (result != TextToSpeech.SUCCESS) return handleSpeechError(bindingGeneration, currentUtterance, result);
        publish(); return true;
    }
    private boolean handleSpeechError(int ticket, String id, int errorCode) {
        if (!matching(ticket, id) || speech == null) return false;
        if (!usedFallback) {
            Voice fallback = bestVoice(false, currentVoice);
            if (fallback != null && speech.setVoice(fallback) == TextToSpeech.SUCCESS) {
                usedFallback = true; currentVoice = fallback.getName();
                message = "系统音色失败，已改用另一设备离线音色。";
                return speakSystem(utteranceToken);
            }
        }
        String reason = errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET ? "系统音色数据尚未安装，请下载英语语音数据。" : "系统发音失败，请检查引擎或网络音色的连接。";
        playback.fail(utteranceToken, reason); stopHardware(); publish(); return false;
    }

    public void pause() { onMain(() -> {
        if (!playback.pause()) return;
        if ("offline".equals(provider)) offline.pause();
        else { currentUtterance = ""; utteranceEpoch++; if (speech != null) speech.stop(); }
        publish();
    }); }
    public void resume() { onMain(() -> {
        if (!playback.resume()) return;
        if ("offline".equals(provider)) offline.resume();
        else if (ready && speech != null) speakSystem(playback.snapshot().token);
        else playback.fail(playback.snapshot().token, "系统语音未就绪，请重新选择音色。");
        publish();
    }); }
    public void stop() { onMain(() -> { playback.stop(); stopHardware(); publish(); }); }
    private void stopHardware() {
        currentUtterance = ""; utteranceEpoch++;
        if (speech != null) speech.stop(); offline.stop();
    }
    public void releaseModel(String modelId, Runnable afterRelease) { onMain(() -> {
        if ("offline".equals(provider) && offline.modelId().equals(modelId)) { playback.stop(); stopHardware(); publish(); }
        offline.releaseModel(modelId, () -> onMain(() -> { if (afterRelease != null) afterRelease.run(); publish(); }));
    }); }
    /** Cache-only invalidation owns no subsequent filesystem delete, so it releases its own gate. */
    public void invalidateModel(String modelId) { releaseModel(modelId, () -> modelDeletionFinished(modelId)); }
    public void modelDeletionFinished(String modelId) { onMain(() -> { offline.modelDeletionFinished(modelId); publish(); }); }

    /** System voice data remains provider-owned; model download happens inside this app. */
    public void downloadVoiceData() { onMain(() -> {
        if (destroyed) return;
        returningFromProvider = true;
        Intent intent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
        String selected = !requestedEngine.isEmpty() && engineInstalled(requestedEngine) ? requestedEngine : currentEngine;
        if (!selected.isEmpty()) intent.setPackage(selected);
        try { launch(intent); }
        catch (ActivityNotFoundException unavailable) { message = "该系统提供商没有下载页面，请在设备 TTS 设置安装英语数据。"; publish(); openSettingsMain(); }
        catch (RuntimeException blocked) { message = "设备不允许打开系统语音提供商，请手动进入设置。"; publish(); }
    }); }
    public void openSettings() { onMain(() -> { if (!destroyed) { returningFromProvider = true; openSettingsMain(); } }); }
    private void openSettingsMain() {
        try { launch(new Intent("com.android.settings.TTS_SETTINGS")); }
        catch (ActivityNotFoundException unsupported) {
            try { launch(new Intent(Settings.ACTION_SETTINGS)); } catch (RuntimeException e) { message = "请手动进入设备的 TTS 设置。"; publish(); }
        } catch (RuntimeException e) { message = "请手动进入设备的 TTS 设置。"; publish(); }
    }
    private void launch(Intent intent) { if (!(owner instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); owner.startActivity(intent); }
    public void onResume() { onMain(() -> {
        if (destroyed) return;
        if (returningFromProvider) { returningFromProvider = false; playback.stop(); stopHardware(); if ("system".equals(provider)) restart(); }
        publish();
    }); }
    public void destroy() {
        destroyed = true;
        onMain(() -> {
            bindingGeneration++; playback.stop(); stopHardware(); offline.destroy();
            if (speech != null) { speech.shutdown(); speech = null; }
            voices.clear(); ready = false; initialized = false; publish(); listener = null;
        });
    }

    private void listInstalledEngines() {
        engines = new JSONArray(); Set<String> seen = new HashSet<>();
        try {
            for (ResolveInfo info : application.getPackageManager().queryIntentServices(new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)) {
                if (info.serviceInfo == null || !seen.add(info.serviceInfo.packageName)) continue;
                engines.put(new JSONObject().put("name", info.serviceInfo.packageName).put("label", info.loadLabel(application.getPackageManager()).toString()));
            }
        } catch (Exception ignored) { }
    }
    private boolean engineInstalled(String name) {
        for (int i = 0; i < engines.length(); i++) if (name.equals(engines.optJSONObject(i).optString("name"))) return true;
        return false;
    }
    private void publish() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::publish); return; }
        try {
            JSONArray list = new JSONArray();
            for (Voice voice : voices) {
                String accent = "GB".equalsIgnoreCase(voice.getLocale().getCountry()) ? "英式" : "US".equalsIgnoreCase(voice.getLocale().getCountry()) ? "美式" : "英语";
                list.put(new JSONObject().put("name", voice.getName()).put("label", accent + " · " + voice.getName()
                        + (voice.isNetworkConnectionRequired() ? "（需要网络）" : "（离线）") + (missingData(voice) ? " · 待下载" : ""))
                        .put("locale", voice.getLocale().toLanguageTag()).put("network", voice.isNetworkConnectionRequired())
                        .put("quality", voice.getQuality()).put("installed", !missingData(voice)));
            }
            snapshot = new JSONObject().put("engines", engines).put("voices", list).put("ready", ready)
                    .put("currentEngine", currentEngine).put("currentVoice", currentVoice).put("requestedEngine", requestedEngine)
                    .put("requestedVoice", requestedVoice).put("message", message).toString();
            if (listener != null) listener.onStatus(status());
        } catch (Exception ignored) { }
    }
    private static boolean missingData(Voice voice) { return voice.getFeatures() != null && voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String clean(String value, int maximum) {
        if (value.length() > maximum) throw new IllegalArgumentException("语音引擎或音色标识过长。");
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) throw new IllegalArgumentException("语音标识包含控制字符。");
        return value.trim();
    }
    private void onMain(Runnable action) { if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action); }
}
