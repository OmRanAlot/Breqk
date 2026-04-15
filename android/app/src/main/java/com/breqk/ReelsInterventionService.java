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
 * GRACE PERIOD: On each fresh entry into Reels/Shorts, a grace period begins. The heartbeat
 * and budget accumulation are deferred until the user's first scroll. This lets the user
 * watch the first video they land on (YouTube auto-open, friend's link, home feed tap)
 * without being blocked. The grace period ends on the first TYPE_VIEW_SCROLLED event.
 * Filter: adb logcat -s REELS_WATCH | grep GRACE
 *
 * Filter logcat with: adb logcat -s REELS_WATCH
 * For budget-related decisions: adb logcat -s REELS_WATCH | grep BUDGET
 * For scroll decisions: adb logcat -s REELS_WATCH | grep SCROLL_DECISION
 * For grace period: adb logcat -s REELS_WATCH | grep GRACE
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
 * Root cause of false positives (FIXED — two generations):
 *
 *   Generation 1 (back-stack nodes):
 *   Instagram keeps the Reels ViewPager in memory on its back stack even after the user
 *   navigates away. The old code only checked view ID existence; the new code additionally
 *   checks isVisibleToUser() and screen-coverage bounds to confirm the viewer is actually
 *   active and full-screen before counting any scroll.
 *
 *   Generation 2 (home screen / app switch):
 *   When the user presses Home or switches apps, the heartbeat kept running because no
 *   accessibility events from Instagram/YouTube fired. Fixed by:
 *   (a) removing the packageNames filter from the XML config so TYPE_WINDOW_STATE_CHANGED
 *       events from all apps are received — detecting app switches immediately;
 *   (b) adding isStillInReels() verification to each heartbeat tick — if the active window
 *       is no longer the Reels/Shorts app or layout, the heartbeat stops and state resets.
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
import android.content.Intent;
import android.content.ComponentName;
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

import com.breqk.reels.FrameworkClassFilter;
import com.breqk.reels.FullScreenCheck;
import com.breqk.reels.ShortFormIds;

import java.util.List;

public class ReelsInterventionService extends AccessibilityService {

    // Filter logcat with: adb logcat -s REELS_WATCH
    // Budget decisions:          adb logcat -s REELS_WATCH | findstr "BUDGET"
    // Scroll decisions:          adb logcat -s REELS_WATCH | findstr "SCROLL_DECISION"
    // Grace period:              adb logcat -s REELS_WATCH | findstr "GRACE"
    // Shorts active state:       adb logcat -s REELS_WATCH | findstr "SHORTS_ACTIVE"
    // Shorts class discovery:    adb logcat -s REELS_WATCH | findstr "SHORTS_CLASS"
    // Shorts text signal:        adb logcat -s REELS_WATCH | findstr "SHORTS_TEXT"
    // YouTube tree dump:         adb logcat -s REELS_WATCH | findstr "YT_TREE_DUMP"
    // YouTube tier decisions:    adb logcat -s REELS_WATCH | findstr "TIER"
    private static final String TAG = "REELS_WATCH";

    // Target package names — must stay in sync with AppEventRouter.MONITORED_PACKAGES
    private static final String PKG_INSTAGRAM = "com.instagram.android";
    private static final String PKG_YOUTUBE = "com.google.android.youtube";
    private static final String PKG_TIKTOK = "com.zhiliaoapp.musically";

    /**
     * AppEventRouter — dispatches events to LaunchInterceptor (launch popup) and
     * ContentFilter (short-form ejection) independently of the scroll budget logic below.
     * Initialized in onServiceConnected(), destroyed in onInterrupt().
     */
    private AppEventRouter eventRouter;

    // -----------------------------------------------------------------------
    // YouTube Shorts view ID constants — now consolidated in ShortFormIds
    // -----------------------------------------------------------------------
    // All view ID arrays and full-screen threshold constants have been moved to
    // com.breqk.reels.ShortFormIds (Step 1 of reels-intervention-refactor).
    // This kills Bug B1 — a single edit to ShortFormIds propagates to both
    // ReelsInterventionService and ContentFilter automatically.
    //
    // To discover new IDs after a YouTube update:
    //   adb logcat -s REELS_WATCH | findstr "YT_TREE_DUMP"

    /**
     * Minimum milliseconds between two processed scroll events.
     *
     * A single physical Reel swipe fires 3–4 TYPE_VIEW_SCROLLED events within
     * ~100–200 ms.
     * Any event that arrives within this window after the last processed scroll is
     * treated as
     * a duplicate from the same physical swipe and is ignored.
     *
     * 600 ms is chosen because real inter-swipe time is always >600 ms in practice,
     * while intra-swipe duplicate events arrive within ~200 ms.
     */
    private static final long SCROLL_DEBOUNCE_MS = 600;

    // Full-screen detection thresholds are now in ShortFormIds:
    //   ShortFormIds.MIN_WIDTH_RATIO  = 0.90f
    //   ShortFormIds.MIN_HEIGHT_RATIO = 0.70f
    //   ShortFormIds.MAX_TOP_OFFSET_PX = 200
    // FullScreenCheck.isFullScreen() uses these thresholds automatically.

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
     * Tracks whether YouTube Shorts was detected as active on the last check.
     * Used by notifyShortsState() to emit [SHORTS_ACTIVE] only on transitions
     * (false→true or true→false), not on every scroll event.
     */
    private boolean shortsCurrentlyDetected = false;

    /**
     * Guard against stacking multiple overlays if scroll events fire
     * rapidly around the budget exhaustion boundary.
     */
    private boolean interventionShowing = false;

    /**
     * Grace period flag. When true, the user just entered Reels/Shorts and is
     * watching the first video. Budget accumulation and heartbeat are deferred
     * until the first TYPE_VIEW_SCROLLED event fires (indicating the user is
     * actively scrolling to more content, not just watching a single video).
     *
     * Resets to true on each fresh entry into Reels/Shorts.
     * Set to false on the first scroll event.
     *
     * Filter: adb logcat -s REELS_WATCH | findstr "GRACE"
     */
    private boolean inGracePeriod = false;

    /** Currently visible intervention overlay, or null. */
    private View interventionView = null;

    /** Handler on main looper — WindowManager calls must be on the UI thread. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Shared utility for detecting system overlay packages and Android framework class events.
     * Holds the lazily-resolved launcher package cache (see FrameworkClassFilter).
     * Initialized once — single instance per service lifecycle.
     */
    private final FrameworkClassFilter frameworkClassFilter = new FrameworkClassFilter();

    /**
     * Timestamp of the last YouTube tree dump (for rate limiting).
     * Diagnostic dumps are limited to once every 10s to prevent log spam.
     */
    private long lastYtTreeDump = 0;
    private static final long YT_TREE_DUMP_INTERVAL_MS = 10_000;

    // --- Reels state persistence (shared with AppUsageMonitor) ---

    /** SharedPreferences key: whether user is currently viewing Reels/Shorts */
    private static final String PREF_IS_IN_REELS = "is_in_reels";
    /**
     * SharedPreferences key: timestamp of last Reels state update (for staleness
     * check)
     */
    private static final String PREF_IS_IN_REELS_TIMESTAMP = "is_in_reels_timestamp";
    /** SharedPreferences key: which app is in Reels (package name) */
    private static final String PREF_IS_IN_REELS_PACKAGE = "is_in_reels_package";

    /**
     * Heartbeat interval: how often we refresh the Reels state timestamp while
     * the user stays in Reels without scrolling. Must be less than the staleness
     * threshold in AppUsageMonitor (5s) to avoid false expiration.
     *
     * Also used as the scroll budget accumulation interval — each heartbeat tick
     * adds REELS_HEARTBEAT_INTERVAL_MS to scroll_time_used_ms in SharedPreferences.
     * Reduced from 2000ms → 1000ms (Phase 1 latency fix) for faster budget
     * accumulation and more responsive is_in_reels state freshness.
     */
    private static final long REELS_HEARTBEAT_INTERVAL_MS = 1000;

    /** Runnable that periodically refreshes the Reels state timestamp. */
    private Runnable reelsHeartbeatRunnable = null;

    /**
     * Package name currently in Reels (set when heartbeat starts).
     * Used by accumulateScrollBudget() to trigger immediate intervention overlay
     * when budget becomes exhausted during passive viewing (no scroll needed).
     */
    private String currentReelsPackage = "";

    // --- Scroll budget accumulation ---
    // Budget tracking is done HERE (not in AppUsageMonitor) because this service
    // is always running as an AccessibilityService, while
    // MyVpnService/AppUsageMonitor
    // may not be running (foreground service restrictions on Android 12+).

    /**
     * SharedPreferences keys for scroll budget (shared with AppUsageMonitor and
     * VPNModule)
     */
    private static final String PREF_SCROLL_TIME_USED_MS = "scroll_time_used_ms";
    private static final String PREF_SCROLL_WINDOW_START = "scroll_window_start_time";
    private static final String PREF_SCROLL_ALLOWANCE_MIN = "scroll_allowance_minutes";
    private static final String PREF_SCROLL_WINDOW_MIN = "scroll_window_minutes";
    private static final String PREF_SCROLL_EXHAUSTED_AT = "scroll_budget_exhausted_at";

    // =========================================================================
    // Service lifecycle
    // =========================================================================

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null)
            info = new AccessibilityServiceInfo();

        // TYPE_VIEW_SCROLLED: needed to detect scrolls within Reels/Shorts
        // TYPE_WINDOW_STATE_CHANGED: needed to detect layout changes (entering/leaving
        // Reels)
        // TYPE_WINDOW_CONTENT_CHANGED is intentionally excluded — too noisy, causes
        // false positives
        // on home feed embeds; layout re-check only happens on STATE_CHANGED (tab
        // navigation)
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        // FLAG_REPORT_VIEW_IDS is CRITICAL: without it
        // findAccessibilityNodeInfosByViewId() returns nothing
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);

        // Initialize the event router — handles LaunchInterceptor + ContentFilter
        // independently from the scroll budget logic further below.
        eventRouter = new AppEventRouter(this, this);

        Log.d(TAG, "=== ReelsInterventionService CONNECTED ===");
        Log.d(TAG, "  watching (budget): " + PKG_INSTAGRAM + ", " + PKG_YOUTUBE);
        Log.d(TAG, "  watching (router): " + AppEventRouter.MONITORED_PACKAGES);
        Log.d(TAG, "  packageFilter: NONE (receives events from all apps for app-switch detection)");
        Log.d(TAG, "  trigger: scroll budget exhaustion (from SharedPreferences)");
        Log.d(TAG, "  full-screen thresholds: widthRatio>=" + ShortFormIds.MIN_WIDTH_RATIO
                + " heightRatio>=" + ShortFormIds.MIN_HEIGHT_RATIO
                + " topOffset<=" + ShortFormIds.MAX_TOP_OFFSET_PX + "px");
        Log.d(TAG, "  eventTypes: VIEW_SCROLLED | WINDOW_STATE_CHANGED (CONTENT_CHANGED excluded from budget)");
        Log.d(TAG, "  false-positive guards: heartbeat isStillInReels() + app-switch detection");
        Log.d(TAG, "  AppEventRouter: LaunchInterceptor + ContentFilter (blockShortForm / launchPopup flags)");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null)
            return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null)
            return;

        String packageName = pkg.toString();

        // ── AppEventRouter dispatch ────────────────────────────────────────────────
        // Route to LaunchInterceptor (launch popup) and ContentFilter (short-form ejection)
        // before any scroll-budget logic. These two features are fully independent of
        // each other and of the budget system below.
        // eventRouter fast-exits for non-monitored packages (O(1) HashSet lookup).
        if (eventRouter != null) {
            eventRouter.onAccessibilityEvent(event);
        }

        // --- App-switch detection (defense in depth for false-positive fix) ---
        // When the user is in Reels and a DIFFERENT app comes to foreground
        // (e.g., Android launcher via Home button, or any other app via recents),
        // immediately reset Reels state. Without this, the heartbeat would keep
        // running and accumulating scroll budget on the home screen.
        // Note: packageNames filter was removed from the XML config so we receive
        // TYPE_WINDOW_STATE_CHANGED from all packages, not just Instagram/YouTube.
        //
        // [P1-FIX] System overlays (IME / keyboard, status bar, etc.) fire
        // TYPE_WINDOW_STATE_CHANGED with their own package name even though the user
        // is still in YouTube Shorts or Instagram Reels. Treating these as a real
        // app switch resets the heartbeat and prevents budget accumulation.
        // isSystemOverlayPackage() exempts known system overlays from this reset.
        if (wasInReelsLayout
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && !packageName.equals(PKG_INSTAGRAM)
                && !packageName.equals(PKG_YOUTUBE)) {

            // [STICKY-FIX] If the intervention overlay is currently visible, ignore ALL
            // app-switch events regardless of source. The overlay must only be dismissed
            // by explicit user action (Lock In / Take a Break buttons) or a budget window
            // rollover — never by ambient window-state events from the launcher, Breqk's
            // own WindowManager attachment, or any other background package.
            // Without this guard, the overlay disappears ~1s after appearing because the
            // overlay's own attachment fires TYPE_WINDOW_STATE_CHANGED with com.breqk (or
            // the home launcher behind it), which was previously treated as a real app switch.
            if (interventionShowing) {
                Log.d(TAG, "[STICKY-FIX] Suppressed app-switch reset from pkg=" + packageName
                        + " className=" + event.getClassName()
                        + " — intervention overlay is active, ignoring");
                return;
            }

            // [STICKY-FIX] Also skip the reset when the event comes from a framework class
            // (android.view.*, android.widget.*) rather than a real Activity. These are
            // floating system overlays, not genuine foreground transitions.
            // Delegates to FrameworkClassFilter.isFrameworkClass() — shared with ContentFilter.
            String className = event.getClassName() != null ? event.getClassName().toString() : "";
            if (FrameworkClassFilter.isFrameworkClass(className)) {
                Log.d(TAG, "[APP_SWITCH] Ignoring framework-class event: pkg=" + packageName
                        + " class=" + className + " — Reels state preserved");
                return;
            }

            if (frameworkClassFilter.isSystemOverlayPackage(packageName, this, TAG)) {
                Log.d(TAG, "[APP_SWITCH] Ignoring system overlay package: " + packageName
                        + " — Reels state preserved");
            } else {
                Log.i(TAG, "[APP_SWITCH] Detected app switch while in Reels: newPkg=" + packageName
                        + " className=" + className
                        + " — resetting Reels state to prevent false-positive budget accumulation");
                resetReelsState();
                // [HOME_DISMISS] Also tell MyVpnService to dismiss its AppUsageMonitor overlay.
                // ReelsInterventionService receives TYPE_WINDOW_STATE_CHANGED events much faster
                // (~0ms) than the 1s polling loop in AppUsageMonitor, so we can proactively
                // dismiss any delay overlay here instead of waiting for the next poll tick.
                Log.i(TAG, "[HOME_DISMISS] Sending DISMISS_OVERLAY intent to MyVpnService for newPkg=" + packageName);
                Intent dismissIntent = new Intent(this, MyVpnService.class);
                dismissIntent.setAction("DISMISS_OVERLAY");
                try {
                    startService(dismissIntent);
                } catch (Exception e) {
                    Log.w(TAG, "[HOME_DISMISS] Failed to send DISMISS_OVERLAY intent", e);
                }
            }
            return;
        }

        // Only process scroll/state events for scroll-budget logic from Instagram or YouTube.
        // TikTok is handled entirely by AppEventRouter (ContentFilter) above — no budget tracking.
        if (!packageName.equals(PKG_INSTAGRAM) && !packageName.equals(PKG_YOUTUBE))
            return;

        // Per-app policy check: skip if reels_detection is disabled for this app.
        // Uses BreqkPrefs.isFeatureEnabled() which resolves active mode overrides → base policy.
        if (!BreqkPrefs.isFeatureEnabled(this, packageName, BreqkPrefs.FEATURE_REELS_DETECTION)) {
            Log.d(TAG, "[POLICY] reels_detection disabled for " + packageName + " — skipping event");
            // If we were tracking this app's Reels state, reset it
            if (wasInReelsLayout) {
                resetReelsState();
            }
            return;
        }

        handleReelsScrollEvent(event, packageName);
    }

    @Override
    public void onInterrupt() {
        // Service interrupted (e.g. user revoked permission) — clean up any visible overlay
        dismissIntervention();
        // Clear Reels state so AppUsageMonitor doesn't keep accumulating budget
        persistReelsState(false, "");
        stopReelsHeartbeat();
        // Destroy AppEventRouter subsystems (LaunchInterceptor + ContentFilter)
        if (eventRouter != null) {
            eventRouter.onDestroy();
            eventRouter = null;
        }
        Log.d(TAG, "onInterrupt: service interrupted, intervention dismissed, reels state cleared, router destroyed");
    }

    // =========================================================================
    // Scroll detection + budget check
    // =========================================================================

    /**
     * Core routing method. Determines if the user is in a Reels/Shorts layout,
     * checks whether the scroll budget is exhausted, and triggers the intervention
     * when the budget is used up.
     *
     * IMPORTANT: Scroll events are only processed when the user is actively
     * watching
     * Reels (Instagram) or Shorts (YouTube). "Reels" means a full-screen vertical
     * video with like/comment/share buttons on the right and account info, music,
     * and caption at the bottom. Scrolling anywhere else in Instagram (home feed,
     * Explore, DMs, profiles, stories) does NOT trigger the popup.
     *
     * The popup fires based on scroll budget exhaustion (time-based), NOT scroll
     * count.
     * Budget state is read from SharedPreferences, written by AppUsageMonitor.
     *
     * One SCROLL_DECISION log line is emitted per scroll event for easy debugging:
     * adb logcat -s REELS_WATCH | grep SCROLL_DECISION
     */
    private void handleReelsScrollEvent(AccessibilityEvent event, String packageName) {
        int eventType = event.getEventType();

        // Skip content-change events entirely — too noisy, matches home feed embeds
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            return;

        // On tab/screen navigation: update the Reels layout flag and reset if we left
        // Reels.
        //
        // [P0-FIX] Previously, only the scroll handler could start the heartbeat (via
        // the !wasInReelsLayout guard). Because STATE_CHANGED always fires BEFORE the
        // first scroll event and sets wasInReelsLayout=true, the scroll handler's
        // heartbeat-start block was permanently skipped — budget never accumulated.
        // Fix: start the heartbeat here on the false→true transition so budget
        // accumulation begins as soon as the user navigates into Reels/Shorts.
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // --- TIER 0: Log the Activity/Fragment class name for discovery ---
            // event.getClassName() returns the class that just became active.
            // For YouTube, this is a Shorts-specific class when the Shorts tab is opened.
            // We always log it as [SHORTS_CLASS] so developers can identify the class name
            // and add it to YOUTUBE_SHORTS_CLASS_NAMES for O(1) detection.
            if (packageName.equals(PKG_YOUTUBE)) {
                String eventClass = event.getClassName() != null
                        ? event.getClassName().toString() : "(null)";
                Log.d(TAG, "[SHORTS_CLASS] YouTube STATE_CHANGED className=" + eventClass);

                // --- Floating overlay guard ---
                // YouTube keeps Shorts UI elements (reel_time_bar, etc.) resident in a
                // separate floating overlay window even when the user is on the home page.
                // When this overlay updates, it fires TYPE_WINDOW_STATE_CHANGED with a
                // generic Android class name (android.view.ViewGroup, android.widget.*).
                // Calling isShortsLayout() on that floating window returns a false positive
                // because getRootInActiveWindow() returns the overlay's root — which contains
                // reel_time_bar with full-screen bounds [0,0,1008,2244].
                //
                // Fix: skip tree traversal for YouTube events whose className is a generic
                // Android framework class. Real YouTube navigations always use proper
                // YouTube-specific class names (e.g., com.google.android.apps.youtube.*).
                // Generic class names = floating overlay, not real navigation.
                //
                // Evidence from log69.txt:
                //   Real Shorts:       className=com.google.android.apps.youtube.app.watchwhile.MainActivity
                //   Floating overlay:  className=android.view.ViewGroup  ← false positive
                if (FrameworkClassFilter.isFrameworkClass(eventClass)) {
                    Log.d(TAG, "[SHORTS_CLASS] Skipping tree traversal — generic class '" + eventClass
                            + "' indicates floating overlay (not real navigation)");
                    // Do not modify wasInReelsLayout — this is not a real navigation event.
                    return;
                }

                // If the class name is already in our known list, confirm Shorts immediately
                // without any tree traversal (fastest possible detection — Tier 0).
                // ShortFormIds.YOUTUBE_SHORTS_CLASS_NAMES is the single source of truth.
                for (String knownClass : ShortFormIds.YOUTUBE_SHORTS_CLASS_NAMES) {
                    if (knownClass.equals(eventClass)) {
                        Log.d(TAG, "isShortsLayout: TIER0 class name matched " + eventClass + " → confirmed Shorts");
                        if (!wasInReelsLayout) {
                            Log.d(TAG, "[GRACE] Entering Shorts (TIER0) — grace period started for " + packageName);
                            inGracePeriod = true;
                            notifyShortsState(true, "TIER0");
                            persistReelsState(true, packageName);
                            // Heartbeat and budget deferred until first scroll (grace period)
                        }
                        wasInReelsLayout = true;
                        return;
                    }
                }
            }

            AccessibilityNodeInfo root = getRootInActiveWindow();
            boolean inReelsLayout = packageName.equals(PKG_INSTAGRAM)
                    ? isReelsLayout(root)
                    : isShortsLayout(root);
            if (root != null)
                root.recycle();

            if (!inReelsLayout && wasInReelsLayout) {
                Log.d(TAG, "Left Reels/Shorts via state change — resetting");
                if (packageName.equals(PKG_YOUTUBE)) notifyShortsState(false, "state-change-exit");
                resetReelsState();
            } else if (inReelsLayout && !wasInReelsLayout) {
                // Entering Reels/Shorts via navigation (tab switch, deep link, etc.).
                // Grace period: let the user watch the first video they land on.
                // Heartbeat and budget accumulation are deferred until the first scroll.
                Log.d(TAG, "[GRACE] Entering Reels/Shorts (TIER1/TIER2) — grace period started for " + packageName);
                inGracePeriod = true;
                if (packageName.equals(PKG_YOUTUBE)) notifyShortsState(true, "TIER1/TIER2");
                persistReelsState(true, packageName);
                // Heartbeat and budget deferred until first scroll (grace period)
            }
            wasInReelsLayout = inReelsLayout;
            Log.d(TAG, "STATE_CHANGED: wasInReelsLayout=" + wasInReelsLayout + " pkg=" + packageName);
            return; // don't process navigation events as scrolls
        }

        // TYPE_VIEW_SCROLLED: re-verify Reels layout on EVERY scroll to prevent false
        // positives
        // from scrolling in non-Reels parts of the app (home feed, Explore, profiles,
        // DMs, etc.)
        if (eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED)
            return;

        boolean confirmedInReels = false;
        boolean usedFastPath = false;

        // --- Fast path: check if the scroll event source is the Reels/Shorts ViewPager
        // ---
        // O(1) — no tree traversal. Also verifies full-screen to catch back-stack
        // nodes.
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            String viewId = source.getViewIdResourceName();
            if (viewId != null) {
                if (packageName.equals(PKG_INSTAGRAM)
                        && viewId.equals("com.instagram.android:id/clips_viewer_view_pager")) {
                    // Verify this isn't a back-stack or embedded node.
                    // FullScreenCheck.isFullScreen delegates to ShortFormIds thresholds.
                    confirmedInReels = FullScreenCheck.isFullScreen(source, this, TAG);
                    usedFastPath = true;
                    Log.d(TAG, "Fast path: source=clips_viewer_view_pager fullScreen=" + confirmedInReels);
                } else if (packageName.equals(PKG_YOUTUBE)) {
                    // YouTube Shorts: loop over all known container IDs and verify
                    // full-screen bounds (matching Instagram's pattern for consistency).
                    // ShortFormIds.YOUTUBE_SHORTS_VIEW_IDS is the single source of truth.
                    for (String shortsId : ShortFormIds.YOUTUBE_SHORTS_VIEW_IDS) {
                        if (viewId.equals(shortsId)) {
                            confirmedInReels = FullScreenCheck.isFullScreen(source, this, TAG);
                            usedFastPath = true;
                            Log.d(TAG, "Fast path: source=" + shortsId
                                    + " fullScreen=" + confirmedInReels);
                            break;
                        }
                    }
                }
            }
            source.recycle();
        }

        // --- Slow path: full tree traversal if fast path didn't fire ---
        // Handles scroll events from child views within the Reels player (e.g., caption
        // scroll).
        if (!usedFastPath) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            confirmedInReels = packageName.equals(PKG_INSTAGRAM)
                    ? isReelsLayout(root)
                    : isShortsLayout(root);
            if (root != null)
                root.recycle();
            Log.d(TAG, "Slow path: tree traversal confirmedInReels=" + confirmedInReels);
        }

        // Update cached layout state and apply decision
        if (!confirmedInReels) {
            if (wasInReelsLayout) {
                Log.d(TAG, "Scroll outside Reels/Shorts — resetting state");
                resetReelsState();
            }
            // Emit SCROLL_DECISION line even for ignored scrolls (useful for debugging
            // false positives)
            Log.d(TAG, "SCROLL_DECISION pkg=" + packageName
                    + " fastPath=" + usedFastPath
                    + " confirmedInReels=false"
                    + " action=IGNORED");
            return;
        }

        // Persist Reels state for AppUsageMonitor to gate scroll budget accumulation
        if (!wasInReelsLayout) {
            // First detection of Reels via scroll (no prior STATE_CHANGED).
            // Start grace period — it will end below when we process this scroll
            // as the "first scroll" that terminates the grace period.
            inGracePeriod = true;
            persistReelsState(true, packageName);
            Log.d(TAG, "[GRACE] Entering Reels/Shorts (first-scroll) — grace period started for " + packageName);
        }
        wasInReelsLayout = true;

        // --- Grace period termination ---
        // The first scroll after entering Reels/Shorts ends the grace period.
        // This is where we start the heartbeat and budget accumulation that was
        // deferred on entry, allowing the user to watch the first video freely.
        if (inGracePeriod) {
            inGracePeriod = false;
            Log.i(TAG, "[GRACE] First scroll detected — grace period ended for " + packageName);

            // Now start what was deferred on entry:
            startReelsHeartbeat(packageName);
            accumulateScrollBudget();

            // Immediate budget check: if budget was already exhausted from a previous
            // session (or allowance=0), intervene now.
            long graceEndNow = System.currentTimeMillis();
            if (isScrollBudgetExhausted(graceEndNow) && !interventionShowing) {
                interventionShowing = true;
                Log.i(TAG, "[GRACE] Budget already exhausted after grace period — immediate intervention for " + packageName);
                triggerIntervention(packageName);
            }
        }

        // [FREE_BREAK] If the user has an active free break, allow all scrolling without
        // any intervention or budget accumulation. The heartbeat keeps running (to maintain
        // is_in_reels state for AppUsageMonitor) but skips budget accumulation via its own
        // guard inside accumulateScrollBudget().
        if (isFreeBreakActive()) {
            Log.d(TAG, "SCROLL_DECISION pkg=" + packageName
                    + " action=FREE_BREAK_ALLOW (break active — skipping budget/intervention)");
            return;
        }

        // --- Debounce: ignore rapid-fire duplicate events from the same physical swipe
        // ---
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
        // AppUsageMonitor persists budget state; we read it here to decide whether to
        // intervene.
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
     * 1. scroll_budget_exhausted_at > 0 (AppUsageMonitor flagged it)
     * 2. AND the current window hasn't expired yet (window hasn't rolled over)
     *
     * If the window has expired (now - windowStart >= windowDuration), the budget
     * is considered available again even if exhausted_at > 0, because
     * AppUsageMonitor
     * will reset it on its next tick.
     *
     * SharedPreferences keys read:
     * - scroll_budget_exhausted_at (long): timestamp when budget was exhausted, 0 =
     * not exhausted
     * - scroll_window_start_time (long): when the current budget window started
     * - scroll_window_minutes (int): window duration in minutes
     *
     * @param now Current system time in milliseconds
     * @return true if scroll budget is exhausted and window hasn't expired
     */
    private boolean isScrollBudgetExhausted(long now) {
        // [FREE_BREAK] During an active free break the budget is never exhausted —
        // the user has unrestricted scrolling access for the duration of the break.
        if (isFreeBreakActive()) {
            Log.d(TAG, "[BUDGET] [FREE_BREAK] isScrollBudgetExhausted: free break active → returning false");
            return false;
        }

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
     * Reels ViewPager — i.e., it is actually visible and covering most of the
     * screen.
     *
     * This is the PRIMARY guard against false positives. Instagram keeps
     * clips_viewer_view_pager in memory on its back stack even when the user
     * navigates
     * to the home feed, DMs, stories, profiles, or Explore. Without this check, any
     * scroll in those screens would be wrongly processed.
     *
     * Three signals must ALL pass:
     *
     * 1. isVisibleToUser() — Android marks off-screen back-stack views as not
     * visible.
     * This is the strongest and cheapest signal. If false, the node is in the back
     * stack and the user is NOT currently watching Reels.
     *
     * 2. Width coverage ≥ MIN_WIDTH_RATIO (90%) — The full-screen Reels player
     * spans
     * the entire screen width. Home feed reel cards span only a fraction.
     *
     * 3. Height coverage ≥ MIN_HEIGHT_RATIO (70%) — The full-screen player spans
     * most
     * of the screen height. 70% (not 90%) to allow for status bar + nav bar.
     *
     * 4. Top edge ≤ MAX_TOP_OFFSET_PX (200px) — The full-screen player starts at
     * the
     * very top of the screen. Embedded previews start further down.
     *
     * To tune thresholds: adjust MIN_WIDTH_RATIO, MIN_HEIGHT_RATIO,
     * MAX_TOP_OFFSET_PX
     * constants at the top of this file and rebuild.
     *
     * @param node The AccessibilityNodeInfo to check (clips_viewer_view_pager or
     *             similar)
     * @return true only if all four signals confirm this is the active full-screen
     *         viewer
     */
    /**
     * Returns true if the given node represents the full-screen Reels or Shorts viewer.
     *
     * @deprecated Replaced by {@link FullScreenCheck#isFullScreen(AccessibilityNodeInfo, Context, String)}.
     *             This method is kept as a thin delegate so existing callers within this file
     *             still compile — remove once all call sites are updated to use FullScreenCheck directly.
     */
    @Deprecated
    private boolean isFullScreenReelsViewPager(AccessibilityNodeInfo node) {
        return FullScreenCheck.isFullScreen(node, this, TAG);
    }

    // =========================================================================
    // Shorts active state logging
    // =========================================================================

    /**
     * Emits a [SHORTS_ACTIVE] log line whenever YouTube Shorts detection
     * transitions between detected and not-detected. Only fires on transitions
     * to avoid log spam on every scroll event.
     *
     * Filter command: adb logcat -s REELS_WATCH | findstr "SHORTS_ACTIVE"
     *
     * @param active true = Shorts just became active, false = Shorts just became inactive
     * @param tier   Which tier / reason triggered this transition (e.g. "TIER1", "reset")
     */
    private void notifyShortsState(boolean active, String tier) {
        if (active == shortsCurrentlyDetected) return; // no transition — skip
        shortsCurrentlyDetected = active;
        if (active) {
            Log.i(TAG, "[SHORTS_ACTIVE] active=true  tier=" + tier
                    + " pkg=" + PKG_YOUTUBE);
        } else {
            Log.i(TAG, "[SHORTS_ACTIVE] active=false reason=" + tier);
        }
    }

    // =========================================================================
    // YouTube Shorts text signal detection (TIER 3)
    // =========================================================================

    /**
     * Walks the YouTube accessibility tree (max depth 3) looking for any visible
     * node whose getText() or getContentDescription() contains the word "Shorts"
     * (case-insensitive). Returns true on the first match.
     *
     * This tier is resilient to YouTube view-ID renames: as long as the word
     * "Shorts" appears somewhere in the visible UI (navigation tab label, page
     * heading, content descriptions on action buttons), detection will succeed
     * even when TIER1 and TIER2 have no known IDs in the tree.
     *
     * Performance: early-exit on first match; max depth 3 limits traversal.
     * All node references are recycled before returning.
     *
     * Filter command: adb logcat -s REELS_WATCH | findstr "SHORTS_TEXT"
     *
     * @param root Root of the YouTube accessibility tree
     * @return true if "Shorts" text was found in any visible node
     */
    private boolean hasShortsTextSignal(AccessibilityNodeInfo root) {
        return scanNodeForShortsText(root, 0, 3);
    }

    /**
     * Recursive helper for hasShortsTextSignal().
     *
     * @param node     Current node to inspect
     * @param depth    Current recursion depth
     * @param maxDepth Maximum depth to traverse
     * @return true if "Shorts" text was found in this node or any descendant
     */
    private boolean scanNodeForShortsText(AccessibilityNodeInfo node, int depth, int maxDepth) {
        if (node == null || depth > maxDepth) return false;

        // Only inspect nodes that are actually visible to the user
        if (node.isVisibleToUser()) {
            CharSequence text = node.getText();
            CharSequence contentDesc = node.getContentDescription();

            boolean textMatch = text != null
                    && text.toString().toLowerCase().contains("shorts");
            boolean descMatch = contentDesc != null
                    && contentDesc.toString().toLowerCase().contains("shorts");

            if (textMatch || descMatch) {
                Log.d(TAG, "[SHORTS_TEXT] matched"
                        + (textMatch ? " text=\"" + text + "\"" : "")
                        + (descMatch ? " contentDesc=\"" + contentDesc + "\"" : "")
                        + " at depth=" + depth);
                return true;
            }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean found = scanNodeForShortsText(child, depth + 1, maxDepth);
                child.recycle();
                if (found) return true;
            }
        }
        return false;
    }

    // =========================================================================
    // System overlay detection (P1-FIX: prevent false APP_SWITCH resets from IME)
    // =========================================================================

    // =========================================================================
    // System overlay + framework class detection
    // =========================================================================
    //
    // APP_SWITCH_IGNORE_PACKAGES, isAndroidFrameworkClass(), and
    // isSystemOverlayPackage() have been moved to FrameworkClassFilter
    // (com.breqk.reels.FrameworkClassFilter) as part of Step 1 of the
    // reels-intervention-refactor. The `frameworkClassFilter` instance field above
    // replaces both the static array and the private helper methods.
    //
    // To add new false-reset packages:
    //   Edit FrameworkClassFilter.APP_SWITCH_IGNORE_PACKAGES[]
    //   adb logcat -s REELS_WATCH | findstr "APP_SWITCH" to observe culprits

    // =========================================================================
    // Layout detection
    // =========================================================================

    /**
     * Returns true if the current window is Instagram's full-screen Reels viewer.
     *
     * What qualifies as "Reels":
     * - Full-screen vertical video playing
     * - Like, comment, share/repost buttons visible on the right side
     * - Account name, music info, and caption visible at the bottom
     * - This is the dedicated Reels tab OR a reel opened from a profile/explore
     *
     * What does NOT qualify (popup must NOT fire):
     * - Home feed (even when it contains embedded reel previews or reel cards)
     * - Explore/Search tab
     * - DMs, Stories, profiles, settings, or any other screen
     *
     * Detection strategy:
     * 1. Find nodes with view ID "clips_viewer_view_pager" (primary) or
     * "clips_viewer_pager" (alternative seen in some Instagram versions)
     * 2. For each matched node, call isFullScreenReelsViewPager() to confirm
     * the node is actually visible and covering the full screen
     *
     * Text fallbacks ("Reel by", "Original audio") have been intentionally removed
     * because
     * those strings also appear on reels embedded in the Instagram home feed.
     *
     * If detection breaks after an Instagram update, use Android Studio Layout
     * Inspector
     * (View > Tool Windows > Layout Inspector → attach to com.instagram.android) to
     * find
     * the new ViewPager ID. Common Reels-related view IDs to look for:
     * - clips_viewer_view_pager (current primary)
     * - clips_viewer_pager (alternative)
     * - clips_tab (tab indicator — less reliable, exists outside full-screen
     * viewer)
     */
    private boolean isReelsLayout(AccessibilityNodeInfo root) {
        if (root == null) {
            Log.d(TAG, "isReelsLayout: root null → false");
            return false;
        }

        // Search all known Instagram Reels IDs (ShortFormIds.INSTAGRAM_REELS_IDS).
        // Each found node is validated via FullScreenCheck.isFullScreen() to prevent
        // false positives from back-stack nodes and home-feed embedded reel previews.
        for (String reelsId : ShortFormIds.INSTAGRAM_REELS_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(reelsId);
            if (nodes != null && !nodes.isEmpty()) {
                Log.d(TAG, "isReelsLayout: found " + nodes.size()
                        + " node(s) for " + reelsId + " — checking full-screen");
                for (AccessibilityNodeInfo n : nodes) {
                    if (FullScreenCheck.isFullScreen(n, this, TAG)) {
                        recycleAll(nodes);
                        Log.d(TAG, "isReelsLayout: YES — " + reelsId + " is full-screen");
                        return true;
                    }
                }
                recycleAll(nodes);
                Log.d(TAG, "isReelsLayout: " + reelsId + " found but none passed full-screen check → false");
            }
        }

        Log.d(TAG, "isReelsLayout: NO — no qualifying Reels view found");
        return false;
    }

    /**
     * Returns true if the current window is YouTube's Shorts player.
     *
     * Uses a three-tier detection strategy to handle YouTube's frequent view ID
     * changes:
     *
     * Tier 1 — Known container IDs: Loop YOUTUBE_SHORTS_VIEW_IDS, find nodes,
     *          verify full-screen bounds via isFullScreenReelsViewPager().
     *
     * Tier 2 — Structural heuristic: Check for Shorts-specific secondary signal IDs
     *          (reel_like_button, shorts_like_button, reel_comment_button) + ABSENCE
     *          of seekbar (seekbar exists in regular videos, not Shorts). Confirms
     *          only if the secondary signal element itself is full-screen.
     *
     * Tier 3 — Diagnostic dump: If both tiers fail, dump all YouTube view IDs to
     *          logcat (rate-limited) so developers can discover the current IDs.
     *          Filter: adb logcat -s REELS_WATCH | grep YT_TREE_DUMP
     *
     * If detection breaks after a YouTube update, run the YT_TREE_DUMP filter
     * while scrolling Shorts and update YOUTUBE_SHORTS_VIEW_IDS with the new IDs.
     */
    private boolean isShortsLayout(AccessibilityNodeInfo root) {
        if (root == null) {
            Log.d(TAG, "isShortsLayout: root null → false");
            return false;
        }

        // --- TIER 1: Known container view IDs with full-screen bounds check ---
        // ShortFormIds.YOUTUBE_SHORTS_VIEW_IDS is the single source of truth for these IDs.
        for (String viewId : ShortFormIds.YOUTUBE_SHORTS_VIEW_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
            if (nodes != null && !nodes.isEmpty()) {
                Log.d(TAG, "isShortsLayout: TIER1 found " + nodes.size()
                        + " node(s) for " + viewId + " — checking full-screen");
                for (AccessibilityNodeInfo n : nodes) {
                    if (FullScreenCheck.isFullScreen(n, this, TAG)) {
                        recycleAll(nodes);
                        Log.d(TAG, "isShortsLayout: TIER1 matched " + viewId
                                + " (full-screen) → true");
                        notifyShortsState(true, "TIER1");
                        return true;
                    }
                }
                recycleAll(nodes);
                Log.d(TAG, "isShortsLayout: TIER1 found " + viewId
                        + " but NOT full-screen");
            }
        }
        Log.d(TAG, "isShortsLayout: TIER1 — no known container IDs matched full-screen");

        // --- TIER 2: Secondary signals + no seekbar (structural heuristic) ---
        // Secondary signals (like button, comment button) exist in Shorts UI.
        // The seekbar is present in regular videos but absent in Shorts.
        // Combining these differentiates Shorts from regular video playback.
        boolean hasSecondarySignal = false;
        String matchedSecondaryId = null;
        boolean secondaryIsFullScreen = false;
        // ShortFormIds.YOUTUBE_SHORTS_SECONDARY_IDS is the single source of truth.
        for (String secId : ShortFormIds.YOUTUBE_SHORTS_SECONDARY_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(secId);
            if (nodes != null && !nodes.isEmpty()) {
                hasSecondarySignal = true;
                matchedSecondaryId = secId;
                // Check if the secondary signal node itself covers the full screen
                // (e.g., reel_time_bar covers [0,0][1008,2244] — the entire screen)
                for (AccessibilityNodeInfo n : nodes) {
                    if (FullScreenCheck.isFullScreen(n, this, TAG)) {
                        secondaryIsFullScreen = true;
                        break;
                    }
                }
                recycleAll(nodes);
                Log.d(TAG, "isShortsLayout: TIER2 secondary signal found: " + secId
                        + " fullScreen=" + secondaryIsFullScreen);
                break;
            }
        }

        if (hasSecondarySignal) {
            // Absence of seekbar differentiates Shorts from regular videos.
            // ShortFormIds.YOUTUBE_SEEKBAR_ID is the single source of truth.
            List<AccessibilityNodeInfo> seekbar = root.findAccessibilityNodeInfosByViewId(
                    ShortFormIds.YOUTUBE_SEEKBAR_ID);
            boolean hasSeekbar = seekbar != null && !seekbar.isEmpty();
            if (seekbar != null) recycleAll(seekbar);

            if (!hasSeekbar) {
                // Only confirm Shorts if the secondary signal itself is full-screen
                // (e.g., reel_time_bar covers the entire screen when Shorts is active).
                // We intentionally do NOT fall back to findFullScreenScrollableContainer
                // because YouTube's home feed RecyclerView is also full-screen and
                // scrollable, which caused false positives on the home page.
                if (secondaryIsFullScreen) {
                    Log.d(TAG, "isShortsLayout: TIER2 matched (secondary="
                            + matchedSecondaryId
                            + " is full-screen + no seekbar) → true");
                    notifyShortsState(true, "TIER2");
                    return true;
                }
                Log.d(TAG, "isShortsLayout: TIER2 secondary found + no seekbar, "
                        + "but secondary not full-screen → false");
            } else {
                Log.d(TAG, "isShortsLayout: TIER2 seekbar present → regular video, not Shorts");
            }
        } else {
            Log.d(TAG, "isShortsLayout: TIER2 — no secondary signals found");
        }

        // --- TIER 3: Visible text scan ---
        // Walk the accessibility tree (max depth 3) checking getText() and
        // getContentDescription() on every visible node for the string "Shorts".
        // This tier is resilient to view ID renames: as long as YouTube still renders
        // "Shorts" as visible text (tab label, page heading, content descriptions),
        // detection will work even if all view IDs above have changed.
        if (hasShortsTextSignal(root)) {
            Log.d(TAG, "isShortsLayout: TIER3 text scan matched → true");
            notifyShortsState(true, "TIER3");
            return true;
        }

        // --- TIER 4: Diagnostic dump (rate-limited) ---
        // When all detection tiers fail, dump the YouTube accessibility tree
        // view IDs to logcat so developers can discover the current IDs.
        dumpYouTubeTreeIfNeeded(root);

        Log.d(TAG, "isShortsLayout: all tiers failed → false");
        return false;
    }

    /**
     * Clears all scroll tracking state. Called on layout exit or intervention
     * resolution.
     * Note: reelsScrollCount has been removed — budget is time-based, not
     * count-based.
     */
    private void resetReelsState() {
        // [HOME_DISMISS] Dismiss the intervention overlay first — this is critical!
        // Without this call, the overlay view stays attached to WindowManager even
        // after all internal state flags are cleared. This was the root bug causing
        // the overlay to persist after navigating home.
        Log.d(TAG, "[HOME_DISMISS] resetReelsState: dismissing intervention overlay if visible");
        dismissIntervention();

        // Emit [SHORTS_ACTIVE] false transition before clearing state
        if (shortsCurrentlyDetected) notifyShortsState(false, "reset");
        wasInReelsLayout = false;
        interventionShowing = false;
        inGracePeriod = false;
        lastScrollTimestamp = 0;
        currentReelsPackage = "";
        // Clear Reels state in SharedPreferences so AppUsageMonitor stops reading active state
        persistReelsState(false, "");
        stopReelsHeartbeat();
        Log.d(TAG,
                "resetReelsState: cleared (wasInReels=false, interventionShowing=false, inGracePeriod=false, lastScrollTimestamp=0, currentReelsPackage='', reelsState=false)");
    }

    // =========================================================================
    // Intervention overlay
    // =========================================================================

    /**
     * Shows the "Time is up!" intervention popup via WindowManager.
     *
     * Uses TYPE_ACCESSIBILITY_OVERLAY (no SYSTEM_ALERT_WINDOW needed;
     * AccessibilityServices
     * get this permission automatically).
     *
     * Layout structure: FrameLayout (full screen, transparent) wraps the card
     * LinearLayout,
     * which is bottom-aligned. This is the correct pattern for WindowManager
     * overlays.
     *
     * The overlay shows:
     * - Title: "Time is up!"
     * - Single button: "Lock In" → fires GLOBAL_ACTION_HOME (exits to Android home
     * screen)
     * - The second button (btn_take_break) is hidden (View.GONE)
     *
     * FLAG_NOT_TOUCH_MODAL + FLAG_WATCH_OUTSIDE_TOUCH: overlay captures its own
     * touches
     * while passing outside-overlay touches through to the app beneath.
     * FLAG_NOT_FOCUSABLE is intentionally absent — without focus the buttons are
     * untappable.
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
            // Gravity.BOTTOM: the inner bottom sheet LinearLayout rises from the bottom
            // edge.
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            // BOTTOM gravity so the sheet anchors to the bottom of the screen
            params.gravity = Gravity.BOTTOM;

            interventionView = LayoutInflater.from(this)
                    .inflate(R.layout.overlay_reels_intervention, null);

            // --- Title: "Time is up!" ---
            TextView titleView = interventionView.findViewById(R.id.intervention_title);
            titleView.setText("Time is up!");
            Log.d(TAG, "triggerIntervention: title set to 'Time is up!'");

            // --- "Return to Feed" button (primary): navigate back within the app ---
            // Fires GLOBAL_ACTION_BACK to exit the full-screen Reels/Shorts viewer
            // and return to the app's main feed. Budget exhaustion state is NOT cleared —
            // if the user navigates back to Reels, the overlay re-fires on the next scroll.
            // Button btnReturnToFeed = interventionView.findViewById(R.id.btn_lock_in);
            // btnReturnToFeed.setText("Return to Feed");
            // btnReturnToFeed.setOnClickListener(v -> {
            // Log.i(TAG, "return_to_feed tapped for " + pkg + " — pressing BACK to exit
            // Reels viewer");
            // dismissIntervention();
            // // Reset only overlay-related flags so re-detection works if user re-enters
            // // Reels.
            // // Do NOT call resetReelsState() — that would clear the budget exhaustion
            // // tracking.
            // // Budget stays exhausted; re-entering Reels will re-trigger the overlay
            // // immediately.
            // wasInReelsLayout = false;
            // // interventionShowing is already set to false by dismissIntervention()
            // Log.d(TAG, "return_to_feed: wasInReelsLayout=false, budget exhaustion
            // preserved");
            // // performGlobalAction(GLOBAL_ACTION_BACK);
            // goToHomePage();
            // });

            // --- "Lock In" button (secondary): exit to Android home screen ---
            // Stronger action — user leaves the app entirely via GLOBAL_ACTION_HOME.
            // Budget exhaustion state is NOT cleared here either — it resets only when
            // the configured time window rolls over (handled by AppUsageMonitor).
            Button btnLockIn = interventionView.findViewById(R.id.btn_take_break);
            btnLockIn.setText("Lock In");
            btnLockIn.setVisibility(View.VISIBLE);
            btnLockIn.setOnClickListener(v -> {
                Log.i(TAG, "lock_in tapped for " + pkg + " — going to Android home screen");
                dismissIntervention();
                resetReelsState();
                performGlobalAction(GLOBAL_ACTION_HOME);
            });

            windowManager.addView(interventionView, params);
            Log.i(TAG, "[BUDGET] Overlay shown (Time is up!) for " + pkg);
        });
    }

    /**
     * Removes the intervention overlay. Safe to call when no overlay is showing.
     *
     * [STICKY-FIX] Logs the immediate caller so any accidental dismiss path is
     * visible in logcat. Filter: adb logcat -s REELS_WATCH | findstr "DISMISS_CALL"
     */
    private void dismissIntervention() {
        if (interventionView == null) {
            Log.d(TAG, "[DISMISS_CALL] dismissIntervention: no overlay active (interventionView=null), skipping");
            return;
        }
        // Log call site for debugging: shows which method triggered the dismiss.
        StackTraceElement caller = Thread.currentThread().getStackTrace().length > 3
                ? Thread.currentThread().getStackTrace()[3]
                : null;
        Log.i(TAG, "[DISMISS_CALL] dismissIntervention: removing overlay"
                + (caller != null ? " — caller=" + caller.getMethodName()
                        + ":" + caller.getLineNumber() : ""));
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager != null) {
            try {
                windowManager.removeView(interventionView);
                Log.d(TAG, "[DISMISS_CALL] dismissIntervention: overlay removed successfully");
            } catch (Exception e) {
                Log.w(TAG, "[DISMISS_CALL] dismissIntervention: removeView failed (already removed?)", e);
            }
        }
        interventionView = null;
        interventionShowing = false;
    }

    /*
     * Relaucnhes Instagram app which opens with the home page as its default
     * 
     */
    private void goToHomePage() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.instagram.android",
                "com.instagram.android.activity.MainTabActivity"));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    // =========================================================================
    // Reels state persistence (shared with AppUsageMonitor via SharedPreferences)
    // =========================================================================

    /**
     * Writes the current Reels/Shorts viewing state to SharedPreferences so that
     * AppUsageMonitor can gate scroll budget accumulation to only Reels time.
     *
     * Three keys are written atomically:
     * - is_in_reels (boolean): whether the user is currently in Reels/Shorts
     * - is_in_reels_timestamp (long): System.currentTimeMillis() of this write
     * - is_in_reels_package (String): which app (e.g. com.instagram.android)
     *
     * AppUsageMonitor reads these keys and treats the flag as stale if the
     * timestamp
     * is older than 5 seconds, so the heartbeat must keep it fresh.
     *
     * @param inReels     true when entering Reels, false when leaving
     * @param packageName the app package currently in Reels (ignored when
     *                    inReels=false)
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
     * Starts a repeating heartbeat that:
     * 1. Refreshes the Reels state timestamp every 2s (keeps is_in_reels fresh)
     * 2. Accumulates scroll budget time (adds 2s per tick to scroll_time_used_ms)
     * 3. Marks budget as exhausted when allowance is exceeded
     *
     * Scroll budget accumulation was moved here from AppUsageMonitor because
     * MyVpnService (which hosts AppUsageMonitor) may not be running on Android 12+
     * due to foreground service start restrictions. This AccessibilityService is
     * always running when enabled, making it the reliable place for tracking.
     *
     * @param packageName the app currently in Reels
     */
    private void startReelsHeartbeat(String packageName) {
        stopReelsHeartbeat(); // cancel any existing heartbeat first
        currentReelsPackage = packageName; // store for accumulateScrollBudget() overlay trigger
        reelsHeartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                // --- Heartbeat self-validation (primary false-positive fix) ---
                // Before accumulating budget, verify the user is STILL in Reels.
                // This catches the case where the user pressed Home or switched apps
                // but no TYPE_WINDOW_STATE_CHANGED was received (e.g., quick gestures,
                // split-screen, or picture-in-picture). If the active window is no
                // longer the Reels/Shorts app or layout, stop the heartbeat and reset.
                // [STICKY-FIX-HEARTBEAT] While the intervention overlay is showing, skip the
                // isStillInReels() active-window check. The overlay is TYPE_ACCESSIBILITY_OVERLAY
                // and is focusable (FLAG_NOT_FOCUSABLE intentionally absent so buttons work).
                // When it's on screen, getRootInActiveWindow() returns the overlay's accessibility
                // tree (pkg=com.breqk), not Instagram's, causing isStillInReels() to return false
                // and triggering resetReelsState() → dismissIntervention() ~2s after the overlay
                // appears. Skipping the check while the overlay is visible prevents this false
                // dismissal. Navigation within Instagram (TYPE_WINDOW_STATE_CHANGED from Instagram)
                // still reaches handleReelsScrollEvent() and correctly dismisses the overlay via
                // the isReelsLayout()=false path.
                if (!interventionShowing && !isStillInReels(packageName)) {
                    Log.d(TAG, "[REELS_STATE] Heartbeat: user no longer in Reels "
                            + "(foreground check failed for " + packageName + ") — stopping heartbeat");
                    resetReelsState();
                    return; // don't reschedule — heartbeat is dead
                }
                if (interventionShowing) {
                    Log.d(TAG, "[STICKY-FIX-HEARTBEAT] Heartbeat: interventionShowing=true — "
                            + "skipping isStillInReels() check, overlay must not be auto-dismissed by heartbeat");
                }

                persistReelsState(true, packageName);
                // Accumulate scroll budget time on every heartbeat tick.
                // accumulateScrollBudget() will trigger the intervention overlay immediately
                // if budget becomes exhausted during this tick (no scroll event needed).
                accumulateScrollBudget();
                Log.d(TAG, "[REELS_STATE] Heartbeat: refreshed timestamp + budget for " + packageName);
                mainHandler.postDelayed(this, REELS_HEARTBEAT_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(reelsHeartbeatRunnable, REELS_HEARTBEAT_INTERVAL_MS);
        Log.d(TAG,
                "[REELS_STATE] Heartbeat started (interval=" + REELS_HEARTBEAT_INTERVAL_MS + "ms) for " + packageName);
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

    /**
     * Checks if the user is still actively viewing Reels/Shorts by inspecting
     * the current active window. Used by the heartbeat to prevent false-positive
     * budget accumulation when the user has left via Home button, app switcher,
     * or any other navigation that didn't fire a target-package accessibility event.
     *
     * Three checks are performed:
     * 1. The active window package matches the expected Reels/Shorts app
     * 2. (YouTube only) The root window class is NOT a generic Android framework class
     *    — YouTube keeps Shorts UI elements alive in a floating overlay window even
     *    after the user navigates to the home page; getRootInActiveWindow() can return
     *    that overlay, which has package=YouTube but class=android.view.ViewGroup.
     *    We reject it to avoid false-positive heartbeat accumulation.
     * 3. The Reels/Shorts full-screen layout is still present
     *
     * If either check fails, the caller should stop the heartbeat and reset state.
     *
     * Filter: adb logcat -s REELS_WATCH | grep STILL_IN_REELS
     *
     * @param expectedPackage the package we expect (PKG_INSTAGRAM or PKG_YOUTUBE)
     * @return true if the active window is still showing full-screen Reels/Shorts
     */
    private boolean isStillInReels(String expectedPackage) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.d(TAG, "[STILL_IN_REELS] root null (no active window) → false");
            return false;
        }

        // Check that the active window belongs to the expected Reels/Shorts app.
        // If the user pressed Home, this will be the launcher package instead.
        CharSequence rootPkg = root.getPackageName();
        if (rootPkg == null || !rootPkg.toString().equals(expectedPackage)) {
            Log.d(TAG, "[STILL_IN_REELS] active window pkg="
                    + (rootPkg != null ? rootPkg : "null")
                    + " expected=" + expectedPackage + " → false (user left app)");
            root.recycle();
            return false;
        }

        // Guard: YouTube keeps Shorts UI elements resident in a floating overlay window
        // even after the user navigates back to the home page. getRootInActiveWindow()
        // can return this floating overlay root, whose class is a generic Android
        // framework class (e.g. "android.view.ViewGroup") rather than a real Activity.
        // If we detect a generic framework root for YouTube, we cannot trust it to
        // represent the real Shorts screen — skip the layout check and return false
        // so the heartbeat does not falsely keep accumulating budget time.
        //
        // Instagram does not exhibit this floating-overlay pattern, so this guard
        // only applies to YouTube.
        //
        // Filter: adb logcat -s REELS_WATCH | findstr "STILL_IN_REELS"
        if (expectedPackage.equals(PKG_YOUTUBE)) {
            CharSequence rootClass = root.getClassName();
            String rootClassStr = rootClass != null ? rootClass.toString() : "";
            if (FrameworkClassFilter.isFrameworkClass(rootClassStr)) {
                Log.d(TAG, "[STILL_IN_REELS] YouTube root class='" + rootClassStr
                        + "' is generic framework class → floating overlay, not real Shorts → false");
                root.recycle();
                return false;
            }
        }

        // Package matches — verify the Reels/Shorts layout is still full-screen.
        // The user might still be in Instagram but navigated away from Reels
        // (e.g., tapped Home tab, opened DMs, etc.)
        boolean inReels = expectedPackage.equals(PKG_INSTAGRAM)
                ? isReelsLayout(root) : isShortsLayout(root);
        root.recycle();

        Log.d(TAG, "[STILL_IN_REELS] pkg=" + expectedPackage + " inReels=" + inReels);
        return inReels;
    }

    // =========================================================================
    // Scroll budget accumulation (runs inside the heartbeat)
    // =========================================================================

    /**
     * Adds REELS_HEARTBEAT_INTERVAL_MS (2s) to the scroll budget used time.
     * Reads and writes SharedPreferences atomically. If the accumulated time
     * exceeds the configured allowance, marks the budget as exhausted.
     *
     * Also handles window expiration: if the window has rolled over, resets
     * the budget before accumulating.
     *
     * SharedPreferences keys used:
     * - scroll_time_used_ms (long): accumulated Reels time in current window
     * - scroll_window_start_time (long): when the current window started (0 = no
     * window)
     * - scroll_allowance_minutes (int): allowed minutes per window
     * - scroll_window_minutes (int): window duration in minutes
     * - scroll_budget_exhausted_at (long): >0 when budget is exhausted
     */
    /**
     * Returns true if the user has an active 20-minute free break.
     *
     * During an active free break, ALL intervention and budget accumulation logic
     * is bypassed, giving the user an uninterrupted scroll window (great for
     * cardio sessions where mindless scrolling is genuinely fine).
     *
     * Includes stale-flag cleanup: if the 20-min window has elapsed but
     * free_break_active was never cleared (e.g. process was killed), this
     * method auto-clears the flag so we don't permanently suppress interventions.
     *
     * Log filter: adb logcat -s REELS_WATCH | grep FREE_BREAK
     */
    private boolean isFreeBreakActive() {
        SharedPreferences prefs = BreqkPrefs.get(this);
        boolean active = prefs.getBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false);
        if (!active) return false;

        long startTime = prefs.getLong(BreqkPrefs.KEY_FREE_BREAK_START_TIME, 0);
        long now = System.currentTimeMillis();
        if (startTime > 0 && (now - startTime) >= BreqkPrefs.FREE_BREAK_DURATION_MS) {
            // Break expired — auto-clear so interventions resume immediately
            prefs.edit().putBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false).apply();
            Log.i(TAG, "[FREE_BREAK] isFreeBreakActive: break expired (startTime=" + startTime
                    + ") — auto-cleared, elapsed=" + (now - startTime) + "ms");
            return false;
        }

        long remainingMs = (startTime + BreqkPrefs.FREE_BREAK_DURATION_MS) - now;
        Log.d(TAG, "[FREE_BREAK] isFreeBreakActive: true — remainingMs=" + remainingMs);
        return true;
    }

    private void accumulateScrollBudget() {
        // [FREE_BREAK] Skip all budget accumulation during an active free break.
        // The user gets an uninterrupted 20-min window; budget is fully preserved.
        if (isFreeBreakActive()) {
            Log.d(TAG, "[FREE_BREAK] accumulateScrollBudget: skipping — free break active");
            return;
        }

        SharedPreferences prefs = getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);

        int allowanceMinutes = prefs.getInt(PREF_SCROLL_ALLOWANCE_MIN, 5);
        int windowMinutes = prefs.getInt(PREF_SCROLL_WINDOW_MIN, 60);
        long windowStartTime = prefs.getLong(PREF_SCROLL_WINDOW_START, 0);
        long scrollTimeUsedMs = prefs.getLong(PREF_SCROLL_TIME_USED_MS, 0);
        long exhaustedAt = prefs.getLong(PREF_SCROLL_EXHAUSTED_AT, 0);

        long now = System.currentTimeMillis();
        long allowanceMs = allowanceMinutes * 60 * 1000L;
        long windowMs = windowMinutes * 60 * 1000L;

        // Check if the window has expired and reset
        if (windowStartTime > 0 && (now - windowStartTime) >= windowMs) {
            Log.i(TAG, "[BUDGET] Scroll window expired — resetting. "
                    + "windowStart=" + windowStartTime + " windowMin=" + windowMinutes);
            windowStartTime = 0;
            scrollTimeUsedMs = 0;
            exhaustedAt = 0;
            // Reset intervention state so the new window's exhaustion can trigger a fresh overlay
            interventionShowing = false;
            dismissIntervention();
        }

        // Start a new window if none is active
        if (windowStartTime == 0) {
            windowStartTime = now;
            Log.d(TAG, "[BUDGET] New scroll window started at " + windowStartTime);
        }

        // Accumulate time (heartbeat fires every 2s)
        scrollTimeUsedMs += REELS_HEARTBEAT_INTERVAL_MS;

        Log.d(TAG, "[BUDGET] Accumulated: used=" + scrollTimeUsedMs + "ms / "
                + allowanceMs + "ms allowance ("
                + String.format("%.0f", (double) scrollTimeUsedMs / 1000) + "s / "
                + (allowanceMinutes * 60) + "s)");

        // Check if budget is now exhausted
        if (scrollTimeUsedMs >= allowanceMs && exhaustedAt == 0) {
            exhaustedAt = now;
            Log.i(TAG, "[BUDGET] Scroll budget EXHAUSTED at " + exhaustedAt);

            // Trigger intervention overlay immediately — don't wait for the next scroll event.
            // This ensures the overlay appears even when the user is passively watching without scrolling.
            if (!interventionShowing && currentReelsPackage != null && !currentReelsPackage.isEmpty()) {
                interventionShowing = true;
                Log.i(TAG, "[BUDGET] Triggering immediate intervention from heartbeat for " + currentReelsPackage);
                triggerIntervention(currentReelsPackage);
            }
        }

        // Persist atomically
        prefs.edit()
                .putLong(PREF_SCROLL_TIME_USED_MS, scrollTimeUsedMs)
                .putLong(PREF_SCROLL_WINDOW_START, windowStartTime)
                .putLong(PREF_SCROLL_EXHAUSTED_AT, exhaustedAt)
                .apply();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String eventTypeName(int type) {
        switch (type) {
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                return "CONTENT_CHANGED";
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                return "STATE_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                return "VIEW_SCROLLED";
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                return "VIEW_CLICKED";
            default:
                return "TYPE_" + type;
        }
    }

    /**
     * Safely recycles all AccessibilityNodeInfo nodes in a list.
     * Replaces the repeated try/catch recycle pattern used throughout this file.
     *
     * @param nodes List of nodes to recycle (may be null)
     */
    private static void recycleAll(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo n : nodes) {
            try {
                n.recycle();
            } catch (Exception ignored) {
            }
        }
    }


    /**
     * Logs all view IDs in the top 2 levels of YouTube's accessibility tree.
     * Rate-limited to once every YT_TREE_DUMP_INTERVAL_MS (10s) to prevent
     * log spam.
     *
     * This is the PRIMARY debugging tool for YouTube Shorts detection failures.
     * When YouTube changes their view IDs, a developer runs:
     *   adb logcat -s REELS_WATCH | grep YT_TREE_DUMP
     * while scrolling through Shorts to see the current IDs, then updates
     * YOUTUBE_SHORTS_VIEW_IDS with the discovered IDs.
     *
     * @param root Root of the YouTube accessibility tree
     */
    private void dumpYouTubeTreeIfNeeded(AccessibilityNodeInfo root) {
        long now = System.currentTimeMillis();
        if (now - lastYtTreeDump < YT_TREE_DUMP_INTERVAL_MS) return;
        lastYtTreeDump = now;

        Log.w(TAG, "YT_TREE_DUMP === YouTube Shorts detection FAILED — dumping tree view IDs ===");
        Log.w(TAG, "YT_TREE_DUMP To fix: find the Shorts container ID below and add it to "
                + "YOUTUBE_SHORTS_VIEW_IDS array in ReelsInterventionService.java");
        dumpNodeChildren(root, 0, 5);
        Log.w(TAG, "YT_TREE_DUMP === End dump ===");
    }

    /**
     * Recursively logs view IDs, class names, scrollability, and bounds for
     * all children of a node up to maxDepth levels deep.
     *
     * @param node     Current node to inspect
     * @param depth    Current depth in the tree
     * @param maxDepth Maximum depth to traverse (prevents performance issues)
     */
    private void dumpNodeChildren(AccessibilityNodeInfo node, int depth, int maxDepth) {
        if (node == null || depth > maxDepth) return;
        StringBuilder indent = new StringBuilder();
        for (int d = 0; d < depth; d++) indent.append("  ");
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                String id = child.getViewIdResourceName();
                String cls = child.getClassName() != null
                        ? child.getClassName().toString() : "null";
                boolean scrollable = child.isScrollable();
                Rect bounds = new Rect();
                child.getBoundsInScreen(bounds);
                // getText() and getContentDescription() expose text-based signals
                // used by TIER3 (hasShortsTextSignal). Including them here lets
                // developers identify new text signals when TIER1/TIER2 fail.
                String text = child.getText() != null
                        ? "\"" + child.getText() + "\"" : "null";
                String contentDesc = child.getContentDescription() != null
                        ? "\"" + child.getContentDescription() + "\"" : "null";
                Log.w(TAG, "YT_TREE_DUMP " + indent + "[" + depth + "." + i + "] "
                        + "id=" + id + " class=" + cls
                        + " text=" + text + " contentDesc=" + contentDesc
                        + " scrollable=" + scrollable
                        + " bounds=" + bounds.toShortString());
                dumpNodeChildren(child, depth + 1, maxDepth);
                child.recycle();
            }
        }
    }
}
