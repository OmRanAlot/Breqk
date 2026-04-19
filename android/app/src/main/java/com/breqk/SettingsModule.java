package com.breqk;

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
        WidgetPrefs widgetPrefs = new WidgetPrefs(reactContext);
        widgetPrefs.updateWidgetCache(focusScore, timeSavedMin, appsBlocked, monitoringEnabled);
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

}
