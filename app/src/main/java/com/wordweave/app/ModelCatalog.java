package com.wordweave.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A pinned, allow-listed catalog. Download URLs, archive roots and hashes are never user supplied. */
public final class ModelCatalog {
    private static final String RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/";
    public static final String DEFAULT_MODEL_ID = "kitten-nano-en-v0_8-int8";
    public static final int CATALOG_VERSION = 1;

    public static final class Speaker {
        public final int id;
        public final String label, locale, gender;
        Speaker(int id, String label, String locale, String gender) {
            this.id = id; this.label = label; this.locale = locale; this.gender = gender;
        }
        public JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("sid", id).put("label", label).put("locale", locale).put("gender", gender);
        }
    }

    public static final class Entry {
        public final String id, name, description, type, archiveRoot, downloadUrl, sha256;
        public final String modelFile, tokensFile, voicesFile, dataDir, usLexicon, gbLexicon;
        public final String license, licenseNotice, sourceUrl;
        public final long archiveBytes, unpackedBytes;
        public final int sampleRate;
        public final List<Speaker> speakers;

        Entry(String id, String name, String description, String type, String archiveRoot,
              long archiveBytes, long unpackedBytes, String sha256, String modelFile, String voicesFile,
              String usLexicon, String gbLexicon, String license, String licenseNotice, String sourceUrl,
              int sampleRate, List<Speaker> speakers) {
            this.id = id; this.name = name; this.description = description; this.type = type;
            this.archiveRoot = archiveRoot; this.downloadUrl = RELEASE + id + ".tar.bz2";
            this.archiveBytes = archiveBytes; this.unpackedBytes = unpackedBytes; this.sha256 = sha256;
            this.modelFile = modelFile; this.tokensFile = "tokens.txt"; this.voicesFile = voicesFile;
            this.dataDir = "espeak-ng-data"; this.usLexicon = usLexicon; this.gbLexicon = gbLexicon;
            this.license = license; this.licenseNotice = licenseNotice; this.sourceUrl = sourceUrl;
            this.sampleRate = sampleRate; this.speakers = Collections.unmodifiableList(new ArrayList<>(speakers));
            if (!sha256.matches("[0-9a-f]{64}") || archiveBytes <= 0 || unpackedBytes <= 0)
                throw new IllegalArgumentException("Invalid pinned model metadata");
        }

        public boolean hasSpeaker(int speakerId) {
            for (Speaker speaker : speakers) if (speaker.id == speakerId) return true;
            return false;
        }

        public Speaker speaker(int speakerId) {
            for (Speaker speaker : speakers) if (speaker.id == speakerId) return speaker;
            throw new IllegalArgumentException("此模型没有所选音色。");
        }

        public JSONObject toJson() throws JSONException {
            JSONArray voices = new JSONArray();
            for (Speaker speaker : speakers) voices.put(speaker.toJson());
            return new JSONObject().put("id", id).put("name", name).put("description", description)
                    .put("type", type).put("archiveRoot", archiveRoot).put("sizeBytes", archiveBytes)
                    .put("archiveBytes", archiveBytes).put("unpackedBytes", unpackedBytes)
                    .put("requiredSpaceBytes", archiveBytes + unpackedBytes + 32L * 1024 * 1024)
                    .put("downloadUrl", downloadUrl).put("sha256", sha256).put("sampleRate", sampleRate)
                    .put("modelFile", modelFile).put("tokensFile", tokensFile).put("voicesFile", voicesFile)
                    .put("dataDir", dataDir).put("usLexicon", usLexicon).put("gbLexicon", gbLexicon)
                    .put("license", license).put("licenseNotice", licenseNotice).put("sourceUrl", sourceUrl)
                    .put("speakers", voices).put("voices", voices);
        }

        /** Mandatory paths are relative to the installed directory; the archive's top-level root is stripped. */
        public List<String> requiredFiles() {
            List<String> files = new ArrayList<>();
            files.add(modelFile); files.add(tokensFile); files.add(type.equals("vits") ? "MODEL_CARD" : "LICENSE");
            if (!voicesFile.isEmpty()) files.add(voicesFile);
            if (!usLexicon.isEmpty()) files.add(usLexicon);
            if (!gbLexicon.isEmpty()) files.add(gbLexicon);
            return Collections.unmodifiableList(files);
        }
    }

    private static final List<Entry> ENTRIES;
    static {
        List<Entry> entries = new ArrayList<>();
        List<Speaker> kitten = new ArrayList<>();
        String[] labels = {"Jasper · 男声", "Bella · 女声", "Bruno · 男声", "Luna · 女声",
                "Hugo · 男声", "Rosie · 女声", "Leo · 男声", "Kiki · 女声"};
        for (int i = 0; i < labels.length; i++) kitten.add(new Speaker(i, labels[i], "en", i % 2 == 0 ? "male" : "female"));
        entries.add(new Entry(DEFAULT_MODEL_ID, "Kitten Nano 0.8 · 轻量英语",
                "15M 参数，8 个英语音色。适合先试单词和例句；官方未给各音色英美口音标签。", "kitten",
                DEFAULT_MODEL_ID, 31220690L, 45652547L,
                "6fa5be852612ce761094ba74ee6123b4fc4acfefa79bf64dc63acae4a83af2fd", "model.int8.onnx", "voices.bin", "", "",
                "Apache-2.0（模型）；eSpeak 数据依其自身许可",
                "模型许可随下载包保留。预处理使用 eSpeak 数据；其软件与数据许可需同时遵守。",
                "https://huggingface.co/KittenML/kitten-tts-nano-0.8-int8", 24000, kitten));
        entries.add(new Entry("vits-piper-en_US-lessac-medium", "Piper Lessac · 美式英语",
                "单音色美式英语。适合英语单词、例句和分段阅读。", "vits", "vits-piper-en_US-lessac-medium",
                67230653L, 81147006L, "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e",
                "en_US-lessac-medium.onnx", "", "", "", "包内许可与原声音 MODEL_CARD",
                "声音来自 Lessac Blizzard 2013；原模型卡链接研究用途数据许可。声音与数据许可需分别核对，尤其是再发布或商业使用时。",
                "https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/lessac/medium/MODEL_CARD", 22050,
                Collections.singletonList(new Speaker(0, "Lessac · 美式英语", "en-US", "unknown"))));
        entries.add(new Entry("vits-piper-en_GB-alan-medium", "Piper Alan · 英式英语",
                "单音色英式英语。适合英语单词、例句和分段阅读。", "vits", "vits-piper-en_GB-alan-medium",
                67220121L, 81199210L, "a48d4017da0f77668b27bed63fe6e04dd64c6397e1fadad4f460efb0ef7c9012",
                "en_GB-alan-medium.onnx", "", "", "", "包内许可与原声音 MODEL_CARD",
                "声音来自 Mimic3 en_UK/apope_low；许可见原 MODEL_CARD 所引用的来源。eSpeak 数据依其自身许可。",
                "https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_GB/alan/medium/MODEL_CARD", 22050,
                Collections.singletonList(new Speaker(0, "Alan · 英式英语男声", "en-GB", "male"))));
        List<Speaker> kokoro = new ArrayList<>();
        String[] us = {"af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore", "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky",
                "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael", "am_onyx", "am_puck", "am_santa"};
        for (int i = 0; i < us.length; i++) kokoro.add(new Speaker(i, us[i] + " · 美式英语", "en-US", us[i].startsWith("af_") ? "female" : "male"));
        String[] gb = {"bf_alice", "bf_emma", "bf_isabella", "bf_lily", "bm_daniel", "bm_fable", "bm_george", "bm_lewis"};
        for (int i = 0; i < gb.length; i++) kokoro.add(new Speaker(i + 20, gb[i] + " · 英式英语", "en-GB", gb[i].startsWith("bf_") ? "female" : "male"));
        entries.add(new Entry("kokoro-int8-multi-lang-v1_0", "Kokoro v1.0 INT8 · 英美多音色",
                "82M 参数的量化模型。目录提供 28 个英美音色，下载较大；适合比较例句与阅读声音。", "kokoro", "kokoro-int8-multi-lang-v1_0",
                132303094L, 189882497L, "4c3052abaa60943a341f193888cf6abd68787dae6ab8ae5c925a706caa247e4e",
                "model.int8.onnx", "voices.bin", "lexicon-us-en.txt", "lexicon-gb-en.txt", "Apache-2.0（模型）；eSpeak 数据依其自身许可",
                "原模型 Apache-2.0，下载保留完整 LICENSE。预处理字典及 eSpeak 数据的许可须同时遵守。应用只列已验证的英语音色。",
                "https://huggingface.co/hexgrad/Kokoro-82M", 24000, kokoro));
        ENTRIES = Collections.unmodifiableList(entries);
    }

    private ModelCatalog() { }
    public static List<Entry> all() { return ENTRIES; }
    public static Entry get(String id) {
        for (Entry entry : ENTRIES) if (entry.id.equals(id)) return entry;
        throw new IllegalArgumentException("未知的离线语音模型。");
    }
    public static JSONObject descriptor(String id) throws JSONException { return get(id).toJson(); }
}
