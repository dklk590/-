package com.wordweave.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Iterator;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Public preferences are separate from the Keystore-encrypted API credential. */
public final class SecureSettings {
    private static final String ALIAS = "wordweave_api_key_v1";
    private static final String PREFS = "wordweave_private_settings";
    private final SharedPreferences preferences;

    public SecureSettings(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized JSONObject loadPublic() throws Exception {
        JSONObject result = defaults();
        String saved = preferences.getString("public_json", "{}");
        try {
            JSONObject stored = new JSONObject(saved);
            Iterator<String> names = stored.keys();
            while (names.hasNext()) {
                String name = names.next();
                if (result.has(name)) result.put(name, stored.get(name));
            }
        } catch (JSONException e) {
            throw new Exception("设置数据损坏，请重新保存设置。", e);
        }
        result.put("hasApiKey", preferences.contains("credential_cipher"));
        return result;
    }

    public synchronized JSONObject loadInternal() throws Exception {
        JSONObject result = loadPublic();
        result.put("apiKey", decryptKey());
        return result;
    }

    /** Missing fields retain their values. An empty key retains it; clearKey explicitly removes it. */
    public synchronized void save(JSONObject updates) throws Exception {
        if (updates == null) throw new IllegalArgumentException("设置不能为空。");
        JSONObject result = loadPublic();
        result.remove("hasApiKey");
        Iterator<String> names = updates.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (result.has(name)) result.put(name, updates.get(name));
        }
        validate(result);
        boolean clear = updates.has("clearKey") && requireBoolean(updates, "clearKey");
        String replacement = "";
        if (updates.has("apiKey")) {
            Object raw = updates.get("apiKey");
            if (!(raw instanceof String)) throw new IllegalArgumentException("API Key 必须是文本。");
            replacement = ((String) raw).trim();
            validateHeaderValue((String) raw, "API Key", 8192);
        }
        SharedPreferences.Editor editor = preferences.edit().putString("public_json", result.toString());
        if (clear) {
            editor.remove("credential_cipher").remove("credential_iv");
        } else if (!replacement.isEmpty()) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(replacement.getBytes(StandardCharsets.UTF_8));
            editor.putString("credential_cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP));
            editor.putString("credential_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        }
        if (!editor.commit()) throw new Exception("设置保存失败，请检查设备存储空间。");
        if (clear) {
            // Also discard an invalidated key, so the next save can generate a fresh one.
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
        }
    }

    private String decryptKey() throws Exception {
        if (!preferences.contains("credential_cipher")) return "";
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            SecretKey key = (SecretKey) store.getKey(ALIAS, null);
            if (key == null) throw new IllegalStateException("Missing Keystore key");
            byte[] iv = Base64.decode(preferences.getString("credential_iv", ""), Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(preferences.getString("credential_cipher", ""), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new Exception("API Key 无法解密，请重新输入并保存 Key；如仍失败，先清除已保存 Key。", e);
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static JSONObject defaults() throws JSONException {
        return new JSONObject()
                .put("newPerDay", 15).put("sessionSize", 12)
                .put("ttsRate", 0.85).put("ttsLocale", "en-GB")
                .put("ttsEngine", "").put("ttsVoice", "")
                .put("ttsProvider", "system")
                .put("offlineModelId", "kitten-nano-en-v0_8-int8").put("offlineSpeakerId", 0)
                .put("readingLength", "short").put("readingDifficulty", "standard").put("readingNewWordCount", 0)
                .put("reminder", false).put("reminderHour", 20).put("reminderMinute", 0)
                .put("apiBaseUrl", "").put("apiModel", "").put("apiTimeoutSec", 45)
                .put("allowLocalHttp", false);
    }

    private static void validate(JSONObject settings) throws Exception {
        requireInt(settings, "newPerDay", 0, 200);
        requireInt(settings, "sessionSize", 3, 50);
        requireInt(settings, "reminderHour", 0, 23);
        requireInt(settings, "reminderMinute", 0, 59);
        requireInt(settings, "apiTimeoutSec", 10, 120);
        requireBoolean(settings, "reminder");
        boolean localHttp = requireBoolean(settings, "allowLocalHttp");
        Object rate = settings.get("ttsRate");
        if (!(rate instanceof Number) || !Double.isFinite(((Number) rate).doubleValue())
                || ((Number) rate).doubleValue() < 0.5 || ((Number) rate).doubleValue() > 1.5)
            throw new IllegalArgumentException("发音语速应在 0.5–1.5 之间。");
        Object locale = settings.get("ttsLocale");
        if (!(locale instanceof String) || !((String) locale).matches("en-(GB|US)"))
            throw new IllegalArgumentException("发音语言请选 en-GB 或 en-US。");
        Object engine = settings.get("ttsEngine"), voice = settings.get("ttsVoice");
        if (!(engine instanceof String) || ((String) engine).length() > 255
                || !((String) engine).matches("[A-Za-z0-9_.]*"))
            throw new IllegalArgumentException("语音引擎名称无效，请从列表选择。");
        if (!(voice instanceof String) || ((String) voice).length() > 300
                || ((String) voice).matches("(?s).*[\\p{Cntrl}].*"))
            throw new IllegalArgumentException("音色名称无效，请从列表选择。");
        Object provider = settings.get("ttsProvider");
        if (!(provider instanceof String) || !((String) provider).matches("system|offline"))
            throw new IllegalArgumentException("朗读来源请选择系统 TTS 或词链本机模型。");
        Object modelId = settings.get("offlineModelId");
        if (!(modelId instanceof String) || ((String) modelId).length() > 120)
            throw new IllegalArgumentException("本机模型无效，请从目录选择。");
        ModelCatalog.Entry offlineModel = ModelCatalog.get((String) modelId);
        int speakerId = requireInt(settings, "offlineSpeakerId", 0, 10000);
        if (!offlineModel.hasSpeaker(speakerId))
            throw new IllegalArgumentException("所选模型没有该音色，请从列表选择。");
        if (!(settings.get("readingLength") instanceof String)
                || !settings.getString("readingLength").matches("short|long"))
            throw new IllegalArgumentException("阅读长度请选择短文或长文。");
        if (!(settings.get("readingDifficulty") instanceof String)
                || !settings.getString("readingDifficulty").matches("easy|standard|challenge"))
            throw new IllegalArgumentException("阅读难度无效。");
        requireInt(settings, "readingNewWordCount", 0, 8);
        Object base = settings.get("apiBaseUrl");
        Object model = settings.get("apiModel");
        if (!(base instanceof String) || !(model instanceof String))
            throw new IllegalArgumentException("API 地址和模型名称必须是文本。");
        String baseText = ((String) base).trim();
        settings.put("apiBaseUrl", baseText);
        String modelText = ((String) model).trim();
        validateHeaderValue(modelText, "模型名称", 200);
        settings.put("apiModel", modelText);
        if (!baseText.isEmpty()) AiClient.resolveEndpoint(baseText, localHttp);
    }

    private static int requireInt(JSONObject object, String key, int min, int max) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException(key + " 必须是整数。");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max)
            throw new IllegalArgumentException(key + " 应在 " + min + "–" + max + " 之间。");
        return (int) number;
    }

    private static boolean requireBoolean(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(key + " 必须是布尔值。");
        return (Boolean) value;
    }

    static void validateHeaderValue(String value, String label, int maxLength) {
        if (value.length() > maxLength) throw new IllegalArgumentException(label + " 过长。");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c > 126 || c == 127)
                throw new IllegalArgumentException(label + " 包含不允许的字符或换行。");
        }
    }
}
