package com.breqk.shortform.platform.youtube;

/**
 * View-ID constants for YouTube Shorts detection.
 *
 * Extracted from ShortFormIds (Step 6a of codebase-reorganization).
 * This is the single source of truth for all YouTube view IDs.
 *
 * How to discover new IDs after a YouTube update:
 *   adb logcat -s REELS_WATCH | findstr "YT_TREE_DUMP"
 */
public final class YouTubeViewIds {

    // =========================================================================
    // Shorts primary container IDs (Tier 1 detection)
    // =========================================================================

    /**
     * Known YouTube Shorts container view IDs (Tier 1 primary detection).
     * Any of these matching + passing FullScreenCheck.isFullScreen() = confirmed Shorts.
     * YouTube updates these IDs regularly — see YT_TREE_DUMP log filter.
     */
    public static final String[] SHORTS_VIEW_IDS = {
            "com.google.android.youtube:id/reel_player_page_container",
            "com.google.android.youtube:id/shorts_container",
            "com.google.android.youtube:id/shorts_shelf_container",
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_watch_player",
            "com.google.android.youtube:id/shorts_player_container",
            "com.google.android.youtube:id/shorts_pager",
    };

    // =========================================================================
    // Shorts secondary signal IDs (Tier 2 structural heuristic)
    // =========================================================================

    /**
     * YouTube Shorts secondary signal IDs — present in the Shorts UI but not containers.
     * Used in Tier 2 heuristic detection when primary container IDs fail.
     *
     * NOTE: "like_button" is excluded — it appears on home page thumbnails too.
     */
    public static final String[] SHORTS_SECONDARY_IDS = {
            "com.google.android.youtube:id/reel_like_button",
            "com.google.android.youtube:id/shorts_like_button",
            "com.google.android.youtube:id/reel_comment_button",
            "com.google.android.youtube:id/reel_time_bar",
    };

    // =========================================================================
    // Regular video seekbar (absence signals Shorts in Tier 2)
    // =========================================================================

    /**
     * YouTube regular video seekbar ID.
     * ABSENCE of this ID helps differentiate Shorts from regular videos:
     * Shorts have no seekbar; regular videos do.
     */
    public static final String SEEKBAR_ID =
            "com.google.android.youtube:id/youtube_controls_seekbar";

    // =========================================================================
    // Shorts class names (Tier 0 — O(1) detection on STATE_CHANGED)
    // =========================================================================

    /**
     * YouTube Activity/Fragment class names unique to the Shorts player (Tier 0).
     * Currently empty — populated once class names are observed in [SHORTS_CLASS] logs.
     * Run: adb logcat -s REELS_WATCH | findstr "SHORTS_CLASS"
     */
    public static final String[] SHORTS_CLASS_NAMES = {
            // Example (add real names after observing [SHORTS_CLASS] logs):
            // "com.google.android.apps.youtube.app.shorts.ShortsActivity",
    };

    private YouTubeViewIds() {}
}
