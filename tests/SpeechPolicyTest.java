package com.wordweave.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JVM tests of the actual production voice policy and reading segmenter, without a fake TTS engine. */
public final class SpeechPolicyTest {
    private static int checked;
    private static void check(boolean value, String detail) {
        ++checked; if (!value) throw new AssertionError(detail);
    }
    private static String join(List<String> parts) {
        StringBuilder result = new StringBuilder(); for (String part : parts) result.append(part); return result.toString();
    }
    private static void splitChecks(String text, int size, String expected) {
        List<String> parts = SpeechText.split(text, size);
        check(join(parts).equals(expected), "segmentation must retain all words and punctuation");
        for (String part : parts) {
            check(part.length() <= size, "chunk exceeds native input limit");
            check(!Character.isHighSurrogate(part.charAt(part.length() - 1)), "split surrogate pair");
        }
    }
    private static SpeechVoice.Candidate voice(String name, String country, boolean network, boolean installed, int quality, int latency) {
        return new SpeechVoice.Candidate(name, country, network, installed, quality, latency);
    }

    public static void main(String[] args) {
        check(SpeechText.split(null, 20).isEmpty(), "null text");
        check(SpeechText.split(" \n\t ", 20).isEmpty(), "blank text");
        try { SpeechText.split("hello", 1); throw new AssertionError("invalid maximum accepted"); }
        catch (IllegalArgumentException expected) { ++checked; }
        splitChecks("  Hello\n\tworld.  ", 20, "Hello world.");
        splitChecks("A sentence ends here. Another sentence follows. A final sentence completes the thought.", 35,
                "A sentence ends here. Another sentence follows. A final sentence completes the thought.");
        List<String> natural = SpeechText.split("We reviewed the words. Then we translated the passage. Finally we corrected our errors.", 40);
        check(natural.get(0).endsWith(". "), "prefer a complete sentence boundary");
        StringBuilder reading = new StringBuilder();
        for (int i = 0; i < 420; i++) reading.append(i % 20 == 19 ? "extraordinary. " : "extraordinary ");
        String longReading = reading.toString().trim();
        check(longReading.length() > 4000, "420-word regression reading should exceed native per-call limit");
        splitChecks(longReading, 2500, longReading);
        check(SpeechText.split(longReading, 2500).size() > 1, "420-word reading is split, not rejected");
        splitChecks("AnEnglishWordWithoutAnyWhitespace", 8, "AnEnglishWordWithoutAnyWhitespace");
        splitChecks("abc\uD83D\uDE42def\uD83D\uDE42ghi", 4, "abc\uD83D\uDE42def\uD83D\uDE42ghi");
        splitChecks("one\u00A0two\nthree", 7, "one two three");
        List<SpeechVoice.Candidate> voices = Arrays.asList(
                voice("failed_high_quality", "GB", false, true, 500, 100),
                voice("usable_lower_quality", "GB", false, true, 200, 300),
                voice("network", "GB", true, true, 500, 100),
                voice("not_downloaded", "GB", false, false, 500, 100),
                voice("american", "US", false, true, 500, 100));
        check(SpeechVoice.choose(voices, "GB", false, "").equals("failed_high_quality"), "initial choice uses quality");
        check(SpeechVoice.choose(voices, "GB", false, "failed_high_quality").equals("usable_lower_quality"),
                "recovery MUST exclude failed best-quality voice");
        check(SpeechVoice.choose(voices, "US", false, "").equals("american"), "match American accent");
        check(SpeechVoice.choose(voices, "GB", true, "failed_high_quality").equals("usable_lower_quality"),
                "automatic selection prefers offline over network");
        List<SpeechVoice.Candidate> networkOnly = Arrays.asList(voice("cloud", "GB", true, true, 500, 100));
        check(SpeechVoice.choose(networkOnly, "GB", false, "").isEmpty(), "network excluded in offline recovery");
        check(SpeechVoice.choose(networkOnly, "GB", true, "").equals("cloud"), "network permitted as last automatic option");
        check(SpeechVoice.choose(networkOnly, "GB", true, "cloud").isEmpty(), "never retry failed voice as fallback");
        check(SpeechVoice.choose(Arrays.asList(voice("not_installed", "GB", false, false, 500, 100)), "GB", true, "").isEmpty(),
                "uninstalled voice cannot be automatic fallback");
        check(SpeechVoice.choose(new ArrayList<>(), "GB", true, "").isEmpty(), "empty provider voice list");
        check(SpeechVoice.choose(Arrays.asList(voice("z", "GB", false, true, 300, 100), voice("a", "GB", false, true, 300, 100)),
                "GB", false, "").equals("a"), "stable equal-quality ordering");
        SpeechProgress progress = new SpeechProgress();
        check(progress.nextIndex() == 0, "no chunk has completed");
        check(progress.retryFrom() == 0, "synchronous failure of chunk2 must replay pending chunks0 and1");
        progress.complete(1);
        check(progress.nextIndex() == 0, "out-of-order completion1 cannot skip unfinished0");
        progress.complete(0);
        check(progress.nextIndex() == 2, "completion0 advances through contiguous completed1");
        check(progress.retryFrom() == 2, "completed prefix0/1 need not be replayed");
        progress.complete(3);
        check(progress.nextIndex() == 2, "future completion3 cannot skip unfinished2");
        check(progress.retryFrom() == 2, "recovery preserves only contiguous completed prefix");
        progress.complete(2);
        check(progress.nextIndex() == 3, "stale future completion3 was cleared before replay");
        progress.complete(3);
        check(progress.nextIndex() == 4, "replayed last chunk completes reading");
        progress.complete(1);
        check(progress.nextIndex() == 4, "duplicate completion does not move backwards");
        progress.reset();
        check(progress.nextIndex() == 0, "stop or new reading resets progress");
        System.out.println("SpeechPolicyTest: " + checked + " checks passed; no Android device/provider runtime claim.");
    }
}
