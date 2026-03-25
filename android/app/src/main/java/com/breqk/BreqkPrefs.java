package com.breqk;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
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

    // Widget cache keys (also defined in WidgetPrefs; aligned here for discoverability)
    public static final String KEY_WIDGET_FOCUS_SCORE = "widget_focus_score";
    public static final String KEY_WIDGET_TIME_SAVED_MIN = "widget_time_saved_min";
    public static final String KEY_WIDGET_APPS_BLOCKED = "widget_apps_blocked";
    public static final String KEY_WIDGET_MONITORING_ENABLED = "widget_monitoring_enabled";
    public static final String KEY_WIDGET_UPDATED_AT = "widget_updated_at";

    // ── Default values ───────────────────────────────────────────────────────
    public static final int DEFAULT_DELAY_TIME_SECONDS = 15;
    public static final int DEFAULT_POPUP_DELAY_MINUTES = 10;
    public static final int DEFAULT_SCROLL_THRESHOLD = 4;
    public static final int DEFAULT_SCROLL_ALLOWANCE_MINUTES = 5;
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

    // Prevent instantiation
    private BreqkPrefs() {}
}
