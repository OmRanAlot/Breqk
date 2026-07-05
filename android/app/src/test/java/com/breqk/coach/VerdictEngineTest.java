package com.Break.coach;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link VerdictEngine}.
 *
 * Exercises the full escalation matrix: base specificity, session escalation,
 * per-mode adjustment, the override force-challenge rule, and boundary values.
 * Pure JVM test — no Android dependencies.
 *
 * Reference intents (classification verified by {@link IntentClassifierTest}):
 *   SPECIFIC: "lecture 4 of cs229 backprop"
 *   VAGUE:    "youtube"
 */
public class VerdictEngineTest {

    private static final String SPECIFIC = "lecture 4 of cs229 backprop";
    private static final String VAGUE = "youtube";

    private static SessionStats stats(int minutes, int videos, int overrides) {
        return new SessionStats(videos, minutes, overrides);
    }

    private static void assertVerdict(Verdict expected, String intent, Mode mode, SessionStats s) {
        assertEquals(expected, VerdictEngine.decide(intent, mode, s));
    }

    // ── Base logic (BALANCED, low session) ──────────────────────────────────

    @Test
    public void specific_lowSession_balanced_approves() {
        assertVerdict(Verdict.APPROVE, SPECIFIC, Mode.BALANCED, stats(0, 0, 0));
    }

    @Test
    public void vague_lowSession_balanced_probes() {
        assertVerdict(Verdict.PROBE, VAGUE, Mode.BALANCED, stats(0, 0, 0));
    }

    // ── Session escalation (BALANCED) ───────────────────────────────────────

    @Test
    public void specific_highVideos_balanced_escalatesToProbe() {
        assertVerdict(Verdict.PROBE, SPECIFIC, Mode.BALANCED, stats(0, 3, 0));
    }

    @Test
    public void specific_longSession_balanced_escalatesToProbe() {
        assertVerdict(Verdict.PROBE, SPECIFIC, Mode.BALANCED, stats(25, 0, 0));
    }

    @Test
    public void vague_highVideos_balanced_escalatesToChallenge() {
        assertVerdict(Verdict.CHALLENGE, VAGUE, Mode.BALANCED, stats(0, 3, 0));
    }

    @Test
    public void vague_longSession_balanced_escalatesToChallenge() {
        assertVerdict(Verdict.CHALLENGE, VAGUE, Mode.BALANCED, stats(25, 0, 0));
    }

    // ── CHILL leniency (one tier down) ──────────────────────────────────────

    @Test
    public void vague_lowSession_chill_probeBecomesApprove() {
        assertVerdict(Verdict.APPROVE, VAGUE, Mode.CHILL, stats(0, 0, 0));
    }

    @Test
    public void vague_highVideos_chill_challengeBecomesProbe() {
        assertVerdict(Verdict.PROBE, VAGUE, Mode.CHILL, stats(0, 3, 0));
    }

    @Test
    public void specific_lowSession_chill_clampsAtApprove() {
        // tier 0 - 1 = -1, clamped back up to APPROVE.
        assertVerdict(Verdict.APPROVE, SPECIFIC, Mode.CHILL, stats(0, 0, 0));
    }

    // ── STRICT mode ─────────────────────────────────────────────────────────

    @Test
    public void specific_longSession_strict_approveBecomesProbe() {
        // Single tier from the shared session-escalation step; strict does NOT
        // re-count minutes>20, so this stays PROBE (matches spec example).
        assertVerdict(Verdict.PROBE, SPECIFIC, Mode.STRICT, stats(25, 0, 0));
    }

    @Test
    public void vague_twoOverrides_strict_probeBecomesChallenge() {
        assertVerdict(Verdict.CHALLENGE, VAGUE, Mode.STRICT, stats(0, 0, 2));
    }

    @Test
    public void specific_twoOverrides_strict_escalatesToProbe() {
        assertVerdict(Verdict.PROBE, SPECIFIC, Mode.STRICT, stats(0, 0, 2));
    }

    // ── Force-challenge on repeated overrides (BALANCED / STRICT) ───────────

    @Test
    public void threeOverrides_balanced_forcesChallenge() {
        assertVerdict(Verdict.CHALLENGE, SPECIFIC, Mode.BALANCED, stats(0, 0, 3));
        assertVerdict(Verdict.CHALLENGE, VAGUE, Mode.BALANCED, stats(0, 0, 3));
    }

    @Test
    public void threeOverrides_strict_forcesChallenge() {
        assertVerdict(Verdict.CHALLENGE, SPECIFIC, Mode.STRICT, stats(0, 0, 3));
    }

    @Test
    public void manyOverrides_chill_neverForcesChallenge() {
        // CHILL never hard-walls: force-challenge rule is skipped in chill.
        assertVerdict(Verdict.APPROVE, SPECIFIC, Mode.CHILL, stats(0, 0, 5));
        assertVerdict(Verdict.APPROVE, VAGUE, Mode.CHILL, stats(0, 0, 5));
    }

    // ── Boundaries ──────────────────────────────────────────────────────────

    @Test
    public void sessionMinutesExactly20_doesNotEscalate() {
        // Threshold is strictly greater-than 20.
        assertVerdict(Verdict.APPROVE, SPECIFIC, Mode.BALANCED, stats(20, 0, 0));
    }

    @Test
    public void videosExactly3_escalates() {
        // Video threshold is inclusive (>= 3).
        assertVerdict(Verdict.PROBE, SPECIFIC, Mode.BALANCED, stats(0, 3, 0));
    }

    // ── Null tolerance ──────────────────────────────────────────────────────

    @Test
    public void nullMode_treatedAsBalanced() {
        assertVerdict(Verdict.PROBE, VAGUE, null, stats(0, 0, 0));
    }

    @Test
    public void nullIntent_treatedAsVague() {
        assertVerdict(Verdict.PROBE, null, Mode.BALANCED, stats(0, 0, 0));
    }
}
