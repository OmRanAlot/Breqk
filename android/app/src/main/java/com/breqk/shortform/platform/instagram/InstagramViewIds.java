package com.breqk.shortform.platform.instagram;

/**
 * View-ID constants for Instagram short-form UI detection.
 *
 * Extracted from ShortFormIds (Step 6a of codebase-reorganization).
 * This is the single source of truth for all Instagram view IDs.
 *
 * How to discover new IDs after an Instagram update:
 *   Android Studio Layout Inspector → attach to com.instagram.android
 *   or: adb logcat -s REELS_WATCH | findstr "HOME_FEED_SOURCE"
 */
public final class InstagramViewIds {

    // =========================================================================
    // Home feed
    // =========================================================================

    /**
     * RecyclerView IDs for the Instagram home feed (the main scrollable list).
     * Used by HomeFeedCounter to detect post swipes — distinct from the full-screen
     * Reels viewer (REELS_IDS below).
     */
    public static final String[] HOME_FEED_IDS = {
            "com.instagram.android:id/feed_main_recycler_view",   // primary home feed RecyclerView
            "com.instagram.android:id/list",                       // fallback (older Instagram versions)
    };

    // =========================================================================
    // Reels viewer
    // =========================================================================

    /**
     * Instagram Reels ViewPager view IDs.
     *
     * clips_viewer_view_pager — primary ID (current as of 2025).
     * clips_viewer_pager      — alternative ID seen on some Instagram versions.
     *
     * Both must pass FullScreenCheck.isFullScreen() to confirm the Reels viewer is
     * actually active and full-screen (not a back-stack node or home-feed embed).
     */
    public static final String[] REELS_IDS = {
            "com.instagram.android:id/clips_viewer_view_pager",  // primary
            "com.instagram.android:id/clips_viewer_pager",       // alternative version
    };

    private InstagramViewIds() {}
}
