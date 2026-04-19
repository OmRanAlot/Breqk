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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.breqk.BuildConfig;
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
import com.breqk.reels.detection.InstagramDetector;
import com.breqk.reels.detection.ShortFormDetector;
import com.breqk.reels.detection.YouTubeDetector;
import com.breqk.reels.budget.BudgetState;
import com.breqk.reels.budget.BudgetHeartbeat;
import com.breqk.reels.budget.HomeFeedCounter;
import com.breqk.reels.intervention.InterventionOverlay;
import com.breqk.reels.intervention.ReelsStateMachine;

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

    private final ReelsStateMachine stateMachine = new ReelsStateMachine();

    // --- WindowManager Overlays ---
    private InterventionOverlay interventionOverlay;

    /** Handler on main looper — WindowManager calls must be on the UI thread. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private void logVerbose(String msg) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, msg);
        }
    }

    /**
     * Shared utility for detecting system overlay packages and Android framework class events.
     * Holds the lazily-resolved launcher package cache (see FrameworkClassFilter).
     * Initialized once — single instance per service lifecycle.
     */
    private final FrameworkClassFilter frameworkClassFilter = new FrameworkClassFilter();

    /**
     * Layout detectors for Instagram Reels and YouTube Shorts.
     *
     * Initialized in onServiceConnected() after the service context is available.
     * instagramDetector encapsulates isReelsLayout().
     * youtubeDetector   encapsulates isShortsLayout() + hasShortsTextSignal() + dumpYouTubeTreeIfNeeded().
     *
     * Both implement ShortFormDetector.detect(root) → DetectResult.
     */
    private InstagramDetector instagramDetector;
    private YouTubeDetector   youtubeDetector;

    // lastYtTreeDump and YT_TREE_DUMP_INTERVAL_MS have been moved to YouTubeDetector
    // (com.breqk.reels.detection.YouTubeDetector) as part of Step 2 refactor.

    // --- Reels state persistence (shared with AppUsageMonitor) ---
    private static final String PREF_IS_IN_REELS = "is_in_reels";
    private static final String PREF_IS_IN_REELS_TIMESTAMP = "is_in_reels_timestamp";
    private static final String PREF_IS_IN_REELS_PACKAGE = "is_in_reels_package";

    private BudgetState budgetState;
    private BudgetHeartbeat budgetHeartbeat;

    // --- Home feed scroll counter ---
    // In-memory; resets when user leaves Instagram. Independent of BudgetState.
    private final HomeFeedCounter homeFeedCounter = new HomeFeedCounter();
    // Separate debounce timestamp so home feed scrolls don't share state with Reels.
    private long lastFeedScrollTimestamp = 0;

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

        // Initialize layout detectors (Step 2 of reels-intervention-refactor).
        // These replace the private isReelsLayout() / isShortsLayout() methods.
        instagramDetector = new InstagramDetector(this, TAG);
        youtubeDetector   = new YouTubeDetector(this, TAG);
        interventionOverlay = new InterventionOverlay(this, mainHandler);
        budgetState       = new BudgetState(this);
        budgetState.load(getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE));
        budgetHeartbeat   = new BudgetHeartbeat(budgetState, mainHandler, getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE), new BudgetHeartbeat.HeartbeatCallback() {
            @Override
            public boolean isStillInReels(String packageName) {
                if (interventionOverlay.isShowing()) {
                    Log.d(TAG, "[STICKY-FIX-HEARTBEAT] Heartbeat: interventionShowing=true — skipping isStillInReels() check");
                    return true;
                }
                return ReelsInterventionService.this.isStillInReels(packageName);
            }
            @Override
            public void onBudgetExhausted(String packageName) {
                if (!interventionOverlay.isShowing()) {
                    Log.i(TAG, "[BUDGET] Triggering immediate intervention from heartbeat for " + packageName);
                    triggerIntervention(packageName);
                }
            }
            @Override
            public void onHeartbeatInvalid() {
                resetReelsState();
            }
            @Override
            public void persistReelsState(boolean active, String packageName) {
                ReelsInterventionService.this.persistReelsState(active, packageName);
            }
        });

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
        if (stateMachine.isInReels()
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
            if (interventionOverlay != null && interventionOverlay.isShowing()) {
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

        // [HOME_FEED] App-switch detection while on Instagram home feed (not in Reels).
        // Reset the home feed counter when the user leaves Instagram, so the limit is
        // per-session (each new Instagram visit starts fresh).
        if (!stateMachine.isInReels()
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && !packageName.equals(PKG_INSTAGRAM)
                && !packageName.equals(PKG_YOUTUBE)
                && !(interventionOverlay != null && interventionOverlay.isShowing())) {
            String homeFeedSwitchClass = event.getClassName() != null ? event.getClassName().toString() : "";
            if (!FrameworkClassFilter.isFrameworkClass(homeFeedSwitchClass)
                    && !frameworkClassFilter.isSystemOverlayPackage(packageName, this, TAG)) {
                homeFeedCounter.reset();
                lastFeedScrollTimestamp = 0;
                Log.d(TAG, "[HOME_FEED] app switch to pkg=" + packageName + " → counter reset");
            }
        }

        // Only process scroll/state events for scroll-budget logic from Instagram or YouTube.
        // TikTok is handled entirely by AppEventRouter (ContentFilter) above — no budget tracking.
        if (!packageName.equals(PKG_INSTAGRAM) && !packageName.equals(PKG_YOUTUBE))
            return;

        // Per-app policy check: skip if reels_detection is disabled for this app.
        // Uses BreqkPrefs.isFeatureEnabled() which resolves active mode overrides → base policy.
        boolean reelsEnabled = BreqkPrefs.isFeatureEnabled(this, packageName, BreqkPrefs.FEATURE_REELS_DETECTION);
        Log.i(TAG, "[INTERCEPT_DECISION] pkg=" + packageName + " reels_detection=" + reelsEnabled);
        if (!reelsEnabled) {
            Log.d(TAG, "[POLICY] reels_detection disabled for " + packageName + " — skipping event");
            // If we were tracking this app's Reels state, reset it
            if (stateMachine.isInReels()) {
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
        if (budgetHeartbeat != null) budgetHeartbeat.stop();
        homeFeedCounter.reset();
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

                // Tier 0: check class name via YouTubeDetector.checkClassNameTier0() — O(1).
                // Returns true if className matches a known Shorts Activity/Fragment class.
                if (youtubeDetector.checkClassNameTier0(eventClass)) {
                    Log.d(TAG, "YouTubeDetector: TIER0 class name matched " + eventClass + " → confirmed Shorts");
                    if (!stateMachine.isInReels()) {
                        stateMachine.enterReels(packageName, "TIER0");
                        persistReelsState(true, packageName);
                        // Heartbeat and budget deferred until first scroll (grace period)
                    }
                    return;
                }
                // checkClassNameTier0 already logged SHORTS_CLASS — no additional log needed here.
                // If it returned false (framework class or unknown class), fall through to tree walk.
            }

            // Run the appropriate detector — delegates to InstagramDetector or YouTubeDetector.
            AccessibilityNodeInfo root = getRootInActiveWindow();
            ShortFormDetector.DetectResult detectResult = packageName.equals(PKG_INSTAGRAM)
                    ? instagramDetector.detect(root)
                    : youtubeDetector.detect(root);
            boolean inReelsLayout = detectResult.inShortForm;
            if (root != null)
                root.recycle();

            if (!inReelsLayout && stateMachine.isInReels()) {
                Log.d(TAG, "Left Reels/Shorts via state change — resetting");
                resetReelsState();
            } else if (inReelsLayout && !stateMachine.isInReels()) {
                // Entering Reels/Shorts via navigation (tab switch, deep link, etc.).
                // Grace period: let the user watch the first video they land on.
                // Heartbeat and budget accumulation are deferred until the first scroll.
                String detectedTier = detectResult.tier.isEmpty() ? "TREE_WALK" : detectResult.tier;
                stateMachine.enterReels(packageName, detectedTier);
                persistReelsState(true, packageName);
                // Heartbeat and budget deferred until first scroll (grace period)
            }
            Log.d(TAG, "STATE_CHANGED: wasInReelsLayout=" + stateMachine.isInReels() + " pkg=" + packageName);
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
                    logVerbose("Fast path: source=clips_viewer_view_pager fullScreen=" + confirmedInReels);
                } else if (packageName.equals(PKG_YOUTUBE)) {
                    // YouTube Shorts: loop over all known container IDs and verify
                    // full-screen bounds (matching Instagram's pattern for consistency).
                    // ShortFormIds.YOUTUBE_SHORTS_VIEW_IDS is the single source of truth.
                    for (String shortsId : ShortFormIds.YOUTUBE_SHORTS_VIEW_IDS) {
                        if (viewId.equals(shortsId)) {
                            confirmedInReels = FullScreenCheck.isFullScreen(source, this, TAG);
                            usedFastPath = true;
                            logVerbose("Fast path: source=" + shortsId + " fullScreen=" + confirmedInReels);
                            break;
                        }
                    }
                }
            }
            source.recycle();
        }

        // --- Slow path: full tree traversal if fast path didn't fire ---
        // Handles scroll events from child views within the Reels player (e.g., caption scroll).
        // Delegates to InstagramDetector or YouTubeDetector (Step 2 refactor).
        if (!usedFastPath) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            ShortFormDetector.DetectResult slowResult = packageName.equals(PKG_INSTAGRAM)
                    ? instagramDetector.detect(root)
                    : youtubeDetector.detect(root);
            confirmedInReels = slowResult.inShortForm;
            if (root != null)
                root.recycle();
            logVerbose("Slow path: tree traversal confirmedInReels=" + confirmedInReels + " tier=" + slowResult.tier);
        }

        // Update cached layout state and apply decision
        if (!confirmedInReels) {
            if (stateMachine.isInReels()) {
                Log.d(TAG, "Scroll outside Reels/Shorts — resetting state");
                resetReelsState();
            }

            // [HOME_FEED] Check if this is a scroll on the Instagram home feed.
            // Only fires for Instagram, scroll events, when no overlay is already showing.
            if (packageName.equals(PKG_INSTAGRAM)
                    && eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
                    && !(interventionOverlay != null && interventionOverlay.isShowing())
                    && !budgetState.isFreeBreakActive(System.currentTimeMillis())) {
                checkHomeFeedScroll(event, packageName);
            }

            logVerbose("SCROLL_DECISION pkg=" + packageName
                    + " fastPath=" + usedFastPath
                    + " confirmedInReels=false"
                    + " action=IGNORED");
            return;
        }

        // User entered Reels — reset home feed counter so Reels visits don't count
        // toward the feed limit, and the counter is fresh when they return to the feed.
        if (!stateMachine.isInReels()) {
            homeFeedCounter.reset();
            lastFeedScrollTimestamp = 0;
        }

        // Persist Reels state for AppUsageMonitor to gate scroll budget accumulation
        if (!stateMachine.isInReels()) {
            // First detection of Reels via scroll (no prior STATE_CHANGED).
            // Start grace period — it will end below when we process this scroll
            // as the "first scroll" that terminates the grace period.
            stateMachine.enterReels(packageName, "first-scroll");
            persistReelsState(true, packageName);
        }

        // --- Grace period termination ---
        // The first scroll after entering Reels/Shorts ends the grace period.
        // This is where we start the heartbeat and budget accumulation that was
        // deferred on entry, allowing the user to watch the first video freely.
        if (stateMachine.processScroll(packageName)) {

            // Now start what was deferred on entry:
            budgetHeartbeat.start(packageName);
            budgetState.tick(BudgetHeartbeat.REELS_HEARTBEAT_INTERVAL_MS);

            // Immediate budget check: if budget was already exhausted from a previous
            // session (or allowance=0), intervene now.
            long graceEndNow = System.currentTimeMillis();
            if (budgetState.isExhausted(graceEndNow) && !interventionOverlay.isShowing()) {
                Log.i(TAG, "[GRACE] Budget already exhausted after grace period — immediate intervention for " + packageName);
                triggerIntervention(packageName);
            }
        }

        // [FREE_BREAK] If the user has an active free break, allow all scrolling without
        // any intervention or budget accumulation. The heartbeat keeps running (to maintain
        // is_in_reels state for AppUsageMonitor) but skips budget accumulation via its own
        // guard inside accumulateScrollBudget().
        if (budgetState.isFreeBreakActive(System.currentTimeMillis())) {
            logVerbose("SCROLL_DECISION pkg=" + packageName + " action=FREE_BREAK_ALLOW (break active — skipping budget/intervention)");
            return;
        }

        // --- Debounce: ignore rapid-fire duplicate events from the same physical swipe
        // ---
        long now = System.currentTimeMillis();
        long elapsed = now - lastScrollTimestamp;
        if (elapsed < SCROLL_DEBOUNCE_MS) {
            logVerbose("SCROLL_DECISION pkg=" + packageName
                    + " fastPath=" + usedFastPath
                    + " confirmedInReels=true"
                    + " DEBOUNCED (elapsed=" + elapsed + "ms < " + SCROLL_DEBOUNCE_MS + "ms)");
            return;
        }
        lastScrollTimestamp = now;

        // --- Check scroll budget exhaustion from SharedPreferences ---
        // AppUsageMonitor persists budget state; we read it here to decide whether to
        // intervene.
        boolean budgetExhausted = budgetState.isExhausted(now);

        logVerbose("SCROLL_DECISION pkg=" + packageName
                + " fastPath=" + usedFastPath
                + " confirmedInReels=true"
                + " budgetExhausted=" + budgetExhausted
                + " interventionShowing=" + interventionOverlay.isShowing());

        if (budgetExhausted && !interventionOverlay.isShowing()) {
            Log.i(TAG, "[BUDGET] Scroll budget exhausted — triggering intervention for " + packageName);
            triggerIntervention(packageName);
        } else if (!budgetExhausted) {
            Log.d(TAG, "[BUDGET] Budget OK — allowing scroll in " + packageName);
        }
    }

    // =========================================================================
    // Full-screen verification (core false-positive fix)
    // =========================================================================




    // hasShortsTextSignal() and scanNodeForShortsText() have been moved to
    // YouTubeDetector (com.breqk.reels.detection.YouTubeDetector) as part of
    // Step 2 of the reels-intervention-refactor.

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

        stateMachine.reset("reset");
        lastScrollTimestamp = 0;
        // Clear Reels state in SharedPreferences so AppUsageMonitor stops reading active state
        persistReelsState(false, "");
        if (budgetHeartbeat != null) budgetHeartbeat.stop();
        Log.d(TAG,
                "resetReelsState: cleared (wasInReels=false, interventionShowing=false, inGracePeriod=false, lastScrollTimestamp=0, reelsState=false)");
    }

    // =========================================================================
    // Home feed scroll detection
    // =========================================================================

    /**
     * Checks whether a scroll event originates from the Instagram home feed RecyclerView.
     * If so, increments the per-session counter and triggers the intervention when the
     * configured post limit is reached.
     *
     * Detection: fast-path only — checks event.getSource().getViewIdResourceName() against
     * ShortFormIds.INSTAGRAM_HOME_FEED_IDS. No tree traversal needed.
     *
     * Log filter: adb logcat -s REELS_WATCH | findstr "HOME_FEED"
     */
    private void checkHomeFeedScroll(AccessibilityEvent event, String packageName) {
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;

        String viewId = source.getViewIdResourceName();
        source.recycle();

        if (viewId == null) {
            logVerbose("[HOME_FEED_SOURCE] source has no view ID — ignored");
            return;
        }

        // Log every source ID to help discover the correct ID after Instagram updates.
        // Filter: adb logcat -s REELS_WATCH | findstr "HOME_FEED_SOURCE"
        logVerbose("[HOME_FEED_SOURCE] source viewId=" + viewId);

        boolean isFeedScroll = false;
        for (String feedId : ShortFormIds.INSTAGRAM_HOME_FEED_IDS) {
            if (viewId.equals(feedId)) {
                isFeedScroll = true;
                break;
            }
        }

        if (!isFeedScroll) return;

        // Debounce: same window as Reels (600ms) — one physical swipe fires multiple events.
        long now = System.currentTimeMillis();
        if (now - lastFeedScrollTimestamp < SCROLL_DEBOUNCE_MS) {
            logVerbose("[HOME_FEED] DEBOUNCED (elapsed=" + (now - lastFeedScrollTimestamp) + "ms)");
            return;
        }
        lastFeedScrollTimestamp = now;

        SharedPreferences prefs = getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
        boolean limitReached = homeFeedCounter.increment(prefs);

        if (limitReached) {
            Log.i(TAG, "[HOME_FEED] Post limit reached (count=" + homeFeedCounter.getCount()
                    + " limit=" + homeFeedCounter.getLimit(prefs)
                    + ") — triggering intervention for " + packageName);
            homeFeedCounter.reset(); // reset so repeated re-entries don't immediately re-trigger
            triggerIntervention(packageName);
        }
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
        if (interventionOverlay != null) {
            interventionOverlay.show(pkg, () -> {
                resetReelsState();
                performGlobalAction(GLOBAL_ACTION_HOME);
            });
        }
    }

    /**
     * Removes the intervention overlay. Safe to call when no overlay is showing.
     *
     * [STICKY-FIX] Logs the immediate caller so any accidental dismiss path is
     * visible in logcat. Filter: adb logcat -s REELS_WATCH | findstr "DISMISS_CALL"
     */
    private void dismissIntervention() {
        if (interventionOverlay != null) {
            interventionOverlay.dismiss();
        }
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
        // Delegates to InstagramDetector or YouTubeDetector (Step 2 refactor).
        ShortFormDetector.DetectResult stillResult = expectedPackage.equals(PKG_INSTAGRAM)
                ? instagramDetector.detect(root)
                : youtubeDetector.detect(root);
        boolean inReels = stillResult.inShortForm;
        root.recycle();

        Log.d(TAG, "[STILL_IN_REELS] pkg=" + expectedPackage + " inReels=" + inReels);
        return inReels;
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


    // dumpYouTubeTreeIfNeeded() and dumpNodeChildren() have been moved to
    // YouTubeDetector (com.breqk.reels.detection.YouTubeDetector) as part of
    // Step 2 of the reels-intervention-refactor.
    // To trigger a tree dump, YouTubeDetector.dumpYouTubeTreeIfNeeded() is called
    // automatically from youtubeDetector.detect() when all tiers fail.
    // Filter: adb logcat -s REELS_WATCH | findstr "YT_TREE_DUMP"
}
