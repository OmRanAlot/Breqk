package com.breqk.reels;

import android.content.Context;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * FullScreenCheck
 * ----------------
 * Static utility that determines whether an AccessibilityNodeInfo represents a
 * full-screen Reels or Shorts viewer.
 *
 * This replaces two near-identical private methods that existed independently in:
 *   - ReelsInterventionService.java (isFullScreenReelsViewPager)
 *   - ContentFilter.java (isFullScreenNode)
 *
 * Both methods shared the same logic and the same thresholds (which were also
 * duplicated as constants). They are now unified here.
 *
 * Four signals must ALL pass:
 *   1. isVisibleToUser()    — back-stack nodes are marked invisible
 *   2. Width  ≥ MIN_WIDTH_RATIO  (90%)  — home-feed reel cards are far narrower
 *   3. Height ≥ MIN_HEIGHT_RATIO (70%)  — allows for status/nav bars
 *   4. top    ≤ MAX_TOP_OFFSET_PX (200) — player starts at screen top
 *
 * Thresholds are defined in {@link ShortFormIds} and documented there.
 *
 * Logging tag: caller-provided TAG (passed in to keep log lines attributed to the
 * calling class, not this utility).
 *
 * Usage:
 *   if (FullScreenCheck.isFullScreen(node, context)) { ... }
 */
public final class FullScreenCheck {

    // Private constructor — all methods are static
    private FullScreenCheck() {}

    /**
     * Returns true if {@code node} is visible to the user and covers at least
     * {@link ShortFormIds#MIN_WIDTH_RATIO} of screen width,
     * {@link ShortFormIds#MIN_HEIGHT_RATIO} of screen height, and has its top
     * edge within {@link ShortFormIds#MAX_TOP_OFFSET_PX} pixels from the top
     * of the screen.
     *
     * All four signals must pass simultaneously. Any failure → false.
     *
     * @param node    The node to evaluate. Null → false.
     * @param context Android context used to retrieve {@link DisplayMetrics}.
     * @param tag     Log tag of the calling class — log lines are attributed to it.
     * @return true only when all four signals confirm a full-screen short-form viewer.
     */
    public static boolean isFullScreen(AccessibilityNodeInfo node, Context context, String tag) {
        if (node == null) {
            Log.d(tag, "[FULLSCREEN_CHECK] node null → false");
            return false;
        }

        // Signal 1: Visibility — back-stack nodes are not visible to the user.
        // isVisibleToUser() is the cheapest check; do it first to short-circuit early.
        if (!node.isVisibleToUser()) {
            Log.d(tag, "[FULLSCREEN_CHECK] not visible to user (back stack) → false");
            return false;
        }

        // Signals 2, 3, 4: Screen-coverage bounds
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int screenW = metrics.widthPixels;
        int screenH = metrics.heightPixels;

        // Guard: if screen dimensions are unavailable, fail safe
        if (screenW <= 0 || screenH <= 0) {
            Log.w(tag, "[FULLSCREEN_CHECK] screen dimensions unavailable (w=" + screenW
                    + " h=" + screenH + ") → false");
            return false;
        }

        float wRatio = (float) bounds.width()  / screenW;
        float hRatio = (float) bounds.height() / screenH;

        boolean fullScreen = wRatio  >= ShortFormIds.MIN_WIDTH_RATIO
                          && hRatio  >= ShortFormIds.MIN_HEIGHT_RATIO
                          && bounds.top <= ShortFormIds.MAX_TOP_OFFSET_PX;

        Log.d(tag, "[FULLSCREEN_CHECK]"
                + " visible=true"
                + " wRatio="  + String.format("%.2f", wRatio)  + "/" + ShortFormIds.MIN_WIDTH_RATIO
                + " hRatio="  + String.format("%.2f", hRatio)  + "/" + ShortFormIds.MIN_HEIGHT_RATIO
                + " top="     + bounds.top + "/" + ShortFormIds.MAX_TOP_OFFSET_PX
                + " → " + fullScreen);

        return fullScreen;
    }
}
