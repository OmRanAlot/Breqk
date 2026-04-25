package com.breqk.shortform.platform.youtube;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.breqk.shortform.FullScreenCheck;
import com.breqk.shortform.platform.FilterHandler;
import com.breqk.shortform.platform.Platform;

import java.util.List;

/**
 * Event handler for YouTube Shorts short-form detection.
 *
 * Extracted from ContentFilter.handleYouTube() + isYouTubeShorts().
 * Detection logic is unchanged — only the location has moved.
 *
 * Fast path (VIEW_SCROLLED only): checks source view ID against YouTubeViewIds.SHORTS_VIEW_IDS.
 * Slow path: full tree traversal via isYouTubeShorts() (Tier 1 — known container IDs only).
 *
 * Logging tag: CONTENT_FILTER
 */
public final class YouTubeFilterHandler implements FilterHandler {

    private static final String TAG = "CONTENT_FILTER";

    private final Context context;
    private final AccessibilityService service;

    public YouTubeFilterHandler(Context context, AccessibilityService service) {
        this.context = context;
        this.service = service;
    }

    @Override
    public Platform platform() {
        return Platform.YOUTUBE;
    }

    /**
     * Returns true if the event confirms the user is in a YouTube Shorts player.
     *
     * Processes both TYPE_VIEW_SCROLLED and TYPE_WINDOW_CONTENT_CHANGED.
     */
    @Override
    public boolean handle(AccessibilityEvent event, AccessibilityNodeInfo root) {
        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
                && eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return false;
        }

        boolean confirmed = false;

        // Fast path (scroll events only): check source view ID against YouTubeViewIds.SHORTS_VIEW_IDS
        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                String viewId = source.getViewIdResourceName();
                if (viewId != null) {
                    for (String shortsId : YouTubeViewIds.SHORTS_VIEW_IDS) {
                        if (viewId.equals(shortsId) && FullScreenCheck.isFullScreen(source, context, TAG)) {
                            confirmed = true;
                            Log.d(TAG, "[YT] Fast path: matched " + viewId + " (full-screen)");
                            break;
                        }
                    }
                }
                source.recycle();
            }
        }

        // Slow path: tree traversal (CONTENT_CHANGED events + fast-path miss)
        if (!confirmed) {
            confirmed = isYouTubeShorts(root);
            Log.d(TAG, "[YT] Slow path: treeTraversal confirmed=" + confirmed);
        }

        return confirmed;
    }

    /**
     * Returns true if the YouTube accessibility tree contains a Shorts player.
     * Implements Tier 1 only (known container IDs + full-screen bounds check).
     * ContentFilter uses Tier 1 only — over-detection causes unwanted BACK presses.
     */
    private boolean isYouTubeShorts(AccessibilityNodeInfo root) {
        if (root == null) return false;

        for (String viewId : YouTubeViewIds.SHORTS_VIEW_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (FullScreenCheck.isFullScreen(n, context, TAG)) {
                        recycleAll(nodes);
                        Log.d(TAG, "[YT] isYouTubeShorts: " + viewId + " confirmed full-screen → true");
                        return true;
                    }
                }
                recycleAll(nodes);
            }
        }

        return false;
    }

    private static void recycleAll(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo n : nodes) {
            if (n != null) n.recycle();
        }
    }
}
