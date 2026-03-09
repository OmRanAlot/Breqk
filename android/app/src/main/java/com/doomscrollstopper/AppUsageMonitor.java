package com.doomscrollstopper;

import android.app.usage.UsageStats;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

/*
 * AppUsageMonitor
 * ----------------
 * Core detection loop for foreground app usage and overlay intervention.
 * Responsibilities:
 *  - Poll UsageStats for current foreground app at 1s interval (battery-aware)
 *  - Show delay overlay for apps in the blocked list, unless explicitly allowed this session
 *  - Maintain a lightweight in-memory session allowlist (`allowedThisSession`)
 *  - Persist blocked apps in SharedPreferences (doomscroll_prefs)
 *
 * Notes on Performance & Battery:
 *  - Polling is kept at 1000ms to balance responsiveness and battery usage.
 *  - Handler bound to main Looper schedules the runnable only while `isMonitoring` is true.
 *  - Overlay operations run on UI thread; avoid heavy work inside callbacks.
 */

public class AppUsageMonitor {
    private static final String TAG = "AppUsageMonitor";
    private Context context;
    private UsageStatsManager usageStatsManager;
    private Handler handler;
    private boolean isMonitoring = false;
    private Set<String> blockedApps = new HashSet<>();
    private ConcurrentHashMap<String, Long> appDelayTimes = new ConcurrentHashMap<>();
    private WindowManager windowManager;
    private View overlayView;
    private String lastDetectedApp = "";
    private boolean isOverlayActive = false;
    private String lastAppPackage = "";
    private String currentForegroundApp = "";
    private Set<String> allowedThisSession = new HashSet<>();
    // Cooldown tracking to prevent immediate re-triggers after dismissal or allow
    private final Map<String, Long> popupCooldown = new ConcurrentHashMap<>();
    private static final long POPUP_COOLDOWN_MS = 1000; // 1s cooldown to avoid rapid re-triggers after Continue
    private static final long OVERLAY_DEBOUNCE_MS = 500; // 0.5s guard to prevent double overlay creation
    private long overlayPendingUntil = 0L;
    private final Object overlayLock = new Object();

    // Custom message for the delay overlay (set from React Native)
    private String customMessage = "Take a moment to consider if you really need this app right now";
    private int customDelayTimeSeconds = 15; // Default delay time for countdown
    // Popup delay: how long to wait after FIRST popup before showing popup again
    // (in minutes)
    private int popupDelayMinutes = 10; // Default: 10 minute
    // Track when each blocked app was opened (packageName -> timestamp in
    // milliseconds)
    private final ConcurrentHashMap<String, Long> appOpenTimestamps = new ConcurrentHashMap<>();
    // Track when the LAST popup was shown for each app (packageName -> timestamp
    // in milliseconds)
    // This is used to show the next popup after X minutes
    private final ConcurrentHashMap<String, Long> lastPopupShownTimestamps = new ConcurrentHashMap<>();
    // Store the monitor runnable so we can remove it to prevent concurrent loops
    private Runnable monitorRunnable;

    public interface AppDetectionListener {
        void onAppDetected(String packageName, String appName);

        void onBlockedAppOpened(String packageName, String appName);
    }

    private AppDetectionListener listener;

    public AppUsageMonitor(Context context) {
        this.context = context;
        this.usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void startMonitoring() {
        try {
            Log.d(TAG, "=== startMonitoring START ===");
            Log.d(TAG, "Current isMonitoring=" + isMonitoring);
            Log.d(TAG, "Current popupDelayMinutes=" + popupDelayMinutes);
            Log.d(TAG, "Current customDelayTimeSeconds=" + customDelayTimeSeconds);

            // Prevent multiple concurrent monitor threads
            if (isMonitoring) {
                Log.d(TAG, "Monitoring already active, skipping duplicate start");
                return;
            }

            // Clean up any orphaned callbacks from previous sessions
            if (monitorRunnable != null) {
                Log.d(TAG, "Removing old monitor runnable");
                handler.removeCallbacks(monitorRunnable);
                monitorRunnable = null;
            }

            if (!hasUsageStatsPermission()) {
                Log.w(TAG, "Usage stats permission not granted; requesting...");
                requestUsageStatsPermission();
                Log.d(TAG, "=== startMonitoring END (no permission) ===");
                return;
            }
            Log.d(TAG, "Usage stats permission OK");

            if (!hasOverlayPermission()) {
                Log.w(TAG, "Overlay permission not granted; requesting...");
                requestOverlayPermission();
                Log.d(TAG, "startMonitoring: missing overlay permission");
                Log.d(TAG, "=== startMonitoring END (no overlay permission) ===");
                return;
            }
            Log.d(TAG, "Overlay permission OK");

            loadBlockedAppsFromPrefs();
            Log.d(TAG, "Loaded blocked apps count=" + (blockedApps != null ? blockedApps.size() : 0));
            if (blockedApps != null && !blockedApps.isEmpty()) {
                Log.d(TAG, "Blocked apps list: " + blockedApps.toString());
            } else {
                Log.w(TAG, "WARNING: No blocked apps loaded! Popups will NOT show!");
            }

            isMonitoring = true;
            Log.d(TAG, "Starting monitor thread...");

            monitorApps();
            Log.d(TAG, "Monitor loop initiated");
            Log.d(TAG, "=== startMonitoring END (success) ===");
        } catch (Exception e) {
            Log.e(TAG, "Error starting monitoring", e);
            Log.d(TAG, "=== startMonitoring END (error) ===");
        }
    }

    public void loadBlockedAppsFromPrefs() {
        SharedPreferences prefs = context.getSharedPreferences("doomscroll_prefs", Context.MODE_PRIVATE);
        Set<String> appSet = prefs.getStringSet("blocked_apps", new HashSet<>());
        blockedApps = new HashSet<>(appSet); // make a copy
    }

    // main monitoring loop that checks the foreground app every second and shows
    // the overlay if needed
    private void monitorApps() {
        // Use the class-level handler to avoid redundant instances and reduce GC
        // pressure.

        monitorRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    String foregroundApp = getCurrentForegroundApp();
                    if (foregroundApp == null) {
                        Log.d(TAG, "Foreground app is null; skipping this tick");
                        // Continue loop even if foreground app is null
                        if (isMonitoring) {
                            handler.postDelayed(this, 1000);
                        }
                        return;
                    }

                    if (foregroundApp != null && !foregroundApp.equals(context.getPackageName())) {
                        String appName = getAppName(foregroundApp);
                        boolean isBlocked = blockedApps.contains(foregroundApp);
                        boolean isAllowed = allowedThisSession.contains(foregroundApp);
                        Long lastShown = popupCooldown.get(foregroundApp);
                        long now = System.currentTimeMillis();
                        long remainingCooldown = (lastShown == null) ? 0
                                : Math.max(0, POPUP_COOLDOWN_MS - (now - lastShown));
                        Log.d(TAG,
                                "Tick fgApp=" + foregroundApp + " blocked=" + isBlocked + " allowedThisSession="
                                        + isAllowed + " overlayActive=" + isOverlayActive + " cooldownMs="
                                        + remainingCooldown + " blockedSize=" + blockedApps.size());

                        // Track when blocked apps are opened
                        if (isBlocked && !isAllowed) {
                            // If this is a new blocked app or app was switched to, record the open time
                            if (!foregroundApp.equals(currentForegroundApp)) {
                                appOpenTimestamps.put(foregroundApp, now);
                                // Clear last popup timestamp when app is reopened (new session)
                                lastPopupShownTimestamps.remove(foregroundApp);
                                Log.d(TAG, "Recorded open time for " + foregroundApp + " at " + now
                                        + " (new session, cleared last popup timestamp)");
                            }
                        }

                        // Check if we should show the overlay
                        // CRITICAL: Check for second popup even if app is in allowedThisSession
                        // allowedThisSession only prevents FIRST popup, not second popup
                        if (isBlocked && !isOverlayActive) {
                            // Get when this app was opened
                            Long appOpenTime = appOpenTimestamps.get(foregroundApp);
                            Long lastPopupTime = lastPopupShownTimestamps.get(foregroundApp);
                            long popupDelayMs = popupDelayMinutes * 60 * 1000; // Convert minutes to milliseconds

                            // Determine if we should show popup:
                            // 1. If no previous popup shown yet AND app not in allowed session → show
                            // immediately (first popup)
                            // 2. If previous popup was shown and X minutes have passed → show again (next
                            // popup) - regardless of allowedThisSession
                            boolean shouldShowFirstPopup = (appOpenTime != null && lastPopupTime == null
                                    && !isAllowed);
                            boolean shouldShowNextPopup = (lastPopupTime != null
                                    && (now - lastPopupTime) >= popupDelayMs);
                            boolean shouldShowPopup = shouldShowFirstPopup || shouldShowNextPopup;

                            // Enhanced logging for debugging
                            long timeSinceOpenMs = appOpenTime != null ? (now - appOpenTime) : 0;
                            long timeSinceOpenSec = timeSinceOpenMs / 1000;
                            long timeSinceLastPopupMs = lastPopupTime != null ? (now - lastPopupTime) : 0;
                            long timeSinceLastPopupSec = timeSinceLastPopupMs / 1000;

                            Log.d(TAG, "Popup check for " + foregroundApp + ": delayMinutes=" + popupDelayMinutes +
                                    " appOpenTime=" + appOpenTime + " lastPopupTime=" + lastPopupTime +
                                    " shouldShowFirst=" + shouldShowFirstPopup + " shouldShowNext="
                                    + shouldShowNextPopup +
                                    " shouldShow=" + shouldShowPopup +
                                    " timeSinceOpen=" + timeSinceOpenSec + "s" +
                                    " timeSinceLastPopup=" + timeSinceLastPopupSec + "s");

                            if (!shouldShowPopup && lastPopupTime != null) {
                                long timeRemaining = popupDelayMs - (now - lastPopupTime);
                                long minutesRemaining = timeRemaining / (60 * 1000);
                                long secondsRemaining = (timeRemaining % (60 * 1000)) / 1000;
                                Log.d(TAG,
                                        "Next popup delay not yet reached for " + foregroundApp + ". "
                                                + minutesRemaining + "m " + secondsRemaining + "s remaining (need "
                                                + (popupDelayMs / 1000) + "s total)");
                            } else if (!shouldShowPopup && appOpenTime == null) {
                                Log.w(TAG, "WARNING: Cannot show popup for " + foregroundApp
                                        + " - no app open timestamp recorded");
                            } else if (shouldShowPopup) {
                                Log.i(TAG, "✓ Popup should show NOW for " + foregroundApp + "! delayMinutes="
                                        + popupDelayMinutes + " timeSinceOpen=" + timeSinceOpenSec + "s");
                            }

                            // Small debounce to avoid double overlay creation when two ticks race
                            if (now < overlayPendingUntil) {
                                Log.d(TAG,
                                        "Overlay debounce active for " + foregroundApp + "; skipping overlay creation");
                            } else if (lastShown != null && (now - lastShown) < POPUP_COOLDOWN_MS) {
                                Log.d(TAG, "Cooldown active for " + foregroundApp + ", skipping overlay");
                            } else if (shouldShowPopup) {
                                synchronized (overlayLock) {
                                    if (overlayView != null) {
                                        Log.d(TAG, "Overlay view already being created for " + foregroundApp
                                                + "; skipping duplicate call");
                                    } else {
                                        // Only call handleBlockedApp if we're not already creating an overlay
                                        Log.d("AppMonitor", "Blocked app detected: " + appName);
                                        Log.i(TAG, "Blocked app opened: " + appName);

                                        // Track when popup is shown (for next popup timing)
                                        lastPopupShownTimestamps.put(foregroundApp, now);
                                        Log.d(TAG, "Recording popup shown time for " + foregroundApp + " at " + now);

                                        overlayPendingUntil = now + OVERLAY_DEBOUNCE_MS;
                                        handleBlockedApp(foregroundApp, appName);
                                    }
                                }
                            }
                        }

                        // If user switches away from an allowed app, remove it from allowed session and
                        // clear timestamps
                        if (!foregroundApp.equals(currentForegroundApp)) {
                            if (currentForegroundApp != null && !currentForegroundApp.isEmpty()) {
                                allowedThisSession.remove(currentForegroundApp);
                                appOpenTimestamps.remove(currentForegroundApp); // Clear timestamp when switching away
                                lastPopupShownTimestamps.remove(currentForegroundApp); // Clear last popup timestamp
                                                                                       // when switching away
                                Log.d(TAG, "Cleared timestamps for " + currentForegroundApp + " after switching away");
                            }
                            currentForegroundApp = foregroundApp;
                        }

                    }

                    // Repeat every second
                    if (isMonitoring) {
                        handler.postDelayed(this, 1000);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error monitoring apps", e);
                }
            }
        };

        handler.post(monitorRunnable);
    }

    private String getCurrentForegroundApp() {
        // Prefer UsageEvents for higher fidelity foreground detection
        try {
            long endTime = System.currentTimeMillis();
            long startTime = endTime - 60000; // look back 60 seconds to capture last foreground event

            UsageEvents events = usageStatsManager.queryEvents(startTime, endTime);
            UsageEvents.Event event = new UsageEvents.Event();
            String lastForeground = null;
            long lastTs = 0;

            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                long ts = event.getTimeStamp();
                if (ts >= lastTs) {
                    if (type == UsageEvents.Event.MOVE_TO_FOREGROUND || type == UsageEvents.Event.ACTIVITY_RESUMED) {
                        lastForeground = event.getPackageName();
                        lastTs = ts;
                    }
                }
            }

            if (lastForeground != null) {
                Log.d(TAG, "UsageEvents detected foreground: " + lastForeground + " at ts=" + lastTs);
                return lastForeground;
            }

            // Fallback to aggregated UsageStats if no events found
            List<UsageStats> stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    endTime - 1000 * 60,
                    endTime);
            if (stats != null && !stats.isEmpty()) {
                SortedMap<Long, UsageStats> sortedMap = new TreeMap<>();
                for (UsageStats usageStats : stats) {
                    sortedMap.put(usageStats.getLastTimeUsed(), usageStats);
                }
                if (!sortedMap.isEmpty()) {
                    String pkg = sortedMap.get(sortedMap.lastKey()).getPackageName();
                    Log.d(TAG, "Fallback UsageStats foreground: " + pkg);
                    return pkg;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting current foreground app", e);
        }
        return null;
    }

    // checks if the app is already being handled by an active overlay to prevent
    // duplicate overlays
    private void handleBlockedApp(String packageName, String appName) {
        if (isOverlayActive && packageName.equals(lastAppPackage)) {
            Log.d(TAG, "Overlay already active for: " + appName);
            return;
        }

        Log.i(TAG, "Handling blocked app: " + appName);
        showDelayOverlay(packageName, appName);
    }

    // CODE FOR OVERLAY DISPLAY AND INTERACTION show overlay
    private void showDelayOverlay(String packageName, String appName) {
        // Double-check gate before posting to handler to prevent concurrent overlay
        // creations
        synchronized (overlayLock) {
            if (isOverlayActive && packageName.equals(lastAppPackage)) {
                Log.d(TAG, "Overlay already active for " + packageName + "; skipping duplicate overlay");
                return;
            }
            lastAppPackage = packageName;
            isOverlayActive = true;
            // Set overlayView to a sentinel to prevent duplicate calls before the handler
            // executes
            if (overlayView == null) {
                overlayView = new View(context); // Sentinel to block duplicate guard checks
            }
            // Ensure debounce is active when we begin overlay creation
            overlayPendingUntil = System.currentTimeMillis() + OVERLAY_DEBOUNCE_MS;
        }

        Log.i(TAG, "Preparing to show overlay for " + appName + " (" + packageName + ")");

        handler.post(() -> {
            try {
                Log.i(TAG, "Overlay handler entered for " + appName + " (" + packageName + ")");

                // Clean up old overlay if it exists
                if (overlayView != null && overlayView.getParent() != null) {
                    Log.d(TAG, "Removing old overlay view");
                    try {
                        windowManager.removeView(overlayView);
                    } catch (Exception e) {
                        Log.e(TAG, "Error removing old overlay", e);
                    }
                }

                // POPUP_MARKER: native overlay popup entry point (searchable)
                Log.i(TAG, "POPUP_MARKER showing delay overlay for " + appName + " (" + packageName + ")");

                /*
                 * OVERLAY CREATION
                 * ----------------
                 * LayoutInflater converts XML layout into a View object that can be displayed.
                 * Think of it as "building" the UI from the blueprint (delay_overlay.xml).
                 */
                LayoutInflater inflater = LayoutInflater.from(context);
                overlayView = inflater.inflate(R.layout.delay_overlay, null);

                /*
                 * FIND VIEW COMPONENTS
                 * --------------------
                 */
                TextView titleText = overlayView.findViewById(R.id.title);
                TextView messageText = overlayView.findViewById(R.id.message);
                TextView countdownText = overlayView.findViewById(R.id.countdown);
                ProgressBar progressBar = overlayView.findViewById(R.id.progressBar);
                Button continueButton = overlayView.findViewById(R.id.continueButton);
                Button backButton = overlayView.findViewById(R.id.backButton);

                /*
                 * SET INITIAL TEXT & VISIBILITY
                 * ------------------------------
                 */
                titleText.setText("Pause and reflect. Is this how you want to spend your time?");
                messageText.setText(
                        customMessage != null && !customMessage.isEmpty() ? customMessage : "TAKE A MOMENT TO DECIDE");

                // Continue button is visible but disabled in Stitch design
                continueButton.setEnabled(false);
                continueButton.setText("CONTINUE TO " + appName.toUpperCase());
                continueButton.setBackgroundResource(R.drawable.disabled_btn_bg);
                continueButton.setTextColor(android.graphics.Color.parseColor("#66F1FFE7"));

                /*
                 * WINDOW MANAGER PARAMETERS
                 * -------------------------
                 */
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        PixelFormat.TRANSLUCENT);

                params.gravity = Gravity.CENTER;

                overlayView.setFocusable(true);
                overlayView.setFocusableInTouchMode(true);
                overlayView.requestFocus();

                windowManager.addView(overlayView, params);

                synchronized (overlayLock) {
                    overlayPendingUntil = 0L;
                }

                /*
                 * START COUNTDOWN & ANIMATION
                 * ---------------------------
                 */
                startCountdown(countdownText, progressBar, continueButton, customDelayTimeSeconds);

                /*
                 * CONTINUE BUTTON CLICK HANDLER
                 * -----------------------------
                 */
                continueButton.setOnClickListener(v -> {
                    Log.d(TAG, "Continue clicked for " + packageName);
                    allowedThisSession.add(packageName);
                    popupCooldown.put(packageName, System.currentTimeMillis());
                    removeOverlay();
                });

                /*
                 * BACK BUTTON CLICK HANDLER
                 * --------------------------
                 */
                backButton.setOnClickListener(v -> {
                    Log.i(TAG, "Back clicked for " + packageName);
                    allowedThisSession.remove(packageName);
                    Log.i(TAG, "Back pressed: no cooldown; will show immediately on next open for " + packageName);
                    removeOverlay();
                    Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                    homeIntent.addCategory(Intent.CATEGORY_HOME);
                    homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(homeIntent);
                });

                Log.i(TAG, "Delay overlay shown for " + appName + " (" + packageName + ")[INSIDE HANDLER]");
            } catch (Exception e) {
                Log.e(TAG, "Overlay handler error", e);
                synchronized (overlayLock) {
                    overlayPendingUntil = 0L;
                    isOverlayActive = false;
                    lastAppPackage = "";
                    overlayView = null;
                }
            }
        });
        Log.i(TAG, "Overlay shown for " + appName + " (" + packageName + ")[OUTSIDE HANDLER]");
    }

    /*
     * COUNTDOWN TIMER WITH ANIMATION
     * -------------------------------
     */
    private void startCountdown(TextView countdownText, ProgressBar progressBar, Button continueButton, int seconds) {
        final int totalSeconds = seconds;
        progressBar.setProgress(0);

        handler.postDelayed(new Runnable() {
            int remaining = seconds;

            @Override
            public void run() {
                if (overlayView == null) {
                    Log.d(TAG, "Countdown stopped: overlay was removed");
                    return;
                }

                /*
                 * UPDATE COUNTDOWN TEXT (Format 0:00)
                 */
                if (remaining > 0) {
                    int mins = remaining / 60;
                    int secs = remaining % 60;
                    countdownText.setText(String.format("%d:%02d", mins, secs));
                } else {
                    countdownText.setText("READY");
                }

                /*
                 * UPDATE PROGRESS BAR ANIMATION
                 */
                int progressPercent = (int) (((float) (totalSeconds - remaining) / totalSeconds) * 100);
                progressBar.setProgress(progressPercent);

                remaining--;

                if (remaining >= 0) {
                    handler.postDelayed(this, 1000);
                } else {
                    progressBar.setProgress(100);
                    // Enable continue button as per Stitch design
                    continueButton.setEnabled(true);
                    continueButton.setBackgroundResource(R.drawable.primary_btn_bg);
                    continueButton.setTextColor(android.graphics.Color.parseColor("#1A1B41"));
                    Log.d(TAG, "Countdown complete for " + lastAppPackage + " - Continue button enabled");
                }
            }
        }, 0);
    }

    private void removeOverlay() {
        if (overlayView != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
        }
        isOverlayActive = false;
        // NOTE: We DON'T clear appOpenTimestamps or lastPopupShownTimestamps here
        // because we want to track the next popup timing even after a popup is
        // dismissed
        // Timestamps are only cleared when user switches away from the app
        lastAppPackage = "";
        overlayPendingUntil = 0L;
    }

    public String getAppName(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(appInfo).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private boolean hasUsageStatsPermission() {
        long currentTime = System.currentTimeMillis();
        List<UsageStats> stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                currentTime - 1000 * 60,
                currentTime);
        return stats != null && !stats.isEmpty();
    }

    private void requestUsageStatsPermission() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    public void stopMonitoring() {
        Log.d(TAG, "stopMonitoring called");
        isMonitoring = false;

        // Remove any pending monitor callbacks to fully stop the loop
        if (monitorRunnable != null) {
            handler.removeCallbacks(monitorRunnable);
            Log.d(TAG, "Removed monitor runnable from handler");
        }

        // Clear all app open timestamps and last popup timestamps when monitoring
        // stops
        appOpenTimestamps.clear();
        lastPopupShownTimestamps.clear();
        Log.d(TAG, "Cleared all app open timestamps and last popup timestamps");

        removeOverlay();
        Log.d(TAG, "stopMonitoring completed");
    }

    public void setBlockedApps(Set<String> apps) {
        this.blockedApps = apps;
    }

    public void setListener(AppDetectionListener listener) {
        this.listener = listener;
    }

    public Set<String> getBlockedApps() {
        return new HashSet<>(blockedApps);
    }

    public void setDelayMessage(String message) {
        if (message != null && !message.trim().isEmpty()) {
            this.customMessage = message;
            Log.d(TAG, "Custom delay message updated: " + message);
        }
    }

    public void setDelayTime(int seconds) {
        // This method can be used to set a custom delay time for the countdown
        // For now, we will just log it, as the countdown is currently hardcoded to 15
        // seconds
        if (seconds < 5)
            seconds = 5; // Minimum 5 seconds
        if (seconds > 120)
            seconds = 120; // Maximum 120 seconds

        this.customDelayTimeSeconds = seconds;
        Log.d(TAG, "Custom delay time set: " + seconds + " seconds");
    }

    public void setPopupDelayMinutes(int minutes) {
        // Set how long to wait after FIRST popup before showing the popup again
        if (minutes < 0)
            minutes = 0; // Minimum 0 minutes (show immediately again)
        if (minutes > 60)
            minutes = 60; // Maximum 60 minutes

        this.popupDelayMinutes = minutes;
        Log.d(TAG, "Popup delay set: " + minutes + " minutes (first popup shows immediately, second popup after "
                + minutes + " min)");

        // Note: We don't clear timestamps when delay changes - let them continue
        // tracking
    }

    // TO IMPLEMENT

    // New methods for getting app usage statistics
    public long getAppUsageTime(String packageName, long startTime, long endTime) {
        try {
            if (!hasUsageStatsPermission()) {
                return 0;
            }

            List<UsageStats> stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    startTime,
                    endTime);

            long totalTime = 0;
            for (UsageStats stat : stats) {
                if (stat.getPackageName().equals(packageName)) {
                    totalTime += stat.getTotalTimeInForeground();
                }
            }
            return totalTime;
        } catch (Exception e) {
            Log.e(TAG, "Error getting app usage time for " + packageName, e);
            return 0;
        }
    }

    public long getTotalScreenTime(long startTime, long endTime) {
        try {
            if (!hasUsageStatsPermission()) {
                return 0;
            }

            List<UsageStats> stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    startTime,
                    endTime);

            long totalTime = 0;
            for (UsageStats stat : stats) {
                totalTime += stat.getTotalTimeInForeground();
            }
            return totalTime;
        } catch (Exception e) {
            Log.e(TAG, "Error getting total screen time", e);
            return 0;
        }
    }

    public List<AppUsageInfo> getTopAppsByUsage(long startTime, long endTime, int limit) {
        List<AppUsageInfo> appUsageList = new ArrayList<>();

        try {
            if (!hasUsageStatsPermission()) {
                return appUsageList;
            }

            List<UsageStats> stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    startTime,
                    endTime);

            // Group by package name and sum up usage time
            Map<String, Long> appUsageMap = new HashMap<>();
            for (UsageStats stat : stats) {
                String packageName = stat.getPackageName();
                long usageTime = stat.getTotalTimeInForeground();

                if (appUsageMap.containsKey(packageName)) {
                    appUsageMap.put(packageName, appUsageMap.get(packageName) + usageTime);
                } else {
                    appUsageMap.put(packageName, usageTime);
                }
            }

            // Convert to AppUsageInfo objects and sort by usage time
            for (Map.Entry<String, Long> entry : appUsageMap.entrySet()) {
                String packageName = entry.getKey();
                long usageTime = entry.getValue();

                if (usageTime > 0) { // Only include apps with actual usage
                    String appName = getAppName(packageName);
                    appUsageList.add(new AppUsageInfo(packageName, appName, usageTime));
                }
            }

            // Sort by usage time (descending) and limit results
            appUsageList.sort((a, b) -> Long.compare(b.usageTime, a.usageTime));
            if (limit > 0 && appUsageList.size() > limit) {
                appUsageList = appUsageList.subList(0, limit);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error getting top apps by usage", e);
        }

        return appUsageList;
    }

    public long getTodayScreenTime() {
        long endTime = System.currentTimeMillis();
        long startTime = endTime - (24 * 60 * 60 * 1000); // Last 24 hours
        return getTotalScreenTime(startTime, endTime);
    }

    public long getAppTodayUsageTime(String packageName) {
        long endTime = System.currentTimeMillis();
        long startTime = endTime - (24 * 60 * 60 * 1000); // Last 24 hours
        return getAppUsageTime(packageName, startTime, endTime);
    }

    // Helper class to hold app usage information
    public static class AppUsageInfo {
        public String packageName;
        public String appName;
        public long usageTime;

        public AppUsageInfo(String packageName, String appName, long usageTime) {
            this.packageName = packageName;
            this.appName = appName;
            this.usageTime = usageTime;
        }
    }
}