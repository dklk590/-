package com.wordweave.app;

import android.content.Context;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Java adapter for the pinned, official sherpa-onnx Android API and static JNI build. */
public final class SherpaTtsBackend implements AutoCloseable {
    public static final class Audio {
        public final float[] samples;
        public final int sampleRate;
        private Audio(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    private final JSONObject descriptor;
    private final File modelRoot;
    private final String type;
    private OfflineTts runtime;
    private String loadedLocale = "";
    private int count, frequency;
    private boolean closed;

    /** Call construction, generation and close on the owning synthesis executor. */
    public SherpaTtsBackend(Context context, JSONObject modelDescriptor) throws Exception {
        descriptor = new JSONObject(modelDescriptor.toString());
        modelRoot = new File(context.getNoBackupFilesDir(), "offline_tts/models").getCanonicalFile();
        type = descriptor.optString("type").toLowerCase(Locale.ROOT);
        if (!"vits".equals(type) && !"piper".equals(type) && !"kokoro".equals(type) && !"kitten".equals(type))
            throw new IOException("不支持的本机语音模型类型。");
        // Validate each native input before JNI allocation; downloaded files stay in app-private storage.
        checkedPath("modelPath", false, true);
        checkedPath("tokensPath", false, true);
        checkedPath("dataDir", true, true);
        if ("kokoro".equals(type) || "kitten".equals(type)) checkedPath("voicesPath", false, true);
        checkedPath("usLexicon", false, false);
        checkedPath("gbLexicon", false, false);
        try {
            load("en-US");
        } catch (LinkageError missingNative) {
            throw new IOException("本机语音运行库无法在此设备载入，请改用系统 TTS。", missingNative);
        }
    }

    public synchronized Audio generate(String text, int speakerId, float speed) throws Exception {
        if (closed) throw new IOException("语音模型已经关闭。");
        if (text == null || text.trim().isEmpty() || text.length() > 4000)
            throw new IllegalArgumentException("单段本机朗读内容必须为 1 至 4000 字符，请分段朗读。");
        if (!Float.isFinite(speed) || speed < 0.5f || speed > 1.5f)
            throw new IllegalArgumentException("朗读速度必须在 0.5 至 1.5 之间。");
        if (speakerId < 0 || speakerId >= count) throw new IllegalArgumentException("该模型没有所选声音。");
        String locale = speakerLocale(speakerId);
        // Kokoro voice identity and its text phonemization dialect must agree.
        if (runtime == null || ("kokoro".equals(type) && !loadedLocale.equals(locale))) load(locale);
        GeneratedAudio generated = runtime.generate(text, speakerId, speed);
        float[] samples = generated.getSamples();
        int sampleRate = generated.getSampleRate();
        if (samples == null || samples.length == 0 || sampleRate < 8000 || sampleRate > 96000)
            throw new IOException("模型没有生成可播放的音频。");
        return new Audio(samples, sampleRate);
    }

    public int speakerCount() { return count; }
    public int sampleRate() { return frequency; }

    private void load(String locale) throws Exception {
        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setNumThreads(Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors())));
        model.setDebug(false);
        model.setProvider("cpu");
        if ("kokoro".equals(type)) {
            OfflineTtsKokoroModelConfig config = new OfflineTtsKokoroModelConfig();
            config.setModel(checkedPath("modelPath", false, true));
            config.setTokens(checkedPath("tokensPath", false, true));
            config.setVoices(checkedPath("voicesPath", false, true));
            config.setDataDir(checkedPath("dataDir", true, true));
            boolean british = "en-GB".equals(locale);
            config.setLang(british ? "en-gb-x-rp" : "en-us");
            String lexicon = checkedPath(british ? "gbLexicon" : "usLexicon", false, false);
            config.setLexicon(lexicon);
            model.setKokoro(config);
        } else if ("kitten".equals(type)) {
            OfflineTtsKittenModelConfig config = new OfflineTtsKittenModelConfig();
            config.setModel(checkedPath("modelPath", false, true));
            config.setTokens(checkedPath("tokensPath", false, true));
            config.setVoices(checkedPath("voicesPath", false, true));
            config.setDataDir(checkedPath("dataDir", true, true));
            model.setKitten(config);
        } else {
            OfflineTtsVitsModelConfig config = new OfflineTtsVitsModelConfig();
            config.setModel(checkedPath("modelPath", false, true));
            config.setTokens(checkedPath("tokensPath", false, true));
            config.setDataDir(checkedPath("dataDir", true, true));
            model.setVits(config);
        }
        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(model);
        config.setMaxNumSentences(1);
        // Release only on the same executor after a previous generate call has returned.
        if (runtime != null) { runtime.release(); runtime = null; }
        runtime = new OfflineTts(null, config); // null AssetManager selects native filesystem paths.
        count = runtime.numSpeakers();
        frequency = runtime.sampleRate();
        if (count <= 0 || frequency < 8000 || frequency > 96000) {
            runtime.release(); runtime = null;
            throw new IOException("模型信息不完整，请重新下载此模型。");
        }
        loadedLocale = locale;
    }

    private String speakerLocale(int id) {
        JSONArray speakers = descriptor.optJSONArray("speakers");
        if (speakers != null) for (int i = 0; i < speakers.length(); i++) {
            JSONObject speaker = speakers.optJSONObject(i);
            if (speaker != null && speaker.optInt("id", -1) == id)
                return "en-GB".equals(speaker.optString("locale")) ? "en-GB" : "en-US";
        }
        return "en-GB".equals(descriptor.optString("locale")) ? "en-GB" : "en-US";
    }

    private String checkedPath(String key, boolean directory, boolean required) throws IOException {
        String value = descriptor.optString(key, "");
        if (value.isEmpty()) {
            if (required) throw new IOException("模型文件不完整：" + key);
            return "";
        }
        File input = new File(value);
        File file = input.getCanonicalFile();
        if (!input.isAbsolute() || !file.getPath().startsWith(modelRoot.getPath() + File.separator))
            throw new IOException("模型文件位置无效。");
        if ((directory ? !file.isDirectory() : !file.isFile()) || !file.canRead())
            throw new IOException("模型文件不可读取：" + key);
        return file.getAbsolutePath();
    }

    @Override public synchronized void close() {
        closed = true;
        if (runtime != null) { runtime.release(); runtime = null; }
    }
}
