package com.wordweave.app;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Deterministic session races and memory bounds, without Android or native TTS mocks. */
public final class SpeechPlaybackTest {
    private static int checks;
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        SpeechPlayback p = new SpeechPlayback();
        check(!p.isCurrent(0) && !p.started(0, 0) && !p.fail(0, "unstarted"), "initial idle state has no active speech session");
        long first = p.begin("Dr. Smith arrived. He said \"Hello!\" Next line.\nLast line.", "reading:12", 200);
        List<SpeechPlayback.Segment> parts = p.segments(first);
        check(parts.size() == 4, "sentences, quotations and paragraph breaks are split");
        check(parts.get(0).text.equals("Dr. Smith arrived."), "common title abbreviation does not split the sentence");
        check(p.snapshot().totalChars == p.snapshot().text.length(), "progress uses the actual input text");
        check(p.started(first, 0), "first segment starts");
        try { p.begin("   ", "illegal", 200); throw new AssertionError("blank text accepted"); }
        catch (IllegalArgumentException expected) {
            check(p.snapshot().token == first && p.snapshot().context.equals("reading:12") && p.snapshot().state.equals("playing"), "invalid input cannot replace or stop valid playback");
        }
        check(!p.complete(first, 1), "out-of-order callbacks cannot skip unheard text");
        check(p.complete(first, 0), "contiguous heard segment completes");
        int chars = p.snapshot().completedChars;
        check(chars == parts.get(0).end, "completed characters advance only on completion");
        check(p.pause(), "preparing next sentence can be paused");
        check(!p.complete(first, 1), "completion while paused cannot skip the interrupted sentence");
        check(p.snapshot().canResume && !p.snapshot().canPause, "paused UI controls are truthful");
        CountDownLatch waiting = new CountDownLatch(1), woke = new CountDownLatch(1);
        boolean[] result = new boolean[1];
        Thread waiter = new Thread(() -> {
            waiting.countDown();
            try { result[0] = p.awaitPlayable(first); } catch (InterruptedException e) { throw new AssertionError(e); }
            woke.countDown();
        });
        waiter.start(); waiting.await();
        check(!woke.await(30, TimeUnit.MILLISECONDS), "paused worker waits rather than continuing synthesis playback");
        check(p.resume(), "resume reuses the current segment");
        check(woke.await(1, TimeUnit.SECONDS) && result[0], "resume wakes audio worker");
        check(p.nextIndex(first) == 1 && p.snapshot().completedChars == chars, "pause/resume preserves exact contiguous progress");
        long second = p.begin("Replacement first. Replacement second.", "word:21", 200);
        check(!p.started(first, 1) && !p.complete(first, 1) && !p.fail(first, "old error"), "replaced-session callbacks are all ignored");
        check(p.snapshot().context.equals("word:21"), "word playback is distinct from a previous reading");
        check(p.pause(), "replacement can pause before its first frame");
        CountDownLatch stopped = new CountDownLatch(1);
        boolean[] afterStop = {true};
        Thread stopWaiter = new Thread(() -> {
            try { afterStop[0] = p.awaitPlayable(second); } catch (InterruptedException e) { throw new AssertionError(e); }
            stopped.countDown();
        });
        stopWaiter.start(); p.stop();
        check(stopped.await(1, TimeUnit.SECONDS) && !afterStop[0], "stop wakes a paused worker and invalidates its token");
        check(!p.resume() && !p.snapshot().canResume, "stopped speech cannot accidentally resume");
        long third = p.begin("Done.", "reading:19", 200);
        check(p.started(third, 0) && p.complete(third, 0), "single sentence completes");
        check(p.snapshot().completed && p.snapshot().state.equals("idle"), "completion is idle plus explicit completed flag");
        check(p.snapshot().completedChars == p.snapshot().totalChars, "completion reaches the exact character total");
        check(!p.pause() && !p.complete(third, 0), "completed playback cannot be paused or completed twice");
        long failed = p.begin("Fail.", "", 200);
        check(p.fail(failed, "missing model"), "current failure is recorded");
        check(p.snapshot().state.equals("error") && !p.isCurrent(failed), "error stops all work for the session");
        for (int maximum = 2; maximum < 28; maximum++) {
            String text = "Longwordwithoutspaces😀 and another sentence.\n中文句子。后一句。";
            List<SpeechPlayback.Segment> split = SpeechPlayback.split(text, maximum);
            int end = 0;
            for (SpeechPlayback.Segment s : split) {
                check(s.end > s.start && s.start == end, "split segments form a contiguous prefix");
                check(!Character.isHighSurrogate(text.charAt(s.end - 1)), "split never leaves half a surrogate at the end");
                check(!Character.isLowSurrogate(text.charAt(s.start)), "split never starts with half a surrogate");
                end = s.end;
            }
            check(end == text.length(), "splitting preserves all source characters");
        }
        SpeechPlayback.PcmCache cache = new SpeechPlayback.PcmCache(32);
        SpeechPlayback.Pcm a = new SpeechPlayback.Pcm(new float[4], 22050);
        cache.put("a", a); cache.put("b", a); check(cache.bytes() == 32, "PCM budget uses sample bytes");
        check(cache.get("a") == a, "cache hit refreshes LRU order");
        cache.put("c", a); check(cache.get("b") == null && cache.get("a") == a, "least recently used sample is evicted");
        cache.put("a", new SpeechPlayback.Pcm(new float[2], 22050)); check(cache.bytes() == 24, "replacing cached PCM updates memory accounting");
        cache.put("oversized", new SpeechPlayback.Pcm(new float[20], 22050)); check(cache.get("oversized") == null && cache.bytes() <= 32, "oversized audio does not exceed cache budget");
        cache.clear(); check(cache.bytes() == 0 && cache.get("a") == null, "destroy clears cached private speech");
        try { new SpeechPlayback.Pcm(new float[]{Float.NaN}, 22050); throw new AssertionError("NaN accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        SpeechPlayback.ModelGate gate = new SpeechPlayback.ModelGate();
        gate.block("kitten");
        check(gate.isBlocked("kitten") && !gate.isBlocked("piper"), "deletion blocks only its model");
        CountDownLatch nativeReleased = new CountDownLatch(1), deleteMayFinish = new CountDownLatch(1), deleted = new CountDownLatch(1);
        Thread deletion = new Thread(() -> {
            nativeReleased.countDown(); // The real main callback hands deletion to another worker here.
            try { deleteMayFinish.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            gate.finished("kitten"); deleted.countDown();
        });
        deletion.start(); check(nativeReleased.await(1, TimeUnit.SECONDS), "native release callback has occurred");
        for (int i = 0; i < 100; i++) check(gate.isBlocked("kitten"), "replay between native release and queued filesystem delete remains blocked");
        check(!gate.isBlocked("piper"), "another model remains playable during deletion");
        deleteMayFinish.countDown(); check(deleted.await(1, TimeUnit.SECONDS) && !gate.isBlocked("kitten"), "only actual deletion completion removes the gate");
        gate.block("kokoro"); gate.block("kokoro"); gate.finished("kokoro");
        check(gate.isBlocked("kokoro"), "a duplicate delete completion cannot release another pending delete");
        gate.finished("kokoro"); check(!gate.isBlocked("kokoro"), "all duplicate operations must finish");
        gate.block("piper"); gate.finished("piper");
        check(!gate.isBlocked("piper"), "failed deletion finally restores ability to use the unchanged model");
        System.out.println("SpeechPlayback: " + checks + " checks passed");
    }
}
