package com.Break.coach;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link IntentClassifier}.
 *
 * Covers every worked example from the product spec's SPECIFICITY section plus
 * null/empty/boundary cases. Pure JVM test — no Android dependencies.
 */
public class IntentClassifierTest {

    private static void assertSpecific(String intent) {
        assertEquals("expected SPECIFIC for: " + intent,
                Specificity.SPECIFIC, IntentClassifier.classify(intent));
    }

    private static void assertVague(String intent) {
        assertEquals("expected VAGUE for: " + intent,
                Specificity.VAGUE, IntentClassifier.classify(intent));
    }

    // ── Spec "specific" examples ────────────────────────────────────────────

    @Test
    public void specExamples_areSpecific() {
        assertSpecific("finishing 3blue1brown matrix series");
        assertSpecific("linus GPU benchmark for 4090");
        assertSpecific("lecture 4 of cs229 backprop");
        assertSpecific("how to fix react hydration error");
    }

    // ── Spec "vague" examples ───────────────────────────────────────────────

    @Test
    public void specExamples_areVague() {
        assertVague("youtube");
        assertVague("videos");
        assertVague("idk");
        assertVague("just browsing");
        assertVague("stuff");
        assertVague("things");
    }

    // ── Null / empty handling ───────────────────────────────────────────────

    @Test
    public void nullAndEmpty_areVague() {
        assertVague(null);
        assertVague("");
        assertVague("   ");
    }

    // ── Boundary / heuristic-signal cases ───────────────────────────────────

    @Test
    public void digitToken_isSpecific() {
        // A number is a strong specificity signal even with few words.
        assertSpecific("episode 12");
    }

    @Test
    public void longTopicToken_isSpecific() {
        // Single long topic word (>= 8 chars) reads as a real topic.
        assertSpecific("photosynthesis");
    }

    @Test
    public void twoContentWords_isSpecific() {
        assertSpecific("cooking pasta");
        assertSpecific("nba highlights");
    }

    @Test
    public void fillerOnlyPhrases_areVague() {
        assertVague("watch a video");
        assertVague("just want to see some stuff");
        assertVague("kill some time");
    }

    @Test
    public void singleShortWord_isVague() {
        // One short, non-filler word with no digit/long-token signal stays vague.
        assertVague("music");
    }

    @Test
    public void caseAndWhitespace_areNormalized() {
        assertVague("  YOUTUBE  ");
        assertSpecific("  Lecture 4 CS229  ");
    }
}
