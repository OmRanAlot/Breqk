package com.breqk.shortform;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.breqk.monitor.LaunchInterceptor;
import com.breqk.prefs.BreqkPrefs;
import com.breqk.shortform.platform.PlatformRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * AppEventRouter
 * ---------------
 * Central dispatcher for accessibility events inside ReelsInterventionService.
 * Routes TYPE_WINDOW_STATE_CHANGED    → LaunchInterceptor
 *         TYPE_WINDOW_CONTENT_CHANGED  → ContentFilter
 *         TYPE_VIEW_SCROLLED           → ContentFilter
 *
 * Monitored packages are determined by PlatformRegistry.isMonitored() — no hardcoded set.
 *
 * Config cache:
 *   Reads AppConfig (blockShortForm, launchPopup) from BreqkPrefs on first access or
 *   after CONFIG_CACHE_TTL_MS, avoiding SharedPreferences reads on every accessibility
 *   event (which can fire hundreds of times per second during scroll).
 *
 * Logging tag: APP_ROUTER
 * Filter:       adb logcat -s APP_ROUTER
 * Cache misses: adb logcat -s APP_ROUTER | findstr "CONFIG_CACHE"
 */
public class AppEventRouter {

    private static final String TAG = "APP_ROUTER";

    /** In-memory config cache TTL: 5 seconds. Balances freshness vs. pref read overhead. */
    private static final long CONFIG_CACHE_TTL_MS = 5_000L;

    private final Context context;
    private final LaunchInterceptor launchInterceptor;
    private final ContentFilter contentFilter;

    /** In-memory config cache: packageName → CachedConfig. */
    private final Map<String, CachedConfig> configCache = new HashMap<>();

    public AppEventRouter(Context context, AccessibilityService service) {
        this.context = context;
        this.launchInterceptor = new LaunchInterceptor(context, service);
        this.contentFilter = new ContentFilter(context, service);
        Log.d(TAG, "[INIT] AppEventRouter created"
                + " monitoredPackages=" + PlatformRegistry.monitoredPackageList()
                + " configCacheTtlMs=" + CONFIG_CACHE_TTL_MS);
    }

    /**
     * Routes an accessibility event to the appropriate subsystem.
     * Fast exit: packages not registered in PlatformRegistry return immediately.
     */
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();

        if (!PlatformRegistry.isMonitored(pkg)) return;

        AppConfig config = getConfig(pkg);
        int eventType = event.getEventType();

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "[ROUTE] STATE_CHANGED → LaunchInterceptor pkg=" + pkg
                    + " launchPopup=" + config.launchPopup);
            launchInterceptor.onWindowStateChanged(event, config);
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            Log.d(TAG, "[ROUTE] type=" + eventType + " → ContentFilter pkg=" + pkg
                    + " blockShortForm=" + config.blockShortForm);
            contentFilter.onContentChanged(event, config);
        }
    }

    /** Cleans up both subsystems. Call from the service's onInterrupt() / onDestroy(). */
    public void onDestroy() {
        launchInterceptor.onDestroy();
        contentFilter.onDestroy();
        Log.d(TAG, "[DESTROY] AppEventRouter and subsystems destroyed");
    }

    private AppConfig getConfig(String packageName) {
        long now = System.currentTimeMillis();
        CachedConfig cached = configCache.get(packageName);

        if (cached != null && (now - cached.cachedAt) < CONFIG_CACHE_TTL_MS) {
            return cached.config;
        }

        boolean blockShortForm = BreqkPrefs.isFeatureEnabled(
                context, packageName, BreqkPrefs.FEATURE_BLOCK_SHORT_FORM);
        boolean launchPopup = BreqkPrefs.isFeatureEnabled(
                context, packageName, BreqkPrefs.FEATURE_LAUNCH_POPUP);

        AppConfig config = new AppConfig(packageName, blockShortForm, launchPopup);
        configCache.put(packageName, new CachedConfig(config, now));

        Log.d(TAG, "[CONFIG_CACHE] miss/refresh pkg=" + packageName
                + " blockShortForm=" + blockShortForm
                + " launchPopup=" + launchPopup);
        return config;
    }

    private static final class CachedConfig {
        final AppConfig config;
        final long cachedAt;

        CachedConfig(AppConfig config, long cachedAt) {
            this.config = config;
            this.cachedAt = cachedAt;
        }
    }
}
