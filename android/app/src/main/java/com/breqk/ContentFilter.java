package com.breqk;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.breqk.reels.FullScreenCheck;
import com.breqk.reels.ShortFormIds;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ContentFilter
 * --------------
 * Surgically ejects users from short-form content (Reels, Shorts, FYP) by
 * calling GLOBAL_ACTION_BACK when the content is detected.
 *
 * Per-package behavior (governed by blockShortForm flag from AppEventRouter.AppConfig):
 *
 *   Instagram (com.instagram.android):
 *     Fires on TYPE_VIEW_SCROLLED only (CONTENT_CHANGED is too noisy on IG).
 *     Fast path: checks event source view ID for clips_viewer_view_pager.
 *     Slow path (fallback): full accessibility tree traversal for clips_viewer_* IDs.
 *     Both paths gate through isFullScreenNode() to prevent false positives from
 *     back-stack nodes and embedded Reel previews in the home feed.
 *
 *   YouTube (com.google.android.youtube):
 *     Fires on TYPE_VIEW_SCROLLED and TYPE_WINDOW_CONTENT_CHANGED.
 *     Checks YOUTUBE_SHORTS_VIEW_IDS (Tier 1 only — same array as ReelsInterventionService).
 *     Fast path: event source view ID check.
 *     Slow path: tree traversal.
 *
 *   TikTok (com.zhiliaoapp.musically):
 *     The entire app is short-form video — no UI detection needed.
 *     Fires on any TYPE_VIEW_SCROLLED event.
 *
 * Important distinctions from ReelsInterventionService:
 *   - ContentFilter performs EJECTION (GLOBAL_ACTION_BACK) based on the blockShortForm
 *     policy flag. It fires regardless of scroll budget state.
 *   - ReelsInterventionService performs TIME-BASED INTERVENTION (overlay popup) based
 *     on scroll budget exhaustion. It fires after the budget is used up.
 *   - Both can fire for the same app at the same time — they are independent features.
 *
 * Full-screen guard thresholds (matching ReelsInterventionService exactly):
 *   MIN_WIDTH_RATIO   = 0.90 (90% of screen width)
 *   MIN_HEIGHT_RATIO  = 0.70 (70% of screen height — accounts for status/nav bars)
 *   MAX_TOP_OFFSET_PX = 200  (viewer must start within 200px from top)
 *
 * Thread safety:
 *   onContentChanged() is called from the accessibility thread.
 *   eject() posts GLOBAL_ACTION_BACK to mainHandler for UI-thread safety.
 *   ConcurrentHashMap is used for lastEjectionTime since it may be accessed
 *   from multiple contexts during service teardown.
 *
 * Logging tag: CONTENT_FILTER
 * Filter:          adb logcat -s CONTENT_FILTER
 * Ejection events: adb logcat -s CONTENT_FILTER | findstr "EJECT"
 * Debounce:        adb logcat -s CONTENT_FILTER | findstr "DEBOUNCE"
 */
public class ContentFilter {

    private static final String TAG = "CONTENT_FILTER";

    private static final String PKG_INSTAGRAM = "com.instagram.android";
    private static final String PKG_YOUTUBE   = "com.google.android.youtube";
    private static final String PKG_TIKTOK    = "com.zhiliaoapp.musically";

    /**
     * Minimum ms between GLOBAL_ACTION_BACK calls for the same package.
     * A single physical swipe fires 3–4 TYPE_VIEW_SCROLLED events within ~200ms;
     * EJECTION_DEBOUNCE_MS of 600ms ensures we only process one per physical gesture.
     * Matches SCROLL_DEBOUNCE_MS in ReelsInterventionService.
     */
    private static final long EJECTION_DEBOUNCE_MS = 600L;

    // ── View IDs and full-screen thresholds ──────────────────────────────────
    // All view IDs and full-screen threshold constants are now consolidated in
    // com.breqk.reels.ShortFormIds (Step 1 of reels-intervention-refactor).
    // This eliminates Bug B1 — a single edit to ShortFormIds propagates to both
    // ContentFilter and ReelsInterventionService automatically.
    //
    // Previously duplicated here as:
    //   YOUTUBE_SHORTS_VIEW_IDS  (L86–94)
    //   MIN_WIDTH_RATIO  = 0.90f  (L101)
    //   MIN_HEIGHT_RATIO = 0.70f  (L106)
    //   MAX_TOP_OFFSET_PX = 200   (L112)
    //
    // To discover new YouTube IDs after an app update:
    //   adb logcat -s REELS_WATCH | findstr "YT_TREE_DUMP"

    private final Context context;
    private final AccessibilityService service;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Per-package last-ejection timestamp (System.currentTimeMillis()).
     * ConcurrentHashMap for thread safety during service teardown.
     */
    private final ConcurrentHashMap<String, Long> lastEjectionTime = new ConcurrentHashMap<>();

    /**
     * Creates the ContentFilter.
     *
     * @param context Android context (the AccessibilityService)
     * @param service AccessibilityService reference — needed for performGlobalAction()
     *                and getRootInActiveWindow()
     */
    public ContentFilter(Context context, AccessibilityService service) {
        this.context = context;
        this.service = service;
        Log.d(TAG, "[INIT] ContentFilter created debounceMs=" + EJECTION_DEBOUNCE_MS);
    }

    /**
     * Called by AppEventRouter on TYPE_WINDOW_CONTENT_CHANGED and TYPE_VIEW_SCROLLED.
     *
     * Fast-exits if blockShortForm is false for this app.
     * Dispatches to the per-package handler based on packageName.
     *
     * @param event  The accessibility event
     * @param config Feature flags for the app (blockShortForm must be true to eject)
     */
    public void onContentChanged(AccessibilityEvent event, AppEventRouter.AppConfig config) {
        if (!config.blockShortForm) {
            return; // Feature disabled for this app — nothing to do
        }

        String pkg = config.packageName;
        int eventType = event.getEventType();

        switch (pkg) {
            case PKG_INSTAGRAM:
                handleInstagram(event, eventType);
                break;
            case PKG_YOUTUBE:
                handleYouTube(event, eventType);
                break;
            case PKG_TIKTOK:
                handleTikTok(eventType);
                break;
            default:
                // Unknown monitored package — log and skip
                Log.w(TAG, "[SKIP] No handler for pkg=" + pkg);
                break;
        }
    }

    /**
     * Cleans up internal state. Call from the service's onInterrupt() / onDestroy().
     */
    public void onDestroy() {
        lastEjectionTime.clear();
        Log.d(TAG, "[DESTROY] ContentFilter destroyed");
    }

    // =========================================================================
    // Per-package handlers
    // =========================================================================

    /**
     * Instagram handler.
     *
     * Only processes TYPE_VIEW_SCROLLED — TYPE_WINDOW_CONTENT_CHANGED is too noisy
     * (it fires on every DOM change in the home feed, including embedded reel previews).
     *
     * Fast path (O(1)):
     *   Checks the scroll event's source view ID for clips_viewer_view_pager /
     *   clips_viewer_pager. If matched, verifies full-screen bounds.
     *
     * Slow path (O(tree size)):
     *   Full accessibility tree traversal via isInstagramReels(). Used when the fast
     *   path doesn't match (e.g., scroll from a child view inside the Reels player).
     */
    private void handleInstagram(AccessibilityEvent event, int eventType) {
        if (eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;

        boolean confirmed = false;

        // Fast path: check source view ID against ShortFormIds.INSTAGRAM_REELS_IDS
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            String viewId = source.getViewIdResourceName();
            if (viewId != null) {
                for (String reelsId : ShortFormIds.INSTAGRAM_REELS_IDS) {
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
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            confirmed = isInstagramReels(root);
            if (root != null) root.recycle();
            Log.d(TAG, "[IG] Slow path: treeTraversal confirmed=" + confirmed);
        }

        if (confirmed) {
            eject(PKG_INSTAGRAM, "Instagram Reels confirmed (full-screen viewer)");
        }
    }

    /**
     * YouTube handler.
     *
     * Processes both TYPE_VIEW_SCROLLED (active Shorts scrolling) and
     * TYPE_WINDOW_CONTENT_CHANGED (Shorts tab navigation).
     *
     * Fast path (VIEW_SCROLLED only):
     *   Checks source view ID against YOUTUBE_SHORTS_VIEW_IDS. If matched + full-screen → eject.
     *
     * Slow path:
     *   Full tree traversal via isYouTubeShorts() (Tier 1 — known container IDs only).
     */
    private void handleYouTube(AccessibilityEvent event, int eventType) {
        if (eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
                && eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;

        boolean confirmed = false;

        // Fast path (scroll events only): check source view ID against ShortFormIds.YOUTUBE_SHORTS_VIEW_IDS
        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                String viewId = source.getViewIdResourceName();
                if (viewId != null) {
                    for (String shortsId : ShortFormIds.YOUTUBE_SHORTS_VIEW_IDS) {
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
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            confirmed = isYouTubeShorts(root);
            if (root != null) root.recycle();
            Log.d(TAG, "[YT] Slow path: treeTraversal confirmed=" + confirmed);
        }

        if (confirmed) {
            eject(PKG_YOUTUBE, "YouTube Shorts confirmed");
        }
    }

    /**
     * TikTok handler.
     *
     * The entire TikTok app is short-form video — there is no "safe" area to detect and
     * allow. When blockShortForm is true, every scroll event triggers ejection.
     *
     * Only processes TYPE_VIEW_SCROLLED (TYPE_CONTENT_CHANGED would fire too aggressively
     * on TikTok's live feed updates between swipes).
     */
    private void handleTikTok(int eventType) {
        if (eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;
        eject(PKG_TIKTOK, "TikTok scroll (full-app is short-form video)");
    }

    // =========================================================================
    // Detection helpers
    // =========================================================================

    /**
     * Returns true if the Instagram accessibility tree contains a full-screen Reels viewer.
     *
     * Checks clips_viewer_view_pager (primary) and clips_viewer_pager (alternative ID
     * seen on some Instagram versions). Both must pass the full-screen bounds check.
     *
     * This mirrors isReelsLayout() in ReelsInterventionService. If Instagram renames
     * these IDs, update both files and add a comment to LOGGING.md.
     *
     * @param root Root node of the Instagram accessibility tree (may be null)
     * @return true if Reels viewer is confirmed full-screen; false otherwise
     */
    /**
     * Returns true if the Instagram accessibility tree contains a full-screen Reels viewer.
     *
     * Iterates ShortFormIds.INSTAGRAM_REELS_IDS (primary + alternative IDs) and validates
     * each found node via FullScreenCheck.isFullScreen().
     *
     * This mirrors isReelsLayout() in ReelsInterventionService — both now share the same
     * constant source (ShortFormIds) so any future Instagram update requires only one edit.
     *
     * @param root Root node of the Instagram accessibility tree (may be null)
     * @return true if Reels viewer is confirmed full-screen; false otherwise
     */
    private boolean isInstagramReels(AccessibilityNodeInfo root) {
        if (root == null) return false;

        // Iterate all known Instagram Reels IDs — ShortFormIds.INSTAGRAM_REELS_IDS
        // is the single source of truth (replaces the two separate lookups that were here).
        for (String reelsId : ShortFormIds.INSTAGRAM_REELS_IDS) {
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

    /**
     * Returns true if the YouTube accessibility tree contains a Shorts player.
     *
     * Implements Tier 1 only (known container IDs + full-screen bounds check).
     * More sophisticated tiers (text scan, structural heuristics) are left to
     * ReelsInterventionService, which handles budget-based intervention.
     * ContentFilter uses Tier 1 only because ejection must be low-latency and
     * precise — over-detection would be worse here (every false positive = a BACK press).
     *
     * @param root Root node of the YouTube accessibility tree (may be null)
     * @return true if Shorts player is confirmed; false otherwise
     */
    /**
     * Returns true if the YouTube accessibility tree contains a Shorts player.
     *
     * Implements Tier 1 only (known container IDs from ShortFormIds.YOUTUBE_SHORTS_VIEW_IDS
     * + full-screen bounds check via FullScreenCheck.isFullScreen()).
     *
     * More sophisticated tiers (text scan, structural heuristics) are handled by
     * ReelsInterventionService, which runs budget-based intervention logic. ContentFilter
     * uses Tier 1 only because ejection must be low-latency and precise — over-detection
     * would be worse here (every false positive = a BACK press).
     *
     * @param root Root node of the YouTube accessibility tree (may be null)
     * @return true if Shorts player is confirmed; false otherwise
     */
    private boolean isYouTubeShorts(AccessibilityNodeInfo root) {
        if (root == null) return false;

        // ShortFormIds.YOUTUBE_SHORTS_VIEW_IDS is the single source of truth.
        for (String viewId : ShortFormIds.YOUTUBE_SHORTS_VIEW_IDS) {
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

    /**
     * Returns true if the node is visible, covers ≥ MIN_WIDTH_RATIO of screen width,
     * ≥ MIN_HEIGHT_RATIO of screen height, and starts within MAX_TOP_OFFSET_PX from
     * the top of the screen.
     *
     * Mirrors isFullScreenReelsViewPager() in ReelsInterventionService. Thresholds
     * are defined as constants at the top of this file and must stay in sync.
     *
     * The four signals (visibility, width, height, top offset) must ALL pass to
     * return true. This prevents false positives from:
     *   - Back-stack nodes (isVisibleToUser() = false)
     *   - Home-feed embedded reel cards (width < 90%)
     *   - Mid-page previews (top > 200px)
     *
     * @param node Node to evaluate; null returns false
     * @return true only when all four signals confirm full-screen Reels/Shorts viewer
     */
    /**
     * Returns true if the node is a full-screen Reels/Shorts viewer.
     *
     * @deprecated Replaced by {@link FullScreenCheck#isFullScreen(AccessibilityNodeInfo, Context, String)}.
     *             Kept as a thin delegate for backward compatibility within this file.
     *             Remove once all call sites are updated to use FullScreenCheck directly.
     */
    @Deprecated
    private boolean isFullScreenNode(AccessibilityNodeInfo node) {
        return FullScreenCheck.isFullScreen(node, context, TAG);
    }

    // =========================================================================
    // Ejection
    // =========================================================================

    /**
     * Performs GLOBAL_ACTION_BACK to eject the user from the short-form content view.
     *
     * Debounced per package: if the last ejection for this package was less than
     * EJECTION_DEBOUNCE_MS ago, this call is silently ignored (prevents rapid-fire
     * back-presses from a single physical swipe gesture).
     *
     * The GLOBAL_ACTION_BACK is posted to the main handler because accessibility API
     * calls that interact with the window system must run on the UI thread.
     *
     * Log filter: adb logcat -s CONTENT_FILTER | findstr "EJECT"
     *
     * @param pkg    Package name of the app being ejected (debounce key)
     * @param reason Human-readable description of what triggered ejection (for logging)
     */
    private void eject(String pkg, String reason) {
        long now = System.currentTimeMillis();
        Long last = lastEjectionTime.get(pkg);

        if (last != null && (now - last) < EJECTION_DEBOUNCE_MS) {
            Log.d(TAG, "[DEBOUNCE] pkg=" + pkg
                    + " elapsedMs=" + (now - last)
                    + " < debounceMs=" + EJECTION_DEBOUNCE_MS + " — skipping");
            return;
        }

        lastEjectionTime.put(pkg, now);
        Log.i(TAG, "[EJECT] pkg=" + pkg
                + " reason=" + reason
                + " → performGlobalAction(GLOBAL_ACTION_BACK)");

        // Post to main thread — accessibility global actions must be on the UI thread
        mainHandler.post(() -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Recycles a list of AccessibilityNodeInfo objects, skipping null entries.
     * Always call after findAccessibilityNodeInfosByViewId() to prevent memory leaks.
     *
     * @param nodes List to recycle; null is silently ignored
     */
    private static void recycleAll(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo n : nodes) {
            if (n != null) n.recycle();
        }
    }
}
