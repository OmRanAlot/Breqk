package com.Break.coach;

/**
 * SessionStats
 * ------------
 * Immutable snapshot of the current YouTube viewing session, fed to
 * {@link VerdictEngine}. All three fields are non-negative.
 *
 *   videosWatched  — videos opened during the current session.
 *   sessionMinutes — wall-clock minutes since the session started.
 *   overridesToday — times the user pushed past a probe/challenge today
 *                    (resets at local midnight; managed by CoachSessionTracker).
 *
 * Populated at runtime from BreakPrefs by CoachSessionTracker (Phase 2). This
 * class itself performs no I/O — it is a plain value holder so the engine stays
 * unit-testable without Android.
 *
 * Logging: none — immutable value type.
 */
public final class SessionStats {

    private final int videosWatched;
    private final int sessionMinutes;
    private final int overridesToday;

    public SessionStats(int videosWatched, int sessionMinutes, int overridesToday) {
        // Defensive clamp: negative counters are meaningless and would corrupt
        // the escalation math. Treat any negative input as zero.
        this.videosWatched = Math.max(0, videosWatched);
        this.sessionMinutes = Math.max(0, sessionMinutes);
        this.overridesToday = Math.max(0, overridesToday);
    }

    public int videosWatched() {
        return videosWatched;
    }

    public int sessionMinutes() {
        return sessionMinutes;
    }

    public int overridesToday() {
        return overridesToday;
    }
}
