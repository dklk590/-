package com.wordweave.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serial native inference plus bounded PCM streaming. No synthesis runs on the UI thread. */
public final class OfflineSpeechManager {
    private static final String DEFAULT_MODEL = "kitten-nano-en-v0_8-int8";
    public static final String DELETING_MESSAGE = "所选本机语音模型正在删除，请等待删除操作结束后再播放。";
    private final Context context;
    private final ModelDownloadManager downloads;
    private final SpeechPlayback playback;
    private final Runnable changed;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "wordweave-offline-tts"));
    private final Object trackLock = new Object();
    private final SpeechPlayback.PcmCache cache = new SpeechPlayback.PcmCache(12L * 1024 * 1024);
    private final SpeechPlayback.ModelGate modelGate = new SpeechPlayback.ModelGate();
    private volatile Config config = new Config(DEFAULT_MODEL, 0, "en", .85f);
    private volatile boolean destroyed;
    private AudioTrack activeTrack;
    // These native fields belong exclusively to worker, including close().
    private SherpaTtsBackend backend;
    private String loadedSignature = "";
    private volatile String loadedModel = "", loadingModel = "";

    private static final class Config {
        final String model, locale; final int speaker; final float speed;
        Config(String model, int speaker, String locale, float speed) { this.model = model; this.speaker = speaker; this.locale = locale; this.speed = speed; }
    }

    public OfflineSpeechManager(Context context, ModelDownloadManager downloads,
                                SpeechPlayback playback, Runnable changed) {
        this.context = context.getApplicationContext(); this.downloads = downloads;
        this.playback = playback; this.changed = changed;
    }

    public void configure(JSONObject settings) {
        String model = settings.optString("offlineModelId", DEFAULT_MODEL);
        if (model.isEmpty()) model = DEFAULT_MODEL;
        ModelCatalog.Entry entry = ModelCatalog.get(model);
        int speaker = settings.optInt("offlineSpeakerId", 0);
        String locale = entry.speaker(speaker).locale;
        double speed = settings.optDouble("ttsRate", .85);
        config = new Config(model, speaker, locale, (float) (Double.isFinite(speed) ? Math.max(.5, Math.min(1.5, speed)) : .85));
    }

    public boolean isReady() { return !modelGate.isBlocked(config.model) && downloads.isReady(config.model); }
    public boolean isDeleting() { return modelGate.isBlocked(config.model); }
    public String modelId() { return config.model; }
    public int speakerId() { return config.speaker; }

    public JSONArray models() {
        JSONArray result = new JSONArray();
        for (ModelCatalog.Entry entry : ModelCatalog.all()) {
            try {
                JSONArray speakers = new JSONArray();
                for (ModelCatalog.Speaker voice : entry.speakers) speakers.put(new JSONObject()
                        .put("id", voice.id).put("label", voice.label).put("locale", voice.locale).put("gender", voice.gender));
                result.put(new JSONObject().put("id", entry.id).put("name", entry.name).put("type", entry.type)
                        .put("ready", !modelGate.isBlocked(entry.id) && downloads.isReady(entry.id))
                        .put("deleting", modelGate.isBlocked(entry.id)).put("downloadBytes", entry.archiveBytes).put("speakers", speakers));
            } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
        return result;
    }

    public void speak(long token) {
        if (destroyed) throw new IllegalStateException("语音播放器已关闭。");
        Config selected = config;
        if (modelGate.isBlocked(selected.model)) throw new IllegalStateException(DELETING_MESSAGE);
        if (!downloads.isReady(selected.model)) throw new IllegalStateException("请先在 App 中下载并校验所选离线语音模型。");
        worker.execute(() -> run(token, selected));
    }

    private void run(long token, Config selected) {
        try {
            if (destroyed || !playback.awaitPlayable(token)) return;
            if (modelGate.isBlocked(selected.model)) throw new IllegalStateException(DELETING_MESSAGE);
            JSONObject descriptor = downloads.descriptor(selected.model);
            String signature = descriptor.toString();
            if (backend == null || !loadedSignature.equals(signature)) {
                if (backend != null) { backend.close(); backend = null; }
                cache.clear(); loadedSignature = ""; loadedModel = "";
                loadingModel = selected.model;
                try {
                    if (destroyed || !playback.awaitPlayable(token)) return;
                    if (modelGate.isBlocked(selected.model)) throw new IllegalStateException(DELETING_MESSAGE);
                    backend = new SherpaTtsBackend(context, descriptor);
                    loadedSignature = signature; loadedModel = selected.model;
                } finally { loadingModel = ""; }
            }
            List<SpeechPlayback.Segment> segments = playback.segments(token);
            for (int index = playback.nextIndex(token); index >= 0 && index < segments.size(); index++) {
                if (destroyed || !playback.awaitPlayable(token)) return;
                if (modelGate.isBlocked(selected.model)) throw new IllegalStateException(DELETING_MESSAGE);
                String text = segments.get(index).text;
                String key = "offline\u0000" + selected.model + "\u0000" + selected.speaker + "\u0000" + selected.locale + "\u0000"
                        + Float.floatToIntBits(selected.speed) + "\u0000" + text;
                SpeechPlayback.Pcm pcm = cache.get(key);
                if (pcm == null) {
                    SherpaTtsBackend.Audio generated = backend.generate(text, selected.speaker, selected.speed);
                    if (destroyed || !playback.isCurrent(token)) return;
                    if (modelGate.isBlocked(selected.model)) throw new IllegalStateException(DELETING_MESSAGE);
                    pcm = new SpeechPlayback.Pcm(generated.samples, generated.sampleRate);
                    cache.put(key, pcm);
                }
                if (!playPcm(token, index, pcm)) return;
                while (playback.isCurrent(token) && !playback.complete(token, index))
                    if (!playback.awaitPlayable(token)) return;
                changed.run();
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            if (!destroyed && playback.fail(token, describe(error))) changed.run();
        }
    }

    private boolean playPcm(long token, int index, SpeechPlayback.Pcm pcm) throws InterruptedException {
        if (!playback.awaitPlayable(token)) return false;
        int minimum = AudioTrack.getMinBufferSize(pcm.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        if (minimum <= 0) throw new IllegalStateException("设备不支持所选模型的音频采样率。");
        AudioTrack track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(pcm.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum, pcm.sampleRate / 5 * 4)).build();
        try {
            if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("设备音频播放器初始化失败。");
            synchronized (trackLock) {
                if (destroyed || !playback.isCurrent(token)) return false;
                activeTrack = track;
                if (!playback.isPaused(token)) track.play();
            }
            int written = 0;
            boolean announced = false;
            while (written < pcm.samples.length) {
                if (destroyed || !playback.awaitPlayable(token)) return false;
                synchronized (trackLock) {
                    if (!playback.isCurrent(token)) return false;
                    if (!playback.isPaused(token) && track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
                }
                int count = track.write(pcm.samples, written, Math.min(4096, pcm.samples.length - written), AudioTrack.WRITE_NON_BLOCKING);
                if (!playback.isCurrent(token)) return false;
                if (count < 0) throw new IllegalStateException("音频输出失败（" + count + "）。");
                written += count;
                if (!announced && count > 0 && playback.started(token, index)) { announced = true; changed.run(); }
                if (count == 0) Thread.sleep(12);
            }
            // write() reports accepted frames, not heard frames; wait for actual playback completion.
            while ((track.getPlaybackHeadPosition() & 0xffffffffL) < pcm.samples.length) {
                if (destroyed || !playback.awaitPlayable(token)) return false;
                if (!announced && playback.started(token, index)) { announced = true; changed.run(); }
                Thread.sleep(12);
            }
            return playback.isCurrent(token);
        } finally {
            synchronized (trackLock) {
                if (activeTrack == track) activeTrack = null;
                try { track.pause(); track.flush(); track.stop(); } catch (RuntimeException ignored) { }
                track.release();
            }
        }
    }

    /** AudioTrack keeps queued PCM and its playhead; resume therefore never repeats a heard sample. */
    public void pause() {
        synchronized (trackLock) { if (activeTrack != null) try { activeTrack.pause(); } catch (RuntimeException ignored) { } }
    }
    public void resume() {
        synchronized (trackLock) { if (activeTrack != null) try { activeTrack.play(); } catch (RuntimeException ignored) { } }
    }
    public void stop() {
        synchronized (trackLock) {
            if (activeTrack != null) try { activeTrack.pause(); activeTrack.flush(); } catch (RuntimeException ignored) { }
        }
    }
    public void destroy() {
        destroyed = true;
        stop();
        worker.execute(() -> {
            if (backend != null) { backend.close(); backend = null; }
            cache.clear(); loadedSignature = ""; loadedModel = "";
        });
        worker.shutdown();
    }
    /** Deletion must wait for this callback: JNI no longer holds the model's files. */
    public void releaseModel(String modelId, Runnable afterRelease) {
        if (destroyed) throw new IllegalStateException("语音播放器已关闭。");
        modelGate.block(modelId);
        // Deleting another model must not wait behind a long or paused reading of this one.
        // loadingModel is set before the final token check, so an obsolete loader cannot
        // subsequently acquire files after this fast-path callback permits deletion.
        if (!modelId.equals(config.model) && !modelId.equals(loadedModel) && !modelId.equals(loadingModel)) {
            if (afterRelease != null) afterRelease.run();
            return;
        }
        worker.execute(() -> {
            if (backend != null && loadedModel.equals(modelId)) {
                backend.close(); backend = null; loadedSignature = ""; loadedModel = ""; cache.clear();
            }
            if (afterRelease != null) afterRelease.run();
        });
    }
    /** Called by Main's delete finally, after removal or failure recovery has really completed. */
    public void modelDeletionFinished(String modelId) { modelGate.finished(modelId); }
    private static String describe(Throwable error) {
        if (error instanceof UnsatisfiedLinkError || error instanceof NoClassDefFoundError)
            return "当前设备或安装包无法加载离线语音运行库，请检查设备架构或切换系统 TTS。";
        String detail = error.getMessage();
        return "离线发音失败：" + (detail == null || detail.isEmpty() ? error.getClass().getSimpleName() : detail);
    }
}
