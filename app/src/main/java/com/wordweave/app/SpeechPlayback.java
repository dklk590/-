package com.wordweave.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

/** Platform-free playback ledger. Tokens make replaced sessions and late callbacks harmless. */
public final class SpeechPlayback {
    public static final class Segment {
        public final String text;
        public final int start, end;
        Segment(String text, int start, int end) { this.text = text; this.start = start; this.end = end; }
    }

    public static final class Snapshot {
        public final long token;
        public final String state, text, context, message;
        public final int segmentIndex, segmentCount, completedSegments, completedChars, totalChars;
        public final boolean completed, canPause, canResume;
        Snapshot(long token, String state, String text, String context, String message,
                 int next, int count, int chars, boolean completed) {
            this.token = token; this.state = state; this.text = text; this.context = context; this.message = message;
            this.segmentIndex = count == 0 ? 0 : Math.min(next, count - 1);
            this.segmentCount = count; this.completedSegments = next;
            this.completedChars = chars; this.totalChars = text.length(); this.completed = completed;
            this.canPause = "playing".equals(state) || "preparing".equals(state);
            this.canResume = "paused".equals(state);
        }
    }

    private long token;
    private String state = "idle", text = "", context = "", message = "";
    private List<Segment> segments = Collections.emptyList();
    private int next, completedChars;
    private boolean completed;

    public synchronized long begin(String value, String context, int maximum) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > 16000) throw new IllegalArgumentException("发音内容为空或超过16000字符。");
        List<Segment> split = split(clean, maximum);
        if (split.isEmpty()) throw new IllegalArgumentException("没有可朗读的文字。");
        token++;
        this.text = clean; this.context = context == null ? "" : context;
        this.segments = Collections.unmodifiableList(split);
        next = 0; completedChars = 0; completed = false; state = "preparing"; message = "正在准备发音。";
        notifyAll();
        return token;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(token, state, text, context, message, next, segments.size(), completedChars, completed);
    }
    public synchronized List<Segment> segments(long expected) {
        return expected == token ? segments : Collections.emptyList();
    }
    public synchronized int nextIndex(long expected) { return expected == token ? next : -1; }
    public synchronized boolean isCurrent(long expected) {
        return expected == token && ("preparing".equals(state) || "playing".equals(state) || "paused".equals(state));
    }
    public synchronized boolean isPaused(long expected) { return isCurrent(expected) && "paused".equals(state); }

    public synchronized boolean started(long expected, int index) {
        if (!isCurrent(expected) || "paused".equals(state) || index != next) return false;
        state = "playing"; message = "正在播放。"; return true;
    }
    public synchronized boolean complete(long expected, int index) {
        if (!isCurrent(expected) || "paused".equals(state) || index != next || next >= segments.size()) return false;
        completedChars = segments.get(next).end; next++;
        if (next == segments.size()) { state = "idle"; completed = true; message = "播放完成。"; }
        else { state = "preparing"; message = "正在准备下一句。"; }
        notifyAll(); return true;
    }
    public synchronized boolean pause() {
        if (!"playing".equals(state) && !"preparing".equals(state)) return false;
        state = "paused"; message = "已暂停。"; notifyAll(); return true;
    }
    public synchronized boolean resume() {
        if (!"paused".equals(state)) return false;
        state = "preparing"; message = "正在继续播放。"; notifyAll(); return true;
    }
    public synchronized void stop() {
        token++; state = "stopped"; completed = false; message = "已停止。"; notifyAll();
    }
    public synchronized boolean fail(long expected, String reason) {
        if (!isCurrent(expected)) return false;
        state = "error"; message = reason == null ? "发音失败。" : reason; notifyAll(); return true;
    }
    /** Called only by the audio worker. stop/replacement/destroy always wake this wait. */
    public synchronized boolean awaitPlayable(long expected) throws InterruptedException {
        while (isCurrent(expected) && "paused".equals(state)) wait();
        return isCurrent(expected);
    }

    /** Prefer individual sentences; split unusually long sentences at word boundaries. */
    public static List<Segment> split(String text, int maximum) {
        if (maximum < 2) throw new IllegalArgumentException("分段长度至少为2。");
        List<Segment> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;
        int start = 0;
        while (start < text.length()) {
            int ceiling = Math.min(text.length(), start + maximum), end = -1;
            for (int i = start; i < ceiling; i++) {
                char c = text.charAt(i);
                if (c == '\n' || c == '\r') { end = i + 1; break; }
                if (c != '.' && c != '!' && c != '?' && c != '。' && c != '！' && c != '？') continue;
                int after = i + 1;
                while (after < text.length() && "\"'”’)]".indexOf(text.charAt(after)) >= 0) after++;
                if (after < text.length() && !Character.isWhitespace(text.charAt(after)) && c < 128) continue;
                if (c == '.' && abbreviation(text, start, i)) continue;
                end = after;
                while (end < text.length() && end < ceiling && Character.isWhitespace(text.charAt(end))) end++;
                if (end > ceiling) end = -1;
                if (end > start) break;
            }
            if (end <= start) {
                end = ceiling;
                if (end < text.length()) {
                    int wordEnd = end;
                    while (wordEnd > start && !Character.isWhitespace(text.charAt(wordEnd - 1))) wordEnd--;
                    if (wordEnd > start) end = wordEnd;
                }
            }
            if (end < text.length() && end > start && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
            if (end <= start) end = Math.min(text.length(), start + 2);
            String spoken = text.substring(start, end).trim();
            if (!spoken.isEmpty()) result.add(new Segment(spoken, start, end));
            else if (!result.isEmpty()) {
                Segment previous = result.remove(result.size() - 1);
                result.add(new Segment(previous.text, previous.start, end));
            }
            start = end;
        }
        return result;
    }
    private static boolean abbreviation(String text, int start, int period) {
        int from = period;
        while (from > start && (Character.isLetter(text.charAt(from - 1)) || text.charAt(from - 1) == '.')) from--;
        String word = text.substring(from, period).toLowerCase(Locale.ROOT);
        return "mr".equals(word) || "mrs".equals(word) || "ms".equals(word) || "dr".equals(word)
                || "prof".equals(word) || "sr".equals(word) || "jr".equals(word) || "vs".equals(word)
                || "e.g".equals(word) || "i.e".equals(word)
                || (period + 1 < text.length() && Character.isDigit(text.charAt(period + 1)));
    }

    public static final class Pcm {
        public final float[] samples;
        public final int sampleRate;
        public Pcm(float[] samples, int sampleRate) {
            if (samples == null || samples.length == 0 || sampleRate < 8000 || sampleRate > 192000)
                throw new IllegalArgumentException("模型未生成有效音频。");
            for (float value : samples) if (Float.isNaN(value) || Float.isInfinite(value))
                throw new IllegalArgumentException("模型音频包含无效采样。");
            this.samples = samples; this.sampleRate = sampleRate;
        }
    }

    /** Bounded RAM cache; model/speaker/rate/text form the key. No private reading is written to disk. */
    public static final class PcmCache {
        private final long budget;
        private long bytes;
        private final LinkedHashMap<String, Pcm> items = new LinkedHashMap<>(16, .75f, true);
        public PcmCache(long budget) { if (budget < 0) throw new IllegalArgumentException("缓存大小无效。"); this.budget = budget; }
        public synchronized Pcm get(String key) { return items.get(key); }
        public synchronized void put(String key, Pcm pcm) {
            Pcm old = items.remove(key); if (old != null) bytes -= old.samples.length * 4L;
            long size = pcm.samples.length * 4L;
            if (size > budget) return;
            items.put(key, pcm); bytes += size;
            while (bytes > budget) {
                Map.Entry<String, Pcm> first = items.entrySet().iterator().next();
                bytes -= first.getValue().samples.length * 4L; items.remove(first.getKey());
            }
        }
        public synchronized long bytes() { return bytes; }
        public synchronized void clear() { items.clear(); bytes = 0; }
    }

    /** Holds through both native release and the later filesystem deletion, including duplicates. */
    public static final class ModelGate {
        private final Map<String, Integer> pending = new HashMap<>();
        public synchronized void block(String id) {
            if (id == null || id.isEmpty()) throw new IllegalArgumentException("模型标识为空。");
            int count = pending.containsKey(id) ? pending.get(id) : 0;
            if (count == Integer.MAX_VALUE) throw new IllegalStateException("删除请求过多。");
            pending.put(id, count + 1);
        }
        public synchronized boolean isBlocked(String id) { return pending.containsKey(id); }
        public synchronized void finished(String id) {
            Integer count = pending.get(id);
            if (count == null) return;
            if (count > 1) pending.put(id, count - 1); else pending.remove(id);
        }
    }
}
