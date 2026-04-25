package com.breqk.shortform.platform.tiktok;

/**
 * TikTokViewIds
 * --------------
 * Accessibility view ID constants for TikTok short-form video detection.
 *
 * Populate after running:
 *   adb logcat -s REELS_WATCH | findstr "TIKTOK_TREE_DUMP"
 * while scrolling TikTok to discover the current container view IDs.
 */
public final class TikTokViewIds {

    /** Primary view IDs that confirm the user is in the TikTok For You feed. */
    public static final String[] SHORT_FORM_IDS = {
        // TODO: populate after adb tree dump on TikTok
    };

    private TikTokViewIds() {}
}
