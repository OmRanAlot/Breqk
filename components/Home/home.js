/**
 * home.js — Home Screen (Tether light design system)
 * ─────────────────────────────────────────────────────────────────────────────
 * Dashboard layout:
 *   • App name header (centred) + settings gear icon
 *   • Summary stat cards: total screen time, unlocks, notifications
 *   • Top 5 apps by usage with proportional progress bars
 *   • "Open Instagram (Safe Mode)" primary action button
 *
 * Data strategy:
 *   Real usage data is loaded via useDigitalWellbeing hook (5-min TTL cache).
 *   Data refreshes automatically when the app returns to the foreground.
 *   Unavailable metrics (API version or OEM restriction) are hidden, not shown as 0.
 *
 * Monitoring lifecycle is separate from data display; startMonitoring / event
 * listeners are preserved verbatim to avoid regression.
 *
 * Logging prefix: [Home]
 */

import React, { useRef, useState, useEffect, useCallback } from 'react';
import {
    View,
    Text,
    Platform,
    AppState,
    NativeModules,
    NativeEventEmitter,
    TouchableOpacity,
    ScrollView,
    ActivityIndicator,
    StyleSheet,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Path, Circle } from 'react-native-svg';
import useDigitalWellbeing from './useDigitalWellbeing';

const { VPNModule, SettingsModule } = NativeModules;
const appBlockerEmitter = new NativeEventEmitter(VPNModule);

// ─── Tether Light Palette ─────────────────────────────────────────────────────
const L = {
    bg: '#F8F8F6',
    charcoal: '#1A1A1A',
    muted: '#757575',
    captionOpacity: 'rgba(26,26,26,0.6)',
    ctaBg: '#1A1A1A',
    ctaText: '#FFFFFF',
    // Accent colours for stats
    accentGreen: '#4CAF50',
    accentBlue: '#2196F3',
    accentOrange: '#FF9800',
    cardBg: '#FFFFFF',
    cardBorder: 'rgba(0,0,0,0.07)',
    barBg: 'rgba(0,0,0,0.07)',
    barFill: '#1A1A1A',
};

// ─── Settings icon ────────────────────────────────────────────────────────────
const SettingsIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.5}
        strokeLinecap="round" strokeLinejoin="round" viewBox="0 0 24 24">
        <Circle cx={12} cy={12} r={3} />
        <Path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </Svg>
);

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Format minutes as "Xh Ym", "Xh", or "Ym" */
const formatTime = (minutes) => {
    if (minutes == null) return '—';
    const m = Math.round(minutes);
    if (m >= 60) {
        const h = Math.floor(m / 60);
        const rem = m % 60;
        return rem > 0 ? `${h}h ${rem}m` : `${h}h`;
    }
    return `${m}m`;
};

/** Format a nullable integer stat; returns '—' for null/undefined */
const formatCount = (value) => (value == null ? '—' : String(value));

// ─── Sub-components ───────────────────────────────────────────────────────────

/** Single stat card shown in the summary row */
const StatCard = ({ label, value, loading }) => (
    <View style={styles.statCard}>
        {loading
            ? <View style={styles.skeletonValue} />
            : <Text style={styles.statValue}>{value}</Text>
        }
        <Text style={styles.statLabel}>{label}</Text>
    </View>
);

/** One row in the top-apps list */
const AppUsageRow = ({ appName, usageTimeMin, maxTimeMin }) => {
    // Progress bar fill ratio relative to the top app in the list
    const ratio = maxTimeMin > 0 ? usageTimeMin / maxTimeMin : 0;

    return (
        <View style={styles.appUsageRow}>
            <View style={styles.appUsageInfo}>
                <Text style={styles.appUsageName} numberOfLines={1}>{appName}</Text>
                <Text style={styles.appUsageTime}>{formatTime(usageTimeMin)}</Text>
            </View>
            <View style={styles.usageBar}>
                <View style={[styles.usageBarFill, { width: `${Math.round(ratio * 100)}%` }]} />
            </View>
        </View>
    );
};

// ─── Main Component ───────────────────────────────────────────────────────────

// ─── Scroll budget helper ────────────────────────────────────────────────────

/** Format milliseconds as "M:SS" for the scroll budget countdown display. */
const formatBudgetTime = (ms) => {
    if (ms == null || ms <= 0) return '0:00';
    const totalSec = Math.floor(ms / 1000);
    const min = Math.floor(totalSec / 60);
    const sec = totalSec % 60;
    return `${min}:${String(sec).padStart(2, '0')}`;
};

const Home = ({ navigation }) => {
    const insets = useSafeAreaInsets();
    const [isMonitoring, setIsMonitoring] = useState(false);

    // ── Digital Wellbeing data ────────────────────────────────────────────────
    const { stats, topApps, loading, error, refresh } = useDigitalWellbeing();

    // ── Scroll budget status (polled every 5s) ───────────────────────────────
    // Reads from SharedPreferences via VPNModule so the displayed status reflects
    // what MyVpnService's monitor has accumulated.
    const [budgetStatus, setBudgetStatus] = useState(null);
    const appStateRefBudget = useRef(AppState.currentState);

    useEffect(() => {
        const pollBudget = async () => {
            try {
                const status = await VPNModule.getScrollBudgetStatus();
                setBudgetStatus(status);
                console.log('[Home] scroll budget polled: canScroll=' + status.canScroll +
                    ' remainingMs=' + status.remainingMs + ' usedMs=' + status.usedMs);
            } catch (e) {
                console.warn('[Home] getScrollBudgetStatus failed:', e);
            }
        };
        pollBudget(); // initial fetch
        const interval = setInterval(pollBudget, 5000);

        // Also refresh on foreground resume for immediate accuracy
        const sub = AppState.addEventListener('change', (nextState) => {
            if (appStateRefBudget.current.match(/inactive|background/) && nextState === 'active') {
                pollBudget();
            }
            appStateRefBudget.current = nextState;
        });

        return () => {
            clearInterval(interval);
            sub?.remove();
        };
    }, []);

    // ── Monitoring lifecycle refs ─────────────────────────────────────────────
    const appStateRef = useRef(AppState.currentState);
    const restartDebounceRef = useRef(null);

    // ── Monitoring helpers ────────────────────────────────────────────────────
    // NOTE: These are preserved verbatim from the original implementation.

    const startMonitoring = async (apps) => {
        try {
            await VPNModule.setBlockedApps(Array.from(apps));
            await VPNModule.startMonitoring();
            setIsMonitoring(true);
            console.log('[Home] monitoring started with', apps.size, 'blocked apps');
        } catch (e) {
            console.error('[Home] startMonitoring failed:', e);
        }
    };

    const restartMonitoring = useCallback(async () => {
        try {
            // Check monitoring_enabled before restarting — respects "App Open Intercept" toggle.
            // Without this guard, returning to foreground would re-enable monitoring even if off.
            const monitoringEnabled = await new Promise((resolve) => {
                SettingsModule.getMonitoringEnabled((v) => resolve(v));
            });
            if (monitoringEnabled === false) {
                console.log('[Home] restartMonitoring skipped — monitoring disabled by user');
                return;
            }
            await VPNModule.stopMonitoring();
            setTimeout(async () => {
                await VPNModule.startMonitoring();
                console.log('[Home] monitoring restarted');
            }, 800);
        } catch (e) {
            console.warn('[Home] restartMonitoring failed:', e);
        }
    }, []);

    const debouncedRestart = useCallback(() => {
        if (restartDebounceRef.current) clearTimeout(restartDebounceRef.current);
        restartDebounceRef.current = setTimeout(restartMonitoring, 1000);
    }, [restartMonitoring]);

    // ── Initialise (monitoring + defaults) ────────────────────────────────────

    useEffect(() => {
        const init = async () => {
            console.log('[Home] initialising');
            try {
                // Load saved blocked apps (add defaults if empty)
                const savedApps = await new Promise((resolve) => {
                    SettingsModule.getBlockedApps((apps) => resolve(apps));
                });
                let appsSet = new Set(savedApps || []);
                let updated = false;
                ['com.instagram.android', 'com.google.android.youtube'].forEach((pkg) => {
                    if (!appsSet.has(pkg)) { appsSet.add(pkg); updated = true; }
                });
                if (updated) SettingsModule.saveBlockedApps(Array.from(appsSet));

                // Check if monitoring is enabled before starting — respects the
                // "App Open Intercept" toggle in Customize. Without this check,
                // monitoring would restart on every Home mount even if toggled off.
                const monitoringEnabled = await new Promise((resolve) => {
                    SettingsModule.getMonitoringEnabled((v) => resolve(v));
                });
                console.log('[Home] monitoring_enabled preference:', monitoringEnabled);

                if (monitoringEnabled !== false) {
                    await startMonitoring(appsSet);
                } else {
                    console.log('[Home] monitoring disabled by user — skipping start');
                    setIsMonitoring(false);
                }

                // Sync widget if available
                if (Platform.OS === 'android' && SettingsModule.updateWidgetStats) {
                    SettingsModule.updateWidgetStats(85, 45, 14, true);
                }
            } catch (e) {
                console.error('[Home] init failed:', e);
            }
        };
        init();

        // Event listeners
        const detectionSub = appBlockerEmitter.addListener('onAppDetected', (event) => {
            console.log('[Home] app detected:', event?.packageName);
        });

        const blockedSub = appBlockerEmitter.addListener('onBlockedAppOpened', (event) => {
            console.log('[Home] blocked app opened:', event?.packageName);
            // App.tsx handles navigation to Browser; this is a secondary log hook.
        });

        const stateSub = AppState.addEventListener('change', (nextState) => {
            if (appStateRef.current.match(/inactive|background/) && nextState === 'active' && isMonitoring) {
                debouncedRestart();
            }
            appStateRef.current = nextState;
        });

        return () => {
            detectionSub.remove();
            blockedSub.remove();
            stateSub?.remove();
            if (restartDebounceRef.current) clearTimeout(restartDebounceRef.current);
        };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [isMonitoring, debouncedRestart]);

    // ── Derived display values ────────────────────────────────────────────────

    const maxTopAppTime = topApps.length > 0 ? topApps[0].usageTimeMin : 0;

    // ── Render ────────────────────────────────────────────────────────────────

    return (
        <View style={[styles.container, { paddingTop: Math.max(insets.top, 16) }]}>

            {/* ── Header: app name (centred) + settings icon ─────────────── */}
            <View style={styles.header}>
                <View style={styles.headerSpacer} />

                <Text style={styles.appName}>BREQK</Text>

                <TouchableOpacity
                    style={styles.settingsButton}
                    activeOpacity={0.7}
                    accessibilityRole="button"
                    accessibilityLabel="Settings"
                    onPress={() => {
                        console.log('[Home] settings tapped — navigating to Customize');
                        navigation.navigate('Customize');
                    }}
                >
                    <SettingsIcon color={L.muted} size={22} />
                </TouchableOpacity>
            </View>

            {/* ── Main scrollable content ─────────────────────────────────── */}
            <ScrollView
                style={styles.scrollView}
                contentContainerStyle={styles.scrollContent}
                showsVerticalScrollIndicator={false}
            >

                {/* ── Summary stat cards ────────────────────────────────── */}
                <View style={styles.statsRow}>
                    <StatCard
                        label="Screen Time"
                        value={formatTime(stats.totalScreenTimeMin)}
                        loading={loading}
                    />
                    {/* Only show unlock card if value is available (API 28+) */}
                    {(!loading && stats.unlockCount !== null) || loading ? (
                        <StatCard
                            label="Unlocks"
                            value={formatCount(stats.unlockCount)}
                            loading={loading}
                        />
                    ) : null}
                    {/* Only show notification card if value is available */}
                    {(!loading && stats.notificationCount !== null) || loading ? (
                        <StatCard
                            label="Notifications"
                            value={formatCount(stats.notificationCount)}
                            loading={loading}
                        />
                    ) : null}
                </View>

                {/* ── Scroll Budget card ─────────────────────────────── */}
                {/* Shows time remaining in the current scroll budget window.
                    Green = budget available, Red = exhausted (with countdown to reset). */}
                {budgetStatus && (() => {
                    const canScroll = budgetStatus.canScroll;
                    const statusColor = canScroll ? L.accentGreen : '#E53935';
                    const allowanceMs = budgetStatus.allowanceMinutes * 60 * 1000;
                    const statusLabel = canScroll
                        ? `${formatBudgetTime(budgetStatus.remainingMs)} remaining`
                        : `Resets in ${formatBudgetTime(budgetStatus.nextScrollAtMs - Date.now())}`;
                    const filledRatio = canScroll
                        ? Math.min(1, (budgetStatus.usedMs / allowanceMs) || 0)
                        : 1;

                    return (
                        <View style={styles.budgetCard}>
                            <View style={styles.budgetHeader}>
                                <Text style={styles.sectionTitle}>Scroll Budget</Text>
                                <View style={[styles.budgetDot, { backgroundColor: statusColor }]} />
                            </View>
                            <Text style={[styles.budgetStatusLabel, { color: statusColor }]}>
                                {statusLabel}
                            </Text>
                            {/* Progress bar: filled = time used, unfilled = time remaining */}
                            <View style={styles.budgetProgressBg}>
                                <View style={{
                                    flex: filledRatio,
                                    backgroundColor: statusColor,
                                    borderRadius: 2,
                                }} />
                                <View style={{ flex: Math.max(0, 1 - filledRatio) }} />
                            </View>
                            <Text style={styles.budgetCaption}>
                                {budgetStatus.allowanceMinutes}m allowed per {budgetStatus.windowMinutes}m window
                            </Text>
                        </View>
                    );
                })()}

                {/* ── Error state ───────────────────────────────────────── */}
                {error && error !== 'usage_permission_missing' && (
                    <TouchableOpacity style={styles.errorRow} onPress={refresh}>
                        <Text style={styles.errorText}>Could not load stats. Tap to retry.</Text>
                    </TouchableOpacity>
                )}

                {/* ── Top Apps section ──────────────────────────────────── */}
                <View style={styles.topAppsSection}>
                    {/* Section header: title left, total time right */}
                    <View style={styles.topAppsSectionHeader}>
                        <Text style={styles.sectionTitle}>Today's Top Apps</Text>
                        {stats.totalScreenTimeMin != null && (
                            <Text style={styles.topAppsTotalTime}>
                                Total: {formatTime(stats.totalScreenTimeMin)}
                            </Text>
                        )}
                    </View>

                    {loading ? (
                        // Loading skeleton rows
                        [0, 1, 2].map((i) => (
                            <View key={i} style={styles.appUsageRow}>
                                <View style={styles.appUsageInfo}>
                                    <View style={[styles.skeletonText, { width: '55%' }]} />
                                    <View style={[styles.skeletonText, { width: '20%' }]} />
                                </View>
                                <View style={styles.usageBar}>
                                    <View style={[styles.usageBarFill, { width: `${60 - i * 15}%` }]} />
                                </View>
                            </View>
                        ))
                    ) : topApps.length === 0 ? (
                        <Text style={styles.emptyText}>No usage data available yet</Text>
                    ) : (
                        topApps.map((app) => (
                            <AppUsageRow
                                key={app.packageName}
                                appName={app.appName}
                                usageTimeMin={app.usageTimeMin}
                                maxTimeMin={maxTopAppTime}
                            />
                        ))
                    )}
                </View>

            </ScrollView>

            {/* ── Footer: action button + caption ────────────────────────── */}
            <View style={styles.footer}>
                <TouchableOpacity
                    style={styles.primaryButton}
                    activeOpacity={0.85}
                    onPress={() => {
                        console.log('[Home] Open Instagram (Safe Mode) tapped');
                        navigation.navigate('Browser', { platform: 'instagram' });
                    }}
                    accessibilityRole="button"
                    accessibilityLabel="Open Instagram in Safe Mode"
                >
                    <Text style={styles.primaryButtonText}>Open Instagram (Safe Mode)</Text>
                </TouchableOpacity>

                <Text style={styles.caption}>Reels are disabled</Text>
            </View>

        </View>
    );
};

export default Home;

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: L.bg,
        paddingBottom: 32,
    },

    // ── Header ────────────────────────────────────────────────────────────────
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingTop: 8,
        paddingHorizontal: 28,
    },
    headerSpacer: {
        width: 36,
    },
    appName: {
        fontSize: 13,
        fontWeight: '500',
        color: L.charcoal,
        letterSpacing: 1.5,
        textAlign: 'center',
        flex: 1,
    },
    settingsButton: {
        width: 36,
        height: 36,
        alignItems: 'center',
        justifyContent: 'center',
    },

    // ── Scroll ────────────────────────────────────────────────────────────────
    scrollView: {
        flex: 1,
    },
    scrollContent: {
        paddingHorizontal: 28,
        paddingTop: 20,
        paddingBottom: 12,
        gap: 20,
    },

    // ── Summary stat cards ────────────────────────────────────────────────────
    statsRow: {
        flexDirection: 'row',
        gap: 10,
    },
    statCard: {
        flex: 1,
        backgroundColor: L.cardBg,
        borderRadius: 14,
        borderWidth: 1,
        borderColor: L.cardBorder,
        paddingVertical: 16,
        paddingHorizontal: 10,
        alignItems: 'center',
        gap: 4,
    },
    statValue: {
        fontSize: 22,
        fontWeight: '300',
        color: L.charcoal,
        letterSpacing: -0.5,
    },
    statLabel: {
        fontSize: 10,
        fontWeight: '600',
        color: L.muted,
        textTransform: 'uppercase',
        letterSpacing: 0.8,
        textAlign: 'center',
    },

    // ── Skeleton placeholders ─────────────────────────────────────────────────
    skeletonValue: {
        height: 24,
        width: '60%',
        backgroundColor: 'rgba(0,0,0,0.08)',
        borderRadius: 6,
    },
    skeletonText: {
        height: 12,
        backgroundColor: 'rgba(0,0,0,0.07)',
        borderRadius: 4,
    },

    // ── Error ─────────────────────────────────────────────────────────────────
    errorRow: {
        paddingVertical: 10,
        alignItems: 'center',
    },
    errorText: {
        fontSize: 13,
        color: '#C62828',
    },

    // ── Top apps section ──────────────────────────────────────────────────────
    topAppsSection: {
        gap: 10,
    },
    topAppsSectionHeader: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
    },
    topAppsTotalTime: {
        fontSize: 12,
        fontWeight: '500',
        color: L.charcoal,
        fontVariant: ['tabular-nums'],
    },
    sectionTitle: {
        fontSize: 11,
        fontWeight: '600',
        color: L.muted,
        textTransform: 'uppercase',
        letterSpacing: 1.2,
    },
    appUsageRow: {
        gap: 6,
    },
    appUsageInfo: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'baseline',
    },
    appUsageName: {
        fontSize: 14,
        fontWeight: '400',
        color: L.charcoal,
        flex: 1,
        marginRight: 8,
    },
    appUsageTime: {
        fontSize: 13,
        color: L.muted,
        fontVariant: ['tabular-nums'],
    },
    usageBar: {
        height: 4,
        backgroundColor: L.barBg,
        borderRadius: 2,
        overflow: 'hidden',
    },
    usageBarFill: {
        height: '100%',
        backgroundColor: L.barFill,
        borderRadius: 2,
    },
    emptyText: {
        fontSize: 14,
        color: L.muted,
        textAlign: 'center',
        paddingVertical: 16,
    },

    // ── Scroll Budget card ──────────────────────────────────────────────────
    budgetCard: {
        backgroundColor: L.cardBg,
        borderRadius: 14,
        borderWidth: 1,
        borderColor: L.cardBorder,
        padding: 16,
        gap: 8,
    },
    budgetHeader: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
    },
    budgetDot: {
        width: 8,
        height: 8,
        borderRadius: 4,
    },
    budgetStatusLabel: {
        fontSize: 16,
        fontWeight: '500',
        fontVariant: ['tabular-nums'],
    },
    budgetProgressBg: {
        height: 4,
        flexDirection: 'row',
        backgroundColor: L.barBg,
        borderRadius: 2,
        overflow: 'hidden',
    },
    budgetCaption: {
        fontSize: 11,
        color: L.muted,
        fontVariant: ['tabular-nums'],
    },

    // ── Footer ────────────────────────────────────────────────────────────────
    footer: {
        gap: 10,
        alignItems: 'center',
        paddingHorizontal: 28,
    },
    primaryButton: {
        backgroundColor: L.ctaBg,
        borderRadius: 9999,
        paddingVertical: 18,
        paddingHorizontal: 32,
        width: '100%',
        alignItems: 'center',
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 2 },
        shadowOpacity: 0.1,
        shadowRadius: 4,
        elevation: 2,
    },
    primaryButtonText: {
        color: L.ctaText,
        fontSize: 17,
        fontWeight: '500',
    },
    caption: {
        fontSize: 10,
        fontWeight: '600',
        color: L.captionOpacity,
        textTransform: 'uppercase',
        letterSpacing: 2,
    },
});
