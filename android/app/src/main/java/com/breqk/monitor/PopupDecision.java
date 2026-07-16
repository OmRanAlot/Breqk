package com.Break.monitor;

/**
 * PopupDecision
 * -------------
 * Pure-function helpers for the App Open Intercept overlay re-show decision.
 *
 * Extracted from {@link AppUsageMonitor}'s polling loop so the "should the
 * intercept fire on this tick?" logic can be unit-tested in isolation (mirrors
 * how {@link com.Break.shortform.budget.ScrollBudgetLogic} was pulled out).
 *
 * The intended behaviour (confirmed product spec):
 *   - A genuine fresh app open always intercepts once (first popup).
 *   - While the user STAYS in the app, the intercept re-shows every X minutes
 *     ({@code delayMinutes}) — governed by {@code lastPopupMs}.
 *   - {@link #ONCE_SENTINEL} means "once per open": never re-show within a session.
 *
 * No Android dependencies — safe to unit-test on the plain JVM.
 */
public final class PopupDecision {

    private PopupDecision() {}

    /**
     * Sentinel re-show interval meaning "show once per open, never re-show".
     * Mirrors {@code BreakPrefs.POPUP_DELAY_ONCE_SENTINEL} and the JS
     * {@code POPUP_DELAY_ONCE_SENTINEL} (both {@code Integer.MAX_VALUE}).
     */
    public static final int ONCE_SENTINEL = Integer.MAX_VALUE;

    private static final int MIN_DELAY_MINUTES = 0;
    private static final int MAX_DELAY_MINUTES = 60;

    /**
     * Normalizes a raw popup-delay value read from prefs.
     *
     * Passes the {@link #ONCE_SENTINEL} through untouched; otherwise clamps into
     * the same 0..60 range the Customize / AppDetail sliders enforce. This guards
     * the per-app path (which previously applied no clamp) against a malformed
     * stored value yielding a ~0 ms interval — i.e. an intercept that re-shows on
     * every tick instead of every X minutes.
     */
    public static int normalizeDelayMinutes(int rawMinutes) {
        if (rawMinutes == ONCE_SENTINEL) {
            return ONCE_SENTINEL;
        }
        return Math.max(MIN_DELAY_MINUTES, Math.min(MAX_DELAY_MINUTES, rawMinutes));
    }

    /**
     * Milliseconds for a normalized re-show interval. Returns {@link Long#MAX_VALUE}
     * for the once-per-open sentinel so a "next popup" comparison can never succeed.
     */
    public static long delayMillis(int normalizedMinutes) {
        if (normalizedMinutes == ONCE_SENTINEL) {
            return Long.MAX_VALUE;
        }
        return (long) normalizedMinutes * 60_000L;
    }

    /**
     * True when a fresh app open should trigger its first intercept this session.
     *
     * @param appOpenMs   epoch ms the current app session started, or null if untracked
     * @param lastPopupMs epoch ms the last intercept was shown this session, or null if none yet
     * @param isAllowed   true once the user has cleared the first intercept this session (Continue)
     */
    public static boolean shouldShowFirst(Long appOpenMs, Long lastPopupMs, boolean isAllowed) {
        return appOpenMs != null && lastPopupMs == null && !isAllowed;
    }

    /**
     * True when an intercept has already shown this session and the re-show
     * interval has elapsed. Always false for the once-per-open sentinel.
     *
     * @param lastPopupMs       epoch ms the last intercept was shown, or null if none yet
     * @param normalizedMinutes re-show interval (already run through {@link #normalizeDelayMinutes})
     * @param now               current epoch ms
     */
    public static boolean shouldShowNext(Long lastPopupMs, int normalizedMinutes, long now) {
        if (lastPopupMs == null || normalizedMinutes == ONCE_SENTINEL) {
            return false;
        }
        return (now - lastPopupMs) >= delayMillis(normalizedMinutes);
    }

    /**
     * Overall decision: show the App Open Intercept overlay on this tick?
     *
     * @param appOpenMs    epoch ms the app session started (null = untracked)
     * @param lastPopupMs  epoch ms the last intercept was shown this session (null = none yet)
     * @param delayMinutes RAW re-show interval; normalized internally. {@link #ONCE_SENTINEL} = once per open
     * @param isAllowed    true once the user cleared the first intercept this session
     * @param now          current epoch ms
     */
    public static boolean shouldShow(Long appOpenMs, Long lastPopupMs, int delayMinutes,
            boolean isAllowed, long now) {
        int norm = normalizeDelayMinutes(delayMinutes);
        return shouldShowFirst(appOpenMs, lastPopupMs, isAllowed)
                || shouldShowNext(lastPopupMs, norm, now);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mindful Viewing Coach (YouTube typing gate) — ownership + re-fire cadence
    // ─────────────────────────────────────────────────────────────────────────

    /** Package the typing coach is scoped to. Mirrors ReelsInterventionService.PKG_YOUTUBE. */
    public static final String YOUTUBE_PACKAGE = "com.google.android.youtube";

    /**
     * True when the typing coach owns YouTube's App Open Intercept, i.e. the
     * ordinary delay overlay must NOT show for this package. Product spec:
     * the coach toggle switches YouTube's launch intercept STYLE — coach ON →
     * typing gate (initial + every X minutes), coach OFF → normal delay overlay
     * exactly like any other app. Exactly one of the two ever fires; they never
     * stack.
     *
     * @param packageName  foreground package being evaluated
     * @param coachEnabled live value of {@code BreakPrefs.isCoachEnabled()}
     */
    public static boolean coachOwnsYouTubeIntercept(String packageName, boolean coachEnabled) {
        return coachEnabled && YOUTUBE_PACKAGE.equals(packageName);
    }

    /**
     * True when the coach should RE-fire while the user stays inside YouTube:
     * an initial coach has already shown ({@code lastShownAtMs > 0}) and the
     * user's re-show interval — the same per-app "Every X min" setting the
     * delay overlay uses — has fully elapsed. {@link #ONCE_SENTINEL} ("once per
     * open") disables re-fire entirely.
     *
     * The INITIAL show is owned by the relaunch detector (gap-based, in
     * ReelsInterventionService); this method never fires it, hence the
     * {@code lastShownAtMs <= 0} guard.
     *
     * @param lastShownAtMs   epoch ms the coach last actually showed (0 = never)
     * @param rawDelayMinutes RAW re-show interval from prefs; normalized internally
     * @param now             current epoch ms
     */
    public static boolean shouldRefireCoach(long lastShownAtMs, int rawDelayMinutes, long now) {
        if (lastShownAtMs <= 0) {
            return false;
        }
        int norm = normalizeDelayMinutes(rawDelayMinutes);
        if (norm == ONCE_SENTINEL) {
            return false;
        }
        return (now - lastShownAtMs) >= delayMillis(norm);
    }
}
