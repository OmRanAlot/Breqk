package com.Break.mode;
import com.Break.prefs.BreakPrefs;

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

import android.app.AlarmManager;
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
                BreakPrefs.migrateIfNeeded(context);
                BreakPrefs.createDefaultModesIfNeeded(context);
                ModeManager.reregisterAllAlarms(context);

                // Check if a scheduled mode should be active right now
                // (e.g., device rebooted at 11pm during Bedtime mode's window)
                ModeManager.applyCurrentScheduleState(context);
                break;

            case AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED:
                // User granted "Alarms & reminders" in system settings (Android 12+).
                // Re-register so pending inexact alarms are upgraded to exact ones.
                Log.i(TAG, "[PERM] Exact alarm permission granted — re-registering mode alarms");
                ModeManager.reregisterAllAlarms(context);
                ModeManager.applyCurrentScheduleState(context);
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

}
