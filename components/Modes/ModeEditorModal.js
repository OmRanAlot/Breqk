/**
 * ModeEditorModal.js — Full-screen editor for creating / editing a mode.
 * ─────────────────────────────────────────────────────────────────────────────
 * Sections: preview, name, icon (SVG set from shared/ModeIcons), color,
 * App Open Intercept box (add apps via the + picker), forced pause duration
 * (only visible while intercept has at least one app), Reels Detection
 * (Instagram + YouTube only), and an optional schedule.
 *
 * Schedule times are picked with the shared TimePickerSheet and displayed as
 * 12-hour AM/PM, but still STORED as 24h "HH:mm" — the native ModeManager
 * parses that format, so the persisted shape must not change.
 *
 * Closing with unsaved edits prompts before discarding (see `isDirty`).
 *
 * Data model is unchanged: policy_overrides[pkg] = { app_open_intercept,
 * reels_detection }, setting_overrides.delay_time_seconds, schedule.
 *
 * Logging prefix: [ModeEditor]
 */

import React, { useState, useEffect, useRef } from 'react';
import {
  View,
  Text,
  Switch,
  TouchableOpacity,
  TextInput,
  ScrollView,
  Modal,
  Alert,
  LayoutAnimation,
  Platform,
  UIManager,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Path, Circle } from 'react-native-svg';
import ModeIcon, { MODE_ICON_KEYS } from '../shared/ModeIcons';
import { MANAGED_APPS } from '../managedApps/manifest';
import { Monogram } from '../Permissions/onboarding/components';
import TimePickerSheet from '../shared/TimePickerSheet';
import { formatTime12h } from '../shared/scheduleWindow';
import { styles, L } from './ModeEditorModal.styles';

if (
  Platform.OS === 'android' &&
  UIManager.setLayoutAnimationEnabledExperimental
) {
  UIManager.setLayoutAnimationEnabledExperimental(true);
}

const DAY_LABELS = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];

// Forced pause duration: multiples of DELAY_STEP only, so the stepper can never
// land on an off-grid value like 1s or 6s.
const DELAY_STEP = 5;
const DELAY_MIN = 5;
const DELAY_MAX = 60;
const DEFAULT_DELAY = 15;

/**
 * Snaps a stored delay onto the DELAY_STEP grid and into [MIN, MAX]. Modes saved
 * before the 5s floor existed can hold values the stepper cannot otherwise
 * reach (the old minimum was 1s), so clamp on load rather than trusting prefs.
 *
 * @param {number} seconds
 * @returns {number}
 */
const snapDelay = seconds => {
  const value = Number(seconds);
  if (!Number.isFinite(value) || value <= 0) {
    return DEFAULT_DELAY;
  }
  const snapped = Math.round(value / DELAY_STEP) * DELAY_STEP;
  return Math.max(DELAY_MIN, Math.min(DELAY_MAX, snapped));
};

const COLOR_OPTIONS = [
  '#FF9800',
  '#7C4DFF',
  '#4CAF50',
  '#2196F3',
  '#E91E63',
  '#9C27B0',
  '#00BCD4',
  '#795548',
];

// Reels Detection is limited to Instagram and YouTube — the only two apps
// this editor exposes for the native short-form detector's shared
// `reels_detection` key.
const REELS_APPS = [
  {
    pkg: 'com.instagram.android',
    label: 'Instagram',
    featureLabel: 'Reels Detection',
  },
  {
    pkg: 'com.google.android.youtube',
    label: 'YouTube',
    featureLabel: 'Shorts Detection',
  },
];

const CloseIcon = ({ color, size }) => (
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
    <Path d="M18 6L6 18M6 6l12 12" />
  </Svg>
);

const PlusIcon = ({ color, size }) => (
  <Svg
    width={size}
    height={size}
    fill="none"
    stroke={color}
    strokeWidth={1.8}
    strokeLinecap="round"
    strokeLinejoin="round"
    viewBox="0 0 24 24"
  >
    <Path d="M12 5v14M5 12h14" />
  </Svg>
);

const ClockIcon = ({ color, size }) => (
  <Svg
    width={size}
    height={size}
    fill="none"
    stroke={color}
    strokeWidth={1.6}
    strokeLinecap="round"
    strokeLinejoin="round"
    viewBox="0 0 24 24"
  >
    <Circle cx={12} cy={12} r={9} />
    <Path d="M12 7v5l3 2" />
  </Svg>
);

const ChevronRightIcon = ({ color, size }) => (
  <Svg
    width={size}
    height={size}
    fill="none"
    stroke={color}
    strokeWidth={1.8}
    strokeLinecap="round"
    strokeLinejoin="round"
    viewBox="0 0 24 24"
  >
    <Path d="M9 18l6-6-6-6" />
  </Svg>
);

const ModeEditorModal = ({
  visible,
  mode,
  modeId,
  onSave,
  onDelete,
  onClose,
  isNew,
}) => {
  const insets = useSafeAreaInsets();

  const [editName, setEditName] = useState(mode?.name || 'New Mode');
  const [editIcon, setEditIcon] = useState(mode?.icon || 'focus');
  const [editColor, setEditColor] = useState(mode?.color || '#FF9800');
  const [editPolicies, setEditPolicies] = useState(
    mode?.policy_overrides || {},
  );
  const [editDelay, setEditDelay] = useState(
    snapDelay(mode?.setting_overrides?.delay_time_seconds),
  );
  const [showAppPicker, setShowAppPicker] = useState(false);
  const [hasSchedule, setHasSchedule] = useState(!!mode?.schedule);
  const [scheduleStart, setScheduleStart] = useState(
    mode?.schedule?.start_time || '22:00',
  );
  const [scheduleEnd, setScheduleEnd] = useState(
    mode?.schedule?.end_time || '07:00',
  );
  const [scheduleDays, setScheduleDays] = useState(
    mode?.schedule?.days || [0, 1, 2, 3, 4, 5, 6],
  );
  // Which time row the picker sheet is editing: 'start' | 'end' | null.
  const [pickingTime, setPickingTime] = useState(null);

  // Snapshot of the form as it looked when the modal opened. Compared against
  // current state to decide whether closing needs a discard confirmation.
  const initialSnapshot = useRef(null);

  useEffect(() => {
    const next = mode
      ? {
          name: mode.name || 'New Mode',
          icon: mode.icon || 'focus',
          color: mode.color || '#FF9800',
          policies: mode.policy_overrides || {},
          delay: snapDelay(mode.setting_overrides?.delay_time_seconds),
          hasSchedule: !!mode.schedule,
          start: mode.schedule?.start_time || '22:00',
          end: mode.schedule?.end_time || '07:00',
          days: mode.schedule?.days || [0, 1, 2, 3, 4, 5, 6],
        }
      : {
          name: 'New Mode',
          icon: 'focus',
          color: '#FF9800',
          policies: {},
          delay: DEFAULT_DELAY,
          hasSchedule: false,
          start: '22:00',
          end: '07:00',
          days: [0, 1, 2, 3, 4, 5, 6],
        };

    setEditName(next.name);
    setEditIcon(next.icon);
    setEditColor(next.color);
    setEditPolicies(next.policies);
    setEditDelay(next.delay);
    setHasSchedule(next.hasSchedule);
    setScheduleStart(next.start);
    setScheduleEnd(next.end);
    setScheduleDays(next.days);
    setShowAppPicker(false);
    setPickingTime(null);

    // Baseline for the unsaved-changes check. Serialized, because the policies
    // object and days array are compared by value, not identity.
    initialSnapshot.current = JSON.stringify(next);
  }, [mode, visible]);

  // Apps currently in the intercept box vs. still available in the + picker.
  const interceptApps = MANAGED_APPS.filter(
    app => editPolicies[app.pkg]?.app_open_intercept === true,
  );
  const availableApps = MANAGED_APPS.filter(
    app => editPolicies[app.pkg]?.app_open_intercept !== true,
  );

  const setPolicyFeature = (pkg, featureKey, value) => {
    setEditPolicies(prev => ({
      ...prev,
      [pkg]: { ...(prev[pkg] || {}), [featureKey]: value },
    }));
  };

  const addInterceptApp = pkg => {
    console.log('[ModeEditor] intercept add:', pkg);
    LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
    setPolicyFeature(pkg, 'app_open_intercept', true);
    setShowAppPicker(false);
  };

  const removeInterceptApp = pkg => {
    console.log('[ModeEditor] intercept remove:', pkg);
    LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
    setPolicyFeature(pkg, 'app_open_intercept', false);
  };

  const toggleAppPicker = () => {
    LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
    setShowAppPicker(prev => !prev);
  };

  const toggleDay = dayIndex => {
    setScheduleDays(prev => {
      if (prev.includes(dayIndex)) return prev.filter(d => d !== dayIndex);
      return [...prev, dayIndex].sort();
    });
  };

  const handleSave = () => {
    LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
    const updatedMode = {
      name: editName,
      icon: editIcon,
      color: editColor,
      enabled: mode?.enabled || false,
      policy_overrides: editPolicies,
      setting_overrides: {
        ...mode?.setting_overrides,
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
    // Re-baseline first so the close that follows a save never sees the form as
    // dirty and never prompts.
    initialSnapshot.current = currentSnapshot();
    onSave(modeId, updatedMode);
  };

  /**
   * Serializes the live form the same way the open-time baseline was
   * serialized, so the two can be compared by value.
   */
  const currentSnapshot = () =>
    JSON.stringify({
      name: editName,
      icon: editIcon,
      color: editColor,
      policies: editPolicies,
      delay: editDelay,
      hasSchedule,
      start: scheduleStart,
      end: scheduleEnd,
      days: scheduleDays,
    });

  const isDirty = () =>
    initialSnapshot.current !== null &&
    initialSnapshot.current !== currentSnapshot();

  /**
   * Close guard. A clean form closes straight away; a dirty one asks first, so
   * a stray back-gesture or mis-tapped X cannot silently drop the user's edits.
   */
  const handleClose = () => {
    if (!isDirty()) {
      onClose();
      return;
    }
    console.log('[ModeEditor] close blocked — unsaved changes');
    Alert.alert(
      'Discard changes?',
      'You have unsaved changes to this mode. Leaving now will lose them.',
      [
        { text: 'Keep Editing', style: 'cancel' },
        {
          text: 'Discard',
          style: 'destructive',
          onPress: () => {
            console.log('[ModeEditor] changes discarded');
            onClose();
          },
        },
      ],
    );
  };

  return (
    <Modal
      visible={visible}
      animationType="slide"
      presentationStyle="pageSheet"
      onRequestClose={handleClose}
    >
      <View
        style={[styles.modalContainer, { paddingTop: Math.max(insets.top, 0) }]}
      >
        <View style={styles.modalHeader}>
          <TouchableOpacity onPress={handleClose} style={styles.headerCloseBtn}>
            <CloseIcon color={L.charcoal} size={24} />
          </TouchableOpacity>
          <Text style={styles.headerTitle}>
            {isNew ? 'Create Mode' : 'Edit Mode'}
          </Text>
          <TouchableOpacity onPress={handleSave} style={styles.headerSaveBtn}>
            <Text style={styles.headerSaveText}>Save</Text>
          </TouchableOpacity>
        </View>

        <ScrollView
          style={styles.modalScroll}
          contentContainerStyle={styles.modalScrollContent}
          keyboardShouldPersistTaps="handled"
        >
          <View style={styles.previewCard}>
            <View
              style={[
                styles.previewIconTile,
                { backgroundColor: editColor + '1F' },
              ]}
            >
              <ModeIcon name={editIcon} size={26} color={editColor} />
            </View>
            <Text style={styles.previewName}>{editName || 'New Mode'}</Text>
            <View style={[styles.previewDot, { backgroundColor: editColor }]} />
          </View>

          <Text style={styles.sectionLabel}>NAME</Text>
          <TextInput
            style={styles.nameInput}
            value={editName}
            onChangeText={setEditName}
            placeholder="Mode name"
            placeholderTextColor={L.muted}
          />

          <Text style={styles.sectionLabel}>ICON</Text>
          <View style={styles.iconGrid}>
            {MODE_ICON_KEYS.map(iconKey => {
              const selected = editIcon === iconKey;
              return (
                <TouchableOpacity
                  key={iconKey}
                  style={[
                    styles.iconOption,
                    selected && { borderColor: editColor },
                  ]}
                  onPress={() => setEditIcon(iconKey)}
                  accessibilityRole="button"
                  accessibilityLabel={'Icon ' + iconKey}
                  accessibilityState={{ selected }}
                >
                  <ModeIcon
                    name={iconKey}
                    size={22}
                    color={selected ? editColor : L.charcoal}
                  />
                </TouchableOpacity>
              );
            })}
          </View>

          <Text style={styles.sectionLabel}>COLOR</Text>
          <View style={styles.colorGrid}>
            {COLOR_OPTIONS.map(color => (
              <TouchableOpacity
                key={color}
                style={[
                  styles.colorOption,
                  { backgroundColor: color },
                  editColor === color && styles.colorOptionSelected,
                ]}
                onPress={() => setEditColor(color)}
              >
                {editColor === color && (
                  <Svg width={16} height={16} fill="white" viewBox="0 0 24 24">
                    <Path
                      d="M20 6L9 17l-5-5"
                      stroke="white"
                      strokeWidth={2.5}
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </Svg>
                )}
              </TouchableOpacity>
            ))}
          </View>

          <Text style={styles.sectionLabel}>APP OPEN INTERCEPT</Text>
          <Text style={styles.sectionCaption}>
            Adds a forced pause before these apps open.
          </Text>
          <View style={styles.interceptBox}>
            {interceptApps.map(app => (
              <View key={app.pkg} style={styles.interceptAppRow}>
                <Monogram
                  text={app.monogram}
                  size={34}
                  radius={9}
                  fontSize={14}
                />
                <Text style={styles.interceptAppLabel}>{app.label}</Text>
                <TouchableOpacity
                  onPress={() => removeInterceptApp(app.pkg)}
                  style={styles.interceptRemoveBtn}
                  hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
                  accessibilityRole="button"
                  accessibilityLabel={'Remove ' + app.label}
                >
                  <CloseIcon color={L.muted} size={16} />
                </TouchableOpacity>
              </View>
            ))}

            {interceptApps.length === 0 && !showAppPicker && (
              <Text style={styles.interceptEmptyText}>
                No apps added yet — tap + to choose apps to intercept.
              </Text>
            )}

            {!showAppPicker && availableApps.length > 0 && (
              <TouchableOpacity
                style={styles.addAppBtn}
                onPress={toggleAppPicker}
                activeOpacity={0.7}
                accessibilityRole="button"
                accessibilityLabel="Add app to intercept"
              >
                <PlusIcon color={L.muted} size={22} />
              </TouchableOpacity>
            )}

            {showAppPicker && (
              <View style={styles.appPickerList}>
                {availableApps.map(app => (
                  <TouchableOpacity
                    key={app.pkg}
                    style={styles.appPickerRow}
                    onPress={() => addInterceptApp(app.pkg)}
                    activeOpacity={0.7}
                    accessibilityRole="button"
                    accessibilityLabel={'Add ' + app.label}
                  >
                    <Monogram
                      text={app.monogram}
                      active={false}
                      size={34}
                      radius={9}
                      fontSize={14}
                    />
                    <Text style={styles.appPickerLabel}>{app.label}</Text>
                    <PlusIcon color={L.muted} size={16} />
                  </TouchableOpacity>
                ))}
                <TouchableOpacity onPress={toggleAppPicker}>
                  <Text style={styles.appPickerCancel}>Cancel</Text>
                </TouchableOpacity>
              </View>
            )}
          </View>

          {interceptApps.length > 0 && (
            <>
              <Text style={styles.sectionLabel}>FORCED PAUSE DURATION</Text>
              <View style={styles.delayRow}>
                <TouchableOpacity
                  style={[
                    styles.stepperBtn,
                    editDelay <= DELAY_MIN && styles.stepperBtnDisabled,
                  ]}
                  disabled={editDelay <= DELAY_MIN}
                  onPress={() =>
                    setEditDelay(d => Math.max(DELAY_MIN, d - DELAY_STEP))
                  }
                  accessibilityRole="button"
                  accessibilityLabel="Decrease pause duration"
                >
                  <Text style={styles.stepperBtnText}>−</Text>
                </TouchableOpacity>
                <Text style={styles.delayValue}>{editDelay}s</Text>
                <TouchableOpacity
                  style={[
                    styles.stepperBtn,
                    editDelay >= DELAY_MAX && styles.stepperBtnDisabled,
                  ]}
                  disabled={editDelay >= DELAY_MAX}
                  onPress={() =>
                    setEditDelay(d => Math.min(DELAY_MAX, d + DELAY_STEP))
                  }
                  accessibilityRole="button"
                  accessibilityLabel="Increase pause duration"
                >
                  <Text style={styles.stepperBtnText}>+</Text>
                </TouchableOpacity>
              </View>
              <Text style={styles.sectionCaptionBelow}>
                {DELAY_MIN}–{DELAY_MAX} seconds, in {DELAY_STEP}-second steps.
              </Text>
            </>
          )}

          <Text style={styles.sectionLabel}>REELS DETECTION</Text>
          <View style={styles.reelsBox}>
            {REELS_APPS.map((app, idx) => {
              const managed = MANAGED_APPS.find(m => m.pkg === app.pkg);
              return (
                <View
                  key={app.pkg}
                  style={[
                    styles.reelsRow,
                    idx < REELS_APPS.length - 1 && styles.reelsRowDivider,
                  ]}
                >
                  <Monogram
                    text={managed?.monogram || app.label[0]}
                    size={34}
                    radius={9}
                    fontSize={14}
                  />
                  <View style={styles.reelsRowInfo}>
                    <Text style={styles.reelsRowLabel}>{app.label}</Text>
                    <Text style={styles.reelsRowFeature}>
                      {app.featureLabel}
                    </Text>
                  </View>
                  <Switch
                    value={editPolicies[app.pkg]?.reels_detection === true}
                    onValueChange={val =>
                      setPolicyFeature(app.pkg, 'reels_detection', val)
                    }
                    trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                    thumbColor="#FFFFFF"
                  />
                </View>
              );
            })}
          </View>

          <Text style={styles.sectionLabel}>SCHEDULE (optional)</Text>
          {!hasSchedule ? (
            <TouchableOpacity
              style={styles.addScheduleBtn}
              onPress={() => setHasSchedule(true)}
            >
              <Text style={styles.addScheduleBtnText}>+ Add Schedule</Text>
            </TouchableOpacity>
          ) : (
            <View style={styles.scheduleBlock}>
              <TouchableOpacity
                style={styles.scheduleTimeRow}
                onPress={() => setPickingTime('start')}
                activeOpacity={0.7}
                accessibilityRole="button"
                accessibilityLabel={`Start time, ${formatTime12h(
                  scheduleStart,
                )}. Tap to change.`}
              >
                <Text style={styles.scheduleTimeLabel}>Starts at</Text>
                <View style={styles.scheduleTimeValueGroup}>
                  <ClockIcon color={L.muted} size={18} />
                  <Text style={styles.scheduleTimeValue}>
                    {formatTime12h(scheduleStart)}
                  </Text>
                  <ChevronRightIcon color={L.muted} size={16} />
                </View>
              </TouchableOpacity>

              <TouchableOpacity
                style={styles.scheduleTimeRow}
                onPress={() => setPickingTime('end')}
                activeOpacity={0.7}
                accessibilityRole="button"
                accessibilityLabel={`End time, ${formatTime12h(
                  scheduleEnd,
                )}. Tap to change.`}
              >
                <Text style={styles.scheduleTimeLabel}>Ends at</Text>
                <View style={styles.scheduleTimeValueGroup}>
                  <ClockIcon color={L.muted} size={18} />
                  <Text style={styles.scheduleTimeValue}>
                    {formatTime12h(scheduleEnd)}
                  </Text>
                  <ChevronRightIcon color={L.muted} size={16} />
                </View>
              </TouchableOpacity>

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

          {!isNew && (
            <TouchableOpacity
              style={styles.deleteBtn}
              onPress={() => onDelete(modeId)}
            >
              <Text style={styles.deleteBtnText}>Delete Mode</Text>
            </TouchableOpacity>
          )}
        </ScrollView>

        <TimePickerSheet
          visible={pickingTime !== null}
          title={pickingTime === 'end' ? 'Ends at' : 'Starts at'}
          value={pickingTime === 'end' ? scheduleEnd : scheduleStart}
          onConfirm={time => {
            if (pickingTime === 'end') {
              setScheduleEnd(time);
            } else {
              setScheduleStart(time);
            }
            setPickingTime(null);
          }}
          onCancel={() => setPickingTime(null)}
        />
      </View>
    </Modal>
  );
};

export default ModeEditorModal;
