package com.wordweave.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Split a reading for native TTS without losing words or breaking a surrogate pair. */
final class SpeechText {
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?][\\\"'”’)]?\\s+");
    private SpeechText() { }

    static List<String> split(String value, int maximum) {
        if (maximum < 2) throw new IllegalArgumentException("语音分段长度至少为2。");
        String text = value == null ? "" : value.replaceAll("[\\s\\p{Z}]+", " ").trim();
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maximum);
            if (end < text.length()) {
                Matcher sentences = SENTENCE_END.matcher(text);
                sentences.region(start, end);
                int sentenceEnd = -1;
                while (sentences.find()) sentenceEnd = sentences.end();
                if (sentenceEnd >= start + maximum / 3) end = sentenceEnd;
                else {
                    int space = text.lastIndexOf(' ', end - 1);
                    if (space > start) end = space + 1;
                }
                if (end < text.length() && end > start && Character.isHighSurrogate(text.charAt(end - 1))
                        && Character.isLowSurrogate(text.charAt(end))) end--;
            }
            if (end <= start) end = Math.min(text.length(), start + 2);
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }
}
