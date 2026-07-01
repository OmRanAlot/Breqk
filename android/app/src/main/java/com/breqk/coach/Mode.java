package com.Break.coach;

/**
 * Mode
 * ----
 * User-selected friction level for the YouTube mindful-viewing coach.
 *
 * The mode tunes how aggressively {@link VerdictEngine} escalates a verdict:
 *   - CHILL    — one tier more lenient than the base logic.
 *   - BALANCED — base logic, no adjustment.
 *   - STRICT   — one tier stricter under high session pressure.
 *
 * Persisted as a lowercase string under BreakPrefs (key "coach_mode").
 *
 * Logging: none — pure value enum.
 */
public enum Mode {
    CHILL,
    BALANCED,
    STRICT;

    /**
     * Parses a stored mode string. Unknown / null values fall back to BALANCED
     * so a corrupt preference can never disable the coach or crash the engine.
     *
     * @param raw stored value such as "chill", "BALANCED", or null
     * @return the matching Mode, or BALANCED when unrecognized
     */
    public static Mode fromString(String raw) {
        if (raw == null) {
            return BALANCED;
        }
        switch (raw.trim().toLowerCase()) {
            case "chill":
                return CHILL;
            case "strict":
                return STRICT;
            case "balanced":
            default:
                return BALANCED;
        }
    }

    /** Lowercase wire/storage form, e.g. "chill". */
    public String key() {
        return name().toLowerCase();
    }
}
