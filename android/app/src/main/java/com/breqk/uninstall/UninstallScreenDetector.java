package com.breqk.uninstall;

import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Detects when the user is on the Android Settings App Info / uninstall screen for Breqk.
 *
 * Detection requires ALL three conditions:
 *   1. "breqk" appears in the node tree (case-insensitive)
 *   2. "uninstall" appears in the node tree (case-insensitive)
 *   3. At least one App Info marker is present ("force stop", "storage", "notifications",
 *      "app info", "open by default") — rules out Settings search results where "Breqk"
 *      appears as a list item but the Uninstall button isn't actually on screen.
 *
 * BFS is bounded to MAX_NODES to avoid OOM on dense OEM Settings trees.
 *
 * Log filter: adb logcat -s REELS_WATCH | findstr "UNINSTALL_WATCH"
 */
public class UninstallScreenDetector {

    private static final String TAG = "REELS_WATCH";

    // BFS cap — prevents OOM on dense OEM Settings accessibility trees
    private static final int MAX_NODES = 500;

    // App Info markers: at least one must be present to confirm we're on the App Info page.
    // The uninstall-confirm dialog itself may not contain all of these, but the App Info
    // page that launches it always does. OEM translations may vary — expand this list if
    // false negatives appear on Samsung/Xiaomi. All lowercased for case-insensitive match.
    private static final String[] APP_INFO_MARKERS = {
            "force stop",
            "storage",
            "notifications",
            "app info",
            "open by default",
            "permissions",
    };

    /**
     * Returns true if the accessibility tree rooted at {@code root} looks like the
     * Breqk App Info / uninstall screen in Android Settings.
     *
     * Safe to call with a null or recycled root (returns false).
     */
    public static boolean isOnBreqkUninstallScreen(AccessibilityNodeInfo root) {
        if (root == null) return false;

        boolean hasBreqk = false;
        boolean hasUninstall = false;
        boolean hasAppInfoMarker = false;

        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            visited++;

            String text = collectText(node);
            if (!text.isEmpty()) {
                if (!hasBreqk && text.contains("breqk")) {
                    hasBreqk = true;
                    Log.d(TAG, "[UNINSTALL_WATCH] Found 'breqk' in node text='" + text + "'");
                }
                if (!hasUninstall && text.contains("uninstall")) {
                    hasUninstall = true;
                    Log.d(TAG, "[UNINSTALL_WATCH] Found 'uninstall' in node text='" + text + "'");
                }
                if (!hasAppInfoMarker) {
                    for (String marker : APP_INFO_MARKERS) {
                        if (text.contains(marker)) {
                            hasAppInfoMarker = true;
                            Log.d(TAG, "[UNINSTALL_WATCH] Found App Info marker '" + marker + "' in text='" + text + "'");
                            break;
                        }
                    }
                }
            }

            // Short-circuit once all three signals are found
            if (hasBreqk && hasUninstall && hasAppInfoMarker) break;

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }

        boolean detected = hasBreqk && hasUninstall && hasAppInfoMarker;
        Log.d(TAG, "[UNINSTALL_WATCH] scan complete visited=" + visited
                + " hasBreqk=" + hasBreqk
                + " hasUninstall=" + hasUninstall
                + " hasAppInfoMarker=" + hasAppInfoMarker
                + " -> detected=" + detected);
        return detected;
    }

    /** Collects text and content description from a node into a single lowercased string. */
    private static String collectText(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        CharSequence text = node.getText();
        if (text != null) sb.append(text);
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(desc);
        }
        return sb.toString().toLowerCase();
    }
}
