package com.breqk;

/*
 * ReelsInterventionService
 * -------------------------
 * AccessibilityService that monitors Instagram Reels and YouTube Shorts scroll behavior.
 * When the user scrolls beyond a configurable threshold, it shows an intervention popup
 * that asks "Still here intentionally?" with two options:
 *   - "Lock In"      → exits to Android home screen (GLOBAL_ACTION_HOME), resets counter to 0
 *   - "Take a Break" → deep-links to instagram://feed (home feed), resets counter to 0
 *
 * Filter logcat with: adb logcat -s REELS_WATCH
 * For a one-line decision trace per scroll: adb logcat -s REELS_WATCH | grep SCROLL_DECISION
 *
 * Architecture:
 *   AccessibilityService (this) → onAccessibilityEvent → handleReelsScrollEvent
 *     → isFullScreenReelsViewPager (visibility + bounds check — core false-positive fix)
 *     → isReelsLayout / isShortsLayout (view ID search + full-screen verification)
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
 * Scroll threshold is persisted in SharedPreferences ("breqk_prefs", key "scroll_threshold")
 * and can be updated from React Native via SettingsModule.saveScrollThreshold() or
 * VPNModule.setScrollThreshold() without restarting the service.
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
    // One-line decision trace: adb logcat -s REELS_WATCH | grep SCROLL_DECISION
    private static final String TAG = "REELS_WATCH";

    // Target package names
    private static final String PKG_INSTAGRAM = "com.instagram.android";
    private static final String PKG_YOUTUBE   = "com.google.android.youtube";
    private Runnable autoDismissRunnable = null;

    /**
     * Default scroll count before showing the intervention popup.
     * Overridable via SharedPreferences key "scroll_threshold" (range 1-20).
     *
     * To tune during testing: change this constant and rebuild, or write to
     * SharedPreferences via SettingsModule.saveScrollThreshold() from React Native.
     */
    private static final int DEFAULT_SCROLL_THRESHOLD = 4;

    /**
     * Minimum milliseconds between two counted scroll events.
     *
     * A single physical Reel swipe fires 3–4 TYPE_VIEW_SCROLLED events within ~100–200 ms.
     * Any event that arrives within this window after the last counted scroll is treated as
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

    /** Running scroll count within the current Reels/Shorts session. */
    private int reelsScrollCount = 0;

    /**
     * Timestamp of the last scroll event that was counted toward the threshold.
     * Used to debounce duplicate events from a single physical swipe.
     */
    private long lastScrollTimestamp = 0;

    /**
     * True when user was in a Reels/Shorts layout on the last event.
     * Lets us detect when they navigate away and reset the counter.
     */
    private boolean wasInReelsLayout = false;

    /**
     * Guard against stacking multiple overlays if scroll events fire
     * rapidly around the threshold.
     */
    private boolean interventionShowing = false;

    /** Currently visible intervention overlay, or null. */
    private View interventionView = null;

    /** Handler on main looper — WindowManager calls must be on the UI thread. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // =========================================================================
    // Service lifecycle
    // =========================================================================

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();

        // TYPE_VIEW_SCROLLED: needed to count scrolls within Reels/Shorts
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
        Log.d(TAG, "  scroll threshold (default): " + DEFAULT_SCROLL_THRESHOLD
                + " (override via breqk_prefs/scroll_threshold)");
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
        Log.d(TAG, "onInterrupt: service interrupted, intervention dismissed");
    }

    // =========================================================================
    // Scroll detection
    // =========================================================================

    /**
     * Core routing method. Determines if the user is in a Reels/Shorts layout,
     * maintains the scroll counter, and triggers the intervention when needed.
     *
     * IMPORTANT: Scroll events are only counted when the user is actively watching
     * Reels (Instagram) or Shorts (YouTube). "Reels" means a full-screen vertical
     * video with like/comment/share buttons on the right and account info, music,
     * and caption at the bottom. Scrolling anywhere else in Instagram (home feed,
     * Explore, DMs, profiles, stories) does NOT count.
     *
     * Detection is re-verified on EVERY scroll event to prevent false positives
     * from Instagram's back-stack caching. Two strategies are used:
     *   1. Fast path: check if the scroll event source itself is the Reels ViewPager,
     *      then verify it is truly full-screen via isFullScreenReelsViewPager()
     *   2. Slow path: traverse the accessibility tree from root, then verify full-screen
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
            return; // don't count navigation events as scrolls
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
                Log.d(TAG, "Scroll outside Reels/Shorts — resetting scroll count");
                resetReelsState();
            }
            // Emit SCROLL_DECISION line even for ignored scrolls (useful for debugging false positives)
            Log.d(TAG, "SCROLL_DECISION pkg=" + packageName
                    + " fastPath=" + usedFastPath
                    + " confirmedInReels=false"
                    + " action=IGNORED");
            return;
        }

        wasInReelsLayout = true;

        // --- Debounce: ignore rapid-fire duplicate events from the same physical swipe ---
        long now = System.currentTimeMillis();
        long elapsed = now - lastScrollTimestamp;
        if (elapsed < SCROLL_DEBOUNCE_MS) {
            Log.d(TAG, "SCROLL_DECISION pkg=" + packageName
                    + " fastPath=" + usedFastPath
                    + " confirmedInReels=true"
                    + " DEBOUNCED (elapsed=" + elapsed + "ms < " + SCROLL_DEBOUNCE_MS + "ms)"
                    + " count=" + reelsScrollCount + "/" + getScrollThreshold());
            return;
        }
        lastScrollTimestamp = now;
        reelsScrollCount++;

        Log.d(TAG, "SCROLL_DECISION pkg=" + packageName
                + " fastPath=" + usedFastPath
                + " confirmedInReels=true"
                + " count=" + reelsScrollCount + "/" + getScrollThreshold()
                + " interventionShowing=" + interventionShowing);

        if (reelsScrollCount >= getScrollThreshold() && !interventionShowing) {
            interventionShowing = true;
            Log.i(TAG, "Scroll threshold reached — triggering intervention for " + packageName);
            triggerIntervention(packageName);
        }
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
     * scroll in those screens would be wrongly counted.
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

    /** Clears all scroll tracking state. Called on layout exit or intervention resolution. */
    private void resetReelsState() {
        reelsScrollCount = 0;
        wasInReelsLayout = false;
        interventionShowing = false;
        lastScrollTimestamp = 0;
        Log.d(TAG, "resetReelsState: cleared (count=0, wasInReels=false, interventionShowing=false, lastScrollTimestamp=0)");
    }

    /**
     * Reads the scroll threshold from SharedPreferences.
     * Default: DEFAULT_SCROLL_THRESHOLD. Clamped to 1-20.
     * Key: "scroll_threshold" in "breqk_prefs".
     */
    private int getScrollThreshold() {
        SharedPreferences prefs = getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
        int threshold = prefs.getInt("scroll_threshold", DEFAULT_SCROLL_THRESHOLD);
        return Math.max(1, Math.min(20, threshold));
    }

    // =========================================================================
    // Intervention overlay
    // =========================================================================

    /**
     * Shows the intervention popup card CENTERED on the screen via WindowManager.
     *
     * Uses TYPE_ACCESSIBILITY_OVERLAY (no SYSTEM_ALERT_WINDOW needed; AccessibilityServices
     * get this permission automatically).
     *
     * Layout structure: FrameLayout (full screen, transparent) wraps the card LinearLayout,
     * which is centered via layout_gravity="center". This is the correct pattern for
     * WindowManager overlays — margins on the root view are not respected by WindowManager,
     * so the inner card carries the horizontal margins (24dp each side).
     *
     * FLAG_NOT_TOUCH_MODAL + FLAG_WATCH_OUTSIDE_TOUCH: overlay captures its own touches
     * while passing outside-overlay touches through to the app beneath.
     * FLAG_NOT_FOCUSABLE is intentionally absent — without focus the buttons are untappable.
     *
     * Button behaviors:
     *   "Lock In"      → dismiss, fire GLOBAL_ACTION_HOME (exits to Android home screen), reset counter
     *   "Take a Break" → dismiss, deep-link instagram://feed (fallback: GLOBAL_ACTION_BACK), reset counter
     *   [ignored 8s]   → auto-dismiss, reset state, fire GLOBAL_ACTION_BACK
     *
     * @param pkg PKG_INSTAGRAM or PKG_YOUTUBE — used for platform-specific subtitle text
     */
    private void triggerIntervention(final String pkg) {
        final int scrollCountSnapshot = reelsScrollCount;

        mainHandler.post(() -> {
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                Log.e(TAG, "triggerIntervention: WindowManager null, cannot show overlay");
                interventionShowing = false;
                return;
            }

            // MATCH_PARENT so the FrameLayout fills the screen.
            // Gravity.BOTTOM: the inner bottom sheet LinearLayout rises from the bottom edge.
            // Design matches stitch_screens/3_reels_intervention.jpg.
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

            // Update headline to be platform-specific ("Reels Detected" / "Shorts Detected")
            TextView titleView = interventionView.findViewById(R.id.intervention_title);
            String contentType = pkg.equals(PKG_YOUTUBE) ? "Shorts" : "Reels";
            titleView.setText(contentType + " Detected");
            Log.d(TAG, "triggerIntervention: title set to '" + contentType + " Detected'");

            /*
             * btn_lock_in → "Return to Feed"
             * --------------------------------
             * NEW behavior (swapped from old "Take a Break"):
             *   Deep-link to the app's home feed (instagram://feed or youtube://).
             *   Lets the user continue using the app in a less addictive section.
             */
            Button btnLockIn = interventionView.findViewById(R.id.btn_lock_in);
            btnLockIn.setText("Return to Feed");
            btnLockIn.setOnClickListener(v -> {
                Log.i(TAG, "return_to_feed tapped for " + pkg + " — navigating to home feed");
                dismissIntervention();
                resetReelsState();

                // Deep-link to the app's home feed
                String feedUri = pkg.equals(PKG_YOUTUBE) ? "youtube://" : "instagram://feed";
                android.content.Intent feedIntent = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(feedUri));
                feedIntent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(feedIntent);
                    Log.d(TAG, "return_to_feed: launched deep link " + feedUri);
                } catch (Exception e) {
                    Log.w(TAG, "return_to_feed: deep link failed, firing GLOBAL_ACTION_BACK", e);
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
            });

            /*
             * btn_take_break → "Force Close <AppName>"
             * ------------------------------------------
             * NEW behavior (swapped from old "Lock In"):
             *   Fire GLOBAL_ACTION_HOME to exit the app entirely to the Android home screen.
             *   Button text is set dynamically to name the specific app being closed.
             */
            Button btnTakeBreak = interventionView.findViewById(R.id.btn_take_break);
            String appDisplayName = pkg.equals(PKG_YOUTUBE) ? "YouTube" : "Instagram";
            btnTakeBreak.setText("Force Close " + appDisplayName);
            btnTakeBreak.setOnClickListener(v -> {
                Log.i(TAG, "force_close tapped for " + pkg + " — going to Android home screen");
                dismissIntervention();
                resetReelsState();
                performGlobalAction(GLOBAL_ACTION_HOME);
            });

            // No auto-dismiss — user must make an explicit choice (cleaner UX)

            windowManager.addView(interventionView, params);
            Log.i(TAG, "overlay shown (bottom-sheet) for " + pkg
                    + " contentType=" + contentType
                    + " count=" + scrollCountSnapshot
                    + " threshold=" + getScrollThreshold());
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

        if (autoDismissRunnable != null) {
            mainHandler.removeCallbacks(autoDismissRunnable);
            autoDismissRunnable = null;
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
