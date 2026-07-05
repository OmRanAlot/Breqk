package com.Break.coach;

/**
 * VerdictEngine
 * -------------
 * Deterministic, on-device decision core for the YouTube mindful-viewing coach.
 * Given the user's typed intent, chosen {@link Mode}, and {@link SessionStats},
 * it returns a {@link Verdict} (APPROVE / PROBE / CHALLENGE). The LLM never makes
 * this decision — it only phrases the resulting message — so the gate is fully
 * reproducible and unit-testable offline.
 *
 * <h3>Escalation pipeline (fixed order — see plan Phase 1)</h3>
 * Tiers are integers (APPROVE=0, PROBE=1, CHALLENGE=2). Each step adjusts the
 * running tier; the result is clamped back to a Verdict at the end.
 *
 * <ol>
 *   <li><b>Base from specificity:</b> SPECIFIC → 0, VAGUE → 1. (Vague intent can
 *       therefore never APPROVE without a leniency step, matching the spec's
 *       "vague intent always lands at PROBE or CHALLENGE".)</li>
 *   <li><b>Session escalation:</b> {@code sessionMinutes > 20 OR videosWatched >= 3}
 *       → +1 tier.</li>
 *   <li><b>Mode adjust:</b>
 *       <ul>
 *         <li>CHILL → −1 tier (one tier more lenient).</li>
 *         <li>STRICT → +1 tier when {@code overridesToday >= 2} (the spec's
 *             "probe→challenge" strict bump). The "approve→probe" strict case
 *             for {@code sessionMinutes > 20} is already produced by the shared
 *             session-escalation step, so it is not re-counted here.</li>
 *         <li>BALANCED → no change.</li>
 *       </ul></li>
 *   <li><b>Force challenge:</b> in BALANCED or STRICT, {@code overridesToday >= 3}
 *       forces CHALLENGE outright (overrides any leniency above).</li>
 *   <li><b>Clamp:</b> {@link Verdict#fromTier(int)} maps the final tier into
 *       [APPROVE, CHALLENGE].</li>
 * </ol>
 *
 * NOTE: the underlying product spec lists both a generic "escalate one tier"
 * rule and mode-specific bumps without fully defining their composition order.
 * The order above is the agreed interpretation; changing it changes outputs, so
 * it is intentionally centralized here and covered exhaustively by tests.
 *
 * Logging: none — Android-free pure logic. Callers log the inputs/outcome under
 * the [COACH] tag.
 */
public final class VerdictEngine {

    private VerdictEngine() {}

    /** Session is "moderate+" once minutes exceed this (strictly greater). */
    static final int MODERATE_SESSION_MINUTES = 20;
    /** Session is "moderate+" once videos watched reaches this (inclusive). */
    static final int HIGH_VIDEO_COUNT = 3;
    /** In STRICT mode, this many overrides today (inclusive) adds a tier. */
    static final int STRICT_OVERRIDE_THRESHOLD = 2;
    /** In BALANCED/STRICT, this many overrides today (inclusive) forces CHALLENGE. */
    static final int FORCE_CHALLENGE_OVERRIDES = 3;

    /**
     * Computes the verdict. Never returns null; tolerates null intent (treated
     * as VAGUE) and null mode (treated as BALANCED).
     *
     * @param intent raw user-typed intent
     * @param mode   friction level (null → BALANCED)
     * @param stats  current session snapshot (must not be null)
     * @return the verdict after the full escalation pipeline
     */
    public static Verdict decide(String intent, Mode mode, SessionStats stats) {
        Mode effectiveMode = (mode == null) ? Mode.BALANCED : mode;
        Specificity specificity = IntentClassifier.classify(intent);

        // 1. Base tier from specificity.
        int tier = (specificity == Specificity.SPECIFIC)
                ? Verdict.APPROVE.tier()
                : Verdict.PROBE.tier();

        // 2. Session escalation.
        if (stats.sessionMinutes() > MODERATE_SESSION_MINUTES
                || stats.videosWatched() >= HIGH_VIDEO_COUNT) {
            tier += 1;
        }

        // 3. Mode adjust.
        switch (effectiveMode) {
            case CHILL:
                tier -= 1;
                break;
            case STRICT:
                // Only overrides drive the strict-specific bump. The
                // sessionMinutes>20 signal is already applied by the shared
                // session-escalation step above, so re-counting it here would
                // double-penalize (a specific intent after a long session would
                // jump straight to CHALLENGE, contradicting the spec's
                // "strict: session_minutes>20 → approve→probe", a single tier).
                if (stats.overridesToday() >= STRICT_OVERRIDE_THRESHOLD) {
                    tier += 1;
                }
                break;
            case BALANCED:
            default:
                break;
        }

        // 4. Force challenge on repeated overrides (not in CHILL — chill never
        //    hard-walls the user).
        if (effectiveMode != Mode.CHILL
                && stats.overridesToday() >= FORCE_CHALLENGE_OVERRIDES) {
            return Verdict.CHALLENGE;
        }

        // 5. Clamp.
        return Verdict.fromTier(tier);
    }
}
