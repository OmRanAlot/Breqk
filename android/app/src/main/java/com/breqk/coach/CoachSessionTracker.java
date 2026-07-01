package com.Break.coach;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.Break.prefs.BreakPrefs;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * CoachSessionTracker
 * -------------------
 * Owns the runtime state of a YouTube viewing "session" for the mindful-viewing
 * coach, persisted in {@link BreakPrefs} so it survives the accessibility
 * service / overlay being torn down between events.
 *
 * <h3>Session boundary</h3>
 * A session is a continuous-ish stretch of YouTube use. {@link #onYouTubeForeground}
 * is called whenever YouTube comes to the foreground; if YouTube has not been seen
 * for longer than {@link BreakPrefs#COACH_SESSION_GAP_MS}, a fresh session starts
 * (session-start reset, video count zeroed, show-once gate re-armed). The coach
 * overlay is shown at most once per session ({@link #shouldShowCoach} /
 * {@link #markCoachShown}).
 *
 * <h3>Counters</h3>
 *   - videosWatched  — per session; bumped by {@link #incrementVideosWatched}
 *                      (wired in Phase 5 on player transitions).
 *   - overridesToday — per local day; bumped by {@link #recordOverride} each time
 *                      the user pushes past a probe/challenge. Resets at midnight.
 *
 * All reads/writes go through {@code Break_prefs}; this class performs no UI work.
 *
 * Logging: TAG "COACH" — prefixes [SESSION], [GATE], [OVERRIDE].
 */
public final class CoachSessionTracker {

    private static final String TAG = "COACH";

    /** Date format for the daily override-counter rollover key. */
    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private final SharedPreferences prefs;

    public CoachSessionTracker(Context context) {
        this.prefs = BreakPrefs.get(context.getApplicationContext());
    }

    /**
     * Records that YouTube is foreground at {@code now} and decides whether this
     * begins a new session. A new session is started when YouTube has been absent
     * longer than {@link BreakPrefs#COACH_SESSION_GAP_MS}, or when no session has
     * ever started.
     *
     * @param now current epoch ms
     * @return true if a NEW session was started by this call
     */
    public boolean onYouTubeForeground(long now) {
        long lastForeground = prefs.getLong(BreakPrefs.KEY_COACH_LAST_YT_FOREGROUND, 0);
        long sessionStart = prefs.getLong(BreakPrefs.KEY_COACH_SESSION_START, 0);
        boolean newSession = sessionStart == 0
                || (now - lastForeground) > BreakPrefs.COACH_SESSION_GAP_MS;

        SharedPreferences.Editor editor = prefs.edit()
                .putLong(BreakPrefs.KEY_COACH_LAST_YT_FOREGROUND, now);

        if (newSession) {
            editor.putLong(BreakPrefs.KEY_COACH_SESSION_START, now)
                    .putInt(BreakPrefs.KEY_COACH_VIDEOS_WATCHED, 0)
                    .putBoolean(BreakPrefs.KEY_COACH_SHOWN_FOR_SESSION, false);
            Log.i(TAG, "[SESSION] New YouTube session started (gap since last fg="
                    + (lastForeground == 0 ? "n/a" : (now - lastForeground) + "ms") + ")");
        }
        editor.apply();
        return newSession;
    }

    /**
     * Whether the coach overlay should be shown right now: the feature is enabled
     * and it has not already been shown during the current session.
     */
    public boolean shouldShowCoach(Context context) {
        boolean enabled = BreakPrefs.isCoachEnabled(context);
        boolean alreadyShown = prefs.getBoolean(BreakPrefs.KEY_COACH_SHOWN_FOR_SESSION, false);
        boolean show = enabled && !alreadyShown;
        Log.d(TAG, "[GATE] shouldShowCoach enabled=" + enabled
                + " alreadyShown=" + alreadyShown + " → " + show);
        return show;
    }

    /** Marks the coach as having run for this session (show-once enforcement). */
    public void markCoachShown() {
        prefs.edit().putBoolean(BreakPrefs.KEY_COACH_SHOWN_FOR_SESSION, true).apply();
        Log.d(TAG, "[GATE] Coach marked shown for current session");
    }

    /** Increments the per-session video counter (called on player transitions). */
    public void incrementVideosWatched() {
        int next = prefs.getInt(BreakPrefs.KEY_COACH_VIDEOS_WATCHED, 0) + 1;
        prefs.edit().putInt(BreakPrefs.KEY_COACH_VIDEOS_WATCHED, next).apply();
        Log.d(TAG, "[SESSION] videosWatched=" + next);
    }

    /**
     * Records that the user pushed past a probe/challenge. Rolls the daily counter
     * if the calendar day changed, then increments it.
     *
     * @param now current epoch ms
     * @return the new override count for today
     */
    public int recordOverride(long now) {
        int current = overridesToday(now);
        int next = current + 1;
        prefs.edit()
                .putString(BreakPrefs.KEY_COACH_OVERRIDES_DATE, today(now))
                .putInt(BreakPrefs.KEY_COACH_OVERRIDES_TODAY, next)
                .apply();
        Log.i(TAG, "[OVERRIDE] overridesToday=" + next);
        return next;
    }

    /**
     * Builds the immutable {@link SessionStats} snapshot the verdict engine needs.
     *
     * @param now current epoch ms
     */
    public SessionStats currentStats(long now) {
        long sessionStart = prefs.getLong(BreakPrefs.KEY_COACH_SESSION_START, 0);
        int sessionMinutes = sessionStart == 0
                ? 0
                : (int) Math.max(0, (now - sessionStart) / 60_000L);
        int videos = prefs.getInt(BreakPrefs.KEY_COACH_VIDEOS_WATCHED, 0);
        int overrides = overridesToday(now);
        return new SessionStats(videos, sessionMinutes, overrides);
    }

    /** Current friction level, parsed from prefs (defaults to BALANCED). */
    public Mode mode(Context context) {
        return Mode.fromString(BreakPrefs.getCoachMode(context));
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Returns today's override count, treating a stored date that is not today as
     * a zeroed counter (the actual reset write happens lazily on the next
     * {@link #recordOverride}).
     */
    private int overridesToday(long now) {
        String storedDate = prefs.getString(BreakPrefs.KEY_COACH_OVERRIDES_DATE, "");
        if (!today(now).equals(storedDate)) {
            return 0;
        }
        return prefs.getInt(BreakPrefs.KEY_COACH_OVERRIDES_TODAY, 0);
    }

    private static String today(long now) {
        return DATE_FMT.format(new Date(now));
    }
}
