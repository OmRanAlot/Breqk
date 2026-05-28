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
  Linking,
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

  // ── Browser content filter state ─────────────────────────────────────────
  const [contentFilterEnabled, setContentFilterEnabled] = useState(false);
  const [contentFilterServiceActive, setContentFilterServiceActive] =
    useState(false);

  // ── Deletion-prevention (uninstall lock) state ───────────────────────────
  // Opt-in. When on, a 30s lock screen appears if the user opens the Breqk
  // uninstall screen in Android Settings.
  const [uninstallLockEnabled, setUninstallLockEnabled] = useState(false);
  // Confirmation modal shown before enabling deletion prevention, so the user
  // reads what it does, its limitations, and the privacy guarantee first.
  const [deletionInfoVisible, setDeletionInfoVisible] = useState(false);

  // ── Scroll budget state ───────────────────────────────────────────────────
  const [scrollAllowance, setScrollAllowance] = useState(5);
  const [scrollWindow, setScrollWindow] = useState(60);
  const [budgetStatus, setBudgetStatus] = useState(null);

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

  const loadSettings = useCallback(async () => {
    console.log('[Customize] loading saved settings');
    try {
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

      // Load intercept message from native
      SettingsModule.getDelayMessage(message => {
        setInterceptMessage(message);
        console.log('[Customize] delay_message=', message);
      });

      // Load pause duration from native
      SettingsModule.getDelayTime(seconds => {
        setPauseDuration(seconds);
        setSliderValue(seconds);
        console.log('[Customize] delay_time_seconds=', seconds);
      });

      // Load content filter state
      SettingsModule.getContentFilterEnabled(enabled => {
        setContentFilterEnabled(enabled);
        console.log('[Customize] content_filter_enabled=', enabled);
      });
      SettingsModule.isContentFilterServiceEnabled(active => {
        setContentFilterServiceActive(active);
        console.log('[Customize] content_filter_service_active=', active);
      });

      // Load deletion-prevention state
      SettingsModule.getUninstallLockEnabled(enabled => {
        setUninstallLockEnabled(enabled);
        console.log('[Customize] uninstall_lock_enabled=', enabled);
      });
    } catch (e) {
      console.warn('[Customize] load settings error:', e);
    }
  }, []);

  // Load on mount
  useEffect(() => {
    loadSettings();
  }, [loadSettings]);

  // Reload when screen regains focus (e.g. returning from AppDetail / Modes)
  useEffect(() => {
    const focusUnsub = navigation?.addListener
      ? navigation.addListener('focus', () => {
          console.log('[Customize] focus → reloading settings');
          loadSettings();
        })
      : null;
    return () => {
      if (focusUnsub) focusUnsub();
    };
  }, [navigation, loadSettings]);

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

  // Scroll budget polling (every 5s)
  useEffect(() => {
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
  }, []);

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

  // ── Content filter handler ────────────────────────────────────────────────

  const handleContentFilterToggle = useCallback(
    async value => {
      setContentFilterEnabled(value);
      console.log('[Customize] content_filter toggled →', value);
      try {
        await SettingsModule.saveContentFilterEnabled(value);
        showSaved();
        // After enabling, re-check whether the accessibility service is active
        if (value) {
          SettingsModule.isContentFilterServiceEnabled(active => {
            setContentFilterServiceActive(active);
          });
        }
      } catch (e) {
        console.warn('[Customize] saveContentFilterEnabled error:', e);
      }
    },
    [showSaved],
  );

  // Persist the deletion-prevention flag. Extracted so both the confirm-modal
  // "Enable" action and the direct "turn off" path share one write.
  const saveUninstallLock = useCallback(
    async value => {
      setUninstallLockEnabled(value);
      console.log('[Customize] uninstall_lock toggled →', value);
      try {
        await SettingsModule.saveUninstallLockEnabled(value);
        showSaved();
      } catch (e) {
        console.warn('[Customize] saveUninstallLockEnabled error:', e);
      }
    },
    [showSaved],
  );

  const handleUninstallLockToggle = useCallback(
    value => {
      // Turning on requires reading the info modal first; turning off is direct.
      if (value) {
        console.log('[Customize] uninstall_lock enable requested — showing info');
        setDeletionInfoVisible(true);
        return;
      }
      saveUninstallLock(false);
    },
    [saveUninstallLock],
  );

  const handleDeletionInfoConfirm = useCallback(() => {
    console.log('[Customize] deletion-prevention info confirmed — enabling');
    setDeletionInfoVisible(false);
    saveUninstallLock(true);
  }, [saveUninstallLock]);

  const handleDeletionInfoCancel = useCallback(() => {
    console.log('[Customize] deletion-prevention info cancelled');
    setDeletionInfoVisible(false);
  }, []);

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
        {/* ── Scroll Budget ─────────────────────────────────────────── */}
        <View style={styles.section}>
          <Text style={styles.sectionLabel}>Scroll Budget</Text>

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

          {scrollAllowance === 0 && (
            <Text style={styles.budgetWarning}>
              0m allowance = Reels blocked immediately on every attempt.
            </Text>
          )}

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
                      style={[styles.budgetStatusText, { color: statusColor }]}
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

        {/* ── Intercept Message ────────────────────────────────────── */}
        <View style={styles.section}>
          <Text style={styles.sectionLabel}>Intercept Message</Text>

          <TextInput
            style={styles.messageInput}
            value={interceptMessage}
            onChangeText={setInterceptMessage}
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

        {/* ── Browser Content Filter ───────────────────────────────── */}
        <View style={styles.section}>
          <Text style={styles.sectionLabel}>Browser Safety</Text>

          <View style={styles.toggleRow}>
            <View style={styles.toggleLabelGroup}>
              <Text style={styles.toggleLabel}>Content filter</Text>
              <Text style={styles.toggleCaption}>
                Blocks listed domains in browsers. Uses a separate Accessibility
                entry from Reels/Shorts; turn on both toggles if you want both
                features.
              </Text>
            </View>
            <Switch
              value={contentFilterEnabled}
              onValueChange={handleContentFilterToggle}
              trackColor={{ false: L.border, true: L.charcoal }}
              thumbColor="#FFFFFF"
              accessibilityLabel="Browser content filter"
            />
          </View>

          {contentFilterEnabled && !contentFilterServiceActive && (
            <TouchableOpacity
              style={styles.permissionHint}
              activeOpacity={0.75}
              onPress={() => {
                console.log(
                  '[Customize] opening accessibility settings for unified service',
                );
                Linking.sendIntent(
                  'android.settings.ACCESSIBILITY_SETTINGS',
                ).catch(e =>
                  console.warn('[Customize] openSettings error:', e),
                );
              }}
              accessibilityRole="button"
              accessibilityLabel="Grant accessibility permission for Breqk"
            >
              <Text style={styles.permissionHintText}>
                ⚠ Enable the Breqk accessibility service — tap to open
                Accessibility Settings
              </Text>
            </TouchableOpacity>
          )}
        </View>

        {/* ── Deletion Prevention ──────────────────────────────────── */}
        <View style={styles.section}>
          <Text style={styles.sectionLabel}>Deletion Prevention</Text>

          <View style={styles.toggleRow}>
            <View style={styles.toggleLabelGroup}>
              <Text style={styles.toggleLabel}>Prevent deletion</Text>
              <Text style={styles.toggleCaption}>
                If you open the Breqk uninstall screen, a full-screen pause
                appears for 30 seconds with reasons to keep going before you can
                continue. Helps you not quit on impulse.
              </Text>
            </View>
            <Switch
              value={uninstallLockEnabled}
              onValueChange={handleUninstallLockToggle}
              trackColor={{ false: L.border, true: L.charcoal }}
              thumbColor="#FFFFFF"
              accessibilityLabel="Prevent deletion"
            />
          </View>
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

      {/* ── Deletion-prevention info modal ───────────────────────────── */}
      <Modal
        visible={deletionInfoVisible}
        transparent
        animationType="fade"
        onRequestClose={handleDeletionInfoCancel}
      >
        <View style={styles.infoModalOverlay}>
          <View style={styles.infoModalCard}>
            <ScrollView
              style={styles.infoModalScroll}
              contentContainerStyle={styles.infoModalContent}
              showsVerticalScrollIndicator={false}
            >
              <Text style={styles.infoModalTitle}>Before you turn this on</Text>

              <Text style={styles.infoModalSectionHeading}>What it does</Text>
              {[
                'Uses the accessibility service you already granted to notice when you open Breqk’s App Info / uninstall screen in Android Settings.',
                'Shows a full-screen pause for 30 seconds with reasons to keep going.',
                'After the 30 seconds you can continue — it never permanently stops you from uninstalling.',
              ].map((line, i) => (
                <View key={`does-${i}`} style={styles.infoModalBulletRow}>
                  <Text style={styles.infoModalBullet}>{'•'}</Text>
                  <Text style={styles.infoModalBulletText}>{line}</Text>
                </View>
              ))}

              <Text style={styles.infoModalSectionHeading}>
                Risks &amp; limitations
              </Text>
              {[
                'This is friction, not a lock. You can wait out the timer, turn off the accessibility service, or use safe mode to remove Breqk anytime.',
                'Detection reads only the on-screen text of the Settings uninstall page to know when to show the pause — nothing else.',
                'Some phone brands label that screen differently, so on rare devices the pause may not appear.',
                'Like any accessibility feature, it depends on a permission that can read screen content; Breqk uses it solely to detect blocked apps and this screen.',
              ].map((line, i) => (
                <View key={`risk-${i}`} style={styles.infoModalBulletRow}>
                  <Text style={styles.infoModalBullet}>{'•'}</Text>
                  <Text style={styles.infoModalBulletText}>{line}</Text>
                </View>
              ))}

              <Text style={styles.infoModalSectionHeading}>Your privacy</Text>
              <Text style={styles.infoModalPrivacy}>
                Breqk collects no data at all. It cannot — the app has no
                server and makes no network connection whatsoever, so there is no
                way for any of this to ever leave your phone. Everything stays in
                local settings on your device. Nothing is collected, nothing is
                sent.
              </Text>
            </ScrollView>

            <View style={styles.infoModalButtonRow}>
              <TouchableOpacity
                style={styles.infoModalCancelButton}
                activeOpacity={0.7}
                accessibilityRole="button"
                accessibilityLabel="Cancel"
                onPress={handleDeletionInfoCancel}
              >
                <Text style={styles.infoModalCancelText}>Cancel</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={styles.infoModalEnableButton}
                activeOpacity={0.7}
                accessibilityRole="button"
                accessibilityLabel="Enable deletion prevention"
                onPress={handleDeletionInfoConfirm}
              >
                <Text style={styles.infoModalEnableText}>Enable</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
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

  // ── Deletion-prevention info modal ────────────────────────────────────────
  infoModalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.45)',
    justifyContent: 'center',
    paddingHorizontal: 24,
  },
  infoModalCard: {
    backgroundColor: L.cardBg,
    borderRadius: 16,
    maxHeight: '82%',
    overflow: 'hidden',
  },
  infoModalScroll: {
    flexGrow: 0,
  },
  infoModalContent: {
    padding: 24,
  },
  infoModalTitle: {
    fontSize: 20,
    fontWeight: '600',
    color: L.charcoal,
    letterSpacing: -0.3,
    marginBottom: 8,
  },
  infoModalSectionHeading: {
    fontSize: 11,
    fontWeight: '600',
    color: L.charcoal,
    textTransform: 'uppercase',
    letterSpacing: 1.5,
    marginTop: 20,
    marginBottom: 10,
  },
  infoModalBulletRow: {
    flexDirection: 'row',
    marginBottom: 8,
  },
  infoModalBullet: {
    fontSize: 14,
    color: L.muted,
    marginRight: 8,
    lineHeight: 20,
  },
  infoModalBulletText: {
    flex: 1,
    fontSize: 14,
    color: L.charcoal,
    lineHeight: 20,
  },
  infoModalPrivacy: {
    fontSize: 14,
    color: L.charcoal,
    lineHeight: 21,
    fontWeight: '500',
  },
  infoModalButtonRow: {
    flexDirection: 'row',
    borderTopWidth: 1,
    borderTopColor: L.border,
  },
  infoModalCancelButton: {
    flex: 1,
    paddingVertical: 16,
    alignItems: 'center',
    justifyContent: 'center',
    borderRightWidth: 1,
    borderRightColor: L.border,
  },
  infoModalCancelText: {
    fontSize: 16,
    color: L.muted,
    fontWeight: '500',
  },
  infoModalEnableButton: {
    flex: 1,
    paddingVertical: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  infoModalEnableText: {
    fontSize: 16,
    color: L.charcoal,
    fontWeight: '600',
  },
  divider: {
    height: 1,
    backgroundColor: L.border,
    marginVertical: 14,
  },
  permissionHint: {
    marginTop: 10,
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: '#FFF8E1',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#FFE082',
  },
  permissionHintText: {
    fontSize: 12,
    color: '#7B5800',
    lineHeight: 17,
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
  budgetWarning: {
    fontSize: 12,
    color: '#C62828',
    marginTop: 8,
    lineHeight: 17,
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
