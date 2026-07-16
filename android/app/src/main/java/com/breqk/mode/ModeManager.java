package com.Break.mode;
import com.Break.prefs.BreakPrefs;
import com.Break.service.BreakVpnService;
import com.Break.monitor.ServiceHelper;

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
    public static final String ACTION_MODE_START = "com.Break.ACTION_MODE_START";
    public static final String ACTION_MODE_END   = "com.Break.ACTION_MODE_END";
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
        SharedPreferences prefs = BreakPrefs.get(context);

        // If we're switching away from a different non-default mode, emit its
        // "ended" notification first. This is the single source of truth for
        // end events — manual switches, schedule end (via deactivate→default),
        // and direct mode-to-mode swaps all funnel through here.
        String previousMode = BreakPrefs.getActiveMode(context);
        if (!modeId.equals(previousMode) && !"default".equals(previousMode)) {
            ModeNotifier.notifyModeEnded(context, previousMode);
        }

        prefs.edit()
                .putString(BreakPrefs.KEY_ACTIVE_MODE, modeId)
                .putString(BreakPrefs.KEY_ACTIVE_MODE_SOURCE, source)
                .apply();

        // Sync legacy blocked_apps from effective policies (base + mode overrides)
        BreakPrefs.syncBlockedAppsFromPolicies(context);

        // Notify MyVpnService so its AppUsageMonitor picks up the new blocked_apps set
        notifyServiceBlockedAppsChanged(context);

        // User-visible "mode started" notification. Skip "default" — it's the
        // always-on fallback, not a mode the user deliberately enters.
        if (!"default".equals(modeId)) {
            ModeNotifier.notifyModeStarted(context, modeId);
        }

        Log.i(TAG, "[ACTIVATE] Mode '" + modeId + "' is now active");
    }

    /**
     * Deactivates the currently active mode. Falls back to the "default" mode
     * instead of having no mode active. The Default mode is always-on unless
     * explicitly overridden by another mode.
     */
    public static void deactivate(Context context) {
        String previousMode = BreakPrefs.getActiveMode(context);
        Log.i(TAG, "[DEACTIVATE] Deactivating mode '" + previousMode + "' → falling back to 'default'");

        // Fall back to Default mode instead of no mode. activate() emits the
        // "ended" notification for previousMode as part of the transition.
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
            JSONObject modes = BreakPrefs.getModes(context);
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

            // Register end alarm. getNextAlarmTime() already returns the next
            // FUTURE occurrence, which is always the correct end boundary — even
            // for overnight windows. E.g. re-registering at 23:00 for a
            // 23:00–07:00 schedule: next end = tomorrow 07:00, which lands
            // before the next start (tomorrow 23:00) and must NOT be pushed
            // back a day, or the active window would never close.
            long endMillis = getNextAlarmTime(endTime);
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
            JSONObject modes = BreakPrefs.getModes(context);
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
        String activeMode = BreakPrefs.getActiveMode(context);
        if (modeId.equals(activeMode)) {
            deactivate(context);
        } else {
            Log.d(TAG, "[SCHEDULE] Mode '" + modeId + "' is not the active mode — skipping deactivation");
        }
        // Re-register alarm for next day
        registerScheduleAlarms(context, modeId);
    }

    // =========================================================================
    // Schedule state reconciliation
    // =========================================================================

    /**
     * Called after the modes JSON is persisted (SettingsModule.saveModes).
     * Reconciles AlarmManager and the active mode with the new data:
     *   1. Cancels alarms for modes whose schedule was removed or changed
     *   2. Re-registers alarms for every mode that has a schedule
     *   3. Deactivates the active schedule-driven mode if its window no longer covers now
     *   4. Activates a mode whose schedule was added/changed and covers now
     *
     * Only modes whose schedule actually CHANGED in this save are auto-activated,
     * so unrelated saves (e.g. the UI normalising 'enabled' flags on foreground)
     * never re-activate a mode the user manually turned off mid-window.
     *
     * @param context  App context
     * @param oldModes The modes JSON as it was BEFORE this save
     */
    public static void onModesSaved(Context context, JSONObject oldModes) {
        JSONObject newModes = BreakPrefs.getModes(context);
        Log.i(TAG, "[SCHEDULE] Modes saved — reconciling alarms and active mode");

        // 1. Cancel alarms for schedules that were removed or changed
        Set<String> changedScheduleIds = new HashSet<>();
        Iterator<String> oldKeys = oldModes.keys();
        while (oldKeys.hasNext()) {
            String modeId = oldKeys.next();
            if (!optScheduleString(oldModes, modeId).equals(optScheduleString(newModes, modeId))) {
                cancelScheduleAlarms(context, modeId);
            }
        }
        // Schedules that are new or changed in the new data (candidates for step 4)
        Iterator<String> newKeys = newModes.keys();
        while (newKeys.hasNext()) {
            String modeId = newKeys.next();
            String newSchedule = optScheduleString(newModes, modeId);
            if (!newSchedule.isEmpty() && !newSchedule.equals(optScheduleString(oldModes, modeId))) {
                changedScheduleIds.add(modeId);
            }
        }

        // 2. Re-register alarms for all current schedules
        reregisterAllAlarms(context);

        // 3. If the active schedule-driven mode lost its window (schedule edited,
        //    removed, or the mode deleted), fall back to default now.
        String activeMode = BreakPrefs.getActiveMode(context);
        String source = getActiveModeSource(context);
        boolean manualNonDefault = isManualNonDefault(activeMode, source);
        if ("schedule".equals(source) && !activeMode.isEmpty() && !"default".equals(activeMode)
                && !isInScheduleWindowNow(context, activeMode)) {
            Log.i(TAG, "[SCHEDULE] Active mode '" + activeMode + "' no longer in window after save — deactivating");
            deactivate(context);
        }

        // 4. If a schedule was just added/changed and its window covers right now,
        //    activate immediately — don't make the user wait for tomorrow's alarm.
        //    A manually chosen non-default mode always wins over schedules.
        if (!manualNonDefault) {
            for (String modeId : changedScheduleIds) {
                if (isInScheduleWindowNow(context, modeId)) {
                    Log.i(TAG, "[SCHEDULE] New/changed schedule for '" + modeId + "' covers now — activating");
                    activate(context, modeId, "schedule");
                    break; // only one mode can be active
                }
            }
        }
    }

    /**
     * Safety net run at boot and app start: if a scheduled mode's window covers
     * right now, activate it. Alarms may have been missed while the app was dead
     * (force-stop clears AlarmManager registrations). Conversely, if the active
     * mode was schedule-driven and its window is over, deactivate it.
     * Never stomps a non-default mode the user activated manually.
     */
    public static void applyCurrentScheduleState(Context context) {
        String activeMode = BreakPrefs.getActiveMode(context);
        String source = getActiveModeSource(context);
        if (isManualNonDefault(activeMode, source)) {
            Log.d(TAG, "[SCHEDULE] Manual mode '" + activeMode + "' active — skipping schedule check");
            return;
        }

        JSONObject modes = BreakPrefs.getModes(context);
        Iterator<String> keys = modes.keys();
        while (keys.hasNext()) {
            String modeId = keys.next();
            if (isInScheduleWindowNow(context, modeId)) {
                if (modeId.equals(activeMode)) {
                    Log.d(TAG, "[SCHEDULE] Mode '" + modeId + "' already active for its window");
                } else {
                    Log.i(TAG, "[SCHEDULE] Mode '" + modeId + "' window covers now — activating");
                    activate(context, modeId, "schedule");
                }
                return; // only one mode can be active
            }
        }

        // No window covers now — end a stale schedule-driven mode.
        if ("schedule".equals(source) && !activeMode.isEmpty() && !"default".equals(activeMode)) {
            Log.i(TAG, "[SCHEDULE] Active mode '" + activeMode + "' window is over — deactivating");
            deactivate(context);
        } else {
            Log.d(TAG, "[SCHEDULE] No scheduled mode should be active right now");
        }
    }

    /**
     * Returns true if the mode has a schedule whose window covers right now,
     * honouring the day-of-week filter. For overnight windows (start > end,
     * e.g. 23:00–07:00) the day filter applies to the day the window STARTED:
     * at 02:00 Monday, a Sunday-only 23:00–07:00 schedule is still in window.
     */
    public static boolean isInScheduleWindowNow(Context context, String modeId) {
        try {
            JSONObject modes = BreakPrefs.getModes(context);
            if (!modes.has(modeId)) return false;
            JSONObject mode = modes.getJSONObject(modeId);
            if (!mode.has("schedule") || mode.isNull("schedule")) return false;
            JSONObject schedule = mode.getJSONObject("schedule");

            Calendar now = Calendar.getInstance();
            int currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            int startMinutes = parseTimeMinutes(schedule.getString("start_time"));
            int endMinutes = parseTimeMinutes(schedule.getString("end_time"));

            boolean overnight = startMinutes > endMinutes;
            boolean inWindow = overnight
                    ? (currentMinutes >= startMinutes || currentMinutes < endMinutes)
                    : (currentMinutes >= startMinutes && currentMinutes < endMinutes);
            if (!inWindow) return false;

            int dayIndex = now.get(Calendar.DAY_OF_WEEK) - 1; // 0=Sun..6=Sat
            if (overnight && currentMinutes < endMinutes) {
                dayIndex = (dayIndex + 6) % 7; // post-midnight tail — window started yesterday
            }
            return isDayAllowed(schedule, dayIndex);
        } catch (JSONException e) {
            Log.w(TAG, "[SCHEDULE] Error checking window for '" + modeId + "': " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /** Returns "manual" or "schedule" — how the current active mode was entered. */
    private static String getActiveModeSource(Context context) {
        return BreakPrefs.get(context).getString(BreakPrefs.KEY_ACTIVE_MODE_SOURCE, "manual");
    }

    /** True when a non-default mode was activated by the user (schedules must not stomp it). */
    private static boolean isManualNonDefault(String activeMode, String source) {
        return !activeMode.isEmpty() && !"default".equals(activeMode) && "manual".equals(source);
    }

    /**
     * Returns the mode's schedule as a JSON string, or "" when the mode or its
     * schedule is absent. Used to diff schedules across a save.
     */
    private static String optScheduleString(JSONObject modes, String modeId) {
        try {
            if (!modes.has(modeId)) return "";
            JSONObject mode = modes.getJSONObject(modeId);
            if (!mode.has("schedule") || mode.isNull("schedule")) return "";
            return mode.getJSONObject("schedule").toString();
        } catch (JSONException e) {
            return "";
        }
    }

    /** Parses "HH:mm" to total minutes from midnight. */
    private static int parseTimeMinutes(String timeStr) {
        String[] parts = timeStr.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    /** Checks a schedule's optional days array (0=Sun..6=Sat). No array = every day. */
    private static boolean isDayAllowed(JSONObject schedule, int dayIndex) throws JSONException {
        if (!schedule.has("days")) return true;
        JSONArray days = schedule.getJSONArray("days");
        for (int i = 0; i < days.length(); i++) {
            if (days.getInt(i) == dayIndex) return true;
        }
        return false;
    }

    /**
     * Sends UPDATE_BLOCKED_APPS intent to MyVpnService with the latest blocked_apps set.
     * This ensures both monitor instances are in sync after policy changes.
     */
    private static void notifyServiceBlockedAppsChanged(Context context) {
        Set<String> blockedApps = BreakPrefs.getBlockedApps(context);
        Intent intent = new Intent(context, BreakVpnService.class);
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
            JSONObject modes = BreakPrefs.getModes(context);
            if (!modes.has(modeId)) return false;
            JSONObject mode = modes.getJSONObject(modeId);
            if (!mode.has("schedule") || mode.isNull("schedule")) return true; // no schedule = always
            JSONObject schedule = mode.getJSONObject("schedule");
            int todayIndex = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1; // 0=Sun..6=Sat
            return isDayAllowed(schedule, todayIndex);
        } catch (JSONException e) {
            Log.w(TAG, "[SCHEDULE] Error checking schedule days: " + e.getMessage());
            return true; // on error, allow activation
        }
    }
}
