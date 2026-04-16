import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  Switch,
  TouchableOpacity,
  TextInput,
  ScrollView,
  Modal,
  Animated,
  LayoutAnimation,
  Platform,
  UIManager,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Path } from 'react-native-svg';

if (
  Platform.OS === 'android' &&
  UIManager.setLayoutAnimationEnabledExperimental
) {
  UIManager.setLayoutAnimationEnabledExperimental(true);
}

const { SettingsModule } = require('react-native').NativeModules;

const L = {
  bg: '#FAFAFA',
  charcoal: '#1A1A1A',
  muted: '#737373',
  border: '#E5E5E5',
  cardBg: '#FFFFFF',
  cardBorder: 'rgba(0,0,0,0.07)',
};

const DAY_LABELS = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];

const ICON_OPTIONS = [
  { key: 'book', emoji: '📖' },
  { key: 'moon', emoji: '🌙' },
  { key: 'dumbbell', emoji: '💪' },
  { key: 'focus', emoji: '🎯' },
  { key: 'coffee', emoji: '☕' },
  { key: 'work', emoji: '💼' },
  { key: 'default', emoji: '⚡' },
];

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

const APPS = [
  { packageName: 'com.instagram.android', label: 'Instagram' },
  { packageName: 'com.google.android.youtube', label: 'YouTube' },
];

const FEATS = [
  { key: 'app_open_intercept', label: 'App Open Intercept' },
  { key: 'reels_detection', label: 'Reels Detection' },
];

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
  }, [mode, visible]);

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

  const selectedEmoji =
    ICON_OPTIONS.find(i => i.key === editIcon)?.emoji || '⚡';

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
            <Text style={styles.previewIcon}>{selectedEmoji}</Text>
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
            {ICON_OPTIONS.map(icon => (
              <TouchableOpacity
                key={icon.key}
                style={[
                  styles.iconOption,
                  editIcon === icon.key && styles.iconOptionSelected,
                  editIcon === icon.key && { borderColor: editColor },
                ]}
                onPress={() => setEditIcon(icon.key)}
              >
                <Text style={styles.iconEmoji}>{icon.emoji}</Text>
              </TouchableOpacity>
            ))}
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

          <Text style={styles.sectionLabel}>APPS & FEATURES</Text>
          {APPS.map(app => {
            const appOverrides = editPolicies[app.packageName] || {};
            return (
              <View key={app.packageName} style={styles.appBlock}>
                <Text style={styles.appLabel}>{app.label}</Text>
                {FEATS.map(feat => (
                  <View key={feat.key} style={styles.featureRow}>
                    <Text style={styles.featureLabel}>{feat.label}</Text>
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

const styles = StyleSheet.create({
  modalContainer: {
    flex: 1,
    backgroundColor: L.bg,
  },
  modalHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 14,
    backgroundColor: L.bg,
    borderBottomWidth: 1,
    borderBottomColor: L.border,
  },
  headerCloseBtn: {
    width: 36,
    height: 36,
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '500',
    color: L.charcoal,
  },
  headerSaveBtn: {
    paddingHorizontal: 8,
    paddingVertical: 6,
  },
  headerSaveText: {
    fontSize: 16,
    fontWeight: '600',
    color: L.charcoal,
  },
  modalScroll: {
    flex: 1,
  },
  modalScrollContent: {
    paddingHorizontal: 24,
    paddingTop: 24,
    paddingBottom: 48,
  },
  previewCard: {
    backgroundColor: L.cardBg,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: L.cardBorder,
    padding: 20,
    alignItems: 'center',
    marginBottom: 24,
    flexDirection: 'row',
    gap: 12,
  },
  previewIcon: {
    fontSize: 32,
  },
  previewName: {
    fontSize: 20,
    fontWeight: '600',
    color: L.charcoal,
    flex: 1,
  },
  previewDot: {
    width: 12,
    height: 12,
    borderRadius: 6,
  },
  sectionLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: L.muted,
    textTransform: 'uppercase',
    letterSpacing: 1.2,
    marginBottom: 12,
    marginTop: 20,
  },
  nameInput: {
    fontSize: 16,
    color: L.charcoal,
    borderBottomWidth: 1.5,
    borderBottomColor: L.charcoal,
    paddingVertical: 8,
  },
  iconGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
  },
  iconOption: {
    width: 52,
    height: 52,
    borderRadius: 12,
    backgroundColor: L.cardBg,
    borderWidth: 2,
    borderColor: L.cardBorder,
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconOptionSelected: {
    borderWidth: 2,
  },
  iconEmoji: {
    fontSize: 24,
  },
  colorGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
  },
  colorOption: {
    width: 44,
    height: 44,
    borderRadius: 22,
    alignItems: 'center',
    justifyContent: 'center',
  },
  colorOptionSelected: {
    borderWidth: 3,
    borderColor: '#FFFFFF',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.2,
    shadowRadius: 4,
    elevation: 4,
  },
  appBlock: {
    backgroundColor: L.cardBg,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: L.cardBorder,
    padding: 14,
    marginBottom: 8,
  },
  appLabel: {
    fontSize: 15,
    fontWeight: '600',
    color: L.charcoal,
    marginBottom: 10,
  },
  featureRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 6,
  },
  featureLabel: {
    fontSize: 14,
    color: L.charcoal,
  },
  delayRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 20,
    backgroundColor: L.cardBg,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: L.cardBorder,
    paddingVertical: 12,
  },
  stepperBtn: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: L.bg,
    borderWidth: 1,
    borderColor: L.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stepperBtnText: {
    fontSize: 24,
    fontWeight: '300',
    color: L.charcoal,
  },
  delayValue: {
    fontSize: 28,
    fontWeight: '500',
    color: L.charcoal,
    minWidth: 60,
    textAlign: 'center',
    fontVariant: ['tabular-nums'],
  },
  addScheduleBtn: {
    borderWidth: 1,
    borderColor: L.border,
    borderStyle: 'dashed',
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: 'center',
  },
  addScheduleBtnText: {
    fontSize: 14,
    color: L.muted,
    fontWeight: '500',
  },
  scheduleBlock: {
    backgroundColor: L.cardBg,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: L.cardBorder,
    padding: 14,
    gap: 12,
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
    fontSize: 14,
    color: L.charcoal,
    borderBottomWidth: 1,
    borderBottomColor: L.border,
    paddingVertical: 4,
    paddingHorizontal: 8,
    minWidth: 60,
    textAlign: 'center',
  },
  dayPickerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  dayBtn: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: L.bg,
    borderWidth: 1,
    borderColor: L.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dayBtnActive: {
    backgroundColor: L.charcoal,
    borderColor: L.charcoal,
  },
  dayBtnText: {
    fontSize: 13,
    fontWeight: '500',
    color: L.muted,
  },
  dayBtnTextActive: {
    color: '#FFFFFF',
  },
  removeScheduleText: {
    fontSize: 13,
    color: '#E53935',
    textAlign: 'center',
    marginTop: 4,
  },
  deleteBtn: {
    marginTop: 32,
    paddingVertical: 14,
    alignItems: 'center',
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#E53935',
  },
  deleteBtnText: {
    fontSize: 15,
    fontWeight: '500',
    color: '#E53935',
  },
});

export default ModeEditorModal;
