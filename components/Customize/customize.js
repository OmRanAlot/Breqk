/**
 * customize.js — Customize Screen (Break light design system)
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
  Switch,
  TouchableOpacity,
  ScrollView,
  NativeModules,
  Modal,
  Animated,
  AppState,
  Linking,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Path } from 'react-native-svg';
import BlockerInterstitial from '../BlockerInterstitial/BlockerInterstitial';
import useDebouncedSaver from './useDebouncedSaver';
import {
  deriveBudgetStatus,
  inferWindowStartMs,
} from '../shared/scrollBudgetStatus';
import useSettingsLock from './useSettingsLock';
import useContentFilterGuard from './useContentFilterGuard';
import SettingsLockGate from './SettingsLockGate';
import SettingsLockSection from './SettingsLockSection';
import useDefaultModeGate from '../shared/useDefaultModeGate';
import ModeGateBanner from '../shared/ModeGateBanner';
import InfoCircle from '../shared/InfoCircle';
import { formatRemaining, GUARD_STATES } from '../shared/lockCycle';
import ScrollBudgetSection from './ScrollBudgetSection';
import InterceptMessageSection from './InterceptMessageSection';
import DeletionInfoModal from './DeletionInfoModal';
import { styles, L } from './customize.styles';

// Debounce window for Customize writes. Rapid toggles coalesce into a single
// commit after this quiet period; any navigate-away / background / unmount
// forces an immediate flush so no writes are ever dropped.
const SAVE_DEBOUNCE_MS = 7000;

const { VPNModule, SettingsModule } = NativeModules;

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

  // ── Default-mode gate ─────────────────────────────────────────────────────
  // Base settings belong to Default mode. While any other mode is active it owns
  // the app's behaviour, so editing the base underneath would be a silent no-op —
  // the controls below go read-only and the banner points the user at the fix.
  // Native rejects these writes independently (BreakPrefs.isBaseSettingsEditable),
  // so this is UX, not the enforcement.
  const modeGate = useDefaultModeGate(navigation);

  // ── Browser content filter state ─────────────────────────────────────────
  const [contentFilterEnabled, setContentFilterEnabled] = useState(false);
  const [accessibilityServiceActive, setAccessibilityServiceActive] =
    useState(false);

  // ── Deletion-prevention (uninstall lock) state ───────────────────────────
  // Opt-in. When on, a 30s lock screen appears if the user opens the Break
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

  // Settings Change Lock for the GLOBAL scope. Any edit on this screen marks the
  // scope dirty; leaving the screen (blur/background/unmount) then starts the lock
  // if the feature is enabled. See useSettingsLock.
  const settingsLock = useSettingsLock('global', navigation);
  const { markDirty: markSettingsDirty } = settingsLock;

  // Content-filter double-safe guard: opt-in two-step disable for the browser
  // content filter (disable → wait a full lock duration → confirm window →
  // disable again). See useContentFilterGuard / ContentFilterGuard.java.
  const cfGuard = useContentFilterGuard(navigation);

  // Called every time the user taps a toggle that gets scheduled.
  // Keeps the pill visible ("Saving…") until the commit fires.
  const showSavedPending = useCallback(() => {
    if (savedTimer.current) {
      clearTimeout(savedTimer.current);
      savedTimer.current = null;
    }
    setSavedLabel('Saving…');
    savedOpacity.setValue(1);
    markSettingsDirty();
  }, [savedOpacity, markSettingsDirty]);

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
    markSettingsDirty();
  }, [savedOpacity, markSettingsDirty]);

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
          // Re-push the saved budget to the live monitor. Skipped while a mode
          // owns the settings: native rejects base writes then (MODE_ACTIVE), and
          // this is only a redundant re-sync of a value we just read back.
          if (modeGate.isEditable) {
            VPNModule.setScrollBudget(allowance, window).catch(e =>
              console.warn('[Customize] setScrollBudget failed:', e),
            );
          }
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
        setAccessibilityServiceActive(active);
        console.log('[Customize] accessibility_service_active=', active);
      });

      // Load deletion-prevention state
      SettingsModule.getUninstallLockEnabled(enabled => {
        setUninstallLockEnabled(enabled);
        console.log('[Customize] uninstall_lock_enabled=', enabled);
      });
    } catch (e) {
      console.warn('[Customize] load settings error:', e);
    }
  }, [modeGate.isEditable]);

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
      const next = Math.max(0, Math.min(15, scrollAllowance + delta));
      setScrollAllowance(next);

      // Optimistic UI: immediately derive the new status from current runtime
      // data so the display updates before the native poll resolves.
      setBudgetStatus(prev => {
        if (!prev) return prev;
        return {
          ...prev,
          allowanceMinutes: next,
          ...deriveBudgetStatus({
            allowanceMinutes: next,
            windowMinutes: scrollWindow,
            usedMs: prev.usedMs,
            windowStartMs: inferWindowStartMs(prev),
            exhaustedAtMs: prev.canScroll
              ? 0
              : prev.nextScrollAtMs - scrollWindow * 60_000,
          }),
        };
      });

      // Fire native update in the background — no await so the UI doesn't block.
      VPNModule.setScrollBudget(next, scrollWindow)
        .then(() => VPNModule.getScrollBudgetStatus().then(setBudgetStatus))
        .catch(e => console.warn('[Customize] setScrollBudget failed:', e));
      SettingsModule.saveScrollBudget(next, scrollWindow);
      console.log('[Customize] scroll allowance →', next);
      showSaved();
    },
    [scrollAllowance, scrollWindow, showSaved],
  );

  const adjustWindow = useCallback(
    delta => {
      const next = Math.max(45, Math.min(240, scrollWindow + delta));
      setScrollWindow(next);

      setBudgetStatus(prev => {
        if (!prev) return prev;
        return {
          ...prev,
          windowMinutes: next,
          ...deriveBudgetStatus({
            allowanceMinutes: scrollAllowance,
            windowMinutes: next,
            usedMs: prev.usedMs,
            windowStartMs: inferWindowStartMs(prev),
            exhaustedAtMs: prev.canScroll
              ? 0
              : prev.nextScrollAtMs - next * 60_000,
          }),
        };
      });

      VPNModule.setScrollBudget(scrollAllowance, next)
        .then(() => VPNModule.getScrollBudgetStatus().then(setBudgetStatus))
        .catch(e => console.warn('[Customize] setScrollBudget failed:', e));
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
      // Double-safe guard on + turning OFF → this tap is disable #1 of two.
      // The filter STAYS ACTIVE; a full lock-duration wait starts, then a
      // confirm window in which disable #2 actually turns it off.
      if (!value && cfGuard.doubleSafeEnabled) {
        console.log('[Customize] content_filter disable → routed to guard');
        const ok = await cfGuard.requestDisable();
        if (ok) showSaved();
        return;
      }
      setContentFilterEnabled(value);
      console.log('[Customize] content_filter toggled →', value);
      try {
        await SettingsModule.saveContentFilterEnabled(value);
        showSaved();
        // Re-enabling also clears any pending two-step disable natively.
        cfGuard.refresh();
        // After enabling, re-check whether the accessibility service is active
        if (value) {
          SettingsModule.isContentFilterServiceEnabled(active => {
            setAccessibilityServiceActive(active);
          });
        }
      } catch (e) {
        console.warn('[Customize] saveContentFilterEnabled error:', e);
      }
    },
    [showSaved, cfGuard],
  );

  // Opt-in/out of the double-safe guard. Turning it OFF is refused natively
  // while a pending disable is in flight (that would shortcut the wait).
  const handleDoubleSafeToggle = useCallback(
    async value => {
      console.log('[Customize] cf double-safe toggled →', value);
      const ok = await cfGuard.setDoubleSafe(value);
      if (ok) showSaved();
    },
    [cfGuard, showSaved],
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
        console.log(
          '[Customize] uninstall_lock enable requested — showing info',
        );
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
        {/* ── Default-mode gate: a mode owns these settings ─────────────
            Shown above the settings-lock gate because the two are independent —
            a mode can be active while the scope is also lock-timed, and the mode
            is the one the user can act on right now. */}
        {!modeGate.isEditable && (
          <ModeGateBanner
            modeName={modeGate.activeModeName}
            modeColor={modeGate.activeModeColor}
            modeIcon={modeGate.activeModeIcon}
            onSwitchToDefault={modeGate.switchToDefault}
            scopeLabel="these settings"
          />
        )}

        {/* ── Settings Change Lock: read-only gate while the global scope is locked ── */}
        {settingsLock.locked ? (
          <SettingsLockGate remainingMs={settingsLock.remainingMs} />
        ) : (
          <>
            <ScrollBudgetSection
              scrollAllowance={scrollAllowance}
              scrollWindow={scrollWindow}
              budgetStatus={budgetStatus}
              adjustAllowance={adjustAllowance}
              adjustWindow={adjustWindow}
              disabled={!modeGate.isEditable}
            />

            {/* <InterceptMessageSection
              interceptMessage={interceptMessage}
              setInterceptMessage={setInterceptMessage}
              handleMessageSubmit={handleMessageSubmit}
              sliderValue={sliderValue}
              pauseDuration={pauseDuration}
              handleSliderChange={handleSliderChange}
              handleSliderComplete={handleSliderComplete}
              onPreview={() => {
                console.log('[Customize] showing preview interstitial');
                setPreviewVisible(true);
              }}
            /> */}

            {/* ── Browser Content Filter ───────────────────────────────── */}
            <View style={styles.section}>
              <Text style={styles.sectionLabel}>Browser Safety</Text>

              <View style={styles.toggleRow}>
                <View style={styles.toggleLabelGroup}>
                  <Text style={styles.toggleLabel}>Content filter</Text>
                  <Text style={styles.toggleCaption}>
                    Blocks listed domains in Chrome and other browsers. Requires
                    the Break accessibility service (one toggle in system
                    settings).
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

              {/* Double-safe disable: opt-in second barrier on the filter. */}
              <View style={styles.toggleRow}>
                <View style={styles.toggleLabelGroup}>
                  <View style={styles.labelWithInfo}>
                    <Text style={styles.toggleLabel}>Double-safe disable</Text>
                    <InfoCircle title="How Double-safe disable works">
                      <Text style={styles.infoPara}>
                        With this on, turning the content filter off takes two
                        deliberate steps instead of one tap.
                      </Text>
                      <Text style={styles.infoPara}>
                        Step 1 — flip the filter off once. Nothing turns off
                        yet: the filter keeps protecting you while a full lock
                        timer runs (your Settings Change Lock duration).
                      </Text>
                      <Text style={styles.infoPara}>
                        Step 2 — when the timer ends, a short confirm window
                        opens. Confirm the disable there and the filter actually
                        turns off.
                      </Text>
                      <Text style={styles.infoPara}>
                        If you let the confirm window pass without confirming,
                        the pending disable is discarded and full protection
                        re-instates automatically. The confirm window matches
                        your re-arm grace setting, or 4 hours if that is set to
                        “None”.
                      </Text>
                      <Text style={styles.infoPara}>
                        You can cancel a pending disable at any time, and
                        re-enabling the filter is always instant. This toggle
                        itself can’t be turned off while a disable is pending.
                      </Text>
                    </InfoCircle>
                  </View>
                  <Text style={styles.toggleCaption}>
                    Turning the filter off requires two waits: one full lock
                    timer, then a final confirmation. Protection re-arms itself
                    if you don’t follow through.
                  </Text>
                </View>
                <Switch
                  value={cfGuard.doubleSafeEnabled}
                  onValueChange={handleDoubleSafeToggle}
                  trackColor={{ false: L.border, true: L.charcoal }}
                  thumbColor="#FFFFFF"
                  accessibilityLabel="Double-safe disable for content filter"
                />
              </View>

              {contentFilterEnabled && !accessibilityServiceActive && (
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
                  accessibilityLabel="Grant accessibility permission for Break"
                >
                  <Text style={styles.permissionHintText}>
                    ⚠ Enable the Break accessibility service — tap to open
                    Accessibility Settings
                  </Text>
                </TouchableOpacity>
              )}
            </View>

            {/* ── Settings Change Lock (opt-in) ─────────────────────────
                Lives INSIDE the gate so that once the global scope is locked
                the toggle itself is read-only — you can't simply disable the
                feature to bypass the wait. Enabling it (or changing its
                duration) marks the scope dirty, so leaving the screen arms the
                lock the same way any other change does. */}
            <SettingsLockSection
              enabled={settingsLock.enabled}
              durationMs={settingsLock.durationMs}
              graceMs={settingsLock.graceMs}
              inGrace={settingsLock.inGrace}
              graceRemainingMs={settingsLock.graceRemainingMs}
              locked={settingsLock.anyLocked}
              onToggle={value => {
                settingsLock.setEnabled(value);
                // Turning it ON (or keeping it on) commits the global scope so
                // it locks on exit. Turning OFF never arms a lock.
                if (value) markSettingsDirty();
              }}
              onPickDuration={hours => {
                settingsLock.setDurationHours(hours);
                markSettingsDirty();
              }}
              onPickGrace={hours => {
                settingsLock.setGraceHours(hours);
                markSettingsDirty();
              }}
            />
          </>
        )}

        {/* ── Content-filter guard status ──────────────────────────────
            Rendered OUTSIDE the SettingsLockGate on purpose: if the global
            scope re-locks while a two-step disable is waiting, the user must
            still be able to see the countdown and confirm/cancel — otherwise
            the confirm window could never be reached. */}
        {(cfGuard.state === GUARD_STATES.PENDING_WAIT ||
          cfGuard.state === GUARD_STATES.CONFIRM_WINDOW) && (
          <View style={styles.section}>
            <Text style={styles.sectionLabel}>Content Filter — Pending</Text>

            {cfGuard.state === GUARD_STATES.PENDING_WAIT ? (
              <Text style={styles.guardStatusText}>
                First barrier removed. The filter is still protecting you — the
                final disable unlocks in{' '}
                <Text style={styles.guardStatusStrong}>
                  {formatRemaining(cfGuard.waitRemainingMs)}
                </Text>
                .
              </Text>
            ) : (
              <Text style={styles.guardStatusWarn}>
                Confirm window open: disable the filter for good within{' '}
                <Text style={styles.guardStatusStrong}>
                  {formatRemaining(cfGuard.confirmRemainingMs)}
                </Text>{' '}
                — or protection re-arms automatically.
              </Text>
            )}

            <View style={styles.guardButtonRow}>
              {cfGuard.state === GUARD_STATES.CONFIRM_WINDOW && (
                <TouchableOpacity
                  style={styles.guardConfirmButton}
                  activeOpacity={0.8}
                  accessibilityRole="button"
                  accessibilityLabel="Confirm content filter disable"
                  onPress={async () => {
                    console.log('[Customize] cf guard confirm tapped');
                    const ok = await cfGuard.confirmDisable();
                    if (ok) {
                      setContentFilterEnabled(false);
                      showSaved();
                    }
                  }}
                >
                  <Text style={styles.guardConfirmText}>Disable filter</Text>
                </TouchableOpacity>
              )}
              <TouchableOpacity
                style={styles.guardCancelButton}
                activeOpacity={0.7}
                accessibilityRole="button"
                accessibilityLabel="Cancel pending content filter disable"
                onPress={async () => {
                  console.log('[Customize] cf guard cancel tapped');
                  const ok = await cfGuard.cancelDisable();
                  if (ok) showSaved();
                }}
              >
                <Text style={styles.guardCancelText}>Keep protection</Text>
              </TouchableOpacity>
            </View>
          </View>
        )}

        {/* ── Deletion Prevention ──────────────────────────────────── */}
        <View style={styles.section}>
          <Text style={styles.sectionLabel}>Deletion Prevention</Text>

          <View style={styles.toggleRow}>
            <View style={styles.toggleLabelGroup}>
              <Text style={styles.toggleLabel}>Prevent deletion</Text>
              <Text style={styles.toggleCaption}>
                If you open the Break uninstall screen, a full-screen pause
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
      <DeletionInfoModal
        visible={deletionInfoVisible}
        onCancel={handleDeletionInfoCancel}
        onConfirm={handleDeletionInfoConfirm}
      />
    </View>
  );
};

export default Customize;
