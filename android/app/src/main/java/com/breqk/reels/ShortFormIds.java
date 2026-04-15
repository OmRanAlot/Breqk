package com.breqk.reels;

/**
 * ShortFormIds
 * -------------
 * Single source of truth for all short-form content view IDs and detection thresholds.
 *
 * Previously, YOUTUBE_SHORTS_VIEW_IDS was duplicated in both ReelsInterventionService.java
 * and ContentFilter.java (Bug B1). This class consolidates all view IDs and layout thresholds
 * so that a YouTube or Instagram app update requires only one edit here.
 *
 * Usage:
 *   - Import this class in any file that needs to detect Reels or Shorts.
 *   - Reference constants directly: ShortFormIds.YOUTUBE_SHORTS_VIEW_IDS
 *
 * How to discover new view IDs after a YouTube/Instagram update:
 *   YouTube:   adb logcat -s REELS_WATCH | findstr "YT_TREE_DUMP"
 *   Instagram: Android Studio Layout Inspector → attach to com.instagram.android
 *
 * Logging tag (for callers that reference these IDs):
 *   YouTube:   adb logcat -s REELS_WATCH | findstr "TIER"
 *   Instagram: adb logcat -s REELS_WATCH | findstr "REELS"
 */
public final class ShortFormIds {

    // =========================================================================
    // Instagram Reels view IDs
    // =========================================================================

    /**
     * Instagram Reels ViewPager view IDs.
     *
     * clips_viewer_view_pager — primary ID (current as of 2025).
     * clips_viewer_pager      — alternative ID seen on some Instagram versions.
     *
     * Both must pass the full-screen bounds check ({@link FullScreenCheck#isFullScreen}) to
     * confirm the Reels viewer is actually active and full-screen (not a back-stack node or
     * home-feed embedded reel card).
     *
     * If detection breaks after an Instagram update, use Android Studio Layout Inspector
     * (View → Tool Windows → Layout Inspector → attach to com.instagram.android) to find
     * the new ViewPager ID and add it here.
     */
    public static final String[] INSTAGRAM_REELS_IDS = {
            "com.instagram.android:id/clips_viewer_view_pager",  // primary
            "com.instagram.android:id/clips_viewer_pager",       // alternative version
    };

    // =========================================================================
    // YouTube Shorts view IDs (primary detection — Tier 1)
    // =========================================================================

    /**
     * Known YouTube Shorts container view IDs (Tier 1 primary detection).
     *
     * Any of these matching + passing isFullScreen() = confirmed Shorts.
     * YouTube updates these IDs regularly — see YT_TREE_DUMP log filter to
     * discover replacements after a YouTube update.
     *
     * To discover new IDs: adb logcat -s REELS_WATCH | findstr "YT_TREE_DUMP"
     *
     * Previously duplicated in:
     *   - ReelsInterventionService.java (L131)
     *   - ContentFilter.java (L86)
     * Both files now reference this single array. Bug B1 is closed.
     */
    public static final String[] YOUTUBE_SHORTS_VIEW_IDS = {
            "com.google.android.youtube:id/reel_player_page_container",
            "com.google.android.youtube:id/shorts_container",
            "com.google.android.youtube:id/shorts_shelf_container",
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_watch_player",
            "com.google.android.youtube:id/shorts_player_container",
            "com.google.android.youtube:id/shorts_pager",
    };

    // =========================================================================
    // YouTube Shorts secondary signal IDs (structural heuristic — Tier 2)
    // =========================================================================

    /**
     * YouTube Shorts secondary signal IDs — present in the Shorts UI but are not
     * container IDs. Used in Tier 2 heuristic detection when primary container IDs fail.
     *
     * NOTE: "like_button" is intentionally excluded — it is a generic YouTube ID that
     * appears on home page video thumbnails, causing false positives. Only
     * Shorts-specific IDs belong here.
     */
    public static final String[] YOUTUBE_SHORTS_SECONDARY_IDS = {
            "com.google.android.youtube:id/reel_like_button",
            "com.google.android.youtube:id/shorts_like_button",
            "com.google.android.youtube:id/reel_comment_button",
            "com.google.android.youtube:id/reel_time_bar",
    };

    /**
     * YouTube regular video seekbar ID.
     *
     * ABSENCE of this ID helps differentiate Shorts from regular videos:
     * Shorts have no seekbar; regular videos do. Combined with secondary signal IDs
     * above, this forms the Tier 2 structural heuristic.
     */
    public static final String YOUTUBE_SEEKBAR_ID =
            "com.google.android.youtube:id/youtube_controls_seekbar";

    /**
     * YouTube Activity/Fragment class names that are unique to the Shorts player.
     *
     * When TYPE_WINDOW_STATE_CHANGED fires from YouTube, event.getClassName()
     * returns the class name of the newly-active Activity or Fragment. If it
     * matches an entry here, we confirm Shorts immediately without any tree
     * traversal (Tier 0 — O(1) detection).
     *
     * HOW TO DISCOVER: These class names are logged as [SHORTS_CLASS] whenever
     * YouTube fires a STATE_CHANGED event. Run:
     *   adb logcat -s REELS_WATCH | findstr "SHORTS_CLASS"
     * while navigating to the Shorts tab, then add the exact class name below.
     *
     * Currently empty — will be populated once class names are observed in logs.
     */
    public static final String[] YOUTUBE_SHORTS_CLASS_NAMES = {
            // Example (add real names after observing [SHORTS_CLASS] logs):
            // "com.google.android.apps.youtube.app.shorts.ShortsActivity",
    };

    // =========================================================================
    // Full-screen detection thresholds (shared between all detectors)
    // =========================================================================

    /**
     * Minimum fraction of screen width the Reels/Shorts viewer must cover.
     *
     * Home feed embedded reel cards are far narrower than 90%.
     * Range: 0.0–1.0. Default: 0.90 (90%).
     *
     * Previously duplicated in:
     *   - ReelsInterventionService.java (L199)
     *   - ContentFilter.java (L101)
     */
    public static final float MIN_WIDTH_RATIO = 0.90f;

    /**
     * Minimum fraction of screen height the Reels/Shorts viewer must cover.
     *
     * 70% (not 90%) accounts for the space consumed by the status bar and
     * navigation bar.
     *
     * Previously duplicated in:
     *   - ReelsInterventionService.java (L205)
     *   - ContentFilter.java (L106)
     */
    public static final float MIN_HEIGHT_RATIO = 0.70f;

    /**
     * Maximum Y pixel offset (from top of screen) for the viewer's top edge.
     *
     * The full-screen Reels/Shorts player starts at or near 0;
     * embedded previews start much further down the page.
     *
     * Previously duplicated in:
     *   - ReelsInterventionService.java (L211)
     *   - ContentFilter.java (L112)
     */
    public static final int MAX_TOP_OFFSET_PX = 200;

    // Prevent instantiation — this is a constants-only class
    private ShortFormIds() {}
}
