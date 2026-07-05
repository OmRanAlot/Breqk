package com.Break.coach;

/**
 * Verdict
 * -------
 * The three possible outcomes of the mindful-viewing gate, ordered by friction:
 *
 *   APPROVE  (tier 0) — clear intent + low session load. Let the user through.
 *   PROBE    (tier 1) — vague intent OR moderate load. Ask one followup.
 *   CHALLENGE(tier 2) — high load + vague intent (esp. strict). Harder followup.
 *
 * The {@link #tier} value drives {@link VerdictEngine}'s escalation arithmetic
 * (each escalation/leniency step is ±1 tier, clamped to [APPROVE, CHALLENGE]).
 *
 * Logging: none — pure value enum.
 */
public enum Verdict {
    APPROVE(0),
    PROBE(1),
    CHALLENGE(2);

    /** Lowest / highest tier indices, kept in sync with the constants above. */
    static final int MIN_TIER = 0;
    static final int MAX_TIER = 2;

    private final int tier;

    Verdict(int tier) {
        this.tier = tier;
    }

    /** Ordinal friction level: 0 = APPROVE, 1 = PROBE, 2 = CHALLENGE. */
    public int tier() {
        return tier;
    }

    /**
     * Maps a (possibly out-of-range) tier integer back to a Verdict, clamping
     * to the valid [MIN_TIER, MAX_TIER] band. This is the single place tier
     * arithmetic is converted back into a verdict, so over/under-escalation can
     * never throw.
     *
     * @param rawTier tier value after escalation math (may be negative or > 2)
     * @return APPROVE for tier<=0, CHALLENGE for tier>=2, PROBE otherwise
     */
    public static Verdict fromTier(int rawTier) {
        int clamped = Math.max(MIN_TIER, Math.min(MAX_TIER, rawTier));
        switch (clamped) {
            case 0:
                return APPROVE;
            case 2:
                return CHALLENGE;
            default:
                return PROBE;
        }
    }
}
