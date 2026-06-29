package com.Break.shortform.metrics;

import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * HomeFeedScrollMeter
 * -------------------
 * Pure-measurement tracker for Instagram HOME-FEED scrolling only.
 *
 * Records, per Instagram session, how much the user has scrolled the home feed:
 *   - postsPassed  : number of feed posts scrolled past (RecyclerView first-visible
 *                    item index movement, via AccessibilityEvent.getFromIndex()).
 *   - totalPixels  : cumulative vertical scroll distance in pixels
 *                    (AccessibilityEvent.getScrollDeltaY(), API 28+).
 *
 * This class does NOT trigger any overlay or intervention. It only logs. It is
 * intentionally decoupled from {@code HomeFeedCounter} (the post-limit enforcer)
 * so measurement runs unconditionally regardless of intervention state.
 *
 * "Home feed only" is the caller's responsibility: the meter must be fed scroll
 * events whose source view ID is the home-feed RecyclerView
 * ({@code feed_main_recycler_view}). Reels, Explore, DMs and Stories use
 * different view IDs and never reach this meter.
 *
 * State is in-memory: it resets when the user leaves Instagram (or the service
 * is interrupted). On each reset a single [SESSION] summary line is emitted.
 *
 * Dedicated log tag so this data can be isolated from all other app logging:
 *   adb logcat -s FEED_SCROLL
 *
 * getScrollDeltaY() was added in API 28. minSdk is 24, so on API 24-27 pixel
 * distance is unavailable; those events still count posts and log px as "n/a".
 */
public class HomeFeedScrollMeter {

    /** Dedicated tag — `adb logcat -s FEED_SCROLL` shows ONLY home-feed metrics. */
    private static final String TAG = "FEED_SCROLL";

    /** Sentinel meaning "no scroll event seen yet this session". */
    private static final int INDEX_UNSET = -1;

    /** Cumulative posts scrolled past this session. */
    private int postsPassed = 0;

    /** Cumulative vertical scroll distance in pixels this session (API 28+ only). */
    private long totalPixels = 0L;

    /** Number of home-feed scroll events processed this session. */
    private int scrollEvents = 0;

    /** First-visible feed item index from the previous scroll event; INDEX_UNSET until seeded. */
    private int lastFromIndex = INDEX_UNSET;

    /** True once at least one scroll event has contributed to a metric (avoids empty summaries). */
    private boolean hasData = false;

    /**
     * Records one home-feed scroll event.
     *
     * The caller MUST have already confirmed this event is an Instagram home-feed
     * scroll (source view ID == feed_main_recycler_view); the meter does no
     * screen filtering of its own.
     *
     * @param event the TYPE_VIEW_SCROLLED accessibility event from the home feed
     */
    public void onScroll(AccessibilityEvent event) {
        if (event == null) return;

        scrollEvents++;

        // --- Posts passed: movement of the first-visible RecyclerView item index ---
        int fromIndex = event.getFromIndex();
        int postsDelta = 0;
        if (fromIndex >= 0) {
            if (lastFromIndex == INDEX_UNSET) {
                // First event only seeds the baseline — no movement to count yet.
                lastFromIndex = fromIndex;
            } else {
                // Absolute movement: scrolling up or down both "pass" posts.
                postsDelta = Math.abs(fromIndex - lastFromIndex);
                lastFromIndex = fromIndex;
            }
        }

        // --- Pixel distance: absolute vertical scroll delta (API 28+) ---
        long pixelDelta = -1L; // -1 = unavailable on this API level
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pixelDelta = Math.abs((long) event.getScrollDeltaY());
        }

        if (postsDelta > 0) {
            postsPassed += postsDelta;
            hasData = true;
        }
        if (pixelDelta > 0) {
            totalPixels += pixelDelta;
            hasData = true;
        }

        // One line per scroll event. Incremental + running totals.
        Log.i(TAG, "[SCROLL]"
                + " +posts=" + postsDelta
                + " +px=" + (pixelDelta >= 0 ? String.valueOf(pixelDelta) : "n/a")
                + " | totalPosts=" + postsPassed
                + " totalPx=" + totalPixels
                + " events=" + scrollEvents);
    }

    /**
     * Resets all session state to zero. Emits a single [SESSION] summary line
     * first (only when there is something to report), so the caller gets a clean
     * end-of-session total whenever the user leaves the home feed / Instagram.
     *
     * @param reason short context for the log (e.g. "app-switch", "entered-reels")
     */
    public void reset(String reason) {
        if (hasData) {
            Log.i(TAG, "[SESSION] reason=" + reason
                    + " posts=" + postsPassed
                    + " pixels=" + totalPixels
                    + " events=" + scrollEvents);
        }
        postsPassed = 0;
        totalPixels = 0L;
        scrollEvents = 0;
        lastFromIndex = INDEX_UNSET;
        hasData = false;
    }

    public int getPostsPassed() {
        return postsPassed;
    }

    public long getTotalPixels() {
        return totalPixels;
    }
}
