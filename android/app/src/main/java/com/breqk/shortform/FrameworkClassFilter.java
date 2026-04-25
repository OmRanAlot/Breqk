package com.breqk.shortform;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FrameworkClassFilter
 * ---------------------
 * Utilities to distinguish real Android app navigations from system overlays and
 * framework-class events that should not trigger Reels state resets.
 *
 * Previously, two private methods existed only in ReelsInterventionService:
 *   - isAndroidFrameworkClass(String)    (L1055)
 *   - isSystemOverlayPackage(String)     (L1078)
 *   - APP_SWITCH_IGNORE_PACKAGES[]       (L1013)
 *   - cachedLauncherPackages (Set<String>) (L1076)
 *   - resolveLauncherPackages()          (L1098)
 *
 * This class consolidates them so they are usable by any future collaborator
 * (e.g., if ContentFilter ever needs the same guard).
 *
 * Thread safety:
 *   lazyInitLaunchers() is called from the accessibility thread.
 *   cachedLauncherPackages is written once (first call) and read-only after that.
 *   No synchronization needed for single-threaded AccessibilityService callbacks.
 *
 * Log filter:
 *   adb logcat -s REELS_WATCH | findstr "APP_SWITCH"
 *   adb logcat -s REELS_WATCH | findstr "STICKY-FIX"
 */
public final class FrameworkClassFilter {

    /**
     * Packages that appear as foreground via TYPE_WINDOW_STATE_CHANGED but are NOT
     * real app navigations â€” they are system overlays (keyboards, status bar, etc.)
     * that float on top of whatever the user is actually viewing.
     *
     * An APP_SWITCH event from one of these packages must NOT reset Reels state,
     * because the user is still watching Reels/Shorts underneath the overlay.
     *
     * To add new false-reset packages: observe them in logs via
     *   adb logcat -s REELS_WATCH | findstr "APP_SWITCH"
     * and add them to this list.
     */
    public static final String[] APP_SWITCH_IGNORE_PACKAGES = {
            "com.google.android.inputmethod.latin",  // Gboard
            "com.samsung.android.honeyboard",        // Samsung Keyboard
            "com.swiftkey.swiftkeyapp",              // SwiftKey
            "com.android.inputmethod.latin",         // AOSP keyboard
            "com.android.systemui",                  // Status bar / notification shade
            // [STICKY-FIX] Breqk itself fires TYPE_WINDOW_STATE_CHANGED when its own
            // WindowManager overlay attaches â€” must not be treated as an app switch.
            "com.breqk",
            // [STICKY-FIX] Launcher packages: the home screen sits behind the Reels
            // intervention overlay and can fire state-change events at any time.
            // Add common OEM variants here; launchers are also caught dynamically in
            // isSystemOverlayPackage() via PackageManager.
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",  // Pixel launcher
            "com.sec.android.app.launcher",           // Samsung One UI launcher
            "com.miui.home",                          // Xiaomi / MIUI launcher
            "com.oppo.launcher",                      // OPPO launcher
            "com.huawei.android.launcher",            // Huawei launcher
            "com.oneplus.launcher",                   // OnePlus launcher
            "com.realme.launcher",                    // Realme launcher
            "com.zte.mifavor.launcher",               // ZTE launcher
            "com.bbk.launcher2",                      // vivo launcher
    };

    /**
     * Cache of home-launcher package names resolved once at runtime via PackageManager.
     * Populated lazily on first call to {@link #isSystemOverlayPackage}.
     * Catches any OEM launcher not already listed in APP_SWITCH_IGNORE_PACKAGES.
     *
     * Null = not yet resolved. Non-null = already populated (may be empty if resolution failed).
     */
    private Set<String> cachedLauncherPackages = null;

    /**
     * Returns true if the given class name is a generic Android framework class
     * (android.view.*, android.widget.*, android.app.*) rather than an app-specific
     * Activity or Fragment class.
     *
     * Used to distinguish real YouTube navigation events from floating overlay updates.
     * YouTube keeps Shorts UI elements (reel_time_bar, etc.) resident in a floating
     * window that fires TYPE_WINDOW_STATE_CHANGED with className=android.view.ViewGroup
     * even when the user is on the home page.
     *
     * Real navigations (entering the Shorts tab) always use proper YouTube class names
     * like com.google.android.apps.youtube.app.watchwhile.MainActivity.
     *
     * @param className The class name from event.getClassName()
     * @return true if the class is a generic Android framework class (= overlay event)
     */
    public static boolean isFrameworkClass(String className) {
        if (className == null || className.isEmpty()) return false;
        return className.startsWith("android.view.")
                || className.startsWith("android.widget.")
                || className.startsWith("android.app.")
                || className.equals("android.view.ViewGroup");
    }

    /**
     * Returns true if the given package name represents a system overlay (IME,
     * keyboard, status bar) rather than a real foreground app navigation.
     *
     * Checks the static APP_SWITCH_IGNORE_PACKAGES list first, then uses a
     * ".inputmethod" suffix heuristic for vendor keyboards not explicitly listed,
     * then falls back to a dynamically-resolved set of launcher package names.
     *
     * @param pkg     Package name from the accessibility event
     * @param context Android context â€” used for PackageManager launcher resolution
     * @param tag     Log tag of the calling class
     * @return true if the package should NOT trigger a Reels state reset
     */
    public boolean isSystemOverlayPackage(String pkg, Context context, String tag) {
        if (pkg == null) return false;

        // Check the static ignore list (O(n) but nâ‰¤20 â€” fast enough)
        for (String ignore : APP_SWITCH_IGNORE_PACKAGES) {
            if (ignore.equals(pkg)) return true;
        }

        // Catch vendor keyboards not explicitly listed above (not airtight but covers most)
        if (pkg.endsWith(".inputmethod") || pkg.contains(".inputmethod.")) return true;

        // [STICKY-FIX] Dynamically detect the active home launcher via PackageManager.
        // This catches any OEM launcher not in the static list above.
        lazyInitLaunchers(context, tag);
        return cachedLauncherPackages.contains(pkg);
    }

    /**
     * Lazily initializes {@link #cachedLauncherPackages} on first call.
     * Subsequent calls return immediately (already populated).
     *
     * Queries PackageManager for all activities that handle the HOME intent,
     * collecting their package names into a set.
     *
     * @param context Android context for PackageManager queries
     * @param tag     Log tag of the calling class
     */
    private void lazyInitLaunchers(Context context, String tag) {
        if (cachedLauncherPackages != null) return; // Already resolved

        Set<String> result = new HashSet<>();
        try {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            List<android.content.pm.ResolveInfo> resolveInfos =
                    context.getPackageManager().queryIntentActivities(homeIntent, 0);
            for (android.content.pm.ResolveInfo info : resolveInfos) {
                if (info.activityInfo != null) {
                    result.add(info.activityInfo.packageName);
                }
            }
            Log.d(tag, "[STICKY-FIX] Resolved launcher packages: " + result);
        } catch (Exception e) {
            Log.w(tag, "[STICKY-FIX] resolveLauncherPackages failed: " + e.getMessage());
        }

        cachedLauncherPackages = Collections.unmodifiableSet(result);
    }

    /**
     * Clears the cached launcher packages, forcing re-resolution on the next call
     * to {@link #isSystemOverlayPackage}. Call this if the device's default launcher
     * changes at runtime (e.g., user installs a new launcher app).
     */
    public void clearLauncherCache() {
        cachedLauncherPackages = null;
    }
}
