package com.breqk;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * AppEventRouter
 * ---------------
 * Central dispatcher for accessibility events inside ReelsInterventionService.
 * Routes TYPE_WINDOW_STATE_CHANGED    → LaunchInterceptor
 *         TYPE_WINDOW_CONTENT_CHANGED  → ContentFilter
 *         TYPE_VIEW_SCROLLED           → ContentFilter
 *
 * These two dispatches are fully independent — no shared state between them.
 * The router itself does not make any decisions about user intent or UI state.
 *
 * Config cache:
 *   Reads AppConfig (blockShortForm, launchPopup) from BreqkPrefs on first access or
 *   after CONFIG_CACHE_TTL_MS, avoiding SharedPreferences reads on every accessibility
 *   event (which can fire hundreds of times per second during scroll).
 *
 * Wiring:
 *   Instantiated in ReelsInterventionService.onServiceConnected().
 *   Called from ReelsInterventionService.onAccessibilityEvent() before existing logic.
 *   Destroyed in ReelsInterventionService.onInterrupt().
 *
 * Logging tag: APP_ROUTER
 * Filter:       adb logcat -s APP_ROUTER
 * Cache misses: adb logcat -s APP_ROUTER | findstr "CONFIG_CACHE"
 */
public class AppEventRouter {

    private static final String TAG = "APP_ROUTER";

    /** In-memory config cache TTL: 5 seconds. Balances freshness vs. pref read overhead. */
    private static final long CONFIG_CACHE_TTL_MS = 5_000L;

    /**
     * Monitored package names — the router fast-exits on all other packages (O(1) lookup).
     * Public so ReelsInterventionService can reference the same set for its app-switch detection.
     */
    public static final Set<String> MONITORED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.zhiliaoapp.musically"
    ));

    private final Context context;
    private final LaunchInterceptor launchInterceptor;
    private final ContentFilter contentFilter;

    /**
     * In-memory config cache: packageName → CachedConfig.
     * Access only from the accessibility thread (single-threaded by Android).
     */
    private final Map<String, CachedConfig> configCache = new HashMap<>();

    /**
     * Creates the router and its two subsystems.
     *
     * @param context Android context (the AccessibilityService)
     * @param service AccessibilityService reference — needed by subsystems for
     *                performGlobalAction() and WindowManager access
     */
    public AppEventRouter(Context context, AccessibilityService service) {
        this.context = context;
        this.launchInterceptor = new LaunchInterceptor(context, service);
        this.contentFilter = new ContentFilter(context, service);
        Log.d(TAG, "[INIT] AppEventRouter created"
                + " monitoredPackages=" + MONITORED_PACKAGES
                + " configCacheTtlMs=" + CONFIG_CACHE_TTL_MS);
    }

    /**
     * Routes an accessibility event to the appropriate subsystem.
     *
     * Routing table:
     *   TYPE_WINDOW_STATE_CHANGED                  → LaunchInterceptor.onWindowStateChanged()
     *   TYPE_WINDOW_CONTENT_CHANGED                → ContentFilter.onContentChanged()
     *   TYPE_VIEW_SCROLLED                         → ContentFilter.onContentChanged()
     *
     * Fast exit: non-monitored packages return immediately (O(1) HashSet lookup).
     * These two dispatches are independent — no shared state flows between them.
     *
     * @param event The AccessibilityEvent from the service's onAccessibilityEvent()
     */
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();

        // Fast exit: only process events from monitored apps
        if (!MONITORED_PACKAGES.contains(pkg)) return;

        AppConfig config = getConfig(pkg);
        int eventType = event.getEventType();

        // [1] Route app-open events to LaunchInterceptor (independent of ContentFilter)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "[ROUTE] STATE_CHANGED → LaunchInterceptor pkg=" + pkg
                    + " launchPopup=" + config.launchPopup);
            launchInterceptor.onWindowStateChanged(event, config);
        }

        // [2] Route in-app navigation and scroll events to ContentFilter (independent of LaunchInterceptor)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            Log.d(TAG, "[ROUTE] type=" + eventType + " → ContentFilter pkg=" + pkg
                    + " blockShortForm=" + config.blockShortForm);
            contentFilter.onContentChanged(event, config);
        }
    }

    /**
     * Cleans up both subsystems. Call from the service's onInterrupt() / onDestroy().
     */
    public void onDestroy() {
        launchInterceptor.onDestroy();
        contentFilter.onDestroy();
        Log.d(TAG, "[DESTROY] AppEventRouter and subsystems destroyed");
    }

    // =========================================================================
    // Config cache
    // =========================================================================

    /**
     * Returns the AppConfig for a package, using the 5-second in-memory cache.
     * On cache miss or TTL expiry, reads live values from BreqkPrefs.isFeatureEnabled()
     * which resolves through active-mode overrides → base per-app policy → false.
     *
     * @param packageName Package to look up; must be in MONITORED_PACKAGES
     * @return AppConfig with current blockShortForm and launchPopup flags; never null
     */
    private AppConfig getConfig(String packageName) {
        long now = System.currentTimeMillis();
        CachedConfig cached = configCache.get(packageName);

        if (cached != null && (now - cached.cachedAt) < CONFIG_CACHE_TTL_MS) {
            return cached.config; // Cache hit — fast path
        }

        // Cache miss or TTL expired — read from SharedPreferences
        boolean blockShortForm = BreqkPrefs.isFeatureEnabled(
                context, packageName, BreqkPrefs.FEATURE_BLOCK_SHORT_FORM);
        boolean launchPopup = BreqkPrefs.isFeatureEnabled(
                context, packageName, BreqkPrefs.FEATURE_LAUNCH_POPUP);

        AppConfig config = new AppConfig(packageName, blockShortForm, launchPopup);
        configCache.put(packageName, new CachedConfig(config, now));

        Log.d(TAG, "[CONFIG_CACHE] miss/refresh pkg=" + packageName
                + " blockShortForm=" + blockShortForm
                + " launchPopup=" + launchPopup
                + " (nextRefreshInMs=" + CONFIG_CACHE_TTL_MS + ")");
        return config;
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Immutable configuration for a monitored app.
     * Resolved from BreqkPrefs feature flags through the 5-second config cache.
     * Active mode overrides are applied transparently via BreqkPrefs.isFeatureEnabled().
     */
    public static class AppConfig {
        /** Package name of the monitored app (e.g. "com.instagram.android"). */
        public final String packageName;
        /**
         * If true, ContentFilter will eject the user from short-form content
         * (Reels / Shorts / FYP) via GLOBAL_ACTION_BACK.
         */
        public final boolean blockShortForm;
        /**
         * If true, LaunchInterceptor will show a 15-second mindfulness overlay
         * on fresh app launches (not re-opens within 30 seconds).
         */
        public final boolean launchPopup;

        public AppConfig(String packageName, boolean blockShortForm, boolean launchPopup) {
            this.packageName = packageName;
            this.blockShortForm = blockShortForm;
            this.launchPopup = launchPopup;
        }
    }

    /** Pairs an AppConfig with the timestamp it was written to the cache. */
    private static class CachedConfig {
        final AppConfig config;
        final long cachedAt;

        CachedConfig(AppConfig config, long cachedAt) {
            this.config = config;
            this.cachedAt = cachedAt;
        }
    }
}
