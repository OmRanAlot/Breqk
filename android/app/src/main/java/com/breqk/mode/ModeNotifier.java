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

    // Separate low-importance channel for the persistent "mode is active" badge so
    // it doesn't buzz like the one-shot start/end alerts.
    private static final String ONGOING_CHANNEL_ID = "BreakModeOngoing";
    private static final String ONGOING_CHANNEL_NAME = "Active Mode";

    // Prevent instantiation
    private ModeNotifier() {}

    /** Posts a notification announcing that a mode has begun. */
    public static void notifyModeStarted(Context context, String modeId) {
        if (notifsDisabled(context, "notifyModeStarted")) return;
        String name = resolveModeName(context, modeId);
        post(context, modeId,
                name + " started",
                name + " is now active.");
    }

    /** Posts a notification announcing that a mode has ended. */
    public static void notifyModeEnded(Context context, String modeId) {
        if (notifsDisabled(context, "notifyModeEnded")) return;
        String name = resolveModeName(context, modeId);
        post(context, modeId,
                name + " ended",
                name + " is no longer active.");
    }

    /**
     * Posts an ongoing (non-dismissible) notification that stays up the whole time
     * the mode is active. Used by modes that opt into persistent_notification
     * (e.g. Bedtime). No-op if mode notifications are globally disabled.
     */
    public static void showOngoing(Context context, String modeId) {
        if (notifsDisabled(context, "showOngoing")) return;
        createOngoingChannelIfNeeded(context);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            Log.w(TAG, "NotificationManager unavailable — cannot show ongoing for '" + modeId + "'");
            return;
        }

        String name = resolveModeName(context, modeId);
        Intent tapIntent = new Intent(context, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, modeId.hashCode(), tapIntent, piFlags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, ONGOING_CHANNEL_ID)
                .setContentTitle(name + " active")
                .setContentText(name + " is on. Tap to manage.")
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        manager.notify(ongoingNotificationId(modeId), builder.build());
        Log.i(TAG, "Posted ongoing notification for mode '" + modeId + "'");
    }

    /** Clears the ongoing "mode is active" notification for a mode. */
    public static void clearOngoing(Context context, String modeId) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.cancel(ongoingNotificationId(modeId));
        Log.i(TAG, "Cleared ongoing notification for mode '" + modeId + "'");
    }

    /** Returns true (and logs) when the global mode-notifications toggle is off. */
    private static boolean notifsDisabled(Context context, String caller) {
        if (BreakPrefs.isModeNotifsEnabled(context)) return false;
        Log.d(TAG, "Mode notifications disabled — skipping " + caller);
        return true;
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

    private static void createOngoingChannelIfNeeded(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                ONGOING_CHANNEL_ID, ONGOING_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Persistent badge shown while a focus mode is active");
        manager.createNotificationChannel(channel);
    }

    /** Derives a stable ongoing notification ID, distinct from the start/end one. */
    private static int ongoingNotificationId(String modeId) {
        return ("mode_ongoing_" + modeId).hashCode();
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
