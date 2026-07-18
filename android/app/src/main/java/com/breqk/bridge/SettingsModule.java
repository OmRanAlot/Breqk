package com.Break.bridge;
import com.Break.prefs.BreakPrefs;
import com.Break.mode.ModeManager;
import com.Break.widget.BreakWidgetProvider;
import com.Break.monitor.AppUsageMonitor;
import com.Break.lock.ContentFilterGuard;
import com.Break.lock.SettingsLockManager;

/*
 * SettingsModule
 * ---------------
 * Lightweight bridge for persisting and retrieving user settings.
 * Currently manages the blocked apps set via SharedPreferences.
 *
 * Notes:
 *  - Uses a single preferences file (Break_prefs) and key (blocked_apps).
 *  - Writes are applied asynchronously (apply) to avoid main-thread blocking.
 *  - Settings writes are immediate. Impulse friction is provided separately by the
 *    opt-in Settings Change Lock (see com.Break.lock.SettingsLockManager), which
 *    makes a whole SCOPE (global or a per-app screen) read-only for a while AFTER
 *    the user edits and leaves it — it does not gate individual writes here.
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
import com.facebook.react.bridge.WritableMap;
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

    /**
     * Rejection message returned to JS when a base-settings write is attempted
     * while a non-default mode owns the settings. The UI blocks these writes
     * up front; this is the enforcement backstop, so the copy still has to be
     * user-presentable in case a screen ever surfaces it.
     */
    private static final String MODE_GATE_MESSAGE =
            "A mode is active. Switch to Default mode to change these settings.";

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
        Set<String> blockedApps = BreakPrefs.getBlockedApps(reactContext);
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
        if (!BreakPrefs.assertBaseSettingsEditable(reactContext, "saveMonitoringEnabled")) return;
        BreakPrefs.get(reactContext).edit()
                .putBoolean(BreakPrefs.KEY_MONITORING_ENABLED, enabled)
                .apply();
        Log.d(TAG, "[SAVE] monitoring_enabled=" + enabled + " saved");
    }

    @ReactMethod
    public void getMonitoringEnabled(com.facebook.react.bridge.Callback callback) {
        Log.d(TAG, "[GET] getMonitoringEnabled called");
        SharedPreferences prefs = BreakPrefs.get(reactContext);
        // Default to true so that after onboarding the blocker starts as ON
        boolean enabled = prefs.getBoolean(BreakPrefs.KEY_MONITORING_ENABLED, true);
        Log.d(TAG, "[GET] monitoring_enabled=" + enabled);
        callback.invoke(enabled);
    }

    @ReactMethod
    public void getRedirectInstagramToBrowser(Callback callback) {
        Log.d(TAG, "[GET] getRedirectInstagramToBrowser called");
        SharedPreferences prefs = BreakPrefs.get(reactContext);
        // Default to true so current behavior (always redirect to Reels-free browser) is unchanged
        boolean value = prefs.getBoolean(BreakPrefs.KEY_REDIRECT_INSTAGRAM, true);
        Log.d(TAG, "[GET] redirect_instagram_to_browser=" + value);
        callback.invoke(value);
    }

    @ReactMethod
    public void saveRedirectInstagramToBrowser(boolean value) {
        Log.d(TAG, "[SAVE] saveRedirectInstagramToBrowser called with value=" + value);
        if (!BreakPrefs.assertBaseSettingsEditable(reactContext, "saveRedirectInstagramToBrowser")) return;
        SharedPreferences prefs = BreakPrefs.get(reactContext);
        prefs.edit().putBoolean(BreakPrefs.KEY_REDIRECT_INSTAGRAM, value).apply();
        Log.d(TAG, "[SAVE] redirect_instagram_to_browser=" + value + " saved");
    }

    @ReactMethod
    public void updateWidgetStats(int focusScore, int timeSavedMin, int appsBlocked, boolean monitoringEnabled) {
        Log.d(TAG, "[WIDGET] updateWidgetStats focusScore=" + focusScore + " timeSavedMin=" + timeSavedMin + " appsBlocked=" + appsBlocked + " monitoring=" + monitoringEnabled);
        BreakPrefs.updateWidgetCache(reactContext, focusScore, timeSavedMin, appsBlocked, monitoringEnabled);
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(reactContext);
        ComponentName provider = new ComponentName(reactContext, BreakWidgetProvider.class);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(provider);
        if (appWidgetIds != null && appWidgetIds.length > 0) {
            reactContext.sendBroadcast(new android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds));
        }
    }

    /**
     * Persists the scroll threshold for Reels/Shorts intervention.
     * Read by ReelsInterventionService at runtime.
     *
     * @param threshold Number of scrolls before intervention popup fires (1–20).
     */
    @ReactMethod
    public void saveScrollThreshold(int threshold) {
        // Clamp to sane range before persisting
        int clamped = Math.max(1, Math.min(20, threshold));
        Log.d(TAG, "[SAVE] saveScrollThreshold threshold=" + threshold + " (clamped=" + clamped + ")");
        if (!BreakPrefs.assertBaseSettingsEditable(reactContext, "saveScrollThreshold")) return;
        BreakPrefs.get(reactContext).edit()
                .putInt(BreakPrefs.KEY_SCROLL_THRESHOLD, clamped)
                .apply();
    }

    /**
     * Retrieves the current scroll threshold from SharedPreferences.
     * Returns default value (4) if not yet set.
     */
    @ReactMethod
    public void getScrollThreshold(com.facebook.react.bridge.Callback callback) {
        Log.d(TAG, "[GET] getScrollThreshold called");
        SharedPreferences prefs = BreakPrefs.get(reactContext);
        int threshold = prefs.getInt(BreakPrefs.KEY_SCROLL_THRESHOLD, 4);
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
        Log.d(TAG, "[SAVE] saveScrollBudget allowance=" + clampedAllowance + "min window="
                + clampedWindow + "min");
        if (!BreakPrefs.assertBaseSettingsEditable(reactContext, "saveScrollBudget")) return;
        BreakPrefs.get(reactContext).edit()
                .putInt(BreakPrefs.KEY_SCROLL_ALLOWANCE_MINUTES, clampedAllowance)
                .putInt(BreakPrefs.KEY_SCROLL_WINDOW_MINUTES, clampedWindow)
                .apply();
    }

    /**
     * Retrieves scroll budget configuration from SharedPreferences.
     * Invokes callback with (allowanceMinutes, windowMinutes).
     * Defaults: allowance=5, window=60.
     */
    @ReactMethod
    public void getScrollBudget(Callback callback) {
        Log.d(TAG, "[GET] getScrollBudget called");
        SharedPreferences prefs = BreakPrefs.get(reactContext);
        int allowanceMinutes = prefs.getInt(BreakPrefs.KEY_SCROLL_ALLOWANCE_MINUTES, 5);
        int windowMinutes = prefs.getInt(BreakPrefs.KEY_SCROLL_WINDOW_MINUTES, 60);
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
        if (!BreakPrefs.assertBaseSettingsEditable(reactContext, "saveHomeFeedPostLimit")) return;
        BreakPrefs.get(reactContext).edit()
                .putInt(BreakPrefs.KEY_HOME_FEED_POST_LIMIT, clamped)
                .apply();
    }

    /**
     * Retrieves the current Instagram home feed post limit.
     * Returns default (30) if not yet set.
     */
    @ReactMethod
    public void getHomeFeedPostLimit(Callback callback) {
        Log.d(TAG, "[GET] getHomeFeedPostLimit called");
        int limit = BreakPrefs.get(reactContext)
                .getInt(BreakPrefs.KEY_HOME_FEED_POST_LIMIT, BreakPrefs.DEFAULT_HOME_FEED_POST_LIMIT);
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
        if (!BreakPrefs.assertBaseSettingsEditable(reactContext, "saveFreeBreakEnabled")) return;
        BreakPrefs.get(reactContext).edit()
                .putBoolean(BreakPrefs.KEY_FREE_BREAK_ENABLED, enabled)
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
        boolean enabled = BreakPrefs.get(reactContext)
                .getBoolean(BreakPrefs.KEY_FREE_BREAK_ENABLED, false);
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
        BreakPrefs.setUninstallLockEnabled(reactContext, enabled);
        Log.d(TAG, "[SAVE] uninstall_lock_enabled=" + enabled + " saved");
    }

    /**
     * Retrieves the deletion-prevention toggle.
     * Defaults to false so existing users are unaffected until they opt in.
     */
    @ReactMethod
    public void getUninstallLockEnabled(com.facebook.react.bridge.Callback callback) {
        Log.d(TAG, "[GET] getUninstallLockEnabled called");
        boolean enabled = BreakPrefs.isUninstallLockEnabled(reactContext);
        Log.d(TAG, "[GET] uninstall_lock_enabled=" + enabled);
        callback.invoke(enabled);
    }

    @ReactMethod
    public void saveBlockedApps(ReadableArray apps) {
        Log.d(TAG, "[SAVE] saveBlockedApps called with size=" + apps.size());
        SharedPreferences prefs = BreakPrefs.get(reactContext);
        SharedPreferences.Editor editor = prefs.edit();
        Set<String> appSet = new HashSet<>();

        for (int i = 0; i < apps.size(); i++) {
            appSet.add(apps.getString(i));
            Log.d(TAG, "[SAVE] app[" + i + "]: " + apps.getString(i));
        }
        Log.d(TAG, "[SAVE] saving set size=" + appSet.size() + " data=" + appSet.toString());

        editor.putStringSet(BreakPrefs.KEY_BLOCKED_APPS, appSet);
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
        String json = BreakPrefs.get(reactContext).getString(BreakPrefs.KEY_APP_POLICIES, "{}");
        Log.d(TAG, "[POLICY] returning: " + json);
        callback.invoke(json);
    }

    /**
     * Saves the full app policies from a JSON string received from React Native.
     * Also triggers legacy blocked_apps sync. Applied immediately.
     *
     * @param jsonString Full policies JSON, e.g. {"com.instagram.android":{"app_open_intercept":true,...}}
     */
    @ReactMethod
    public void saveAppPolicies(String jsonString) {
        Log.d(TAG, "[POLICY] saveAppPolicies called");
        if (!BreakPrefs.assertBaseSettingsEditable(reactContext, "saveAppPolicies")) return;
        try {
            BreakPrefs.get(reactContext).edit()
                    .putString(BreakPrefs.KEY_APP_POLICIES, jsonString)
                    .apply();
            BreakPrefs.syncBlockedAppsFromPolicies(reactContext);
            BreakPrefs.dispatchBlockedAppsReload(reactContext);
            Log.d(TAG, "[POLICY] saveAppPolicies applied=" + jsonString);
        } catch (Exception e) {
            Log.e(TAG, "[POLICY] saveAppPolicies error: " + e.getMessage());
        }
    }

    /**
     * Atomically updates a single feature for a single app. Applied immediately.
     *
     * @param packageName e.g. "com.instagram.android"
     * @param featureKey  e.g. "app_open_intercept", "reels_detection"
     * @param enabled     true/false
     */
    @ReactMethod
    public void setAppFeature(String packageName, String featureKey, boolean enabled, Promise promise) {
        Log.d(TAG, "[POLICY] setAppFeature pkg=" + packageName + " " + featureKey + "=" + enabled);
        if (!BreakPrefs.assertBaseSettingsEditable(reactContext, "setAppFeature")) {
            promise.reject("MODE_ACTIVE", MODE_GATE_MESSAGE);
            return;
        }
        try {
            BreakPrefs.setAppFeature(reactContext, packageName, featureKey, enabled);
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[POLICY] setAppFeature failed: " + e.getMessage());
            promise.reject("SET_APP_FEATURE_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void getAppInterceptSettings(String packageName, Promise promise) {
        try {
            org.json.JSONObject entry = BreakPrefs.getAppInterceptSettings(reactContext, packageName);
            com.facebook.react.bridge.WritableMap map = com.facebook.react.bridge.Arguments.createMap();
            map.putString("message", entry.optString("message", ""));
            map.putInt("delaySecs", BreakPrefs.getEffectiveDelaySecs(reactContext, packageName));
            map.putBoolean("hasDelayOverride", BreakPrefs.hasPerAppInterceptSettings(reactContext, packageName)
                    && entry.has("delay_secs"));
            map.putInt("popupDelayMin", entry.has("popup_delay_min")
                    ? entry.optInt("popup_delay_min", BreakPrefs.DEFAULT_POPUP_DELAY_MINUTES)
                    : BreakPrefs.get(reactContext).getInt(
                            BreakPrefs.KEY_POPUP_DELAY_MINUTES, BreakPrefs.DEFAULT_POPUP_DELAY_MINUTES));
            Log.d(TAG, "[INTERCEPT_SETTINGS] getAppInterceptSettings pkg=" + packageName
                    + " delaySecs=" + map.getInt("delaySecs")
                    + " hasDelayOverride=" + map.getBoolean("hasDelayOverride"));
            promise.resolve(map);
        } catch (Exception e) {
            Log.e(TAG, "[INTERCEPT_SETTINGS] getAppInterceptSettings failed: " + e.getMessage());
            promise.reject("GET_INTERCEPT_SETTINGS_FAILED", e.getMessage());
        }
    }

    /**
     * Saves per-app intercept settings (message, pause length, popup frequency).
     * Applied immediately.
     */
    @ReactMethod
    public void setAppInterceptSettings(String packageName, String message, int delaySecs, int popupDelayMin, Promise promise) {
        if (!BreakPrefs.assertBaseSettingsEditable(reactContext, "setAppInterceptSettings")) {
            promise.reject("MODE_ACTIVE", MODE_GATE_MESSAGE);
            return;
        }
        try {
            int clampedNew = BreakPrefs.clampDelaySecs(delaySecs);
            BreakPrefs.setAppInterceptSettings(reactContext, packageName, message, clampedNew, popupDelayMin);
            Log.d(TAG, "[INTERCEPT_SETTINGS] setAppInterceptSettings pkg=" + packageName
                    + " delay=" + clampedNew + " popupDelayMin=" + popupDelayMin);
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[INTERCEPT_SETTINGS] setAppInterceptSettings failed: " + e.getMessage());
            promise.reject("SET_INTERCEPT_SETTINGS_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void setAllAppsInterceptSettings(String message, int delaySecs, int popupDelayMin, Promise promise) {
        if (!BreakPrefs.assertBaseSettingsEditable(reactContext, "setAllAppsInterceptSettings")) {
            promise.reject("MODE_ACTIVE", MODE_GATE_MESSAGE);
            return;
        }
        try {
            int clampedNew = BreakPrefs.clampDelaySecs(delaySecs);
            BreakPrefs.setAllAppsInterceptSettings(reactContext, message, clampedNew, popupDelayMin);
            Log.d(TAG, "[INTERCEPT_SETTINGS] setAllAppsInterceptSettings delay=" + clampedNew
                    + " popupDelayMin=" + popupDelayMin);
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[INTERCEPT_SETTINGS] setAllAppsInterceptSettings failed: " + e.getMessage());
            promise.reject("SET_ALL_INTERCEPT_SETTINGS_FAILED", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Mindful Viewing Coach (YouTube typing gate) toggle
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Whether the YouTube typing coach is enabled. When ON, the coach IS YouTube's
     * App Open Intercept (initial + every-X-min re-fire); when OFF, YouTube gets
     * the ordinary delay overlay like every other app.
     */
    @ReactMethod
    public void getCoachEnabled(Promise promise) {
        try {
            boolean enabled = BreakPrefs.isCoachEnabled(reactContext);
            Log.d(TAG, "[COACH] getCoachEnabled → " + enabled);
            promise.resolve(enabled);
        } catch (Exception e) {
            Log.e(TAG, "[COACH] getCoachEnabled failed: " + e.getMessage());
            promise.reject("GET_COACH_ENABLED_FAILED", e.getMessage());
        }
    }

    /** Enables/disables the YouTube typing coach. Applied immediately. */
    @ReactMethod
    public void setCoachEnabled(boolean enabled, Promise promise) {
        if (!BreakPrefs.assertBaseSettingsEditable(reactContext, "setCoachEnabled")) {
            promise.reject("MODE_ACTIVE", MODE_GATE_MESSAGE);
            return;
        }
        try {
            BreakPrefs.setCoachEnabled(reactContext, enabled);
            Log.i(TAG, "[COACH] setCoachEnabled → " + enabled);
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[COACH] setCoachEnabled failed: " + e.getMessage());
            promise.reject("SET_COACH_ENABLED_FAILED", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings Change Lock methods (opt-in per-scope edit lock)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the lock state for ONE scope as a JSON string:
     * { enabled, locked, lockUntil (epoch ms), durationMs }.
     * scope is "global" or a managed app's package name.
     */
    @ReactMethod
    public void getSettingsLockState(String scope, Callback callback) {
        String json = SettingsLockManager.getStateJson(reactContext, scope);
        Log.d(TAG, "[SETTINGS_LOCK] getSettingsLockState scope=" + scope + " → " + json);
        callback.invoke(json);
    }

    /** Enables/disables the whole feature. Disabling instantly unlocks every scope. */
    @ReactMethod
    public void setSettingsLockEnabled(boolean enabled) {
        Log.d(TAG, "[SETTINGS_LOCK] setSettingsLockEnabled=" + enabled);
        SettingsLockManager.setEnabled(reactContext, enabled);
    }

    /** Reads the feature toggle (default false). */
    @ReactMethod
    public void getSettingsLockEnabled(Callback callback) {
        boolean enabled = SettingsLockManager.isEnabled(reactContext);
        Log.d(TAG, "[SETTINGS_LOCK] getSettingsLockEnabled=" + enabled);
        callback.invoke(enabled);
    }

    /** Sets the lock length in HOURS (clamped 24–168). Applies to the next lock that starts. */
    @ReactMethod
    public void setSettingsLockDuration(int hours) {
        long ms = (long) hours * 60L * 60L * 1000L;
        SettingsLockManager.setDurationMs(reactContext, ms);
        Log.d(TAG, "[SETTINGS_LOCK] setSettingsLockDuration hours=" + hours);
    }

    /**
     * Starts (or restarts) the lock for {@code scope}. The JS layer calls this when
     * the user has edited the scope and is leaving the screen. No-op if the feature
     * is off. scope is "global" or a managed app's package name.
     */
    @ReactMethod
    public void startSettingsLock(String scope) {
        Log.d(TAG, "[SETTINGS_LOCK] startSettingsLock scope=" + scope);
        SettingsLockManager.startLock(reactContext, scope);
    }

    /**
     * No-op. Re-arm is removed; grace is always 0. Kept as a safe bridge stub
     * so any cached JS that still calls this does not throw.
     */
    @ReactMethod
    public void setSettingsLockGrace(int hours) {
        SettingsLockManager.setGraceMs(reactContext, 0L);
        Log.d(TAG, "[SETTINGS_LOCK] setSettingsLockGrace no-op (re-arm removed)");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Content Filter double-safe guard (see com.Break.lock.ContentFilterGuard)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the guard state as a JSON string:
     * { doubleSafeEnabled, filterEnabled, state, pendingDisableAt, readyAt,
     *   confirmWindowMs, confirmEndsAt, now }.
     */
    @ReactMethod
    public void getContentFilterGuardState(Callback callback) {
        String json = ContentFilterGuard.getStateJson(reactContext);
        Log.d(TAG, "[CF_GUARD] getContentFilterGuardState → " + json);
        callback.invoke(json);
    }

    /**
     * Enables/disables the double-safe guard. Rejects turning it OFF while a
     * pending disable is in flight (that would shortcut the wait).
     */
    @ReactMethod
    public void setContentFilterDoubleSafe(boolean enabled, Promise promise) {
        boolean ok = ContentFilterGuard.setDoubleSafeEnabled(reactContext, enabled);
        Log.d(TAG, "[CF_GUARD] setContentFilterDoubleSafe=" + enabled + " ok=" + ok);
        if (ok) {
            promise.resolve(true);
        } else {
            promise.reject("CF_GUARD_REFUSED",
                    "Cannot turn off double-safe while a disable is pending");
        }
    }

    /** Step 1: request the disable. Starts the full-duration wait; filter stays ON. */
    @ReactMethod
    public void requestContentFilterDisable(Promise promise) {
        boolean ok = ContentFilterGuard.requestDisable(reactContext);
        Log.d(TAG, "[CF_GUARD] requestContentFilterDisable ok=" + ok);
        if (ok) {
            promise.resolve(true);
        } else {
            promise.reject("CF_GUARD_REFUSED", "Disable request not valid in current state");
        }
    }

    /** Step 2: confirm the disable inside the confirm window. Flips the filter off. */
    @ReactMethod
    public void confirmContentFilterDisable(Promise promise) {
        boolean ok = ContentFilterGuard.confirmDisable(reactContext);
        Log.d(TAG, "[CF_GUARD] confirmContentFilterDisable ok=" + ok);
        if (ok) {
            promise.resolve(true);
        } else {
            promise.reject("CF_GUARD_REFUSED", "Confirm not valid in current state");
        }
    }

    /** Cancels an in-flight pending disable (always allowed). */
    @ReactMethod
    public void cancelContentFilterDisable(Promise promise) {
        ContentFilterGuard.cancelDisable(reactContext);
        Log.d(TAG, "[CF_GUARD] cancelContentFilterDisable");
        promise.resolve(true);
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
        String json = BreakPrefs.get(reactContext).getString(BreakPrefs.KEY_MODES, "{}");
        Log.d(TAG, "[MODE] returning: " + json);
        callback.invoke(json);
    }

    /**
     * Saves the full modes JSON from React Native, then reconciles schedules:
     * cancels/re-registers AlarmManager alarms and activates/deactivates a mode
     * if a changed schedule window covers (or no longer covers) right now.
     * Without this, an edited schedule only took effect after an app restart
     * or reboot — the original "Bedtime never triggers" bug.
     */
    @ReactMethod
    public void saveModes(String jsonString) {
        Log.d(TAG, "[MODE] saveModes called");
        try {
            JSONObject oldModes = BreakPrefs.getModes(reactContext);
            JSONObject parsed = new JSONObject(jsonString);
            BreakPrefs.saveModes(reactContext, parsed);
            ModeManager.onModesSaved(reactContext, oldModes);
            Log.d(TAG, "[MODE] saveModes saved");
        } catch (Exception e) {
            Log.e(TAG, "[MODE] saveModes error: " + e.getMessage());
        }
    }

    /**
     * Resolves true when the app may schedule exact alarms. Always true below
     * Android 12 (API 31). On Android 14+ the SCHEDULE_EXACT_ALARM permission
     * is DENIED by default, which makes mode schedules fire late (or seemingly
     * not at all under Doze) — the UI should prompt the user to grant it.
     */
    @ReactMethod
    public void canScheduleExactAlarms(Promise promise) {
        try {
            boolean allowed = true;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.app.AlarmManager alarmManager =
                        (android.app.AlarmManager) reactContext.getSystemService(android.content.Context.ALARM_SERVICE);
                allowed = alarmManager != null && alarmManager.canScheduleExactAlarms();
            }
            Log.d(TAG, "[MODE] canScheduleExactAlarms → " + allowed);
            promise.resolve(allowed);
        } catch (Exception e) {
            Log.e(TAG, "[MODE] canScheduleExactAlarms error: " + e.getMessage());
            promise.resolve(false);
        }
    }

    /**
     * Opens the system "Alarms & reminders" screen for this app (Android 12+)
     * so the user can grant exact-alarm access. No-op on older versions.
     * When granted, ModeSchedulerReceiver gets
     * ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED and re-registers.
     */
    @ReactMethod
    public void requestExactAlarmPermission(Promise promise) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.content.Intent intent =
                        new android.content.Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.setData(android.net.Uri.parse("package:" + reactContext.getPackageName()));
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                reactContext.startActivity(intent);
                Log.i(TAG, "[MODE] Opened exact-alarm permission settings");
            }
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[MODE] requestExactAlarmPermission error: " + e.getMessage());
            promise.reject("EXACT_ALARM_ERROR", e.getMessage());
        }
    }

    /**
     * Returns the currently active mode ID (empty string if none).
     */
    @ReactMethod
    public void getActiveMode(Callback callback) {
        String modeId = BreakPrefs.getActiveMode(reactContext);
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
     * ReelsInterventionService checks this pref on browser accessibility events.
     */
    @ReactMethod
    public void saveContentFilterEnabled(boolean enabled, Promise promise) {
        Log.d(TAG, "[FILTER] saveContentFilterEnabled=" + enabled);
        try {
            // Defense in depth: while the double-safe guard is on, a DIRECT
            // disable is refused — the only path off is the two-step guard flow
            // (requestContentFilterDisable → confirmContentFilterDisable).
            if (!enabled && !ContentFilterGuard.isDirectDisableAllowed(reactContext)) {
                Log.w(TAG, "[FILTER] direct disable refused — double-safe guard is on");
                promise.reject("CF_GUARD_REFUSED",
                        "Double-safe is on: disable via the two-step flow");
                return;
            }
            BreakPrefs.setContentFilterEnabled(reactContext, enabled);
            // Re-enabling instantly discards any pending two-step disable.
            if (enabled) {
                ContentFilterGuard.onFilterEnabled(reactContext);
            }
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
        boolean enabled = BreakPrefs.isContentFilterEnabled(reactContext);
        Log.d(TAG, "[FILTER] getContentFilterEnabled=" + enabled);
        callback.invoke(enabled);
    }

    /**
     * Returns true if ReelsInterventionService is enabled in Android Accessibility
     * Settings (the single Break accessibility toggle).
     * Independent of the content_filter_enabled in-app feature pref.
     */
    @ReactMethod
    public void isContentFilterServiceEnabled(Callback callback) {
        try {
            String enabled = Settings.Secure.getString(
                    reactContext.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean active = enabled != null &&
                    enabled.contains("com.Break/com.Break.ReelsInterventionService");
            Log.d(TAG, "[FILTER] accessibilityServiceEnabled=" + active);
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
        SharedPreferences prefs = BreakPrefs.get(reactContext);
        String message = prefs.getString(BreakPrefs.KEY_DELAY_MESSAGE, "Is this intentional?");
        Log.d(TAG, "[GET] delay_message=" + message);
        callback.invoke(message);
    }

    /**
     * Retrieves the forced pause duration currently IN FORCE — the active mode's
     * setting_override when it has one, otherwise the saved base value. The
     * Customize slider must show what the app is actually doing, not a base value
     * the mode is masking.
     */
    @ReactMethod
    public void getDelayTime(Callback callback) {
        Log.d(TAG, "[GET] getDelayTime called");
        int seconds = BreakPrefs.getEffectiveSettingInt(reactContext,
                BreakPrefs.KEY_DELAY_TIME_SECONDS, BreakPrefs.DEFAULT_DELAY_TIME_SECONDS);
        Log.d(TAG, "[GET] delay_time_seconds (effective)=" + seconds);
        callback.invoke(seconds);
    }

    // ── Default-mode gate ─────────────────────────────────────────────────────

    /**
     * Reports whether the base settings screens (Customize, AppDetail) may be
     * edited right now. False whenever a non-default mode is active: that mode
     * owns the app's behaviour, so editing the base underneath would be a silent
     * no-op the user could not see.
     *
     * The JS layer calls this to render the read-only banner; the native setters
     * enforce the same rule independently, so a stale UI can never write.
     *
     * Callback: (editable: boolean, activeModeId: String)
     *
     * Logging: [MODE_GATE]
     */
    @ReactMethod
    public void getBaseSettingsEditable(Callback callback) {
        boolean editable = BreakPrefs.isBaseSettingsEditable(reactContext);
        String activeMode = BreakPrefs.getActiveMode(reactContext);
        Log.d(TAG, "[MODE_GATE] getBaseSettingsEditable → " + editable
                + " (activeMode='" + activeMode + "')");
        callback.invoke(editable, activeMode);
    }

}
