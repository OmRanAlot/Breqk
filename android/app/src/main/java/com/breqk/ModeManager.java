package com.breqk;

/*
 * ModeManager
 * ------------
 * Central mode lifecycle manager. Handles:
 *   - Activating / deactivating modes
 *   - Syncing effective policies → legacy blocked_apps → MyVpnService
 *   - Registering / cancelling AlarmManager schedules
 *   - Re-registering alarms on BOOT_COMPLETED
 *
 * A mode is a named preset that temporarily overrides base per-app policies and
 * global settings (e.g., delay_time_seconds). Only one mode can be active at a time.
 *
 * Resolution: active mode override → base policy → false.
 *
 * Logging tag: MODE_MGR
 * Filter: adb logcat -s MODE_MGR
 */

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public final class ModeManager {
    private static final String TAG = "MODE_MGR";

    // Intent actions for AlarmManager-triggered schedule events
    public static final String ACTION_MODE_START = "com.breqk.ACTION_MODE_START";
    public static final String ACTION_MODE_END   = "com.breqk.ACTION_MODE_END";
    // Intent extra key for the mode ID
    public static final String EXTRA_MODE_ID = "mode_id";

    // Prevent instantiation
    private ModeManager() {}

    // =========================================================================
    // Activation / Deactivation
    // =========================================================================

    /**
     * Activates a mode. Sets the active_mode pref, syncs blocked_apps, and
     * sends UPDATE_BLOCKED_APPS to MyVpnService so both monitor instances
     * pick up the new effective policy.
     *
     * @param context App context
     * @param modeId  Mode identifier (e.g., "study", "bedtime")
     * @param source  "manual" or "schedule" — tracks how the mode was activated
     */
    public static void activate(Context context, String modeId, String source) {
        Log.i(TAG, "[ACTIVATE] Activating mode '" + modeId + "' source=" + source);
        SharedPreferences prefs = BreqkPrefs.get(context);
        prefs.edit()
                .putString(BreqkPrefs.KEY_ACTIVE_MODE, modeId)
                .putString(BreqkPrefs.KEY_ACTIVE_MODE_SOURCE, source)
                .apply();

        // Sync legacy blocked_apps from effective policies (base + mode overrides)
        BreqkPrefs.syncBlockedAppsFromPolicies(context);

        // Notify MyVpnService so its AppUsageMonitor picks up the new blocked_apps set
        notifyServiceBlockedAppsChanged(context);

        Log.i(TAG, "[ACTIVATE] Mode '" + modeId + "' is now active");
    }

    /**
     * Deactivates the currently active mode. Falls back to the "default" mode
     * instead of having no mode active. The Default mode is always-on unless
     * explicitly overridden by another mode.
     */
    public static void deactivate(Context context) {
        String previousMode = BreqkPrefs.getActiveMode(context);
        Log.i(TAG, "[DEACTIVATE] Deactivating mode '" + previousMode + "' → falling back to 'default'");

        // Fall back to Default mode instead of no mode
        activate(context, "default", "manual");

        Log.i(TAG, "[DEACTIVATE] Fell back to 'default' mode");
    }

    // =========================================================================
    // Schedule management (AlarmManager)
    // =========================================================================

    /**
     * Registers start + end alarms for a mode's schedule.
     * Uses setExactAndAllowWhileIdle() for precise timing even in Doze mode.
     *
     * @param context App context
     * @param modeId  Mode identifier whose schedule to register
     */
    public static void registerScheduleAlarms(Context context, String modeId) {
        try {
            JSONObject modes = BreqkPrefs.getModes(context);
            if (!modes.has(modeId)) {
                Log.w(TAG, "[SCHEDULE] Cannot register alarms: mode '" + modeId + "' not found");
                return;
            }
            JSONObject mode = modes.getJSONObject(modeId);
            if (!mode.has("schedule") || mode.isNull("schedule")) {
                Log.d(TAG, "[SCHEDULE] Mode '" + modeId + "' has no schedule — skipping alarm registration");
                return;
            }
            JSONObject schedule = mode.getJSONObject("schedule");
            String startTime = schedule.getString("start_time"); // "HH:mm"
            String endTime = schedule.getString("end_time");     // "HH:mm"

            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) {
                Log.e(TAG, "[SCHEDULE] AlarmManager is null");
                return;
            }

            // Register start alarm
            long startMillis = getNextAlarmTime(startTime);
            PendingIntent startIntent = createAlarmIntent(context, modeId, ACTION_MODE_START);
            setExactAlarm(alarmManager, startMillis, startIntent);
            Log.i(TAG, "[SCHEDULE] Registered START alarm for mode '" + modeId
                    + "' at " + startTime + " (epochMs=" + startMillis + ")");

            // Register end alarm
            long endMillis = getNextAlarmTime(endTime);
            // If end time is before start time (overnight schedule), ensure end is after start
            if (endMillis <= startMillis) {
                endMillis += 24 * 60 * 60 * 1000; // add 24 hours
            }
            PendingIntent endIntent = createAlarmIntent(context, modeId, ACTION_MODE_END);
            setExactAlarm(alarmManager, endMillis, endIntent);
            Log.i(TAG, "[SCHEDULE] Registered END alarm for mode '" + modeId
                    + "' at " + endTime + " (epochMs=" + endMillis + ")");

        } catch (JSONException e) {
            Log.e(TAG, "[SCHEDULE] Error registering alarms: " + e.getMessage());
        }
    }

    /**
     * Cancels both start and end alarms for a mode.
     */
    public static void cancelScheduleAlarms(Context context, String modeId) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        PendingIntent startIntent = createAlarmIntent(context, modeId, ACTION_MODE_START);
        PendingIntent endIntent = createAlarmIntent(context, modeId, ACTION_MODE_END);
        alarmManager.cancel(startIntent);
        alarmManager.cancel(endIntent);
        startIntent.cancel();
        endIntent.cancel();
        Log.i(TAG, "[SCHEDULE] Cancelled alarms for mode '" + modeId + "'");
    }

    /**
     * Re-registers alarms for ALL modes that have schedules.
     * Called on BOOT_COMPLETED and when modes are saved.
     */
    public static void reregisterAllAlarms(Context context) {
        Log.d(TAG, "[SCHEDULE] Re-registering all mode schedule alarms");
        try {
            JSONObject modes = BreqkPrefs.getModes(context);
            Iterator<String> keys = modes.keys();
            while (keys.hasNext()) {
                String modeId = keys.next();
                JSONObject mode = modes.getJSONObject(modeId);
                if (mode.has("schedule") && !mode.isNull("schedule")) {
                    registerScheduleAlarms(context, modeId);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "[SCHEDULE] Error re-registering alarms: " + e.getMessage());
        }
    }

    /**
     * Called by ModeSchedulerReceiver when a START alarm fires.
     * Checks day-of-week filter before activating.
     */
    public static void handleScheduleStart(Context context, String modeId) {
        Log.i(TAG, "[SCHEDULE] START alarm fired for mode '" + modeId + "'");
        if (!isTodayInSchedule(context, modeId)) {
            Log.d(TAG, "[SCHEDULE] Today is not in schedule days — skipping activation");
            // Re-register for next occurrence
            registerScheduleAlarms(context, modeId);
            return;
        }
        activate(context, modeId, "schedule");
        // Re-register alarm for next day
        registerScheduleAlarms(context, modeId);
    }

    /**
     * Called by ModeSchedulerReceiver when an END alarm fires.
     * Only deactivates if this mode is currently active (prevents race conditions).
     */
    public static void handleScheduleEnd(Context context, String modeId) {
        Log.i(TAG, "[SCHEDULE] END alarm fired for mode '" + modeId + "'");
        String activeMode = BreqkPrefs.getActiveMode(context);
        if (modeId.equals(activeMode)) {
            deactivate(context);
        } else {
            Log.d(TAG, "[SCHEDULE] Mode '" + modeId + "' is not the active mode — skipping deactivation");
        }
        // Re-register alarm for next day
        registerScheduleAlarms(context, modeId);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Sends UPDATE_BLOCKED_APPS intent to MyVpnService with the latest blocked_apps set.
     * This ensures both monitor instances are in sync after policy changes.
     */
    private static void notifyServiceBlockedAppsChanged(Context context) {
        Set<String> blockedApps = BreqkPrefs.getBlockedApps(context);
        Intent intent = new Intent(context, MyVpnService.class);
        intent.setAction("UPDATE_BLOCKED_APPS");
        intent.putStringArrayListExtra("blockedApps", new ArrayList<>(blockedApps));
        try {
            ServiceHelper.startForegroundServiceCompat(context, intent);
            Log.d(TAG, "[SYNC] Sent UPDATE_BLOCKED_APPS to MyVpnService: " + blockedApps);
        } catch (Exception e) {
            Log.w(TAG, "[SYNC] Failed to notify MyVpnService (may not be running): " + e.getMessage());
        }
    }

    /**
     * Creates a PendingIntent for an AlarmManager alarm.
     * Uses the mode ID hashCode as the request code to allow per-mode alarms.
     */
    private static PendingIntent createAlarmIntent(Context context, String modeId, String action) {
        Intent intent = new Intent(context, ModeSchedulerReceiver.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_MODE_ID, modeId);
        // Unique request code: combine mode ID hash + action hash to avoid collisions
        int requestCode = (modeId + action).hashCode();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    /**
     * Sets an exact alarm that fires even in Doze mode.
     * On Android 12+ (API 31+), checks canScheduleExactAlarms() first;
     * falls back to setAndAllowWhileIdle() if exact alarms are not permitted.
     */
    private static void setExactAlarm(AlarmManager alarmManager, long triggerAtMillis,
                                       PendingIntent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: must check permission before scheduling exact alarms
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent);
                } else {
                    // Fallback: inexact but allowed — may fire up to ~15 min late
                    Log.w(TAG, "[SCHEDULE] canScheduleExactAlarms=false — using inexact alarm");
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent);
            }
        } catch (SecurityException e) {
            // Graceful fallback if exact alarm permission revoked at runtime
            Log.w(TAG, "[SCHEDULE] SecurityException scheduling exact alarm — using inexact: " + e.getMessage());
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent);
        }
    }

    /**
     * Calculates the next occurrence of a time string "HH:mm" from now.
     * If the time has already passed today, returns tomorrow's time.
     */
    private static long getNextAlarmTime(String timeStr) {
        String[] parts = timeStr.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);

        Calendar cal = Calendar.getInstance();
        Calendar target = (Calendar) cal.clone();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        // If target time has passed today, schedule for tomorrow
        if (target.before(cal)) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }
        return target.getTimeInMillis();
    }

    /**
     * Checks if today's day-of-week is in the mode's schedule days array.
     * Schedule days use: 0=Sunday, 1=Monday, ..., 6=Saturday.
     * Calendar.DAY_OF_WEEK uses: 1=Sunday, 2=Monday, ..., 7=Saturday.
     */
    private static boolean isTodayInSchedule(Context context, String modeId) {
        try {
            JSONObject modes = BreqkPrefs.getModes(context);
            if (!modes.has(modeId)) return false;
            JSONObject mode = modes.getJSONObject(modeId);
            if (!mode.has("schedule") || mode.isNull("schedule")) return true; // no schedule = always
            JSONObject schedule = mode.getJSONObject("schedule");
            if (!schedule.has("days")) return true; // no days filter = every day

            JSONArray days = schedule.getJSONArray("days");
            int todayCalendar = Calendar.getInstance().get(Calendar.DAY_OF_WEEK); // 1=Sun..7=Sat
            int todayIndex = todayCalendar - 1; // Convert to 0=Sun..6=Sat

            for (int i = 0; i < days.length(); i++) {
                if (days.getInt(i) == todayIndex) return true;
            }
            return false;
        } catch (JSONException e) {
            Log.w(TAG, "[SCHEDULE] Error checking schedule days: " + e.getMessage());
            return true; // on error, allow activation
        }
    }
}
