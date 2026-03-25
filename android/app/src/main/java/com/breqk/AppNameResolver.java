package com.breqk;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AppNameResolver
 * ----------------
 * Shared utility for resolving package names to human-readable app labels.
 * Uses an LRU cache (max 100 entries) since the same packages are resolved
 * repeatedly during monitoring and stats display.
 *
 * Used by: AppUsageMonitor, ScreenTimeTracker, VPNModule (via AppUsageMonitor)
 *
 * Logging tag: AppNameResolver
 * Filter: adb logcat -s AppNameResolver
 * Prefix: [RESOLVE] — cache miss / lookup
 */
public final class AppNameResolver {

    private static final String TAG = "AppNameResolver";
    private static final int MAX_CACHE_SIZE = 100;

    /**
     * LRU cache mapping packageName → appName.
     * Thread-safe via synchronized access (the resolver is called from the main looper
     * handler and from React Native bridge methods, both on the main thread).
     */
    private static final Map<String, String> cache = new LinkedHashMap<String, String>(
            MAX_CACHE_SIZE + 1, 0.75f, true /* accessOrder = true for LRU */) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    /**
     * Resolves a package name to a human-readable app label.
     * Returns from LRU cache if available; otherwise queries PackageManager.
     * Falls back to the raw package name if the app is not installed.
     *
     * @param pm          PackageManager instance (caller supplies to avoid repeated lookups)
     * @param packageName Package to resolve, e.g. "com.instagram.android"
     * @return            Human-readable label, e.g. "Instagram"; never null
     */
    public static synchronized String resolve(PackageManager pm, String packageName) {
        // Check cache first
        String cached = cache.get(packageName);
        if (cached != null) {
            return cached;
        }

        // Cache miss — query PackageManager
        String appName;
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            appName = pm.getApplicationLabel(appInfo).toString();
        } catch (PackageManager.NameNotFoundException e) {
            // App may have been uninstalled; return raw package name as fallback
            appName = packageName;
            Log.d(TAG, "[RESOLVE] Package not found: " + packageName + " (using raw name)");
        }

        cache.put(packageName, appName);
        return appName;
    }

    /**
     * Clears the name resolution cache.
     * Call if apps are installed/uninstalled and labels may have changed.
     */
    public static synchronized void clearCache() {
        cache.clear();
        Log.d(TAG, "[RESOLVE] Cache cleared");
    }

    // Prevent instantiation
    private AppNameResolver() {}
}
