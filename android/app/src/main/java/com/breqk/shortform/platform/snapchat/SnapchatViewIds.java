package com.breqk.shortform.platform.snapchat;

/**
 * SnapchatViewIds
 * ----------------
 * Accessibility view ID constants for Snapchat Spotlight detection.
 *
 * Populate after running:
 *   adb logcat -s REELS_WATCH | findstr "SNAP_TREE_DUMP"
 * while scrolling Snapchat Spotlight to discover the current container view IDs.
 */
public final class SnapchatViewIds {

    /** Primary view IDs that confirm the user is in the Snapchat Spotlight feed. */
    public static final String[] SPOTLIGHT_IDS = {
        // TODO: populate after adb tree dump on Snapchat
    };

    private SnapchatViewIds() {}
}
