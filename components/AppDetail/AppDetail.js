/**
 * AppDetail.js — Per-app settings screen.
 *
 * Receives { packageName } from route.params and renders all controls
 * for that app from the manifest:
 *   • Enabled master toggle (short-circuits everything below when off)
 *   • App Open Intercept toggle (universal — delay overlay on launch)
 *   • Per-app feature toggles (Switch or stepper per manifest kind)
 *   • 20-Min Free Break toggle (once per day, scoped to this app)
 *   • "Open in Safe Mode" button (apps with safeModePlatform only)
 *
 * Writes immediately on each toggle via SettingsModule.setAppFeature.
 * For Instagram session_post_limit, also calls saveHomeFeedPostLimit
 * for backwards compatibility with the native ReelsInterventionService.
 *
 * Logging prefix: [AppDetail]
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  View,
  Text,
  Switch,
  TouchableOpacity,
  ScrollView,
  StyleSheet,
  NativeModules,
  TextInput,
  Modal,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Slider from '@react-native-community/slider';
import Svg, { Path } from 'react-native-svg';
import { MANAGED_APPS } from '../managedApps/manifest';
import useSettingsLock from '../Customize/useSettingsLock';
import SettingsLockGate from '../Customize/SettingsLockGate';

const { VPNModule, SettingsModule } = NativeModules;

const POPUP_DELAY_ONCE_SENTINEL = 2147483647;

const L = {
  bg: '#FAFAFA',
  charcoal: '#1A1A1A',
  muted: '#737373',
  border: '#E5E5E5',
  cardBg: '#FFFFFF',
  cardBorder: 'rgba(0,0,0,0.07)',
};

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

const DEFAULT_POST_LIMIT = 20;

const AppDetail = ({ navigation, route }) => {
  const { packageName } = route.params;
  const insets = useSafeAreaInsets();

  const appInfo = MANAGED_APPS.find(a => a.pkg === packageName);

  // Settings Change Lock for THIS app's scope (its package name). Editing any
  // control marks the scope dirty; leaving the screen then starts the lock if the
  // feature is enabled. Independent of the global scope and other apps.
  const settingsLock = useSettingsLock(packageName, navigation);
  const { markDirty: markSettingsDirty } = settingsLock;

  const [policy, setPolicy] = useState({});
  const [postLimit, setPostLimit] = useState(DEFAULT_POST_LIMIT);
  const [activeModeId, setActiveModeId] = useState(null);
  const [modes, setModes] = useState({});

  // ── Per-app intercept settings ─────────────────────────────────────────────
  const [interceptMessage, setInterceptMessage] = useState('');
  const [interceptDelaySecs, setInterceptDelaySecs] = useState(15);
  const [interceptFreqMode, setInterceptFreqMode] = useState('repeat'); // 'once' | 'repeat'
  const [interceptRepeatMin, setInterceptRepeatMin] = useState(10);
  const [showApplyAllModal, setShowApplyAllModal] = useState(false);
  const interceptSaveTimer = useRef(null);

  useEffect(() => {
    console.log('[AppDetail] loading policy for', packageName);
    const loadData = async () => {
      try {
        const activeId = await new Promise(resolve =>
          SettingsModule.getActiveMode(resolve),
        );
        setActiveModeId(activeId || null);

        const modesJson = await new Promise(resolve =>
          SettingsModule.getModes(resolve),
        );
        let parsedModes = {};
        try {
          parsedModes = JSON.parse(modesJson || '{}');
        } catch (e) {}
        setModes(parsedModes);

        const policiesJson = await new Promise(resolve =>
          SettingsModule.getAppPolicies(resolve),
        );
        let basePolicies = {};
        try {
          basePolicies = JSON.parse(policiesJson || '{}');
        } catch (e) {}

        let p = basePolicies[packageName] || {};

        // If a mode is active, layer its overrides on top so the UI reflects the true effective state
        if (activeId && parsedModes[activeId]?.policy_overrides) {
          const overrides =
            parsedModes[activeId].policy_overrides[packageName] || {};
          p = { ...p, ...overrides };
        }

        setPolicy(p);

        if (typeof p.session_post_limit === 'number') {
          setPostLimit(p.session_post_limit);
        } else if (packageName === 'com.instagram.android') {
          SettingsModule.getHomeFeedPostLimit(v => {
            setPostLimit(typeof v === 'number' ? v : DEFAULT_POST_LIMIT);
          });
        }

        console.log('[AppDetail] effective policy loaded:', JSON.stringify(p));

        // Load per-app intercept settings
        try {
          const s = await SettingsModule.getAppInterceptSettings(packageName);
          setInterceptMessage(s.message ?? '');
          setInterceptDelaySecs(
            typeof s.delaySecs === 'number' ? s.delaySecs : 15,
          );
          if (s.popupDelayMin === POPUP_DELAY_ONCE_SENTINEL) {
            setInterceptFreqMode('once');
            setInterceptRepeatMin(10);
          } else {
            setInterceptFreqMode('repeat');
            setInterceptRepeatMin(s.popupDelayMin ?? 10);
          }
        } catch (e) {
          console.warn('[AppDetail] load intercept settings failed:', e);
        }
      } catch (e) {
        console.warn('[AppDetail] load data failed:', e);
      }
    };
    loadData();
  }, [packageName]);

  const saveInterceptSettings = useCallback(
    async (msg, secs, mode, repeatMin) => {
      const delayMin = mode === 'once' ? POPUP_DELAY_ONCE_SENTINEL : repeatMin;
      const roundedSecs = Math.round(secs);
      try {
        await SettingsModule.setAppInterceptSettings(
          packageName,
          msg,
          roundedSecs,
          delayMin,
        );
        console.log(
          '[AppDetail] intercept settings saved pkg=' +
            packageName +
            ' secs=' +
            roundedSecs +
            ' delayMin=' +
            delayMin,
        );
      } catch (e) {
        console.warn('[AppDetail] save intercept settings failed:', e);
      }
    },
    [packageName],
  );

  const scheduleInterceptSave = useCallback(
    (msg, secs, mode, repeatMin) => {
      markSettingsDirty();
      if (interceptSaveTimer.current) clearTimeout(interceptSaveTimer.current);
      interceptSaveTimer.current = setTimeout(() => {
        saveInterceptSettings(msg, secs, mode, repeatMin);
      }, 1500);
    },
    [saveInterceptSettings, markSettingsDirty],
  );

  // Flush pending intercept settings on unmount (message / frequency edits)
  useEffect(
    () => () => {
      if (interceptSaveTimer.current) {
        clearTimeout(interceptSaveTimer.current);
        interceptSaveTimer.current = null;
        saveInterceptSettings(
          interceptMessage,
          interceptDelaySecs,
          interceptFreqMode,
          interceptRepeatMin,
        );
      }
    },
    [
      saveInterceptSettings,
      interceptMessage,
      interceptDelaySecs,
      interceptFreqMode,
      interceptRepeatMin,
    ],
  );

  const applyInterceptToAll = useCallback(async () => {
    const delayMin =
      interceptFreqMode === 'once'
        ? POPUP_DELAY_ONCE_SENTINEL
        : interceptRepeatMin;
    try {
      await SettingsModule.setAllAppsInterceptSettings(
        interceptMessage,
        interceptDelaySecs,
        delayMin,
      );
      console.log('[AppDetail] intercept settings applied to all apps');
    } catch (e) {
      console.warn('[AppDetail] apply to all failed:', e);
    }
  }, [
    interceptMessage,
    interceptDelaySecs,
    interceptFreqMode,
    interceptRepeatMin,
  ]);

  const setFeature = useCallback(
    async (key, value) => {
      console.log('[AppDetail] setFeature', packageName, key, '→', value);
      markSettingsDirty();
      setPolicy(prev => ({ ...prev, [key]: value }));
      try {
        // Update base policy
        await SettingsModule.setAppFeature(packageName, key, value);

        // If a mode is active, propagate the change so it persists in the mode
        if (activeModeId && modes[activeModeId]) {
          const updatedModes = { ...modes };
          if (!updatedModes[activeModeId].policy_overrides) {
            updatedModes[activeModeId].policy_overrides = {};
          }
          if (!updatedModes[activeModeId].policy_overrides[packageName]) {
            updatedModes[activeModeId].policy_overrides[packageName] = {};
          }
          updatedModes[activeModeId].policy_overrides[packageName][key] = value;
          setModes(updatedModes);
          SettingsModule.saveModes(JSON.stringify(updatedModes));

          // Re-trigger activation to sync blocked_apps natively
          VPNModule.activateMode(activeModeId).catch(() => {});
        }

        if (key === 'app_open_intercept') {
          VPNModule.startMonitoring().catch(() => {});
        }
        // Sync the global free_break_enabled pref so Home's getFreeBreakStatus()
        // picks up the change (it reads from the global pref, not per-app policy).
        if (key === 'free_break_enabled') {
          SettingsModule.saveFreeBreakEnabled(value);
        }
      } catch (e) {
        console.error('[AppDetail] setAppFeature failed:', e);
      }
    },
    [packageName, activeModeId, modes, markSettingsDirty],
  );

  const stepperFeature = appInfo?.features.find(
    f => f.key === 'session_post_limit',
  );

  const adjustPostLimit = useCallback(
    delta => {
      if (!stepperFeature) {
        return;
      }
      const next = Math.max(
        stepperFeature.min,
        Math.min(stepperFeature.max, postLimit + delta * stepperFeature.step),
      );
      setPostLimit(next);
      markSettingsDirty();
      console.log('[AppDetail] session_post_limit →', next);
      SettingsModule.setAppFeature(packageName, 'session_post_limit', next);

      // If a mode is active, propagate the change so it persists in the mode
      if (activeModeId && modes[activeModeId]) {
        const updatedModes = { ...modes };
        if (!updatedModes[activeModeId].policy_overrides) {
          updatedModes[activeModeId].policy_overrides = {};
        }
        if (!updatedModes[activeModeId].policy_overrides[packageName]) {
          updatedModes[activeModeId].policy_overrides[packageName] = {};
        }
        updatedModes[activeModeId].policy_overrides[packageName][
          'session_post_limit'
        ] = next;
        setModes(updatedModes);
        SettingsModule.saveModes(JSON.stringify(updatedModes));

        VPNModule.activateMode(activeModeId).catch(() => {});
      }

      if (packageName === 'com.instagram.android') {
        SettingsModule.saveHomeFeedPostLimit(next);
      }
    },
    [
      packageName,
      postLimit,
      stepperFeature,
      activeModeId,
      modes,
      markSettingsDirty,
    ],
  );

  if (!appInfo) {
    return null;
  }

  const isEnabled = policy.enabled !== false;
  const toggleFeatures = appInfo.features.filter(f => f.kind !== 'stepper');
  const hasSafeMode = Boolean(appInfo.safeModePlatform);

  return (
    <View style={[styles.container, { paddingTop: Math.max(insets.top, 0) }]}>
      {/* ── Header ── */}
      <View style={styles.header}>
        <TouchableOpacity
          style={styles.backButton}
          onPress={() => navigation.goBack()}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel="Back"
        >
          <BackIcon color={L.charcoal} size={22} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{appInfo.label}</Text>
        <View style={styles.headerSpacer} />
      </View>

      <ScrollView
        style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}
      >
        {settingsLock.locked ? (
          <SettingsLockGate
            remainingMs={settingsLock.remainingMs}
            scopeLabel={appInfo.label}
          />
        ) : (
          <>
            {/* ── Master toggle ── */}
            <View style={styles.section}>
              <View style={styles.toggleRow}>
                <View style={styles.toggleLabelGroup}>
                  <Text style={styles.toggleLabel}>Enable</Text>
                  <Text style={styles.toggleCaption}>
                    Turn off to disable all interventions for {appInfo.label}.
                  </Text>
                </View>
                <Switch
                  value={isEnabled}
                  onValueChange={val => setFeature('enabled', val)}
                  trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                  thumbColor="#FFFFFF"
                  accessibilityLabel={`Enable ${appInfo.label} interventions`}
                />
              </View>
            </View>

            {/* ── Per-app controls (gated on master toggle) ── */}
            {isEnabled && (
              <>
                {/* App Open Intercept */}
                <View style={styles.section}>
                  <Text style={styles.sectionLabel}>App Open</Text>
                  <View style={styles.toggleRow}>
                    <View style={styles.toggleLabelGroup}>
                      <Text style={styles.toggleLabel}>App Open Intercept</Text>
                      <Text style={styles.toggleCaption}>
                        Show a delay overlay every time you open {appInfo.label}
                        .
                      </Text>
                    </View>
                    <Switch
                      value={policy.app_open_intercept === true}
                      onValueChange={val =>
                        setFeature('app_open_intercept', val)
                      }
                      trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                      thumbColor="#FFFFFF"
                      accessibilityLabel="App Open Intercept"
                    />
                  </View>

                  {/* Per-app intercept customization — only shown when intercept is on */}
                  {policy.app_open_intercept === true && (
                    <View style={styles.interceptBox}>
                      {/* Message */}
                      <Text style={styles.interceptFieldLabel}>
                        Overlay message
                      </Text>
                      <TextInput
                        style={styles.interceptInput}
                        value={interceptMessage}
                        onChangeText={text => {
                          setInterceptMessage(text);
                          scheduleInterceptSave(
                            text,
                            interceptDelaySecs,
                            interceptFreqMode,
                            interceptRepeatMin,
                          );
                        }}
                        placeholder="Take a moment before opening this app…"
                        placeholderTextColor={L.muted}
                        multiline
                        maxLength={120}
                      />

                      {/* Duration */}
                      <View style={styles.interceptRow}>
                        <Text style={styles.interceptFieldLabel}>
                          Countdown
                        </Text>
                        <Text style={styles.interceptValue}>
                          {interceptDelaySecs}s
                        </Text>
                      </View>
                      <Slider
                        style={styles.interceptSlider}
                        minimumValue={5}
                        maximumValue={30}
                        step={1}
                        value={interceptDelaySecs}
                        onValueChange={v =>
                          setInterceptDelaySecs(Math.round(v))
                        }
                        onSlidingComplete={v => {
                          const rounded = Math.round(v);
                          setInterceptDelaySecs(rounded);
                          if (interceptSaveTimer.current) {
                            clearTimeout(interceptSaveTimer.current);
                            interceptSaveTimer.current = null;
                          }
                          saveInterceptSettings(
                            interceptMessage,
                            rounded,
                            interceptFreqMode,
                            interceptRepeatMin,
                          );
                        }}
                        minimumTrackTintColor={L.charcoal}
                        maximumTrackTintColor={L.border}
                        thumbTintColor={L.charcoal}
                      />

                      {/* Frequency */}
                      <Text
                        style={[styles.interceptFieldLabel, { marginTop: 6 }]}
                      >
                        Re-show overlay
                      </Text>
                      <View style={styles.interceptSegment}>
                        <TouchableOpacity
                          style={[
                            styles.interceptSegBtn,
                            interceptFreqMode === 'once' &&
                              styles.interceptSegBtnActive,
                          ]}
                          onPress={() => {
                            setInterceptFreqMode('once');
                            scheduleInterceptSave(
                              interceptMessage,
                              interceptDelaySecs,
                              'once',
                              interceptRepeatMin,
                            );
                          }}
                          activeOpacity={0.75}
                        >
                          <Text
                            style={[
                              styles.interceptSegText,
                              interceptFreqMode === 'once' &&
                                styles.interceptSegTextActive,
                            ]}
                          >
                            Once per open
                          </Text>
                        </TouchableOpacity>
                        <TouchableOpacity
                          style={[
                            styles.interceptSegBtn,
                            interceptFreqMode === 'repeat' &&
                              styles.interceptSegBtnActive,
                          ]}
                          onPress={() => {
                            setInterceptFreqMode('repeat');
                            scheduleInterceptSave(
                              interceptMessage,
                              interceptDelaySecs,
                              'repeat',
                              interceptRepeatMin,
                            );
                          }}
                          activeOpacity={0.75}
                        >
                          <Text
                            style={[
                              styles.interceptSegText,
                              interceptFreqMode === 'repeat' &&
                                styles.interceptSegTextActive,
                            ]}
                          >
                            Every X min
                          </Text>
                        </TouchableOpacity>
                      </View>

                      {interceptFreqMode === 'repeat' && (
                        <>
                          <View style={styles.interceptRow}>
                            <Text style={styles.interceptFieldLabel}>
                              Repeat interval
                            </Text>
                            <Text style={styles.interceptValue}>
                              {interceptRepeatMin}m
                            </Text>
                          </View>
                          <Slider
                            style={styles.interceptSlider}
                            minimumValue={1}
                            maximumValue={60}
                            step={1}
                            value={interceptRepeatMin}
                            onValueChange={v => {
                              setInterceptRepeatMin(v);
                              scheduleInterceptSave(
                                interceptMessage,
                                interceptDelaySecs,
                                interceptFreqMode,
                                v,
                              );
                            }}
                            minimumTrackTintColor={L.charcoal}
                            maximumTrackTintColor={L.border}
                            thumbTintColor={L.charcoal}
                          />
                        </>
                      )}

                      {/* Apply to all */}
                      <TouchableOpacity
                        style={styles.applyAllButton}
                        onPress={() => setShowApplyAllModal(true)}
                        activeOpacity={0.75}
                      >
                        <Text style={styles.applyAllButtonText}>
                          Apply to all apps
                        </Text>
                      </TouchableOpacity>
                    </View>
                  )}
                </View>

                {/* Apply-to-all confirmation modal */}
                <Modal
                  visible={showApplyAllModal}
                  transparent
                  animationType="fade"
                  onRequestClose={() => setShowApplyAllModal(false)}
                >
                  <View style={styles.modalBackdrop}>
                    <View style={styles.modalCard}>
                      <Text style={styles.modalTitle}>Apply to all apps?</Text>
                      <Text style={styles.modalBody}>
                        This will overwrite the intercept message, countdown,
                        and re-show settings for every managed app with{' '}
                        {appInfo.label}
                        's current values.
                      </Text>
                      <View style={styles.modalActions}>
                        <TouchableOpacity
                          style={styles.modalCancel}
                          onPress={() => setShowApplyAllModal(false)}
                          activeOpacity={0.75}
                        >
                          <Text style={styles.modalCancelText}>Cancel</Text>
                        </TouchableOpacity>
                        <TouchableOpacity
                          style={styles.modalConfirm}
                          onPress={async () => {
                            setShowApplyAllModal(false);
                            await applyInterceptToAll();
                          }}
                          activeOpacity={0.75}
                        >
                          <Text style={styles.modalConfirmText}>Apply</Text>
                        </TouchableOpacity>
                      </View>
                    </View>
                  </View>
                </Modal>

                {/* Feature toggles */}
                {toggleFeatures.length > 0 && (
                  <View style={styles.section}>
                    <Text style={styles.sectionLabel}>Interventions</Text>
                    {toggleFeatures.map((feature, i) => (
                      <View
                        key={feature.key}
                        style={[
                          styles.toggleRow,
                          i > 0 && styles.toggleRowDivided,
                        ]}
                      >
                        <Text style={styles.toggleLabel}>{feature.label}</Text>
                        <Switch
                          value={policy[feature.key] === true}
                          onValueChange={val => setFeature(feature.key, val)}
                          trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                          thumbColor="#FFFFFF"
                          accessibilityLabel={feature.label}
                        />
                      </View>
                    ))}
                  </View>
                )}

                {/* Session post limit stepper */}
                {stepperFeature && (
                  <View style={styles.section}>
                    <Text style={styles.sectionLabel}>
                      {stepperFeature.label}
                    </Text>
                    <Text style={styles.sectionCaption}>
                      After this many posts, you'll be prompted to stop
                      scrolling.
                    </Text>
                    <View style={styles.stepperRow}>
                      <TouchableOpacity
                        style={styles.stepperBtn}
                        onPress={() => adjustPostLimit(-1)}
                        accessibilityRole="button"
                        accessibilityLabel="Decrease limit"
                      >
                        <Text style={styles.stepperBtnText}>−</Text>
                      </TouchableOpacity>
                      <Text style={styles.stepperValue}>{postLimit}</Text>
                      <TouchableOpacity
                        style={styles.stepperBtn}
                        onPress={() => adjustPostLimit(1)}
                        accessibilityRole="button"
                        accessibilityLabel="Increase limit"
                      >
                        <Text style={styles.stepperBtnText}>+</Text>
                      </TouchableOpacity>
                      <Text style={styles.stepperUnit}>posts</Text>
                    </View>
                  </View>
                )}

                {/* 20-Min Free Break */}
                <View style={styles.section}>
                  <Text style={styles.sectionLabel}>Free Break</Text>
                  <View style={styles.toggleRow}>
                    <View style={styles.toggleLabelGroup}>
                      <Text style={styles.toggleLabel}>20-Min Free Break</Text>
                      <Text style={styles.toggleCaption}>
                        Once per day — scroll freely for 20 min with no
                        interruptions.
                      </Text>
                    </View>
                    <Switch
                      value={policy.free_break_enabled === true}
                      onValueChange={val =>
                        setFeature('free_break_enabled', val)
                      }
                      trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                      thumbColor="#FFFFFF"
                      accessibilityLabel="20-Minute Free Break"
                    />
                  </View>
                </View>

                {/* Safe Mode */}
                {hasSafeMode && (
                  <View style={styles.section}>
                    <Text style={styles.sectionLabel}>Safe Mode</Text>
                    <Text style={styles.sectionCaption}>
                      Opens {appInfo.label} through a restricted browser — no
                      Reels or Shorts.
                    </Text>
                    <TouchableOpacity
                      style={styles.safeModeButton}
                      activeOpacity={0.85}
                      onPress={() => {
                        console.log(
                          '[AppDetail] Open Safe Mode:',
                          appInfo.safeModePlatform,
                        );
                        navigation.navigate('Browser', {
                          platform: appInfo.safeModePlatform,
                        });
                      }}
                      accessibilityRole="button"
                      accessibilityLabel={`Open ${appInfo.label} in Safe Mode`}
                    >
                      <Text style={styles.safeModeButtonText}>
                        Open in Safe Mode
                      </Text>
                    </TouchableOpacity>
                  </View>
                )}
              </>
            )}
          </>
        )}
      </ScrollView>
    </View>
  );
};

export default AppDetail;

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: L.bg,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 14,
    backgroundColor: L.bg,
    borderBottomWidth: 1,
    borderBottomColor: L.border,
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

  scroll: { flex: 1 },
  scrollContent: {
    paddingHorizontal: 24,
    paddingTop: 28,
    paddingBottom: 48,
  },

  section: { marginBottom: 32 },
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
    marginBottom: 14,
    marginTop: -4,
  },

  toggleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 4,
  },
  toggleRowDivided: {
    marginTop: 12,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: L.border,
  },
  toggleLabelGroup: {
    flex: 1,
    marginRight: 12,
  },
  toggleLabel: {
    fontSize: 16,
    color: L.charcoal,
    fontWeight: '400',
  },
  toggleCaption: {
    fontSize: 12,
    color: L.muted,
    marginTop: 3,
    lineHeight: 17,
  },

  stepperRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  stepperBtn: {
    width: 36,
    height: 36,
    borderRadius: 18,
    borderWidth: 1,
    borderColor: L.border,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: L.cardBg,
  },
  stepperBtnText: {
    fontSize: 20,
    color: L.charcoal,
    lineHeight: 24,
  },
  stepperValue: {
    fontSize: 22,
    fontWeight: '300',
    color: L.charcoal,
    minWidth: 40,
    textAlign: 'center',
  },
  stepperUnit: {
    fontSize: 14,
    color: L.muted,
  },

  safeModeButton: {
    backgroundColor: L.charcoal,
    borderRadius: 9999,
    paddingVertical: 14,
    paddingHorizontal: 28,
    alignItems: 'center',
    alignSelf: 'flex-start',
  },
  safeModeButtonText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '500',
  },

  // ── Per-app intercept customization ─────────────────────────────────────
  interceptBox: {
    marginTop: 16,
    padding: 16,
    backgroundColor: L.cardBg,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: L.cardBorder,
  },
  interceptFieldLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: L.muted,
    textTransform: 'uppercase',
    letterSpacing: 1,
    marginBottom: 6,
  },
  interceptInput: {
    borderWidth: 1,
    borderColor: L.border,
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
    fontSize: 14,
    color: L.charcoal,
    marginBottom: 16,
    minHeight: 60,
    textAlignVertical: 'top',
  },
  interceptRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 4,
  },
  interceptValue: {
    fontSize: 14,
    fontWeight: '500',
    color: L.charcoal,
  },
  interceptSlider: {
    width: '100%',
    height: 36,
    marginBottom: 12,
  },
  interceptSegment: {
    flexDirection: 'row',
    borderWidth: 1,
    borderColor: L.border,
    borderRadius: 8,
    overflow: 'hidden',
    marginBottom: 12,
  },
  interceptSegBtn: {
    flex: 1,
    paddingVertical: 8,
    alignItems: 'center',
    backgroundColor: L.cardBg,
  },
  interceptSegBtnActive: {
    backgroundColor: L.charcoal,
  },
  interceptSegText: {
    fontSize: 13,
    color: L.muted,
    fontWeight: '500',
  },
  interceptSegTextActive: {
    color: '#FFFFFF',
  },
  applyAllButton: {
    marginTop: 8,
    borderWidth: 1,
    borderColor: L.border,
    borderRadius: 8,
    paddingVertical: 10,
    alignItems: 'center',
  },
  applyAllButtonText: {
    fontSize: 14,
    color: L.charcoal,
    fontWeight: '500',
  },

  // ── Apply-to-all modal ───────────────────────────────────────────────────
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.45)',
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 32,
  },
  modalCard: {
    width: '100%',
    backgroundColor: L.cardBg,
    borderRadius: 16,
    padding: 24,
  },
  modalTitle: {
    fontSize: 17,
    fontWeight: '600',
    color: L.charcoal,
    marginBottom: 10,
  },
  modalBody: {
    fontSize: 14,
    color: L.muted,
    lineHeight: 20,
    marginBottom: 20,
  },
  modalActions: {
    flexDirection: 'row',
    gap: 12,
  },
  modalCancel: {
    flex: 1,
    borderWidth: 1,
    borderColor: L.border,
    borderRadius: 9999,
    paddingVertical: 12,
    alignItems: 'center',
  },
  modalCancelText: {
    fontSize: 15,
    color: L.charcoal,
    fontWeight: '500',
  },
  modalConfirm: {
    flex: 1,
    backgroundColor: L.charcoal,
    borderRadius: 9999,
    paddingVertical: 12,
    alignItems: 'center',
  },
  modalConfirmText: {
    fontSize: 15,
    color: '#FFFFFF',
    fontWeight: '500',
  },
});
