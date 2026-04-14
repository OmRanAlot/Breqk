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

    /**
     * Known YouTube Shorts container view IDs.
     * When any of these is found and passes the full-screen bounds check, Shorts is confirmed.
     * Keep in sync with YOUTUBE_SHORTS_VIEW_IDS in ReelsInterventionService.
     * To discover new IDs after a YouTube update: enable logs and check CONTENT_FILTER output.
     */
    private static final String[] YOUTUBE_SHORTS_VIEW_IDS = {
            "com.google.android.youtube:id/reel_player_page_container",
            "com.google.android.youtube:id/shorts_container",
            "com.google.android.youtube:id/shorts_shelf_container",
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_watch_player",
            "com.google.android.youtube:id/shorts_player_container",
            "com.google.android.youtube:id/shorts_pager",
    };

    // ── Full-screen detection thresholds (must match ReelsInterventionService) ──
    /**
     * Minimum fraction of screen width the Reels/Shorts viewer must cover.
     * Embedded reel previews in the home feed are far narrower than 90%.
     */
    private static final float MIN_WIDTH_RATIO  = 0.90f;
    /**
     * Minimum fraction of screen height. 70% (not 90%) accounts for the space
     * consumed by the status bar and navigation bar.
     */
    private static final float MIN_HEIGHT_RATIO = 0.70f;
    /**
     * Maximum Y pixel offset (from top of screen) for the viewer's top edge.
     * The full-screen Reels/Shorts player starts at or near 0; embedded previews
     * start much further down the page.
     */
    private static final int   MAX_TOP_OFFSET_PX = 200;

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

        // Fast path: check source view ID
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            String viewId = source.getViewIdResourceName();
            if ("com.instagram.android:id/clips_viewer_view_pager".equals(viewId)
                    || "com.instagram.android:id/clips_viewer_pager".equals(viewId)) {
                confirmed = isFullScreenNode(source);
                Log.d(TAG, "[IG] Fast path: viewId=" + viewId + " fullScreen=" + confirmed);
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

        // Fast path (scroll events only): check source view ID
        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                String viewId = source.getViewIdResourceName();
                if (viewId != null) {
                    for (String shortsId : YOUTUBE_SHORTS_VIEW_IDS) {
                        if (viewId.equals(shortsId) && isFullScreenNode(source)) {
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
    private boolean isInstagramReels(AccessibilityNodeInfo root) {
        if (root == null) return false;

        // Primary ID: clips_viewer_view_pager
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(
                "com.instagram.android:id/clips_viewer_view_pager");
        if (nodes != null) {
            for (AccessibilityNodeInfo n : nodes) {
                if (isFullScreenNode(n)) {
                    recycleAll(nodes);
                    Log.d(TAG, "[IG] isInstagramReels: clips_viewer_view_pager confirmed full-screen → true");
                    return true;
                }
            }
            recycleAll(nodes);
        }

        // Secondary ID: clips_viewer_pager (alternative Instagram versions)
        List<AccessibilityNodeInfo> altNodes = root.findAccessibilityNodeInfosByViewId(
                "com.instagram.android:id/clips_viewer_pager");
        if (altNodes != null) {
            for (AccessibilityNodeInfo n : altNodes) {
                if (isFullScreenNode(n)) {
                    recycleAll(altNodes);
                    Log.d(TAG, "[IG] isInstagramReels: clips_viewer_pager (alt) confirmed full-screen → true");
                    return true;
                }
            }
            recycleAll(altNodes);
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
    private boolean isYouTubeShorts(AccessibilityNodeInfo root) {
        if (root == null) return false;

        for (String viewId : YOUTUBE_SHORTS_VIEW_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (isFullScreenNode(n)) {
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
    private boolean isFullScreenNode(AccessibilityNodeInfo node) {
        if (node == null) return false;

        // Signal 1: Must be visible to user (not a back-stack node)
        if (!node.isVisibleToUser()) return false;

        // Signals 2, 3, 4: Screen coverage bounds
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int screenW = metrics.widthPixels;
        int screenH = metrics.heightPixels;
        if (screenW <= 0 || screenH <= 0) return false;

        float wRatio = (float) bounds.width()  / screenW;
        float hRatio = (float) bounds.height() / screenH;

        boolean fullScreen = wRatio >= MIN_WIDTH_RATIO
                && hRatio >= MIN_HEIGHT_RATIO
                && bounds.top <= MAX_TOP_OFFSET_PX;

        Log.d(TAG, "[FULLSCREEN_CHECK]"
                + " visible=true"
                + " wRatio=" + String.format("%.2f", wRatio) + "/" + MIN_WIDTH_RATIO
                + " hRatio=" + String.format("%.2f", hRatio) + "/" + MIN_HEIGHT_RATIO
                + " top=" + bounds.top + "/" + MAX_TOP_OFFSET_PX
                + " → " + fullScreen);

        return fullScreen;
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
