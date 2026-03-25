package com.breqk;

import android.app.usage.UsageStats;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.widget.Button;
import android.widget.TextView;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
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
 *  - Persist blocked apps in SharedPreferences (breqk_prefs)
 *
 * Notes on Performance & Battery:
 *  - Polling is kept at 1000ms to balance responsiveness and battery usage.
 *  - Handler bound to main Looper schedules the runnable only while `isMonitoring` is true.
 *  - Overlay operations run on UI thread; avoid heavy work inside callbacks.
 *  - SharedPreferences reads (scroll budget, Reels state) are throttled to every 5s
 *    to reduce polling overhead. See PREFS_SYNC_INTERVAL_MS.
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
    // Popup delay: how long to wait after FIRST popup before showing popup again (in minutes)
    private int popupDelayMinutes = 10; // Default: 10 minutes
    // Track when each blocked app was opened (packageName -> timestamp in milliseconds)
    private final ConcurrentHashMap<String, Long> appOpenTimestamps = new ConcurrentHashMap<>();
    // Track when the LAST popup was shown for each app (packageName -> timestamp in milliseconds)
    // This is used to show the next popup after X minutes
    private final ConcurrentHashMap<String, Long> lastPopupShownTimestamps = new ConcurrentHashMap<>();
    // Store the monitor runnable so we can remove it to prevent concurrent loops
    private Runnable monitorRunnable;
    // Store the countdown runnable so we can cancel it when overlay is removed
    private Runnable countdownRunnable;
    // ObjectAnimator for the breathing circle on the delay overlay — cancelled in removeOverlay()
    private ObjectAnimator breathingAnimator = null;

    // ─── Overlay Safety & Auto-Dismiss ─────────────────────────────────────
    // Timestamp when the overlay was displayed — used for safety timeout
    private long overlayShownAt = 0L;
    // Cached launcher package name — resolved once via PackageManager
    private String cachedLauncherPackage = null;
    // Known system packages that should never trigger the overlay (dialer, system UI, etc.)
    private static final Set<String> SYSTEM_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.dialer",
            "com.samsung.android.dialer",
            "com.google.android.dialer",
            "com.android.incallui",
            "com.android.systemui",
            "com.android.phone",
            "com.samsung.android.incallui",
            "com.android.server.telecom"
    ));

    // ─── SharedPreferences Throttling ───────────────────────────────────────
    // Sync scroll budget & Reels state from SharedPreferences every 5 seconds
    // instead of every 1-second tick to reduce polling overhead.
    // Android caches SharedPreferences in memory, but the repeated reads + logging
    // add up. A 5s interval is acceptable because the JS bridge already has a 5-min
    // cache TTL for stats display.
    private static final long PREFS_SYNC_INTERVAL_MS = 5000;
    private long lastPrefsSyncTime = 0;
    // Cached SharedPreferences instance — Android internally caches these, but
    // storing the reference avoids the hash lookup in getSharedPreferences() each tick.
    private SharedPreferences cachedPrefs;
    // Cached Reels state — refreshed every PREFS_SYNC_INTERVAL_MS
    private boolean cachedInReels = false;

    // ─── Stale Entry Pruning ────────────────────────────────────────────────
    // Prune ConcurrentHashMap entries older than 24h every 60 ticks (~1 minute)
    private static final int PRUNE_INTERVAL_TICKS = 60;
    private static final long STALE_ENTRY_AGE_MS = 24 * 60 * 60 * 1000; // 24 hours
    private int tickCounter = 0;

    // ─── Scroll Budget ─────────────────────────────────────────────────────────
    // Configuration (loaded from SharedPreferences via loadScrollBudgetFromPrefs, updated by setScrollBudget)
    private int scrollAllowanceMinutes = 5;    // minutes allowed per window
    private int scrollWindowMinutes = 60;      // window duration in minutes
    private boolean scrollBudgetEnabled = true; // master on/off toggle

    // In-memory tracking — READ-ONLY mirrors of values written by ReelsInterventionService.
    // Synced from SharedPreferences each sync tick so JS bridge APIs stay accurate.
    // Do NOT write these back to SharedPreferences — ReelsInterventionService is the sole writer.
    private long scrollTimeUsedMs = 0;
    private long windowStartTime = 0;
    private long budgetExhaustedAt = 0;

    // ─── Reels State Detection ────────────────────────────────────────────────
    // ReelsInterventionService writes is_in_reels / is_in_reels_timestamp / is_in_reels_package
    // to SharedPreferences. We read those here to gate scroll budget accumulation to only
    // the time the user is actually viewing Reels/Shorts (not the entire Instagram app).

    /**
     * Maximum age (in ms) of the Reels state timestamp before we treat it as stale.
     * ReelsInterventionService refreshes the timestamp via heartbeat every 2s, so
     * 5s gives ~2 missed heartbeats of tolerance before we assume the service crashed
     * or the user left Reels without a clean exit event.
     */
    private static final long REELS_STATE_STALENESS_MS = 5000;

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
        this.cachedPrefs = BreqkPrefs.get(context);
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
            loadScrollBudgetFromPrefs();
            Log.d(TAG, "Loaded blocked apps count=" + (blockedApps != null ? blockedApps.size() : 0));
            if (blockedApps != null && !blockedApps.isEmpty()) {
                Log.d(TAG, "Blocked apps list: " + blockedApps.toString());
            } else {
                Log.w(TAG, "WARNING: No blocked apps loaded! Popups will NOT show!");
            }

            isMonitoring = true;
            // Reset throttle timer so first tick syncs immediately
            lastPrefsSyncTime = 0;
            tickCounter = 0;
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
        // Use BreqkPrefs.getBlockedApps() which returns a defensive copy
        blockedApps = BreqkPrefs.getBlockedApps(context);
    }

    // main monitoring loop that checks the foreground app every second and shows
    // the overlay if needed
    private void monitorApps() {
        // Use the class-level handler to avoid redundant instances and reduce GC pressure.

        monitorRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    // ── Periodic maintenance: prune stale ConcurrentHashMap entries ──
                    tickCounter++;
                    if (tickCounter >= PRUNE_INTERVAL_TICKS) {
                        tickCounter = 0;
                        pruneStaleEntries();
                    }

                    String foregroundApp = getCurrentForegroundApp();
                    if (foregroundApp == null) {
                        Log.d(TAG, "Foreground app is null; skipping this tick");
                        // Continue loop even if foreground app is null
                        if (isMonitoring) {
                            handler.postDelayed(this, 1000);
                        }
                        return;
                    }

                    // ─── Auto-Dismiss: remove overlay when user leaves the blocked app ───
                    // If the overlay is active and the foreground app has changed away from the
                    // app the overlay was shown for, dismiss it immediately. This handles:
                    //   - User pressing Home while overlay is displayed
                    //   - Incoming phone call switching to dialer
                    //   - User switching to a different (non-blocked) app
                    if (isOverlayActive && foregroundApp != null
                            && !foregroundApp.equals(lastAppPackage)
                            && !foregroundApp.equals(context.getPackageName())) {
                        Log.i(TAG, "[AUTO_DISMISS] User left blocked app " + lastAppPackage
                                + " → now on " + foregroundApp + "; removing overlay");
                        handler.post(() -> removeOverlay());
                    }
                    // Also auto-dismiss if foreground is a system app or home launcher —
                    // these should never have the overlay blocking them
                    if (isOverlayActive && isSystemOrLauncher(foregroundApp)) {
                        Log.i(TAG, "[AUTO_DISMISS] System/launcher app detected: "
                                + foregroundApp + "; removing overlay");
                        handler.post(() -> removeOverlay());
                    }
                    // Safety timeout: auto-dismiss overlay if it's been visible too long
                    // without user interaction (failsafe for edge cases)
                    if (isOverlayActive && overlayShownAt > 0) {
                        long maxOverlayDurationMs = (customDelayTimeSeconds + 30) * 1000L;
                        long now = System.currentTimeMillis();
                        if ((now - overlayShownAt) > maxOverlayDurationMs) {
                            Log.w(TAG, "[SAFETY_DISMISS] Overlay for " + lastAppPackage
                                    + " exceeded max duration (" + maxOverlayDurationMs + "ms); auto-dismissing");
                            handler.post(() -> removeOverlay());
                        }
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

                        // [ScrollBudget] Throttled sync of budget & Reels state from SharedPreferences.
                        // Only sync every PREFS_SYNC_INTERVAL_MS (5s) instead of every 1s tick.
                        if (now - lastPrefsSyncTime >= PREFS_SYNC_INTERVAL_MS) {
                            lastPrefsSyncTime = now;
                            cachedInReels = isCurrentlyInReels(foregroundApp);
                            if (scrollBudgetEnabled) {
                                syncScrollBudgetFromPrefs();
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
                                Log.i(TAG, "Popup should show NOW for " + foregroundApp + "! delayMinutes="
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

    /**
     * Prunes stale entries from ConcurrentHashMaps to prevent unbounded growth.
     * Called every PRUNE_INTERVAL_TICKS ticks (~1 minute). Removes entries older
     * than STALE_ENTRY_AGE_MS (24 hours).
     */
    private void pruneStaleEntries() {
        long cutoff = System.currentTimeMillis() - STALE_ENTRY_AGE_MS;
        int pruned = 0;

        pruned += pruneMap(appDelayTimes, cutoff);
        pruned += pruneMap(popupCooldown, cutoff);
        pruned += pruneMap(appOpenTimestamps, cutoff);
        pruned += pruneMap(lastPopupShownTimestamps, cutoff);

        if (pruned > 0) {
            Log.d(TAG, "[PRUNE] Removed " + pruned + " stale entries (older than 24h)");
        }
    }

    /**
     * Removes entries from a ConcurrentHashMap where the Long value (timestamp) is before cutoff.
     * Returns the number of entries removed.
     */
    private int pruneMap(Map<String, Long> map, long cutoff) {
        int removed = 0;
        Iterator<Map.Entry<String, Long>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() < cutoff) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * Resolves the default home launcher package via PackageManager.
     * Cached after first call since the launcher rarely changes at runtime.
     */
    private String getLauncherPackage() {
        if (cachedLauncherPackage != null) {
            return cachedLauncherPackage;
        }
        try {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolveInfo = context.getPackageManager()
                    .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            if (resolveInfo != null && resolveInfo.activityInfo != null) {
                cachedLauncherPackage = resolveInfo.activityInfo.packageName;
                Log.d(TAG, "[FG_DETECT] Resolved launcher package: " + cachedLauncherPackage);
            }
        } catch (Exception e) {
            Log.e(TAG, "[FG_DETECT] Error resolving launcher package", e);
        }
        return cachedLauncherPackage;
    }

    /**
     * Returns true if the given package is a system app, launcher, or phone/dialer
     * that should never trigger the blocked-app overlay.
     * Checks both the hardcoded SYSTEM_PACKAGES set and the dynamically resolved launcher.
     */
    private boolean isSystemOrLauncher(String packageName) {
        if (packageName == null) return false;
        // Check hardcoded system packages
        if (SYSTEM_PACKAGES.contains(packageName)) return true;
        // Check dynamically resolved home launcher
        String launcher = getLauncherPackage();
        if (launcher != null && packageName.equals(launcher)) return true;
        return false;
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
                        // App moved to foreground — record it as the current candidate
                        lastForeground = event.getPackageName();
                        lastTs = ts;
                    } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND || type == UsageEvents.Event.ACTIVITY_PAUSED) {
                        // App moved to background — if it's our current foreground candidate,
                        // invalidate it. This prevents stale foreground detection when the user
                        // goes to the home screen, receives a phone call, etc.
                        if (event.getPackageName().equals(lastForeground)) {
                            Log.d(TAG, "[FG_DETECT] " + lastForeground + " moved to background at ts=" + ts);
                            lastForeground = null;
                            lastTs = ts;
                        }
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
        // Set isOverlayActive as external guard before posting to handler.
        // The actual overlayView sentinel is created inside the handler to avoid
        // leak if an exception occurs between here and handler execution.
        synchronized (overlayLock) {
            if (isOverlayActive && packageName.equals(lastAppPackage)) {
                Log.d(TAG, "Overlay already active for " + packageName + "; skipping duplicate overlay");
                return;
            }
            lastAppPackage = packageName;
            isOverlayActive = true;
            overlayShownAt = System.currentTimeMillis(); // Track when overlay was displayed for safety timeout
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
                // Set overlayView here (inside handler) instead of as a sentinel outside.
                // This prevents the sentinel leak if an exception occurs before the handler runs.
                overlayView = inflater.inflate(R.layout.delay_overlay, null);

                /*
                 * FIND VIEW COMPONENTS
                 * --------------------
                 * New design (7_overlay.html): breathing_circle, title, backButton, continueButton.
                 * Removed from old design: message TextView, countdown TextView, progressBar.
                 */
                View breathingCircle = overlayView.findViewById(R.id.pulse_ring);
                TextView titleText = overlayView.findViewById(R.id.title);
                Button continueButton = overlayView.findViewById(R.id.continueButton);
                Button backButton = overlayView.findViewById(R.id.backButton);

                /*
                 * SET INITIAL TEXT
                 * ----------------
                 * Title: use customMessage if the user set one, else the default question.
                 * Continue button: disabled, shows countdown text ("Continue (Wait Xs)").
                 */
                titleText.setText(customMessage != null && !customMessage.isEmpty()
                        ? customMessage : "Is this intentional?");
                Log.d(TAG, "Overlay title set to: " + titleText.getText());

                // Continue button starts disabled; countdown text reflects customDelayTimeSeconds
                continueButton.setEnabled(false);
                continueButton.setText("Continue (Wait " + customDelayTimeSeconds + "s)");
                Log.d(TAG, "Continue button initialised — will enable after " + customDelayTimeSeconds + "s");

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
                                WindowManager.LayoutParams.FLAG_FULLSCREEN |
                                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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
                 * START BREATHING ANIMATION & COUNTDOWN
                 * --------------------------------------
                 * Breathing: ObjectAnimator scales the circle 1.0→1.05→1.0 every 3s (infinite).
                 * Countdown: enables the Continue button after customDelayTimeSeconds ticks.
                 */
                startRippleAnimation(breathingCircle);
                startContinueCountdown(continueButton, customDelayTimeSeconds);

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
     * RIPPLE ANIMATION
     * -----------------
     * The pulse_ring view expands outward and fades, creating a sonar/ripple effect
     * around the static "Is this intentional?" text.
     *
     * Animation: scale 1.0→1.8 + alpha 0.5→0.0, 2s, INFINITE RESTART.
     * Since alpha ends at 0.0, the instant reset to (scale=1.0, alpha=0.5) at restart
     * is invisible — there is no visible jump between cycles.
     *
     * The animator is stored in breathingAnimator so it can be cancelled in removeOverlay()
     * before the view is detached from WindowManager.
     */
    private void startRippleAnimation(View ring) {
        if (ring == null) {
            Log.w(TAG, "startRippleAnimation: ring view is null, skipping animation");
            return;
        }
        // Cancel any leftover animator from a previous overlay session
        if (breathingAnimator != null) {
            breathingAnimator.cancel();
            breathingAnimator = null;
        }
        // Expand outward (scale) and fade out (alpha) simultaneously
        breathingAnimator = ObjectAnimator.ofPropertyValuesHolder(ring,
                PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.8f),
                PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.8f),
                PropertyValuesHolder.ofFloat("alpha", 0.5f, 0.0f));
        breathingAnimator.setDuration(2000); // 2s per ripple cycle
        breathingAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        breathingAnimator.setRepeatMode(ObjectAnimator.RESTART); // jump-reset is invisible at alpha=0
        breathingAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        breathingAnimator.start();
        Log.d(TAG, "startRippleAnimation: 2s ripple (scale 1.0→1.8, alpha 0.5→0) started");
    }

    /*
     * CONTINUE BUTTON COUNTDOWN
     * --------------------------
     * Counts down from `seconds` and updates the Continue button text each tick:
     *   "Continue (Wait 3s)" → "Continue (Wait 2s)" → "Continue (Wait 1s)" → "Continue" [enabled]
     *
     * When the countdown completes, the button is enabled and its text/color updated to
     * signal it is now tappable. Uses the class-level handler (main looper).
     *
     * The Runnable is stored in countdownRunnable so it can be cancelled in removeOverlay().
     */
    private void startContinueCountdown(Button continueButton, int seconds) {
        Log.d(TAG, "startContinueCountdown: starting " + seconds + "s countdown for " + lastAppPackage);
        // Cancel any previous countdown to prevent orphaned callbacks
        if (countdownRunnable != null) {
            handler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
        }
        countdownRunnable = new Runnable() {
            int remaining = seconds;

            @Override
            public void run() {
                // Safety: stop if overlay was removed while countdown was in flight
                if (overlayView == null) {
                    Log.d(TAG, "startContinueCountdown: overlay removed, stopping countdown");
                    return;
                }

                remaining--;

                if (remaining > 0) {
                    // Still counting — update button text
                    String label = "Continue (Wait " + remaining + "s)";
                    continueButton.setText(label);
                    Log.d(TAG, "startContinueCountdown: " + remaining + "s remaining for " + lastAppPackage);
                    handler.postDelayed(this, 1000);
                } else {
                    // Countdown complete — enable the button
                    continueButton.setText("Continue");
                    continueButton.setEnabled(true);
                    // Fully white text to signal button is active
                    continueButton.setTextColor(android.graphics.Color.WHITE);
                    Log.d(TAG, "startContinueCountdown: complete — Continue button enabled for " + lastAppPackage);
                }
            }
        };
        handler.postDelayed(countdownRunnable, 1000); // first tick after 1 second
    }

    private void removeOverlay() {
        // Cancel countdown to prevent orphaned handler callbacks referencing detached views
        if (countdownRunnable != null) {
            handler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
            Log.d(TAG, "removeOverlay: countdown runnable cancelled");
        }
        // Cancel breathing animation before removing view to avoid animating a detached view
        if (breathingAnimator != null) {
            breathingAnimator.cancel();
            breathingAnimator = null;
            Log.d(TAG, "removeOverlay: breathing animation cancelled");
        }
        if (overlayView != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
        }
        isOverlayActive = false;
        overlayShownAt = 0L; // Reset safety timeout tracker
        // NOTE: We DON'T clear appOpenTimestamps or lastPopupShownTimestamps here
        // because we want to track the next popup timing even after a popup is
        // dismissed. Timestamps are only cleared when user switches away from the app.
        lastAppPackage = "";
        overlayPendingUntil = 0L;
    }

    /**
     * Resolves a package name to a human-readable app label.
     * Delegates to AppNameResolver which maintains an LRU cache.
     */
    public String getAppName(String packageName) {
        return AppNameResolver.resolve(context.getPackageManager(), packageName);
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

        // Cancel any in-flight countdown
        if (countdownRunnable != null) {
            handler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
            Log.d(TAG, "Removed countdown runnable from handler");
        }

        // Clear all app open timestamps and last popup timestamps when monitoring stops
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
        // Clamp to valid range
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

        // Note: We don't clear timestamps when delay changes - let them continue tracking
    }

    // ─── Usage Stats Query Methods ──────────────────────────────────────────

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

    // ─── Scroll Budget Methods ────────────────────────────────────────────────

    /**
     * Load scroll budget configuration from SharedPreferences.
     * Called on startMonitoring() so settings survive app restarts.
     */
    private void loadScrollBudgetFromPrefs() {
        scrollAllowanceMinutes = cachedPrefs.getInt(BreqkPrefs.KEY_SCROLL_ALLOWANCE_MINUTES, BreqkPrefs.DEFAULT_SCROLL_ALLOWANCE_MINUTES);
        scrollWindowMinutes = cachedPrefs.getInt(BreqkPrefs.KEY_SCROLL_WINDOW_MINUTES, BreqkPrefs.DEFAULT_SCROLL_WINDOW_MINUTES);
        // Restore in-progress window state (survives short service interruptions)
        scrollTimeUsedMs = cachedPrefs.getLong(BreqkPrefs.KEY_SCROLL_TIME_USED_MS, 0);
        windowStartTime = cachedPrefs.getLong(BreqkPrefs.KEY_SCROLL_WINDOW_START_TIME, 0);
        budgetExhaustedAt = cachedPrefs.getLong(BreqkPrefs.KEY_SCROLL_BUDGET_EXHAUSTED_AT, 0);
        Log.d(TAG, "[ScrollBudget] Loaded from prefs: allowance=" + scrollAllowanceMinutes +
                "min window=" + scrollWindowMinutes + "min usedMs=" + scrollTimeUsedMs +
                " windowStart=" + windowStartTime + " exhaustedAt=" + budgetExhaustedAt);
    }

    /**
     * Syncs in-memory budget state from SharedPreferences (written by ReelsInterventionService).
     * Called every PREFS_SYNC_INTERVAL_MS (5s) to keep JS bridge APIs accurate without
     * reading prefs on every 1s tick.
     */
    private void syncScrollBudgetFromPrefs() {
        scrollTimeUsedMs = cachedPrefs.getLong(BreqkPrefs.KEY_SCROLL_TIME_USED_MS, 0);
        windowStartTime = cachedPrefs.getLong(BreqkPrefs.KEY_SCROLL_WINDOW_START_TIME, 0);
        budgetExhaustedAt = cachedPrefs.getLong(BreqkPrefs.KEY_SCROLL_BUDGET_EXHAUSTED_AT, 0);
        Log.d(TAG, "[ScrollBudget] Synced from prefs: usedMs=" + scrollTimeUsedMs +
                " windowStart=" + windowStartTime + " exhaustedAt=" + budgetExhaustedAt);
    }

    /**
     * Update scroll budget configuration (in-memory only).
     * VPNModule.setScrollBudget() already persists to SharedPreferences before sending
     * the intent to MyVpnService, so this method does NOT write to prefs to avoid
     * double writes.
     *
     * @param allowanceMin Minutes of scroll allowed per window (>=0; 0 = always block immediately)
     * @param windowMin    Window duration in minutes (>=15)
     */
    public void setScrollBudget(int allowanceMin, int windowMin) {
        this.scrollAllowanceMinutes = Math.max(0, allowanceMin);
        this.scrollWindowMinutes = Math.max(15, windowMin);
        Log.d(TAG, "[ScrollBudget] Budget config updated in-memory: allowance=" + scrollAllowanceMinutes +
                "min window=" + scrollWindowMinutes + "min");
    }

    /**
     * Returns a WritableMap with current scroll budget status.
     * Called by VPNModule.getScrollBudgetStatus() for the JS bridge.
     *
     * Fields: allowanceMinutes, windowMinutes, usedMs, canScroll, nextScrollAtMs, remainingMs
     */
    public com.facebook.react.bridge.WritableMap getScrollBudgetStatus() {
        com.facebook.react.bridge.WritableMap status = com.facebook.react.bridge.Arguments.createMap();
        long allowanceMs = scrollAllowanceMinutes * 60 * 1000L;
        boolean canScroll = !isScrollBudgetExhausted();
        long remainingMs = canScroll ? Math.max(0, allowanceMs - scrollTimeUsedMs) : 0;
        long nextScrollAtMs = isScrollBudgetExhausted() ? (windowStartTime + scrollWindowMinutes * 60 * 1000L) : 0;

        status.putInt("allowanceMinutes", scrollAllowanceMinutes);
        status.putInt("windowMinutes", scrollWindowMinutes);
        status.putDouble("usedMs", scrollTimeUsedMs);
        status.putBoolean("canScroll", canScroll);
        status.putDouble("nextScrollAtMs", nextScrollAtMs);
        status.putDouble("remainingMs", remainingMs);
        return status;
    }

    /** Returns true if the scroll budget has been exhausted for the current window. */
    public boolean isScrollBudgetExhausted() {
        return budgetExhaustedAt > 0;
    }

    /**
     * Checks whether the user is currently viewing Reels/Shorts in the given foreground app.
     *
     * Reads state written by ReelsInterventionService via SharedPreferences:
     *   - is_in_reels (boolean): flag set by the accessibility service
     *   - is_in_reels_timestamp (long): when the flag was last updated
     *   - is_in_reels_package (String): which app is in Reels
     *
     * Returns false (not in Reels) when:
     *   - The flag is false
     *   - The timestamp is stale (older than REELS_STATE_STALENESS_MS = 5s)
     *   - The package doesn't match the current foreground app
     *
     * This gates scroll budget accumulation so that only time spent in Reels/Shorts
     * counts against the budget, not general Instagram/YouTube browsing.
     *
     * @param foregroundApp package name of the current foreground app
     * @return true if user is actively viewing Reels/Shorts in foregroundApp
     */
    private boolean isCurrentlyInReels(String foregroundApp) {
        boolean inReels = cachedPrefs.getBoolean(BreqkPrefs.KEY_IS_IN_REELS, false);
        long timestamp = cachedPrefs.getLong(BreqkPrefs.KEY_IS_IN_REELS_TIMESTAMP, 0);
        String reelsPackage = cachedPrefs.getString(BreqkPrefs.KEY_IS_IN_REELS_PACKAGE, "");

        // Not in Reels at all
        if (!inReels) {
            Log.d(TAG, "[REELS_STATE] isCurrentlyInReels: flag=false → not in Reels");
            return false;
        }

        // Staleness check: if ReelsInterventionService hasn't updated in 5s, assume it crashed
        // or the user left Reels without a clean exit event
        long age = System.currentTimeMillis() - timestamp;
        if (age > REELS_STATE_STALENESS_MS) {
            Log.d(TAG, "[REELS_STATE] isCurrentlyInReels: stale (age=" + age + "ms > " + REELS_STATE_STALENESS_MS + "ms) → not in Reels");
            return false;
        }

        // Package mismatch: Reels state is for a different app than the foreground app
        if (!reelsPackage.equals(foregroundApp)) {
            Log.d(TAG, "[REELS_STATE] isCurrentlyInReels: package mismatch (reels=" + reelsPackage + " fg=" + foregroundApp + ") → not in Reels");
            return false;
        }

        Log.d(TAG, "[REELS_STATE] isCurrentlyInReels: YES (pkg=" + foregroundApp + " age=" + age + "ms)");
        return true;
    }

    /** Returns ms until the next scroll window opens. 0 if not exhausted or window start unknown. */
    public long getTimeUntilNextScroll() {
        if (windowStartTime == 0 || budgetExhaustedAt == 0) return 0;
        long windowMs = scrollWindowMinutes * 60 * 1000L;
        return Math.max(0, (windowStartTime + windowMs) - System.currentTimeMillis());
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
