package com.breqk;

/*
 * ReelsInterventionService
 * -------------------------
 * AccessibilityService that monitors Instagram Reels and YouTube Shorts scroll behavior.
 * When the scroll budget time is exhausted (tracked by AppUsageMonitor via SharedPreferences),
 * it shows a "Time is up!" intervention popup with a single "Lock In" button that exits
 * the user to the Android home screen.
 *
 * The popup does NOT fire based on scroll count — it only fires when the scroll budget
 * (configured in Customize → Scroll Budget) is exhausted. Setting the budget allowance to
 * 0 minutes causes the popup to fire immediately on every Reels/Shorts scroll.
 *
 * Filter logcat with: adb logcat -s REELS_WATCH
 * For budget-related decisions: adb logcat -s REELS_WATCH | grep BUDGET
 * For scroll decisions: adb logcat -s REELS_WATCH | grep SCROLL_DECISION
 *
 * Architecture:
 *   AccessibilityService (this) → onAccessibilityEvent → handleReelsScrollEvent
 *     → isFullScreenReelsViewPager (visibility + bounds check — core false-positive fix)
 *     → isReelsLayout / isShortsLayout (view ID search + full-screen verification)
 *     → checks SharedPreferences for scroll budget exhaustion
 *     → triggerIntervention → WindowManager overlay (overlay_reels_intervention.xml)
 *
 * IMPORTANT: The popup ONLY fires when the user is in the full-screen Reels viewer
 * (vertical video with like/comment/share on the right, account info + music + caption
 * at the bottom). Scrolling anywhere else in Instagram — home feed (including embedded
 * reel previews), DMs, stories, profiles, Explore — does NOT trigger the popup.
 *
 * Root cause of false positives (FIXED):
 *   Instagram keeps the Reels ViewPager in memory on its back stack even after the user
 *   navigates away. The old code only checked view ID existence; the new code additionally
 *   checks isVisibleToUser() and screen-coverage bounds to confirm the viewer is actually
 *   active and full-screen before counting any scroll.
 *
 * Detection re-verified on every scroll event using two strategies:
 *   1. Fast path: check if scroll event source is clips_viewer_view_pager (O(1))
 *   2. Slow path: full accessibility tree traversal for Reels view IDs
 *   Both paths gate through isFullScreenReelsViewPager() before confirming.
 *
 * Scroll budget status is read from SharedPreferences ("breqk_prefs"):
 *   - scroll_budget_exhausted_at (long): >0 means budget is exhausted
 *   - scroll_window_start_time (long): when the current budget window started
 *   - scroll_window_minutes (int): duration of the budget window
 *
 * Config: res/xml/reels_intervention_service_config.xml
 * Registered in: AndroidManifest.xml
 */

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

public class ReelsInterventionService extends AccessibilityService {

    // Filter logcat with: adb logcat -s REELS_WATCH
    // Budget decisions: adb logcat -s REELS_WATCH | grep BUDGET
    // Scroll decisions: adb logcat -s REELS_WATCH | grep SCROLL_DECISION
    private static final String TAG = "REELS_WATCH";

    // Target package names
    private static final String PKG_INSTAGRAM = "com.instagram.android";
    private static final String PKG_YOUTUBE   = "com.google.android.youtube";

    /**
     * Minimum milliseconds between two processed scroll events.
     *
     * A single physical Reel swipe fires 3–4 TYPE_VIEW_SCROLLED events within ~100–200 ms.
     * Any event that arrives within this window after the last processed scroll is treated as
     * a duplicate from the same physical swipe and is ignored.
     *
     * 600 ms is chosen because real inter-swipe time is always >600 ms in practice,
     * while intra-swipe duplicate events arrive within ~200 ms.
     */
    private static final long SCROLL_DEBOUNCE_MS = 600;

    /**
     * Minimum fraction of screen width the Reels ViewPager must cover to be
     * considered full-screen. Home feed embedded reel cards are far narrower.
     * Range: 0.0–1.0. Default: 0.90 (90%).
     */
    private static final float MIN_WIDTH_RATIO  = 0.90f;

    /**
     * Minimum fraction of screen height the Reels ViewPager must cover.
     * 0.70 (70%) accounts for status bar + nav bar eating significant space.
     */
    private static final float MIN_HEIGHT_RATIO = 0.70f;

    /**
     * Maximum Y pixel offset (from top of screen) for the ViewPager's top edge.
     * Embedded reels in the home feed start much further down the page.
     */
    private static final int MAX_TOP_OFFSET_PX = 200;

    // --- Scroll tracking state ---

    /**
     * Timestamp of the last scroll event that was processed (after debounce).
     * Used to debounce duplicate events from a single physical swipe.
     */
    private long lastScrollTimestamp = 0;

    /**
     * True when user was in a Reels/Shorts layout on the last event.
     * Lets us detect when they navigate away and reset state.
     */
    private boolean wasInReelsLayout = false;

    /**
     * Guard against stacking multiple overlays if scroll events fire
     * rapidly around the budget exhaustion boundary.
     */
    private boolean interventionShowing = false;

    /** Currently visible intervention overlay, or null. */
    private View interventionView = null;

    /** Handler on main looper — WindowManager calls must be on the UI thread. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // --- Reels state persistence (shared with AppUsageMonitor) ---

    /** SharedPreferences key: whether user is currently viewing Reels/Shorts */
    private static final String PREF_IS_IN_REELS = "is_in_reels";
    /** SharedPreferences key: timestamp of last Reels state update (for staleness check) */
    private static final String PREF_IS_IN_REELS_TIMESTAMP = "is_in_reels_timestamp";
    /** SharedPreferences key: which app is in Reels (package name) */
    private static final String PREF_IS_IN_REELS_PACKAGE = "is_in_reels_package";

    /**
     * Heartbeat interval: how often we refresh the Reels state timestamp while
     * the user stays in Reels without scrolling. Must be less than the staleness
     * threshold in AppUsageMonitor (5s) to avoid false expiration.
     */
    private static final long REELS_HEARTBEAT_INTERVAL_MS = 2000;

    /** Runnable that periodically refreshes the Reels state timestamp. */
    private Runnable reelsHeartbeatRunnable = null;

    // =========================================================================
    // Service lifecycle
    // =========================================================================

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();

        // TYPE_VIEW_SCROLLED: needed to detect scrolls within Reels/Shorts
        // TYPE_WINDOW_STATE_CHANGED: needed to detect layout changes (entering/leaving Reels)
        // TYPE_WINDOW_CONTENT_CHANGED is intentionally excluded — too noisy, causes false positives
        // on home feed embeds; layout re-check only happens on STATE_CHANGED (tab navigation)
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        // FLAG_REPORT_VIEW_IDS is CRITICAL: without it findAccessibilityNodeInfosByViewId() returns nothing
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);

        Log.d(TAG, "=== ReelsInterventionService CONNECTED ===");
        Log.d(TAG, "  watching: " + PKG_INSTAGRAM + ", " + PKG_YOUTUBE);
        Log.d(TAG, "  trigger: scroll budget exhaustion (from SharedPreferences)");
        Log.d(TAG, "  full-screen thresholds: widthRatio>=" + MIN_WIDTH_RATIO
                + " heightRatio>=" + MIN_HEIGHT_RATIO + " topOffset<=" + MAX_TOP_OFFSET_PX + "px");
        Log.d(TAG, "  eventTypes: VIEW_SCROLLED | WINDOW_STATE_CHANGED (CONTENT_CHANGED excluded)");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;

        String packageName = pkg.toString();

        // Only process events from Instagram or YouTube
        if (!packageName.equals(PKG_INSTAGRAM) && !packageName.equals(PKG_YOUTUBE)) return;

        handleReelsScrollEvent(event, packageName);
    }

    @Override
    public void onInterrupt() {
        // Service interrupted (e.g. user revoked permission) — clean up any visible overlay
        dismissIntervention();
        // Clear Reels state so AppUsageMonitor doesn't keep accumulating budget
        persistReelsState(false, "");
        stopReelsHeartbeat();
        Log.d(TAG, "onInterrupt: service interrupted, intervention dismissed, reels state cleared");
    }

    // =========================================================================
    // Scroll detection + budget check
    // =========================================================================

    /**
     * Core routing method. Determines if the user is in a Reels/Shorts layout,
     * checks whether the scroll budget is exhausted, and triggers the intervention
     * when the budget is used up.
     *
     * IMPORTANT: Scroll events are only processed when the user is actively watching
     * Reels (Instagram) or Shorts (YouTube). "Reels" means a full-screen vertical
     * video with like/comment/share buttons on the right and account info, music,
     * and caption at the bottom. Scrolling anywhere else in Instagram (home feed,
     * Explore, DMs, profiles, stories) does NOT trigger the popup.
     *
     * The popup fires based on scroll budget exhaustion (time-based), NOT scroll count.
     * Budget state is read from SharedPreferences, written by AppUsageMonitor.
     *
     * One SCROLL_DECISION log line is emitted per scroll event for easy debugging:
     *   adb logcat -s REELS_WATCH | grep SCROLL_DECISION
     */
    private void handleReelsScrollEvent(AccessibilityEvent event, String packageName) {
        int eventType = event.getEventType();

        // Skip content-change events entirely — too noisy, matches home feed embeds
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;

        // On tab/screen navigation: update the Reels layout flag and reset if we left Reels
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            boolean inReelsLayout = packageName.equals(PKG_INSTAGRAM)
                    ? isReelsLayout(root)
                    : isShortsLayout(root);
            if (root != null) root.recycle();

            if (!inReelsLayout && wasInReelsLayout) {
                Log.d(TAG, "Left Reels/Shorts via state change — resetting");
                resetReelsState();
            }
            wasInReelsLayout = inReelsLayout;
            Log.d(TAG, "STATE_CHANGED: wasInReelsLayout=" + wasInReelsLayout + " pkg=" + packageName);
            return; // don't process navigation events as scrolls
        }

        // TYPE_VIEW_SCROLLED: re-verify Reels layout on EVERY scroll to prevent false positives
        // from scrolling in non-Reels parts of the app (home feed, Explore, profiles, DMs, etc.)
        if (eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;

        boolean confirmedInReels = false;
        boolean usedFastPath     = false;

        // --- Fast path: check if the scroll event source is the Reels/Shorts ViewPager ---
        // O(1) — no tree traversal. Also verifies full-screen to catch back-stack nodes.
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            String viewId = source.getViewIdResourceName();
            if (viewId != null) {
                if (packageName.equals(PKG_INSTAGRAM)
                        && viewId.equals("com.instagram.android:id/clips_viewer_view_pager")) {
                    // Verify this isn't a back-stack or embedded node
                    confirmedInReels = isFullScreenReelsViewPager(source);
                    usedFastPath = true;
                    Log.d(TAG, "Fast path: source=clips_viewer_view_pager fullScreen=" + confirmedInReels);
                } else if (packageName.equals(PKG_YOUTUBE)
                        && (viewId.equals("com.google.android.youtube:id/reel_player_page_container")
                            || viewId.equals("com.google.android.youtube:id/shorts_container"))) {
                    // YouTube Shorts: trust the view ID (no back-stack issue observed)
                    confirmedInReels = true;
                    usedFastPath = true;
                    Log.d(TAG, "Fast path: source IS Shorts container (" + viewId + ")");
                }
            }
            source.recycle();
        }

        // --- Slow path: full tree traversal if fast path didn't fire ---
        // Handles scroll events from child views within the Reels player (e.g., caption scroll).
        if (!usedFastPath) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            confirmedInReels = packageName.equals(PKG_INSTAGRAM)
                    ? isReelsLayout(root)
                    : isShortsLayout(root);
            if (root != null) root.recycle();
            Log.d(TAG, "Slow path: tree traversal confirmedInReels=" + confirmedInReels);
        }

        // Update cached layout state and apply decision
        if (!confirmedInReels) {
            if (wasInReelsLayout) {
                Log.d(TAG, "Scroll outside Reels/Shorts — resetting state");
                resetReelsState();
            }
            // Emit SCROLL_DECISION line even for ignored scrolls (useful for debugging false positives)
            Log.d(TAG, "SCROLL_DECISION pkg=" + packageName
                    + " fastPath=" + usedFastPath
                    + " confirmedInReels=false"
                    + " action=IGNORED");
            return;
        }

        // Persist Reels state for AppUsageMonitor to gate scroll budget accumulation
        if (!wasInReelsLayout) {
            // Just entered Reels — persist state and start heartbeat
            persistReelsState(true, packageName);
            startReelsHeartbeat(packageName);
        }
        wasInReelsLayout = true;

        // --- Debounce: ignore rapid-fire duplicate events from the same physical swipe ---
        long now = System.currentTimeMillis();
        long elapsed = now - lastScrollTimestamp;
        if (elapsed < SCROLL_DEBOUNCE_MS) {
            Log.d(TAG, "SCROLL_DECISION pkg=" + packageName
                    + " fastPath=" + usedFastPath
                    + " confirmedInReels=true"
                    + " DEBOUNCED (elapsed=" + elapsed + "ms < " + SCROLL_DEBOUNCE_MS + "ms)");
            return;
        }
        lastScrollTimestamp = now;

        // --- Check scroll budget exhaustion from SharedPreferences ---
        // AppUsageMonitor persists budget state; we read it here to decide whether to intervene.
        boolean budgetExhausted = isScrollBudgetExhausted(now);

        Log.d(TAG, "SCROLL_DECISION pkg=" + packageName
                + " fastPath=" + usedFastPath
                + " confirmedInReels=true"
                + " budgetExhausted=" + budgetExhausted
                + " interventionShowing=" + interventionShowing);

        if (budgetExhausted && !interventionShowing) {
            interventionShowing = true;
            Log.i(TAG, "[BUDGET] Scroll budget exhausted — triggering intervention for " + packageName);
            triggerIntervention(packageName);
        } else if (!budgetExhausted) {
            Log.d(TAG, "[BUDGET] Budget OK — allowing scroll in " + packageName);
        }
    }

    // =========================================================================
    // Scroll budget check (reads from SharedPreferences)
    // =========================================================================

    /**
     * Reads scroll budget state from SharedPreferences to determine if the budget
     * is exhausted. The budget is tracked and persisted by AppUsageMonitor; this
     * service only reads the persisted state.
     *
     * Budget is considered exhausted when:
     *   1. scroll_budget_exhausted_at > 0 (AppUsageMonitor flagged it)
     *   2. AND the current window hasn't expired yet (window hasn't rolled over)
     *
     * If the window has expired (now - windowStart >= windowDuration), the budget
     * is considered available again even if exhausted_at > 0, because AppUsageMonitor
     * will reset it on its next tick.
     *
     * SharedPreferences keys read:
     *   - scroll_budget_exhausted_at (long): timestamp when budget was exhausted, 0 = not exhausted
     *   - scroll_window_start_time (long): when the current budget window started
     *   - scroll_window_minutes (int): window duration in minutes
     *
     * @param now Current system time in milliseconds
     * @return true if scroll budget is exhausted and window hasn't expired
     */
    private boolean isScrollBudgetExhausted(long now) {
        SharedPreferences prefs = getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
        long exhaustedAt = prefs.getLong("scroll_budget_exhausted_at", 0);

        // If budget hasn't been flagged as exhausted, it's available
        if (exhaustedAt == 0) {
            Log.d(TAG, "[BUDGET] exhaustedAt=0 → budget available");
            return false;
        }

        // Budget was flagged as exhausted — check if the window has rolled over
        long windowStart = prefs.getLong("scroll_window_start_time", 0);
        int windowMinutes = prefs.getInt("scroll_window_minutes", 60);
        long windowMs = windowMinutes * 60 * 1000L;

        if (windowStart > 0 && (now - windowStart) >= windowMs) {
            // Window has expired — AppUsageMonitor will reset on its next tick
            Log.d(TAG, "[BUDGET] Window expired (windowStart=" + windowStart
                    + " windowMin=" + windowMinutes
                    + " elapsed=" + (now - windowStart) + "ms) → budget available (pending reset)");
            return false;
        }

        Log.d(TAG, "[BUDGET] Budget exhausted (exhaustedAt=" + exhaustedAt
                + " windowStart=" + windowStart
                + " windowMin=" + windowMinutes + ")");
        return true;
    }

    // =========================================================================
    // Full-screen verification (core false-positive fix)
    // =========================================================================

    /**
     * Returns true if the given AccessibilityNodeInfo represents the full-screen
     * Reels ViewPager — i.e., it is actually visible and covering most of the screen.
     *
     * This is the PRIMARY guard against false positives. Instagram keeps
     * clips_viewer_view_pager in memory on its back stack even when the user navigates
     * to the home feed, DMs, stories, profiles, or Explore. Without this check, any
     * scroll in those screens would be wrongly processed.
     *
     * Three signals must ALL pass:
     *
     *   1. isVisibleToUser() — Android marks off-screen back-stack views as not visible.
     *      This is the strongest and cheapest signal. If false, the node is in the back
     *      stack and the user is NOT currently watching Reels.
     *
     *   2. Width coverage ≥ MIN_WIDTH_RATIO (90%) — The full-screen Reels player spans
     *      the entire screen width. Home feed reel cards span only a fraction.
     *
     *   3. Height coverage ≥ MIN_HEIGHT_RATIO (70%) — The full-screen player spans most
     *      of the screen height. 70% (not 90%) to allow for status bar + nav bar.
     *
     *   4. Top edge ≤ MAX_TOP_OFFSET_PX (200px) — The full-screen player starts at the
     *      very top of the screen. Embedded previews start further down.
     *
     * To tune thresholds: adjust MIN_WIDTH_RATIO, MIN_HEIGHT_RATIO, MAX_TOP_OFFSET_PX
     * constants at the top of this file and rebuild.
     *
     * @param node The AccessibilityNodeInfo to check (clips_viewer_view_pager or similar)
     * @return true only if all four signals confirm this is the active full-screen viewer
     */
    private boolean isFullScreenReelsViewPager(AccessibilityNodeInfo node) {
        if (node == null) {
            Log.d(TAG, "isFullScreenReelsViewPager: node null → false");
            return false;
        }

        // --- Signal 1: Visibility ---
        // isVisibleToUser() returns false for views that are in the back stack or off-screen.
        // This is the cheapest check — do it first.
        if (!node.isVisibleToUser()) {
            Log.d(TAG, "isFullScreenReelsViewPager: NOT visible to user (back stack) → false");
            return false;
        }

        // --- Signals 2, 3, 4: Screen coverage bounds ---
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth  = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;

        float widthRatio  = screenWidth  > 0 ? (float) bounds.width()  / screenWidth  : 0f;
        float heightRatio = screenHeight > 0 ? (float) bounds.height() / screenHeight : 0f;

        Log.d(TAG, "isFullScreenReelsViewPager:"
                + " visible=true"
                + " bounds=[" + bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom + "]"
                + " screen=" + screenWidth + "x" + screenHeight
                + " widthRatio=" + String.format("%.2f", widthRatio)
                + " heightRatio=" + String.format("%.2f", heightRatio)
                + " top=" + bounds.top);

        if (widthRatio < MIN_WIDTH_RATIO) {
            Log.d(TAG, "isFullScreenReelsViewPager: widthRatio " + String.format("%.2f", widthRatio)
                    + " < " + MIN_WIDTH_RATIO + " → embedded preview → false");
            return false;
        }

        if (heightRatio < MIN_HEIGHT_RATIO) {
            Log.d(TAG, "isFullScreenReelsViewPager: heightRatio " + String.format("%.2f", heightRatio)
                    + " < " + MIN_HEIGHT_RATIO + " → embedded preview → false");
            return false;
        }

        if (bounds.top > MAX_TOP_OFFSET_PX) {
            Log.d(TAG, "isFullScreenReelsViewPager: top=" + bounds.top
                    + " > " + MAX_TOP_OFFSET_PX + "px → not at screen top → false");
            return false;
        }

        Log.d(TAG, "isFullScreenReelsViewPager: ALL signals passed → true (full-screen Reels viewer)");
        return true;
    }

    // =========================================================================
    // Layout detection
    // =========================================================================

    /**
     * Returns true if the current window is Instagram's full-screen Reels viewer.
     *
     * What qualifies as "Reels":
     *   - Full-screen vertical video playing
     *   - Like, comment, share/repost buttons visible on the right side
     *   - Account name, music info, and caption visible at the bottom
     *   - This is the dedicated Reels tab OR a reel opened from a profile/explore
     *
     * What does NOT qualify (popup must NOT fire):
     *   - Home feed (even when it contains embedded reel previews or reel cards)
     *   - Explore/Search tab
     *   - DMs, Stories, profiles, settings, or any other screen
     *
     * Detection strategy:
     *   1. Find nodes with view ID "clips_viewer_view_pager" (primary) or
     *      "clips_viewer_pager" (alternative seen in some Instagram versions)
     *   2. For each matched node, call isFullScreenReelsViewPager() to confirm
     *      the node is actually visible and covering the full screen
     *
     * Text fallbacks ("Reel by", "Original audio") have been intentionally removed because
     * those strings also appear on reels embedded in the Instagram home feed.
     *
     * If detection breaks after an Instagram update, use Android Studio Layout Inspector
     * (View > Tool Windows > Layout Inspector → attach to com.instagram.android) to find
     * the new ViewPager ID. Common Reels-related view IDs to look for:
     *   - clips_viewer_view_pager (current primary)
     *   - clips_viewer_pager (alternative)
     *   - clips_tab (tab indicator — less reliable, exists outside full-screen viewer)
     */
    private boolean isReelsLayout(AccessibilityNodeInfo root) {
        if (root == null) {
            Log.d(TAG, "isReelsLayout: root null → false");
            return false;
        }

        // PRIMARY: clips_viewer_view_pager
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(
                "com.instagram.android:id/clips_viewer_view_pager");
        if (nodes != null && !nodes.isEmpty()) {
            Log.d(TAG, "isReelsLayout: found " + nodes.size() + " clips_viewer_view_pager node(s) — checking full-screen");
            for (AccessibilityNodeInfo n : nodes) {
                if (isFullScreenReelsViewPager(n)) {
                    // Clean up remaining nodes before returning
                    for (AccessibilityNodeInfo r : nodes) {
                        try { r.recycle(); } catch (Exception ignored) {}
                    }
                    Log.d(TAG, "isReelsLayout: YES — clips_viewer_view_pager is full-screen");
                    return true;
                }
            }
            for (AccessibilityNodeInfo n : nodes) {
                try { n.recycle(); } catch (Exception ignored) {}
            }
            Log.d(TAG, "isReelsLayout: clips_viewer_view_pager found but none passed full-screen check → false");
        }

        // SECONDARY: clips_viewer_pager (alternative ID on some Instagram versions)
        List<AccessibilityNodeInfo> altNodes = root.findAccessibilityNodeInfosByViewId(
                "com.instagram.android:id/clips_viewer_pager");
        if (altNodes != null && !altNodes.isEmpty()) {
            Log.d(TAG, "isReelsLayout: found " + altNodes.size() + " clips_viewer_pager node(s) — checking full-screen");
            for (AccessibilityNodeInfo n : altNodes) {
                if (isFullScreenReelsViewPager(n)) {
                    for (AccessibilityNodeInfo r : altNodes) {
                        try { r.recycle(); } catch (Exception ignored) {}
                    }
                    Log.d(TAG, "isReelsLayout: YES — clips_viewer_pager (alt ID) is full-screen");
                    return true;
                }
            }
            for (AccessibilityNodeInfo n : altNodes) {
                try { n.recycle(); } catch (Exception ignored) {}
            }
            Log.d(TAG, "isReelsLayout: clips_viewer_pager found but none passed full-screen check → false");
        }

        Log.d(TAG, "isReelsLayout: NO — no qualifying Reels view found");
        return false;
    }

    /**
     * Returns true if the current window is YouTube's Shorts player.
     *
     * Detection strategy (in priority order):
     * 1. View ID "reel_player_page_container" — primary signal
     * 2. View IDs "like_button" + "shorts_container" — confirms Shorts vs regular video
     *
     * YouTube Shorts detection is left intentionally simpler than Instagram's because
     * the back-stack false positive issue has not been observed on YouTube.
     *
     * If detection breaks after a YouTube update, use Layout Inspector
     * (attach to com.google.android.youtube) to find new container IDs.
     */
    private boolean isShortsLayout(AccessibilityNodeInfo root) {
        if (root == null) {
            Log.d(TAG, "isShortsLayout: root null → false");
            return false;
        }

        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(
                "com.google.android.youtube:id/reel_player_page_container");
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo n : nodes) {
                try { n.recycle(); } catch (Exception ignored) {}
            }
            Log.d(TAG, "isShortsLayout: matched reel_player_page_container → true");
            return true;
        }

        // like_button alone exists in regular videos too — must confirm shorts_container
        List<AccessibilityNodeInfo> likeNodes = root.findAccessibilityNodeInfosByViewId(
                "com.google.android.youtube:id/like_button");
        if (likeNodes != null && !likeNodes.isEmpty()) {
            for (AccessibilityNodeInfo n : likeNodes) {
                try { n.recycle(); } catch (Exception ignored) {}
            }
            List<AccessibilityNodeInfo> shortsFeed = root.findAccessibilityNodeInfosByViewId(
                    "com.google.android.youtube:id/shorts_container");
            if (shortsFeed != null && !shortsFeed.isEmpty()) {
                for (AccessibilityNodeInfo n : shortsFeed) {
                    try { n.recycle(); } catch (Exception ignored) {}
                }
                Log.d(TAG, "isShortsLayout: matched like_button + shorts_container → true");
                return true;
            }
        }

        Log.d(TAG, "isShortsLayout: no match → false");
        return false;
    }

    /**
     * Clears all scroll tracking state. Called on layout exit or intervention resolution.
     * Note: reelsScrollCount has been removed — budget is time-based, not count-based.
     */
    private void resetReelsState() {
        wasInReelsLayout = false;
        interventionShowing = false;
        lastScrollTimestamp = 0;
        // Clear Reels state in SharedPreferences so AppUsageMonitor stops accumulating budget
        persistReelsState(false, "");
        stopReelsHeartbeat();
        Log.d(TAG, "resetReelsState: cleared (wasInReels=false, interventionShowing=false, lastScrollTimestamp=0, reelsState=false)");
    }

    // =========================================================================
    // Intervention overlay
    // =========================================================================

    /**
     * Shows the "Time is up!" intervention popup via WindowManager.
     *
     * Uses TYPE_ACCESSIBILITY_OVERLAY (no SYSTEM_ALERT_WINDOW needed; AccessibilityServices
     * get this permission automatically).
     *
     * Layout structure: FrameLayout (full screen, transparent) wraps the card LinearLayout,
     * which is bottom-aligned. This is the correct pattern for WindowManager overlays.
     *
     * The overlay shows:
     *   - Title: "Time is up!"
     *   - Single button: "Lock In" → fires GLOBAL_ACTION_HOME (exits to Android home screen)
     *   - The second button (btn_take_break) is hidden (View.GONE)
     *
     * FLAG_NOT_TOUCH_MODAL + FLAG_WATCH_OUTSIDE_TOUCH: overlay captures its own touches
     * while passing outside-overlay touches through to the app beneath.
     * FLAG_NOT_FOCUSABLE is intentionally absent — without focus the buttons are untappable.
     *
     * @param pkg PKG_INSTAGRAM or PKG_YOUTUBE — used for logging context
     */
    private void triggerIntervention(final String pkg) {
        mainHandler.post(() -> {
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                Log.e(TAG, "triggerIntervention: WindowManager null, cannot show overlay");
                interventionShowing = false;
                return;
            }

            // MATCH_PARENT so the FrameLayout fills the screen.
            // Gravity.BOTTOM: the inner bottom sheet LinearLayout rises from the bottom edge.
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
            );
            // BOTTOM gravity so the sheet anchors to the bottom of the screen
            params.gravity = Gravity.BOTTOM;

            interventionView = LayoutInflater.from(this)
                    .inflate(R.layout.overlay_reels_intervention, null);

            // --- Title: "Time is up!" ---
            TextView titleView = interventionView.findViewById(R.id.intervention_title);
            titleView.setText("Time is up!");
            Log.d(TAG, "triggerIntervention: title set to 'Time is up!'");

            // --- "Lock In" button: exit to Android home screen ---
            // This is the ONLY visible button — forces user to leave the app
            Button btnLockIn = interventionView.findViewById(R.id.btn_lock_in);
            btnLockIn.setText("Lock In");
            btnLockIn.setOnClickListener(v -> {
                Log.i(TAG, "lock_in tapped for " + pkg + " — going to Android home screen");
                dismissIntervention();
                resetReelsState();
                performGlobalAction(GLOBAL_ACTION_HOME);
            });

            // --- Hide the second button entirely ---
            // Only one action available: Lock In (go home)
            Button btnTakeBreak = interventionView.findViewById(R.id.btn_take_break);
            btnTakeBreak.setVisibility(View.GONE);

            windowManager.addView(interventionView, params);
            Log.i(TAG, "[BUDGET] Overlay shown (Time is up!) for " + pkg);
        });
    }

    /**
     * Removes the intervention overlay. Safe to call when no overlay is showing.
     */
    private void dismissIntervention() {
        if (interventionView == null) return;
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager != null) {
            try {
                windowManager.removeView(interventionView);
                Log.d(TAG, "dismissIntervention: removed");
            } catch (Exception e) {
                Log.w(TAG, "dismissIntervention: removeView failed (already removed?)", e);
            }
        }
        interventionView = null;
        interventionShowing = false;
    }

    // =========================================================================
    // Reels state persistence (shared with AppUsageMonitor via SharedPreferences)
    // =========================================================================

    /**
     * Writes the current Reels/Shorts viewing state to SharedPreferences so that
     * AppUsageMonitor can gate scroll budget accumulation to only Reels time.
     *
     * Three keys are written atomically:
     *   - is_in_reels (boolean): whether the user is currently in Reels/Shorts
     *   - is_in_reels_timestamp (long): System.currentTimeMillis() of this write
     *   - is_in_reels_package (String): which app (e.g. com.instagram.android)
     *
     * AppUsageMonitor reads these keys and treats the flag as stale if the timestamp
     * is older than 5 seconds, so the heartbeat must keep it fresh.
     *
     * @param inReels true when entering Reels, false when leaving
     * @param packageName the app package currently in Reels (ignored when inReels=false)
     */
    private void persistReelsState(boolean inReels, String packageName) {
        SharedPreferences prefs = getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(PREF_IS_IN_REELS, inReels)
                .putLong(PREF_IS_IN_REELS_TIMESTAMP, System.currentTimeMillis())
                .putString(PREF_IS_IN_REELS_PACKAGE, inReels ? packageName : "")
                .apply();
        Log.d(TAG, "[REELS_STATE] persistReelsState: inReels=" + inReels
                + " pkg=" + packageName
                + " timestamp=" + System.currentTimeMillis());
    }

    /**
     * Starts a repeating heartbeat that refreshes the Reels state timestamp every 2s.
     * This prevents AppUsageMonitor from treating the flag as stale when the user
     * is sitting on one Reel without scrolling (no scroll events = no timestamp updates).
     *
     * @param packageName the app currently in Reels
     */
    private void startReelsHeartbeat(String packageName) {
        stopReelsHeartbeat(); // cancel any existing heartbeat first
        reelsHeartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                persistReelsState(true, packageName);
                Log.d(TAG, "[REELS_STATE] Heartbeat: refreshed timestamp for " + packageName);
                mainHandler.postDelayed(this, REELS_HEARTBEAT_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(reelsHeartbeatRunnable, REELS_HEARTBEAT_INTERVAL_MS);
        Log.d(TAG, "[REELS_STATE] Heartbeat started (interval=" + REELS_HEARTBEAT_INTERVAL_MS + "ms) for " + packageName);
    }

    /**
     * Stops the Reels state heartbeat. Safe to call when no heartbeat is active.
     */
    private void stopReelsHeartbeat() {
        if (reelsHeartbeatRunnable != null) {
            mainHandler.removeCallbacks(reelsHeartbeatRunnable);
            reelsHeartbeatRunnable = null;
            Log.d(TAG, "[REELS_STATE] Heartbeat stopped");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String eventTypeName(int type) {
        switch (type) {
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: return "CONTENT_CHANGED";
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:   return "STATE_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:          return "VIEW_SCROLLED";
            case AccessibilityEvent.TYPE_VIEW_CLICKED:           return "VIEW_CLICKED";
            default:                                             return "TYPE_" + type;
        }
    }
}
