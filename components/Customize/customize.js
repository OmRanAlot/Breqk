/**
 * customize.js — Customize Screen (Tether light design system)
 * ─────────────────────────────────────────────────────────────────────────────
 * Settings screen layout:
 *   • Sticky header: back button + "Customize" title
 *   • "Intervention Modes" section — simple toggles (App Open Intercept,
 *       Reels Detection, 20-Min Free Break) — these are the BASE settings
 *   • "Custom Modes" section — optional timed overlays (Study, Bedtime, etc.)
 *       When a mode is active it overrides the base settings above.
 *   • "Scroll Budget" section — only visible when Reels Detection is on
 *   • "Intercept Message" section — text input, duration slider, preview button
 *   • Version footer
 *
 * State wired to VPNModule (monitoring, delay, modes) and SettingsModule (redirect, modes).
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
  LayoutAnimation,
  Platform,
  UIManager,
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

// Enable LayoutAnimation on Android
if (
  Platform.OS === 'android' &&
  UIManager.setLayoutAnimationEnabledExperimental
) {
  UIManager.setLayoutAnimationEnabledExperimental(true);
}

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

// ─── Constants ───────────────────────────────────────────────────────────────

/** Mode icon map — maps icon identifier to emoji for v1 simplicity. */
const MODE_ICONS = {
  book: '📖',
  moon: '🌙',
  dumbbell: '💪',
  focus: '🎯',
  default: '⚡',
};

/** Day labels for schedule day picker. Index matches 0=Sun..6=Sat. */
const DAY_LABELS = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];

/** Default modes shipped on first launch. */
const DEFAULT_MODES = {
  study: {
    name: 'Study Mode',
    icon: 'book',
    color: '#FF9800',
    enabled: false,
    policy_overrides: {
      'com.instagram.android': { app_open_intercept: true },
      'com.google.android.youtube': { app_open_intercept: true },
    },
    setting_overrides: { delay_time_seconds: 20 },
    schedule: null,
  },
  bedtime: {
    name: 'Bedtime',
    icon: 'moon',
    color: '#7C4DFF',
    enabled: false,
    policy_overrides: {
      'com.instagram.android': {
        app_open_intercept: true,
        reels_detection: true,
      },
      'com.google.android.youtube': {
        app_open_intercept: true,
        reels_detection: true,
      },
    },
    setting_overrides: { delay_time_seconds: 20 },
    schedule: {
      start_time: '22:00',
      end_time: '07:00',
      days: [0, 1, 2, 3, 4, 5, 6],
    },
  },
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

/**
 * Generates a one-line summary of a mode's configuration.
 */
const generateModeSummary = mode => {
  const parts = [];
  const overrides = mode.policy_overrides || {};
  const settings = mode.setting_overrides || {};

  let interceptCount = 0;
  let reelsCount = 0;
  for (const pkg of Object.keys(overrides)) {
    if (overrides[pkg]?.app_open_intercept) interceptCount++;
    if (overrides[pkg]?.reels_detection) reelsCount++;
  }

  if (interceptCount > 0 && reelsCount > 0) {
    parts.push('Full blocking');
  } else if (interceptCount > 0) {
    parts.push('Intercepts on all apps');
  } else if (reelsCount > 0) {
    parts.push('Reels blocking');
  }

  if (settings.delay_time_seconds) {
    parts.push(settings.delay_time_seconds + 's delay');
  }

  if (mode.schedule) {
    parts.push(mode.schedule.start_time + '–' + mode.schedule.end_time);
  } else if (mode.enabled) {
    parts.push('Manual');
  }

  return parts.join(', ') || 'No overrides';
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

// ─── ModeCard ────────────────────────────────────────────────────────────────

/**
 * ModeCard — Custom mode card with enable/disable toggle and optional inline editor.
 *
 * Collapsed: icon + name + summary + enabled toggle + "Edit ▸"
 * Expanded: name input, per-app overrides, delay override, schedule config (optional)
 */
const ModeCard = ({
  modeId,
  mode,
  editing,
  onToggleEnabled,
  onEdit,
  onSave,
  onDelete,
}) => {
  const icon = MODE_ICONS[mode.icon] || MODE_ICONS.default;

  // Local editing state — mirrors the mode prop, reset when mode changes
  const [editName, setEditName] = useState(mode.name || '');
  const [editPolicies, setEditPolicies] = useState(mode.policy_overrides || {});
  const [editDelay, setEditDelay] = useState(
    mode.setting_overrides?.delay_time_seconds || 15,
  );
  const [hasSchedule, setHasSchedule] = useState(!!mode.schedule);
  const [scheduleStart, setScheduleStart] = useState(
    mode.schedule?.start_time || '22:00',
  );
  const [scheduleEnd, setScheduleEnd] = useState(
    mode.schedule?.end_time || '07:00',
  );
  const [scheduleDays, setScheduleDays] = useState(
    mode.schedule?.days || [0, 1, 2, 3, 4, 5, 6],
  );

  useEffect(() => {
    setEditName(mode.name || '');
    setEditPolicies(mode.policy_overrides || {});
    setEditDelay(mode.setting_overrides?.delay_time_seconds || 15);
    setHasSchedule(!!mode.schedule);
    setScheduleStart(mode.schedule?.start_time || '22:00');
    setScheduleEnd(mode.schedule?.end_time || '07:00');
    setScheduleDays(mode.schedule?.days || [0, 1, 2, 3, 4, 5, 6]);
  }, [mode]);

  const toggleModeFeature = (pkg, featureKey, value) => {
    setEditPolicies(prev => {
      const updated = { ...prev };
      if (!updated[pkg]) updated[pkg] = {};
      updated[pkg] = { ...updated[pkg], [featureKey]: value };
      return updated;
    });
  };

  const toggleDay = dayIndex => {
    setScheduleDays(prev => {
      if (prev.includes(dayIndex)) return prev.filter(d => d !== dayIndex);
      return [...prev, dayIndex].sort();
    });
  };

  const handleSave = () => {
    const updated = {
      ...mode,
      name: editName,
      policy_overrides: editPolicies,
      setting_overrides: {
        ...mode.setting_overrides,
        delay_time_seconds: editDelay,
      },
      schedule: hasSchedule
        ? {
            start_time: scheduleStart,
            end_time: scheduleEnd,
            days: scheduleDays,
          }
        : null,
    };
    onSave(modeId, updated);
  };

  const APPS = [
    { packageName: 'com.instagram.android', label: 'Instagram' },
    { packageName: 'com.google.android.youtube', label: 'YouTube' },
  ];

  const FEATS = [
    { key: 'app_open_intercept', label: 'App Open Intercept' },
    { key: 'reels_detection', label: 'Reels Detection' },
  ];

  return (
    <View
      style={[
        styles.modeCard,
        mode.enabled && { borderColor: mode.color || L.charcoal },
      ]}
    >
      {/* Header row */}
      <View style={styles.modeCardHeader}>
        <Text style={styles.modeIcon}>{icon}</Text>
        <View style={styles.modeCardInfo}>
          <Text style={styles.modeCardName}>{mode.name}</Text>
          <Text style={styles.modeCardSummary} numberOfLines={1}>
            {generateModeSummary(mode)}
          </Text>
        </View>
        <Switch
          value={mode.enabled === true}
          onValueChange={onToggleEnabled}
          trackColor={{ false: '#D6D6D6', true: mode.color || L.charcoal }}
          thumbColor="#FFFFFF"
        />
      </View>

      {/* Edit link */}
      {!editing && (
        <TouchableOpacity onPress={onEdit} style={styles.modeEditLink}>
          <Text style={styles.modeEditLinkText}>Edit ▸</Text>
        </TouchableOpacity>
      )}

      {/* Inline editor */}
      {editing && (
        <View style={styles.modeEditor}>
          {/* Name */}
          <TextInput
            style={styles.modeNameInput}
            value={editName}
            onChangeText={setEditName}
            placeholder="Mode name"
            placeholderTextColor={L.muted}
          />

          {/* Per-app feature overrides */}
          <Text style={styles.editorSectionLabel}>APPS & FEATURES</Text>
          {APPS.map(app => {
            const appOverrides = editPolicies[app.packageName] || {};
            return (
              <View key={app.packageName} style={styles.modeAppBlock}>
                <Text style={styles.modeAppLabel}>{app.label}</Text>
                {FEATS.map(feat => (
                  <View key={feat.key} style={styles.modeFeatureRow}>
                    <Text style={styles.modeFeatureLabel}>{feat.label}</Text>
                    <Switch
                      value={appOverrides[feat.key] === true}
                      onValueChange={val =>
                        toggleModeFeature(app.packageName, feat.key, val)
                      }
                      trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                      thumbColor="#FFFFFF"
                    />
                  </View>
                ))}
              </View>
            );
          })}

          {/* Delay override */}
          <Text style={styles.editorSectionLabel}>OVERRIDES</Text>
          <View style={styles.modeDelayRow}>
            <Text style={styles.modeFeatureLabel}>Forced Pause Duration</Text>
            <View style={styles.modeDelayInput}>
              <TouchableOpacity
                style={styles.stepperBtn}
                onPress={() => setEditDelay(d => Math.max(1, d - 5))}
              >
                <Text style={styles.stepperBtnText}>−</Text>
              </TouchableOpacity>
              <Text style={styles.modeDelayValue}>{editDelay}s</Text>
              <TouchableOpacity
                style={styles.stepperBtn}
                onPress={() => setEditDelay(d => Math.min(60, d + 5))}
              >
                <Text style={styles.stepperBtnText}>+</Text>
              </TouchableOpacity>
            </View>
          </View>

          {/* Schedule (optional) */}
          <Text style={styles.editorSectionLabel}>SCHEDULE (optional)</Text>
          {!hasSchedule ? (
            <TouchableOpacity
              style={styles.addScheduleBtn}
              onPress={() => setHasSchedule(true)}
            >
              <Text style={styles.addScheduleBtnText}>+ Add Schedule</Text>
            </TouchableOpacity>
          ) : (
            <View style={styles.scheduleBlock}>
              <View style={styles.scheduleTimeRow}>
                <Text style={styles.scheduleTimeLabel}>Starts at</Text>
                <TextInput
                  style={styles.scheduleTimeInput}
                  value={scheduleStart}
                  onChangeText={setScheduleStart}
                  placeholder="22:00"
                  placeholderTextColor={L.muted}
                  maxLength={5}
                />
              </View>
              <View style={styles.scheduleTimeRow}>
                <Text style={styles.scheduleTimeLabel}>Ends at</Text>
                <TextInput
                  style={styles.scheduleTimeInput}
                  value={scheduleEnd}
                  onChangeText={setScheduleEnd}
                  placeholder="07:00"
                  placeholderTextColor={L.muted}
                  maxLength={5}
                />
              </View>
              <View style={styles.dayPickerRow}>
                {DAY_LABELS.map((label, idx) => (
                  <TouchableOpacity
                    key={idx}
                    style={[
                      styles.dayBtn,
                      scheduleDays.includes(idx) && styles.dayBtnActive,
                    ]}
                    onPress={() => toggleDay(idx)}
                  >
                    <Text
                      style={[
                        styles.dayBtnText,
                        scheduleDays.includes(idx) && styles.dayBtnTextActive,
                      ]}
                    >
                      {label}
                    </Text>
                  </TouchableOpacity>
                ))}
              </View>
              <TouchableOpacity onPress={() => setHasSchedule(false)}>
                <Text style={styles.removeScheduleText}>Remove Schedule</Text>
              </TouchableOpacity>
            </View>
          )}

          {/* Save + Delete */}
          <View style={styles.modeEditorActions}>
            <TouchableOpacity style={styles.modeSaveBtn} onPress={handleSave}>
              <Text style={styles.modeSaveBtnText}>Save</Text>
            </TouchableOpacity>
            <TouchableOpacity onPress={() => onDelete(modeId)}>
              <Text style={styles.modeDeleteText}>Delete Mode</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}
    </View>
  );
};

// ─── Main Component ──────────────────────────────────────────────────────────
const Customize = ({ navigation }) => {
  const insets = useSafeAreaInsets();

  // ── Per-app policies state ───────────────────────────────────────────────
  // { "com.instagram.android": { app_open_intercept: true, reels_detection: true, ... }, ... }
  const [appPolicies, setAppPolicies] = useState({});

  // ── Global sub-feature toggles (kept global, not per-app) ─────────────
  const [freeBreakEnabled, setFreeBreakEnabled] = useState(false);

  // ── Custom modes state ────────────────────────────────────────────────────
  const [modes, setModes] = useState({});
  const [editingMode, setEditingMode] = useState(null);
  // Currently active mode ID (empty string = "default" mode / none)
  const [activeMode, setActiveMode] = useState('');

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
        } catch (_) {}
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
        } catch (_) {}
        if (!parsedModes || Object.keys(parsedModes).length === 0) {
          parsedModes = DEFAULT_MODES;
          SettingsModule.saveModes(JSON.stringify(parsedModes));
        }
        setModes(parsedModes);
        console.log(
          '[Customize] modes loaded:',
          Object.keys(parsedModes).length,
        );

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
    (packageName, featureKey, value) => {
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
      // Debounced commit — coalesces rapid toggles into one native write.
      // Key is stable per app+feature so flipping the same toggle multiple
      // times only ever produces one commit for the latest value.
      saver.schedule(`appFeature:${packageName}:${featureKey}`, () => {
        SettingsModule.setAppFeature(packageName, featureKey, value);
      });

      if (featureKey === 'app_open_intercept') {
        // Immediate write to SharedPreferences BEFORE startMonitoring reads it.
        // This fixes a race condition where the debounced write hadn't completed
        // when the service tried to load the blocked apps list.
        SettingsModule.setAppFeature(packageName, featureKey, value);
        VPNModule.startMonitoring().catch(() => {});
      }
      showSavedPending();
    },
    [saver, showSavedPending],
  );

  const handleFreeBreakToggle = value => {
    console.log('[Customize] free break toggle →', value);
    setFreeBreakEnabled(value);
    saver.schedule('freeBreak', () => {
      SettingsModule.saveFreeBreakEnabled(value);
    });
    showSavedPending();
  };

  // ── Custom mode handlers ──────────────────────────────────────────────────

  const handleModeToggleEnabled = useCallback(
    (modeId, newValue) => {
      console.log('[Customize] mode enabled toggle:', modeId, '→', newValue);
      LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
      const updatedModes = {
        ...modes,
        [modeId]: { ...modes[modeId], enabled: newValue },
      };
      setModes(updatedModes);
      // Snapshot the map in the closure so the commit always writes the
      // latest coalesced state even if multiple modes are toggled in sequence.
      saver.schedule(`mode:${modeId}:enabled`, () => {
        SettingsModule.saveModes(JSON.stringify(updatedModes));
      });
      showSavedPending();
    },
    [modes, saver, showSavedPending],
  );

  const handleModeSave = useCallback(
    (modeId, updatedMode) => {
      console.log('[Customize] saving mode:', modeId);
      LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
      const updatedModes = { ...modes, [modeId]: updatedMode };
      setModes(updatedModes);
      saver.schedule(`mode:${modeId}:full`, () => {
        SettingsModule.saveModes(JSON.stringify(updatedModes));
      });
      setEditingMode(null);
      showSavedPending();
    },
    [modes, saver, showSavedPending],
  );

  const handleModeDelete = useCallback(
    modeId => {
      console.log('[Customize] deleting mode:', modeId);
      LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
      const updatedModes = { ...modes };
      delete updatedModes[modeId];
      setModes(updatedModes);
      SettingsModule.saveModes(JSON.stringify(updatedModes));
      setEditingMode(null);
      showSaved();
    },
    [modes, showSaved],
  );

  const handleCreateMode = useCallback(() => {
    const id = 'custom_' + Date.now();
    console.log('[Customize] creating new mode:', id);
    LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
    const newMode = {
      name: 'New Mode',
      icon: 'focus',
      color: '#4CAF50',
      enabled: false,
      policy_overrides: {},
      setting_overrides: { delay_time_seconds: 15 },
      schedule: null,
    };
    const updatedModes = { ...modes, [id]: newMode };
    setModes(updatedModes);
    SettingsModule.saveModes(JSON.stringify(updatedModes));
    setEditingMode(id);
  }, [modes]);

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

  // Derive: which mode is non-default and active?
  const activeModeObj =
    activeMode && activeMode !== 'default' && modes[activeMode];
  const activeModeColor = activeModeObj?.color || L.charcoal;
  const activeModeName = activeModeObj?.name || '';
  const anyInterceptOn = Object.values(appPolicies).some(
    p => p?.app_open_intercept === true,
  );

  // App list for cards
  const MANAGED_APPS = [
    { packageName: 'com.instagram.android', label: 'Instagram', emoji: '📷' },
    {
      packageName: 'com.google.android.youtube',
      label: 'YouTube',
      emoji: '▶️',
    },
  ];

  // Helper: check if a feature is overridden by the active mode
  const isOverriddenByMode = (packageName, featureKey) => {
    if (!activeModeObj?.policy_overrides) return false;
    const appOverrides = activeModeObj.policy_overrides[packageName];
    return appOverrides && appOverrides[featureKey] !== undefined;
  };

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
        {/* ── Active mode banner ──────────────────────────────────── */}
        {activeModeObj && (
          <View
            style={[
              styles.activeBanner,
              { backgroundColor: activeModeColor + '18' },
            ]}
          >
            <Text style={[styles.activeBannerText, { color: activeModeColor }]}>
              {MODE_ICONS[activeModeObj.icon] || '⚡'} {activeModeName} active —
              overrides may apply
            </Text>
          </View>
        )}

        {/* ── YOUR APPS (per-app base defaults) ───────────────────── */}
        <View style={styles.section}>
          <Text style={styles.sectionLabel}>Your Apps</Text>

          {MANAGED_APPS.map(app => {
            const policy = appPolicies[app.packageName] || {};
            const reelsOn = policy.reels_detection === true;
            const interceptOn = policy.app_open_intercept === true;
            const reelsOverridden = isOverriddenByMode(
              app.packageName,
              'reels_detection',
            );
            const interceptOverridden = isOverriddenByMode(
              app.packageName,
              'app_open_intercept',
            );

            return (
              <View key={app.packageName} style={styles.appCard}>
                <Text style={styles.appCardTitle}>
                  {app.emoji} {app.label}
                </Text>

                <View style={styles.divider} />

                {/* Reels Detection toggle */}
                <View style={styles.toggleRow}>
                  <View style={styles.toggleLabelGroup}>
                    <Text
                      style={[
                        styles.toggleLabel,
                        reelsOverridden && styles.toggleLabelOverridden,
                      ]}
                    >
                      Reels Detection
                    </Text>
                    {reelsOverridden && (
                      <Text
                        style={[
                          styles.overrideHint,
                          { color: activeModeColor },
                        ]}
                      >
                        Overridden by {activeModeName}
                      </Text>
                    )}
                  </View>
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
                    disabled={reelsOverridden}
                  />
                </View>

                {/* App Open Intercept toggle */}
                <View style={styles.toggleRow}>
                  <View style={styles.toggleLabelGroup}>
                    <Text
                      style={[
                        styles.toggleLabel,
                        interceptOverridden && styles.toggleLabelOverridden,
                      ]}
                    >
                      App Open Intercept
                    </Text>
                    {interceptOverridden && (
                      <Text
                        style={[
                          styles.overrideHint,
                          { color: activeModeColor },
                        ]}
                      >
                        Overridden by {activeModeName}
                      </Text>
                    )}
                  </View>
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
                    disabled={interceptOverridden}
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

        {/* ── Custom Modes ─────────────────────────────────────────── */}
        <View style={styles.section}>
          <Text style={styles.sectionLabel}>Custom Modes</Text>
          <Text style={styles.sectionCaption}>
            Enable a mode to temporarily override the base settings above. Modes
            can be scheduled to activate automatically.
          </Text>

          {Object.entries(modes).map(([id, mode]) => (
            <ModeCard
              key={id}
              modeId={id}
              mode={mode}
              editing={editingMode === id}
              onToggleEnabled={val => handleModeToggleEnabled(id, val)}
              onEdit={() => {
                LayoutAnimation.configureNext(
                  LayoutAnimation.Presets.easeInEaseOut,
                );
                setEditingMode(editingMode === id ? null : id);
              }}
              onSave={handleModeSave}
              onDelete={handleModeDelete}
            />
          ))}

          {/* + Create Mode */}
          <TouchableOpacity
            style={styles.createModeBtn}
            onPress={handleCreateMode}
            activeOpacity={0.7}
          >
            <Text style={styles.createModeBtnText}>+ Create Mode</Text>
          </TouchableOpacity>
        </View>

        {/* ── Scroll Budget — visible when any app has Reels ON ── */}
        {anyReelsOn && (
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

  // ── Active mode banner ───────────────────────────────────────────────────
  activeBanner: {
    backgroundColor: 'rgba(0,0,0,0.06)',
    borderRadius: 10,
    paddingVertical: 10,
    paddingHorizontal: 14,
    marginBottom: 24,
  },
  activeBannerText: {
    fontSize: 12,
    color: L.charcoal,
    fontWeight: '500',
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
  toggleLabelOverridden: {
    opacity: 0.45,
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
  overrideHint: {
    fontSize: 11,
    fontWeight: '500',
    marginTop: 2,
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
