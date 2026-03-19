package com.breqk;

/*
 * ScreenTimeTracker
 * ------------------
 * Aggregates device-wide foreground time and usage events using UsageStatsManager.
 *
 * Public API:
 *  - getScreenTimeStats()        — total 24h screen time (minutes) + time range
 *  - getComprehensiveStats()     — total screen time + unlock count + notification count in one pass
 *  - getPerAppStats(start, end, limit) — per-app usage sorted by time descending
 *  - getUnlockCount(start, end)  — device unlock events (API 28+ only; returns -1 below)
 *  - getNotificationCount(start, end) — notification-seen events (API 25+; returns -1 if unavailable)
 *
 * Implementation details:
 *  - Uses INTERVAL_DAILY and sums foreground time for user-facing apps only
 *    (excludes system services and home launchers — see isUserFacingApp()).
 *  - Converts milliseconds to minutes for user-friendly display.
 *  - Requires API 22 (LOLLIPOP_MR1) for queryUsageStats behaviour.
 *  - getComprehensiveStats() does a single event pass for unlock + notification counts
 *    to minimise battery/CPU impact.
 *  - OEM note: NOTIFICATION_SEEN events are sparse on some manufacturers (Samsung, Xiaomi).
 *    Treat notification count as a best-effort metric; hide in UI when -1 is returned.
 *
 * Logging tag: ScreenTimeTracker
 * Prefixes:
 *   [SCREEN_TIME]    — getScreenTimeStats
 *   [COMPREHENSIVE]  — getComprehensiveStats
 *   [PER_APP]        — getPerAppStats
 *   [UNLOCK_COUNT]   — getUnlockCount
 *   [NOTIF_COUNT]    — getNotificationCount
 */

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ScreenTimeTracker {
    private static final String TAG = "ScreenTimeTracker";

    // ── Debug toggle ──────────────────────────────────────────────────────────
    // Set to false in production builds if verbose event-loop logs are too noisy.
    private static final boolean VERBOSE_LOGGING = true;

    private final Context context;

    public ScreenTimeTracker(Context context) {
        this.context = context;
        Log.d(TAG, "[INIT] ScreenTimeTracker created");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getScreenTimeStats — original API, preserved for backward compatibility
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns total 24-hour foreground time (in minutes) plus the queried time range.
     * Requires API 22+. Safe to call on all supported API levels.
     *
     * @return Map with keys: "totalScreenTime" (minutes), "startTime" (ms), "endTime" (ms)
     */
    public Map<String, Long> getScreenTimeStats() {
        Log.d(TAG, "[SCREEN_TIME] getScreenTimeStats called");
        Map<String, Long> result = new HashMap<>();
        try {
            UsageStatsManager usageStatsManager =
                    (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);

            Calendar calendar = Calendar.getInstance();
            long endTime = calendar.getTimeInMillis();
            calendar.add(Calendar.DAY_OF_YEAR, -1);
            long startTime = calendar.getTimeInMillis();

            List<UsageStats> stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, startTime, endTime);

            PackageManager pm = context.getPackageManager();
            long totalScreenTime = 0;
            int skippedSystem = 0;
            for (UsageStats usageStats : stats) {
                String pkg = usageStats.getPackageName();
                if (!isUserFacingApp(pm, pkg)) {
                    // Skip home launchers and background system services
                    skippedSystem++;
                    if (VERBOSE_LOGGING) {
                        Log.v(TAG, "[SCREEN_TIME] Skipping system/launcher: " + pkg);
                    }
                    continue;
                }
                totalScreenTime += usageStats.getTotalTimeInForeground();
            }

            // Convert ms → minutes
            totalScreenTime = TimeUnit.MILLISECONDS.toMinutes(totalScreenTime);

            result.put("totalScreenTime", totalScreenTime);
            result.put("startTime", startTime);
            result.put("endTime", endTime);

            Log.d(TAG, "[SCREEN_TIME] totalScreenTime=" + totalScreenTime + "min, range=" +
                    startTime + "-" + endTime + ", skippedSystem=" + skippedSystem);
        } catch (Exception e) {
            Log.e(TAG, "[SCREEN_TIME] Error: " + e.getMessage(), e);
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getComprehensiveStats — single-pass aggregation for all today's metrics
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Retrieves total screen time, unlock count, and notification count for today
     * in a single UsageEvents pass to minimise battery/CPU impact.
     *
     * Keys in returned map:
     *   "totalScreenTimeMin" (double) — total foreground time in minutes (today)
     *   "unlockCount"        (int)    — KEYGUARD_HIDDEN events; -1 if API < 28
     *   "notificationCount"  (int)    — NOTIFICATION_SEEN events; -1 if none found or API < 25
     *   "startTime"          (long)   — start of today (midnight) in ms
     *   "endTime"            (long)   — now in ms
     *
     * @return Map with comprehensive stats. Never null; may contain -1 sentinel values.
     */
    public Map<String, Object> getComprehensiveStats() {
        Log.d(TAG, "[COMPREHENSIVE] getComprehensiveStats called");
        Map<String, Object> result = new HashMap<>();

        try {
            UsageStatsManager usageStatsManager =
                    (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);

            // ── Time range: start of today (midnight) → now ──────────────────
            Calendar calendar = Calendar.getInstance();
            long endTime = calendar.getTimeInMillis();
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            long startTime = calendar.getTimeInMillis();

            Log.d(TAG, "[COMPREHENSIVE] Querying range: " + startTime + " → " + endTime);

            // ── Step 1: Total screen time via UsageEvents (accurate) ────────
            // Uses MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND events for exact per-app time.
            // The previous INTERVAL_DAILY approach inflated totals by including time from
            // overlapping daily buckets (yesterday + today summed together).
            PackageManager pm = context.getPackageManager();
            Map<String, Long> perAppMs = getPerAppForegroundTimeFromEvents(startTime, endTime);
            long totalMs = 0;
            int skippedSystem = 0;
            for (Map.Entry<String, Long> entry : perAppMs.entrySet()) {
                if (!isUserFacingApp(pm, entry.getKey())) {
                    skippedSystem++;
                    if (VERBOSE_LOGGING) {
                        Log.v(TAG, "[COMPREHENSIVE] Skipping system/launcher: " + entry.getKey());
                    }
                    continue;
                }
                totalMs += entry.getValue();
            }
            double totalScreenTimeMin = (double) totalMs / 60_000.0;
            Log.d(TAG, "[COMPREHENSIVE] totalScreenTimeMin=" + totalScreenTimeMin +
                    " (from events, " + perAppMs.size() + " apps total, skipped " +
                    skippedSystem + " system/launcher)");

            // ── Step 2: Unlock + notification counts via UsageEvents ──────────
            int unlockCount = -1;
            int notificationCount = -1;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                // queryEvents available API 21+
                UsageEvents events = usageStatsManager.queryEvents(startTime, endTime);
                UsageEvents.Event event = new UsageEvents.Event();

                int processedEvents = 0;
                int localUnlockCount = 0;
                int localNotifCount = 0;

                // UsageEvents.Event integer constants (avoid compile-time class refs for old APIs)
                // KEYGUARD_HIDDEN    = 18  (API 28+) — keyguard dismissed; most accurate unlock signal
                // SCREEN_INTERACTIVE = 15  (API 23+) — screen turned on; fallback unlock proxy
                // NOTIFICATION_INTERRUPTION = 12 (API 26+) — notification caused sound/vibration
                final int KEYGUARD_HIDDEN = 18;
                final int SCREEN_INTERACTIVE = 15;
                final int NOTIFICATION_INTERRUPTION = 12;

                // Unlock strategy: prefer KEYGUARD_HIDDEN (exact), fall back to SCREEN_INTERACTIVE
                boolean useKeyguard = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;   // API 28
                boolean useScreenOn = !useKeyguard && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M; // API 23

                // Notification interruptions available API 26+
                boolean canCountNotifs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O; // API 26

                Log.d(TAG, "[COMPREHENSIVE] unlock strategy: " +
                        (useKeyguard ? "KEYGUARD_HIDDEN" : useScreenOn ? "SCREEN_INTERACTIVE" : "none") +
                        ", notifs: " + (canCountNotifs ? "NOTIFICATION_INTERRUPTION" : "unavailable"));

                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    processedEvents++;

                    int eventType = event.getEventType();

                    if (useKeyguard && eventType == KEYGUARD_HIDDEN) {
                        localUnlockCount++;
                        if (VERBOSE_LOGGING) {
                            Log.v(TAG, "[UNLOCK_COUNT] KEYGUARD_HIDDEN at t=" + event.getTimeStamp());
                        }
                    } else if (useScreenOn && eventType == SCREEN_INTERACTIVE) {
                        localUnlockCount++;
                        if (VERBOSE_LOGGING) {
                            Log.v(TAG, "[UNLOCK_COUNT] SCREEN_INTERACTIVE at t=" + event.getTimeStamp());
                        }
                    }

                    if (canCountNotifs && eventType == NOTIFICATION_INTERRUPTION) {
                        localNotifCount++;
                        if (VERBOSE_LOGGING) {
                            Log.v(TAG, "[NOTIF_COUNT] NOTIFICATION_INTERRUPTION pkg=" +
                                    event.getPackageName() + " t=" + event.getTimeStamp());
                        }
                    }
                }

                if (useKeyguard || useScreenOn) {
                    unlockCount = localUnlockCount;
                } else {
                    Log.d(TAG, "[UNLOCK_COUNT] API " + Build.VERSION.SDK_INT +
                            " < 23 — no unlock event available, returning -1");
                }

                if (canCountNotifs) {
                    notificationCount = localNotifCount;
                } else {
                    Log.d(TAG, "[NOTIF_COUNT] API " + Build.VERSION.SDK_INT +
                            " < 26 — NOTIFICATION_INTERRUPTION unavailable, returning -1");
                }

                Log.d(TAG, "[COMPREHENSIVE] Processed " + processedEvents + " total events. " +
                        "unlocks=" + unlockCount + " notifications=" + notificationCount);
            } else {
                Log.d(TAG, "[COMPREHENSIVE] API < 22 — event query unavailable");
            }

            result.put("totalScreenTimeMin", totalScreenTimeMin);
            result.put("unlockCount", unlockCount);
            result.put("notificationCount", notificationCount);
            result.put("startTime", startTime);
            result.put("endTime", endTime);

        } catch (Exception e) {
            Log.e(TAG, "[COMPREHENSIVE] Error: " + e.getMessage(), e);
            // Return safe defaults so JS side doesn't crash
            result.put("totalScreenTimeMin", 0.0);
            result.put("unlockCount", -1);
            result.put("notificationCount", -1);
            result.put("startTime", 0L);
            result.put("endTime", System.currentTimeMillis());
        }

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getPerAppStats — per-app usage for today, sorted by time descending
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns per-app usage stats for the given time range, sorted by usage time descending.
     * Filters out system/launcher apps and apps with zero usage.
     *
     * Each entry in the returned list is a Map with:
     *   "packageName"  (String) — e.g. "com.instagram.android"
     *   "appName"      (String) — human-readable label from PackageManager
     *   "usageTimeMs"  (long)   — milliseconds of foreground time
     *   "usageTimeMin" (double) — minutes of foreground time (convenience)
     *
     * @param startTime  Query start in epoch ms
     * @param endTime    Query end in epoch ms
     * @param limit      Maximum number of apps to return; 0 = unlimited
     * @return           Sorted list of per-app stats. Never null.
     */
    public List<Map<String, Object>> getPerAppStats(long startTime, long endTime, int limit) {
        Log.d(TAG, "[PER_APP] getPerAppStats called, limit=" + limit +
                ", range=" + startTime + "→" + endTime);
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            // Use UsageEvents (MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND) for accurate per-app time.
            // The previous INTERVAL_DAILY approach returned daily buckets whose
            // getTotalTimeInForeground() covers the ENTIRE bucket period, not just the
            // portion overlapping the query range. This caused inflated times when
            // yesterday's bucket was included (e.g., 2h30m instead of 40m for Instagram).
            Map<String, Long> usageMap = getPerAppForegroundTimeFromEvents(startTime, endTime);

            Log.d(TAG, "[PER_APP] Events-based: " + usageMap.size() + " apps with usage > 0");

            PackageManager pm = context.getPackageManager();

            // Build result list
            for (Map.Entry<String, Long> entry : usageMap.entrySet()) {
                String pkg = entry.getKey();
                long usageMs = entry.getValue();

                // Only include user-facing apps: must have a launch intent AND must not be
                // a home launcher. This excludes system services (SystemUI, Dreams/screensaver,
                // input methods) and the home screen itself. See isUserFacingApp() for details.
                if (!isUserFacingApp(pm, pkg)) {
                    if (VERBOSE_LOGGING) {
                        Log.v(TAG, "[PER_APP] Skipping system/launcher package: " + pkg);
                    }
                    continue;
                }

                String appName = getAppName(pm, pkg);
                double usageTimeMin = (double) usageMs / 60_000.0;

                Map<String, Object> appEntry = new HashMap<>();
                appEntry.put("packageName", pkg);
                appEntry.put("appName", appName);
                appEntry.put("usageTimeMs", usageMs);
                appEntry.put("usageTimeMin", usageTimeMin);
                results.add(appEntry);
            }

            // Sort descending by usage time
            Collections.sort(results, new Comparator<Map<String, Object>>() {
                @Override
                public int compare(Map<String, Object> a, Map<String, Object> b) {
                    long timeA = (long) a.get("usageTimeMs");
                    long timeB = (long) b.get("usageTimeMs");
                    return Long.compare(timeB, timeA);
                }
            });

            // Apply limit
            if (limit > 0 && results.size() > limit) {
                results = results.subList(0, limit);
            }

            Log.d(TAG, "[PER_APP] Returning " + results.size() + " apps");
            if (VERBOSE_LOGGING) {
                for (Map<String, Object> app : results) {
                    Log.v(TAG, "[PER_APP] " + app.get("appName") +
                            " (" + app.get("packageName") + "): " +
                            String.format("%.1f", (double) app.get("usageTimeMin")) + "min");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "[PER_APP] Error: " + e.getMessage(), e);
        }

        return results;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getUnlockCount — standalone convenience method
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the number of device unlocks (KEYGUARD_HIDDEN events) in the given range.
     * Requires API 28+. Returns -1 on lower API levels.
     *
     * @param startTime Query start in epoch ms
     * @param endTime   Query end in epoch ms
     * @return          Unlock count, or -1 if API < 28 or an error occurred
     */
    public int getUnlockCount(long startTime, long endTime) {
        Log.d(TAG, "[UNLOCK_COUNT] getUnlockCount called, API=" + Build.VERSION.SDK_INT);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.d(TAG, "[UNLOCK_COUNT] API " + Build.VERSION.SDK_INT +
                    " < 28 (P) — KEYGUARD_HIDDEN unavailable, returning -1");
            return -1;
        }

        try {
            UsageStatsManager usageStatsManager =
                    (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents events = usageStatsManager.queryEvents(startTime, endTime);
            UsageEvents.Event event = new UsageEvents.Event();

            // KEYGUARD_HIDDEN constant value = 18
            final int KEYGUARD_HIDDEN = 18;
            int count = 0;

            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == KEYGUARD_HIDDEN) {
                    count++;
                }
            }

            Log.d(TAG, "[UNLOCK_COUNT] Found " + count + " unlock events");
            return count;
        } catch (Exception e) {
            Log.e(TAG, "[UNLOCK_COUNT] Error: " + e.getMessage(), e);
            return -1;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNotificationCount — standalone convenience method
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the number of notifications the user has seen (NOTIFICATION_SEEN events) in
     * the given range. Requires API 25+. Returns -1 if no events are found or API < 25.
     *
     * NOTE: This counts notifications the user actually *saw* (interacted with or dismissed),
     * not all posted notifications. Many OEMs restrict this event, so -1 is common.
     *
     * @param startTime Query start in epoch ms
     * @param endTime   Query end in epoch ms
     * @return          Notification count, or -1 if unavailable
     */
    public int getNotificationCount(long startTime, long endTime) {
        Log.d(TAG, "[NOTIF_COUNT] getNotificationCount called, API=" + Build.VERSION.SDK_INT);

        if (Build.VERSION.SDK_INT < 25) {
            Log.d(TAG, "[NOTIF_COUNT] API < 25 — NOTIFICATION_SEEN unavailable, returning -1");
            return -1;
        }

        try {
            UsageStatsManager usageStatsManager =
                    (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents events = usageStatsManager.queryEvents(startTime, endTime);
            UsageEvents.Event event = new UsageEvents.Event();

            // NOTIFICATION_SEEN constant value = 12
            final int NOTIFICATION_SEEN = 12;
            int count = 0;

            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == NOTIFICATION_SEEN) {
                    count++;
                }
            }

            if (count == 0) {
                Log.d(TAG, "[NOTIF_COUNT] No NOTIFICATION_SEEN events found (OEM restriction likely)");
                return -1;
            }

            Log.d(TAG, "[NOTIF_COUNT] Found " + count + " notification events");
            return count;
        } catch (Exception e) {
            Log.e(TAG, "[NOTIF_COUNT] Error: " + e.getMessage(), e);
            return -1;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Events-based per-app foreground time (accurate, no bucket overlap issues)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Computes per-app foreground time by processing MOVE_TO_FOREGROUND (type 1) and
     * MOVE_TO_BACKGROUND (type 2) events from UsageEvents. This is more accurate than
     * queryUsageStats(INTERVAL_DAILY) because:
     *   1. Events have exact timestamps — no daily-bucket overlap / double-counting
     *   2. Only time within [startTime, endTime] is counted
     *   3. This matches what Android's Digital Wellbeing app uses internally
     *
     * Edge cases handled:
     *   - App still in foreground at endTime: session capped at endTime
     *   - App in foreground before startTime: session starts at startTime (via first BG event)
     *   - Multiple foreground events without intervening background: last FG wins (reset)
     *
     * @param startTime Query start in epoch ms (inclusive)
     * @param endTime   Query end in epoch ms (exclusive)
     * @return Map of packageName → total foreground milliseconds. Never null.
     */
    private Map<String, Long> getPerAppForegroundTimeFromEvents(long startTime, long endTime) {
        Log.d(TAG, "[EVENTS] getPerAppForegroundTimeFromEvents range=" + startTime + "→" + endTime);
        Map<String, Long> foregroundTimes = new HashMap<>();

        try {
            UsageStatsManager usm =
                    (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents events = usm.queryEvents(startTime, endTime);
            UsageEvents.Event event = new UsageEvents.Event();

            // Track when each app most recently moved to foreground
            // Key = packageName, Value = timestamp of MOVE_TO_FOREGROUND
            Map<String, Long> fgStartTimes = new HashMap<>();

            // UsageEvents.Event type constants:
            //   MOVE_TO_FOREGROUND = 1 (activity resumed / became visible)
            //   MOVE_TO_BACKGROUND = 2 (activity paused / went invisible)
            final int MOVE_TO_FG = 1;
            final int MOVE_TO_BG = 2;

            int processedEvents = 0;

            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();

                if (type != MOVE_TO_FG && type != MOVE_TO_BG) continue;

                processedEvents++;
                String pkg = event.getPackageName();
                long ts = event.getTimeStamp();

                if (type == MOVE_TO_FG) {
                    // App came to foreground — record session start
                    fgStartTimes.put(pkg, ts);
                } else { // MOVE_TO_BG
                    // App went to background — compute session duration
                    Long sessionStart = fgStartTimes.remove(pkg);
                    if (sessionStart != null) {
                        long duration = ts - sessionStart;
                        if (duration > 0) {
                            Long existing = foregroundTimes.get(pkg);
                            foregroundTimes.put(pkg, (existing != null ? existing : 0L) + duration);
                        }
                    } else {
                        // BG event without a preceding FG event in our range:
                        // App was in foreground before startTime. Count from startTime.
                        long duration = ts - startTime;
                        if (duration > 0) {
                            Long existing = foregroundTimes.get(pkg);
                            foregroundTimes.put(pkg, (existing != null ? existing : 0L) + duration);
                        }
                    }
                }
            }

            // Handle apps still in foreground at endTime (no MOVE_TO_BG yet)
            for (Map.Entry<String, Long> entry : fgStartTimes.entrySet()) {
                String pkg = entry.getKey();
                long sessionStart = entry.getValue();
                long duration = endTime - sessionStart;
                if (duration > 0) {
                    Long existing = foregroundTimes.get(pkg);
                    foregroundTimes.put(pkg, (existing != null ? existing : 0L) + duration);
                }
            }

            Log.d(TAG, "[EVENTS] Processed " + processedEvents + " FG/BG events, " +
                    foregroundTimes.size() + " apps with usage > 0");

        } catch (Exception e) {
            Log.e(TAG, "[EVENTS] Error computing foreground time from events: " + e.getMessage(), e);
        }

        return foregroundTimes;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns true if the package is a user-facing app that should count toward screen time.
     *
     * A package is considered user-facing if:
     *   1. It has a launch intent — it can be opened from the app drawer. This excludes
     *      background system services (SystemUI, input methods, Dreams/screensaver, etc.)
     *      that accumulate foreground time invisibly.
     *   2. It is NOT a home launcher — excludes time spent on the home screen, which
     *      Android counts as foreground time for the default launcher package.
     *
     * This is the single source of truth used by getComprehensiveStats(), getScreenTimeStats(),
     * and getPerAppStats() to ensure all three are consistent.
     *
     * @param pm          PackageManager instance (caller supplies to avoid repeated lookups)
     * @param packageName Package to evaluate
     * @return            true = count this package's time; false = skip it
     */
    private boolean isUserFacingApp(PackageManager pm, String packageName) {
        // Check 1: must have a launch intent (filters system services)
        if (pm.getLaunchIntentForPackage(packageName) == null) {
            return false;
        }

        // Check 2: must NOT be a home launcher (filters home screen time).
        // We resolve ACTION_MAIN + CATEGORY_HOME to find all current launcher packages
        // and exclude any match. This handles OEM launchers, third-party launchers, etc.
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> launchers = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo info : launchers) {
            if (info.activityInfo != null && packageName.equals(info.activityInfo.packageName)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Resolves a package name to a human-readable app label.
     * Falls back to the package name itself if the label cannot be resolved.
     */
    private String getAppName(PackageManager pm, String packageName) {
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(appInfo).toString();
        } catch (PackageManager.NameNotFoundException e) {
            // App may have been uninstalled; return raw package name as fallback
            return packageName;
        }
    }
}
