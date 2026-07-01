package com.Break.coach;

/**
 * CoachCopy
 * ---------
 * Deterministic, on-device fallback copy for the coach overlay. Produces the
 * warm message + (optional) followup question for a given verdict, referencing
 * the user's actual intent and session stats.
 *
 * This is the templated baseline used until the on-device LLM (Phase 3) is wired
 * in. The LLM will replace {@link #forVerdict} with generated phrasing behind the
 * same {@link CoachMessage} shape; if inference is unavailable the overlay falls
 * back to this class, so the gate always has copy to show (fail-open on phrasing,
 * never on the verdict itself).
 *
 * Tone: warm, curious, non-judgmental; messages kept short (≈ <= 35 words).
 *
 * Logging: none — pure templating. The overlay owns logging.
 */
public final class CoachCopy {

    private CoachCopy() {}

    /** Max characters of the user's intent we echo back, to keep messages short. */
    private static final int INTENT_ECHO_MAX = 60;

    /**
     * Immutable message bundle shown by the overlay.
     *   message  — affirming / reflective line (never null).
     *   followup — question the user must answer for PROBE/CHALLENGE; null for
     *              APPROVE (nothing to answer).
     */
    public static final class CoachMessage {
        public final String message;
        public final String followup;

        public CoachMessage(String message, String followup) {
            this.message = message;
            this.followup = followup;
        }
    }

    /**
     * Builds copy for a verdict. Never returns null.
     *
     * @param verdict the decided outcome
     * @param intent  the user's typed intent (may be null/empty)
     * @param stats   the current session snapshot
     */
    public static CoachMessage forVerdict(Verdict verdict, String intent, SessionStats stats) {
        switch (verdict) {
            case APPROVE:
                return approve(intent);
            case CHALLENGE:
                return challenge(intent, stats);
            case PROBE:
            default:
                return probe(intent, stats);
        }
    }

    // ── APPROVE ─────────────────────────────────────────────────────────────

    private static CoachMessage approve(String intent) {
        String echo = echo(intent);
        String message = echo.isEmpty()
                ? "Sounds intentional. Enjoy it — and head out when you're done."
                : "Nice — \"" + echo + "\". Enjoy it, and come back out when you're done.";
        return new CoachMessage(message, null);
    }

    // ── PROBE ───────────────────────────────────────────────────────────────

    private static CoachMessage probe(String intent, SessionStats stats) {
        // Pick the followup that best fits *why* we're probing.
        if (IntentClassifier.classify(intent) == Specificity.VAGUE) {
            return new CoachMessage(
                    "Quick gut-check before you dive in.",
                    "What specific video or topic are you here for?");
        }
        if (stats.sessionMinutes() > VerdictEngine.MODERATE_SESSION_MINUTES) {
            return new CoachMessage(
                    "You've been here a little while.",
                    "What's the goal for this next one?");
        }
        return new CoachMessage(
                "One small check first.",
                "What's the goal for this next one?");
    }

    // ── CHALLENGE ───────────────────────────────────────────────────────────

    private static CoachMessage challenge(String intent, SessionStats stats) {
        if (stats.videosWatched() >= VerdictEngine.HIGH_VIDEO_COUNT) {
            return new CoachMessage(
                    "That's " + stats.videosWatched() + " videos today — worth a pause.",
                    "What would feel like a good stopping point after this one?");
        }
        return new CoachMessage(
                "Let's make this one count.",
                "What exactly do you want to get from this, and when will you stop?");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Trimmed, length-capped echo of the user's intent (empty if none). */
    private static String echo(String intent) {
        if (intent == null) {
            return "";
        }
        String trimmed = intent.trim();
        if (trimmed.length() <= INTENT_ECHO_MAX) {
            return trimmed;
        }
        return trimmed.substring(0, INTENT_ECHO_MAX - 1).trim() + "…";
    }
}
