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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Device-installed TTS providers own their voice data; this app never hosts voice files. */
public final class SpeechManager {
    private final Context owner;
    private final Context application;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech speech;
    private List<Voice> voices = new ArrayList<>();
    private JSONArray engines = new JSONArray();
    private String requestedEngine = "", requestedVoice = "", locale = "en-GB";
    private String currentEngine = "", currentVoice = "", message = "正在初始化设备语音引擎。";
    private float rate = 0.85f;
    private volatile boolean ready, initialized, destroyed;
    private volatile String snapshot = "{\"engines\":[],\"voices\":[],\"ready\":false,\"currentEngine\":\"\",\"currentVoice\":\"\"}";
    private int generation;
    private boolean returningFromProvider, usedFallback;
    private String utterancePrefix = "";
    private List<String> activeChunks = new ArrayList<>();
    private final Map<String, Integer> queuedChunks = new HashMap<>();
    private final SpeechProgress progress = new SpeechProgress();

    public SpeechManager(Context context) { this(context, null); }

    public SpeechManager(Context context, SecureSettings settings) {
        owner = context;
        application = context.getApplicationContext();
        JSONObject preferences = new JSONObject();
        if (settings != null) {
            try { preferences = settings.loadPublic(); } catch (Exception ignored) { }
        }
        configure(preferences);
    }

    /** Safe to read from a bridge worker; every result is a separate JSON object. */
    public JSONObject options() {
        try { return new JSONObject(snapshot); }
        catch (Exception impossible) { return new JSONObject(); }
    }

    /** Preferences are persisted by SecureSettings; engine binding is asynchronous. */
    public void configure(JSONObject settings) {
        final String engine = clean(settings.optString("ttsEngine", ""), 250);
        final String voice = clean(settings.optString("ttsVoice", ""), 512);
        final String language = "en-US".equals(settings.optString("ttsLocale")) ? "en-US" : "en-GB";
        final double value = settings.optDouble("ttsRate", 0.85);
        final float speed = (float) (Double.isFinite(value) ? Math.max(0.5, Math.min(1.5, value)) : 0.85);
        onMain(() -> {
            if (destroyed) return;
            boolean differentEngine = !requestedEngine.equals(engine);
            requestedEngine = engine;
            requestedVoice = voice;
            locale = language;
            rate = speed;
            if (speech == null || differentEngine || !initialized) restart();
            else { selectPreferredVoice(); publish(); }
        });
    }

    private void restart() {
        ready = false;
        initialized = false;
        currentVoice = "";
        voices.clear();
        clearQueue();
        final int ticket = ++generation;
        if (speech != null) { speech.stop(); speech.shutdown(); speech = null; }
        listInstalledEngines();
        String selected = requestedEngine;
        if (!selected.isEmpty() && !engineInstalled(selected)) {
            message = "所选语音引擎尚未安装，正在使用系统默认引擎。";
            selected = "";
        } else message = "正在连接语音引擎。";
        currentEngine = selected;
        publish();
        final String selectedEngine = selected;
        try {
            TextToSpeech.OnInitListener listener = status -> main.post(() -> initialized(ticket, status));
            speech = selectedEngine.isEmpty() ? new TextToSpeech(application, listener)
                    : new TextToSpeech(application, listener, selectedEngine);
        } catch (RuntimeException error) {
            message = "设备语音引擎启动失败，请安装或切换英语语音引擎。";
            publish();
        }
    }

    private void initialized(int ticket, int status) {
        if (destroyed || ticket != generation || speech == null) return;
        initialized = status == TextToSpeech.SUCCESS;
        if (!initialized) {
            ready = false;
            message = "语音引擎未就绪，请检查引擎安装与英语语音数据。";
            publish();
            return;
        }
        if (currentEngine.isEmpty()) currentEngine = safe(speech.getDefaultEngine());
        listInstalledEngines();
        try {
            Set<Voice> available = speech.getVoices();
            if (available != null) for (Voice voice : available) {
                if (voice != null && voice.getLocale() != null &&
                        "en".equalsIgnoreCase(voice.getLocale().getLanguage())) voices.add(voice);
            }
        } catch (RuntimeException ignored) { }
        Collections.sort(voices, Comparator.comparing(voice -> voice.getName()));
        speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) {
                main.post(() -> {
                    Integer index = queuedChunks.get(id);
                    if (ticket != generation || index == null) return;
                    progress.complete(index);
                    if (progress.nextIndex() == activeChunks.size()) clearQueue();
                });
            }
            @Override public void onError(String id) { onError(id, TextToSpeech.ERROR); }
            @Override public void onError(String id, int errorCode) {
                main.post(() -> handleSpeechError(ticket, id, errorCode));
            }
        });
        selectPreferredVoice();
        publish();
    }

    private void selectPreferredVoice() {
        if (!initialized || speech == null) { ready = false; return; }
        speech.setSpeechRate(rate);
        Voice wanted = null;
        for (Voice voice : voices) if (voice.getName().equals(requestedVoice)) { wanted = voice; break; }
        // A user-selected network voice takes priority; automatic selection prefers installed offline voices.
        if (wanted != null && !missingData(wanted) && speech.setVoice(wanted) == TextToSpeech.SUCCESS) {
            ready = true;
            currentVoice = wanted.getName();
            message = missingData(wanted) ? "所选音色需要安装语音数据，请使用下载语音数据按钮。"
                    : wanted.isNetworkConnectionRequired() ? "已选网络音色，发音时需要该提供商的网络服务。" : "已选设备离线音色。";
            return;
        }
        Voice fallback = bestVoice(false, "");
        if (fallback == null) fallback = bestVoice(true, "");
        if (fallback != null && speech.setVoice(fallback) == TextToSpeech.SUCCESS) {
            ready = true;
            currentVoice = fallback.getName();
            message = requestedVoice.isEmpty() ? "已自动选择可用英语音色。"
                    : wanted != null && missingData(wanted) ? "所选音色尚未下载，暂时使用可用音色；请下载英语语音数据。"
                    : "所选音色不可用，已切换到可用英语音色。";
            return;
        }
        int result = speech.setLanguage(Locale.forLanguageTag(locale));
        Voice actual = speech.getVoice();
        ready = result >= TextToSpeech.LANG_AVAILABLE && actual != null &&
                actual.getLocale() != null && "en".equalsIgnoreCase(actual.getLocale().getLanguage());
        currentVoice = ready ? safe(actual.getName()) : "";
        message = ready ? "已使用引擎默认英语音色。" : "没有可用英语音色，请下载英语语音数据或切换引擎。";
    }

    /** Score only available voices. Network voices are allowed only as a final automatic fallback. */
    private Voice bestVoice(boolean allowNetwork, String avoidName) {
        List<SpeechVoice.Candidate> candidates = new ArrayList<>();
        for (Voice voice : voices) {
            candidates.add(new SpeechVoice.Candidate(voice.getName(), voice.getLocale().getCountry(),
                    voice.isNetworkConnectionRequired(), !missingData(voice), voice.getQuality(), voice.getLatency()));
        }
        String selected = SpeechVoice.choose(candidates, Locale.forLanguageTag(locale).getCountry(), allowNetwork, avoidName);
        for (Voice voice : voices) if (voice.getName().equals(selected)) return voice;
        return null;
    }

    public boolean speak(String value) {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("发音操作请在主线程执行。");
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > 16000)
            throw new IllegalArgumentException("发音内容为空或超过16000字符。");
        if (!ready || destroyed || speech == null)
            throw new IllegalStateException("英语语音尚未就绪，请选择设备语音引擎并下载英语语音数据。");
        List<String> chunks = SpeechText.split(text, Math.min(2500, TextToSpeech.getMaxSpeechInputLength()));
        if (chunks.isEmpty()) throw new IllegalArgumentException("发音内容没有可朗读的文字。");
        selectPreferredVoice();
        if (!ready) { publish(); throw new IllegalStateException(message); }
        usedFallback = false;
        progress.reset();
        activeChunks = chunks;
        utterancePrefix = "wordweave_" + generation + "_" + System.nanoTime();
        boolean accepted = enqueueRange(0, true);
        publish();
        return accepted;
    }

    private boolean enqueueRange(int first, boolean recover) {
        queuedChunks.clear();
        for (int i = first; i < activeChunks.size(); i++) {
            String id = utterancePrefix + "_" + i;
            queuedChunks.put(id, i);
            int result = speech.speak(activeChunks.get(i), i == first ? TextToSpeech.QUEUE_FLUSH
                    : TextToSpeech.QUEUE_ADD, null, id);
            if (result != TextToSpeech.SUCCESS)
                return recover && handleSpeechError(generation, id, result);
        }
        return true;
    }

    private boolean handleSpeechError(int ticket, String id, int errorCode) {
        Integer failedChunk = queuedChunks.get(id);
        if (destroyed || speech == null || ticket != generation || failedChunk == null) return false;
        if (!usedFallback && !activeChunks.isEmpty()) {
            Voice fallback = bestVoice(false, currentVoice);
            if (fallback != null && !fallback.getName().equals(currentVoice) &&
                    speech.setVoice(fallback) == TextToSpeech.SUCCESS) {
                usedFallback = true;
                currentVoice = fallback.getName();
                message = "所选音色发音失败，已改用设备离线音色；可下载语音数据或检查提供商网络。";
                utterancePrefix = "wordweave_fallback_" + generation + "_" + System.nanoTime();
                // A later chunk may fail while earlier queued chunks have not played yet.
                // QUEUE_FLUSH must replay every chunk after the contiguous completed prefix.
                if (enqueueRange(progress.retryFrom(), false)) {
                    publish(); return true;
                }
            }
        }
        speech.stop();
        clearQueue();
        message = errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET
                ? "音色数据尚未安装，请进入提供商页面下载英语语音数据。"
                : "发音失败，请检查语音引擎、英语数据或网络音色的网络连接。";
        publish();
        return false;
    }

    public void stop() {
        onMain(() -> { clearQueue(); if (speech != null) speech.stop(); });
    }

    /** Opens the selected provider's official installer; completion is rechecked on resume. */
    public void downloadVoiceData() {
        onMain(() -> {
            if (destroyed) return;
            returningFromProvider = true;
            Intent intent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
            String provider = !requestedEngine.isEmpty() && engineInstalled(requestedEngine)
                    ? requestedEngine : currentEngine;
            if (!provider.isEmpty()) intent.setPackage(provider);
            try { launch(intent); }
            catch (ActivityNotFoundException error) {
                message = "该提供商没有独立下载页面，请在系统 TTS 设置中安装英语语音数据。";
                publish(); openSettingsMain();
            } catch (RuntimeException blocked) {
                message = "设备不允许打开该提供商，请手动进入系统 TTS 设置。";
                publish();
            }
        });
    }

    public void openSettings() {
        onMain(() -> { if (!destroyed) { returningFromProvider = true; openSettingsMain(); } });
    }

    private void openSettingsMain() {
        try { launch(new Intent("com.android.settings.TTS_SETTINGS")); }
        catch (ActivityNotFoundException unsupported) {
            try { launch(new Intent(Settings.ACTION_SETTINGS)); }
            catch (RuntimeException blocked) { message = "系统设置无法打开，请手动进入设备设置。"; publish(); }
        } catch (RuntimeException blocked) { message = "设备限制了 TTS 设置，请手动进入设备设置。"; publish(); }
    }

    private void launch(Intent intent) {
        if (!(owner instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        owner.startActivity(intent);
    }

    public void onResume() {
        onMain(() -> {
            if (!destroyed && returningFromProvider) {
                returningFromProvider = false;
                restart(); // Returning from a provider never implies that the download succeeded.
            }
        });
    }

    public void destroy() {
        destroyed = true;
        ready = false;
        onMain(() -> {
            ++generation;
            clearQueue();
            if (speech != null) { speech.stop(); speech.shutdown(); speech = null; }
            voices.clear(); initialized = false; publish();
        });
    }

    private void listInstalledEngines() {
        engines = new JSONArray();
        Set<String> seen = new HashSet<>();
        try {
            List<ResolveInfo> results = application.getPackageManager().queryIntentServices(
                    new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0);
            for (ResolveInfo result : results) {
                if (result.serviceInfo == null || !seen.add(result.serviceInfo.packageName)) continue;
                JSONObject item = new JSONObject().put("name", result.serviceInfo.packageName)
                        .put("label", result.loadLabel(application.getPackageManager()).toString());
                engines.put(item);
            }
        } catch (Exception ignored) { }
    }

    private boolean engineInstalled(String name) {
        for (int i = 0; i < engines.length(); i++)
            if (name.equals(engines.optJSONObject(i).optString("name"))) return true;
        return false;
    }

    private void publish() {
        try {
            JSONArray list = new JSONArray();
            for (Voice voice : voices) {
                String tag = voice.getLocale().toLanguageTag();
                String accent = "GB".equalsIgnoreCase(voice.getLocale().getCountry()) ? "英式"
                        : "US".equalsIgnoreCase(voice.getLocale().getCountry()) ? "美式" : "英语";
                String label = accent + " · " + voice.getName() +
                        (voice.isNetworkConnectionRequired() ? "（需要网络）" : "（离线）") +
                        (missingData(voice) ? " · 待下载" : "");
                list.put(new JSONObject().put("name", voice.getName()).put("label", label)
                        .put("locale", tag).put("network", voice.isNetworkConnectionRequired())
                        .put("quality", voice.getQuality()).put("installed", !missingData(voice)));
            }
            snapshot = new JSONObject().put("engines", engines).put("voices", list)
                    .put("ready", ready).put("currentEngine", currentEngine).put("currentVoice", currentVoice)
                    .put("requestedEngine", requestedEngine).put("requestedVoice", requestedVoice)
                    .put("message", message).toString();
        } catch (Exception ignored) { }
    }

    private static boolean missingData(Voice voice) {
        return voice.getFeatures() != null && voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private void clearQueue() {
        queuedChunks.clear();
        activeChunks = new ArrayList<>();
        utterancePrefix = "";
        progress.reset();
    }
    private static String clean(String value, int maximum) {
        if (value.length() > maximum) throw new IllegalArgumentException("语音引擎或音色标识过长。");
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i)))
            throw new IllegalArgumentException("语音引擎或音色标识包含控制字符。");
        return value.trim();
    }
    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
    }
}
