package com.Break.coach;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * IntentClassifier
 * ----------------
 * Deterministic, on-device heuristic that labels a typed viewing intent as
 * {@link Specificity#SPECIFIC} or {@link Specificity#VAGUE}. No LLM, no network —
 * the verdict must be reproducible and testable offline.
 *
 * Rules (mirroring the product spec's "SPECIFICITY" section):
 *   1. Empty / whitespace-only intent  → VAGUE.
 *   2. Exact match to a banned filler phrase ("youtube", "just browsing", …) → VAGUE.
 *   3. Otherwise count "content words" (tokens that are not generic fillers) and
 *      look for strong specificity signals (a digit, or a long/topic-like token).
 *        - >= 2 content words            → SPECIFIC
 *        - any digit                     → SPECIFIC (e.g. "4090", "cs229", "lecture 4")
 *        - any token length >= 8         → SPECIFIC (e.g. "3blue1brown", "hydration")
 *   4. Anything else (short, filler-only) → VAGUE.
 *
 * This intentionally favors a few false "specific" classifications over blocking
 * a genuinely-intentional user; the verdict engine still escalates on session
 * load, so a borderline-specific intent under heavy use is not waved straight
 * through. The heuristic's limits are accepted by design (no LLM in this path).
 *
 * Logging: none here — callers (VerdictEngine / overlay) own logging so this
 * stays a pure function.
 */
public final class IntentClassifier {

    private IntentClassifier() {}

    /** Minimum content-word count that, on its own, marks an intent SPECIFIC. */
    private static final int CONTENT_WORD_THRESHOLD = 2;

    /** A single token at least this long is treated as a specificity signal. */
    private static final int LONG_TOKEN_LENGTH = 8;

    /**
     * Exact-match filler phrases that are always vague regardless of length.
     * Compared against the whole normalized intent string.
     */
    private static final Set<String> BANNED_PHRASES = new HashSet<>(Arrays.asList(
            "youtube", "videos", "video", "idk", "i dont know", "i don't know",
            "just browsing", "browsing", "stuff", "things", "nothing",
            "watch youtube", "watching youtube", "yt"));

    /**
     * Generic filler tokens that carry no topic. A token in this set does NOT
     * count toward the content-word total. Everything not in this set counts.
     */
    private static final Set<String> FILLER_WORDS = new HashSet<>(Arrays.asList(
            "a", "an", "the", "to", "of", "for", "on", "in", "at", "and", "or",
            "my", "me", "i", "im", "i'm", "want", "wanna", "gonna", "going",
            "just", "some", "see", "watch", "watching", "look", "looking",
            "browse", "browsing", "around", "stuff", "things", "thing", "video",
            "videos", "youtube", "yt", "kill", "time", "bit", "little"));

    /**
     * Classifies an intent string. Never returns null and never throws.
     *
     * @param intent raw user-typed intent (may be null)
     * @return SPECIFIC or VAGUE
     */
    public static Specificity classify(String intent) {
        if (intent == null) {
            return Specificity.VAGUE;
        }
        String normalized = intent.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return Specificity.VAGUE;
        }
        if (BANNED_PHRASES.contains(normalized)) {
            return Specificity.VAGUE;
        }

        // Split on any run of non-alphanumeric characters so "cs229" stays whole
        // but "3blue1brown," loses its trailing comma.
        String[] tokens = normalized.split("[^a-z0-9]+");

        int contentWords = 0;
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            // Strong signals: a number anywhere, or a long/topic-like token.
            if (containsDigit(token) || token.length() >= LONG_TOKEN_LENGTH) {
                return Specificity.SPECIFIC;
            }
            if (!FILLER_WORDS.contains(token)) {
                contentWords++;
            }
        }

        return contentWords >= CONTENT_WORD_THRESHOLD
                ? Specificity.SPECIFIC
                : Specificity.VAGUE;
    }

    /** True if the token contains at least one 0-9 digit. */
    private static boolean containsDigit(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (Character.isDigit(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
