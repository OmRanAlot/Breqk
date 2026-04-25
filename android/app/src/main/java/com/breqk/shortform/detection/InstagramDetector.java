package com.breqk.shortform.detection;

import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.breqk.shortform.FullScreenCheck;
import com.breqk.shortform.platform.instagram.InstagramViewIds;

import java.util.List;

/**
 * InstagramDetector
 * ------------------
 * Detects whether the user is currently viewing Instagram Reels in full-screen mode.
 *
 * Extracted from ReelsInterventionService.isReelsLayout() (previously L1154â€“L1201).
 * Detection logic is unchanged â€” the same view IDs and full-screen checks apply.
 *
 * Detection strategy:
 *   Iterates InstagramViewIds.REELS_IDS and checks each found node via
 *   FullScreenCheck.isFullScreen(). Returns on the first confirmed full-screen node.
 *
 * What qualifies as "Reels":
 *   - Full-screen vertical video playing
 *   - clips_viewer_view_pager (primary ID) or clips_viewer_pager (alternate ID)
 *   - View must pass full-screen bounds check (â‰¥90% width, â‰¥70% height, top â‰¤200px)
 *
 * What does NOT qualify (must return false):
 *   - Home feed with embedded reel cards (width < 90% OR top > 200px)
 *   - Back-stack nodes (not visible to user)
 *   - Explore, DMs, Stories, Profiles, Search
 *
 * Log filter:
 *   adb logcat -s REELS_WATCH | findstr "REELS"
 *   adb logcat -s REELS_WATCH | findstr "TIER"
 */
public class InstagramDetector implements ShortFormDetector {

    /** Log tag inherited from the service â€” all logs appear under REELS_WATCH. */
    private final String tag;

    /** Android context for FullScreenCheck.isFullScreen DisplayMetrics lookup. */
    private final Context context;

    /**
     * @param context Android context (the AccessibilityService)
     * @param tag     Log tag to use â€” should be "REELS_WATCH" in production
     */
    public InstagramDetector(Context context, String tag) {
        this.context = context;
        this.tag     = tag;
    }

    /**
     * Inspects the Instagram accessibility tree and returns a DetectResult.
     *
     * Iterates InstagramViewIds.REELS_IDS (primary + alternative view IDs).
     * For each found node, calls FullScreenCheck.isFullScreen() to confirm the
     * node is actually the full-screen Reels viewer (not a back-stack or embedded node).
     *
     * @param root Root AccessibilityNodeInfo of the Instagram window. May be null.
     * @return DetectResult with inShortForm=true and tier=reels ID on match;
     *         DetectResult.notDetected() if no full-screen Reels node is found.
     */
    @Override
    public DetectResult detect(AccessibilityNodeInfo root) {
        if (root == null) {
            Log.d(tag, "[REELS] InstagramDetector: root null â†’ false");
            return DetectResult.notDetected();
        }

        // Iterate all known Instagram Reels IDs.
        // InstagramViewIds.REELS_IDS is the single source of truth.
        for (String reelsId : InstagramViewIds.REELS_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(reelsId);
            if (nodes != null && !nodes.isEmpty()) {
                Log.d(tag, "[REELS] InstagramDetector: found " + nodes.size()
                        + " node(s) for " + reelsId + " â€” checking full-screen");
                for (AccessibilityNodeInfo n : nodes) {
                    if (FullScreenCheck.isFullScreen(n, context, tag)) {
                        recycleAll(nodes);
                        Log.d(tag, "[REELS] InstagramDetector: YES â€” " + reelsId + " is full-screen");
                        return new DetectResult(true, reelsId);
                    }
                }
                recycleAll(nodes);
                Log.d(tag, "[REELS] InstagramDetector: " + reelsId
                        + " found but none passed full-screen check");
            }
        }

        Log.d(tag, "[REELS] InstagramDetector: NO â€” no qualifying Reels view found");
        return DetectResult.notDetected();
    }

    /**
     * Safely recycles all nodes in a list.
     * Always call after findAccessibilityNodeInfosByViewId() to prevent memory leaks.
     */
    private static void recycleAll(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo n : nodes) {
            if (n != null) {
                try { n.recycle(); } catch (Exception ignored) {}
            }
        }
    }
}
