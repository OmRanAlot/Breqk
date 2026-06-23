package com.Break.mode;

import com.Break.prefs.BreakPrefs;
import com.Break.MainActivity;
import com.Break.R;

/*
 * ModeNotifier
 * ------------
 * Posts one-shot user-visible notifications when a mode begins or ends.
 * Used by ModeManager for both scheduled (AlarmManager) and manual transitions.
 *
 * Notifications are dismissible (non-ongoing) and tap through to MainActivity.
 * A stable per-mode notification ID lets a "started" alert be replaced by the
 * matching "ended" alert instead of stacking.
 *
 * Logging tag: MODE_NOTIFY
 * Filter: adb logcat -s MODE_NOTIFY
 */

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

public final class ModeNotifier {
    private static final String TAG = "MODE_NOTIFY";

    // Dedicated channel so users can mute mode alerts independently of the
    // ongoing monitoring notification.
    private static final String CHANNEL_ID = "BreakModeAlerts";
    private static final String CHANNEL_NAME = "Mode Alerts";

    // Prevent instantiation
    private ModeNotifier() {}

    /** Posts a notification announcing that a mode has begun. */
    public static void notifyModeStarted(Context context, String modeId) {
        String name = resolveModeName(context, modeId);
        post(context, modeId,
                name + " started",
                name + " is now active.");
    }

    /** Posts a notification announcing that a mode has ended. */
    public static void notifyModeEnded(Context context, String modeId) {
        String name = resolveModeName(context, modeId);
        post(context, modeId,
                name + " ended",
                name + " is no longer active.");
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private static void post(Context context, String modeId, String title, String text) {
        createChannelIfNeeded(context);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            Log.w(TAG, "NotificationManager unavailable — cannot post '" + title + "'");
            return;
        }

        Intent tapIntent = new Intent(context, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, modeId.hashCode(), tapIntent, piFlags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        // Stable per-mode ID so start/end alerts for the same mode replace each other.
        manager.notify(notificationId(modeId), builder.build());
        Log.i(TAG, "Posted notification: '" + title + "' (mode=" + modeId + ")");
    }

    private static void createChannelIfNeeded(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Alerts when a focus mode (e.g. Bedtime) begins or ends");
        manager.createNotificationChannel(channel);
    }

    /** Resolves a mode's friendly display name, falling back to its id. */
    private static String resolveModeName(Context context, String modeId) {
        try {
            JSONObject modes = BreakPrefs.getModes(context);
            if (modes.has(modeId)) {
                JSONObject mode = modes.getJSONObject(modeId);
                if (mode.has("name") && !mode.isNull("name")) {
                    return mode.getString("name");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve name for mode '" + modeId + "': " + e.getMessage());
        }
        return modeId;
    }

    /** Derives a stable notification ID from the mode id. */
    private static int notificationId(String modeId) {
        return ("mode_alert_" + modeId).hashCode();
    }
}
