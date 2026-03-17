/**
 * customize.js — Customize Screen (Tether light design system)
 * ─────────────────────────────────────────────────────────────────────────────
 * Settings screen layout (stitch screen 2):
 *   • Sticky header: back button + "Customize" title
 *   • "Intervention Modes" section — two toggles
 *   • "Intercept Message" section — text input, duration slider, preview button
 *   • Version footer
 *
 * State wired to VPNModule (monitoring, delay) and SettingsModule (redirect toggle).
 *
 * Logging prefix: [Customize]
 */

import React, { useState, useEffect } from 'react';
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
    // Intercept message shown on the pause overlay
    const [interceptMessage, setInterceptMessage] = useState('Is this intentional?');
    // Forced pause duration in seconds (1–30)
    const [pauseDuration, setPauseDuration] = useState(5);
    // Live slider value (tracks before commit)
    const [sliderValue, setSliderValue] = useState(5);
    // Preview modal visibility
    const [previewVisible, setPreviewVisible] = useState(false);

    // ── Load saved settings ───────────────────────────────────────────────────

    useEffect(() => {
        const load = async () => {
            console.log('[Customize] loading saved settings');
            try {
                const [monitoring, redirect] = await Promise.all([
                    new Promise((resolve) => SettingsModule.getMonitoringEnabled((v) => resolve(v))),
                    new Promise((resolve) => SettingsModule.getRedirectInstagramToBrowser((v) => resolve(v))),
                ]);
                setIsMonitoringEnabled(monitoring !== false);
                setReelsDetection(redirect !== false);
                console.log('[Customize] settings loaded — monitoring:', monitoring, 'redirect:', redirect);
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
        } catch (e) {
            console.error('[Customize] monitoring toggle failed:', e);
        }
    };

    const handleReelsToggle = (value) => {
        console.log('[Customize] reels detection toggle →', value);
        setReelsDetection(value);
        SettingsModule.saveRedirectInstagramToBrowser(value);
    };

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
        } catch (e) {
            console.warn('[Customize] setDelayTime error:', e);
        }
    };

    // ── Intercept message save ────────────────────────────────────────────────

    const handleMessageSubmit = async () => {
        console.log('[Customize] saving intercept message:', interceptMessage);
        try {
            await VPNModule.setDelayMessage(interceptMessage);
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
                </View>

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
