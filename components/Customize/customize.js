/**
 * customize.js — Customize Screen (Tether light design system)
 * ─────────────────────────────────────────────────────────────────────────────
 * Settings screen layout:
 *   • Sticky header: back button + "Customize" title
 *   • "Your Apps" section — per-app toggles (App Open Intercept, Reels Detection)
 *   • "20-Min Free Break" toggle (when Reels Detection is on)
 *   • "Scroll Budget" section — only visible when Reels Detection is on
 *   • "Intercept Message" section — text input, duration slider, preview button
 *   • Version footer
 *
 * Note: Modes management has moved to the dedicated Modes screen.
 *
 * State wired to VPNModule (monitoring, delay) and SettingsModule (redirect).
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
  Platform,
  AppState,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Slider from '@react-native-community/slider';
import Svg, { Path } from 'react-native-svg';
import BlockerInterstitial from '../BlockerInterstitial/BlockerInterstitial';
import useDebouncedSaver from './useDebouncedSaver';

// Debounce window for Customize writes. Rapid toggles coalesce into a single
// commit after this quiet period; any navigate-away / background / unmount
// forces an immediate flush so no writes are ever dropped.
const SAVE_DEBOUNCE_MS = 7000;

const { VPNModule, SettingsModule } = NativeModules;

// ─── Tether Light Palette ─────────────────────────────────────────────────────
const L = {
  bg: '#FAFAFA',
  charcoal: '#1A1A1A',
  muted: '#737373',
  border: '#E5E5E5',
  sectionLabel: '#1A1A1A',
  inputBorder: '#1A1A1A',
  sliderTrack: '#E5E5E5',
  sliderThumb: '#1A1A1A',
  previewBorder: '#E5E5E5',
  cardBg: '#FFFFFF',
  cardBorder: 'rgba(0,0,0,0.07)',
};

// ─── Helpers ─────────────────────────────────────────────────────────────────

/** Format milliseconds as "M:SS" for the scroll budget countdown display. */
const formatBudgetTime = ms => {
  if (ms == null || ms <= 0) return '0:00';
  const totalSec = Math.floor(ms / 1000);
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${min}:${String(sec).padStart(2, '0')}`;
};

// ─── Icons ───────────────────────────────────────────────────────────────────

const BackIcon = ({ color, size }) => (
  <Svg
    width={size}
    height={size}
    fill="none"
    stroke={color}
    strokeWidth={1.5}
    strokeLinecap="round"
    strokeLinejoin="round"
    viewBox="0 0 24 24"
  >
    <Path d="M15 19l-7-7 7-7" />
  </Svg>
);

// ─── Main Component ──────────────────────────────────────────────────────────
const Customize = ({ navigation }) => {
  const insets = useSafeAreaInsets();

  // ── Per-app policies state ───────────────────────────────────────────────
  // { "com.instagram.android": { app_open_intercept: true, reels_detection: true, ... }, ... }
  const [appPolicies, setAppPolicies] = useState({});

  // ── Global sub-feature toggles (kept global, not per-app) ─────────────
  const [freeBreakEnabled, setFreeBreakEnabled] = useState(false);

  // ── Scroll budget state ───────────────────────────────────────────────────
  const [scrollAllowance, setScrollAllowance] = useState(5);
  const [scrollWindow, setScrollWindow] = useState(60);
  const [budgetStatus, setBudgetStatus] = useState(null);

  // ── Home feed post limit ──────────────────────────────────────────────────
  const [homeFeedLimit, setHomeFeedLimit] = useState(20);

  // ── Intercept message + delay ─────────────────────────────────────────────
  const [interceptMessage, setInterceptMessage] = useState(
    'Is this intentional?',
  );
  const [pauseDuration, setPauseDuration] = useState(5);
  const [sliderValue, setSliderValue] = useState(5);

  // ── Preview modal ─────────────────────────────────────────────────────────
  const [previewVisible, setPreviewVisible] = useState(false);

  // ── "Saved" toast ─────────────────────────────────────────────────────────
  // Two states:
  //   - "Saving…"   — shown while a debounced write is pending (opacity held at 1)
  //   - "✓ Saved"   — shown once a commit lands (fades in then out)
  const savedOpacity = useRef(new Animated.Value(0)).current;
  const savedTimer = useRef(null);
  const [savedLabel, setSavedLabel] = useState('✓  Saved');

  // Called every time the user taps a toggle that gets scheduled.
  // Keeps the pill visible ("Saving…") until the commit fires.
  const showSavedPending = useCallback(() => {
    if (savedTimer.current) {
      clearTimeout(savedTimer.current);
      savedTimer.current = null;
    }
    setSavedLabel('Saving…');
    savedOpacity.setValue(1);
  }, [savedOpacity]);

  // Called by the saver's onCommit hook after pending writes flush to native.
  // Flips the label to "✓ Saved" and runs the fade-out animation.
  const showSavedCommitted = useCallback(() => {
    if (savedTimer.current) clearTimeout(savedTimer.current);
    setSavedLabel('✓  Saved');
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

  // Legacy alias: immediate writes (e.g. scroll budget buttons, preview message)
  // that don't go through the debounced saver still use the one-shot pill.
  const showSaved = showSavedCommitted;

  // Debounced saver — coalesces rapid toggles and commits after SAVE_DEBOUNCE_MS.
  // Flushed on navigation blur, AppState→background, and unmount (see effects below).
  const saver = useDebouncedSaver(SAVE_DEBOUNCE_MS, {
    onCommit: showSavedCommitted,
  });

  // Flush any pending writes when the user navigates away or backgrounds the app.
  useEffect(() => {
    const blurUnsub = navigation?.addListener
      ? navigation.addListener('blur', () => {
        console.log('[Customize] blur → flushing saver');
        saver.flush();
      })
      : null;
    const appStateSub = AppState.addEventListener('change', next => {
      if (next !== 'active') {
        console.log('[Customize] AppState=' + next + ' → flushing saver');
        saver.flush();
      }
    });
    return () => {
      if (blurUnsub) blurUnsub();
      appStateSub.remove();
    };
  }, [navigation, saver]);

  // ── Load saved settings ───────────────────────────────────────────────────

  useEffect(() => {
    const load = async () => {
      console.log('[Customize] loading saved settings');
      try {
        // Load per-app policies
        const policiesJson = await new Promise(resolve =>
          SettingsModule.getAppPolicies(json => resolve(json)),
        );
        let parsed = {};
        try {
          parsed = JSON.parse(policiesJson || '{}');
        } catch (_) { }
        setAppPolicies(parsed);
        console.log(
          '[Customize] app policies loaded:',
          Object.keys(parsed).length,
          'apps',
        );

        // Load free break toggle (global)
        const freeBreak = await new Promise(resolve =>
          SettingsModule.getFreeBreakEnabled(v => resolve(v)),
        );
        setFreeBreakEnabled(freeBreak === true);

        // Load active mode
        const mode = await new Promise(resolve =>
          SettingsModule.getActiveMode(m => resolve(m)),
        );
        setActiveMode(mode || '');
        console.log('[Customize] active mode:', mode);

        // Load custom modes (seed defaults if empty)
        const modesJson = await new Promise(resolve =>
          SettingsModule.getModes(json => resolve(json)),
        );
        let parsedModes = {};
        try {
          parsedModes = JSON.parse(modesJson || '{}');
        } catch (_) { }
        if (!parsedModes || Object.keys(parsedModes).length === 0) {
          parsedModes = DEFAULT_MODES;
          SettingsModule.saveModes(JSON.stringify(parsedModes));
        }
        setModes(parsedModes);
        console.log(
          '[Customize] modes loaded:',
          Object.keys(parsedModes).length,
        );

        // Load home feed post limit
        const feedLimit = await new Promise(resolve =>
          SettingsModule.getHomeFeedPostLimit(v => resolve(v)),
        );
        setHomeFeedLimit(typeof feedLimit === 'number' ? feedLimit : 20);
        console.log('[Customize] home feed post limit loaded:', feedLimit);

        // Load scroll budget
        await new Promise(resolve => {
          SettingsModule.getScrollBudget((allowance, window) => {
            setScrollAllowance(allowance);
            setScrollWindow(window);
            VPNModule.setScrollBudget(allowance, window).catch(e =>
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

  // ── Per-app feature toggle handler ─────────────────────────────────────────

  const handleAppFeatureToggle = useCallback(
    async (packageName, featureKey, value) => {
      console.log(
        '[Customize] app feature toggle:',
        packageName,
        featureKey,
        '→',
        value,
      );
      // Update local state immediately for instant UI feedback.
      setAppPolicies(prev => {
        const updated = { ...prev };
        if (!updated[packageName]) updated[packageName] = {};
        updated[packageName] = { ...updated[packageName], [featureKey]: value };
        return updated;
      });
      // Write immediately (no debounce) — per-app toggles are discrete user actions.
      // Awaiting guarantees syncBlockedAppsFromPolicies + dispatchBlockedAppsReload
      // have completed before startMonitoring reads the updated blocked-apps set.
      try {
        await SettingsModule.setAppFeature(packageName, featureKey, value);
        if (featureKey === 'app_open_intercept') {
          VPNModule.startMonitoring().catch(() => { });
        }
        showSavedPending();
      } catch (e) {
        console.error('[Customize] setAppFeature failed:', e);
      }
    },
    [showSavedPending],
  );

  const handleFreeBreakToggle = value => {
    console.log('[Customize] free break toggle →', value);
    setFreeBreakEnabled(value);
    saver.schedule('freeBreak', () => {
      SettingsModule.saveFreeBreakEnabled(value);
    });
    showSavedPending();
  };

  // ── Scroll budget handlers ────────────────────────────────────────────────

  const adjustAllowance = useCallback(
    delta => {
      const next = Math.max(0, Math.min(30, scrollAllowance + delta));
      setScrollAllowance(next);
      VPNModule.setScrollBudget(next, scrollWindow).catch(e =>
        console.warn('[Customize] setScrollBudget failed:', e),
      );
      SettingsModule.saveScrollBudget(next, scrollWindow);
      console.log('[Customize] scroll allowance →', next);
      showSaved();
    },
    [scrollAllowance, scrollWindow, showSaved],
  );

  const adjustWindow = useCallback(
    delta => {
      const next = Math.max(15, Math.min(120, scrollWindow + delta));
      setScrollWindow(next);
      VPNModule.setScrollBudget(scrollAllowance, next).catch(e =>
        console.warn('[Customize] setScrollBudget failed:', e),
      );
      SettingsModule.saveScrollBudget(scrollAllowance, next);
      console.log('[Customize] scroll window →', next);
      showSaved();
    },
    [scrollAllowance, scrollWindow, showSaved],
  );

  const adjustHomeFeedLimit = useCallback(
    delta => {
      const next = Math.max(5, Math.min(100, homeFeedLimit + delta));
      setHomeFeedLimit(next);
      SettingsModule.saveHomeFeedPostLimit(next);
      console.log('[Customize] home feed post limit →', next);
      showSaved();
    },
    [homeFeedLimit, showSaved],
  );

  // Scroll budget polling (every 5s when any app has reels detection on)
  const anyReelsOn = Object.values(appPolicies).some(
    p => p?.reels_detection === true,
  );
  useEffect(() => {
    if (!anyReelsOn) {
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
    poll();
    const interval = setInterval(poll, 5000);
    return () => clearInterval(interval);
  }, [anyReelsOn]);

  // ── Intercept message + delay handlers ───────────────────────────────────

  const handleSliderChange = value => setSliderValue(Math.round(value));
  const handleSliderComplete = async value => {
    const rounded = Math.round(value);
    setPauseDuration(rounded);
    setSliderValue(rounded);
    console.log('[Customize] pause duration →', rounded);
    try {
      await VPNModule.setDelayTime(rounded);
      showSaved();
    } catch (e) {
      console.warn('[Customize] setDelayTime error:', e);
    }
  };

  const handleMessageSubmit = async () => {
    console.log('[Customize] saving intercept message:', interceptMessage);
    try {
      await VPNModule.setDelayMessage(interceptMessage);
      showSaved();
    } catch (e) {
      console.warn('[Customize] setDelayMessage error:', e);
    }
  };

  // ── Render ────────────────────────────────────────────────────────────────

  // App list for cards
  const MANAGED_APPS = [
    { packageName: 'com.instagram.android', label: 'Instagram', emoji: '📷' },
    {
      packageName: 'com.google.android.youtube',
      label: 'YouTube',
      emoji: '▶️',
    },
  ];

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
            console.log('[Customize] back tapped');
            navigation.goBack();
          }}
        >
          <BackIcon color={L.charcoal} size={24} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>Customize</Text>
        <View style={styles.headerSpacer} />
      </View>

      <ScrollView
        style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
      >
        {/* ── YOUR APPS (per-app base defaults) ───────────────────── */}
        <View style={styles.section}>
          <Text style={styles.sectionLabel}>Your Apps</Text>

          {MANAGED_APPS.map(app => {
            const policy = appPolicies[app.packageName] || {};
            const reelsOn = policy.reels_detection === true;
            const interceptOn = policy.app_open_intercept === true;

            return (
              <View key={app.packageName} style={styles.appCard}>
                <Text style={styles.appCardTitle}>
                  {app.emoji} {app.label}
                </Text>

                <View style={styles.divider} />

                <View style={styles.toggleRow}>
                  <Text style={styles.toggleLabel}>Reels Detection</Text>
                  <Switch
                    value={reelsOn}
                    onValueChange={val =>
                      handleAppFeatureToggle(
                        app.packageName,
                        'reels_detection',
                        val,
                      )
                    }
                    trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                    thumbColor="#FFFFFF"
                  />
                </View>

                <View style={styles.toggleRow}>
                  <Text style={styles.toggleLabel}>App Open Intercept</Text>
                  <Switch
                    value={interceptOn}
                    onValueChange={val =>
                      handleAppFeatureToggle(
                        app.packageName,
                        'app_open_intercept',
                        val,
                      )
                    }
                    trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                    thumbColor="#FFFFFF"
                  />
                </View>
              </View>
            );
          })}

          {/* Global sub-features (shown when any app has Reels ON) */}
          {anyReelsOn && (
            <>
              <View style={[styles.divider, { marginTop: 16 }]} />
              <View style={styles.toggleRow}>
                <View style={styles.toggleLabelGroup}>
                  <Text style={styles.toggleLabel}>20-Min Free Break</Text>
                  <Text style={styles.toggleCaption}>
                    Once per day — scroll freely for 20 min with no
                    interruptions.
                  </Text>
                </View>
                <Switch
                  value={freeBreakEnabled}
                  onValueChange={handleFreeBreakToggle}
                  trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                  thumbColor="#FFFFFF"
                  accessibilityLabel="20-Minute Free Break toggle"
                />
              </View>
            </>
          )}
        </View>

        {/* ── Scroll Budget — visible when any app has Reels ON ── */}
        {anyReelsOn && (
          <View style={styles.section}>
            <Text style={styles.sectionLabel}>Scroll Budget</Text>

            {/* Home feed post limit — Instagram only, under same Reels toggle */}
            {appPolicies['com.instagram.android']?.reels_detection === true && (
              <>
                <Text style={styles.budgetSubLabel}>
                  Instagram Feed Post Limit
                </Text>
                <Text style={styles.budgetSubCaption}>
                  After this many posts on the home feed, you'll be prompted to
                  lock in.
                </Text>
                <View style={[styles.budgetControls, { marginBottom: 20 }]}>
                  <View style={styles.stepperGroup}>
                    <TouchableOpacity
                      style={styles.stepperBtn}
                      onPress={() => adjustHomeFeedLimit(-5)}
                      accessibilityRole="button"
                      accessibilityLabel="Decrease feed post limit"
                    >
                      <Text style={styles.stepperBtnText}>−</Text>
                    </TouchableOpacity>
                    <Text style={styles.stepperValue}>{homeFeedLimit}</Text>
                    <TouchableOpacity
                      style={styles.stepperBtn}
                      onPress={() => adjustHomeFeedLimit(5)}
                      accessibilityRole="button"
                      accessibilityLabel="Increase feed post limit"
                    >
                      <Text style={styles.stepperBtnText}>+</Text>
                    </TouchableOpacity>
                  </View>
                  <Text style={styles.budgetDivider}>posts</Text>
                </View>
                <View style={[styles.divider, { marginBottom: 16 }]} />
              </>
            )}

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
            {budgetStatus &&
              (() => {
                const canScroll = budgetStatus.canScroll;
                const statusColor = canScroll ? '#4CAF50' : '#E53935';
                const statusLabel = canScroll
                  ? `${formatBudgetTime(budgetStatus.remainingMs)} remaining`
                  : `Scroll again in ${formatBudgetTime(
                    budgetStatus.nextScrollAtMs - Date.now(),
                  )}`;
                const filledRatio = canScroll
                  ? Math.min(
                    1,
                    budgetStatus.usedMs / (scrollAllowance * 60 * 1000) || 0,
                  )
                  : 1;
                return (
                  <View style={styles.budgetStatusSection}>
                    <View style={styles.budgetStatusRow}>
                      <View
                        style={[
                          styles.budgetDot,
                          { backgroundColor: statusColor },
                        ]}
                      />
                      <Text
                        style={[
                          styles.budgetStatusText,
                          { color: statusColor },
                        ]}
                      >
                        {statusLabel}
                      </Text>
                    </View>
                    <View style={styles.budgetProgressBg}>
                      <View
                        style={{
                          flex: filledRatio,
                          backgroundColor: statusColor,
                          borderRadius: 2,
                        }}
                      />
                      <View style={{ flex: Math.max(0, 1 - filledRatio) }} />
                    </View>
                  </View>
                );
              })()}
          </View>
        )}

        {/* ── Intercept Message ────────────────────────────────────── */}
        <View style={styles.section}>
          <Text style={styles.sectionLabel}>Intercept Message</Text>

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

          <View style={styles.durationHeader}>
            <Text style={styles.durationLabel}>Forced Pause Duration</Text>
            <Text style={styles.durationValue}>{sliderValue} seconds</Text>
          </View>

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

          <View style={styles.sliderLabels}>
            <Text style={styles.sliderRangeLabel}>1s</Text>
            <Text style={styles.sliderRangeLabel}>30s</Text>
          </View>

          <TouchableOpacity
            style={styles.previewButton}
            activeOpacity={0.85}
            onPress={() => {
              console.log('[Customize] showing preview interstitial');
              setPreviewVisible(true);
            }}
            accessibilityRole="button"
            accessibilityLabel="Preview intercept"
          >
            <Text style={styles.previewButtonText}>Preview Intercept</Text>
          </TouchableOpacity>
        </View>

        <Text style={styles.footer}>v1.0 • Minimal Design</Text>
      </ScrollView>

      {/* ── Saved toast ──────────────────────────────────────────────── */}
      <Animated.View
        style={[styles.savedToast, { opacity: savedOpacity }]}
        pointerEvents="none"
        accessibilityLiveRegion="polite"
      >
        <Text style={styles.savedToastText}>{savedLabel}</Text>
      </Animated.View>

      {/* ── Preview modal ────────────────────────────────────────────── */}
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
  headerSpacer: { width: 36 },

  // Scrollable area
  scroll: { flex: 1 },
  scrollContent: {
    paddingHorizontal: 24,
    paddingTop: 28,
    paddingBottom: 48,
  },

  // Section block
  section: { marginBottom: 36 },
  sectionLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: L.charcoal,
    textTransform: 'uppercase',
    letterSpacing: 1.5,
    marginBottom: 12,
  },
  sectionCaption: {
    fontSize: 12,
    color: L.muted,
    lineHeight: 17,
    marginBottom: 16,
    marginTop: -4,
  },

  // ── Intervention Modes toggles ────────────────────────────────────────────
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
  divider: {
    height: 1,
    backgroundColor: L.border,
    marginVertical: 14,
  },

  // ── App cards ─────────────────────────────────────────────────────────────
  appCard: {
    backgroundColor: L.cardBg,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: L.cardBorder,
    padding: 16,
    marginBottom: 10,
  },
  appCardTitle: {
    fontSize: 17,
    fontWeight: '600',
    color: L.charcoal,
  },

  // ── Mode cards ───────────────────────────────────────────────────────────
  modeCard: {
    backgroundColor: L.cardBg,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: L.cardBorder,
    padding: 16,
    marginBottom: 10,
  },
  modeCardHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
  },
  modeIcon: {
    fontSize: 20,
  },
  modeCardInfo: {
    flex: 1,
  },
  modeCardName: {
    fontSize: 16,
    fontWeight: '500',
    color: L.charcoal,
  },
  modeCardSummary: {
    fontSize: 12,
    color: L.muted,
    marginTop: 2,
  },
  modeEditLink: {
    marginTop: 8,
    paddingTop: 8,
    borderTopWidth: 1,
    borderTopColor: L.border,
  },
  modeEditLinkText: {
    fontSize: 13,
    color: L.muted,
    fontWeight: '500',
  },

  // ── Mode editor (inline) ─────────────────────────────────────────────────
  modeEditor: {
    marginTop: 12,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: L.border,
    gap: 12,
  },
  modeNameInput: {
    fontSize: 16,
    color: L.charcoal,
    borderBottomWidth: 1.5,
    borderBottomColor: L.inputBorder,
    paddingVertical: 6,
    paddingHorizontal: 0,
  },
  editorSectionLabel: {
    fontSize: 10,
    fontWeight: '600',
    color: L.muted,
    textTransform: 'uppercase',
    letterSpacing: 1.2,
    marginTop: 8,
  },
  modeAppBlock: {
    backgroundColor: 'rgba(0,0,0,0.02)',
    borderRadius: 10,
    padding: 12,
    gap: 6,
  },
  modeAppLabel: {
    fontSize: 14,
    fontWeight: '500',
    color: L.charcoal,
    marginBottom: 4,
  },
  modeFeatureRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 2,
  },
  modeFeatureLabel: {
    fontSize: 14,
    color: L.charcoal,
  },
  modeDelayRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  modeDelayInput: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  modeDelayValue: {
    fontSize: 16,
    fontWeight: '500',
    color: L.charcoal,
    minWidth: 36,
    textAlign: 'center',
    fontVariant: ['tabular-nums'],
  },

  // ── Schedule ─────────────────────────────────────────────────────────────
  addScheduleBtn: {
    borderWidth: 1,
    borderColor: L.border,
    borderStyle: 'dashed',
    borderRadius: 10,
    paddingVertical: 12,
    alignItems: 'center',
  },
  addScheduleBtnText: {
    fontSize: 13,
    color: L.muted,
    fontWeight: '500',
  },
  scheduleBlock: {
    backgroundColor: 'rgba(0,0,0,0.02)',
    borderRadius: 10,
    padding: 12,
    gap: 10,
  },
  scheduleTimeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  scheduleTimeLabel: {
    fontSize: 14,
    color: L.charcoal,
  },
  scheduleTimeInput: {
    fontSize: 16,
    fontWeight: '500',
    color: L.charcoal,
    borderBottomWidth: 1,
    borderBottomColor: L.border,
    paddingVertical: 4,
    paddingHorizontal: 8,
    minWidth: 70,
    textAlign: 'center',
    fontVariant: ['tabular-nums'],
  },
  dayPickerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    gap: 6,
  },
  dayBtn: {
    width: 34,
    height: 34,
    borderRadius: 17,
    backgroundColor: 'rgba(0,0,0,0.06)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  dayBtnActive: {
    backgroundColor: L.charcoal,
  },
  dayBtnText: {
    fontSize: 12,
    fontWeight: '600',
    color: L.charcoal,
  },
  dayBtnTextActive: {
    color: '#FFFFFF',
  },
  removeScheduleText: {
    fontSize: 12,
    color: '#E53935',
    fontWeight: '500',
    textAlign: 'center',
  },

  // ── Mode editor actions ──────────────────────────────────────────────────
  modeEditorActions: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 8,
  },
  modeSaveBtn: {
    backgroundColor: L.charcoal,
    borderRadius: 9999,
    paddingVertical: 10,
    paddingHorizontal: 28,
  },
  modeSaveBtnText: {
    color: '#FFFFFF',
    fontSize: 14,
    fontWeight: '500',
  },
  modeDeleteText: {
    fontSize: 13,
    color: '#E53935',
    fontWeight: '500',
  },

  // ── Create mode button ───────────────────────────────────────────────────
  createModeBtn: {
    borderWidth: 1,
    borderColor: L.border,
    borderStyle: 'dashed',
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: 'center',
  },
  createModeBtnText: {
    fontSize: 14,
    color: L.muted,
    fontWeight: '500',
  },

  // ── Scroll Budget ────────────────────────────────────────────────────────
  budgetSubLabel: {
    fontSize: 13,
    fontWeight: '500',
    color: L.charcoal,
    marginBottom: 4,
  },
  budgetSubCaption: {
    fontSize: 12,
    color: L.muted,
    lineHeight: 17,
    marginBottom: 12,
  },
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
  budgetStatusSection: { gap: 6 },
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

  // ── Intercept Message ────────────────────────────────────────────────────
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

  // ── Saved toast ──────────────────────────────────────────────────────────
  savedToast: {
    position: 'absolute',
    bottom: 80,
    alignSelf: 'center',
    backgroundColor: '#1A1A1A',
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: 9999,
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

  // Footer
  footer: {
    textAlign: 'center',
    fontSize: 10,
    color: L.muted,
    letterSpacing: 1.5,
    textTransform: 'uppercase',
    marginTop: 8,
  },
});
