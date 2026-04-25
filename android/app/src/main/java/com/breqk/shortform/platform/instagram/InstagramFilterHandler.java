package com.breqk.shortform.platform.instagram;

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
 * Event handler for Instagram Reels short-form detection.
 *
 * Extracted from ContentFilter.handleInstagram() + isInstagramReels().
 * Detection logic is unchanged — only the location has moved.
 *
 * Fast path (O(1)): checks scroll event source against InstagramViewIds.REELS_IDS.
 * Slow path (O(tree)): full accessibility tree traversal when fast path misses.
 * Both paths gate through FullScreenCheck.isFullScreen() to prevent false positives.
 *
 * Logging tag: CONTENT_FILTER
 */
public final class InstagramFilterHandler implements FilterHandler {

    private static final String TAG = "CONTENT_FILTER";

    private final Context context;
    private final AccessibilityService service;

    public InstagramFilterHandler(Context context, AccessibilityService service) {
        this.context = context;
        this.service = service;
    }

    @Override
    public Platform platform() {
        return Platform.INSTAGRAM;
    }

    /**
     * Returns true if the event confirms the user is in a full-screen Instagram Reels viewer.
     *
     * Only processes TYPE_VIEW_SCROLLED — TYPE_WINDOW_CONTENT_CHANGED is too noisy on IG.
     */
    @Override
    public boolean handle(AccessibilityEvent event, AccessibilityNodeInfo root) {
        if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_SCROLLED) return false;

        boolean confirmed = false;

        // Fast path: check source view ID against InstagramViewIds.REELS_IDS
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            String viewId = source.getViewIdResourceName();
            if (viewId != null) {
                for (String reelsId : InstagramViewIds.REELS_IDS) {
                    if (reelsId.equals(viewId)) {
                        confirmed = FullScreenCheck.isFullScreen(source, context, TAG);
                        Log.d(TAG, "[IG] Fast path: viewId=" + viewId + " fullScreen=" + confirmed);
                        break;
                    }
                }
            }
            source.recycle();
        }

        // Slow path: tree traversal (catches child-view scrolls)
        if (!confirmed) {
            confirmed = isInstagramReels(root);
            Log.d(TAG, "[IG] Slow path: treeTraversal confirmed=" + confirmed);
        }

        return confirmed;
    }

    /**
     * Returns true if the accessibility tree contains a full-screen Reels viewer.
     * Iterates InstagramViewIds.REELS_IDS and validates via FullScreenCheck.isFullScreen().
     */
    private boolean isInstagramReels(AccessibilityNodeInfo root) {
        if (root == null) return false;

        for (String reelsId : InstagramViewIds.REELS_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(reelsId);
            if (nodes != null) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (FullScreenCheck.isFullScreen(n, context, TAG)) {
                        recycleAll(nodes);
                        Log.d(TAG, "[IG] isInstagramReels: " + reelsId + " confirmed full-screen → true");
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
