package com.breqk.bridge;
import com.breqk.prefs.BreqkPrefs;
import com.breqk.mode.ModeManager;
import com.breqk.widget.BreqkWidgetProvider;
import com.breqk.monitor.AppUsageMonitor;

/*
 * SettingsModule
 * ---------------
 * Lightweight bridge for persisting and retrieving user settings.
 * Currently manages the blocked apps set via SharedPreferences.
 *
 * Notes:
 *  - Uses a single preferences file (breqk_prefs) and key (blocked_apps).
 *  - Writes are applied asynchronously (apply) to avoid main-thread blocking.
 */

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.appwidget.AppWidgetManager;
import android.provider.Settings;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.Promise;
import android.util.Log;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SettingsModule extends ReactContextBaseJavaModule {
    private final ReactApplicationContext reactContext;
    private static final String TAG = "SettingsModule";

    public SettingsModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        Log.d(TAG, "[INIT] SettingsModule initialized");
    }

    @Override
    public String getName() {
        return "SettingsModule";
    }

    @ReactMethod
    public void getBlockedApps(com.facebook.react.bridge.Callback callback) {
        Log.d(TAG, "[GET] getBlockedApps called");
        Set<String> blockedApps = BreqkPrefs.getBlockedApps(reactContext);
        Log.d(TAG, "[GET] returning " + blockedApps.size() + " apps: " + blockedApps.toString());

        // CRITICAL FIX: Convert Set to WritableArray so React Native receives a proper
        // array
        // Previous code used (Object[]) which spread values as separate callback
        // arguments!
        // This caused: callback('app1', 'app2') instead of: callback(['app1', 'app2'])
        WritableArray appsArray = Arguments.createArray();
        for (String app : blockedApps) {
            appsArray.pushString(app);
        }
        callback.invoke(appsArray);
    }

    @ReactMethod
    public void saveMonitoringEnabled(boolean enabled) {
        Log.d(TAG, "[SAVE] saveMonitoringEnabled called with enabled=" + enabled);
        SharedPreferences prefs = BreqkPrefs.get(reactContext);
        prefs.edit().putBoolean(BreqkPrefs.KEY_MONITORING_ENABLED, enabled).apply();
        Log.d(TAG, "[SAVE] monitoring_enabled=" + enabled + " saved");
    }

    @ReactMethod
    public void getMonitoringEnabled(com.facebook.react.bridge.Callback callback) {
        Log.d(TAG, "[GET] getMonitoringEnabled called");
        SharedPreferences prefs = BreqkPrefs.get(reactContext);
        // Default to true so that after onboarding the blocker starts as ON
        boolean enabled = prefs.getBoolean(BreqkPrefs.KEY_MONITORING_ENABLED, true);
        Log.d(TAG, "[GET] monitoring_enabled=" + enabled);
        callback.invoke(enabled);
    }

    @ReactMethod
    public void getRedirectInstagramToBrowser(Callback callback) {
        Log.d(TAG, "[GET] getRedirectInstagramToBrowser called");
        SharedPreferences prefs = BreqkPrefs.get(reactContext);
        // Default to true so current behavior (always redirect to Reels-free browser) is unchanged
        boolean value = prefs.getBoolean(BreqkPrefs.KEY_REDIRECT_INSTAGRAM, true);
        Log.d(TAG, "[GET] redirect_instagram_to_browser=" + value);
        callback.invoke(value);
    }

    @ReactMethod
    public void saveRedirectInstagramToBrowser(boolean value) {
        Log.d(TAG, "[SAVE] saveRedirectInstagramToBrowser called with value=" + value);
        SharedPreferences prefs = BreqkPrefs.get(reactContext);
        prefs.edit().putBoolean(BreqkPrefs.KEY_REDIRECT_INSTAGRAM, value).apply();
        Log.d(TAG, "[SAVE] redirect_instagram_to_browser=" + value + " saved");
    }

    @ReactMethod
    public void updateWidgetStats(int focusScore, int timeSavedMin, int appsBlocked, boolean monitoringEnabled) {
        Log.d(TAG, "[WIDGET] updateWidgetStats focusScore=" + focusScore + " timeSavedMin=" + timeSavedMin + " appsBlocked=" + appsBlocked + " monitoring=" + monitoringEnabled);
        BreqkPrefs.updateWidgetCache(reactContext, focusScore, timeSavedMin, appsBlocked, monitoringEnabled);
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(reactContext);
        ComponentName provider = new ComponentName(reactContext, BreqkWidgetProvider.class);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(provider);
        if (appWidgetIds != null && appWidgetIds.length > 0) {
            reactContext.sendBroadcast(new android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds));
        }
    }

    /**
     * Persists the scroll threshold for Reels/Shorts intervention.
     * Read by ContentFilterService.getScrollThreshold() at runtime.
     *
     * @param threshold Number of scrolls before intervention popup fires (1–20).
     */
    @ReactMethod
    public void saveScrollThreshold(int threshold) {
        // Clamp to sane range before persisting
        int clamped = Math.max(1, Math.min(20, threshold));
        Log.d(TAG, "[SAVE] saveScrollThreshold called with threshold=" + threshold + " (clamped=" + clamped + ")");
        SharedPreferences prefs = BreqkPrefs.get(reactContext);
        prefs.edit().putInt(BreqkPrefs.KEY_SCROLL_THRESHOLD, clamped).apply();
        Log.d(TAG, "[SAVE] scroll_threshold=" + clamped + " saved");
    }

    /**
     * Retrieves the current scroll threshold from SharedPreferences.
     * Returns default value (4) if not yet set.
     */
    @ReactMethod
    public void getScrollThreshold(com.facebook.react.bridge.Callback callback) {
        Log.d(TAG, "[GET] getScrollThreshold called");
        SharedPreferences prefs = BreqkPrefs.get(reactContext);
        int threshold = prefs.getInt(BreqkPrefs.KEY_SCROLL_THRESHOLD, 4);
        Log.d(TAG, "[GET] scroll_threshold=" + threshold);
        callback.invoke(threshold);
    }

    /**
     * Persists scroll budget configuration to SharedPreferences.
     * Read by AppUsageMonitor.loadScrollBudgetFromPrefs() on service start.
     *
     * @param allowanceMinutes Minutes of scroll allowed per window (clamped 1–30)
     * @param windowMinutes    Window duration in minutes (clamped 15–120)
     */
    @ReactMethod
    public void saveScrollBudget(int allowanceMinutes, int windowMinutes) {
        int clampedAllowance = Math.max(1, Math.min(30, allowanceMinutes));
        int clampedWindow = Math.max(15, Math.min(120, windowMinutes));
        Log.d(TAG, "[SAVE] saveScrollBudget allowance=" + clampedAllowance + "min window=" + clampedWindow + "min");
        SharedPreferences prefs = BreqkPrefs.get(reactContext);
        prefs.edit()
                .putInt(BreqkPrefs.KEY_SCROLL_ALLOWANCE_MINUTES, clampedAllowance)
                .putInt(BreqkPrefs.KEY_SCROLL_WINDOW_MINUTES, clampedWindow)
                .apply();
        Log.d(TAG, "[SAVE] scroll budget saved");
    }

    /**
     * Retrieves scroll budget configuration from SharedPreferences.
     * Invokes callback with (allowanceMinutes, windowMinutes).
     * Defaults: allowance=5, window=60.
     */
    @ReactMethod
    public void getScrollBudget(Callback callback) {
        Log.d(TAG, "[GET] getScrollBudget called");
        SharedPreferences prefs = BreqkPrefs.get(reactContext);
        int allowanceMinutes = prefs.getInt(BreqkPrefs.KEY_SCROLL_ALLOWANCE_MINUTES, 5);
        int windowMinutes = prefs.getInt(BreqkPrefs.KEY_SCROLL_WINDOW_MINUTES, 60);
        Log.d(TAG, "[GET] scroll budget: allowance=" + allowanceMinutes + "min window=" + windowMinutes + "min");
        callback.invoke(allowanceMinutes, windowMinutes);
    }

    /**
     * Persists the Instagram home feed post limit.
     * When this many debounced swipes are detected on the home feed, the
     * "Time is up!" intervention fires. Clamped to 5–100.
     */
    @ReactMethod
    public void saveHomeFeedPostLimit(int limit) {
        int clamped = Math.max(5, Math.min(100, limit));
        Log.d(TAG, "[SAVE] saveHomeFeedPostLimit limit=" + limit + " (clamped=" + clamped + ")");
        BreqkPrefs.get(reactContext).edit()
                .putInt(BreqkPrefs.KEY_HOME_FEED_POST_LIMIT, clamped)
                .apply();
    }

    /**
     * Retrieves the current Instagram home feed post limit.
     * Returns default (20) if not yet set.
     */
    @ReactMethod
    public void getHomeFeedPostLimit(Callback callback) {
        Log.d(TAG, "[GET] getHomeFeedPostLimit called");
        int limit = BreqkPrefs.get(reactContext)
                .getInt(BreqkPrefs.KEY_HOME_FEED_POST_LIMIT, BreqkPrefs.DEFAULT_HOME_FEED_POST_LIMIT);
        Log.d(TAG, "[GET] home_feed_post_limit=" + limit);
        callback.invoke(limit);
    }

    /**
     * Persists the "20-Min Free Break" feature toggle.
     * When false (default), the break button is hidden on the Home screen.
     */
    @ReactMethod
    public void saveFreeBreakEnabled(boolean enabled) {
        Log.d(TAG, "[SAVE] saveFreeBreakEnabled called with enabled=" + enabled);
        BreqkPrefs.get(reactContext).edit()
                .putBoolean(BreqkPrefs.KEY_FREE_BREAK_ENABLED, enabled)
                .apply();
        Log.d(TAG, "[SAVE] free_break_enabled=" + enabled + " saved");
    }

    /**
     * Retrieves the "20-Min Free Break" feature toggle.
     * Defaults to false so existing users are unaffected on first launch.
     */
    @ReactMethod
    public void getFreeBreakEnabled(com.facebook.react.bridge.Callback callback) {
        Log.d(TAG, "[GET] getFreeBreakEnabled called");
        boolean enabled = BreqkPrefs.get(reactContext)
                .getBoolean(BreqkPrefs.KEY_FREE_BREAK_ENABLED, false);
        Log.d(TAG, "[GET] free_break_enabled=" + enabled);
        callback.invoke(enabled);
    }

    /**
     * Persists the deletion-prevention (uninstall lock) feature toggle.
     * When false (default), ReelsInterventionService never inspects the Settings
     * uninstall screen and the lock screen never appears.
     */
    @ReactMethod
    public void saveUninstallLockEnabled(boolean enabled) {
        Log.d(TAG, "[SAVE] saveUninstallLockEnabled called with enabled=" + enabled);
        BreqkPrefs.setUninstallLockEnabled(reactContext, enabled);
        Log.d(TAG, "[SAVE] uninstall_lock_enabled=" + enabled + " saved");
    }

    /**
     * Retrieves the deletion-prevention toggle.
     * Defaults to false so existing users are unaffected until they opt in.
     */
    @ReactMethod
    public void getUninstallLockEnabled(com.facebook.react.bridge.Callback callback) {
        Log.d(TAG, "[GET] getUninstallLockEnabled called");
        boolean enabled = BreqkPrefs.isUninstallLockEnabled(reactContext);
        Log.d(TAG, "[GET] uninstall_lock_enabled=" + enabled);
        callback.invoke(enabled);
    }

    @ReactMethod
    public void saveBlockedApps(ReadableArray apps) {
        Log.d(TAG, "[SAVE] saveBlockedApps called with size=" + apps.size());
        SharedPreferences prefs = BreqkPrefs.get(reactContext);
        SharedPreferences.Editor editor = prefs.edit();
        Set<String> appSet = new HashSet<>();

        for (int i = 0; i < apps.size(); i++) {
            appSet.add(apps.getString(i));
            Log.d(TAG, "[SAVE] app[" + i + "]: " + apps.getString(i));
        }
        Log.d(TAG, "[SAVE] saving set size=" + appSet.size() + " data=" + appSet.toString());

        editor.putStringSet(BreqkPrefs.KEY_BLOCKED_APPS, appSet);
        editor.apply();
        Log.d(TAG, "[SAVE] apply complete");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Per-App Policy methods
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the full app policies JSON string to React Native.
     * JS receives a JSON string that can be parsed with JSON.parse().
     *
     * Logging: [POLICY]
     */
    @ReactMethod
    public void getAppPolicies(Callback callback) {
        Log.d(TAG, "[POLICY] getAppPolicies called");
        String json = BreqkPrefs.get(reactContext).getString(BreqkPrefs.KEY_APP_POLICIES, "{}");
        Log.d(TAG, "[POLICY] returning: " + json);
        callback.invoke(json);
    }

    /**
     * Saves the full app policies from a JSON string received from React Native.
     * Also triggers legacy blocked_apps sync.
     *
     * @param jsonString Full policies JSON, e.g. {"com.instagram.android":{"app_open_intercept":true,...}}
     */
    @ReactMethod
    public void saveAppPolicies(String jsonString) {
        Log.d(TAG, "[POLICY] saveAppPolicies called");
        try {
            // Parse to validate, then re-save through BreqkPrefs helper (which handles sync)
            JSONObject parsed = new JSONObject(jsonString);
            BreqkPrefs.get(reactContext).edit()
                    .putString(BreqkPrefs.KEY_APP_POLICIES, parsed.toString())
                    .apply();
            // Sync legacy blocked_apps
            BreqkPrefs.syncBlockedAppsFromPolicies(reactContext);
            // Notify running monitors so the change takes effect live
            BreqkPrefs.dispatchBlockedAppsReload(reactContext);
            Log.d(TAG, "[POLICY] saveAppPolicies saved + synced blocked_apps");
        } catch (Exception e) {
            Log.e(TAG, "[POLICY] saveAppPolicies error: " + e.getMessage());
        }
    }

    /**
     * Atomically updates a single feature for a single app.
     * More efficient than sending the full policy map for a single toggle change.
     *
     * @param packageName e.g. "com.instagram.android"
     * @param featureKey  e.g. "app_open_intercept", "reels_detection"
     * @param enabled     true/false
     */
    @ReactMethod
    public void setAppFeature(String packageName, String featureKey, boolean enabled, Promise promise) {
        Log.d(TAG, "[POLICY] setAppFeature pkg=" + packageName + " " + featureKey + "=" + enabled);
        try {
            BreqkPrefs.setAppFeature(reactContext, packageName, featureKey, enabled);
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[POLICY] setAppFeature failed: " + e.getMessage());
            promise.reject("SET_APP_FEATURE_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void getAppInterceptSettings(String packageName, Promise promise) {
        try {
            org.json.JSONObject entry = BreqkPrefs.getAppInterceptSettings(reactContext, packageName);
            com.facebook.react.bridge.WritableMap map = com.facebook.react.bridge.Arguments.createMap();
            map.putString("message", entry.optString("message", ""));
            map.putInt("delaySecs", entry.optInt("delay_secs", BreqkPrefs.DEFAULT_DELAY_TIME_SECONDS));
            map.putInt("popupDelayMin", entry.optInt("popup_delay_min", BreqkPrefs.DEFAULT_POPUP_DELAY_MINUTES));
            Log.d(TAG, "[INTERCEPT_SETTINGS] getAppInterceptSettings pkg=" + packageName);
            promise.resolve(map);
        } catch (Exception e) {
            Log.e(TAG, "[INTERCEPT_SETTINGS] getAppInterceptSettings failed: " + e.getMessage());
            promise.reject("GET_INTERCEPT_SETTINGS_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void setAppInterceptSettings(String packageName, String message, int delaySecs, int popupDelayMin, Promise promise) {
        try {
            BreqkPrefs.setAppInterceptSettings(reactContext, packageName, message, delaySecs, popupDelayMin);
            Log.d(TAG, "[INTERCEPT_SETTINGS] setAppInterceptSettings pkg=" + packageName
                    + " delaySecs=" + delaySecs + " popupDelayMin=" + popupDelayMin);
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[INTERCEPT_SETTINGS] setAppInterceptSettings failed: " + e.getMessage());
            promise.reject("SET_INTERCEPT_SETTINGS_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void setAllAppsInterceptSettings(String message, int delaySecs, int popupDelayMin, Promise promise) {
        try {
            BreqkPrefs.setAllAppsInterceptSettings(reactContext, message, delaySecs, popupDelayMin);
            Log.d(TAG, "[INTERCEPT_SETTINGS] setAllAppsInterceptSettings delaySecs=" + delaySecs
                    + " popupDelayMin=" + popupDelayMin);
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[INTERCEPT_SETTINGS] setAllAppsInterceptSettings failed: " + e.getMessage());
            promise.reject("SET_ALL_INTERCEPT_SETTINGS_FAILED", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Mode methods
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the full modes JSON string to React Native.
     */
    @ReactMethod
    public void getModes(Callback callback) {
        Log.d(TAG, "[MODE] getModes called");
        String json = BreqkPrefs.get(reactContext).getString(BreqkPrefs.KEY_MODES, "{}");
        Log.d(TAG, "[MODE] returning: " + json);
        callback.invoke(json);
    }

    /**
     * Saves the full modes JSON from React Native.
     */
    @ReactMethod
    public void saveModes(String jsonString) {
        Log.d(TAG, "[MODE] saveModes called");
        try {
            JSONObject parsed = new JSONObject(jsonString);
            BreqkPrefs.saveModes(reactContext, parsed);
            Log.d(TAG, "[MODE] saveModes saved");
        } catch (Exception e) {
            Log.e(TAG, "[MODE] saveModes error: " + e.getMessage());
        }
    }

    /**
     * Returns the currently active mode ID (empty string if none).
     */
    @ReactMethod
    public void getActiveMode(Callback callback) {
        String modeId = BreqkPrefs.getActiveMode(reactContext);
        Log.d(TAG, "[MODE] getActiveMode → " + modeId);
        callback.invoke(modeId);
    }

    /**
     * Activates a mode by ID. Deactivates any previously active mode.
     * Triggers blocked_apps sync and notifies MyVpnService.
     */
    @ReactMethod
    public void activateMode(String modeId, Promise promise) {
        Log.d(TAG, "[MODE] activateMode called with modeId=" + modeId);
        try {
            ModeManager.activate(reactContext, modeId, "manual");
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[MODE] activateMode error: " + e.getMessage());
            promise.reject("MODE_ERROR", e.getMessage());
        }
    }

    /**
     * Deactivates the currently active mode.
     * Reverts to base policies and syncs blocked_apps.
     */
    @ReactMethod
    public void deactivateMode(Promise promise) {
        Log.d(TAG, "[MODE] deactivateMode called");
        try {
            ModeManager.deactivate(reactContext);
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[MODE] deactivateMode error: " + e.getMessage());
            promise.reject("MODE_ERROR", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Browser content filter methods
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Enables or disables the browser content filter.
     * ContentFilterService checks this pref on every accessibility event.
     */
    @ReactMethod
    public void saveContentFilterEnabled(boolean enabled, Promise promise) {
        Log.d(TAG, "[FILTER] saveContentFilterEnabled=" + enabled);
        try {
            BreqkPrefs.setContentFilterEnabled(reactContext, enabled);
            promise.resolve(null);
        } catch (Exception e) {
            Log.e(TAG, "[FILTER] saveContentFilterEnabled error: " + e.getMessage());
            promise.reject("FILTER_ERROR", e.getMessage());
        }
    }

    /**
     * Returns the current content filter enabled state (default true if never set).
     */
    @ReactMethod
    public void getContentFilterEnabled(Callback callback) {
        boolean enabled = BreqkPrefs.isContentFilterEnabled(reactContext);
        Log.d(TAG, "[FILTER] getContentFilterEnabled=" + enabled);
        callback.invoke(enabled);
    }

    /**
     * Returns true if ContentFilterService is currently enabled in Android
     * Accessibility Settings (i.e. the user has granted the permission).
     * This is independent of the content_filter_enabled pref.
     */
    @ReactMethod
    public void isContentFilterServiceEnabled(Callback callback) {
        try {
            String enabled = Settings.Secure.getString(
                    reactContext.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            // ContentFilterService merged into ReelsInterventionService — single toggle.
            boolean active = enabled != null &&
                    enabled.contains("com.breqk/com.breqk.ReelsInterventionService");
            Log.d(TAG, "[FILTER] isContentFilterServiceEnabled=" + active);
            callback.invoke(active);
        } catch (Exception e) {
            Log.e(TAG, "[FILTER] isContentFilterServiceEnabled error: " + e.getMessage());
            callback.invoke(false);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Intercept message & delay getters
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Retrieves the saved intercept message from SharedPreferences.
     * Returns default "Is this intentional?" if not yet set.
     */
    @ReactMethod
    public void getDelayMessage(Callback callback) {
        Log.d(TAG, "[GET] getDelayMessage called");
        SharedPreferences prefs = BreqkPrefs.get(reactContext);
        String message = prefs.getString(BreqkPrefs.KEY_DELAY_MESSAGE, "Is this intentional?");
        Log.d(TAG, "[GET] delay_message=" + message);
        callback.invoke(message);
    }

    /**
     * Retrieves the saved forced pause duration from SharedPreferences.
     * Returns default (15 seconds) if not yet set.
     */
    @ReactMethod
    public void getDelayTime(Callback callback) {
        Log.d(TAG, "[GET] getDelayTime called");
        SharedPreferences prefs = BreqkPrefs.get(reactContext);
        int seconds = prefs.getInt(BreqkPrefs.KEY_DELAY_TIME_SECONDS, 15);
        Log.d(TAG, "[GET] delay_time_seconds=" + seconds);
        callback.invoke(seconds);
    }

}
