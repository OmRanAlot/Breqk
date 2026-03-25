/**
 * customize.js — Customize Screen (Tether light design system)
 * ─────────────────────────────────────────────────────────────────────────────
 * Settings screen layout (stitch screen 2):
 *   • Sticky header: back button + "Customize" title
 *   • "Intervention Modes" section — two toggles
 *   • "Scroll Budget" section — only visible when Reels Detection is on
 *   • "Intercept Message" section — text input, duration slider, preview button
 *   • Version footer
 *
 * State wired to VPNModule (monitoring, delay) and SettingsModule (redirect toggle).
 *
 * Logging prefix: [Customize]
 */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
    View,
    Text,
    StyleSheet,
    Switch,
    TouchableOpacity,
    TextInput,
    ScrollView,
    NativeModules,
    Modal,
    Animated,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Slider from '@react-native-community/slider';
import Svg, { Path } from 'react-native-svg';
import BlockerInterstitial from '../BlockerInterstitial/BlockerInterstitial';

const { VPNModule, SettingsModule } = NativeModules;

// ─── Tether Light Palette ─────────────────────────────────────────────────────
const L = {
    bg: '#FAFAFA',
    charcoal: '#1A1A1A',
    muted: '#737373',
    border: '#E5E5E5',
    sectionLabel: '#1A1A1A',
    inputBorder: '#1A1A1A',  // focused underline
    sliderTrack: '#E5E5E5',
    sliderThumb: '#1A1A1A',
    previewBorder: '#E5E5E5',
};

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Format milliseconds as "M:SS" for the scroll budget countdown display.
 * e.g. 125000 → "2:05"
 */
const formatBudgetTime = (ms) => {
    if (ms == null || ms <= 0) return '0:00';
    const totalSec = Math.floor(ms / 1000);
    const min = Math.floor(totalSec / 60);
    const sec = totalSec % 60;
    return `${min}:${String(sec).padStart(2, '0')}`;
};

// ─── Back arrow icon ──────────────────────────────────────────────────────────
const BackIcon = ({ color, size }) => (
    <Svg width={size} height={size} fill="none" stroke={color} strokeWidth={1.5}
        strokeLinecap="round" strokeLinejoin="round" viewBox="0 0 24 24">
        <Path d="M15 19l-7-7 7-7" />
    </Svg>
);

// ─── Component ────────────────────────────────────────────────────────────────
const Customize = ({ navigation }) => {
    const insets = useSafeAreaInsets();

    // ── State ─────────────────────────────────────────────────────────────────

    // "App Open Intercept" = monitoring enabled
    const [isMonitoringEnabled, setIsMonitoringEnabled] = useState(true);
    // "Reels Detection" = redirect Instagram to safe browser
    const [reelsDetection, setReelsDetection] = useState(true);
    // "20-Min Free Break" — one daily window of unrestricted Reels/Shorts scrolling
    const [freeBreakEnabled, setFreeBreakEnabled] = useState(false);

    // ── Scroll budget state (only used when reelsDetection is on) ─────────────
    // allowance = minutes of scroll allowed per window; window = window duration in minutes
    const [scrollAllowance, setScrollAllowance] = useState(5);
    const [scrollWindow, setScrollWindow] = useState(60);
    // budgetStatus = { canScroll, remainingMs, nextScrollAtMs, usedMs, allowanceMinutes, windowMinutes }
    const [budgetStatus, setBudgetStatus] = useState(null);
    // Intercept message shown on the pause overlay
    const [interceptMessage, setInterceptMessage] = useState('Is this intentional?');
    // Forced pause duration in seconds (1–30)
    const [pauseDuration, setPauseDuration] = useState(5);
    // Live slider value (tracks before commit)
    const [sliderValue, setSliderValue] = useState(5);
    // Preview modal visibility
    const [previewVisible, setPreviewVisible] = useState(false);

    // "Saved" toast — fades in on save, auto-dismisses after 1.8s
    const savedOpacity = useRef(new Animated.Value(0)).current;
    const savedTimer = useRef(null);

    const showSaved = useCallback(() => {
        // Cancel any in-flight dismiss timer so rapid saves don't stack
        if (savedTimer.current) clearTimeout(savedTimer.current);
        Animated.timing(savedOpacity, {
            toValue: 1,
            duration: 150,
            useNativeDriver: true,
        }).start();
        savedTimer.current = setTimeout(() => {
            Animated.timing(savedOpacity, {
                toValue: 0,
                duration: 300,
                useNativeDriver: true,
            }).start();
        }, 1800);
    }, [savedOpacity]);

    // ── Load saved settings ───────────────────────────────────────────────────

    useEffect(() => {
        const load = async () => {
            console.log('[Customize] loading saved settings');
            try {
                const [monitoring, redirect, freeBreak] = await Promise.all([
                    new Promise((resolve) => SettingsModule.getMonitoringEnabled((v) => resolve(v))),
                    new Promise((resolve) => SettingsModule.getRedirectInstagramToBrowser((v) => resolve(v))),
                    new Promise((resolve) => SettingsModule.getFreeBreakEnabled((v) => resolve(v))),
                ]);
                setIsMonitoringEnabled(monitoring !== false);
                setReelsDetection(redirect !== false);
                setFreeBreakEnabled(freeBreak === true);
                console.log('[Customize] settings loaded — monitoring:', monitoring, 'redirect:', redirect, 'freeBreak:', freeBreak);

                // Load saved scroll budget config and apply to native layer
                await new Promise((resolve) => {
                    SettingsModule.getScrollBudget((allowance, window) => {
                        setScrollAllowance(allowance);
                        setScrollWindow(window);
                        VPNModule.setScrollBudget(allowance, window).catch((e) =>
                            console.warn('[Customize] setScrollBudget failed:', e),
                        );
                        resolve();
                    });
                });
                console.log('[Customize] scroll budget loaded');
            } catch (e) {
                console.warn('[Customize] load settings error:', e);
            }
        };
        load();
    }, []);

    // ── Toggle handlers ───────────────────────────────────────────────────────

    const handleMonitoringToggle = async (value) => {
        console.log('[Customize] monitoring toggle →', value);
        try {
            if (value) {
                await VPNModule.startMonitoring();
            } else {
                await VPNModule.stopMonitoring();
            }
            setIsMonitoringEnabled(value);
            SettingsModule.saveMonitoringEnabled(value);
            showSaved();
        } catch (e) {
            console.error('[Customize] monitoring toggle failed:', e);
        }
    };

    const handleReelsToggle = (value) => {
        console.log('[Customize] reels detection toggle →', value);
        setReelsDetection(value);
        SettingsModule.saveRedirectInstagramToBrowser(value);
        showSaved();
    };

    const handleFreeBreakToggle = (value) => {
        console.log('[Customize] free break toggle →', value);
        setFreeBreakEnabled(value);
        SettingsModule.saveFreeBreakEnabled(value);
        showSaved();
    };

    // ── Scroll budget adjustment handlers ─────────────────────────────────────

    /** Step the allowance by `delta` minutes (clamped 0–30; 0 = always block immediately). */
    const adjustAllowance = useCallback(
        (delta) => {
            const next = Math.max(0, Math.min(30, scrollAllowance + delta));
            setScrollAllowance(next);
            VPNModule.setScrollBudget(next, scrollWindow).catch((e) =>
                console.warn('[Customize] setScrollBudget failed:', e),
            );
            SettingsModule.saveScrollBudget(next, scrollWindow);
            console.log('[Customize] scroll allowance changed to', next, 'min');
            showSaved();
        },
        [scrollAllowance, scrollWindow, showSaved],
    );

    /** Step the window duration by `delta` minutes (clamped 15–120). */
    const adjustWindow = useCallback(
        (delta) => {
            const next = Math.max(15, Math.min(120, scrollWindow + delta));
            setScrollWindow(next);
            VPNModule.setScrollBudget(scrollAllowance, next).catch((e) =>
                console.warn('[Customize] setScrollBudget failed:', e),
            );
            SettingsModule.saveScrollBudget(scrollAllowance, next);
            console.log('[Customize] scroll window changed to', next, 'min');
            showSaved();
        },
        [scrollAllowance, scrollWindow, showSaved],
    );

    // ── Scroll budget polling (every 5s when reels detection is on) ───────────
    // Reads from SharedPreferences via VPNModule so the displayed status reflects
    // what MyVpnService's monitor has accumulated (avoids cross-instance reads).
    useEffect(() => {
        if (!reelsDetection) {
            setBudgetStatus(null);
            return;
        }
        const poll = async () => {
            try {
                const status = await VPNModule.getScrollBudgetStatus();
                setBudgetStatus(status);
            } catch (e) {
                console.warn('[Customize] getScrollBudgetStatus failed:', e);
            }
        };
        poll(); // initial fetch
        const interval = setInterval(poll, 5000);
        return () => clearInterval(interval);
    }, [reelsDetection]);

    // ── Duration slider handlers ──────────────────────────────────────────────

    const handleSliderChange = (value) => {
        // Update displayed value while dragging (integer)
        const rounded = Math.round(value);
        setSliderValue(rounded);
    };

    const handleSliderComplete = async (value) => {
        const rounded = Math.round(value);
        setPauseDuration(rounded);
        setSliderValue(rounded);
        console.log('[Customize] pause duration set to', rounded, 'seconds');
        try {
            await VPNModule.setDelayTime(rounded);
            showSaved();
        } catch (e) {
            console.warn('[Customize] setDelayTime error:', e);
        }
    };

    // ── Intercept message save ────────────────────────────────────────────────

    const handleMessageSubmit = async () => {
        console.log('[Customize] saving intercept message:', interceptMessage);
        try {
            await VPNModule.setDelayMessage(interceptMessage);
            showSaved();
        } catch (e) {
            console.warn('[Customize] setDelayMessage error:', e);
        }
    };

    // ── Preview ───────────────────────────────────────────────────────────────

    const handlePreview = () => {
        console.log('[Customize] showing preview interstitial');
        setPreviewVisible(true);
    };

    // ── Render ────────────────────────────────────────────────────────────────

    return (
        <View style={[styles.container, { paddingTop: Math.max(insets.top, 0) }]}>

            {/* ── Sticky header ───────────────────────────────────────────── */}
            <View style={styles.header}>
                <TouchableOpacity
                    style={styles.backButton}
                    activeOpacity={0.7}
                    accessibilityRole="button"
                    accessibilityLabel="Back"
                    onPress={() => {
                        console.log('[Customize] back tapped — navigating back');
                        navigation.goBack();
                    }}
                >
                    <BackIcon color={L.charcoal} size={24} />
                </TouchableOpacity>

                <Text style={styles.headerTitle}>Customize</Text>

                {/* Spacer to centre the title */}
                <View style={styles.headerSpacer} />
            </View>

            {/* ── Scrollable content ──────────────────────────────────────── */}
            <ScrollView
                style={styles.scroll}
                contentContainerStyle={styles.scrollContent}
                showsVerticalScrollIndicator={false}
                keyboardShouldPersistTaps="handled"
            >

                {/* ── Intervention Modes ────────────────────────────────── */}
                <View style={styles.section}>
                    <Text style={styles.sectionLabel}>Intervention Modes</Text>

                    {/* Toggle: App Open Intercept */}
                    <View style={styles.toggleRow}>
                        <Text style={styles.toggleLabel}>App Open Intercept</Text>
                        <Switch
                            value={isMonitoringEnabled}
                            onValueChange={handleMonitoringToggle}
                            trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                            thumbColor="#FFFFFF"
                            ios_backgroundColor="#D6D6D6"
                        />
                    </View>

                    <View style={styles.divider} />

                    {/* Toggle: Reels Detection */}
                    <View style={styles.toggleRow}>
                        <Text style={styles.toggleLabel}>Reels Detection (Android)</Text>
                        <Switch
                            value={reelsDetection}
                            onValueChange={handleReelsToggle}
                            trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                            thumbColor="#FFFFFF"
                            ios_backgroundColor="#D6D6D6"
                        />
                    </View>

                    {/* Toggle: 20-Min Free Break — only shown when Reels Detection is on */}
                    {reelsDetection && (
                        <>
                            <View style={styles.divider} />
                            <View style={styles.toggleRow}>
                                <View style={styles.toggleLabelGroup}>
                                    <Text style={styles.toggleLabel}>20-Min Free Break</Text>
                                    <Text style={styles.toggleCaption}>
                                        Once per day — scroll freely for 20 min with no interruptions or budget counting.
                                    </Text>
                                </View>
                                <Switch
                                    value={freeBreakEnabled}
                                    onValueChange={handleFreeBreakToggle}
                                    trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                                    thumbColor="#FFFFFF"
                                    ios_backgroundColor="#D6D6D6"
                                    accessibilityLabel="20-Minute Free Break toggle"
                                />
                            </View>
                        </>
                    )}
                </View>

                {/* ── Scroll Budget — only shown when Reels Detection is on */}
                {reelsDetection && (
                    <View style={styles.section}>
                        <Text style={styles.sectionLabel}>Scroll Budget</Text>

                        {/* Stepper controls: allow X min every Y min */}
                        <View style={styles.budgetControls}>
                            <View style={styles.stepperGroup}>
                                <TouchableOpacity
                                    style={styles.stepperBtn}
                                    onPress={() => adjustAllowance(-1)}
                                    accessibilityRole="button"
                                    accessibilityLabel="Decrease allowance"
                                >
                                    <Text style={styles.stepperBtnText}>−</Text>
                                </TouchableOpacity>
                                <Text style={styles.stepperValue}>{scrollAllowance}m</Text>
                                <TouchableOpacity
                                    style={styles.stepperBtn}
                                    onPress={() => adjustAllowance(1)}
                                    accessibilityRole="button"
                                    accessibilityLabel="Increase allowance"
                                >
                                    <Text style={styles.stepperBtnText}>+</Text>
                                </TouchableOpacity>
                            </View>

                            <Text style={styles.budgetDivider}>per</Text>

                            <View style={styles.stepperGroup}>
                                <TouchableOpacity
                                    style={styles.stepperBtn}
                                    onPress={() => adjustWindow(-15)}
                                    accessibilityRole="button"
                                    accessibilityLabel="Decrease window"
                                >
                                    <Text style={styles.stepperBtnText}>−</Text>
                                </TouchableOpacity>
                                <Text style={styles.stepperValue}>{scrollWindow}m</Text>
                                <TouchableOpacity
                                    style={styles.stepperBtn}
                                    onPress={() => adjustWindow(15)}
                                    accessibilityRole="button"
                                    accessibilityLabel="Increase window"
                                >
                                    <Text style={styles.stepperBtnText}>+</Text>
                                </TouchableOpacity>
                            </View>
                        </View>

                        {/* Live status row */}
                        {budgetStatus && (() => {
                            const canScroll = budgetStatus.canScroll;
                            const statusColor = canScroll ? '#4CAF50' : '#E53935';
                            const statusLabel = canScroll
                                ? `${formatBudgetTime(budgetStatus.remainingMs)} remaining`
                                : `Scroll again in ${formatBudgetTime(budgetStatus.nextScrollAtMs - Date.now())}`;
                            const filledRatio = canScroll
                                ? Math.min(1, (budgetStatus.usedMs / (scrollAllowance * 60 * 1000)) || 0)
                                : 1;
                            return (
                                <View style={styles.budgetStatusSection}>
                                    <View style={styles.budgetStatusRow}>
                                        <View style={[styles.budgetDot, { backgroundColor: statusColor }]} />
                                        <Text style={[styles.budgetStatusText, { color: statusColor }]}>
                                            {statusLabel}
                                        </Text>
                                    </View>
                                    {/* Progress bar: filled = time used, unfilled = time remaining */}
                                    <View style={styles.budgetProgressBg}>
                                        <View style={{ flex: filledRatio, backgroundColor: statusColor, borderRadius: 2 }} />
                                        <View style={{ flex: Math.max(0, 1 - filledRatio) }} />
                                    </View>
                                </View>
                            );
                        })()}
                    </View>
                )}

                {/* ── Intercept Message ─────────────────────────────────── */}
                <View style={styles.section}>
                    <Text style={styles.sectionLabel}>Intercept Message</Text>

                    {/* Text input with underline border (no box, transparent bg) */}
                    <TextInput
                        style={styles.messageInput}
                        value={interceptMessage}
                        onChangeText={setInterceptMessage}
                        onBlur={handleMessageSubmit}
                        onSubmitEditing={handleMessageSubmit}
                        placeholder="Enter message..."
                        placeholderTextColor={L.muted}
                        returnKeyType="done"
                        accessibilityLabel="Intercept message"
                    />

                    {/* Duration label + live value */}
                    <View style={styles.durationHeader}>
                        <Text style={styles.durationLabel}>Forced Pause Duration</Text>
                        <Text style={styles.durationValue}>{sliderValue} seconds</Text>
                    </View>

                    {/* Slider (1–30 seconds) */}
                    <Slider
                        style={styles.slider}
                        minimumValue={1}
                        maximumValue={30}
                        step={1}
                        value={pauseDuration}
                        minimumTrackTintColor={L.charcoal}
                        maximumTrackTintColor={L.sliderTrack}
                        thumbTintColor={L.sliderThumb}
                        onValueChange={handleSliderChange}
                        onSlidingComplete={handleSliderComplete}
                        accessibilityLabel="Pause duration in seconds"
                    />

                    {/* Range labels */}
                    <View style={styles.sliderLabels}>
                        <Text style={styles.sliderRangeLabel}>1s</Text>
                        <Text style={styles.sliderRangeLabel}>30s</Text>
                    </View>

                    {/* Preview Intercept button */}
                    <TouchableOpacity
                        style={styles.previewButton}
                        activeOpacity={0.85}
                        onPress={handlePreview}
                        accessibilityRole="button"
                        accessibilityLabel="Preview intercept"
                    >
                        <Text style={styles.previewButtonText}>Preview Intercept</Text>
                    </TouchableOpacity>
                </View>

                {/* ── Footer ────────────────────────────────────────────── */}
                <Text style={styles.footer}>v1.0 • Minimal Design</Text>

            </ScrollView>

            {/* ── Saved toast ───────────────────────────────────────────── */}
            <Animated.View
                style={[styles.savedToast, { opacity: savedOpacity }]}
                pointerEvents="none"
                accessibilityLiveRegion="polite"
            >
                <Text style={styles.savedToastText}>✓  Saved</Text>
            </Animated.View>

            {/* ── Preview modal ─────────────────────────────────────────── */}
            <Modal
                visible={previewVisible}
                transparent
                animationType="none"
                onRequestClose={() => setPreviewVisible(false)}
            >
                <BlockerInterstitial
                    duration={pauseDuration}
                    onComplete={() => {
                        console.log('[Customize] preview completed');
                        setPreviewVisible(false);
                    }}
                    onForceClose={() => {
                        console.log('[Customize] preview force-closed');
                        setPreviewVisible(false);
                    }}
                />
            </Modal>

        </View>
    );
};

export default Customize;

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: L.bg,
    },

    // Sticky header
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 20,
        paddingVertical: 14,
        backgroundColor: L.bg,
        // Simulate backdrop blur with a bottom border instead
        borderBottomWidth: 1,
        borderBottomColor: L.border,
        zIndex: 10,
    },
    backButton: {
        width: 36,
        height: 36,
        alignItems: 'center',
        justifyContent: 'center',
    },
    headerTitle: {
        flex: 1,
        textAlign: 'center',
        fontSize: 18,
        fontWeight: '500',
        color: L.charcoal,
        letterSpacing: -0.2,
    },
    // Balances back button so title is visually centred
    headerSpacer: {
        width: 36,
    },

    // Scrollable area
    scroll: {
        flex: 1,
    },
    scrollContent: {
        paddingHorizontal: 24,
        paddingTop: 28,
        paddingBottom: 48,
    },

    // Section block
    section: {
        marginBottom: 36,
    },
    // "INTERVENTION MODES" / "INTERCEPT MESSAGE" labels
    sectionLabel: {
        fontSize: 11,
        fontWeight: '600',
        color: L.charcoal,
        textTransform: 'uppercase',
        letterSpacing: 1.5,
        marginBottom: 16,
    },

    // Toggle row
    toggleRow: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingVertical: 4,
    },
    toggleLabel: {
        fontSize: 16,
        color: L.charcoal,
        fontWeight: '400',
    },
    // Wraps label + caption text to allow the Switch to sit beside multi-line text
    toggleLabelGroup: {
        flex: 1,
        marginRight: 12,
    },
    toggleCaption: {
        fontSize: 12,
        color: L.muted,
        marginTop: 3,
        lineHeight: 17,
    },

    // Thin divider between toggles
    divider: {
        height: 1,
        backgroundColor: L.border,
        marginVertical: 14,
    },

    // Text input with underline only (no box)
    messageInput: {
        fontSize: 18,
        color: L.charcoal,
        borderBottomWidth: 1.5,
        borderBottomColor: L.inputBorder,
        paddingVertical: 8,
        paddingHorizontal: 0,
        backgroundColor: 'transparent',
        marginBottom: 24,
    },

    // Duration header row
    durationHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'baseline',
        marginBottom: 4,
    },
    durationLabel: {
        fontSize: 14,
        color: L.charcoal,
        fontWeight: '500',
    },
    durationValue: {
        fontSize: 14,
        color: L.muted,
        fontWeight: '400',
    },

    // Slider
    slider: {
        width: '100%',
        height: 40,
    },
    sliderLabels: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        marginTop: -4,
        marginBottom: 20,
    },
    sliderRangeLabel: {
        fontSize: 11,
        color: L.muted,
    },

    // Preview button (outlined, light border)
    previewButton: {
        borderWidth: 1,
        borderColor: L.previewBorder,
        borderRadius: 12,
        paddingVertical: 16,
        alignItems: 'center',
    },
    previewButtonText: {
        fontSize: 15,
        color: L.charcoal,
        fontWeight: '500',
    },

    // "Saved" toast bubble — floats at bottom centre, pointer-events: none
    savedToast: {
        position: 'absolute',
        bottom: 80,
        alignSelf: 'center',
        backgroundColor: '#1A1A1A',
        paddingVertical: 10,
        paddingHorizontal: 20,
        borderRadius: 9999,
        // Lift it above the scroll content visually
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.15,
        shadowRadius: 8,
        elevation: 6,
    },
    savedToastText: {
        color: '#FFFFFF',
        fontSize: 14,
        fontWeight: '500',
        letterSpacing: 0.2,
    },

    // ── Scroll Budget ─────────────────────────────────────────────────────────
    budgetControls: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 12,
        marginBottom: 8,
    },
    stepperGroup: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 8,
    },
    stepperBtn: {
        width: 30,
        height: 30,
        borderRadius: 8,
        backgroundColor: 'rgba(0,0,0,0.06)',
        alignItems: 'center',
        justifyContent: 'center',
    },
    stepperBtnText: {
        fontSize: 18,
        color: L.charcoal,
        fontWeight: '400',
        lineHeight: 22,
    },
    stepperValue: {
        fontSize: 16,
        fontWeight: '500',
        color: L.charcoal,
        minWidth: 36,
        textAlign: 'center',
        fontVariant: ['tabular-nums'],
    },
    budgetDivider: {
        fontSize: 12,
        color: L.muted,
        fontWeight: '500',
    },
    budgetStatusSection: {
        gap: 6,
    },
    budgetStatusRow: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 6,
    },
    budgetDot: {
        width: 7,
        height: 7,
        borderRadius: 4,
    },
    budgetStatusText: {
        fontSize: 13,
        fontWeight: '500',
        fontVariant: ['tabular-nums'],
    },
    budgetProgressBg: {
        height: 4,
        flexDirection: 'row',
        backgroundColor: 'rgba(0,0,0,0.07)',
        borderRadius: 2,
        overflow: 'hidden',
    },

    // Footer caption
    footer: {
        textAlign: 'center',
        fontSize: 10,
        color: L.muted,
        letterSpacing: 1.5,
        textTransform: 'uppercase',
        marginTop: 8,
    },
});
