package com.breqk.mode;
import com.breqk.prefs.BreqkPrefs;

/*
 * ModeSchedulerReceiver
 * ----------------------
 * BroadcastReceiver that handles:
 *   1. AlarmManager intents for mode schedule start/end
 *   2. BOOT_COMPLETED — re-registers all mode schedule alarms after reboot
 *
 * Logging tag: MODE_SCHED
 * Filter: adb logcat -s MODE_SCHED
 */

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class ModeSchedulerReceiver extends BroadcastReceiver {
    private static final String TAG = "MODE_SCHED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "Received null intent or action");
            return;
        }

        String action = intent.getAction();
        Log.d(TAG, "[RECEIVE] action=" + action);

        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
                // Device rebooted — re-register all scheduled alarms and run migration
                Log.i(TAG, "[BOOT] Device boot completed — re-registering mode alarms");
                BreqkPrefs.migrateIfNeeded(context);
                BreqkPrefs.createDefaultModesIfNeeded(context);
                ModeManager.reregisterAllAlarms(context);

                // Check if a scheduled mode should be active right now
                // (e.g., device rebooted at 11pm during Bedtime mode's window)
                checkAndActivateCurrentSchedule(context);
                break;

            case ModeManager.ACTION_MODE_START:
                String startModeId = intent.getStringExtra(ModeManager.EXTRA_MODE_ID);
                if (startModeId == null || startModeId.isEmpty()) {
                    Log.w(TAG, "[START] Missing mode_id extra");
                    return;
                }
                Log.i(TAG, "[START] Schedule start alarm for mode '" + startModeId + "'");
                ModeManager.handleScheduleStart(context, startModeId);
                break;

            case ModeManager.ACTION_MODE_END:
                String endModeId = intent.getStringExtra(ModeManager.EXTRA_MODE_ID);
                if (endModeId == null || endModeId.isEmpty()) {
                    Log.w(TAG, "[END] Missing mode_id extra");
                    return;
                }
                Log.i(TAG, "[END] Schedule end alarm for mode '" + endModeId + "'");
                ModeManager.handleScheduleEnd(context, endModeId);
                break;

            default:
                Log.d(TAG, "[RECEIVE] Unhandled action: " + action);
                break;
        }
    }

    /**
     * After boot, checks if any scheduled mode should be active right now.
     * For example, if Bedtime runs 22:00–07:00 and the device boots at 23:00,
     * Bedtime should be activated immediately.
     */
    private void checkAndActivateCurrentSchedule(Context context) {
        try {
            org.json.JSONObject modes = BreqkPrefs.getModes(context);
            java.util.Iterator<String> keys = modes.keys();
            java.util.Calendar now = java.util.Calendar.getInstance();
            int currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60
                    + now.get(java.util.Calendar.MINUTE);
            int todayIndex = now.get(java.util.Calendar.DAY_OF_WEEK) - 1; // 0=Sun..6=Sat

            while (keys.hasNext()) {
                String modeId = keys.next();
                org.json.JSONObject mode = modes.getJSONObject(modeId);
                if (!mode.has("schedule") || mode.isNull("schedule")) continue;
                org.json.JSONObject schedule = mode.getJSONObject("schedule");

                // Check day filter
                if (schedule.has("days")) {
                    org.json.JSONArray days = schedule.getJSONArray("days");
                    boolean todayAllowed = false;
                    for (int i = 0; i < days.length(); i++) {
                        if (days.getInt(i) == todayIndex) { todayAllowed = true; break; }
                    }
                    if (!todayAllowed) continue;
                }

                // Parse schedule times
                String startStr = schedule.getString("start_time");
                String endStr = schedule.getString("end_time");
                int startMinutes = parseTimeMinutes(startStr);
                int endMinutes = parseTimeMinutes(endStr);

                boolean inWindow;
                if (startMinutes <= endMinutes) {
                    // Same-day: e.g., 09:00–17:00
                    inWindow = currentMinutes >= startMinutes && currentMinutes < endMinutes;
                } else {
                    // Overnight: e.g., 22:00–07:00
                    inWindow = currentMinutes >= startMinutes || currentMinutes < endMinutes;
                }

                if (inWindow) {
                    Log.i(TAG, "[BOOT] Mode '" + modeId + "' should be active now (schedule "
                            + startStr + "–" + endStr + ", current=" + currentMinutes + "min)");
                    ModeManager.activate(context, modeId, "schedule");
                    return; // Only one mode can be active
                }
            }
            Log.d(TAG, "[BOOT] No scheduled mode should be active right now");
        } catch (Exception e) {
            Log.w(TAG, "[BOOT] Error checking current schedule: " + e.getMessage());
        }
    }

    /** Parses "HH:mm" to total minutes from midnight. */
    private int parseTimeMinutes(String timeStr) {
        String[] parts = timeStr.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }
}
