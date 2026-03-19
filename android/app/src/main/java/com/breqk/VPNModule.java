package com.breqk;

/*
 * VPNModule
 * ---------
 * React Native native module acting as the bridge between JS and Android OS services.
 * Responsibilities:
 *  - Manage permissions (Usage Stats, Overlay, optional VPN)
 *  - Start/stop foreground monitoring service (MyVpnService)
 *  - Expose screen time and per-app usage statistics via AppUsageMonitor & ScreenTimeTracker
 *  - Emit real-time events to JS when apps are detected/opened
 *
 * Design Notes:
 *  - Permission checks funnel through a single implementation to avoid drift.
 *  - All methods are defensive: exceptions are caught and routed through Promises.
 *  - Event emission uses RCTDeviceEventEmitter only if Catalyst instance is active.
 *  - Monitoring can run without actual VPN tunneling; service is used to keep the app alive.
 */

import android.content.Intent;
import android.provider.Settings;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.Callback;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import android.app.ActivityManager;
import android.content.Context;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;
import android.app.AppOpsManager;
import android.content.pm.ApplicationInfo;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.provider.Settings;
import java.util.Map;
import java.util.HashMap;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;

/*
 * VPNModule
 * ---------
 * React Native native module acting as the bridge between JS and Android OS services.
 * 
 * CRITICAL ARCHITECTURE NOTE:
 * This module has its OWN AppUsageMonitor instance that is SEPARATE from MyVpnService's instance.
 * Both monitors must have synchronized blocked apps lists for the overlay to work correctly.
 * 
 * KEY FIXES APPLIED:
 * 1. loadBlockedAppsIntoMonitor() - Loads blocked apps from SharedPreferences into VPNModule's monitor
 * 2. setBlockedApps() - Now updates BOTH VPNModule's monitor AND sends to MyVpnService
 * 3. startMonitoring() - Reloads blocked apps before starting the monitor
 * 4. Constructor - Loads blocked apps immediately when module is initialized
 */

//This module doesn't send any packet or traffic data back to React Native.
//It's just a "control switch" — start/stop the VPN.
// Just turns the VPN on or off from react native code

public class VPNModule extends ReactContextBaseJavaModule {
    private static final String MODULE_NAME = "VPNModule";
    private static final String TAG = "VPNModule";
    private ReactApplicationContext reactContext;
    private AppUsageMonitor appMonitor;
    private ScreenTimeTracker screenTimeTracker;
    
    public VPNModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        this.appMonitor = new AppUsageMonitor(reactContext);
        this.screenTimeTracker = new ScreenTimeTracker(reactContext);
        Log.d(TAG, "[INIT] VPNModule initialized");
        
        // CRITICAL FIX: Load blocked apps immediately when VPNModule is created
        // This ensures appMonitor has blocked apps even before startMonitoring() is called
        loadBlockedAppsIntoMonitor();
        
        // Set up listener
        appMonitor.setListener(new AppUsageMonitor.AppDetectionListener() {
            @Override
            public void onAppDetected(String packageName, String appName) {
                sendEvent("onAppDetected", createAppEvent(packageName, appName));
            }
            
            @Override
            public void onBlockedAppOpened(String packageName, String appName) {
                sendEvent("onBlockedAppOpened", createAppEvent(packageName, appName));
            }
        });
    }

    /**
     * CRITICAL FIX: Helper method to load blocked apps from SharedPreferences into VPNModule's appMonitor
     * 
     * WHY THIS IS NEEDED:
     * - VPNModule has its own AppUsageMonitor instance (separate from MyVpnService's instance)
     * - When setBlockedApps() is called from React Native, it saves to SharedPreferences via SettingsModule
     * - VPNModule's appMonitor needs to read those saved blocked apps to show overlays
     * - Without this, VPNModule's monitor runs with an empty blocked apps list = no overlays!
     * 
     * CALLED FROM:
     * - Constructor: Loads blocked apps when module is first created
     * - startMonitoring(): Reloads to ensure we have the latest list before monitoring starts
     */
    private void loadBlockedAppsIntoMonitor() {
        try {
            // Same SharedPreferences file and key used by SettingsModule.java
            SharedPreferences prefs = reactContext.getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
            Set<String> savedBlockedApps = prefs.getStringSet("blocked_apps", new HashSet<>());
            
            if (savedBlockedApps != null && !savedBlockedApps.isEmpty()) {
                // IMPORTANT: Create a copy to avoid SharedPreferences mutation issues
                // SharedPreferences.getStringSet() returns a reference that shouldn't be modified
                Set<String> blockedAppsCopy = new HashSet<>(savedBlockedApps);
                appMonitor.setBlockedApps(blockedAppsCopy);
                Log.d(TAG, "[LOAD_BLOCKED] ✓ Loaded " + blockedAppsCopy.size() + " blocked apps into VPNModule's monitor");
                Log.d(TAG, "[LOAD_BLOCKED] Blocked apps: " + blockedAppsCopy.toString());
            } else {
                Log.d(TAG, "[LOAD_BLOCKED] No saved blocked apps found in SharedPreferences (file: breqk_prefs, key: blocked_apps)");
            }
        } catch (Exception e) {
            Log.e(TAG, "[LOAD_BLOCKED] ERROR loading blocked apps: " + e.getMessage(), e);
        }
    }

    @Override
    public String getName() {
        return MODULE_NAME;
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        // Single-source permission check to avoid divergence between methods.
        constants.put("isScreenTimePermissionGranted", hasUsageAccessPermission());
        return constants;
    }

    /**
     * Backward-compat method preserved for any external callers; delegates to the
     * canonical implementation {@link #hasUsageAccessPermission()} to prevent logic drift.
     */
    private boolean isUsageAccessGranted() {
        return hasUsageAccessPermission();
    }

    @ReactMethod
    public void isUsageAccessGranted(Promise promise) {
        try {
            boolean granted = hasUsageAccessPermission();
            promise.resolve(granted);
        } catch (Exception e) {
            promise.reject("PERMISSION_CHECK_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void openUsageAccessSettings() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        reactContext.startActivity(intent);
    }

    @ReactMethod
    public void openPermissionsSettings() {
        // Alias to openUsageAccessSettings to avoid duplicated intent logic.
        openUsageAccessSettings();
    }

    @ReactMethod
    public void startMonitoring(Promise promise) {
        Log.d(TAG, "[START] ========== startMonitoring called ==========");
        try {
            // STEP 1: Start the foreground service (keeps monitoring alive even when app is backgrounded)
            Log.d(TAG, "[START] Step 1: Starting MyVpnService foreground service...");
            Intent serviceIntent = new Intent(reactContext, MyVpnService.class);
            serviceIntent.setAction("START_VPN");
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(serviceIntent);
                Log.d(TAG, "[START] Started foreground service (API >= O)");
            } else {
                reactContext.startService(serviceIntent);
                Log.d(TAG, "[START] Started service (API < O)");
            }

            // STEP 2: CRITICAL FIX - Reload blocked apps from SharedPreferences before starting monitor
            // This ensures VPNModule's appMonitor has the latest blocked apps list
            Log.d(TAG, "[START] Step 2: Loading blocked apps into VPNModule's monitor...");
            loadBlockedAppsIntoMonitor();
            
            // STEP 3: Log current state before starting
            Set<String> currentBlocked = appMonitor.getBlockedApps();
            int blockedCount = (currentBlocked != null) ? currentBlocked.size() : 0;
            Log.d(TAG, "[START] Step 3: VPNModule's monitor has " + blockedCount + " blocked apps");
            if (currentBlocked != null && !currentBlocked.isEmpty()) {
                Log.d(TAG, "[START] Blocked apps list: " + currentBlocked.toString());
            } else {
                Log.w(TAG, "[START] WARNING: No blocked apps! Overlay will NOT show for any app!");
            }

            // STEP 4: MyVpnService handles the monitoring loop
            // VPNModule's appMonitor should NOT start monitoring to prevent double overlays
            // Only MyVpnService's monitor instance should be active
            Log.d(TAG, "[START] Step 4: MyVpnService will handle monitoring (VPNModule monitor stays idle)");
            // REMOVED: appMonitor.startMonitoring(); // <- This caused double overlays!
            
            Log.d(TAG, "[START] ========== startMonitoring complete ==========");
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[START] startMonitoring failed", e);
            promise.reject("START_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void getScreenTimeStats(Promise promise) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                Map<String, Long> stats = screenTimeTracker.getScreenTimeStats();
                WritableMap result = Arguments.createMap();
                result.putDouble("totalScreenTime", stats.get("totalScreenTime"));
                result.putDouble("startTime", stats.get("startTime"));
                result.putDouble("endTime", stats.get("endTime"));
                promise.resolve(result);
            } else {
                promise.reject("UNSUPPORTED", "Screen time tracking requires API level 22 or higher");
            }
        } catch (Exception e) {
            Log.e("VPNModule", "Error getting screen time stats", e);
            promise.reject("ERROR", "Failed to get screen time stats: " + e.getMessage());
        }
    }
    
    @ReactMethod
    public void stopMonitoring(Promise promise) {
        try {
            Log.d(TAG, "[STOP] stopMonitoring called");
            appMonitor.stopMonitoring();
            
            Intent serviceIntent = new Intent(reactContext, MyVpnService.class);
            serviceIntent.setAction("STOP_VPN");
            reactContext.stopService(serviceIntent);
            
            Log.d(TAG, "[STOP] stopMonitoring success");
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[STOP] stopMonitoring failed", e);
            promise.reject("STOP_ERROR", e.getMessage());
        }
    }
    
    // New methods for getting app usage statistics
    @ReactMethod
    public void getAppUsageTime(String packageName, double startTime, double endTime, Promise promise) {
        try {
            long startTimeLong = (long) startTime;
            long endTimeLong = (long) endTime;
            long usageTime = appMonitor.getAppUsageTime(packageName, startTimeLong, endTimeLong);
            promise.resolve(usageTime);
        } catch (Exception e) {
            Log.e("VPNModule", "Error getting app usage time", e);
            promise.reject("USAGE_STATS_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void getTotalScreenTime(double startTime, double endTime, Promise promise) {
        try {
            long startTimeLong = (long) startTime;
            long endTimeLong = (long) endTime;
            long totalTime = appMonitor.getTotalScreenTime(startTimeLong, endTimeLong);
            promise.resolve(totalTime);
        } catch (Exception e) {
            Log.e("VPNModule", "Error getting total screen time", e);
            promise.reject("USAGE_STATS_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void getTodayScreenTime(Promise promise) {
        try {
            long todayTime = appMonitor.getTodayScreenTime();
            promise.resolve(todayTime);
        } catch (Exception e) {
            Log.e("VPNModule", "Error getting today's screen time", e);
            promise.reject("USAGE_STATS_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void getAppTodayUsageTime(String packageName, Promise promise) {
        try {
            long usageTime = appMonitor.getAppTodayUsageTime(packageName);
            promise.resolve(usageTime);
        } catch (Exception e) {
            Log.e("VPNModule", "Error getting app's today usage time", e);
            promise.reject("USAGE_STATS_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void getTopAppsByUsage(double startTime, double endTime, int limit, Promise promise) {
        try {
            long startTimeLong = (long) startTime;
            long endTimeLong = (long) endTime;
            List<AppUsageMonitor.AppUsageInfo> topApps = appMonitor.getTopAppsByUsage(startTimeLong, endTimeLong, limit);
            
            WritableArray appArray = Arguments.createArray();
            for (AppUsageMonitor.AppUsageInfo appInfo : topApps) {
                WritableMap appMap = Arguments.createMap();
                appMap.putString("packageName", appInfo.packageName);
                appMap.putString("appName", appInfo.appName);
                appMap.putDouble("usageTime", appInfo.usageTime);
                appArray.pushMap(appMap);
            }
            
            promise.resolve(appArray);
        } catch (Exception e) {
            Log.e("VPNModule", "Error getting top apps by usage", e);
            promise.reject("USAGE_STATS_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void getBlockedAppsUsageStats(Promise promise) {
        try {
            // Get usage stats for all blocked apps
            Set<String> blockedApps = appMonitor.getBlockedApps();
            WritableArray blockedAppsStats = Arguments.createArray();
            
            long endTime = System.currentTimeMillis();
            long startTime = endTime - (24 * 60 * 60 * 1000); // Last 24 hours
            
            for (String packageName : blockedApps) {
                long usageTime = appMonitor.getAppUsageTime(packageName, startTime, endTime);
                if (usageTime > 0) {
                    WritableMap appStats = Arguments.createMap();
                    appStats.putString("packageName", packageName);
                    appStats.putString("appName", appMonitor.getAppName(packageName));
                    appStats.putDouble("usageTime", usageTime);
                    blockedAppsStats.pushMap(appStats);
                }
            }
            
            promise.resolve(blockedAppsStats);
        } catch (Exception e) {
            Log.e("VPNModule", "Error getting blocked apps usage stats", e);
            promise.reject("USAGE_STATS_ERROR", e.getMessage());
        }
    }
    
    /**
     * setBlockedApps - Called from React Native (Customize screen) when user toggles apps
     * 
     * CRITICAL: This method must update TWO places:
     * 1. VPNModule's own appMonitor instance (for overlay detection)
     * 2. MyVpnService's appMonitor instance (via Intent)
     * 
     * Previous bug: Only updated MyVpnService, leaving VPNModule's monitor empty!
     */
    @ReactMethod
    public void setBlockedApps(ReadableArray apps, Promise promise) {
        Log.d(TAG, "[SET_BLOCKED] ========== setBlockedApps called ==========");
        Log.d(TAG, "[SET_BLOCKED] Received " + (apps != null ? apps.size() : 0) + " apps from React Native");
        
        try {
            Set<String> blockedApps = new HashSet<>();

            if (apps != null){
                if (apps.size() == 1 && apps.getType(0) == com.facebook.react.bridge.ReadableType.String) {
                    String single = apps.getString(0);
                    if (single != null && single.contains(".")) {
                        blockedApps.add(single);
                        Log.d(TAG, "[SET_BLOCKED] Added single app: " + single);
                    }
                } else {
                    // ✅ Normal case: proper array of strings
                    for (int i = 0; i < apps.size(); i++) {
                        if (apps.getType(i) == com.facebook.react.bridge.ReadableType.String) {
                            String app = apps.getString(i);
                            blockedApps.add(app);
                            Log.d(TAG, "[SET_BLOCKED] Added app: " + app);
                        }
                    }
                }
            }
            
            Log.d(TAG, "[SET_BLOCKED] Total blocked apps parsed: " + blockedApps.size());
            Log.d(TAG, "[SET_BLOCKED] Blocked apps: " + blockedApps.toString());

            // CRITICAL FIX #1: Update VPNModule's OWN appMonitor instance
            // Without this line, VPNModule's monitor has NO blocked apps and overlay never shows!
            appMonitor.setBlockedApps(blockedApps);
            Log.d(TAG, "[SET_BLOCKED] ✓ Updated VPNModule's appMonitor with " + blockedApps.size() + " apps");
    
            // CRITICAL FIX #2: Also send intent to MyVpnService to update ITS monitor
            // The service has its own AppUsageMonitor instance that also needs updating
            Intent intent = new Intent(reactContext, MyVpnService.class);
            intent.setAction("UPDATE_BLOCKED_APPS");
            intent.putStringArrayListExtra("blockedApps", new ArrayList<>(blockedApps));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(intent);
            } else {
                reactContext.startService(intent);
            }
            Log.d(TAG, "[SET_BLOCKED] ✓ Sent UPDATE_BLOCKED_APPS intent to MyVpnService");
            
            Log.d(TAG, "[SET_BLOCKED] ========== setBlockedApps complete ==========");
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[SET_BLOCKED] ERROR: " + e.getMessage(), e);
            promise.reject("SET_APPS_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void requestPermissions(Promise promise) {
        try {
            // Request usage stats permission
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            reactContext.startActivity(intent);
            Log.d("VPNModule", "requestPermissions success, resolving promise");
            promise.resolve(true);
        } catch (Exception e) {
            Log.d("VPNModule", "requestPermissions failed, rejecting promise");
            promise.reject("PERMISSION_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void requestOverlayPermission(Promise promise) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            reactContext.startActivity(intent);
            Log.d("VPNModule", "requestOverlayPermission success, resolving promise");
            promise.resolve(true);
        } catch (Exception e) {
            Log.d("VPNModule", "requestOverlayPermission failed, rejecting promise");
            promise.reject("OVERLAY_PERMISSION_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void getInstalledApps(Promise promise) {
        try {
            Log.d("VPNModule", "getInstalledApps");
            WritableArray apps = Arguments.createArray();
            
            // Get list of installed apps
            android.content.pm.PackageManager pm = reactContext.getPackageManager();
            List<android.content.pm.ApplicationInfo> packages = pm.getInstalledApplications(
                android.content.pm.PackageManager.GET_META_DATA
            );
            
            for (android.content.pm.ApplicationInfo packageInfo : packages) {
                // Filter out system apps
                if ((packageInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
                    WritableMap app = Arguments.createMap();
                    app.putString("packageName", packageInfo.packageName);
                    app.putString("appName", pm.getApplicationLabel(packageInfo).toString());
                    apps.pushMap(app);
                }
            }
            
            Log.d("VPNModule", "getInstalledApps success, resolving promise");
            promise.resolve(apps);
        } catch (Exception e) {
            Log.d("VPNModule", "getInstalledApps failed, rejecting promise");
            promise.reject("GET_APPS_ERROR", e.getMessage());
        }
    }
    
    // VPN related methods (optional - you can remove these if not using VPN)
    @ReactMethod
    public void requestVpnPermission(Promise promise) {
        try {
            Intent intent = VpnService.prepare(reactContext);
            if (intent != null) {
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                reactContext.startActivity(intent);
                promise.resolve(false); // user must grant permission
            } else {
                // Permission already granted
                Log.d("VPNModule", "requestVpnPermission success, resolving promise");
                promise.resolve(true);
            }
        } catch (Exception e) {
            Log.d("VPNModule", "requestVpnPermission failed, rejecting promise");
            promise.reject("VPN_PERMISSION_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void startVpnService(Promise promise) {
        try {
            Intent serviceIntent = new Intent(reactContext, MyVpnService.class);
            
            serviceIntent.setAction("START_VPN");
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(serviceIntent);
            } else {
                reactContext.startService(serviceIntent);
            }
            
            Log.d("VPNModule", "startVpnService success, resolving promise");
            promise.resolve(true);
        } catch (Exception e) {
            Log.d("VPNModule", "startVpnService failed, rejecting promise");
            promise.reject("START_VPN_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void stopVpnService(Promise promise) {
        try {
            Intent serviceIntent = new Intent(reactContext, MyVpnService.class);
            serviceIntent.setAction("STOP_VPN");
            reactContext.startService(serviceIntent);
            Log.d("VPNModule", "stopVpnService success, resolving promise");
            promise.resolve(true);
        } catch (Exception e) {
            Log.d("VPNModule", "stopVpnService failed, rejecting promise");
            promise.reject("STOP_VPN_ERROR", e.getMessage());
        }
    }

    private WritableMap createAppEvent(String packageName, String appName) {
        WritableMap event = Arguments.createMap();
        event.putString("packageName", packageName);
        event.putString("appName", appName);
        event.putDouble("timestamp", System.currentTimeMillis());
        return event;
    }
    
    // Ensure this method exists and is working
    private void sendEvent(String eventName, WritableMap params) {
        if (reactContext != null && reactContext.hasActiveCatalystInstance()) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
        }
    }


    @ReactMethod
    public void checkPermissions(Promise promise) {
        WritableMap result = Arguments.createMap();
        result.putBoolean("overlay", Settings.canDrawOverlays(reactContext));
        result.putBoolean("usage", hasUsageAccessPermission());
        promise.resolve(result);
    }
    
    @ReactMethod
    public void setDelayMessage(String message, Promise promise) {
        try {
            Log.d(TAG, "[SET_MESSAGE] Setting delay message: " + message);
            
            // Update VPNModule's appMonitor
            appMonitor.setDelayMessage(message);
            
            // Also send to MyVpnService via Intent
            Intent serviceIntent = new Intent(reactContext, MyVpnService.class);
            serviceIntent.setAction("SET_DELAY_MESSAGE");
            serviceIntent.putExtra("message", message);
            reactContext.startService(serviceIntent);
            
            Log.d(TAG, "[SET_MESSAGE] Message updated successfully");
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[SET_MESSAGE] Failed to set message", e);
            promise.reject("SET_MESSAGE_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void setDelayTime(int seconds, Promise promise) {
        try {
            Log.d(TAG, "[SET_MESSAGE] Setting delay timer to " + seconds + " seconds");
            
            // Update VPNModule's appMonitor
            appMonitor.setDelayTime(seconds);
            
            // Also send to MyVpnService via Intent
            Intent serviceIntent = new Intent(reactContext, MyVpnService.class);
            serviceIntent.setAction("SET_DELAY_TIME");
            serviceIntent.putExtra("seconds", seconds);
            reactContext.startService(serviceIntent);
            
            Log.d(TAG, "[SET_MESSAGE] Message updated successfully");
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[SET_MESSAGE] Failed to set message", e);
            promise.reject("SET_MESSAGE_ERROR", e.getMessage());
        }
    }

    /**
     * setScrollThreshold — Called from React Native (Customize screen) when user adjusts scroll sensitivity.
     * Persists to SharedPreferences so ContentFilterService reads the new threshold at next scroll event.
     *
     * @param threshold Number of scrolls within Reels/Shorts before the intervention popup fires (1–20).
     */
    // ═══════════════════════════════════════════════════════════════════════════
    // Digital Wellbeing bridge methods
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * getDigitalWellbeingStats — Returns comprehensive today stats for the Home screen.
     *
     * Resolves with a map containing:
     *   totalScreenTimeMin  (double) — total foreground time today in minutes
     *   unlockCount         (int)    — device unlocks today; -1 if API < 28
     *   notificationCount   (int)    — notifications seen today; -1 if unavailable
     *   startTime           (double) — start of today in epoch ms
     *   endTime             (double) — now in epoch ms
     *
     * Logging: [WELLBEING]
     */
    @ReactMethod
    public void getDigitalWellbeingStats(Promise promise) {
        Log.d(TAG, "[WELLBEING] getDigitalWellbeingStats called");
        try {
            Map<String, Object> rawStats = screenTimeTracker.getComprehensiveStats();

            WritableMap result = Arguments.createMap();
            result.putDouble("totalScreenTimeMin",
                    rawStats.containsKey("totalScreenTimeMin")
                            ? ((Number) rawStats.get("totalScreenTimeMin")).doubleValue() : 0.0);
            result.putInt("unlockCount",
                    rawStats.containsKey("unlockCount")
                            ? ((Number) rawStats.get("unlockCount")).intValue() : -1);
            result.putInt("notificationCount",
                    rawStats.containsKey("notificationCount")
                            ? ((Number) rawStats.get("notificationCount")).intValue() : -1);
            result.putDouble("startTime",
                    rawStats.containsKey("startTime")
                            ? ((Number) rawStats.get("startTime")).doubleValue() : 0.0);
            result.putDouble("endTime",
                    rawStats.containsKey("endTime")
                            ? ((Number) rawStats.get("endTime")).doubleValue() : 0.0);

            Log.d(TAG, "[WELLBEING] Resolved: totalScreenTimeMin=" +
                    result.getDouble("totalScreenTimeMin") +
                    " unlocks=" + result.getInt("unlockCount") +
                    " notifications=" + result.getInt("notificationCount"));
            promise.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "[WELLBEING] Error: " + e.getMessage(), e);
            promise.reject("WELLBEING_ERROR", "Failed to get digital wellbeing stats: " + e.getMessage());
        }
    }

    /**
     * getTopAppsToday — Returns top N apps by foreground usage today for the Home screen.
     *
     * Resolves with an array where each element is a map containing:
     *   packageName   (String) — e.g. "com.instagram.android"
     *   appName       (String) — human-readable label
     *   usageTimeMin  (double) — foreground minutes today
     *
     * @param limit Maximum number of apps to return (recommended: 5)
     *
     * Logging: [TOP_APPS_TODAY]
     */
    @ReactMethod
    public void getTopAppsToday(int limit, Promise promise) {
        Log.d(TAG, "[TOP_APPS_TODAY] getTopAppsToday called, limit=" + limit);
        try {
            // Build today's time range (midnight → now)
            java.util.Calendar cal = java.util.Calendar.getInstance();
            long endTime = cal.getTimeInMillis();
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            long startTime = cal.getTimeInMillis();

            List<Map<String, Object>> perAppStats =
                    screenTimeTracker.getPerAppStats(startTime, endTime, limit);

            WritableArray appArray = Arguments.createArray();
            for (Map<String, Object> appStats : perAppStats) {
                WritableMap appMap = Arguments.createMap();
                appMap.putString("packageName", (String) appStats.get("packageName"));
                appMap.putString("appName", (String) appStats.get("appName"));
                appMap.putDouble("usageTimeMin",
                        ((Number) appStats.get("usageTimeMin")).doubleValue());
                appArray.pushMap(appMap);
            }

            Log.d(TAG, "[TOP_APPS_TODAY] Returning " + appArray.size() + " apps");
            promise.resolve(appArray);
        } catch (Exception e) {
            Log.e(TAG, "[TOP_APPS_TODAY] Error: " + e.getMessage(), e);
            promise.reject("TOP_APPS_ERROR", "Failed to get top apps: " + e.getMessage());
        }
    }

    @ReactMethod
    public void setScrollThreshold(int threshold, Promise promise) {
        try {
            Log.d(TAG, "[SET_SCROLL_THRESHOLD] Setting scroll threshold to " + threshold);
            SharedPreferences prefs = reactContext.getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
            // Clamp to valid range: 1–20
            int clamped = Math.max(1, Math.min(20, threshold));
            prefs.edit().putInt("scroll_threshold", clamped).apply();
            Log.d(TAG, "[SET_SCROLL_THRESHOLD] Saved scroll_threshold=" + clamped + " to breqk_prefs");
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[SET_SCROLL_THRESHOLD] Failed", e);
            promise.reject("SET_SCROLL_THRESHOLD_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void setPopupDelayMinutes(int minutes, Promise promise) {
        try {
            Log.d(TAG, "[SET_POPUP_DELAY] Setting popup delay to " + minutes + " minutes");
            
            // Update VPNModule's appMonitor
            appMonitor.setPopupDelayMinutes(minutes);
            
            // Also send to MyVpnService via Intent
            Intent serviceIntent = new Intent(reactContext, MyVpnService.class);
            serviceIntent.setAction("SET_POPUP_DELAY");
            serviceIntent.putExtra("minutes", minutes);
            reactContext.startService(serviceIntent);
            
            Log.d(TAG, "[SET_POPUP_DELAY] Popup delay updated successfully");
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[SET_POPUP_DELAY] Failed to set popup delay", e);
            promise.reject("SET_POPUP_DELAY_ERROR", e.getMessage());
        }
    }

    /**
     * setScrollBudget — Called from React Native when user changes scroll budget settings.
     *
     * Saves to SharedPreferences so the budget survives restarts, then sends
     * SET_SCROLL_BUDGET intent to MyVpnService to update its running monitor instance.
     *
     * @param allowanceMinutes Minutes of scroll allowed per window (1–30)
     * @param windowMinutes    Window duration in minutes (15–120)
     */
    @ReactMethod
    public void setScrollBudget(int allowanceMinutes, int windowMinutes, Promise promise) {
        try {
            Log.d(TAG, "[setScrollBudget] allowance=" + allowanceMinutes + "min window=" + windowMinutes + "min");

            // Persist so AppUsageMonitor.loadScrollBudgetFromPrefs() picks up on restart
            SharedPreferences prefs = reactContext.getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
            prefs.edit()
                    .putInt("scroll_allowance_minutes", Math.max(1, allowanceMinutes))
                    .putInt("scroll_window_minutes", Math.max(15, windowMinutes))
                    .apply();

            // Notify MyVpnService's running monitor instance via intent
            Intent serviceIntent = new Intent(reactContext, MyVpnService.class);
            serviceIntent.setAction("SET_SCROLL_BUDGET");
            serviceIntent.putExtra("allowanceMinutes", allowanceMinutes);
            serviceIntent.putExtra("windowMinutes", windowMinutes);
            reactContext.startService(serviceIntent);

            Log.d(TAG, "[setScrollBudget] Scroll budget updated and intent sent to service");
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[setScrollBudget] Failed", e);
            promise.reject("SET_SCROLL_BUDGET_ERROR", e.getMessage());
        }
    }

    /**
     * getScrollBudgetStatus — Called from React Native (Home screen) to show live budget status.
     *
     * Reads state from SharedPreferences, which MyVpnService's AppUsageMonitor persists
     * periodically and on state changes. Returns:
     *   { allowanceMinutes, windowMinutes, usedMs, canScroll, nextScrollAtMs, remainingMs }
     */
    @ReactMethod
    public void getScrollBudgetStatus(Promise promise) {
        try {
            SharedPreferences prefs = reactContext.getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
            int allowanceMinutes = prefs.getInt("scroll_allowance_minutes", 5);
            int windowMinutes = prefs.getInt("scroll_window_minutes", 60);
            long scrollTimeUsedMs = prefs.getLong("scroll_time_used_ms", 0);
            long windowStartTime = prefs.getLong("scroll_window_start_time", 0);
            long budgetExhaustedAt = prefs.getLong("scroll_budget_exhausted_at", 0);

            long allowanceMs = allowanceMinutes * 60 * 1000L;
            long now = System.currentTimeMillis();
            boolean canScroll = (budgetExhaustedAt == 0);
            long nextScrollAtMs = 0;
            long remainingMs = 0;

            if (!canScroll && windowStartTime > 0) {
                long windowMs = windowMinutes * 60 * 1000L;
                nextScrollAtMs = windowStartTime + windowMs;
                // Check if the window has already expired (service may not have reset yet)
                if (now >= nextScrollAtMs) {
                    canScroll = true;
                    remainingMs = allowanceMs;
                    nextScrollAtMs = 0;
                }
            }

            if (canScroll) {
                remainingMs = Math.max(0, allowanceMs - scrollTimeUsedMs);
            }

            WritableMap status = Arguments.createMap();
            status.putInt("allowanceMinutes", allowanceMinutes);
            status.putInt("windowMinutes", windowMinutes);
            status.putDouble("usedMs", scrollTimeUsedMs);
            status.putBoolean("canScroll", canScroll);
            status.putDouble("nextScrollAtMs", nextScrollAtMs);
            status.putDouble("remainingMs", remainingMs);

            Log.d(TAG, "[getScrollBudgetStatus] canScroll=" + canScroll +
                    " remainingMs=" + remainingMs + " usedMs=" + scrollTimeUsedMs);
            promise.resolve(status);
        } catch (Exception e) {
            Log.e(TAG, "[getScrollBudgetStatus] Failed", e);
            promise.reject("GET_SCROLL_BUDGET_STATUS_ERROR", e.getMessage());
        }
    }

    private boolean hasUsageAccessPermission() {
        try {
            AppOpsManager appOps = (AppOpsManager) reactContext.getSystemService(Context.APP_OPS_SERVICE);
            ApplicationInfo appInfo = reactContext.getPackageManager().getApplicationInfo(reactContext.getPackageName(), 0);
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    appInfo.uid, appInfo.packageName);
            return (mode == AppOpsManager.MODE_ALLOWED);
        } catch (Exception e) {
            return false;
        }
}

}
