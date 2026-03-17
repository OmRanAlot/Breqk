/**
 * home.js — Home Screen (Tether light design system)
 * ─────────────────────────────────────────────────────────────────────────────
 * Minimal dashboard layout:
 *   • App name header + settings icon
 *   • Large "N Save Events Today" stats display
 *   • "Open Instagram (Safe Mode)" primary action button
 *
 * Data strategy: shows hardcoded demo stats on first render,
 * loads real VPN stats async in background (5-min TTL cache).
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
    StyleSheet,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Path, Circle } from 'react-native-svg';

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
};

// ─── Settings icon ────────────────────────────────────────────────────────────
const SettingsIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.5}
        strokeLinecap="round" strokeLinejoin="round" viewBox="0 0 24 24">
        <Circle cx={12} cy={12} r={3} />
        <Path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </Svg>
);

// ─── Stats cache (5-minute TTL) ───────────────────────────────────────────────
const CACHE_TTL_MS = 5 * 60 * 1000;

// ─── Component ────────────────────────────────────────────────────────────────
const Home = ({ navigation }) => {
    const insets = useSafeAreaInsets();

    // Stats: defaults are demo values shown while real data loads
    const [saveEvents, setSaveEvents] = useState(14);
    const [timeSavedMin, setTimeSavedMin] = useState(45);
    const [isMonitoring, setIsMonitoring] = useState(false);

    const cacheRef = useRef({
        screenTimeStats: { data: null, timestamp: 0 },
    });
    const appStateRef = useRef(AppState.currentState);
    const restartDebounceRef = useRef(null);

    // ── Load real stats ───────────────────────────────────────────────────────

    const loadStats = useCallback(async () => {
        try {
            const hasPermission = await VPNModule.isUsageAccessGranted();
            if (!hasPermission) {
                console.log('[Home] no usage permission — showing demo stats');
                return;
            }

            const now = Date.now();
            const cache = cacheRef.current.screenTimeStats;
            let stats = null;

            if (cache.data && (now - cache.timestamp) < CACHE_TTL_MS) {
                stats = cache.data;
                console.log('[Home] using cached screen time stats');
            } else {
                stats = await VPNModule.getScreenTimeStats();
                cacheRef.current.screenTimeStats = { data: stats, timestamp: now };
                console.log('[Home] loaded fresh screen time stats:', JSON.stringify(stats));
            }

            if (stats) {
                // Map native stats to UI fields
                if (typeof stats.blockedCount === 'number') setSaveEvents(stats.blockedCount);
                if (typeof stats.totalTimeSavedMin === 'number') setTimeSavedMin(stats.totalTimeSavedMin);
            }
        } catch (e) {
            console.warn('[Home] loadStats error:', e);
        }
    }, []);

    // ── Monitoring helpers ────────────────────────────────────────────────────

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

    // ── Initialise ────────────────────────────────────────────────────────────

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

                // Load stats and start monitoring
                await loadStats();
                await startMonitoring(appsSet);

                // Sync widget if available (use placeholder stats until real data loads)
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
    }, [isMonitoring, loadStats, debouncedRestart]);

    // ── Render helpers ────────────────────────────────────────────────────────

    /** Format minutes as "Xh Ym" or just "Ym" */
    const formatTime = (minutes) => {
        if (minutes >= 60) {
            const h = Math.floor(minutes / 60);
            const m = minutes % 60;
            return m > 0 ? `${h}h ${m}m` : `${h}h`;
        }
        return `${minutes}m`;
    };

    // ── Render ────────────────────────────────────────────────────────────────

    return (
        <View style={[styles.container, { paddingTop: Math.max(insets.top, 16) }]}>

            {/* ── Header: app name (centered) + settings icon ─────────────── */}
            <View style={styles.header}>
                {/* Left spacer to balance settings icon */}
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

            {/* ── Main stats section ──────────────────────────────────────── */}
            <View style={styles.statsSection}>
                <Text style={styles.saveEventsText}>
                    {saveEvents} Save Events Today
                </Text>
                <Text style={styles.timeSavedText}>
                    Total time saved: {formatTime(timeSavedMin)}
                </Text>
            </View>

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
        paddingHorizontal: 28,
        paddingBottom: 32,
        justifyContent: 'space-between',
    },

    // Header row
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingTop: 8,
    },
    // Spacer matches settings button width so app name stays centered
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

    // Stats section (vertically centered in remaining space)
    statsSection: {
        flex: 1,
        justifyContent: 'center',
        alignItems: 'center',
    },
    // Large stat headline — fontWeight 300 (light) to match stitch screen
    saveEventsText: {
        fontSize: 36,
        fontWeight: '300',
        color: L.charcoal,
        textAlign: 'center',
        lineHeight: 44,
        letterSpacing: -0.5,
    },
    timeSavedText: {
        marginTop: 8,
        fontSize: 14,
        color: L.muted,
        textAlign: 'center',
    },

    // Footer
    footer: {
        gap: 10,
        alignItems: 'center',
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
