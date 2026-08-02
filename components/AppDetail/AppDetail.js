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
 * Save model: MANUAL. Every control edits LOCAL state only and marks the
 * screen dirty — nothing is written to the native layer until the user taps
 * "Save changes". Saving persists everything, then arms the per-app Settings
 * Change Lock for this scope (so no rash follow-up edits). Leaving with unsaved
 * changes prompts Save / Discard / Keep editing. This replaced an auto-save +
 * debounce model that raced itself and dropped edits (notably YouTube, whose
 * only settings live in the intercept box).
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
  Alert,
  NativeModules,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Path } from 'react-native-svg';
import { MANAGED_APPS } from '../managedApps/manifest';
import useSettingsLock from '../Customize/useSettingsLock';
import SettingsLockGate from '../Customize/SettingsLockGate';
import { saveAppSettingsToActiveMode } from '../shared/activeModeSettings';
import { styles, L } from './AppDetail.styles';
import InterceptCustomization from './InterceptCustomization';
import ApplyAllModal from './ApplyAllModal';

const { VPNModule, SettingsModule } = NativeModules;

const POPUP_DELAY_ONCE_SENTINEL = 2147483647;

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

  // Settings Change Lock for THIS app's scope (its package name). We opt OUT of
  // auto-lock-on-leave: the lock is armed explicitly from handleSave() instead,
  // so it triggers on an intentional Save rather than on plain exit.
  const settingsLock = useSettingsLock(packageName, navigation, {
    autoLockOnLeave: false,
  });

  // No mode gate any more: this screen edits whatever mode is ACTIVE. Toggles
  // here write into the active mode's policy_overrides (see handleSave), so a
  // write is never masked by the active mode — there is nothing to lock.

  const [policy, setPolicy] = useState({});
  const [postLimit, setPostLimit] = useState(DEFAULT_POST_LIMIT);
  // NOTE: the active mode / modes JSON are read locally inside the load effect to
  // layer overrides onto the displayed policy. They are deliberately NOT held in
  // state any more — the only consumer was the write-into-the-active-mode path in
  // handleSave, which the Default-mode gate replaced.

  // ── Per-app intercept settings ─────────────────────────────────────────────
  const [interceptMessage, setInterceptMessage] = useState('');
  const [interceptDelaySecs, setInterceptDelaySecs] = useState(15);
  const [interceptFreqMode, setInterceptFreqMode] = useState('repeat'); // 'once' | 'repeat'
  const [interceptRepeatMin, setInterceptRepeatMin] = useState(10);
  const [showApplyAllModal, setShowApplyAllModal] = useState(false);

  // ── Typing Coach (YouTube only) ────────────────────────────────────────────
  // Coach ON → YouTube's intercept is the typing gate (launch + every X min);
  // coach OFF → the normal delay overlay, like every other app.
  const isYouTube = packageName === 'com.google.android.youtube';
  const [coachEnabled, setCoachEnabled] = useState(true);

  // Unsaved-changes tracking. `dirty` drives the Save button + leave prompt;
  // `dirtyRef` mirrors it so the beforeRemove listener reads the latest value
  // without re-subscribing every render.
  const [dirty, setDirty] = useState(false);
  const dirtyRef = useRef(false);
  const markDirty = useCallback(() => {
    dirtyRef.current = true;
    setDirty(true);
  }, []);

  useEffect(() => {
    console.log('[AppDetail] loading policy for', packageName);
    const loadData = async () => {
      try {
        const activeId = await new Promise(resolve =>
          SettingsModule.getActiveMode(resolve),
        );

        const modesJson = await new Promise(resolve =>
          SettingsModule.getModes(resolve),
        );
        let parsedModes = {};
        try {
          parsedModes = JSON.parse(modesJson || '{}');
        } catch (e) {}

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

        // TEMP: Typing Coach load disabled — revive later with isCoachEnabled.
        // if (isYouTube) {
        //   try {
        //     const enabled = await SettingsModule.getCoachEnabled();
        //     setCoachEnabled(enabled === true);
        //   } catch (e) {
        //     console.warn('[AppDetail] load coach enabled failed:', e);
        //   }
        // }

        // Freshly loaded state is the saved baseline — not dirty.
        dirtyRef.current = false;
        setDirty(false);
      } catch (e) {
        console.warn('[AppDetail] load data failed:', e);
      }
    };
    loadData();
  }, [packageName, isYouTube]);

  const stepperFeature = appInfo?.features.find(
    f => f.key === 'session_post_limit',
  );

  // ── Local-only editors (persistence happens in handleSave) ──────────────────

  const setFeature = useCallback(
    (key, value) => {
      console.log(
        '[AppDetail] edit',
        packageName,
        key,
        '→',
        value,
        '(pending)',
      );
      setPolicy(prev => ({ ...prev, [key]: value }));
      markDirty();
    },
    [packageName, markDirty],
  );

  const adjustPostLimit = useCallback(
    delta => {
      if (!stepperFeature) {
        return;
      }
      setPostLimit(prev => {
        const next = Math.max(
          stepperFeature.min,
          Math.min(stepperFeature.max, prev + delta * stepperFeature.step),
        );
        console.log('[AppDetail] session_post_limit →', next, '(pending)');
        return next;
      });
      markDirty();
    },
    [stepperFeature, markDirty],
  );

  // ── Persist everything, then arm the lock ───────────────────────────────────

  const handleSave = useCallback(async () => {
    console.log('[AppDetail] saving all settings for', packageName);

    try {
      // 1. Per-app policy + forced-pause → written into the ACTIVE mode's
      //    overrides (whatever mode is on, including Default). This is the single
      //    write path now; there is no separate base app_policies write, so a
      //    toggle can never be masked by the active mode.
      const modeId = await saveAppSettingsToActiveMode(
        packageName,
        policy,
        interceptDelaySecs,
      );
      console.log('[AppDetail] wrote', packageName, 'into mode', modeId);

      // 2. session_post_limit is a NUMBER (not a policy boolean) — the helper
      //    above ignores it; it feeds the global home-feed limit in step 4.

      // 3. Sync the global free_break_enabled pref so Home's getFreeBreakStatus()
      //    (which reads the global pref, not per-app policy) stays in step.
      if (typeof policy.free_break_enabled === 'boolean') {
        SettingsModule.saveFreeBreakEnabled(policy.free_break_enabled);
      }

      // 4. Instagram session_post_limit also feeds the legacy home-feed limit.
      if (stepperFeature && packageName === 'com.instagram.android') {
        SettingsModule.saveHomeFeedPostLimit(postLimit);
      }

      // 5. Per-app intercept settings (message / countdown / re-show frequency).
      const delayMin =
        interceptFreqMode === 'once'
          ? POPUP_DELAY_ONCE_SENTINEL
          : interceptRepeatMin;
      await SettingsModule.setAppInterceptSettings(
        packageName,
        interceptMessage,
        Math.round(interceptDelaySecs),
        delayMin,
      );

      // TEMP: Typing Coach save disabled — revive later with isCoachEnabled.
      // if (isYouTube) {
      //   await SettingsModule.setCoachEnabled(coachEnabled);
      // }

      // 7. Restart monitoring so an intercept toggle takes effect immediately.
      if (policy.app_open_intercept === true) {
        VPNModule.startMonitoring().catch(() => {});
      }

      console.log('[AppDetail] save complete for', packageName);

      dirtyRef.current = false;
      setDirty(false);

      // 8. Arm the Settings Change Lock for this scope (no-op if disabled).
      settingsLock.startLock();
      return true;
    } catch (e) {
      console.error('[AppDetail] save failed:', e);
      Alert.alert('Save failed', 'Could not save your changes. Try again.');
      return false;
    }
  }, [
    packageName,
    policy,
    postLimit,
    stepperFeature,
    interceptMessage,
    interceptDelaySecs,
    interceptFreqMode,
    interceptRepeatMin,
    settingsLock,
  ]);

  // Warn before leaving with unsaved changes (header back, hardware back, swipe).
  useEffect(() => {
    const unsub = navigation.addListener('beforeRemove', e => {
      if (!dirtyRef.current) {
        return;
      }
      e.preventDefault();
      Alert.alert(
        'Unsaved changes',
        'Your changes to ' +
          (appInfo?.label || 'this app') +
          " haven't been saved yet.",
        [
          { text: 'Keep editing', style: 'cancel', onPress: () => {} },
          {
            text: 'Discard',
            style: 'destructive',
            onPress: () => {
              dirtyRef.current = false;
              setDirty(false);
              navigation.dispatch(e.data.action);
            },
          },
          {
            text: 'Save',
            onPress: async () => {
              const ok = await handleSave();
              if (ok) {
                navigation.dispatch(e.data.action);
              }
            },
          },
        ],
      );
    });
    return unsub;
  }, [navigation, handleSave, appInfo]);

  const applyInterceptToAll = useCallback(async () => {
    const delayMin =
      interceptFreqMode === 'once'
        ? POPUP_DELAY_ONCE_SENTINEL
        : interceptRepeatMin;
    try {
      await SettingsModule.setAllAppsInterceptSettings(
        interceptMessage,
        Math.round(interceptDelaySecs),
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
                    <InterceptCustomization
                      interceptMessage={interceptMessage}
                      setInterceptMessage={setInterceptMessage}
                      interceptDelaySecs={interceptDelaySecs}
                      setInterceptDelaySecs={setInterceptDelaySecs}
                      interceptFreqMode={interceptFreqMode}
                      setInterceptFreqMode={setInterceptFreqMode}
                      interceptRepeatMin={interceptRepeatMin}
                      setInterceptRepeatMin={setInterceptRepeatMin}
                      // TEMP: Typing Coach toggle hidden — revive later
                      showCoachToggle={false}
                      coachEnabled={coachEnabled}
                      setCoachEnabled={setCoachEnabled}
                      onEdit={markDirty}
                      onApplyAll={() => setShowApplyAllModal(true)}
                    />
                  )}
                </View>

                {/* Apply-to-all confirmation modal */}
                <ApplyAllModal
                  visible={showApplyAllModal}
                  appLabel={appInfo.label}
                  onCancel={() => setShowApplyAllModal(false)}
                  onConfirm={async () => {
                    setShowApplyAllModal(false);
                    await applyInterceptToAll();
                  }}
                />

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

      {/* ── Sticky Save bar ──
          Hidden only while the settings-lock gate is showing (no form to save). */}
      {!settingsLock.locked && (
        <View
          style={[
            styles.saveBar,
            { paddingBottom: Math.max(insets.bottom, 12) },
          ]}
        >
          <TouchableOpacity
            style={[styles.saveButton, !dirty && styles.saveButtonDisabled]}
            onPress={handleSave}
            disabled={!dirty}
            activeOpacity={0.85}
            accessibilityRole="button"
            accessibilityState={{ disabled: !dirty }}
            accessibilityLabel={dirty ? 'Save changes' : 'No changes to save'}
          >
            <Text
              style={[
                styles.saveButtonText,
                !dirty && styles.saveButtonTextDisabled,
              ]}
            >
              {dirty ? 'Save changes' : 'Saved'}
            </Text>
          </TouchableOpacity>
        </View>
      )}
    </View>
  );
};

export default AppDetail;
