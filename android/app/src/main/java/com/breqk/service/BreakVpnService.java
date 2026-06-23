package com.Break.service;

/*
 * BreakVpnService
 * ---------------
 * Plain foreground service (NOT a VPN) used to keep AppUsageMonitor alive
 * in the background. Does not tunnel any traffic.
 *
 * Key Points:
 *  - Creates notification channel and runs as foreground service.
 *  - Owns lifecycle of AppUsageMonitor and blocked apps persistence.
 *  - Listener hooks are available for future event routing.
 *
 * Logging tag: BreakVpnService
 * Filter: adb logcat -s BreakVpnService
 */

import com.Break.monitor.AppUsageMonitor;
import com.Break.prefs.BreakPrefs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.Service;
import android.os.Build;
import android.util.Log;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

import com.Break.MainActivity;
import com.Break.R;

import java.util.Set;
import java.util.HashSet;

public class BreakVpnService extends Service {
    private static final String TAG = "BreakVpnService";
    private static final String NOTIFICATION_CHANNEL_ID = "BreakMonitoring";
    private static final int NOTIFICATION_ID = 1;

    private AppUsageMonitor monitor;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Log.d(TAG, "[CREATE] BreakVpnService onCreate");

        Notification notification = createNotification("Break Active");
        startForeground(NOTIFICATION_ID, notification);

        Log.d(TAG, "[CREATE] Initializing AppUsageMonitor");

        monitor = new AppUsageMonitor(this);

        // Restore blocked apps — defensive copy to avoid SharedPreferences mutation issues
        Set<String> savedBlockedApps = BreakPrefs.getBlockedApps(this);
        Log.d(TAG, "[CREATE] Loaded savedBlockedApps size=" + savedBlockedApps.size());
        if (!savedBlockedApps.isEmpty()) {
            monitor.setBlockedApps(savedBlockedApps);
            Log.d(TAG, "[CREATE] Applied blocked apps to monitor");
        }

        // Load scroll budget configuration from SharedPreferences and apply to monitor
        loadScrollBudgetIntoMonitor(monitor);

        // Set up listener (intentionally lightweight — event routing reserved for future use)
        monitor.setListener(new AppUsageMonitor.AppDetectionListener() {
            @Override
            public void onAppDetected(String packageName, String appName) {
                /* Intentionally left light */
            }
            @Override
            public void onBlockedAppOpened(String packageName, String appName) {
                /* Intentionally left light */
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "[CMD] onStartCommand intent=" + intent);

        // B4: Guard against null intent (OS restarts service with START_STICKY)
        if (intent == null) {
            Log.w(TAG, "[CMD] null intent — OS restart, ignoring");
            return START_STICKY;
        }

        String action = intent.getAction();
        Log.d(TAG, "[CMD] action=" + action);

        if (action == null) {
            Log.w(TAG, "[CMD] null action — ignoring");
            return START_STICKY;
        }

        switch (action) {
                case "START_MONITORING":
                    Notification notification = createNotification("Break Active");
                    startForeground(NOTIFICATION_ID, notification);
                    startMonitoring();
                    break;
                case "STOP_MONITORING":
                    stopMonitoring();
                    stopForeground(true);
                    stopSelf();
                    break;
                case "UPDATE_BLOCKED_APPS":
                    Set<String> blocked = new HashSet<>(intent.getStringArrayListExtra("blockedApps"));
                    Log.d(TAG, "[CMD] UPDATE_BLOCKED_APPS size=" + blocked.size() + " apps=" + blocked.toString());
                    if (monitor != null) monitor.setBlockedApps(blocked);
                    saveBlockedApps(blocked);
                    break;
                case "SET_DELAY_MESSAGE":
                    String message = intent.getStringExtra("message");
                    Log.d(TAG, "[CMD] SET_DELAY_MESSAGE message=" + message);
                    if (monitor != null && message != null) {
                        monitor.setDelayMessage(message);
                    }
                    break;
                case "SET_DELAY_TIME":
                    int seconds = intent.getIntExtra("seconds", BreakPrefs.DEFAULT_DELAY_TIME_SECONDS);
                    Log.d(TAG, "[CMD] SET_DELAY_TIME seconds=" + seconds);
                    if (monitor != null) {
                        monitor.setDelayTime(seconds);
                    }
                    break;
                case "SET_POPUP_DELAY":
                    int minutes = intent.getIntExtra("minutes", BreakPrefs.DEFAULT_POPUP_DELAY_MINUTES);
                    Log.d(TAG, "[CMD] SET_POPUP_DELAY minutes=" + minutes);
                    if (monitor != null) {
                        monitor.setPopupDelayMinutes(minutes);
                    }
                    break;
                case "SET_SCROLL_BUDGET":
                    int allowanceMinutes = intent.getIntExtra("allowanceMinutes", BreakPrefs.DEFAULT_SCROLL_ALLOWANCE_MINUTES);
                    int windowMinutes = intent.getIntExtra("windowMinutes", BreakPrefs.DEFAULT_SCROLL_WINDOW_MINUTES);
                    Log.d(TAG, "[CMD] SET_SCROLL_BUDGET received: allowance=" + allowanceMinutes + "min window=" + windowMinutes + "min");
                    if (monitor != null) {
                        monitor.setScrollBudget(allowanceMinutes, windowMinutes);
                    }
                    break;
                case "com.Break.FREE_BREAK_START":
                    // SharedPreferences already updated by VPNModule.startFreeBreak().
                    // ReelsInterventionService reads prefs directly — no extra state needed here.
                    Log.i(TAG, "[FREE_BREAK] FREE_BREAK_START received — Reels budget accumulation suspended");
                    break;
                case "com.Break.FREE_BREAK_END":
                    // SharedPreferences already updated by VPNModule.endFreeBreakInternal().
                    Log.i(TAG, "[FREE_BREAK] FREE_BREAK_END received — Reels budget accumulation resumed");
                    break;
                case "DISMISS_OVERLAY":
                    // [HOME_DISMISS] Called when the user navigates to the home screen.
                    // ReelsInterventionService detects the app switch near-instantly via
                    // AccessibilityEvent and sends this intent so we can dismiss the delay
                    // overlay before the 1s polling loop would catch it naturally.
                    Log.i(TAG, "[HOME_DISMISS] DISMISS_OVERLAY received — dismissing delay overlay");
                    if (monitor != null) {
                        monitor.dismissOverlayIfShowing();
                    }
                    break;
                default:
                    Log.w(TAG, "[CMD] Unknown action: " + action);
        }

        // Return START_STICKY so service restarts if killed
        return START_STICKY;
    }

    private void startMonitoring() {
        if (monitor == null) {
            monitor = new AppUsageMonitor(this);
            // Load and set blocked apps — defensive copy
            Set<String> savedBlockedApps = BreakPrefs.getBlockedApps(this);
            if (!savedBlockedApps.isEmpty()) {
                monitor.setBlockedApps(savedBlockedApps);
            }
        }
        loadScrollBudgetIntoMonitor(monitor);
        monitor.startMonitoring();
        Log.d(TAG, "Monitoring started with " + (monitor.getBlockedApps() != null ? monitor.getBlockedApps().size() : 0) + " blocked apps");
    }

    private void stopMonitoring() {
        if (monitor != null) {
            monitor.stopMonitoring();
            monitor = null;
        }
    }

    private Notification createNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Break")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true);
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Break Monitoring",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitors app usage to show delay screens");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (monitor != null) {
            monitor.stopMonitoring();
        }
    }

    public void updateBlockedApps(Set<String> blockedApps) {
        if (monitor != null) {
            monitor.setBlockedApps(blockedApps);
        }
    }

    /**
     * Loads scroll budget configuration from SharedPreferences and applies it to the given monitor.
     * Called on onCreate() and inside startMonitoring() to ensure the budget is always in sync.
     *
     * Log tag: [BreakVpnService] [BUDGET]
     */
    private void loadScrollBudgetIntoMonitor(AppUsageMonitor targetMonitor) {
        if (targetMonitor == null) return;
        SharedPreferences prefs = BreakPrefs.get(this);
        int allowanceMin = prefs.getInt(BreakPrefs.KEY_SCROLL_ALLOWANCE_MINUTES, BreakPrefs.DEFAULT_SCROLL_ALLOWANCE_MINUTES);
        int windowMin = prefs.getInt(BreakPrefs.KEY_SCROLL_WINDOW_MINUTES, BreakPrefs.DEFAULT_SCROLL_WINDOW_MINUTES);
        targetMonitor.setScrollBudget(allowanceMin, windowMin);
        Log.d(TAG, "[BUDGET] loadScrollBudgetIntoMonitor: allowance=" + allowanceMin + "min window=" + windowMin + "min");
    }

    private void saveBlockedApps(Set<String> blockedApps) {
        BreakPrefs.get(this)
            .edit()
            .putStringSet(BreakPrefs.KEY_BLOCKED_APPS, blockedApps)
            .apply();
        Log.d(TAG, "[PREF] saveBlockedApps size=" + (blockedApps != null ? blockedApps.size() : 0) + " data=" + blockedApps);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
