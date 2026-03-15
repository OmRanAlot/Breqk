package com.doomscrollstopper;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Read/write widget cache in SharedPreferences so the home screen widget
 * can display focus score, time saved, apps blocked, and monitoring status
 * without calling React Native or VPNModule.
 */
public final class WidgetPrefs {
    private static final String PREFS_NAME = "doomscroll_prefs";
    private static final String KEY_FOCUS_SCORE = "widget_focus_score";
    private static final String KEY_TIME_SAVED_MIN = "widget_time_saved_min";
    private static final String KEY_APPS_BLOCKED = "widget_apps_blocked";
    private static final String KEY_MONITORING_ENABLED = "widget_monitoring_enabled";
    private static final String KEY_UPDATED_AT = "widget_updated_at";

    private final SharedPreferences prefs;

    public WidgetPrefs(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void updateWidgetCache(int focusScore, int timeSavedMin, int appsBlocked, boolean monitoringEnabled) {
        prefs.edit()
                .putInt(KEY_FOCUS_SCORE, focusScore)
                .putInt(KEY_TIME_SAVED_MIN, timeSavedMin)
                .putInt(KEY_APPS_BLOCKED, appsBlocked)
                .putBoolean(KEY_MONITORING_ENABLED, monitoringEnabled)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    public int getFocusScore() {
        return prefs.getInt(KEY_FOCUS_SCORE, 0);
    }

    public int getTimeSavedMin() {
        return prefs.getInt(KEY_TIME_SAVED_MIN, 0);
    }

    public int getAppsBlocked() {
        return prefs.getInt(KEY_APPS_BLOCKED, 0);
    }

    public boolean isMonitoringEnabled() {
        return prefs.getBoolean(KEY_MONITORING_ENABLED, false);
    }

    public long getUpdatedAt() {
        return prefs.getLong(KEY_UPDATED_AT, 0L);
    }
}
