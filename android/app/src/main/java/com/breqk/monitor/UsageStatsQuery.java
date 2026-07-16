package com.Break.monitor;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UsageStatsQuery
 * ---------------
 * Stateless wrapper around Android's {@link UsageStatsManager} queries, extracted
 * from AppUsageMonitor — which had grown past the 1500-line hard limit enforced by
 * tests/static/test_060_file_size_limit.py.
 *
 * These methods are pure reads: they depend on nothing in AppUsageMonitor except a
 * UsageStatsManager handle, the permission flag, and a package-name → display-name
 * resolver, all of which are passed in. AppUsageMonitor keeps thin delegating
 * methods, so the JS bridge (VPNModule) is unaffected.
 *
 * Every method fails soft — returning 0 or an empty list rather than throwing. A
 * missing usage-stats grant or an OEM quirk must never take down the 1-second
 * monitor loop that calls into this.
 *
 * Logging tag: UsageStatsQuery
 */
public final class UsageStatsQuery {
    private static final String TAG = "UsageStatsQuery";

    /** Resolves a package name to a human-readable app label. */
    public interface AppNameLookup {
        String getAppName(String packageName);
    }

    /** Total foreground time (ms) for one package within [startTime, endTime]. */
    public static long getAppUsageTime(UsageStatsManager usageStatsManager, boolean hasPermission,
            String packageName, long startTime, long endTime) {
        try {
            if (!hasPermission || usageStatsManager == null) {
                return 0;
            }
            List<UsageStats> stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, startTime, endTime);

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

    /** Total foreground time (ms) across all packages within [startTime, endTime]. */
    public static long getTotalScreenTime(UsageStatsManager usageStatsManager, boolean hasPermission,
            long startTime, long endTime) {
        try {
            if (!hasPermission || usageStatsManager == null) {
                return 0;
            }
            List<UsageStats> stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, startTime, endTime);

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

    /**
     * Apps with non-zero usage within [startTime, endTime], sorted by foreground
     * time descending and capped at {@code limit} (limit <= 0 means "no cap").
     *
     * UsageStatsManager can return several UsageStats rows for the same package in
     * one window, so times are summed per package before sorting.
     */
    public static List<AppUsageMonitor.AppUsageInfo> getTopAppsByUsage(
            UsageStatsManager usageStatsManager, boolean hasPermission,
            AppNameLookup nameLookup, long startTime, long endTime, int limit) {
        List<AppUsageMonitor.AppUsageInfo> appUsageList = new ArrayList<>();

        try {
            if (!hasPermission || usageStatsManager == null) {
                return appUsageList;
            }
            List<UsageStats> stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, startTime, endTime);

            // Sum per package — one package can appear in several rows.
            Map<String, Long> appUsageMap = new HashMap<>();
            for (UsageStats stat : stats) {
                String packageName = stat.getPackageName();
                long usageTime = stat.getTotalTimeInForeground();
                Long existing = appUsageMap.get(packageName);
                appUsageMap.put(packageName, existing == null ? usageTime : existing + usageTime);
            }

            for (Map.Entry<String, Long> entry : appUsageMap.entrySet()) {
                long usageTime = entry.getValue();
                if (usageTime > 0) { // Only include apps with actual usage
                    String appName = nameLookup.getAppName(entry.getKey());
                    appUsageList.add(new AppUsageMonitor.AppUsageInfo(
                            entry.getKey(), appName, usageTime));
                }
            }

            appUsageList.sort((a, b) -> Long.compare(b.usageTime, a.usageTime));
            if (limit > 0 && appUsageList.size() > limit) {
                appUsageList = appUsageList.subList(0, limit);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting top apps by usage", e);
        }

        return appUsageList;
    }

    // Prevent instantiation
    private UsageStatsQuery() {
    }
}
