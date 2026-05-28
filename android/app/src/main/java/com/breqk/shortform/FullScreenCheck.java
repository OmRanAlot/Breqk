package com.breqk.shortform;

import android.content.Context;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/**
 * FullScreenCheck
 * ----------------
 * Static utility that determines whether an AccessibilityNodeInfo represents a
 * full-screen Reels or Shorts viewer.
 *
 * Four signals must ALL pass:
 *   1. isVisibleToUser()          — back-stack nodes are marked invisible
 *   2. Width  >= MIN_WIDTH_RATIO  (90%) — home-feed reel cards are far narrower
 *   3. Height >= MIN_HEIGHT_RATIO (70%) — allows for status/nav bars
 *   4. top    <= MAX_TOP_OFFSET_PX (200px) — player starts at screen top
 *
 * Thresholds are defined here and are the single source of truth across all detectors.
 *
 * Logging tag: caller-provided TAG (kept attributed to the calling class).
 */
public final class FullScreenCheck {

    // =========================================================================
    // Full-screen detection thresholds (single source of truth)
    // =========================================================================

    /** Minimum fraction of screen width the viewer must cover (90%). */
    public static final float MIN_WIDTH_RATIO = 0.90f;

    /** Minimum fraction of screen height the viewer must cover (70% — accounts for bars). */
    public static final float MIN_HEIGHT_RATIO = 0.70f;

    /** Maximum Y pixel offset from the top of the screen for the viewer's top edge. */
    public static final int MAX_TOP_OFFSET_PX = 200;

    private FullScreenCheck() {}

    /**
     * Returns true if {@code node} belongs to the active, foreground TYPE_APPLICATION window.
     *
     * YouTube keeps a resident "background Shorts player" alive as a floating overlay window
     * (TYPE_APPLICATION_OVERLAY) after the user exits a short. Nodes inside that overlay window
     * report isVisibleToUser()=true and full-screen bounds, causing false-positive Shorts detection
     * on scrolls/heartbeats while the user is on the home feed or a regular video.
     *
     * This check rejects nodes whose window is:
     *   - null (cannot verify — fail-open: return true to avoid breaking detection)
     *   - type != TYPE_APPLICATION (e.g. overlay, system, accessibility)
     *   - isActive()=false (background/not-currently-interacted window)
     *
     * Called after isFullScreen() in YouTubeDetector Tier 1 and Tier 2 only.
     * Not applied to Instagram (different false-positive profile).
     *
     * Requires FLAG_RETRIEVE_INTERACTIVE_WINDOWS in AccessibilityServiceInfo (already set).
     *
     * Log filter: adb logcat -s REELS_WATCH | findstr "BG_PLAYER"
     *
     * @param node The node whose window membership to check. Null -> false.
     * @param tag  Log tag of the calling class.
     * @return true if the node is in the active foreground application window (or window info unavailable).
     */
    public static boolean isInActiveForegroundWindow(AccessibilityNodeInfo node, String tag) {
        if (node == null) return false;

        AccessibilityWindowInfo window = node.getWindow();
        if (window == null) {
            Log.d(tag, "[BG_PLAYER] isInActiveForegroundWindow: window=null -- cannot verify, allowing");
            return true;
        }

        int type = window.getType();
        boolean active = window.isActive();
        window.recycle();

        if (type != AccessibilityWindowInfo.TYPE_APPLICATION) {
            Log.d(tag, "[BG_PLAYER] isInActiveForegroundWindow: type=" + type
                    + " (not TYPE_APPLICATION=" + AccessibilityWindowInfo.TYPE_APPLICATION
                    + ") -- background overlay, rejecting");
            return false;
        }
        if (!active) {
            Log.d(tag, "[BG_PLAYER] isInActiveForegroundWindow: isActive()=false -- background window, rejecting");
            return false;
        }
        return true;
    }

    /**
     * Returns true if {@code node} is visible to the user and covers at least
     * MIN_WIDTH_RATIO of screen width, MIN_HEIGHT_RATIO of screen height, and has
     * its top edge within MAX_TOP_OFFSET_PX pixels from the top of the screen.
     *
     * All four signals must pass simultaneously. Any failure → false.
     *
     * @param node    The node to evaluate. Null → false.
     * @param context Android context used to retrieve DisplayMetrics.
     * @param tag     Log tag of the calling class — log lines are attributed to it.
     * @return true only when all four signals confirm a full-screen short-form viewer.
     */
    public static boolean isFullScreen(AccessibilityNodeInfo node, Context context, String tag) {
        if (node == null) {
            Log.d(tag, "[FULLSCREEN_CHECK] node null → false");
            return false;
        }

        // Signal 1: Visibility — back-stack nodes are not visible to the user.
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

        if (screenW <= 0 || screenH <= 0) {
            Log.w(tag, "[FULLSCREEN_CHECK] screen dimensions unavailable (w=" + screenW
                    + " h=" + screenH + ") → false");
            return false;
        }

        float wRatio = (float) bounds.width()  / screenW;
        float hRatio = (float) bounds.height() / screenH;

        boolean fullScreen = wRatio  >= MIN_WIDTH_RATIO
                          && hRatio  >= MIN_HEIGHT_RATIO
                          && bounds.top <= MAX_TOP_OFFSET_PX;

        Log.d(tag, "[FULLSCREEN_CHECK]"
                + " visible=true"
                + " wRatio="  + String.format("%.2f", wRatio)  + "/" + MIN_WIDTH_RATIO
                + " hRatio="  + String.format("%.2f", hRatio)  + "/" + MIN_HEIGHT_RATIO
                + " top="     + bounds.top + "/" + MAX_TOP_OFFSET_PX
                + " → " + fullScreen);

        return fullScreen;
    }
}
