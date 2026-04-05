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
 *
 * CRITICAL ARCHITECTURE NOTE:
 * This module has its OWN AppUsageMonitor instance that is SEPARATE from MyVpnService's instance.
 * Both monitors must have synchronized blocked apps lists for the overlay to work correctly.
 * VPNModule's monitor is NOT started for monitoring — only used for getAppName(), getBlockedApps(),
 * and usage stats queries. MyVpnService's monitor handles the actual polling loop.
 */

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
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

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import android.content.Context;
import android.net.VpnService;
import android.util.Log;
import android.app.AppOpsManager;
import android.content.pm.ApplicationInfo;
import java.util.Map;
import java.util.HashMap;

// This module doesn't send any packet or traffic data back to React Native.
// It's just a "control switch" — start/stop the VPN service from React Native code.

public class VPNModule extends ReactContextBaseJavaModule {
    private static final String MODULE_NAME = "VPNModule";
    private static final String TAG = "VPNModule";
    private static final String FREE_BREAK_TAG = "VPNModule:FreeBreak";
    private ReactApplicationContext reactContext;
    private AppUsageMonitor appMonitor;
    private ScreenTimeTracker screenTimeTracker;

    // Free break — schedules the auto-end callback after 20 minutes
    private final android.os.Handler freeBreakHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable freeBreakEndRunnable = null;

    public VPNModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        // Use applicationContext for long-lived objects to prevent Activity context leak.
        // AppUsageMonitor and ScreenTimeTracker only need Context for SharedPreferences,
        // PackageManager, and system services — all of which work with applicationContext.
        this.appMonitor = new AppUsageMonitor(reactContext.getApplicationContext());
        this.screenTimeTracker = new ScreenTimeTracker(reactContext.getApplicationContext());
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
     * Helper method to load blocked apps from SharedPreferences into VPNModule's appMonitor.
     * Uses BreqkPrefs.getBlockedApps() which returns a defensive copy (safe from mutation).
     *
     * CALLED FROM:
     * - Constructor: Loads blocked apps when module is first created
     * - startMonitoring(): Reloads to ensure we have the latest list before monitoring starts
     */
    private void loadBlockedAppsIntoMonitor() {
        try {
            Set<String> blockedAppsCopy = BreqkPrefs.getBlockedApps(reactContext);

            if (!blockedAppsCopy.isEmpty()) {
                appMonitor.setBlockedApps(blockedAppsCopy);
                Log.d(TAG, "[LOAD_BLOCKED] Loaded " + blockedAppsCopy.size() + " blocked apps into VPNModule's monitor");
                Log.d(TAG, "[LOAD_BLOCKED] Blocked apps: " + blockedAppsCopy.toString());
            } else {
                Log.d(TAG, "[LOAD_BLOCKED] No saved blocked apps found in SharedPreferences");
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

    /**
     * Alias for openUsageAccessSettings() — kept for JS backward compatibility.
     */
    @ReactMethod
    public void openPermissionsSettings() {
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
            ServiceHelper.startForegroundServiceCompat(reactContext, serviceIntent);

            // STEP 2: Reload blocked apps from SharedPreferences before starting monitor
            Log.d(TAG, "[START] Step 2: Loading blocked apps into VPNModule's monitor...");
            loadBlockedAppsIntoMonitor();

            // STEP 3: Log current state
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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
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
            Log.e(TAG, "Error getting screen time stats", e);
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
            Log.e(TAG, "Error getting app usage time", e);
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
            Log.e(TAG, "Error getting total screen time", e);
            promise.reject("USAGE_STATS_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void getTodayScreenTime(Promise promise) {
        try {
            long todayTime = appMonitor.getTodayScreenTime();
            promise.resolve(todayTime);
        } catch (Exception e) {
            Log.e(TAG, "Error getting today's screen time", e);
            promise.reject("USAGE_STATS_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void getAppTodayUsageTime(String packageName, Promise promise) {
        try {
            long usageTime = appMonitor.getAppTodayUsageTime(packageName);
            promise.resolve(usageTime);
        } catch (Exception e) {
            Log.e(TAG, "Error getting app's today usage time", e);
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
            Log.e(TAG, "Error getting top apps by usage", e);
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
            Log.e(TAG, "Error getting blocked apps usage stats", e);
            promise.reject("USAGE_STATS_ERROR", e.getMessage());
        }
    }

    /**
     * setBlockedApps - Called from React Native (Customize screen) when user toggles apps
     *
     * CRITICAL: This method must update TWO places:
     * 1. VPNModule's own appMonitor instance (for overlay detection)
     * 2. MyVpnService's appMonitor instance (via Intent)
     */
    @ReactMethod
    public void setBlockedApps(ReadableArray apps, Promise promise) {
        Log.d(TAG, "[SET_BLOCKED] ========== setBlockedApps called ==========");
        Log.d(TAG, "[SET_BLOCKED] Received " + (apps != null ? apps.size() : 0) + " apps from React Native");

        try {
            Set<String> blockedApps = new HashSet<>();

            if (apps != null) {
                if (apps.size() == 1 && apps.getType(0) == com.facebook.react.bridge.ReadableType.String) {
                    String single = apps.getString(0);
                    if (single != null && single.contains(".")) {
                        blockedApps.add(single);
                        Log.d(TAG, "[SET_BLOCKED] Added single app: " + single);
                    }
                } else {
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

            // Update VPNModule's OWN appMonitor instance
            appMonitor.setBlockedApps(blockedApps);
            Log.d(TAG, "[SET_BLOCKED] Updated VPNModule's appMonitor with " + blockedApps.size() + " apps");

            // Also send intent to MyVpnService to update ITS monitor
            Intent intent = new Intent(reactContext, MyVpnService.class);
            intent.setAction("UPDATE_BLOCKED_APPS");
            intent.putStringArrayListExtra("blockedApps", new ArrayList<>(blockedApps));
            ServiceHelper.startForegroundServiceCompat(reactContext, intent);
            Log.d(TAG, "[SET_BLOCKED] Sent UPDATE_BLOCKED_APPS intent to MyVpnService");

            Log.d(TAG, "[SET_BLOCKED] ========== setBlockedApps complete ==========");
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[SET_BLOCKED] ERROR: " + e.getMessage(), e);
            promise.reject("SET_APPS_ERROR", e.getMessage());
        }
    }

    /**
     * requestPermissions — Opens Usage Access settings.
     * Functionally identical to openUsageAccessSettings(); kept for JS backward compatibility.
     */
    @ReactMethod
    public void requestPermissions(Promise promise) {
        try {
            openUsageAccessSettings();
            Log.d(TAG, "requestPermissions success, resolving promise");
            promise.resolve(true);
        } catch (Exception e) {
            Log.d(TAG, "requestPermissions failed, rejecting promise");
            promise.reject("PERMISSION_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void requestOverlayPermission(Promise promise) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            reactContext.startActivity(intent);
            Log.d(TAG, "requestOverlayPermission success, resolving promise");
            promise.resolve(true);
        } catch (Exception e) {
            Log.d(TAG, "requestOverlayPermission failed, rejecting promise");
            promise.reject("OVERLAY_PERMISSION_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void getInstalledApps(Promise promise) {
        try {
            Log.d(TAG, "getInstalledApps");
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

            Log.d(TAG, "getInstalledApps success, resolving promise");
            promise.resolve(apps);
        } catch (Exception e) {
            Log.d(TAG, "getInstalledApps failed, rejecting promise");
            promise.reject("GET_APPS_ERROR", e.getMessage());
        }
    }

    // VPN related methods (optional - used for explicit VPN permission flow)
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
                Log.d(TAG, "requestVpnPermission success, resolving promise");
                promise.resolve(true);
            }
        } catch (Exception e) {
            Log.d(TAG, "requestVpnPermission failed, rejecting promise");
            promise.reject("VPN_PERMISSION_ERROR", e.getMessage());
        }
    }

    /**
     * @deprecated Use startMonitoring() instead. This method starts the service but
     * doesn't reload blocked apps or log diagnostics. Kept for backward compatibility.
     */
    @ReactMethod
    public void startVpnService(Promise promise) {
        try {
            Intent serviceIntent = new Intent(reactContext, MyVpnService.class);
            serviceIntent.setAction("START_VPN");
            ServiceHelper.startForegroundServiceCompat(reactContext, serviceIntent);

            Log.d(TAG, "startVpnService success, resolving promise");
            promise.resolve(true);
        } catch (Exception e) {
            Log.d(TAG, "startVpnService failed, rejecting promise");
            promise.reject("START_VPN_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void stopVpnService(Promise promise) {
        try {
            Intent serviceIntent = new Intent(reactContext, MyVpnService.class);
            serviceIntent.setAction("STOP_VPN");
            reactContext.startService(serviceIntent);
            Log.d(TAG, "stopVpnService success, resolving promise");
            promise.resolve(true);
        } catch (Exception e) {
            Log.d(TAG, "stopVpnService failed, rejecting promise");
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
        result.putBoolean("deviceAdmin", isDeviceAdminActiveInternal());
        promise.resolve(result);
    }

    // ── Device Admin helpers ──────────────────────────────────────────────────

    /**
     * Returns true if Breqk is currently an active Device Administrator.
     * Internal helper — also exposed to JS via isDeviceAdminActive().
     */
    private boolean isDeviceAdminActiveInternal() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) reactContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent =
                new ComponentName(reactContext, BreqkDeviceAdminReceiver.class);
        return dpm != null && dpm.isAdminActive(adminComponent);
    }

    /**
     * JS-callable: check whether Device Admin is active.
     * Returns { active: boolean }.
     */
    @ReactMethod
    public void isDeviceAdminActive(Promise promise) {
        boolean active = isDeviceAdminActiveInternal();
        Log.d(TAG, "[DEVICE_ADMIN] isDeviceAdminActive → " + active);
        WritableMap result = Arguments.createMap();
        result.putBoolean("active", active);
        promise.resolve(result);
    }

    /**
     * JS-callable: launch the system Device Admin activation dialog.
     * The user sees a standard Android "Activate device administrator?" screen.
     * After the user accepts or cancels, the app returns to foreground and
     * PermissionsScreen re-checks via checkPermissions().
     *
     * DEV bypass (ADB — works on debug builds):
     *   adb shell dpm remove-active-admin com.breqk/.BreqkDeviceAdminReceiver
     */
    @ReactMethod
    public void activateDeviceAdmin(Promise promise) {
        try {
            if (isDeviceAdminActiveInternal()) {
                Log.d(TAG, "[DEVICE_ADMIN] already active — skipping activation intent");
                promise.resolve(true);
                return;
            }
            ComponentName adminComponent =
                    new ComponentName(reactContext, BreqkDeviceAdminReceiver.class);
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            intent.putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Enabling Device Admin prevents Breqk from being uninstalled while you "
                            + "are committed to your focus goals. You can deactivate this anytime "
                            + "in Settings → Security → Device Administrators."
            );
            // Launch from the current Activity (same task) so that when the system dialog
            // finishes, the user is returned to Breqk and AppState fires 'active'.
            // Do NOT use FLAG_ACTIVITY_NEW_TASK here — that puts the dialog in a separate
            // task, causing the user to land on the launcher instead of coming back to us.
            android.app.Activity currentActivity = getCurrentActivity();
            if (currentActivity != null) {
                currentActivity.startActivity(intent);
            } else {
                // Fallback: no active Activity (e.g. app in background). Use new task flag.
                Log.w(TAG, "[DEVICE_ADMIN] no current Activity — falling back to FLAG_ACTIVITY_NEW_TASK");
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                reactContext.startActivity(intent);
            }
            Log.d(TAG, "[DEVICE_ADMIN] activation dialog launched");
            promise.resolve(false); // user must confirm in system dialog
        } catch (Exception e) {
            Log.e(TAG, "[DEVICE_ADMIN] failed to launch activation dialog", e);
            promise.reject("DEVICE_ADMIN_ERROR", e.getMessage());
        }
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
            Log.d(TAG, "[SET_DELAY_TIME] Setting delay timer to " + seconds + " seconds");

            // Update VPNModule's appMonitor
            appMonitor.setDelayTime(seconds);

            // Also send to MyVpnService via Intent
            Intent serviceIntent = new Intent(reactContext, MyVpnService.class);
            serviceIntent.setAction("SET_DELAY_TIME");
            serviceIntent.putExtra("seconds", seconds);
            reactContext.startService(serviceIntent);

            Log.d(TAG, "[SET_DELAY_TIME] Delay time updated successfully");
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "[SET_DELAY_TIME] Failed to set delay time", e);
            promise.reject("SET_DELAY_TIME_ERROR", e.getMessage());
        }
    }

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
            // Clamp to valid range: 1–20
            int clamped = Math.max(1, Math.min(20, threshold));
            BreqkPrefs.get(reactContext)
                    .edit()
                    .putInt(BreqkPrefs.KEY_SCROLL_THRESHOLD, clamped)
                    .apply();
            Log.d(TAG, "[SET_SCROLL_THRESHOLD] Saved scroll_threshold=" + clamped);
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
            BreqkPrefs.get(reactContext)
                    .edit()
                    .putInt(BreqkPrefs.KEY_SCROLL_ALLOWANCE_MINUTES, Math.max(1, allowanceMinutes))
                    .putInt(BreqkPrefs.KEY_SCROLL_WINDOW_MINUTES, Math.max(15, windowMinutes))
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
            SharedPreferences prefs = BreqkPrefs.get(reactContext);
            int allowanceMinutes = prefs.getInt(BreqkPrefs.KEY_SCROLL_ALLOWANCE_MINUTES, BreqkPrefs.DEFAULT_SCROLL_ALLOWANCE_MINUTES);
            int windowMinutes = prefs.getInt(BreqkPrefs.KEY_SCROLL_WINDOW_MINUTES, BreqkPrefs.DEFAULT_SCROLL_WINDOW_MINUTES);
            long scrollTimeUsedMs = prefs.getLong(BreqkPrefs.KEY_SCROLL_TIME_USED_MS, 0);
            long windowStartTime = prefs.getLong(BreqkPrefs.KEY_SCROLL_WINDOW_START_TIME, 0);
            long budgetExhaustedAt = prefs.getLong(BreqkPrefs.KEY_SCROLL_BUDGET_EXHAUSTED_AT, 0);

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

    // =========================================================================
    // Free Break — 20-minute daily suspension of all Reels/Shorts interventions
    // =========================================================================

    /**
     * Starts the 20-minute free break.
     *
     * Guards: rejects if a break is already active, or if the break was already
     * used today (calendar day in device locale, resets at midnight).
     *
     * Side effects:
     *  - Writes free_break_active=true, free_break_start_time, free_break_last_used_date
     *    to SharedPreferences (ReelsInterventionService reads these directly).
     *  - Dispatches FREE_BREAK_START intent to MyVpnService (informational).
     *  - Schedules an auto-end Runnable for 20 minutes from now.
     */
    @ReactMethod
    public void startFreeBreak(Promise promise) {
        try {
            SharedPreferences prefs = BreqkPrefs.get(reactContext);

            // Guard: already active
            if (prefs.getBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false)) {
                Log.w(FREE_BREAK_TAG, "[FREE_BREAK] startFreeBreak rejected — break already active");
                promise.reject("ALREADY_ACTIVE", "Free break is already running");
                return;
            }

            // Guard: already used today
            String todayDate = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(new java.util.Date());
            String lastUsedDate = prefs.getString(BreqkPrefs.KEY_FREE_BREAK_LAST_USED_DATE, "");
            if (todayDate.equals(lastUsedDate)) {
                Log.w(FREE_BREAK_TAG, "[FREE_BREAK] startFreeBreak rejected — already used today (" + todayDate + ")");
                promise.reject("ALREADY_USED_TODAY", "Free break already used today");
                return;
            }

            long now = System.currentTimeMillis();
            prefs.edit()
                    .putBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, true)
                    .putLong(BreqkPrefs.KEY_FREE_BREAK_START_TIME, now)
                    .putString(BreqkPrefs.KEY_FREE_BREAK_LAST_USED_DATE, todayDate)
                    .apply();

            Log.i(FREE_BREAK_TAG, "[FREE_BREAK] Break started at " + now + " date=" + todayDate
                    + " — auto-ends in 20 min");

            // Notify MyVpnService (informational — service doesn't need its own timer
            // since ReelsInterventionService reads SharedPreferences directly)
            Intent breakStartIntent = new Intent(reactContext, MyVpnService.class);
            breakStartIntent.setAction("com.breqk.FREE_BREAK_START");
            ServiceHelper.startForegroundServiceCompat(reactContext, breakStartIntent);

            // Schedule auto-end on main thread
            if (freeBreakEndRunnable != null) freeBreakHandler.removeCallbacks(freeBreakEndRunnable);
            freeBreakEndRunnable = () -> {
                Log.i(FREE_BREAK_TAG, "[FREE_BREAK] 20-min timer expired — auto-ending break");
                endFreeBreakInternal();
            };
            freeBreakHandler.postDelayed(freeBreakEndRunnable, BreqkPrefs.FREE_BREAK_DURATION_MS);

            WritableMap result = Arguments.createMap();
            result.putBoolean("success", true);
            result.putDouble("startTimeMs", (double) now);
            result.putDouble("durationMs", (double) BreqkPrefs.FREE_BREAK_DURATION_MS);
            promise.resolve(result);
        } catch (Exception e) {
            Log.e(FREE_BREAK_TAG, "[FREE_BREAK] startFreeBreak error: " + e.getMessage(), e);
            promise.reject("ERROR", e.getMessage());
        }
    }

    /**
     * Ends the free break early (user-initiated).
     * Clears free_break_active, cancels the auto-end timer, and notifies MyVpnService.
     */
    @ReactMethod
    public void endFreeBreak(Promise promise) {
        try {
            Log.i(FREE_BREAK_TAG, "[FREE_BREAK] endFreeBreak called — user-initiated early end");
            endFreeBreakInternal();
            promise.resolve(null);
        } catch (Exception e) {
            Log.e(FREE_BREAK_TAG, "[FREE_BREAK] endFreeBreak error: " + e.getMessage(), e);
            promise.reject("ERROR", e.getMessage());
        }
    }

    /**
     * Returns the current free break status as a JS-readable map:
     *   enabled     — whether the feature toggle is on in settings
     *   active      — whether a break is currently running
     *   startTimeMs — epoch ms when the current break started (0 if not active)
     *   durationMs  — always FREE_BREAK_DURATION_MS (1 200 000 ms)
     *   remainingMs — ms until the break ends (0 if not active)
     *   usedToday   — whether the break was already used today (12 am – 11:59 pm)
     *
     * Also performs stale-flag cleanup: if active=true but the 20-min window has
     * elapsed (e.g. process was killed and restarted), auto-clears the flag.
     */
    @ReactMethod
    public void getFreeBreakStatus(Promise promise) {
        try {
            SharedPreferences prefs = BreqkPrefs.get(reactContext);
            boolean enabled = prefs.getBoolean(BreqkPrefs.KEY_FREE_BREAK_ENABLED, false);
            boolean active = prefs.getBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false);
            long startTime = prefs.getLong(BreqkPrefs.KEY_FREE_BREAK_START_TIME, 0);
            String lastUsedDate = prefs.getString(BreqkPrefs.KEY_FREE_BREAK_LAST_USED_DATE, "");

            long now = System.currentTimeMillis();

            // Stale-flag cleanup: clear if the 20-min window has passed without a clean end
            if (active && startTime > 0 && (now - startTime) >= BreqkPrefs.FREE_BREAK_DURATION_MS) {
                Log.i(FREE_BREAK_TAG, "[FREE_BREAK] getFreeBreakStatus: stale active flag — auto-clearing");
                prefs.edit().putBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false).apply();
                active = false;
            }

            long remainingMs = 0;
            if (active && startTime > 0) {
                remainingMs = Math.max(0, (startTime + BreqkPrefs.FREE_BREAK_DURATION_MS) - now);
            }

            String todayDate = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(new java.util.Date());
            boolean usedToday = todayDate.equals(lastUsedDate);

            WritableMap map = Arguments.createMap();
            map.putBoolean("enabled", enabled);
            map.putBoolean("active", active);
            map.putDouble("startTimeMs", (double) startTime);
            map.putDouble("durationMs", (double) BreqkPrefs.FREE_BREAK_DURATION_MS);
            map.putDouble("remainingMs", (double) remainingMs);
            map.putBoolean("usedToday", usedToday);

            Log.d(FREE_BREAK_TAG, "[FREE_BREAK] getFreeBreakStatus: enabled=" + enabled
                    + " active=" + active + " remainingMs=" + remainingMs + " usedToday=" + usedToday);
            promise.resolve(map);
        } catch (Exception e) {
            Log.e(FREE_BREAK_TAG, "[FREE_BREAK] getFreeBreakStatus error: " + e.getMessage(), e);
            promise.reject("ERROR", e.getMessage());
        }
    }

    /**
     * Internal: clears the free break active flag, cancels the auto-end timer,
     * and notifies MyVpnService. Called by both the timer callback and endFreeBreak().
     */
    private void endFreeBreakInternal() {
        if (freeBreakEndRunnable != null) {
            freeBreakHandler.removeCallbacks(freeBreakEndRunnable);
            freeBreakEndRunnable = null;
        }
        BreqkPrefs.get(reactContext).edit()
                .putBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false)
                .apply();
        Log.i(FREE_BREAK_TAG, "[FREE_BREAK] Break ended — free_break_active=false");

        Intent breakEndIntent = new Intent(reactContext, MyVpnService.class);
        breakEndIntent.setAction("com.breqk.FREE_BREAK_END");
        ServiceHelper.startForegroundServiceCompat(reactContext, breakEndIntent);
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
