import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
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
import { MANAGED_APPS } from '../managedApps/manifest';
import { Monogram } from '../Permissions/onboarding/components';
import { styles, L } from './ModeEditorModal.styles';

if (
  Platform.OS === 'android' &&
  UIManager.setLayoutAnimationEnabledExperimental
) {
  UIManager.setLayoutAnimationEnabledExperimental(true);
}

const { SettingsModule } = require('react-native').NativeModules;

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

// ── AM/PM time helpers ──
// Modes persist schedule times as 24h "HH:mm" so the native ModeManager /
// AlarmManager stay unchanged. The picker edits a 12h view and converts on
// every keystroke.
const parse24 = hhmm => {
  const [h, m] = (hhmm || '00:00').split(':').map(n => parseInt(n, 10));
  const hour = Number.isFinite(h) ? h : 0;
  const minute = Number.isFinite(m) ? m : 0;
  const period = hour >= 12 ? 'PM' : 'AM';
  let hour12 = hour % 12;
  if (hour12 === 0) hour12 = 12;
  return { hour12, minute, period };
};

const to24 = (hour12, minute, period) => {
  let h = hour12 % 12;
  if (period === 'PM') h += 12;
  return `${String(h).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
};

const clampInt = (val, min, max, fallback) => {
  const n = parseInt(val, 10);
  if (!Number.isFinite(n)) return fallback;
  return Math.max(min, Math.min(max, n));
};

// Compact 12-hour picker: hour (1–12), minute (00–59), AM/PM toggle. Emits a
// 24h "HH:mm" string via onChange.
const TimePicker = ({ value, onChange }) => {
  const { hour12, minute, period } = parse24(value);

  return (
    <View style={styles.timePicker}>
      <TextInput
        style={styles.timeField}
        value={String(hour12)}
        onChangeText={t =>
          onChange(to24(clampInt(t, 1, 12, hour12), minute, period))
        }
        keyboardType="number-pad"
        maxLength={2}
        selectTextOnFocus
      />
      <Text style={styles.timeColon}>:</Text>
      <TextInput
        style={styles.timeField}
        value={String(minute).padStart(2, '0')}
        onChangeText={t =>
          onChange(to24(hour12, clampInt(t, 0, 59, minute), period))
        }
        keyboardType="number-pad"
        maxLength={2}
        selectTextOnFocus
      />
      <TouchableOpacity
        style={styles.periodToggle}
        onPress={() =>
          onChange(to24(hour12, minute, period === 'AM' ? 'PM' : 'AM'))
        }
      >
        <Text style={styles.periodToggleText}>{period}</Text>
      </TouchableOpacity>
    </View>
  );
};

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
  const [editMessage, setEditMessage] = useState(
    mode?.setting_overrides?.delay_message || '',
  );
  const [recurringOverlay, setRecurringOverlay] = useState(
    mode?.setting_overrides?.recurring_overlay === true,
  );
  const [overlayInterval, setOverlayInterval] = useState(
    mode?.setting_overrides?.overlay_interval_seconds || 5,
  );
  const [persistentNotif, setPersistentNotif] = useState(
    mode?.setting_overrides?.persistent_notification === true,
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
      setEditMessage(mode.setting_overrides?.delay_message || '');
      setRecurringOverlay(mode.setting_overrides?.recurring_overlay === true);
      setOverlayInterval(mode.setting_overrides?.overlay_interval_seconds || 5);
      setPersistentNotif(
        mode.setting_overrides?.persistent_notification === true,
      );
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
      setEditMessage('');
      setRecurringOverlay(false);
      setOverlayInterval(5);
      setPersistentNotif(false);
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

  // Master "manage this app in this mode" toggle. ON seeds App Open Intercept so
  // the app is blocked by default; OFF removes the app's entry entirely so it has
  // no overrides in this mode (excluded). Presence of the pkg key — not its
  // truthiness — drives whether the per-app feature block is shown, so the user
  // can keep an app managed with intercept off (e.g. Reels-only, like the Home
  // "Managed Apps" rows).
  const toggleAppManaged = (pkg, value) => {
    setEditPolicies(prev => {
      const updated = { ...prev };
      if (value) {
        updated[pkg] = { ...(updated[pkg] || {}), app_open_intercept: true };
      } else {
        delete updated[pkg];
      }
      return updated;
    });
  };

  // Per-app stepper (e.g. session_post_limit) inside the mode's policy_overrides.
  // Mirrors AppDetail's adjustPostLimit so both screens write the same key/shape.
  const adjustModeStepper = (pkg, feature, delta) => {
    setEditPolicies(prev => {
      const cur = prev[pkg] || {};
      const base = typeof cur[feature.key] === 'number' ? cur[feature.key] : 20;
      const next = Math.max(
        feature.min,
        Math.min(feature.max, base + delta * feature.step),
      );
      return { ...prev, [pkg]: { ...cur, [feature.key]: next } };
    });
  };

  // Arms a near-future test window: enables the schedule, sets start to ~2 min
  // from now and end ~2 min after that, every day. Lets you watch the mode
  // auto-switch on and back to default without waiting for a real schedule.
  const handleQuickTest = () => {
    const now = new Date();
    const fmt = d =>
      `${String(d.getHours()).padStart(2, '0')}:${String(
        d.getMinutes(),
      ).padStart(2, '0')}`;
    const start = new Date(now.getTime() + 2 * 60 * 1000);
    const end = new Date(now.getTime() + 4 * 60 * 1000);
    LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
    setHasSchedule(true);
    setScheduleStart(fmt(start));
    setScheduleEnd(fmt(end));
    setScheduleDays([0, 1, 2, 3, 4, 5, 6]);
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
        delay_message: editMessage.trim(),
        recurring_overlay: recurringOverlay,
        overlay_interval_seconds: overlayInterval,
        persistent_notification: persistentNotif,
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

          <Text style={styles.sectionLabel}>APPS TO BLOCK</Text>
          <Text style={styles.appsSectionCaption}>
            Choose which apps this mode manages and which interventions apply.
          </Text>
          {MANAGED_APPS.map(app => {
            const appOverrides = editPolicies[app.pkg] || {};
            const managed = Object.prototype.hasOwnProperty.call(
              editPolicies,
              app.pkg,
            );
            const toggleFeatures = app.features.filter(
              f => f.kind !== 'stepper',
            );
            const stepper = app.features.find(f => f.kind === 'stepper');

            return (
              <View key={app.pkg} style={styles.appBlock}>
                <View style={styles.appHeaderRow}>
                  <Monogram
                    text={app.monogram}
                    active={managed}
                    size={32}
                    radius={9}
                    fontSize={13}
                  />
                  <Text style={styles.appHeaderLabel}>{app.label}</Text>
                  <Switch
                    value={managed}
                    onValueChange={val => toggleAppManaged(app.pkg, val)}
                    trackColor={{ false: '#D6D6D6', true: editColor }}
                    thumbColor="#FFFFFF"
                  />
                </View>

                {managed && (
                  <View style={styles.appFeatures}>
                    {/* App Open Intercept = the literal "block on open" toggle */}
                    <View style={styles.featureRow}>
                      <Text style={styles.featureLabel}>
                        App Open Intercept
                      </Text>
                      <Switch
                        value={appOverrides.app_open_intercept === true}
                        onValueChange={val =>
                          toggleModeFeature(app.pkg, 'app_open_intercept', val)
                        }
                        trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                        thumbColor="#FFFFFF"
                      />
                    </View>

                    {toggleFeatures.map(feat => (
                      <View key={feat.key} style={styles.featureRow}>
                        <Text style={styles.featureLabel}>{feat.label}</Text>
                        <Switch
                          value={appOverrides[feat.key] === true}
                          onValueChange={val =>
                            toggleModeFeature(app.pkg, feat.key, val)
                          }
                          trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                          thumbColor="#FFFFFF"
                        />
                      </View>
                    ))}

                    {stepper && (
                      <View style={styles.featureRow}>
                        <Text style={styles.featureLabel}>{stepper.label}</Text>
                        <View style={styles.miniStepper}>
                          <TouchableOpacity
                            style={styles.miniStepperBtn}
                            onPress={() =>
                              adjustModeStepper(app.pkg, stepper, -1)
                            }
                          >
                            <Text style={styles.miniStepperBtnText}>−</Text>
                          </TouchableOpacity>
                          <Text style={styles.miniStepperValue}>
                            {typeof appOverrides[stepper.key] === 'number'
                              ? appOverrides[stepper.key]
                              : 20}
                          </Text>
                          <TouchableOpacity
                            style={styles.miniStepperBtn}
                            onPress={() =>
                              adjustModeStepper(app.pkg, stepper, 1)
                            }
                          >
                            <Text style={styles.miniStepperBtnText}>+</Text>
                          </TouchableOpacity>
                        </View>
                      </View>
                    )}
                  </View>
                )}
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
              onPress={() => setEditDelay(d => Math.min(120, d + 5))}
            >
              <Text style={styles.stepperBtnText}>+</Text>
            </TouchableOpacity>
          </View>

          <Text style={styles.sectionLabel}>CUSTOM MESSAGE</Text>
          <TextInput
            style={styles.messageInput}
            value={editMessage}
            onChangeText={setEditMessage}
            placeholder="Is this intentional?"
            placeholderTextColor={L.muted}
            multiline
            maxLength={140}
          />

          <Text style={styles.sectionLabel}>RECURRING OVERLAY</Text>
          <View style={styles.recurringBlock}>
            <View style={styles.featureRow}>
              <Text style={styles.featureLabel}>Re-show overlay on a loop</Text>
              <Switch
                value={recurringOverlay}
                onValueChange={setRecurringOverlay}
                trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                thumbColor="#FFFFFF"
              />
            </View>
            {recurringOverlay && (
              <>
                <View style={styles.intervalRow}>
                  <Text style={styles.intervalLabel}>Gap between overlays</Text>
                  <View style={styles.intervalStepper}>
                    <TouchableOpacity
                      style={styles.stepperBtn}
                      onPress={() =>
                        setOverlayInterval(v => Math.max(1, v - 1))
                      }
                    >
                      <Text style={styles.stepperBtnText}>−</Text>
                    </TouchableOpacity>
                    <Text style={styles.intervalValue}>{overlayInterval}s</Text>
                    <TouchableOpacity
                      style={styles.stepperBtn}
                      onPress={() =>
                        setOverlayInterval(v => Math.min(300, v + 1))
                      }
                    >
                      <Text style={styles.stepperBtnText}>+</Text>
                    </TouchableOpacity>
                  </View>
                </View>
                <Text style={styles.recurringInfo}>
                  While this mode is active, the {editDelay}s overlay re-appears
                  every {overlayInterval}s on your blocked apps until you leave
                  them.
                </Text>
              </>
            )}
          </View>

          <Text style={styles.sectionLabel}>NOTIFICATION</Text>
          <View style={styles.recurringBlock}>
            <View style={styles.featureRow}>
              <Text style={styles.featureLabel}>
                Show notification while active
              </Text>
              <Switch
                value={persistentNotif}
                onValueChange={setPersistentNotif}
                trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                thumbColor="#FFFFFF"
              />
            </View>
            <Text style={styles.recurringInfo}>
              Keeps an ongoing notification visible the whole time this mode is
              on. Requires mode notifications to be enabled on the Modes screen.
            </Text>
          </View>

          <Text style={styles.sectionLabel}>SCHEDULE (optional)</Text>
          <TouchableOpacity
            style={styles.quickTestBtn}
            onPress={handleQuickTest}
          >
            <Text style={styles.quickTestBtnText}>
              ⚡ Quick test (start in 2 min)
            </Text>
          </TouchableOpacity>
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
                <TimePicker value={scheduleStart} onChange={setScheduleStart} />
              </View>
              <View style={styles.scheduleTimeRow}>
                <Text style={styles.scheduleTimeLabel}>Ends at</Text>
                <TimePicker value={scheduleEnd} onChange={setScheduleEnd} />
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
