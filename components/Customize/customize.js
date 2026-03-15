import React, { useState, useEffect } from 'react';
import { View, Text, ScrollView, TouchableOpacity, StyleSheet, Switch, NativeModules } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Path, Circle, Rect } from 'react-native-svg';

const { VPNModule, SettingsModule } = NativeModules;
const tokens = require('../../design/tokens').default;

const { spacing, radii, typography, shadows, colors } = tokens;
const s = colors.stitch; // { navy, mint, seafoam, steel, appBg, appSurface, appBorder, appText, appAccent }

// Reusable SVG Icon Components
const BackIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.5} viewBox="0 0 24 24">
        <Path d="M15 19l-7-7 7-7" strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
);

const DeepWorkIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.2} viewBox="0 0 24 24">
        <Rect height={16} width={16} x={4} y={4} />
        <Path d="M12 4v16M4 12h16M8 8l8 8M16 8l-8 8" strokeLinecap="round" />
    </Svg>
);

const SleepIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.2} viewBox="0 0 24 24">
        <Circle cx={12} cy={12} r={8} />
        <Path d="M12 7c-2.76 0-5 2.24-5 5s2.24 5 5 5" />
    </Svg>
);

const ReadingIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.2} viewBox="0 0 24 24">
        <Path d="M4 6h16M4 10h16M4 14h10M4 18h16" strokeLinecap="round" />
        <Circle cx={18} cy={14} r={2} />
    </Svg>
);

const DetoxIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.2} viewBox="0 0 24 24">
        <Path d="M12 4v16M8 6l8 12M16 6L8 18" strokeLinecap="round" />
        <Circle cx={12} cy={12} r={9} />
    </Svg>
);

const AddIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={2.5} viewBox="0 0 24 24">
        <Path d="M12 5v14M5 12h14" strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
);


const Customize = () => {
    const insets = useSafeAreaInsets();
    // Hidden standard settings that we'll just keep defaults for, to match Stitch Mock visuals precisely
    const [delayTime, setDelayTime] = useState(15);
    const [delayMessage, setDelayMessage] = useState('Take a moment to consider if you really need this app right now');
    const [popupDelayMinutes, setPopupDelayMinutes] = useState(1);

    // Core Focus Mode State
    const [isMonitoringEnabled, setIsMonitoringEnabled] = useState(true);
    const [selectedModeId, setSelectedModeId] = useState('sleep');
    const [redirectInstagramToBrowser, setRedirectInstagramToBrowser] = useState(true);

    // Preset Focus Modes from Stitch
    const focusModes = [
        {
            id: 'deep-work',
            name: 'Deep Work',
            description: 'Blocks social media & news apps for uninterrupted productivity.',
            SvgIcon: DeepWorkIcon,
            blockedApps: ['com.instagram.android', 'com.zhiliaoapp.musically', 'com.twitter.android']
        },
        {
            id: 'sleep',
            name: 'Sleep',
            description: 'Dims screen and silences notifications to help you rest.',
            SvgIcon: SleepIcon,
            blockedApps: ['com.google.android.youtube', 'com.facebook.katana', 'com.snapchat.android', 'com.reddit.frontpage']
        },
        {
            id: 'reading',
            name: 'Reading',
            description: 'Limits distractions to Kindle and Books only.',
            SvgIcon: ReadingIcon,
            blockedApps: ['com.instagram.android', 'com.zhiliaoapp.musically', 'com.google.android.youtube', 'com.twitter.android', 'com.whatsapp']
        },
        {
            id: 'digital-detox',
            name: 'Digital Detox',
            description: 'Hard block on all entertainment apps.',
            SvgIcon: DetoxIcon,
            blockedApps: ['com.instagram.android', 'com.zhiliaoapp.musically', 'com.google.android.youtube', 'com.twitter.android', 'com.spotify.music', 'com.reddit.frontpage', 'com.snapchat.android']
        }
    ];

    useEffect(() => {
        const loadSettings = async () => {
            try {
                const [monitoringEnabled, redirectInstagram] = await Promise.all([
                    new Promise((resolve) => {
                        SettingsModule.getMonitoringEnabled((enabled) => resolve(enabled));
                    }),
                    new Promise((resolve) => {
                        SettingsModule.getRedirectInstagramToBrowser((value) => resolve(value));
                    })
                ]);
                setIsMonitoringEnabled(monitoringEnabled !== false);
                setRedirectInstagramToBrowser(redirectInstagram !== false);
            } catch (error) {
                console.log('loadSettings failed:', error);
            }
        };
        loadSettings();
    }, []);

    const handleModeSelect = async (mode) => {
        setSelectedModeId(mode.id);
        const appsToBlock = mode.blockedApps || [];

        try {
            SettingsModule.saveBlockedApps(appsToBlock);
            await VPNModule.setBlockedApps(appsToBlock);
            if (!isMonitoringEnabled) {
                await toggleMonitoring(true);
            }
        } catch (error) {
            console.error('Failed to apply focus mode:', error);
        }
    };

    const toggleMonitoring = async (forceState = null) => {
        const nextValue = forceState !== null ? forceState : !isMonitoringEnabled;
        try {
            if (nextValue) {
                await VPNModule.startMonitoring();
            } else {
                await VPNModule.stopMonitoring();
            }
            setIsMonitoringEnabled(nextValue);
            SettingsModule.saveMonitoringEnabled(nextValue);
        } catch (error) {
            console.error('Failed to toggle monitoring:', error);
        }
    };

    const toggleRedirectInstagram = (value) => {
        setRedirectInstagramToBrowser(value);
        SettingsModule.saveRedirectInstagramToBrowser(value);
    };

    return (
        <View style={styles.container}>
            <View style={[styles.headerArea, { paddingTop: Math.max(insets.top, 16) + 16 }]}>
                {/* Need backButton styling to be round for symmetry */}
                <TouchableOpacity style={styles.backButton}>
                    <BackIcon color={s.appText} size={24} />
                </TouchableOpacity>
                <Text style={styles.headerTitle}>Focus Modes</Text>
                <View style={styles.headerSpacer} />
            </View>

            <ScrollView style={styles.scrollContainer} contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>

                <View style={styles.titleSection}>
                    <Text style={styles.mainTitle}>Select your flow</Text>
                    <Text style={styles.subTitle}>Choose a preset to block distractions and reclaim your time.</Text>
                </View>

                {/* Instagram Reels Option */}
                <View style={styles.instagramSection}>
                    <Text style={styles.sectionLabel}>Instagram</Text>
                    <View style={styles.instagramRow}>
                        <View style={styles.instagramTexts}>
                            <Text style={styles.instagramLabel}>Open Instagram without Reels</Text>
                            <Text style={styles.instagramSubtitle}>When on, opening Instagram uses the in-app browser with Reels and Explore hidden.</Text>
                        </View>
                        <Switch
                            value={redirectInstagramToBrowser}
                            onValueChange={toggleRedirectInstagram}
                            trackColor={{ false: s.appBg, true: s.appAccent }}
                            thumbColor={redirectInstagramToBrowser ? s.appBg : s.appText}
                            style={styles.switchControl}
                        />
                    </View>
                </View>

                {/* Focus Modes List */}
                <View style={styles.modesContainer}>
                    {focusModes.map((mode) => {
                        const isSelected = selectedModeId === mode.id;
                        return (
                            <TouchableOpacity
                                key={mode.id}
                                style={[styles.modeCard, isSelected && styles.modeCardSelected]}
                                onPress={() => handleModeSelect(mode)}
                                activeOpacity={0.8}
                            >
                                <View style={styles.cardHeader}>
                                    <View style={styles.iconBox}>
                                        <mode.SvgIcon color={s.appText} size={28} />
                                    </View>
                                    <View style={styles.radioWrapper}>
                                        <Switch
                                            value={isSelected}
                                            onValueChange={() => handleModeSelect(mode)}
                                            trackColor={{ false: s.appBg, true: s.appAccent }}
                                            thumbColor={isSelected ? s.appBg : s.appText}
                                            style={styles.switchControl}
                                        />
                                    </View>
                                </View>

                                <View style={styles.cardTexts}>
                                    <Text style={styles.modeName}>{mode.name}</Text>
                                    <Text style={styles.modeDesc}>{mode.description}</Text>
                                </View>

                                {/* Background Glow effect placeholder */}
                                <View style={styles.glowEffect} />
                            </TouchableOpacity>
                        );
                    })}

                    {/* Create Custom Mode Button */}
                    <TouchableOpacity style={styles.createButton} activeOpacity={0.7}>
                        <View style={styles.createIconBox}>
                            <AddIcon color={s.appBg} size={24} />
                        </View>
                        <Text style={styles.createText}>Create Custom Mode</Text>
                    </TouchableOpacity>
                </View>

            </ScrollView>
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: s.appBg,
    },
    headerArea: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: spacing.md,
        paddingBottom: spacing.sm,
        zIndex: 10,
    },
    backButton: {
        width: 40,
        height: 40,
        borderRadius: 20,
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: 'rgba(255,255,255,0.05)',
    },
    headerTitle: {
        fontSize: 18,
        fontWeight: 'bold',
        color: s.appText,
        flex: 1,
        textAlign: 'center',
    },
    headerSpacer: {
        width: 40, // Match backButton width to center title perfectly
    },
    scrollContainer: {
        flex: 1,
    },
    scrollContent: {
        paddingHorizontal: spacing.md,
        paddingBottom: spacing.xxxl,
        paddingTop: spacing.sm,
    },
    titleSection: {
        marginBottom: spacing.xl,
    },
    mainTitle: {
        fontSize: 32,
        fontWeight: 'bold',
        color: s.appText,
        marginBottom: spacing.xs,
        letterSpacing: -0.5,
    },
    subTitle: {
        fontSize: 14,
        color: 'rgba(241, 255, 231, 0.6)', // appText at 60%
        lineHeight: 20,
    },
    instagramSection: {
        marginBottom: spacing.xl,
    },
    sectionLabel: {
        fontSize: 12,
        fontWeight: '600',
        color: 'rgba(241, 255, 231, 0.5)',
        textTransform: 'uppercase',
        letterSpacing: 1,
        marginBottom: spacing.sm,
    },
    instagramRow: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        backgroundColor: s.appSurface,
        borderWidth: 1,
        borderColor: 'rgba(98, 144, 195, 0.3)',
        borderRadius: radii.xl,
        padding: spacing.lg,
    },
    instagramTexts: {
        flex: 1,
        marginRight: spacing.md,
    },
    instagramLabel: {
        fontSize: 16,
        fontWeight: '600',
        color: s.appText,
        marginBottom: 4,
    },
    instagramSubtitle: {
        fontSize: 13,
        color: 'rgba(241, 255, 231, 0.7)',
        lineHeight: 18,
    },
    modesContainer: {
        gap: spacing.md,
        flexDirection: 'column',
    },
    modeCard: {
        position: 'relative',
        overflow: 'hidden',
        backgroundColor: s.appSurface,
        borderWidth: 1,
        borderColor: 'rgba(98, 144, 195, 0.3)', // appBorder at 30%
        borderRadius: radii.xl,
        padding: spacing.lg,
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 10 },
        shadowOpacity: 0.2,
        shadowRadius: 15,
        elevation: 10,
        marginBottom: spacing.md,
    },
    modeCardSelected: {
        borderColor: s.appBorder,
    },
    cardHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'flex-start',
        marginBottom: spacing.md,
        zIndex: 2,
    },
    iconBox: {
        width: 48,
        height: 48,
        borderRadius: radii.md,
        backgroundColor: 'rgba(241, 255, 231, 0.1)', // text/10
        alignItems: 'center',
        justifyContent: 'center',
    },
    radioWrapper: {
        position: 'absolute',
        right: 0,
        top: 0,
    },
    switchControl: {
        transform: [{ scaleX: 0.9 }, { scaleY: 0.9 }],
    },
    cardTexts: {
        zIndex: 2,
    },
    modeName: {
        fontSize: 20,
        fontWeight: 'bold',
        color: s.appText,
        marginBottom: 4,
    },
    modeDesc: {
        fontSize: 14,
        color: 'rgba(241, 255, 231, 0.7)',
        lineHeight: 20,
    },
    glowEffect: {
        position: 'absolute',
        bottom: -40,
        right: -40,
        width: 128,
        height: 128,
        borderRadius: 64,
        backgroundColor: 'rgba(194, 231, 218, 0.05)', // accent/5
        shadowColor: s.appAccent,
        shadowOffset: { width: 0, height: 0 },
        shadowOpacity: 1,
        shadowRadius: 40,
        elevation: 5,
        zIndex: 1,
    },
    createButton: {
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: spacing.xl,
        borderRadius: radii.xl,
        borderWidth: 2,
        borderColor: 'rgba(98, 144, 195, 0.4)', // border/40
        borderStyle: 'dashed',
        backgroundColor: 'transparent',
        marginTop: spacing.sm,
    },
    createIconBox: {
        width: 48,
        height: 48,
        borderRadius: 24,
        backgroundColor: s.appAccent,
        alignItems: 'center',
        justifyContent: 'center',
        marginBottom: spacing.md,
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.3,
        shadowRadius: 6,
        elevation: 5,
    },
    createText: {
        fontSize: 16,
        fontWeight: 'bold',
        color: s.appAccent,
        textAlign: 'center',
    }
});

export default Customize;