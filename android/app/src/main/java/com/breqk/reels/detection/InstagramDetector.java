package com.breqk.reels.detection;

import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.breqk.reels.FullScreenCheck;
import com.breqk.reels.ShortFormIds;

import java.util.List;

/**
 * InstagramDetector
 * ------------------
 * Detects whether the user is currently viewing Instagram Reels in full-screen mode.
 *
 * Extracted from ReelsInterventionService.isReelsLayout() (previously L1154–L1201).
 * Detection logic is unchanged — the same view IDs and full-screen checks apply.
 *
 * Detection strategy:
 *   Iterates ShortFormIds.INSTAGRAM_REELS_IDS and checks each found node via
 *   FullScreenCheck.isFullScreen(). Returns on the first confirmed full-screen node.
 *
 * What qualifies as "Reels":
 *   - Full-screen vertical video playing
 *   - clips_viewer_view_pager (primary ID) or clips_viewer_pager (alternate ID)
 *   - View must pass full-screen bounds check (≥90% width, ≥70% height, top ≤200px)
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

    /** Log tag inherited from the service — all logs appear under REELS_WATCH. */
    private final String tag;

    /** Android context for FullScreenCheck.isFullScreen DisplayMetrics lookup. */
    private final Context context;

    /**
     * @param context Android context (the AccessibilityService)
     * @param tag     Log tag to use — should be "REELS_WATCH" in production
     */
    public InstagramDetector(Context context, String tag) {
        this.context = context;
        this.tag     = tag;
    }

    /**
     * Inspects the Instagram accessibility tree and returns a DetectResult.
     *
     * Iterates ShortFormIds.INSTAGRAM_REELS_IDS (primary + alternative view IDs).
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
            Log.d(tag, "[REELS] InstagramDetector: root null → false");
            return DetectResult.notDetected();
        }

        // Iterate all known Instagram Reels IDs.
        // ShortFormIds.INSTAGRAM_REELS_IDS is the single source of truth.
        for (String reelsId : ShortFormIds.INSTAGRAM_REELS_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(reelsId);
            if (nodes != null && !nodes.isEmpty()) {
                Log.d(tag, "[REELS] InstagramDetector: found " + nodes.size()
                        + " node(s) for " + reelsId + " — checking full-screen");
                for (AccessibilityNodeInfo n : nodes) {
                    if (FullScreenCheck.isFullScreen(n, context, tag)) {
                        recycleAll(nodes);
                        Log.d(tag, "[REELS] InstagramDetector: YES — " + reelsId + " is full-screen");
                        return new DetectResult(true, reelsId);
                    }
                }
                recycleAll(nodes);
                Log.d(tag, "[REELS] InstagramDetector: " + reelsId
                        + " found but none passed full-screen check");
            }
        }

        Log.d(tag, "[REELS] InstagramDetector: NO — no qualifying Reels view found");
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
