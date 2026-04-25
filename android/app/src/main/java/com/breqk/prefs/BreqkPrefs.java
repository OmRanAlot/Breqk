package com.breqk.prefs;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.breqk.service.BreqkVpnService;
import com.breqk.monitor.ServiceHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * BreqkPrefs
 * -----------
 * Centralized SharedPreferences constants and accessor for the breqk_prefs file.
 * All preference keys used across the app are defined here as constants to
 * prevent typo-induced bugs and make key discovery trivial.
 *
 * Usage:
 *   SharedPreferences prefs = BreqkPrefs.get(context);
 *   Set<String> blocked = BreqkPrefs.getBlockedApps(context);
 *
 * Logging: No logging — this is a constants/utility-only class.
 */
public final class BreqkPrefs {

    // ── Preferences file name ────────────────────────────────────────────────
    public static final String PREFS_NAME = "breqk_prefs";

    // ── Key constants ────────────────────────────────────────────────────────
    // Blocked apps & monitoring
    public static final String KEY_BLOCKED_APPS = "blocked_apps";
    public static final String KEY_MONITORING_ENABLED = "monitoring_enabled";

    // Instagram redirect
    public static final String KEY_REDIRECT_INSTAGRAM = "redirect_instagram_to_browser";

    // Overlay / delay settings
    public static final String KEY_DELAY_MESSAGE = "delay_message";
    public static final String KEY_DELAY_TIME_SECONDS = "delay_time_seconds";
    public static final String KEY_POPUP_DELAY_MINUTES = "popup_delay_minutes";

    // Scroll threshold (Reels intervention)
    public static final String KEY_SCROLL_THRESHOLD = "scroll_threshold";

    // Scroll budget configuration
    public static final String KEY_SCROLL_ALLOWANCE_MINUTES = "scroll_allowance_minutes";
    public static final String KEY_SCROLL_WINDOW_MINUTES = "scroll_window_minutes";

    // Scroll budget runtime state (written by ReelsInterventionService)
    public static final String KEY_SCROLL_TIME_USED_MS = "scroll_time_used_ms";
    public static final String KEY_SCROLL_WINDOW_START_TIME = "scroll_window_start_time";
    public static final String KEY_SCROLL_BUDGET_EXHAUSTED_AT = "scroll_budget_exhausted_at";

    // Reels state detection (written by ReelsInterventionService)
    public static final String KEY_IS_IN_REELS = "is_in_reels";
    public static final String KEY_IS_IN_REELS_TIMESTAMP = "is_in_reels_timestamp";
    public static final String KEY_IS_IN_REELS_PACKAGE = "is_in_reels_package";

    // Free break — one-time 20-min daily suspension of all Reels/Shorts interventions
    public static final String KEY_FREE_BREAK_ENABLED        = "free_break_enabled";
    public static final String KEY_FREE_BREAK_ACTIVE         = "free_break_active";
    public static final String KEY_FREE_BREAK_START_TIME     = "free_break_start_time";
    public static final String KEY_FREE_BREAK_LAST_USED_DATE = "free_break_last_used_date";
    /** Fixed duration for the free break: 20 minutes in milliseconds. */
    public static final long   FREE_BREAK_DURATION_MS        = 20 * 60 * 1000L;

    // ── Per-app feature policies ───────────────────────────────────────────
    // JSON map: { "com.instagram.android": { "app_open_intercept": true, ... }, ... }
    public static final String KEY_APP_POLICIES = "app_policies";

    // ── Modes ────────────────────────────────────────────────────────────────
    // JSON map of mode definitions: { "study": { "name": "Study Mode", ... }, ... }
    public static final String KEY_MODES = "breqk_modes";
    // Currently active mode ID (null/empty = no mode active)
    public static final String KEY_ACTIVE_MODE = "active_mode";
    // How the mode was activated: "manual" or "schedule"
    public static final String KEY_ACTIVE_MODE_SOURCE = "active_mode_source";

    // ── Feature flag keys (used inside per-app policy objects) ───────────────
    public static final String FEATURE_APP_OPEN_INTERCEPT = "app_open_intercept";
    public static final String FEATURE_REELS_DETECTION = "reels_detection";
    public static final String FEATURE_SCROLL_BUDGET = "scroll_budget";
    public static final String FEATURE_FREE_BREAK = "free_break";

    // ── AppEventRouter feature flags (used by LaunchInterceptor + ContentFilter) ──
    /** Whether short-form content (Reels / Shorts / FYP) should be ejected via GLOBAL_ACTION_BACK. */
    public static final String FEATURE_BLOCK_SHORT_FORM = "block_short_form";
    /** Whether a 15-second mindfulness overlay should appear on fresh app launch. */
    public static final String FEATURE_LAUNCH_POPUP = "launch_popup";

    /**
     * SharedPreferences key prefix for LaunchInterceptor debounce timestamps.
     * Full key = KEY_LAUNCH_LAST_FOREGROUND + packageName
     * Example: "launch_last_foreground_com.instagram.android"
     * Stores the System.currentTimeMillis() of the last time the overlay was dismissed
     * (or the app was last foregrounded during an active session).
     */
    public static final String KEY_LAUNCH_LAST_FOREGROUND = "launch_last_foreground_";

    private static final String TAG = "BreqkPrefs";

    // Widget cache keys (also defined in WidgetPrefs; aligned here for discoverability)
    public static final String KEY_WIDGET_FOCUS_SCORE = "widget_focus_score";
    public static final String KEY_WIDGET_TIME_SAVED_MIN = "widget_time_saved_min";
    public static final String KEY_WIDGET_APPS_BLOCKED = "widget_apps_blocked";
    public static final String KEY_WIDGET_MONITORING_ENABLED = "widget_monitoring_enabled";
    public static final String KEY_WIDGET_UPDATED_AT = "widget_updated_at";

    // Home feed post limit (Instagram home feed scroll counter)
    public static final String KEY_HOME_FEED_POST_LIMIT = "home_feed_post_limit";

    // ── Default values ───────────────────────────────────────────────────────
    public static final int DEFAULT_DELAY_TIME_SECONDS = 15;
    public static final int DEFAULT_POPUP_DELAY_MINUTES = 10;
    public static final int DEFAULT_SCROLL_THRESHOLD = 4;
    public static final int DEFAULT_SCROLL_ALLOWANCE_MINUTES = 5;
    public static final int DEFAULT_HOME_FEED_POST_LIMIT = 20;
    public static final int DEFAULT_SCROLL_WINDOW_MINUTES = 60;

    // ── Accessor ─────────────────────────────────────────────────────────────

    /**
     * Returns the SharedPreferences instance for breqk_prefs.
     * Android caches SharedPreferences internally so calling this repeatedly is cheap.
     */
    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Returns a defensive copy of the blocked apps set.
     * Android documentation warns that getStringSet() returns the internal backing set
     * which must not be modified. This method always returns a safe copy.
     *
     * @return New HashSet containing blocked package names; never null.
     */
    public static Set<String> getBlockedApps(Context context) {
        Set<String> raw = get(context).getStringSet(KEY_BLOCKED_APPS, new HashSet<>());
        return new HashSet<>(raw != null ? raw : new HashSet<>());
    }

    // ── Per-App Policy helpers ─────────────────────────────────────────────

    /**
     * Parses the app_policies JSON from SharedPreferences.
     * Returns a map of packageName → { featureKey → enabled }.
     * On parse error or missing key, returns an empty map.
     *
     * Logging: [POLICY]
     */
    public static Map<String, Map<String, Boolean>> getAppPolicies(Context context) {
        String json = get(context).getString(KEY_APP_POLICIES, "");
        Map<String, Map<String, Boolean>> result = new HashMap<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JSONObject root = new JSONObject(json);
            Iterator<String> pkgs = root.keys();
            while (pkgs.hasNext()) {
                String pkg = pkgs.next();
                JSONObject features = root.getJSONObject(pkg);
                Map<String, Boolean> featureMap = new HashMap<>();
                Iterator<String> keys = features.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    featureMap.put(key, features.getBoolean(key));
                }
                result.put(pkg, featureMap);
            }
        } catch (JSONException e) {
            Log.e(TAG, "[POLICY] Failed to parse app_policies JSON: " + e.getMessage());
        }
        return result;
    }

    /**
     * Saves the full app policies map to SharedPreferences as JSON.
     *
     * Also syncs the legacy blocked_apps set (derived from app_open_intercept flags)
     * so AppUsageMonitor/MyVpnService continue working without changes.
     */
    public static void saveAppPolicies(Context context, Map<String, Map<String, Boolean>> policies) {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, Map<String, Boolean>> entry : policies.entrySet()) {
                JSONObject features = new JSONObject();
                for (Map.Entry<String, Boolean> feat : entry.getValue().entrySet()) {
                    features.put(feat.getKey(), feat.getValue());
                }
                root.put(entry.getKey(), features);
            }
        } catch (JSONException e) {
            Log.e(TAG, "[POLICY] Failed to serialize app_policies: " + e.getMessage());
        }
        get(context).edit().putString(KEY_APP_POLICIES, root.toString()).apply();
        Log.d(TAG, "[POLICY] Saved app_policies: " + root.toString());

        // Sync legacy blocked_apps set from effective policies
        syncBlockedAppsFromPolicies(context);
        // Notify live monitors so policy changes take effect without a service restart
        dispatchBlockedAppsReload(context);
    }

    /**
     * Gets the base policy for a single app. Returns a map with all feature flags.
     * Missing features default to false.
     */
    public static Map<String, Boolean> getAppPolicy(Context context, String packageName) {
        Map<String, Map<String, Boolean>> all = getAppPolicies(context);
        Map<String, Boolean> policy = all.get(packageName);
        if (policy == null) policy = new HashMap<>();
        return policy;
    }

    /**
     * Checks if a feature is enabled for a given app, considering active mode overrides.
     * Resolution order: active mode override → base policy → false.
     *
     * This is the primary method services should call to check feature state.
     */
    public static boolean isFeatureEnabled(Context context, String packageName, String featureKey) {
        SharedPreferences prefs = get(context);

        // Check active mode overrides first
        String activeMode = prefs.getString(KEY_ACTIVE_MODE, "");
        if (activeMode != null && !activeMode.isEmpty()) {
            Boolean modeOverride = getModeFeatureOverride(context, activeMode, packageName, featureKey);
            if (modeOverride != null) {
                Log.d(TAG, "[POLICY] isFeatureEnabled pkg=" + packageName + " feature=" + featureKey
                        + " → " + modeOverride + " (from mode '" + activeMode + "')");
                return modeOverride;
            }
        }

        // Fall through to base policy
        Map<String, Boolean> policy = getAppPolicy(context, packageName);
        boolean enabled = Boolean.TRUE.equals(policy.get(featureKey));
        Log.d(TAG, "[POLICY] isFeatureEnabled pkg=" + packageName + " feature=" + featureKey
                + " → " + enabled + " (base policy)");
        return enabled;
    }

    /**
     * Returns the effective value of a global setting, considering mode overrides.
     * Resolution: active mode setting_overrides → base SharedPreferences → defaultValue.
     */
    public static int getEffectiveSettingInt(Context context, String settingKey, int defaultValue) {
        SharedPreferences prefs = get(context);
        String activeMode = prefs.getString(KEY_ACTIVE_MODE, "");
        if (activeMode != null && !activeMode.isEmpty()) {
            try {
                String modesJson = prefs.getString(KEY_MODES, "");
                if (modesJson != null && !modesJson.isEmpty()) {
                    JSONObject modes = new JSONObject(modesJson);
                    if (modes.has(activeMode)) {
                        JSONObject mode = modes.getJSONObject(activeMode);
                        if (mode.has("setting_overrides")) {
                            JSONObject overrides = mode.getJSONObject("setting_overrides");
                            if (overrides.has(settingKey)) {
                                int value = overrides.getInt(settingKey);
                                Log.d(TAG, "[POLICY] getEffectiveSettingInt key=" + settingKey
                                        + " → " + value + " (from mode '" + activeMode + "')");
                                return value;
                            }
                        }
                    }
                }
            } catch (JSONException e) {
                Log.w(TAG, "[POLICY] Error reading mode setting override: " + e.getMessage());
            }
        }
        return prefs.getInt(settingKey, defaultValue);
    }

    /**
     * Atomically updates a single feature flag for a single app in the base policy.
     * Creates the app entry if it doesn't exist.
     */
    public static void setAppFeature(Context context, String packageName, String featureKey, boolean enabled) {
        Map<String, Map<String, Boolean>> policies = getAppPolicies(context);
        Map<String, Boolean> appPolicy = policies.get(packageName);
        if (appPolicy == null) {
            appPolicy = new HashMap<>();
            policies.put(packageName, appPolicy);
        }
        appPolicy.put(featureKey, enabled);
        saveAppPolicies(context, policies);
        Log.d(TAG, "[POLICY] setAppFeature pkg=" + packageName + " " + featureKey + "=" + enabled);
    }

    // ── Mode helpers ─────────────────────────────────────────────────────────

    /**
     * Returns the modes JSON object from SharedPreferences.
     * Each mode has: name, icon, color, policy_overrides, setting_overrides, schedule.
     */
    public static JSONObject getModes(Context context) {
        String json = get(context).getString(KEY_MODES, "");
        if (json == null || json.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            Log.e(TAG, "[MODE] Failed to parse modes JSON: " + e.getMessage());
            return new JSONObject();
        }
    }

    /**
     * Saves the full modes JSON to SharedPreferences.
     */
    public static void saveModes(Context context, JSONObject modes) {
        get(context).edit().putString(KEY_MODES, modes.toString()).apply();
        Log.d(TAG, "[MODE] Saved modes: " + modes.toString());
    }

    /**
     * Returns the feature override value from a specific mode for a specific app+feature.
     * Returns null if the mode doesn't override this feature (caller should fall through).
     */
    public static Boolean getModeFeatureOverride(Context context, String modeId,
                                                  String packageName, String featureKey) {
        try {
            JSONObject modes = getModes(context);
            if (!modes.has(modeId)) return null;
            JSONObject mode = modes.getJSONObject(modeId);
            if (!mode.has("policy_overrides")) return null;
            JSONObject policyOverrides = mode.getJSONObject("policy_overrides");
            if (!policyOverrides.has(packageName)) return null;
            JSONObject appOverrides = policyOverrides.getJSONObject(packageName);
            if (!appOverrides.has(featureKey)) return null;
            return appOverrides.getBoolean(featureKey);
        } catch (JSONException e) {
            Log.w(TAG, "[MODE] Error reading mode override: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns the currently active mode ID, or empty string if none.
     */
    public static String getActiveMode(Context context) {
        return get(context).getString(KEY_ACTIVE_MODE, "");
    }

    // ── Migration ────────────────────────────────────────────────────────────

    /**
     * Migrates from the legacy blocked_apps set to the new per-app policy format.
     * Only runs if app_policies is empty and blocked_apps has entries.
     * All features are enabled for each previously blocked app (preserves old behavior).
     *
     * Should be called once during app startup (e.g., from MainApplication or first module init).
     */
    public static void migrateIfNeeded(Context context) {
        SharedPreferences prefs = get(context);
        String existingPolicies = prefs.getString(KEY_APP_POLICIES, "");
        if (existingPolicies != null && !existingPolicies.isEmpty()) {
            return; // Already migrated
        }

        Set<String> blockedApps = getBlockedApps(context);
        if (blockedApps.isEmpty()) {
            // No legacy data either — write default policies for Instagram + YouTube
            Log.d(TAG, "[MIGRATION] No legacy data — creating default policies");
            Map<String, Map<String, Boolean>> defaults = new HashMap<>();
            Map<String, Boolean> igPolicy = new HashMap<>();
            igPolicy.put(FEATURE_APP_OPEN_INTERCEPT, true);
            igPolicy.put(FEATURE_REELS_DETECTION, true);
            igPolicy.put(FEATURE_SCROLL_BUDGET, true);
            igPolicy.put(FEATURE_FREE_BREAK, true);
            defaults.put("com.instagram.android", igPolicy);

            Map<String, Boolean> ytPolicy = new HashMap<>();
            ytPolicy.put(FEATURE_APP_OPEN_INTERCEPT, true);
            ytPolicy.put(FEATURE_REELS_DETECTION, true);
            ytPolicy.put(FEATURE_SCROLL_BUDGET, true);
            ytPolicy.put(FEATURE_FREE_BREAK, true);
            defaults.put("com.google.android.youtube", ytPolicy);

            // TikTok: the entire app is short-form — block Reels-equivalent content
            // and show launch popup. No scroll budget (TikTok ejection is policy-based).
            Map<String, Boolean> tikTokPolicy = new HashMap<>();
            tikTokPolicy.put(FEATURE_BLOCK_SHORT_FORM, true);
            tikTokPolicy.put(FEATURE_LAUNCH_POPUP, true);
            defaults.put("com.zhiliaoapp.musically", tikTokPolicy);

            saveAppPolicies(context, defaults);
            return;
        }

        // Migrate: all features ON for each previously blocked app
        Log.i(TAG, "[MIGRATION] Migrating " + blockedApps.size() + " blocked apps to per-app policies");
        Map<String, Map<String, Boolean>> policies = new HashMap<>();
        for (String pkg : blockedApps) {
            Map<String, Boolean> features = new HashMap<>();
            features.put(FEATURE_APP_OPEN_INTERCEPT, true);
            features.put(FEATURE_REELS_DETECTION, true);
            features.put(FEATURE_SCROLL_BUDGET, true);
            features.put(FEATURE_FREE_BREAK, true);
            policies.put(pkg, features);
        }
        saveAppPolicies(context, policies);
    }

    /**
     * Creates default modes on first run (Default + Study Mode + Bedtime).
     * Only runs if KEY_MODES is empty.
     *
     * "Default" mode is always active unless another mode is selected. It carries
     * the baseline per-app policies so users configure everything through modes.
     */
    // Version key — bump this to force re-creation of default modes on next launch
    private static final int DEFAULT_MODES_VERSION = 2;
    private static final String KEY_DEFAULT_MODES_VERSION = "default_modes_version";

    public static void createDefaultModesIfNeeded(Context context) {
        SharedPreferences prefs = get(context);
        int currentVersion = prefs.getInt(KEY_DEFAULT_MODES_VERSION, 0);
        if (currentVersion >= DEFAULT_MODES_VERSION) return;

        // Mark version immediately to prevent re-runs
        prefs.edit().putInt(KEY_DEFAULT_MODES_VERSION, DEFAULT_MODES_VERSION).apply();

        Log.d(TAG, "[MODE] Creating default modes (Default + Study + Bedtime)");
        try {
            JSONObject modes = new JSONObject();

            // Default mode: Instagram = reels only, YouTube = intercept only.
            // Always active unless another mode is selected.
            JSONObject defaultMode = new JSONObject();
            defaultMode.put("name", "Default");
            defaultMode.put("icon", "default");
            defaultMode.put("color", "#1A1A1A");
            defaultMode.put("is_default", true); // marker — UI treats this specially
            JSONObject defaultPolicies = new JSONObject();
            JSONObject defaultIg = new JSONObject();
            defaultIg.put(FEATURE_APP_OPEN_INTERCEPT, false);
            defaultIg.put(FEATURE_REELS_DETECTION, true);
            defaultIg.put(FEATURE_SCROLL_BUDGET, true);
            defaultIg.put(FEATURE_FREE_BREAK, true);
            defaultPolicies.put("com.instagram.android", defaultIg);
            JSONObject defaultYt = new JSONObject();
            defaultYt.put(FEATURE_APP_OPEN_INTERCEPT, true);
            defaultYt.put(FEATURE_REELS_DETECTION, false);
            defaultYt.put(FEATURE_SCROLL_BUDGET, false);
            defaultYt.put(FEATURE_FREE_BREAK, false);
            defaultPolicies.put("com.google.android.youtube", defaultYt);
            // TikTok default: block short-form + show launch popup
            JSONObject defaultTikTok = new JSONObject();
            defaultTikTok.put(FEATURE_BLOCK_SHORT_FORM, true);
            defaultTikTok.put(FEATURE_LAUNCH_POPUP, true);
            defaultPolicies.put("com.zhiliaoapp.musically", defaultTikTok);
            defaultMode.put("policy_overrides", defaultPolicies);
            defaultMode.put("setting_overrides", new JSONObject());
            modes.put("default", defaultMode);

            // Study Mode: intercepts ON for all apps, 20s delay, no schedule
            JSONObject study = new JSONObject();
            study.put("name", "Study Mode");
            study.put("icon", "book");
            study.put("color", "#FF9800");
            JSONObject studyPolicies = new JSONObject();
            JSONObject studyIg = new JSONObject();
            studyIg.put(FEATURE_APP_OPEN_INTERCEPT, true);
            studyIg.put(FEATURE_REELS_DETECTION, true);
            studyPolicies.put("com.instagram.android", studyIg);
            JSONObject studyYt = new JSONObject();
            studyYt.put(FEATURE_APP_OPEN_INTERCEPT, true);
            studyYt.put(FEATURE_REELS_DETECTION, false);
            studyPolicies.put("com.google.android.youtube", studyYt);
            // TikTok: always block in study mode
            JSONObject studyTikTok = new JSONObject();
            studyTikTok.put(FEATURE_BLOCK_SHORT_FORM, true);
            studyTikTok.put(FEATURE_LAUNCH_POPUP, true);
            studyPolicies.put("com.zhiliaoapp.musically", studyTikTok);
            study.put("policy_overrides", studyPolicies);
            JSONObject studySettings = new JSONObject();
            studySettings.put("delay_time_seconds", 20);
            study.put("setting_overrides", studySettings);
            modes.put("study", study);

            // Bedtime: all features ON, 20s delay, schedule 10pm–7am all days
            JSONObject bedtime = new JSONObject();
            bedtime.put("name", "Bedtime");
            bedtime.put("icon", "moon");
            bedtime.put("color", "#7C4DFF");
            JSONObject bedPolicies = new JSONObject();
            JSONObject bedIg = new JSONObject();
            bedIg.put(FEATURE_APP_OPEN_INTERCEPT, true);
            bedIg.put(FEATURE_REELS_DETECTION, true);
            bedPolicies.put("com.instagram.android", bedIg);
            JSONObject bedYt = new JSONObject();
            bedYt.put(FEATURE_APP_OPEN_INTERCEPT, true);
            bedYt.put(FEATURE_REELS_DETECTION, true);
            bedPolicies.put("com.google.android.youtube", bedYt);
            // TikTok: always block at bedtime
            JSONObject bedTikTok = new JSONObject();
            bedTikTok.put(FEATURE_BLOCK_SHORT_FORM, true);
            bedTikTok.put(FEATURE_LAUNCH_POPUP, true);
            bedPolicies.put("com.zhiliaoapp.musically", bedTikTok);
            bedtime.put("policy_overrides", bedPolicies);
            JSONObject bedSettings = new JSONObject();
            bedSettings.put("delay_time_seconds", 20);
            bedtime.put("setting_overrides", bedSettings);
            JSONObject schedule = new JSONObject();
            schedule.put("start_time", "22:00");
            schedule.put("end_time", "07:00");
            schedule.put("days", new org.json.JSONArray(new int[]{0, 1, 2, 3, 4, 5, 6}));
            bedtime.put("schedule", schedule);
            modes.put("bedtime", bedtime);

            saveModes(context, modes);

            // Auto-activate the Default mode
            get(context).edit()
                    .putString(KEY_ACTIVE_MODE, "default")
                    .putString(KEY_ACTIVE_MODE_SOURCE, "manual")
                    .apply();
            Log.d(TAG, "[MODE] Default mode auto-activated on first run");

        } catch (JSONException e) {
            Log.e(TAG, "[MODE] Failed to create default modes: " + e.getMessage());
        }
    }

    // ── Sync helper ──────────────────────────────────────────────────────────

    /**
     * Recomputes the legacy blocked_apps set from effective per-app policies.
     * An app is "blocked" (in the legacy sense) if its effective app_open_intercept is true.
     * This keeps AppUsageMonitor and MyVpnService working without changes.
     *
     * CRITICAL: Must iterate ALL known apps — both from base policies AND from the
     * active mode's policy_overrides. A mode can enable app_open_intercept for an app
     * that has it disabled in base policy. Only iterating base policy keys would miss
     * these mode-added apps entirely.
     */
    public static void syncBlockedAppsFromPolicies(Context context) {
        Set<String> blockedApps = new HashSet<>();

        // Collect all known app package names from both sources
        Set<String> allApps = new HashSet<>();

        // 1. Apps from base policies
        Map<String, Map<String, Boolean>> policies = getAppPolicies(context);
        allApps.addAll(policies.keySet());

        // 2. Apps from active mode's policy_overrides (mode can add new app-level overrides)
        String activeMode = get(context).getString(KEY_ACTIVE_MODE, "");
        if (activeMode != null && !activeMode.isEmpty()) {
            try {
                JSONObject modes = getModes(context);
                if (modes.has(activeMode)) {
                    JSONObject mode = modes.getJSONObject(activeMode);
                    if (mode.has("policy_overrides")) {
                        JSONObject overrides = mode.getJSONObject("policy_overrides");
                        Iterator<String> keys = overrides.keys();
                        while (keys.hasNext()) {
                            allApps.add(keys.next());
                        }
                    }
                }
            } catch (JSONException e) {
                Log.w(TAG, "[POLICY] Error reading mode overrides during sync: " + e.getMessage());
            }
        }

        // 3. Resolve effective app_open_intercept for each known app
        for (String pkg : allApps) {
            if (isFeatureEnabled(context, pkg, FEATURE_APP_OPEN_INTERCEPT)) {
                blockedApps.add(pkg);
            }
        }

        get(context).edit().putStringSet(KEY_BLOCKED_APPS, blockedApps).apply();
        Log.d(TAG, "[POLICY] Synced blocked_apps from policies: " + blockedApps
                + " (allApps=" + allApps.size() + " activeMode=" + activeMode + ")");
    }

    /**
     * Notifies the running MyVpnService to reload its in-memory blocked apps list.
     * Must be called after any write that changes the effective blocked_apps set
     * (setAppFeature, saveAppPolicies, mode activation, etc.) so the live monitor
     * picks up the change without requiring a service restart.
     *
     * VPNModule's appMonitor instance is updated separately via a
     * SharedPreferences.OnSharedPreferenceChangeListener registered in its constructor.
     *
     * Logging: [POLICY_RELOAD]
     */
    public static void dispatchBlockedAppsReload(Context context) {
        try {
            Set<String> blocked = getBlockedApps(context);
            Intent intent = new Intent(context, BreqkVpnService.class);
            intent.setAction("UPDATE_BLOCKED_APPS");
            intent.putStringArrayListExtra("blockedApps", new ArrayList<>(blocked));
            ServiceHelper.startForegroundServiceCompat(context, intent);
            Log.d(TAG, "[POLICY_RELOAD] dispatched UPDATE_BLOCKED_APPS size=" + blocked.size());
        } catch (Exception e) {
            Log.w(TAG, "[POLICY_RELOAD] dispatch failed: " + e.getMessage());
        }
    }

    // ── Widget cache accessors (consolidated from WidgetPrefs) ────────────────

    public static void updateWidgetCache(Context context, int focusScore, int timeSavedMin,
                                         int appsBlocked, boolean monitoringEnabled) {
        get(context).edit()
                .putInt(KEY_WIDGET_FOCUS_SCORE, focusScore)
                .putInt(KEY_WIDGET_TIME_SAVED_MIN, timeSavedMin)
                .putInt(KEY_WIDGET_APPS_BLOCKED, appsBlocked)
                .putBoolean(KEY_WIDGET_MONITORING_ENABLED, monitoringEnabled)
                .putLong(KEY_WIDGET_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    public static int getWidgetFocusScore(Context context) {
        return get(context).getInt(KEY_WIDGET_FOCUS_SCORE, 0);
    }

    public static int getWidgetTimeSavedMin(Context context) {
        return get(context).getInt(KEY_WIDGET_TIME_SAVED_MIN, 0);
    }

    public static int getWidgetAppsBlocked(Context context) {
        return get(context).getInt(KEY_WIDGET_APPS_BLOCKED, 0);
    }

    public static boolean isWidgetMonitoringEnabled(Context context) {
        return get(context).getBoolean(KEY_WIDGET_MONITORING_ENABLED, false);
    }

    public static long getWidgetUpdatedAt(Context context) {
        return get(context).getLong(KEY_WIDGET_UPDATED_AT, 0L);
    }

    // Prevent instantiation
    private BreqkPrefs() {}
}
