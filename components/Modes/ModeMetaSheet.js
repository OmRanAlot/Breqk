/**
 * ModeMetaSheet.js — Metadata-only editor for a mode.
 * ─────────────────────────────────────────────────────────────────────────────
 * Edits a mode's IDENTITY and SCHEDULE only: name, icon, colour, optional
 * schedule, and (for existing modes) delete. It deliberately does NOT touch the
 * mode's blocking — app-open intercept, reels detection, and forced-pause
 * duration are edited from the HOME screen while the mode is active (activate a
 * mode, then open the gear / a managed app). This replaced the old full-settings
 * ModeEditorModal; see docs/current_task.md.
 *
 * onSave emits ONLY the metadata keys ({ name, icon, color, schedule }); the
 * parent merges them into the existing mode so policy_overrides / setting_overrides
 * are preserved untouched.
 *
 * Schedule times are picked with TimePickerSheet and shown as 12-hour AM/PM but
 * STORED as 24h "HH:mm" — the native ModeManager parses that, so the persisted
 * shape must not change.
 *
 * Logging prefix: [ModeMeta]
 */

import React, { useState, useEffect, useRef } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  TextInput,
  ScrollView,
  Modal,
  Alert,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Path, Circle } from 'react-native-svg';
import ModeIcon, { MODE_ICON_KEYS } from '../shared/ModeIcons';
import TimePickerSheet from '../shared/TimePickerSheet';
import { formatTime12h } from '../shared/scheduleWindow';
import { styles, L } from './ModeMetaSheet.styles';

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

const ModeMetaSheet = ({
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

  // Snapshot of the form as it looked when the sheet opened, so closing with
  // unsaved edits can prompt before discarding them.
  const initialSnapshot = useRef(null);

  useEffect(() => {
    const next = {
      name: mode?.name || 'New Mode',
      icon: mode?.icon || 'focus',
      color: mode?.color || '#FF9800',
      hasSchedule: !!mode?.schedule,
      start: mode?.schedule?.start_time || '22:00',
      end: mode?.schedule?.end_time || '07:00',
      days: mode?.schedule?.days || [0, 1, 2, 3, 4, 5, 6],
    };
    setEditName(next.name);
    setEditIcon(next.icon);
    setEditColor(next.color);
    setHasSchedule(next.hasSchedule);
    setScheduleStart(next.start);
    setScheduleEnd(next.end);
    setScheduleDays(next.days);
    setPickingTime(null);
    initialSnapshot.current = JSON.stringify(next);
  }, [mode, visible]);

  const toggleDay = dayIndex => {
    setScheduleDays(prev => {
      if (prev.includes(dayIndex)) return prev.filter(d => d !== dayIndex);
      return [...prev, dayIndex].sort();
    });
  };

  const currentSnapshot = () =>
    JSON.stringify({
      name: editName,
      icon: editIcon,
      color: editColor,
      hasSchedule,
      start: scheduleStart,
      end: scheduleEnd,
      days: scheduleDays,
    });

  const isDirty = () =>
    initialSnapshot.current !== null &&
    initialSnapshot.current !== currentSnapshot();

  const handleSave = () => {
    const meta = {
      name: editName,
      icon: editIcon,
      color: editColor,
      schedule: hasSchedule
        ? {
            start_time: scheduleStart,
            end_time: scheduleEnd,
            days: scheduleDays,
          }
        : null,
    };
    // Re-baseline so the close that follows a save never re-prompts.
    initialSnapshot.current = currentSnapshot();
    console.log('[ModeMeta] save', modeId, editName);
    onSave(modeId, meta);
  };

  const handleClose = () => {
    if (!isDirty()) {
      onClose();
      return;
    }
    console.log('[ModeMeta] close blocked — unsaved changes');
    Alert.alert(
      'Discard changes?',
      'You have unsaved changes to this mode. Leaving now will lose them.',
      [
        { text: 'Keep Editing', style: 'cancel' },
        {
          text: 'Discard',
          style: 'destructive',
          onPress: () => {
            console.log('[ModeMeta] changes discarded');
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
            {isNew ? 'Create Mode' : 'Mode Details'}
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

          <Text style={styles.metaHint}>
            Activate this mode, then edit its blocking (intercept, reels
            detection, pause length) from the home screen.
          </Text>

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

export default ModeMetaSheet;
