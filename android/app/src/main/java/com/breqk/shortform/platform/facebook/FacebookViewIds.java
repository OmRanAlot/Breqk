package com.breqk.shortform.platform.facebook;

/**
 * FacebookViewIds
 * ----------------
 * Accessibility view ID constants for Facebook Reels detection.
 *
 * Populate after running:
 *   adb logcat -s REELS_WATCH | findstr "FB_TREE_DUMP"
 * while scrolling Facebook Reels to discover the current container view IDs.
 */
public final class FacebookViewIds {

    /** Primary view IDs that confirm the user is in the Facebook Reels feed. */
    public static final String[] REELS_IDS = {
        // TODO: populate after adb tree dump on Facebook
    };

    private FacebookViewIds() {}
}
