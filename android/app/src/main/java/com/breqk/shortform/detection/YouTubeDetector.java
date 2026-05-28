package com.breqk.shortform.detection;

import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.breqk.shortform.FullScreenCheck;
import com.breqk.shortform.FrameworkClassFilter;
import com.breqk.shortform.platform.youtube.YouTubeViewIds;

import java.util.List;

/**
 * YouTubeDetector
 * ----------------
 * Detects whether the user is currently viewing YouTube Shorts in full-screen
 * mode.
 *
 * Extracted from ReelsInterventionService.isShortsLayout() (previously
 * L1224-L1327),
 * hasShortsTextSignal() (L951-L995), and dumpYouTubeTreeIfNeeded()
 * (L1852-L1902).
 * All detection logic is unchanged -- the same tier ladder applies.
 *
 * Detection strategy (4 tiers):
 *
 * Tier 0 -- Class name match (O(1)):
 * Called by the service on TYPE_WINDOW_STATE_CHANGED. Not part of detect() --
 * call checkClassNameTier0() separately before calling detect().
 *
 * Tier 1 -- Known container IDs (YouTubeViewIds.SHORTS_VIEW_IDS):
 * Any matching node that passes isFullScreen() -> confirmed Shorts.
 *
 * Tier 2 -- Secondary signals + no seekbar:
 * YouTubeViewIds.SHORTS_SECONDARY_IDS + absence of YOUTUBE_SEEKBAR_ID.
 * Only confirms if the secondary signal node itself is full-screen.
 *
 * Tier 3 -- Visible text scan (depth 3):
 * Walks the tree looking for “Shorts” in getText()/getContentDescription().
 * Resilient to view ID renames. NOT used in detectStrict() to avoid
 * bottom-nav “Shorts” tab false positives on home page / long-form videos.
 * Guards: seekbar presence aborts scan; bounds check rejects nav-tab nodes.
 *
 * Tier 4 -- Diagnostic tree dump (rate-limited to once per 10s):
 * When all detection tiers fail, dumps all YouTube view IDs to logcat so
 * developers can discover the current IDs after a YouTube update.
 * Filter: adb logcat -s REELS_WATCH | findstr “YT_TREE_DUMP”
 *
 * Log filters:
 * Tier decisions: adb logcat -s REELS_WATCH | findstr “TIER”
 * Strict detect: adb logcat -s REELS_WATCH | findstr “STRICT_DETECT”
 * Shorts active: adb logcat -s REELS_WATCH | findstr “SHORTS_ACTIVE”
 * Shorts class: adb logcat -s REELS_WATCH | findstr “SHORTS_CLASS”
 * Text signal: adb logcat -s REELS_WATCH | findstr “SHORTS_TEXT”
 * Tree dump: adb logcat -s REELS_WATCH | findstr “YT_TREE_DUMP”
 */
public class YouTubeDetector implements ShortFormDetector {

    /** Log tag inherited from the service â€” all logs appear under REELS_WATCH. */
    private final String tag;

    /** Android context for FullScreenCheck.isFullScreen DisplayMetrics lookup. */
    private final Context context;

    /**
     * Rate-limit state for the Tier 4 diagnostic tree dump.
     * Only one dump per YT_TREE_DUMP_INTERVAL_MS (10s) to prevent log spam.
     */
    private long lastYtTreeDump = 0;
    private static final long YT_TREE_DUMP_INTERVAL_MS = 10_000;

    /**
     * @param context Android context (the AccessibilityService)
     * @param tag     Log tag to use â€” should be "REELS_WATCH" in production
     */
    public YouTubeDetector(Context context, String tag) {
        this.context = context;
        this.tag = tag;
    }

    /**
     * Checks whether a YouTube TYPE_WINDOW_STATE_CHANGED class name is a known
     * Shorts-specific Activity or Fragment class (Tier 0 â€” O(1) detection).
     *
     * Call this on every TYPE_WINDOW_STATE_CHANGED event BEFORE calling detect().
     * If it returns true, the caller can confirm Shorts without any tree traversal.
     *
     * Generic Android framework class names (android.view.ViewGroup, etc.) are
     * filtered out â€” they indicate YouTube's floating overlay, not real
     * navigation.
     *
     * Filter: adb logcat -s REELS_WATCH | findstr "SHORTS_CLASS"
     *
     * @param eventClass event.getClassName() from the accessibility event
     * @return true if the class is a known Shorts-specific class
     */
    public boolean checkClassNameTier0(String eventClass) {
        if (eventClass == null || eventClass.isEmpty())
            return false;

        Log.d(tag, "[SHORTS_CLASS] YouTube STATE_CHANGED className=" + eventClass);

        // Generic Android framework class = YouTube floating overlay, not real
        // navigation.
        // Skip tree traversal entirely â€” getRootInActiveWindow() would return the
        // overlay root.
        if (FrameworkClassFilter.isFrameworkClass(eventClass)) {
            Log.d(tag, "[SHORTS_CLASS] Skipping -- generic class '" + eventClass
                    + "' indicates floating overlay (not real navigation)");
            return false;
        }

        // Check against known Shorts-specific class names
        for (String knownClass : YouTubeViewIds.SHORTS_CLASS_NAMES) {
            if (knownClass.equals(eventClass)) {
                Log.d(tag, "[SHORTS_CLASS] TIER0 class name matched " + eventClass
                        + " -> confirmed Shorts");
                return true;
            }
        }

        return false;
    }

    /**
     * Strict variant -- Tier 1 + Tier 2 only. No Tier 3 (text scan), no Tier 4
     * dump.
     *
     * Use when verifying the user is STILL in Shorts (isStillInReels(),
     * STATE_CHANGED
     * exit checks, heartbeat re-verification). Tier 3 is excluded because YouTube’s
     * persistent bottom-nav “Shorts” tab always matches the text scan and causes
     * false
     * positives on the home page and in long-form videos.
     *
     * Filter: adb logcat -s REELS_WATCH | findstr “STRICT_DETECT”
     *
     * @param root Root AccessibilityNodeInfo of the YouTube window. May be null.
     * @return DetectResult with inShortForm=true on Tier 1/2 match; notDetected()
     *         otherwise.
     */
    public DetectResult detectStrict(AccessibilityNodeInfo root) {
        if (root == null) {
            Log.d(tag, "[STRICT_DETECT] root null -> false");
            return DetectResult.notDetected();
        }
        DetectResult result = detectTier1And2(root);
        Log.d(tag, "[STRICT_DETECT] inShortForm=" + result.inShortForm + " tier=" + result.tier);
        return result;
    }

    /**
     * Runs the Tier 1->Tier 2->Tier 3->Tier 4 ladder on the accessibility tree
     * root.
     *
     * Each tier is attempted in order. Returns on the first positive result.
     * If all tiers fail, triggers a rate-limited Tier 4 tree dump and returns
     * DetectResult.notDetected().
     *
     * For STILL-IN checks prefer detectStrict() to avoid Tier 3 bottom-nav false
     * positives.
     *
     * @param root Root AccessibilityNodeInfo of the YouTube window. May be null.
     * @return DetectResult with inShortForm=true and tier name on match;
     *         DetectResult.notDetected() if all tiers fail.
     */
    @Override
    public DetectResult detect(AccessibilityNodeInfo root) {
        if (root == null) {
            Log.d(tag, "[TIER] YouTubeDetector: root null -> false");
            return DetectResult.notDetected();
        }

        DetectResult tier12 = detectTier1And2(root);
        if (tier12.inShortForm)
            return tier12;

        // --- TIER 3: Visible text scan ---
        // Walks the tree (max depth 3) for any visible node whose getText() or
        // getContentDescription() contains “shorts” (case-insensitive).
        // Resilient to view ID renames. Not used in detectStrict() -- the bottom-nav
        // “Shorts” tab always matches and causes false positives when the user is on
        // the home page or watching a long-form video.
        if (hasShortsTextSignal(root)) {
            Log.d(tag, "[TIER] TIER3 text scan matched -> true");
            return new DetectResult(true, "TIER3");
        }

        // --- TIER 4: Diagnostic tree dump (rate-limited) ---
        // When all detection tiers fail, dump the YouTube accessibility tree to logcat
        // so developers can discover the current IDs after a YouTube update.
        dumpYouTubeTreeIfNeeded(root);

        Log.d(tag, "[TIER] All tiers failed -> false");
        return DetectResult.notDetected();
    }

    // =========================================================================
    // Shared helper: Tier 1 + Tier 2 (used by both detect() and detectStrict())
    // =========================================================================

    /**
     * Runs Tier 1 (known container IDs + full-screen check) and Tier 2 (secondary
     * signals + no seekbar) on the given root. Returns on first positive result.
     *
     * @param root Root of the YouTube accessibility tree. Must not be null.
     * @return DetectResult from whichever tier matched, or notDetected().
     */
    private DetectResult detectTier1And2(AccessibilityNodeInfo root) {
        // --- TIER 1: Known container IDs with full-screen bounds check ---
        // YouTubeViewIds.SHORTS_VIEW_IDS is the single source of truth.
        // Also requires isInActiveForegroundWindow() to reject YouTube's resident background
        // Shorts player, which keeps full-screen visible nodes alive in a floating overlay
        // window after the user exits a short.
        for (String viewId : YouTubeViewIds.SHORTS_VIEW_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
            if (nodes != null && !nodes.isEmpty()) {
                Log.d(tag, "[TIER] TIER1 found " + nodes.size()
                        + " node(s) for " + viewId + " -- checking full-screen + foreground window");
                for (AccessibilityNodeInfo n : nodes) {
                    if (FullScreenCheck.isFullScreen(n, context, tag)
                            && FullScreenCheck.isInActiveForegroundWindow(n, tag)) {
                        recycleAll(nodes);
                        Log.d(tag, "[TIER] TIER1 matched " + viewId + " (full-screen + foreground) -> true");
                        return new DetectResult(true, "TIER1");
                    }
                }
                recycleAll(nodes);
                Log.d(tag, "[TIER] TIER1 found " + viewId + " but failed full-screen or foreground check");
            }
        }
        Log.d(tag, "[TIER] TIER1 -- no known container IDs matched full-screen + foreground");

        // --- TIER 2: Secondary signals + no seekbar (structural heuristic) ---
        // Secondary signals (reel_like_button, shorts_like_button, reel_comment_button)
        // are
        // present in Shorts UI. The seekbar is present in regular videos but absent in
        // Shorts.
        // Combining these differentiates Shorts from regular video playback.
        boolean hasSecondarySignal = false;
        String matchedSecondaryId = null;
        boolean secondaryIsFullScreen = false;

        for (String secId : YouTubeViewIds.SHORTS_SECONDARY_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(secId);
            if (nodes != null && !nodes.isEmpty()) {
                hasSecondarySignal = true;
                matchedSecondaryId = secId;
                for (AccessibilityNodeInfo n : nodes) {
                    if (FullScreenCheck.isFullScreen(n, context, tag)
                            && FullScreenCheck.isInActiveForegroundWindow(n, tag)) {
                        secondaryIsFullScreen = true;
                        break;
                    }
                }
                recycleAll(nodes);
                Log.d(tag, "[TIER] TIER2 secondary signal found: " + secId
                        + " fullScreen+foreground=" + secondaryIsFullScreen);
                break;
            }
        }

        if (hasSecondarySignal) {
            // Absence of seekbar differentiates Shorts from regular videos
            List<AccessibilityNodeInfo> seekbar = root.findAccessibilityNodeInfosByViewId(
                    YouTubeViewIds.SEEKBAR_ID);
            boolean hasSeekbar = seekbar != null && !seekbar.isEmpty();
            if (seekbar != null)
                recycleAll(seekbar);

            if (!hasSeekbar) {
                // Only confirm Shorts if the secondary signal itself is full-screen.
                // We intentionally do NOT fall back to findFullScreenScrollableContainer
                // because YouTube’s home feed RecyclerView is also full-screen and
                // scrollable, which caused false positives on the home page.
                if (secondaryIsFullScreen) {
                    Log.d(tag, "[TIER] TIER2 matched (secondary=" + matchedSecondaryId
                            + " is full-screen + no seekbar) -> true");
                    return new DetectResult(true, "TIER2");
                }
                Log.d(tag, "[TIER] TIER2 secondary found + no seekbar, "
                        + "but secondary not full-screen -> false");
            } else {
                Log.d(tag, "[TIER] TIER2 seekbar present -> regular video, not Shorts");
            }
        } else {
            Log.d(tag, "[TIER] TIER2 -- no secondary signals found");
        }

        return DetectResult.notDetected();
    }

    // =========================================================================
    // Tier 3: Shorts text signal
    // =========================================================================

    /**
     * Walks the YouTube accessibility tree (max depth 3) looking for any visible
     * node
     * whose getText() or getContentDescription() contains "shorts"
     * (case-insensitive).
     *
     * Resilient to YouTube view-ID renames: as long as "Shorts" appears somewhere
     * in
     * the visible UI (page heading, content descriptions), detection will succeed
     * even
     * when all view IDs in Tier 1 and Tier 2 have changed.
     *
     * Guards against bottom-nav "Shorts" tab false-positives:
     * 1. Seekbar presence check: long-form videos always expose a seekbar in the
     * accessibility tree; Shorts never do. If a seekbar is found, this is not
     * Shorts -- skip the text scan entirely.
     * 2. Bounds check in scanNodeForShortsText: rejects "Shorts" text nodes that
     * are shorter than 30% of screen height or sit in the bottom 15% of the
     * screen (the nav-bar strip).
     *
     * Performance: early-exit on first match; max depth 3 limits traversal.
     * All node references are recycled before returning.
     *
     * Filter: adb logcat -s REELS_WATCH | findstr "SHORTS_TEXT"
     *
     * @param root Root of the YouTube accessibility tree
     * @return true if "Shorts" text was found in a plausible (non-nav-tab) node
     */
    private boolean hasShortsTextSignal(AccessibilityNodeInfo root) {
        // Seekbar is present in long-form videos but absent in Shorts.
        // If the seekbar is visible the screen is definitely not Shorts.
        List<AccessibilityNodeInfo> seekbar = root.findAccessibilityNodeInfosByViewId(
                YouTubeViewIds.SEEKBAR_ID);
        boolean hasSeekbar = seekbar != null && !seekbar.isEmpty();
        if (seekbar != null)
            recycleAll(seekbar);
        if (hasSeekbar) {
            Log.d(tag, "[SHORTS_TEXT] seekbar present -> long-form video, skipping text scan");
            return false;
        }

        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        return scanNodeForShortsText(root, 0, 3, screenHeight);
    }

    /**
     * Recursive helper for hasShortsTextSignal().
     *
     * Rejects "Shorts" text matches that come from the bottom navigation bar tab:
     * - Node height < 30% of screen height (real Shorts UI fills most of the
     * screen)
     * - Node top > 85% of screen height (bottom 15% is the nav-bar strip)
     *
     * @param node         Current node to inspect
     * @param depth        Current recursion depth
     * @param maxDepth     Maximum depth to traverse
     * @param screenHeight Screen height in pixels (from DisplayMetrics)
     * @return true if "Shorts" text was found in a valid (non-nav-tab) node
     */
    private boolean scanNodeForShortsText(
            AccessibilityNodeInfo node, int depth, int maxDepth, int screenHeight) {
        if (node == null || depth > maxDepth)
            return false;

        if (node.isVisibleToUser()) {
            CharSequence text = node.getText();
            CharSequence contentDesc = node.getContentDescription();

            boolean textMatch = text != null
                    && text.toString().toLowerCase().contains("shorts");
            boolean descMatch = contentDesc != null
                    && contentDesc.toString().toLowerCase().contains("shorts");

            if (textMatch || descMatch) {
                // Reject the bottom-nav tab: it sits in the nav-bar strip and is tiny.
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                boolean isNavTab = bounds.height() < screenHeight * 0.30f
                        || bounds.top > screenHeight * 0.85f;
                if (isNavTab) {
                    Log.d(tag, "[SHORTS_TEXT] rejected nav-tab match: bounds="
                            + bounds.toShortString() + " screenH=" + screenHeight);
                    // Continue scanning siblings/children -- do not return true yet.
                } else {
                    Log.d(tag, "[SHORTS_TEXT] matched"
                            + (textMatch ? " text=\"" + text + "\"" : "")
                            + (descMatch ? " contentDesc=\"" + contentDesc + "\"" : "")
                            + " at depth=" + depth + " bounds=" + bounds.toShortString());
                    return true;
                }
            }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean found = scanNodeForShortsText(child, depth + 1, maxDepth, screenHeight);
                child.recycle();
                if (found)
                    return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Tier 4: Diagnostic tree dump
    // =========================================================================

    /**
     * Logs all view IDs in the top 5 levels of YouTube's accessibility tree.
     *
     * Rate-limited to once every YT_TREE_DUMP_INTERVAL_MS (10s) to prevent log
     * spam.
     * This is the PRIMARY debugging tool for YouTube Shorts detection failures.
     *
     * When YouTube changes their view IDs, a developer runs:
     * adb logcat -s REELS_WATCH | findstr "YT_TREE_DUMP"
     * while scrolling through Shorts to see the current IDs, then updates
     * YouTubeViewIds.SHORTS_VIEW_IDS with the discovered IDs.
     *
     * @param root Root of the YouTube accessibility tree
     */
    public void dumpYouTubeTreeIfNeeded(AccessibilityNodeInfo root) {
        long now = System.currentTimeMillis();
        if (now - lastYtTreeDump < YT_TREE_DUMP_INTERVAL_MS)
            return;
        lastYtTreeDump = now;

        Log.w(tag, "YT_TREE_DUMP === YouTube Shorts detection FAILED â€” dumping tree view IDs ===");
        Log.w(tag, "YT_TREE_DUMP To fix: find the Shorts container ID below and add it to "
                + "YouTubeViewIds.SHORTS_VIEW_IDS in com.breqk.shortform.platform.youtube.YouTubeViewIds");
        dumpNodeChildren(root, 0, 5);
        Log.w(tag, "YT_TREE_DUMP === End dump ===");
    }

    /**
     * Recursively logs view IDs, class names, scrollability, and bounds for
     * all children of a node up to maxDepth levels deep.
     *
     * @param node     Current node to inspect
     * @param depth    Current depth in the tree
     * @param maxDepth Maximum depth to traverse
     */
    private void dumpNodeChildren(AccessibilityNodeInfo node, int depth, int maxDepth) {
        if (node == null || depth > maxDepth)
            return;
        StringBuilder indent = new StringBuilder();
        for (int d = 0; d < depth; d++)
            indent.append("  ");
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                String id = child.getViewIdResourceName();
                String cls = child.getClassName() != null
                        ? child.getClassName().toString()
                        : "null";
                boolean scrollable = child.isScrollable();
                Rect bounds = new Rect();
                child.getBoundsInScreen(bounds);
                // getText() and getContentDescription() expose text-based signals
                // used by TIER3 (hasShortsTextSignal). Including them here lets
                // developers identify new text signals when TIER1/TIER2 fail.
                String text = child.getText() != null
                        ? "\"" + child.getText() + "\""
                        : "null";
                String contentDesc = child.getContentDescription() != null
                        ? "\"" + child.getContentDescription() + "\""
                        : "null";
                Log.w(tag, "YT_TREE_DUMP " + indent + "[" + depth + "." + i + "] "
                        + "id=" + id + " class=" + cls
                        + " text=" + text + " contentDesc=" + contentDesc
                        + " scrollable=" + scrollable
                        + " bounds=" + bounds.toShortString());
                dumpNodeChildren(child, depth + 1, maxDepth);
                child.recycle();
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Safely recycles all nodes in a list.
     * Always call after findAccessibilityNodeInfosByViewId() to prevent memory
     * leaks.
     */
    private static void recycleAll(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null)
            return;
        for (AccessibilityNodeInfo n : nodes) {
            if (n != null) {
                try {
                    n.recycle();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
