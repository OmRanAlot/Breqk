package com.breqk;

import android.content.Context;
import android.content.SharedPreferences;


/**
 * Read/write widget cache in SharedPreferences so the home screen widget
 * can display focus score, time saved, apps blocked, and monitoring status
 * without calling React Native or VPNModule.
 */
public final class WidgetPrefs {
    private final SharedPreferences prefs;

    public WidgetPrefs(Context context) {
        this.prefs = BreqkPrefs.get(context);
    }

    public void updateWidgetCache(int focusScore, int timeSavedMin, int appsBlocked, boolean monitoringEnabled) {
        prefs.edit()
                .putInt(BreqkPrefs.KEY_WIDGET_FOCUS_SCORE, focusScore)
                .putInt(BreqkPrefs.KEY_WIDGET_TIME_SAVED_MIN, timeSavedMin)
                .putInt(BreqkPrefs.KEY_WIDGET_APPS_BLOCKED, appsBlocked)
                .putBoolean(BreqkPrefs.KEY_WIDGET_MONITORING_ENABLED, monitoringEnabled)
                .putLong(BreqkPrefs.KEY_WIDGET_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    public int getFocusScore() {
        return prefs.getInt(BreqkPrefs.KEY_WIDGET_FOCUS_SCORE, 0);
    }

    public int getTimeSavedMin() {
        return prefs.getInt(BreqkPrefs.KEY_WIDGET_TIME_SAVED_MIN, 0);
    }

    public int getAppsBlocked() {
        return prefs.getInt(BreqkPrefs.KEY_WIDGET_APPS_BLOCKED, 0);
    }

    public boolean isMonitoringEnabled() {
        return prefs.getBoolean(BreqkPrefs.KEY_WIDGET_MONITORING_ENABLED, false);
    }

    public long getUpdatedAt() {
        return prefs.getLong(BreqkPrefs.KEY_WIDGET_UPDATED_AT, 0L);
    }
}
