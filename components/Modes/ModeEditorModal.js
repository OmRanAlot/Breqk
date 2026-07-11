/**
 * ModeEditorModal.js — Full-screen editor for creating / editing a mode.
 * ─────────────────────────────────────────────────────────────────────────────
 * Sections: preview, name, icon (SVG set from shared/ModeIcons), color,
 * App Open Intercept box (add apps via the + picker), forced pause duration
 * (only visible while intercept has at least one app), Reels Detection
 * (Instagram + YouTube only), and an optional schedule.
 *
 * Data model is unchanged: policy_overrides[pkg] = { app_open_intercept,
 * reels_detection }, setting_overrides.delay_time_seconds, schedule.
 *
 * Logging prefix: [ModeEditor]
 */

import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  Switch,
  TouchableOpacity,
  TextInput,
  ScrollView,
  Modal,
  LayoutAnimation,
  Platform,
  UIManager,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Path } from 'react-native-svg';
import ModeIcon, { MODE_ICON_KEYS } from '../shared/ModeIcons';
import { MANAGED_APPS } from '../managedApps/manifest';
import { Monogram } from '../Permissions/onboarding/components';
import { styles, L } from './ModeEditorModal.styles';

if (
  Platform.OS === 'android' &&
  UIManager.setLayoutAnimationEnabledExperimental
) {
  UIManager.setLayoutAnimationEnabledExperimental(true);
}

const DAY_LABELS = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];

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
    mode?.setting_overrides?.delay_time_seconds || 15,
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

  useEffect(() => {
    if (mode) {
      setEditName(mode.name || 'New Mode');
      setEditIcon(mode.icon || 'focus');
      setEditColor(mode.color || '#FF9800');
      setEditPolicies(mode.policy_overrides || {});
      setEditDelay(mode.setting_overrides?.delay_time_seconds || 15);
      setHasSchedule(!!mode.schedule);
      setScheduleStart(mode.schedule?.start_time || '22:00');
      setScheduleEnd(mode.schedule?.end_time || '07:00');
      setScheduleDays(mode.schedule?.days || [0, 1, 2, 3, 4, 5, 6]);
    } else {
      setEditName('New Mode');
      setEditIcon('focus');
      setEditColor('#FF9800');
      setEditPolicies({});
      setEditDelay(15);
      setHasSchedule(false);
      setScheduleStart('22:00');
      setScheduleEnd('07:00');
      setScheduleDays([0, 1, 2, 3, 4, 5, 6]);
    }
    setShowAppPicker(false);
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
    onSave(modeId, updatedMode);
  };

  return (
    <Modal
      visible={visible}
      animationType="slide"
      presentationStyle="pageSheet"
      onRequestClose={onClose}
    >
      <View
        style={[styles.modalContainer, { paddingTop: Math.max(insets.top, 0) }]}
      >
        <View style={styles.modalHeader}>
          <TouchableOpacity onPress={onClose} style={styles.headerCloseBtn}>
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
                  style={styles.stepperBtn}
                  onPress={() => setEditDelay(d => Math.max(1, d - 5))}
                >
                  <Text style={styles.stepperBtnText}>−</Text>
                </TouchableOpacity>
                <Text style={styles.delayValue}>{editDelay}s</Text>
                <TouchableOpacity
                  style={styles.stepperBtn}
                  onPress={() => setEditDelay(d => Math.min(60, d + 5))}
                >
                  <Text style={styles.stepperBtnText}>+</Text>
                </TouchableOpacity>
              </View>
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

          {!isNew && (
            <TouchableOpacity
              style={styles.deleteBtn}
              onPress={() => onDelete(modeId)}
            >
              <Text style={styles.deleteBtnText}>Delete Mode</Text>
            </TouchableOpacity>
          )}
        </ScrollView>
      </View>
    </Modal>
  );
};

export default ModeEditorModal;
