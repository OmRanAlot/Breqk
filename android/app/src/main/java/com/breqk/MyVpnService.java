package com.breqk;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

import java.io.IOException;
import java.net.InetSocketAddress;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

// The actual VPN that READS the incoming data and creates a Notification so the service runs in background
public class MyVpnService extends VpnService {
    private static final String TAG = "MyVpnService";
    private static final String NOTIFICATION_CHANNEL_ID = "BreqkVPN";
    private static final int NOTIFICATION_ID = 1;
    private static final String LOG_TAG = "VPNActivity";
    
    private AppUsageMonitor monitor;
    private ParcelFileDescriptor vpnInterface;
    private boolean isRunning = false;
    private Thread vpnThread;
    private ConcurrentHashMap<String, Long> appAccessTimes = new ConcurrentHashMap<>();
    private AppMonitorCallback callback;

    public interface AppMonitorCallback {
        void onAppDetected(String packageName);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Log.d(TAG, "[CREATE] MyVpnService onCreate");

        Notification notification = createNotification("VPN Active");
        startForeground(NOTIFICATION_ID, notification);

        Log.d(TAG, "[CREATE] Initializing AppUsageMonitor");

        monitor = new AppUsageMonitor(this);
        // Restore blocked apps
        Set<String> savedBlockedApps = loadBlockedApps();
        Log.d(TAG, "[CREATE] Loaded savedBlockedApps size=" + (savedBlockedApps != null ? savedBlockedApps.size() : 0));
        if (savedBlockedApps != null) {
            monitor.setBlockedApps(savedBlockedApps);
            Log.d(TAG, "[CREATE] Applied blocked apps to monitor");
        }

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
         */
        // Load scroll budget configuration from SharedPreferences and apply to monitor
        loadScrollBudgetIntoMonitor(monitor);

        // Set up listener as before
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
    // Start the VPN service
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.d(TAG, "[CMD] onStartCommand intent=" + intent);

        if (intent != null){
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
                    int seconds = intent.getIntExtra("seconds", 15);
                    Log.d(TAG, "[CMD] SET_DELAY_TIME seconds=" + seconds);
                    if (monitor != null) {
                        monitor.setDelayTime(seconds);
                    }
                    break;
                case "SET_POPUP_DELAY":
                    int minutes = intent.getIntExtra("minutes", 10);
                    Log.d(TAG, "[CMD] SET_POPUP_DELAY minutes=" + minutes);
                    if (monitor != null) {
                        monitor.setPopupDelayMinutes(minutes);
                    }
                    break;
                case "SET_SCROLL_BUDGET":
                    int allowanceMinutes = intent.getIntExtra("allowanceMinutes", 5);
                    int windowMinutes = intent.getIntExtra("windowMinutes", 60);
                    Log.d(TAG, "[CMD] SET_SCROLL_BUDGET received: allowance=" + allowanceMinutes + "min window=" + windowMinutes + "min");
                    if (monitor != null) {
                        monitor.setScrollBudget(allowanceMinutes, windowMinutes);
                    }
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
            // Load and set blocked apps
            Set<String> savedBlockedApps = loadBlockedApps();
            if (savedBlockedApps != null && !savedBlockedApps.isEmpty()) {
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

    // Parse packet to detect app
    private String parsePacket(ByteBuffer packet) {
        // Simple packet inspection to detect app
        // This is a simplified version - you'd need more sophisticated parsing
        try {
            // Check IP version (4 or 6)
            byte version = (byte) ((packet.get(0) >> 4) & 0xF);
            
            if (version == 4) {
                // IPv4 packet
                int protocol = packet.get(9) & 0xFF;
                
                // Get destination port for TCP/UDP
                if (protocol == 6 || protocol == 17) { // TCP or UDP
                    int destPort = ((packet.get(22) & 0xFF) << 8) | (packet.get(23) & 0xFF);
                    
                    // Map common ports to apps (simplified)
                    return mapPortToApp(destPort);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing packet", e);
        }
        return null;
    }

    // Map port to app
    private String mapPortToApp(int port) {
        // This is a simplified mapping - you'd need a more comprehensive solution
        switch (port) {
            case 443:
            case 5228: // Google Play Services
                return "google";
            case 5222: // WhatsApp
                return "com.whatsapp";
            case 3478: // Facebook
                return "com.facebook.katana";
            default:
                return null;
        }
    }
    
    // Start the VPN
    private void startVPN(Intent intent) {
        if (vpnInterface == null) {
            startForeground(NOTIFICATION_ID, createNotification("VPN Active"));
            Builder builder = new Builder();
            builder.setSession("Breqk")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setMtu(1400);
            
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (PackageManager.NameNotFoundException e){
                Log.e(TAG, "Failed to add disallowed application", e);
            }

            // Create the VPN interface
            vpnInterface = builder.establish();
            
            if (vpnInterface != null) {
                if (monitor != null) {
                    monitor.startMonitoring();
                }
            }
        }
    }


    // Stop the VPN
    private void stopVPN(Intent intent) {
        if (vpnThread != null) {
            vpnThread.interrupt();
        }
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN interface", e);
            }
            vpnInterface = null;
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

    // Set callback for app detection
    public void setCallback(AppMonitorCallback callback) {
        this.callback = callback;
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
     * Log tag: [MyVpnService] SET_SCROLL_BUDGET or [CREATE]
     */
    private void loadScrollBudgetIntoMonitor(AppUsageMonitor targetMonitor) {
        if (targetMonitor == null) return;
        int allowanceMin = getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE)
                .getInt("scroll_allowance_minutes", 5);
        int windowMin = getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE)
                .getInt("scroll_window_minutes", 60);
        targetMonitor.setScrollBudget(allowanceMin, windowMin);
        Log.d(TAG, "[BUDGET] loadScrollBudgetIntoMonitor: allowance=" + allowanceMin + "min window=" + windowMin + "min");
    }

    private void saveBlockedApps(Set<String> blockedApps) {
        getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("blocked_apps", blockedApps)
            .apply();
        Log.d(TAG, "[PREF] saveBlockedApps size=" + (blockedApps != null ? blockedApps.size() : 0) + " data=" + blockedApps);
    }
    
    private Set<String> loadBlockedApps() {
        Set<String> set = getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE)
            .getStringSet("blocked_apps", new HashSet<>());
        Log.d(TAG, "[PREF] loadBlockedApps size=" + (set != null ? set.size() : 0) + " data=" + set);
        return set;
    }
    
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
