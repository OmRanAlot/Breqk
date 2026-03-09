import React, { useRef, useState, useEffect, useCallback } from 'react';
import {
    View, Text, ScrollView, Platform, AppState, Alert, NativeModules,
    NativeEventEmitter, TouchableOpacity
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Path, Circle, Rect, Line, Polyline } from 'react-native-svg';
import styles from './homeStyle';

const { VPNModule, SettingsModule } = NativeModules;
const appBlockerEmitter = new NativeEventEmitter(VPNModule);

// Reusable SVG Icon Components
const MenuIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" viewBox="0 0 24 24">
        <Path d="M4 8h16M4 16h10" />
    </Svg>
);

const NotificationIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" viewBox="0 0 24 24">
        <Path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.73 21a2 2 0 0 1-3.46 0" />
    </Svg>
);

const TrendUpIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" viewBox="0 0 24 24">
        <Path d="M23 6l-9.5 9.5-5-5L1 18m22-12h-6m6 0v6" />
    </Svg>
);

const GridIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" viewBox="0 0 24 24">
        <Rect height={7} rx={1} width={7} x={3} y={3} />
        <Rect height={7} rx={1} width={7} x={14} y={3} />
        <Rect height={7} rx={1} width={7} x={14} y={14} />
        <Rect height={7} rx={1} width={7} x={3} y={14} />
    </Svg>
);

const TimerIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" viewBox="0 0 24 24">
        <Circle cx={12} cy={12} r={9} />
        <Path d="M12 7v5l3 2" />
    </Svg>
);

const InsightIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" viewBox="0 0 24 24">
        <Path d="M9.5 2A4.5 4.5 0 0 0 5 6.5C5 8.97 7.03 11 9.5 11h.5c.5 0 .93-.28 1.15-.69A3.001 3.001 0 0 1 15 9V8a3 3 0 0 1 3-3h1" />
        <Circle cx={15} cy={15} r={4} />
        <Path d="M15 19v3" />
    </Svg>
);

const SocialMediaIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" viewBox="0 0 24 24">
        <Circle cx={18} cy={5} r={3} />
        <Circle cx={6} cy={12} r={3} />
        <Circle cx={18} cy={19} r={3} />
        <Line x1={8.59} x2={15.42} y1={13.51} y2={17.49} />
        <Line x1={15.41} x2={8.59} y1={6.51} y2={10.49} />
    </Svg>
);

const EntertainmentIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" viewBox="0 0 24 24">
        <Rect height={12} rx={2} width={20} x={2} y={6} />
        <Path d="M6 12h4m-2-2v4m7-2h.01m2.99 0h.01" />
    </Svg>
);


const Home = () => {
    const [animatedScore, setAnimatedScore] = useState(0);
    // Hard-coded data for display (Matches Stitch Mock)
    const dailyStats = {
        focusScore: 85,
        appsBlocked: 14,
        timeSavedHours: 2,
        timeSavedMinutes: 15,
        peakFocusMins: 45
    };

    const recentBlocks = [
        { id: 1, category: 'Social Media', apps: 'Instagram • TikTok', timeOut: '2m ago', SvgIcon: SocialMediaIcon, color: '#6290C3' },
        { id: 2, category: 'Entertainment', apps: 'Youtube • Netflix', timeOut: '1h ago', SvgIcon: EntertainmentIcon, color: '#C2E7DA' }
    ];

    const CACHE_TTL_MS = 5 * 60 * 1000;
    const cacheRef = useRef({
        installedApps: { data: [], timestamp: 0 },
        screenTimeStats: { data: null, timestamp: 0 },
        blockedUsage: { data: [], timestamp: 0 },
        topApps: { data: [], timestamp: 0 },
    });

    const restartDebounceRef = useRef(null);

    const [hasUsagePermission, setHasUsagePermission] = useState(false);
    const [isLoadingStats, setIsLoadingStats] = useState(false);
    const [screenTime, setScreenTime] = useState(0);
    const [blockedAppsUsage, setBlockedAppsUsage] = useState([]);
    const [topAppsUsage, setTopAppsUsage] = useState([]);
    const [blockedApps, setBlockedApps] = useState(new Set());
    const [appState, setAppState] = useState('active');
    const [installedApps, setInstalledApps] = useState([]);
    const [detectedApps, setDetectedApps] = useState([]);
    const [isMonitoring, setIsMonitoring] = useState(false);

    const insets = useSafeAreaInsets();

    useEffect(() => {
        let start = 0;
        const target = dailyStats.focusScore;
        const duration = 1500; // 1.5 seconds animation
        const increment = target / (duration / 16); // Assuming ~60fps (16ms per frame)

        const timer = setInterval(() => {
            start += increment;
            if (start >= target) {
                setAnimatedScore(target);
                clearInterval(timer);
            } else {
                setAnimatedScore(Math.ceil(start));
            }
        }, 16);

        return () => clearInterval(timer);
    }, [dailyStats.focusScore]);

    const checkUsagePermission = useCallback(async () => {
        try {
            const hasPermission = await VPNModule.isUsageAccessGranted();
            setHasUsagePermission(hasPermission);
            return hasPermission;
        } catch (error) {
            console.error('Error checking usage permission:', error);
            return false;
        }
    }, []);

    const requestUsagePermission = async () => {
        try {
            if (Platform.OS === 'android') {
                await VPNModule.openUsageAccessSettings();
                const hasPermission = await checkUsagePermission();
                if (hasPermission) {
                    loadScreenTimeStats();
                }
                return hasPermission;
            }
            return false;
        } catch (error) {
            console.error('Error requesting usage permission:', error);
            return false;
        }
    };

    const loadScreenTimeStats = useCallback(async () => {
        try {
            const hasPermission = await checkUsagePermission();
            if (!hasPermission) return;

            setIsLoadingStats(true);
            const now = Date.now();
            let stats = null;
            if (cacheRef.current.screenTimeStats.data && (now - cacheRef.current.screenTimeStats.timestamp) < CACHE_TTL_MS) {
                stats = cacheRef.current.screenTimeStats.data;
            } else {
                stats = await VPNModule.getScreenTimeStats();
                cacheRef.current.screenTimeStats = { data: stats, timestamp: now };
            }
            if (stats) setScreenTime(stats.totalScreenTime || 0);

            let blockedUsage = null;
            if (cacheRef.current.blockedUsage.data && (now - cacheRef.current.blockedUsage.timestamp) < CACHE_TTL_MS) {
                blockedUsage = cacheRef.current.blockedUsage.data;
            } else {
                blockedUsage = await VPNModule.getBlockedAppsUsageStats();
                cacheRef.current.blockedUsage = { data: blockedUsage, timestamp: now };
            }
            if (blockedUsage) setBlockedAppsUsage(blockedUsage);

            const endTime = now;
            const startTime = endTime - (24 * 60 * 60 * 1000);
            let topApps = null;
            if (cacheRef.current.topApps.data && (now - cacheRef.current.topApps.timestamp) < CACHE_TTL_MS) {
                topApps = cacheRef.current.topApps.data;
            } else {
                topApps = await VPNModule.getTopAppsByUsage(startTime, endTime, 5);
                cacheRef.current.topApps = { data: topApps, timestamp: now };
            }
            if (topApps) setTopAppsUsage(topApps);
        } catch (error) {
            console.error('Error loading screen time stats:', error);
        } finally {
            setIsLoadingStats(false);
        }
    }, [checkUsagePermission]);

    useEffect(() => {
        const initialize = async () => {
            try {
                const savedBlockedApps = await new Promise((resolve) => {
                    SettingsModule.getBlockedApps((apps) => resolve(apps));
                });

                let blockedSet = new Set(savedBlockedApps || []);
                let hasUpdates = false;
                ['com.instagram.android', 'com.google.android.youtube'].forEach((pkg) => {
                    if (!blockedSet.has(pkg)) {
                        blockedSet.add(pkg);
                        hasUpdates = true;
                    }
                });

                if (hasUpdates) {
                    SettingsModule.saveBlockedApps(Array.from(blockedSet));
                }
                setBlockedApps(blockedSet);

                await Promise.all([
                    loadInstalledApps(),
                    loadScreenTimeStats()
                ]);

                startMonitoringWithApps(blockedSet);
            } catch (error) {
                console.error('Failed to initialize:', error);
            }
        };

        initialize();

        const detectionListener = appBlockerEmitter.addListener('onAppDetected', (event) => {
            if (event && event.packageName) addDetectedApp(event);
        });

        const blockedListener = appBlockerEmitter.addListener('onBlockedAppOpened', (event) => { });

        const appStateListener = AppState.addEventListener('change', (nextAppState) => {
            setAppState(prevState => {
                if (prevState.match(/inactive|background/) && nextAppState === 'active' && isMonitoring) {
                    debouncedRestartMonitoring();
                }
                return nextAppState;
            });
        });

        return () => {
            detectionListener.remove();
            blockedListener.remove();
            appStateListener?.remove();
            if (restartDebounceRef.current) clearTimeout(restartDebounceRef.current);
        };
    }, [isMonitoring, loadScreenTimeStats]);

    const loadInstalledApps = async () => {
        try {
            const now = Date.now();
            if (cacheRef.current.installedApps.data.length > 0 && (now - cacheRef.current.installedApps.timestamp) < CACHE_TTL_MS) {
                setInstalledApps(cacheRef.current.installedApps.data);
                return;
            }
            const apps = await VPNModule.getInstalledApps();
            cacheRef.current.installedApps = { data: apps || [], timestamp: now };
            setInstalledApps(apps || []);
        } catch (e) {
            console.warn('loadInstalledApps failed', e);
        }
    };

    const addDetectedApp = (appInfo) => {
        setDetectedApps(prev => [appInfo, ...prev.slice(0, 9)]);
    };

    const restartMonitoring = async () => {
        try {
            await VPNModule.stopMonitoring();
            setTimeout(async () => {
                await VPNModule.startMonitoring();
            }, 800);
        } catch (e) {
            console.warn('restartMonitoring failed', e);
        }
    };

    const debouncedRestartMonitoring = useCallback(() => {
        if (restartDebounceRef.current) clearTimeout(restartDebounceRef.current);
        restartDebounceRef.current = setTimeout(() => restartMonitoring(), 1000);
    }, [restartMonitoring]);

    const startMonitoringWithApps = async (apps) => {
        try {
            if (apps && apps.size > 0) {
                await VPNModule.setBlockedApps(Array.from(apps));
                await VPNModule.startMonitoring();
                setIsMonitoring(true);
            }
        } catch (error) {
            console.error('Failed to start monitoring:', error);
        }
    };

    const renderInnerHeader = () => (
        <View style={[styles.innerHeader, { paddingTop: Math.max(insets.top, 16) + 16 }]}>
            <TouchableOpacity style={styles.iconButton}>
                <MenuIcon size={24} color="#C2E7DA" />
            </TouchableOpacity>

            <View style={styles.statusChip}>
                <View style={styles.pulseDot} />
                <Text style={styles.statusText}>FOCUS ACTIVE</Text>
            </View>

            <TouchableOpacity style={styles.iconButton}>
                <NotificationIcon size={24} color="#C2E7DA" />
                <View style={styles.notificationDot} />
            </TouchableOpacity>
        </View>
    );

    return (
        <View style={styles.container}>
            <ScrollView style={styles.scrollContainer} contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
                {renderInnerHeader()}

                {/* Large Focus Score Dial */}
                <View style={styles.scoreContainer}>
                    <View style={styles.circleBackgroundGlow} />
                    <View style={styles.scoreCircle}>
                        <View style={styles.innerScoreContent}>
                            <Text style={styles.scoreLabel}>FOCUS SCORE</Text>
                            <Text style={styles.scoreValue}>{animatedScore}</Text>
                            <View style={styles.trendChip}>
                                <TrendUpIcon size={12} color="#C2E7DA" />
                                <Text style={styles.trendText}>+12%</Text>
                            </View>
                        </View>
                    </View>
                </View>

                {/* Glass Panels Grid */}
                <View style={styles.glassGrid}>
                    <View style={styles.glassCard}>
                        <View style={styles.glassCardIconBoxSteel}>
                            <GridIcon size={24} color="#6290C3" />
                        </View>
                        <Text style={styles.glassCardLabel}>APPS BLOCKED</Text>
                        <View style={styles.glassCardValues}>
                            <Text style={styles.glassCardValueBig}>{dailyStats.appsBlocked}</Text>
                            <Text style={styles.glassCardValueSmall}>TODAY</Text>
                        </View>
                    </View>

                    <View style={styles.glassCard}>
                        <View style={styles.glassCardIconBoxSeafoam}>
                            <TimerIcon size={24} color="#C2E7DA" />
                        </View>
                        <Text style={styles.glassCardLabel}>TIME SAVED</Text>
                        <View style={styles.glassCardValues}>
                            <Text style={styles.glassCardValueBig}>{dailyStats.timeSavedHours}h</Text>
                            <Text style={[styles.glassCardValueSmall, { fontSize: 20, marginTop: 4, marginLeft: 2, color: 'rgba(241, 255, 231, 0.4)' }]}>{dailyStats.timeSavedMinutes}m</Text>
                        </View>
                    </View>
                </View>

                {/* Daily Insight Gradient Panel */}
                <View style={styles.insightSection}>
                    <View style={styles.insightHeader}>
                        <Text style={styles.insightTitle}>DAILY INSIGHT</Text>
                        <Text style={styles.insightReportLink}>REPORT</Text>
                    </View>

                    <View style={styles.insightCard}>
                        <View style={styles.insightIconBox}>
                            <InsightIcon size={24} color="#F1FFE7" />
                        </View>
                        <View style={styles.insightTexts}>
                            <Text style={styles.insightCardTitle}>Deep Work Peak</Text>
                            <Text style={styles.insightCardDesc}>
                                You maintained deep focus for <Text style={styles.insightHighlight}>{dailyStats.peakFocusMins} mins</Text> straight during your morning session.
                            </Text>
                        </View>
                    </View>
                </View>

                {/* Recent Blocks List */}
                <View style={styles.blocksSection}>
                    <Text style={styles.blocksTitle}>RECENT BLOCKS</Text>
                    {recentBlocks.map(block => (
                        <View key={block.id} style={styles.blockRow}>
                            <View style={styles.blockRowLeft}>
                                <View style={styles.blockIconBox}>
                                    <block.SvgIcon size={24} color={block.color} />
                                </View>
                                <View>
                                    <Text style={styles.blockCategory}>{block.category}</Text>
                                    <Text style={styles.blockApps}>{block.apps}</Text>
                                </View>
                            </View>
                            <View style={styles.blockTimeChip}>
                                <Text style={styles.blockTimeText}>{block.timeOut}</Text>
                            </View>
                        </View>
                    ))}
                </View>

            </ScrollView>
        </View>
    );
};

export default Home;
