package com.Break.coach;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.Break.monitor.PopupDecision;
import com.Break.prefs.BreakPrefs;
import com.Break.shortform.FrameworkClassFilter;

/**
 * YouTubeCoachGate
 * ----------------
 * Owns the Mindful Viewing Coach's trigger logic for YouTube, extracted from
 * {@code ReelsInterventionService} (file-size limit + cohesion). The service
 * forwards every accessibility event via {@link #onAccessibilityEvent}; this
 * class decides when to show {@link IntentCoachOverlay} (wait → type intent →
 * verdict) and fires it on two cadences:
 *
 * <ol>
 *   <li><b>Launch gate</b> — a genuine launch / return into YouTube
 *       ({@link #maybeTriggerLaunchGate}).</li>
 *   <li><b>X-minute re-fire</b> — while the user STAYS in YouTube, re-show
 *       every X minutes, where X is the per-app "Re-show overlay" interval the
 *       delay overlay would use ({@link #maybeRefire}).</li>
 * </ol>
 *
 * <h3>Intercept-style precedence</h3>
 * The coach is YouTube's App Open Intercept STYLE, not a second intercept. It
 * only runs when App Open Intercept is enabled for YouTube; the coach toggle
 * then picks WHICH surface fires — coach ON → this typing gate (AppUsageMonitor
 * suppresses its delay overlay, see [COACH_OWNS] there), coach OFF → the
 * ordinary delay overlay, exactly like Instagram. Exactly one of the two ever
 * shows; they can never stack.
 *
 * Logging: the host service's TAG (REELS_WATCH), prefixes [COACH] and
 * [COACH_REFIRE]. (Session/overlay internals log under TAG COACH from this
 * package.)
 */
public final class YouTubeCoachGate {

    private static final String PKG_YOUTUBE = "com.google.android.youtube";

    /**
     * Throttle for the re-fire check: the decision runs at most once per this
     * interval, not on every accessibility event (which can arrive hundreds of
     * times/sec during scroll).
     */
    private static final long REFIRE_CHECK_INTERVAL_MS = 5_000L;

    private final AccessibilityService service;
    private final FrameworkClassFilter frameworkClassFilter;
    private final IntentCoachOverlay overlay;
    private final CoachSessionTracker tracker;
    private final String tag;

    /**
     * Wall-clock ms of the last YouTube-package user-surface event (state change /
     * content change / scroll) seen this service lifetime. In-memory complement to
     * the persisted KEY_COACH_LAST_YT_FOREGROUND (which is only written on
     * throttled boundaries): the relaunch detector takes the max of both, so
     * YouTube's own quiet stretches mid-session (watching a video fires no
     * STATE_CHANGED for minutes) are never misread as a relaunch. Resets to 0 on
     * service restart — the persisted timestamp covers that gap.
     */
    private long lastYtEventWallMs = 0;

    /** Last time the re-fire decision actually ran (see REFIRE_CHECK_INTERVAL_MS). */
    private long lastRefireCheckMs = 0;

    /**
     * @param service              host accessibility service (context + active-window access)
     * @param mainHandler          UI-thread handler for the overlay
     * @param frameworkClassFilter shared system-overlay/package filter owned by the service
     * @param tag                  host service log TAG so logcat filters stay unified
     */
    public YouTubeCoachGate(AccessibilityService service, Handler mainHandler,
            FrameworkClassFilter frameworkClassFilter, String tag) {
        this.service = service;
        this.frameworkClassFilter = frameworkClassFilter;
        this.overlay = new IntentCoachOverlay(service, mainHandler);
        this.tracker = new CoachSessionTracker(service);
        this.tag = tag;
        Log.d(tag, "[COACH] YouTubeCoachGate created (refireCheckIntervalMs="
                + REFIRE_CHECK_INTERVAL_MS + ")");
    }

    /**
     * Entry point — call once per accessibility event, before any early-return
     * branches in the service, so a genuine launch into YouTube is always caught.
     * Cheap: each path fast-exits for non-YouTube packages / irrelevant types.
     */
    public void onAccessibilityEvent(String packageName, AccessibilityEvent event) {
        maybeTriggerLaunchGate(packageName, event);
        maybeRefire(packageName, event);

        // Record the freshest YouTube user-surface activity AFTER the two checks
        // above (the current event must not count as "already seen" when the
        // relaunch detector measures the gap it just arrived across).
        if (PKG_YOUTUBE.equals(packageName) && isUserSurfaceEvent(event.getEventType())) {
            lastYtEventWallMs = System.currentTimeMillis();
        }
    }

    /**
     * Detects a genuine launch / return into YouTube and shows the coach.
     *
     * Only TYPE_WINDOW_STATE_CHANGED events for the YouTube package are considered.
     * Transient windows (our own overlay, framework-class windows, known system
     * overlays like IME/status bar) are ignored entirely — they carry no launch
     * signal either way.
     *
     * <h3>Relaunch detection (time-gap, not pinned-package)</h3>
     * Previously this compared against {@code coachLastForegroundPackage}, the last
     * *real* foreground package seen. That heuristic silently broke for YouTube
     * specifically: leaving YouTube via the Home button fires a launcher
     * WINDOW_STATE_CHANGED event, but the launcher is deliberately filtered out as a
     * system-overlay package (the Reels [STICKY-FIX] — see FrameworkClassFilter) so
     * {@code coachLastForegroundPackage} was never cleared. Every re-open of YouTube
     * after Home then looked like an internal window change and the coach never
     * fired — until some other real app happened to be opened in between.
     *
     * Fix: derive "is this a relaunch?" from the freshest evidence YouTube was on
     * screen — the persisted last-YouTube-foreground timestamp OR the in-memory
     * {@link #lastYtEventWallMs}. YouTube's own internal window changes fire
     * back-to-back within a few hundred ms; a gap of
     * {@link BreakPrefs#COACH_RELAUNCH_GAP_MS} (1.5s) or more since YouTube was last
     * seen cleanly marks a genuine return from elsewhere, independent of whether we
     * ever observed the intervening launcher/app event.
     *
     * <h3>Cadence</h3>
     * The coach fires on every genuine relaunch (not once per 30-min session);
     * {@link CoachSessionTracker#shouldShowCoach} applies only a short re-show
     * cooldown ({@code COACH_RESHOW_COOLDOWN_MS}) to prevent a single launch's
     * multiple STATE_CHANGED events from double-showing the overlay. While the
     * user STAYS in YouTube, {@link #maybeRefire} re-shows the coach every X
     * minutes (the per-app "Re-show overlay" setting).
     */
    private void maybeTriggerLaunchGate(String packageName, AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        if (!BreakPrefs.isCoachEnabled(service)) {
            return;
        }
        if (!PKG_YOUTUBE.equals(packageName)) {
            return;
        }
        if (!BreakPrefs.isFeatureEnabled(service, PKG_YOUTUBE, BreakPrefs.FEATURE_APP_OPEN_INTERCEPT)) {
            Log.d(tag, "[COACH] App Open Intercept disabled for YouTube — no launch gate at all");
            return;
        }

        // Ignore transient windows — our own overlay, framework-class windows, and
        // known system overlays never represent a real YouTube navigation.
        String className = event.getClassName() != null ? event.getClassName().toString() : "";
        if (packageName.equals(service.getPackageName())
                || FrameworkClassFilter.isFrameworkClass(className)
                || frameworkClassFilter.isSystemOverlayPackage(packageName, service, tag)) {
            return;
        }

        long now = System.currentTimeMillis();
        SharedPreferences prefs = BreakPrefs.get(service);
        // Freshest evidence YouTube was on screen: the persisted foreground stamp
        // (survives service restarts) OR the in-memory last user-surface event
        // (updated on every YouTube content/scroll event, so a quiet stretch of
        // video-watching with no STATE_CHANGED can't masquerade as a relaunch —
        // only the X-minute re-fire may interrupt mid-session).
        long lastYtForeground = Math.max(
                prefs.getLong(BreakPrefs.KEY_COACH_LAST_YT_FOREGROUND, 0),
                lastYtEventWallMs);
        long gap = lastYtForeground == 0 ? Long.MAX_VALUE : now - lastYtForeground;
        boolean isRelaunch = gap >= BreakPrefs.COACH_RELAUNCH_GAP_MS;

        // Always refresh the timestamp / roll the session boundary — keeps session
        // stats (video counts, session minutes) correct regardless of the relaunch
        // decision below.
        tracker.onYouTubeForeground(now);

        Log.d(tag, "[COACH] YouTube STATE_CHANGED gap=" + (lastYtForeground == 0 ? "n/a" : gap + "ms")
                + " isRelaunch=" + isRelaunch);
        if (!isRelaunch) {
            // YouTube's own internal window change, not a launch.
            return;
        }

        if (overlay.isShowing()) {
            Log.d(tag, "[COACH] Relaunch detected but overlay already showing — skip");
            return;
        }
        if (!tracker.shouldShowCoach(service)) {
            return;
        }
        if (!Settings.canDrawOverlays(service)) {
            Log.w(tag, "[COACH] Overlay permission (SYSTEM_ALERT_WINDOW) missing — cannot show YouTube coach");
            return;
        }

        Log.i(tag, "[COACH] YouTube relaunch detected (gap=" + gap + "ms) — showing intent coach");
        overlay.show(
                () -> Log.i(tag, "[COACH] User satisfied the gate — proceeding into YouTube"),
                () -> Log.i(tag, "[COACH] User exited to home from the coach"));
    }

    /**
     * Re-fires the coach every X minutes while the user STAYS inside YouTube.
     *
     * The launch gate above only catches relaunches (foreground gap ≥ 1.5s); a
     * continuous viewing session would otherwise never be interrupted again. This
     * check rides the YouTube accessibility event stream (content changes fire
     * steadily during playback), throttled to one evaluation per
     * {@link #REFIRE_CHECK_INTERVAL_MS}, and re-shows the coach once the per-app
     * "Every X min" re-show interval — the SAME setting the delay overlay uses
     * (AppDetail → App Open Intercept → Re-show overlay) — has elapsed since the
     * coach last actually showed ({@code coach_last_shown_at}). "Once per open"
     * (sentinel) disables re-fire; {@link CoachSessionTracker#shouldShowCoach}
     * additionally enforces the 60s anti-double-fire cooldown.
     *
     * Guards, in order: YouTube package + user-surface event type → throttle →
     * feature gates (coach + App Open Intercept) → overlay not already up →
     * pure cadence decision ({@link PopupDecision#shouldRefireCoach}) → YouTube
     * actually the ACTIVE window (a PiP window or a stray background event must
     * not pop the coach over another app) → overlay permission.
     */
    private void maybeRefire(String packageName, AccessibilityEvent event) {
        if (!PKG_YOUTUBE.equals(packageName)
                || !isUserSurfaceEvent(event.getEventType())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRefireCheckMs < REFIRE_CHECK_INTERVAL_MS) {
            return;
        }
        lastRefireCheckMs = now;

        if (!BreakPrefs.isCoachEnabled(service)) {
            return;
        }
        if (!BreakPrefs.isFeatureEnabled(service, PKG_YOUTUBE, BreakPrefs.FEATURE_APP_OPEN_INTERCEPT)) {
            return;
        }
        if (overlay.isShowing()) {
            return;
        }

        // Keep session stats/boundaries fresh even during long passive watching
        // (STATE_CHANGED-only updates would let the 30-min session gap lapse).
        tracker.onYouTubeForeground(now);

        long lastShownAt = BreakPrefs.get(service).getLong(BreakPrefs.KEY_COACH_LAST_SHOWN_AT, 0);
        int rawDelayMin = BreakPrefs.getEffectivePopupDelayMinutes(service, PKG_YOUTUBE);
        if (!PopupDecision.shouldRefireCoach(lastShownAt, rawDelayMin, now)) {
            return;
        }

        // Confirm YouTube truly owns the screen: events can arrive from a PiP
        // window while the user is in another app — never pop the coach there.
        AccessibilityNodeInfo activeRoot = service.getRootInActiveWindow();
        boolean youTubeIsActive = activeRoot != null
                && activeRoot.getPackageName() != null
                && PKG_YOUTUBE.contentEquals(activeRoot.getPackageName());
        if (!youTubeIsActive) {
            Log.d(tag, "[COACH_REFIRE] Interval elapsed but YouTube is not the active window — skip");
            return;
        }

        if (!tracker.shouldShowCoach(service)) {
            return;
        }
        if (!Settings.canDrawOverlays(service)) {
            Log.w(tag, "[COACH_REFIRE] Overlay permission missing — cannot re-fire coach");
            return;
        }

        Log.i(tag, "[COACH_REFIRE] Re-show interval elapsed (delayMin=" + rawDelayMin
                + " sinceLastShownMs=" + (now - lastShownAt) + ") — showing intent coach again");
        overlay.show(
                () -> Log.i(tag, "[COACH_REFIRE] User satisfied the gate — continuing in YouTube"),
                () -> Log.i(tag, "[COACH_REFIRE] User exited to home from the coach"));
    }

    /**
     * Event types that indicate the user-facing YouTube surface produced activity.
     * Used both to time-stamp "YouTube was just on screen" for the relaunch
     * detector and to gate the re-fire check. Excludes notification/announcement
     * noise, which can fire from YouTube while the user is elsewhere.
     */
    private static boolean isUserSurfaceEvent(int eventType) {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED;
    }
}
