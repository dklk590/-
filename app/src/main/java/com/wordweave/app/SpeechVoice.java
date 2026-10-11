package com.wordweave.app;

import java.util.List;

/** Pure selection policy, separate from provider binding so its fallback rules can be tested. */
final class SpeechVoice {
    static final class Candidate {
        final String name, country;
        final boolean network, installed;
        final int quality, latency;
        Candidate(String name, String country, boolean network, boolean installed, int quality, int latency) {
            this.name = name; this.country = country; this.network = network;
            this.installed = installed; this.quality = quality; this.latency = latency;
        }
    }
    private SpeechVoice() { }

    static String choose(List<Candidate> candidates, String country, boolean networkAllowed, String failedName) {
        String selected = "";
        int selectedScore = Integer.MIN_VALUE;
        for (Candidate voice : candidates) {
            if (!voice.installed || (!networkAllowed && voice.network) || voice.name.equals(failedName)) continue;
            int score = voice.quality - voice.latency;
            if (!voice.network) score += 10000;
            if (voice.country.equalsIgnoreCase(country)) score += 2000;
            if (score > selectedScore || (score == selectedScore && voice.name.compareTo(selected) < 0)) {
                selected = voice.name; selectedScore = score;
            }
        }
        return selected;
    }
}
