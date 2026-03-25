package com.breqk;

/*
 * MyVpnService
 * -------------
 * Foreground service used to maintain background monitoring.
 * This implementation does not tunnel traffic; it keeps a persistent
 * notification to reduce the chance of the OS killing the process
 * while AppUsageMonitor runs.
 *
 * Key Points:
 *  - Creates notification channel and runs as foreground service.
 *  - Owns lifecycle of AppUsageMonitor and blocked apps persistence.
 *  - Listener hooks are available for future event routing.
 *
 * Logging tag: MyVpnService
 * Filter: adb logcat -s MyVpnService
 */

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

import java.util.Set;
import java.util.HashSet;

public class MyVpnService extends VpnService {
    private static final String TAG = "MyVpnService";
    private static final String NOTIFICATION_CHANNEL_ID = "BreqkVPN";
    private static final int NOTIFICATION_ID = 1;

    private AppUsageMonitor monitor;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Log.d(TAG, "[CREATE] MyVpnService onCreate");

        Notification notification = createNotification("VPN Active");
        startForeground(NOTIFICATION_ID, notification);

        Log.d(TAG, "[CREATE] Initializing AppUsageMonitor");

        monitor = new AppUsageMonitor(this);

        // Restore blocked apps — defensive copy to avoid SharedPreferences mutation issues
        Set<String> savedBlockedApps = BreqkPrefs.getBlockedApps(this);
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

        if (intent != null) {
            String action = intent.getAction();
            Log.d(TAG, "[CMD] action=" + action);

            // Handle all the Intent actions here
            switch (action) {
                case "START_VPN":
                    Notification notification = createNotification("VPN Active");
                    startForeground(NOTIFICATION_ID, notification);
                    startMonitoring();
                    break;
                case "STOP_VPN":
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
                    int seconds = intent.getIntExtra("seconds", BreqkPrefs.DEFAULT_DELAY_TIME_SECONDS);
                    Log.d(TAG, "[CMD] SET_DELAY_TIME seconds=" + seconds);
                    if (monitor != null) {
                        monitor.setDelayTime(seconds);
                    }
                    break;
                case "SET_POPUP_DELAY":
                    int minutes = intent.getIntExtra("minutes", BreqkPrefs.DEFAULT_POPUP_DELAY_MINUTES);
                    Log.d(TAG, "[CMD] SET_POPUP_DELAY minutes=" + minutes);
                    if (monitor != null) {
                        monitor.setPopupDelayMinutes(minutes);
                    }
                    break;
                case "SET_SCROLL_BUDGET":
                    int allowanceMinutes = intent.getIntExtra("allowanceMinutes", BreqkPrefs.DEFAULT_SCROLL_ALLOWANCE_MINUTES);
                    int windowMinutes = intent.getIntExtra("windowMinutes", BreqkPrefs.DEFAULT_SCROLL_WINDOW_MINUTES);
                    Log.d(TAG, "[CMD] SET_SCROLL_BUDGET received: allowance=" + allowanceMinutes + "min window=" + windowMinutes + "min");
                    if (monitor != null) {
                        monitor.setScrollBudget(allowanceMinutes, windowMinutes);
                    }
                    break;
                case "com.breqk.FREE_BREAK_START":
                    // SharedPreferences already updated by VPNModule.startFreeBreak().
                    // ReelsInterventionService reads prefs directly — no extra state needed here.
                    Log.i(TAG, "[FREE_BREAK] FREE_BREAK_START received — Reels budget accumulation suspended");
                    break;
                case "com.breqk.FREE_BREAK_END":
                    // SharedPreferences already updated by VPNModule.endFreeBreakInternal().
                    Log.i(TAG, "[FREE_BREAK] FREE_BREAK_END received — Reels budget accumulation resumed");
                    break;
                default:
                    Log.w(TAG, "[CMD] Unknown action: " + action);
            }
        }

        // Return START_STICKY so service restarts if killed
        return START_STICKY;
    }

    // Start monitoring
    private void startMonitoring() {
        if (monitor == null) {
            monitor = new AppUsageMonitor(this);
            // Load and set blocked apps — defensive copy
            Set<String> savedBlockedApps = BreqkPrefs.getBlockedApps(this);
            if (!savedBlockedApps.isEmpty()) {
                monitor.setBlockedApps(savedBlockedApps);
            }
        }
        loadScrollBudgetIntoMonitor(monitor);
        monitor.startMonitoring();
        Log.d(TAG, "Monitoring started with " + (monitor.getBlockedApps() != null ? monitor.getBlockedApps().size() : 0) + " blocked apps");
    }

    // Stop monitoring
    private void stopMonitoring() {
        if (monitor != null) {
            monitor.stopMonitoring();
            monitor = null;
        }
    }

    // Create notification for foreground service
    private Notification createNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Breqk")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true);
        return builder.build();
    }

    // Create notification channel for Android O and above
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "App Monitoring",
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
     * Log tag: [MyVpnService] [BUDGET]
     */
    private void loadScrollBudgetIntoMonitor(AppUsageMonitor targetMonitor) {
        if (targetMonitor == null) return;
        // Use a single SharedPreferences instance (previously called getSharedPreferences twice)
        SharedPreferences prefs = BreqkPrefs.get(this);
        int allowanceMin = prefs.getInt(BreqkPrefs.KEY_SCROLL_ALLOWANCE_MINUTES, BreqkPrefs.DEFAULT_SCROLL_ALLOWANCE_MINUTES);
        int windowMin = prefs.getInt(BreqkPrefs.KEY_SCROLL_WINDOW_MINUTES, BreqkPrefs.DEFAULT_SCROLL_WINDOW_MINUTES);
        targetMonitor.setScrollBudget(allowanceMin, windowMin);
        Log.d(TAG, "[BUDGET] loadScrollBudgetIntoMonitor: allowance=" + allowanceMin + "min window=" + windowMin + "min");
    }

    private void saveBlockedApps(Set<String> blockedApps) {
        BreqkPrefs.get(this)
            .edit()
            .putStringSet(BreqkPrefs.KEY_BLOCKED_APPS, blockedApps)
            .apply();
        Log.d(TAG, "[PREF] saveBlockedApps size=" + (blockedApps != null ? blockedApps.size() : 0) + " data=" + blockedApps);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
