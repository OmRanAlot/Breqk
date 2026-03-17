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
import android.content.Context;
import android.content.SharedPreferences;
import android.appwidget.AppWidgetManager;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import android.util.Log;

import java.util.HashSet;
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
        SharedPreferences prefs = reactContext.getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
        Set<String> blockedApps = prefs.getStringSet("blocked_apps", new HashSet<>());
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
        SharedPreferences prefs = reactContext.getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("monitoring_enabled", enabled).apply();
        Log.d(TAG, "[SAVE] monitoring_enabled=" + enabled + " saved");
    }

    @ReactMethod
    public void getMonitoringEnabled(com.facebook.react.bridge.Callback callback) {
        Log.d(TAG, "[GET] getMonitoringEnabled called");
        SharedPreferences prefs = reactContext.getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
        // Default to true so that after onboarding the blocker starts as ON
        boolean enabled = prefs.getBoolean("monitoring_enabled", true);
        Log.d(TAG, "[GET] monitoring_enabled=" + enabled);
        callback.invoke(enabled);
    }

    @ReactMethod
    public void getRedirectInstagramToBrowser(Callback callback) {
        Log.d(TAG, "[GET] getRedirectInstagramToBrowser called");
        SharedPreferences prefs = reactContext.getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
        // Default to true so current behavior (always redirect to Reels-free browser) is unchanged
        boolean value = prefs.getBoolean("redirect_instagram_to_browser", true);
        Log.d(TAG, "[GET] redirect_instagram_to_browser=" + value);
        callback.invoke(value);
    }

    @ReactMethod
    public void saveRedirectInstagramToBrowser(boolean value) {
        Log.d(TAG, "[SAVE] saveRedirectInstagramToBrowser called with value=" + value);
        SharedPreferences prefs = reactContext.getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("redirect_instagram_to_browser", value).apply();
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
     * Read by PornBlockerService.getScrollThreshold() at runtime.
     *
     * @param threshold Number of scrolls before intervention popup fires (1–20).
     */
    @ReactMethod
    public void saveScrollThreshold(int threshold) {
        // Clamp to sane range before persisting
        int clamped = Math.max(1, Math.min(20, threshold));
        Log.d(TAG, "[SAVE] saveScrollThreshold called with threshold=" + threshold + " (clamped=" + clamped + ")");
        SharedPreferences prefs = reactContext.getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
        prefs.edit().putInt("scroll_threshold", clamped).apply();
        Log.d(TAG, "[SAVE] scroll_threshold=" + clamped + " saved");
    }

    /**
     * Retrieves the current scroll threshold from SharedPreferences.
     * Returns default value (4) if not yet set.
     */
    @ReactMethod
    public void getScrollThreshold(com.facebook.react.bridge.Callback callback) {
        Log.d(TAG, "[GET] getScrollThreshold called");
        SharedPreferences prefs = reactContext.getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
        int threshold = prefs.getInt("scroll_threshold", 4);
        Log.d(TAG, "[GET] scroll_threshold=" + threshold);
        callback.invoke(threshold);
    }

    @ReactMethod
    public void saveBlockedApps(ReadableArray apps) {
        Log.d(TAG, "[SAVE] saveBlockedApps called with size=" + apps.size());
        SharedPreferences prefs = reactContext.getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        Set<String> appSet = new HashSet<>();

        for (int i = 0; i < apps.size(); i++) {
            appSet.add(apps.getString(i));
            Log.d(TAG, "[SAVE] app[" + i + "]: " + apps.getString(i));
        }
        Log.d(TAG, "[SAVE] saving set size=" + appSet.size() + " data=" + appSet.toString());

        editor.putStringSet("blocked_apps", appSet);
        editor.apply();
        Log.d(TAG, "[SAVE] apply complete");
    }

}
