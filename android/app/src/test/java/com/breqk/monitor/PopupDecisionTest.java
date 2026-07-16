package com.Break.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link PopupDecision} — the App Open Intercept re-show logic.
 *
 * Locks the confirmed product spec:
 *   - A fresh open always intercepts once.
 *   - While the user stays in the app, re-show only every X minutes.
 *   - Once-per-open ({@link PopupDecision#ONCE_SENTINEL}) never re-shows.
 *   - A malformed stored interval is clamped, never yielding a ~0ms interval.
 *
 * Regression guard for the YouTube "shows every time the previous closes"
 * glitch: the fix keeps {@code lastPopupMs} alive across coach-owned ticks;
 * these tests assert that a live {@code lastPopupMs} within the interval does
 * NOT re-show. Pure JVM test — no Android dependencies.
 */
public class PopupDecisionTest {

    private static final long T0 = 1_000_000_000_000L; // arbitrary "now" base
    private static final long FIVE_MIN_MS = 5L * 60_000L;

    // ── normalizeDelayMinutes ────────────────────────────────────────────────

    @Test
    public void normalize_passesSentinelThrough() {
        assertEquals(PopupDecision.ONCE_SENTINEL,
                PopupDecision.normalizeDelayMinutes(PopupDecision.ONCE_SENTINEL));
    }

    @Test
    public void normalize_clampsNegativeToZero() {
        assertEquals(0, PopupDecision.normalizeDelayMinutes(-7));
    }

    @Test
    public void normalize_clampsAboveMaxToSixty() {
        assertEquals(60, PopupDecision.normalizeDelayMinutes(9999));
    }

    @Test
    public void normalize_leavesInRangeUntouched() {
        assertEquals(10, PopupDecision.normalizeDelayMinutes(10));
    }

    // ── delayMillis ──────────────────────────────────────────────────────────

    @Test
    public void delayMillis_sentinelIsEffectivelyInfinite() {
        assertEquals(Long.MAX_VALUE, PopupDecision.delayMillis(PopupDecision.ONCE_SENTINEL));
    }

    @Test
    public void delayMillis_convertsMinutesToMs() {
        assertEquals(FIVE_MIN_MS, PopupDecision.delayMillis(5));
    }

    // ── shouldShowFirst ──────────────────────────────────────────────────────

    @Test
    public void first_firesOnFreshOpen() {
        assertTrue(PopupDecision.shouldShowFirst(T0, null, false));
    }

    @Test
    public void first_suppressedWhenAlreadyAllowed() {
        assertFalse(PopupDecision.shouldShowFirst(T0, null, true));
    }

    @Test
    public void first_suppressedWhenNoOpenTimestamp() {
        assertFalse(PopupDecision.shouldShowFirst(null, null, false));
    }

    @Test
    public void first_suppressedOncePopupAlreadyShown() {
        assertFalse(PopupDecision.shouldShowFirst(T0, T0, false));
    }

    // ── shouldShowNext ───────────────────────────────────────────────────────

    @Test
    public void next_falseWhenNoPriorPopup() {
        assertFalse(PopupDecision.shouldShowNext(null, 5, T0));
    }

    @Test
    public void next_falseForOnceSentinel() {
        // Even a year later, once-per-open never re-shows.
        assertFalse(PopupDecision.shouldShowNext(T0, PopupDecision.ONCE_SENTINEL,
                T0 + 365L * 24 * 60 * 60_000L));
    }

    @Test
    public void next_falseWithinInterval() {
        // 4m59s after last popup with a 5-min interval → still suppressed.
        // This is the core regression case: while the coach-preserved timer is
        // alive and inside the window, the intercept must NOT re-show.
        assertFalse(PopupDecision.shouldShowNext(T0, 5, T0 + FIVE_MIN_MS - 1));
    }

    @Test
    public void next_trueExactlyAtInterval() {
        assertTrue(PopupDecision.shouldShowNext(T0, 5, T0 + FIVE_MIN_MS));
    }

    @Test
    public void next_trueAfterInterval() {
        assertTrue(PopupDecision.shouldShowNext(T0, 5, T0 + FIVE_MIN_MS + 1));
    }

    // ── shouldShow (composed) ────────────────────────────────────────────────

    @Test
    public void show_freshOpenIntercepts() {
        assertTrue(PopupDecision.shouldShow(T0, null, 5, false, T0));
    }

    @Test
    public void show_onceModeNeverRepeats() {
        // lastPopup set + once sentinel → no re-show no matter how much time passes.
        assertFalse(PopupDecision.shouldShow(T0, T0, PopupDecision.ONCE_SENTINEL,
                true, T0 + 10L * 60_000L));
    }

    @Test
    public void show_repeatModeSuppressedThenFiresAtInterval() {
        // Simulates staying in the app: first popup shown at T0, X=5min.
        long lastPopup = T0;
        assertFalse("within interval must not re-show",
                PopupDecision.shouldShow(T0, lastPopup, 5, true, T0 + FIVE_MIN_MS - 1));
        assertTrue("re-show once the interval elapses",
                PopupDecision.shouldShow(T0, lastPopup, 5, true, T0 + FIVE_MIN_MS));
    }

    @Test
    public void show_malformedZeroIntervalStillClampedNotNegative() {
        // A malformed 0-min interval is clamped to 0 rather than a negative/huge
        // value, so behaviour stays defined (0ms elapsed >= 0ms → eligible once a
        // first popup exists). Documented edge, not a supported user config.
        assertEquals(0, PopupDecision.normalizeDelayMinutes(0));
        assertTrue(PopupDecision.shouldShowNext(T0, 0, T0));
    }

    // ── coachOwnsYouTubeIntercept ────────────────────────────────────────────
    // Locks the precedence spec: coach ON → typing gate owns YouTube's launch
    // intercept (delay overlay suppressed); coach OFF → YouTube behaves exactly
    // like Instagram. Never both.

    @Test
    public void coach_ownsYouTubeWhenEnabled() {
        assertTrue(PopupDecision.coachOwnsYouTubeIntercept(
                PopupDecision.YOUTUBE_PACKAGE, true));
    }

    @Test
    public void coach_doesNotOwnYouTubeWhenDisabled() {
        assertFalse(PopupDecision.coachOwnsYouTubeIntercept(
                PopupDecision.YOUTUBE_PACKAGE, false));
    }

    @Test
    public void coach_neverOwnsOtherApps() {
        assertFalse(PopupDecision.coachOwnsYouTubeIntercept(
                "com.instagram.android", true));
    }

    @Test
    public void coach_nullPackageIsNotOwned() {
        assertFalse(PopupDecision.coachOwnsYouTubeIntercept(null, true));
    }

    // ── shouldRefireCoach ────────────────────────────────────────────────────
    // The "every X minutes" cadence while the user STAYS in YouTube. The initial
    // show belongs to the relaunch detector, so lastShownAt=0 must never fire.

    @Test
    public void refire_neverBeforeInitialCoachShown() {
        assertFalse(PopupDecision.shouldRefireCoach(0, 5, T0));
    }

    @Test
    public void refire_suppressedWithinInterval() {
        assertFalse(PopupDecision.shouldRefireCoach(T0, 5, T0 + FIVE_MIN_MS - 1));
    }

    @Test
    public void refire_firesExactlyAtInterval() {
        assertTrue(PopupDecision.shouldRefireCoach(T0, 5, T0 + FIVE_MIN_MS));
    }

    @Test
    public void refire_firesAfterInterval() {
        assertTrue(PopupDecision.shouldRefireCoach(T0, 5, T0 + FIVE_MIN_MS + 1));
    }

    @Test
    public void refire_neverForOncePerOpen() {
        assertFalse(PopupDecision.shouldRefireCoach(T0, PopupDecision.ONCE_SENTINEL,
                T0 + 365L * 24 * 60 * 60_000L));
    }

    @Test
    public void refire_malformedIntervalClampedNotInfinite() {
        // A malformed huge interval clamps to 60 min, so re-fire still works.
        assertTrue(PopupDecision.shouldRefireCoach(T0, 9999, T0 + 60L * 60_000L));
        assertFalse(PopupDecision.shouldRefireCoach(T0, 9999, T0 + 60L * 60_000L - 1));
    }
}
