import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  Switch,
  TouchableOpacity,
  ScrollView,
  Animated,
  LayoutAnimation,
  Platform,
  UIManager,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Path } from 'react-native-svg';
import ModeEditorModal from './ModeEditorModal';

if (
  Platform.OS === 'android' &&
  UIManager.setLayoutAnimationEnabledExperimental
) {
  UIManager.setLayoutAnimationEnabledExperimental(true);
}

const { SettingsModule, VPNModule } = require('react-native').NativeModules;

const L = {
  bg: '#FAFAFA',
  charcoal: '#1A1A1A',
  muted: '#737373',
  border: '#E5E5E5',
  cardBg: '#FFFFFF',
  cardBorder: 'rgba(0,0,0,0.07)',
};

const MODE_ICONS = {
  book: '📖',
  moon: '🌙',
  dumbbell: '💪',
  focus: '🎯',
  coffee: '☕',
  work: '💼',
  default: '⚡',
};

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

const ModeCard = ({ modeId, mode, isActive, onToggleActive, onEdit }) => {
  const icon = MODE_ICONS[mode.icon] || MODE_ICONS.default;

  return (
    <View
      style={[
        styles.modeCard,
        mode.enabled && { borderColor: mode.color || L.charcoal },
        isActive && { borderColor: mode.color || L.charcoal, borderWidth: 2 },
      ]}
    >
      <View style={styles.modeCardHeader}>
        <Text style={styles.modeIcon}>{icon}</Text>
        <View style={styles.modeCardInfo}>
          <Text style={styles.modeCardName}>{mode.name}</Text>
          <Text style={styles.modeCardSummary} numberOfLines={2}>
            {generateModeSummary(mode)}
          </Text>
        </View>
        <Switch
          value={mode.enabled === true}
          onValueChange={val => onToggleActive(modeId, val)}
          trackColor={{ false: '#D6D6D6', true: mode.color || L.charcoal }}
          thumbColor="#FFFFFF"
        />
      </View>

      <TouchableOpacity
        onPress={() => onEdit(modeId, mode)}
        style={styles.modeEditLink}
      >
        <Text style={styles.modeEditLinkText}>
          {mode.enabled ? 'Edit active mode ▸' : 'Edit ▸'}
        </Text>
      </TouchableOpacity>

      {isActive && (
        <View
          style={[styles.activeBadge, { backgroundColor: mode.color + '20' }]}
        >
          <Text style={[styles.activeBadgeText, { color: mode.color }]}>
            Active Now
          </Text>
        </View>
      )}
    </View>
  );
};

const ModesScreen = ({ navigation }) => {
  const insets = useSafeAreaInsets();

  const [modes, setModes] = useState({});
  const [editingModeId, setEditingModeId] = useState(null);
  const [editingMode, setEditingMode] = useState(null);
  const [modalVisible, setModalVisible] = useState(false);
  const [isNewMode, setIsNewMode] = useState(false);

  const savedOpacity = useRef(new Animated.Value(0)).current;
  const savedTimer = useRef(null);

  const showSaved = useCallback(() => {
    if (savedTimer.current) clearTimeout(savedTimer.current);
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

  useEffect(() => {
    const load = async () => {
      console.log('[ModesScreen] loading modes');
      try {
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
          '[ModesScreen] modes loaded:',
          Object.keys(parsedModes).length,
        );
      } catch (e) {
        console.warn('[ModesScreen] load error:', e);
      }
    };
    load();
  }, []);

  const handleToggleActive = useCallback(
    (modeId, newValue) => {
      console.log('[ModesScreen] mode toggle:', modeId, '→', newValue);
      LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
      // Native ModeManager supports one active mode at a time. Mirror that in
      // the persisted JSON so the UI never shows two "enabled" at once.
      const normalized = { ...modes };
      if (newValue) {
        Object.keys(normalized).forEach(id => {
          normalized[id] = { ...normalized[id], enabled: id === modeId };
        });
      } else {
        normalized[modeId] = { ...normalized[modeId], enabled: false };
      }
      setModes(normalized);
      SettingsModule.saveModes(JSON.stringify(normalized));

      // Drive the native ModeManager so scheduling + policy overrides apply.
      const nativeCall = newValue
        ? VPNModule.activateMode(modeId)
        : VPNModule.deactivateMode();
      Promise.resolve(nativeCall).catch(e =>
        console.warn('[ModesScreen] native mode toggle failed:', e),
      );
      showSaved();
    },
    [modes, showSaved],
  );

  const handleEdit = useCallback((modeId, mode) => {
    setEditingModeId(modeId);
    setEditingMode(mode);
    setIsNewMode(false);
    setModalVisible(true);
  }, []);

  const handleCreate = useCallback(() => {
    setEditingModeId('new_' + Date.now());
    setEditingMode({
      name: 'New Mode',
      icon: 'focus',
      color: '#4CAF50',
      enabled: false,
      policy_overrides: {},
      setting_overrides: { delay_time_seconds: 15 },
      schedule: null,
    });
    setIsNewMode(true);
    setModalVisible(true);
  }, []);

  const handleSave = useCallback(
    (modeId, updatedMode) => {
      console.log('[ModesScreen] saving mode:', modeId);
      LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);

      if (isNewMode) {
        const updatedModes = { ...modes, [modeId]: updatedMode };
        setModes(updatedModes);
        SettingsModule.saveModes(JSON.stringify(updatedModes));
      } else {
        const updatedModes = { ...modes, [modeId]: updatedMode };
        setModes(updatedModes);
        SettingsModule.saveModes(JSON.stringify(updatedModes));
      }

      setModalVisible(false);
      showSaved();
    },
    [modes, isNewMode, showSaved],
  );

  const handleDelete = useCallback(
    modeId => {
      console.log('[ModesScreen] deleting mode:', modeId);
      LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
      const updatedModes = { ...modes };
      delete updatedModes[modeId];
      setModes(updatedModes);
      SettingsModule.saveModes(JSON.stringify(updatedModes));
      setModalVisible(false);
      showSaved();
    },
    [modes, showSaved],
  );

  const handleCloseModal = useCallback(() => {
    if (isNewMode) {
      LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
      const updatedModes = { ...modes };
      delete updatedModes[editingModeId];
      setModes(updatedModes);
    }
    setModalVisible(false);
  }, [isNewMode, modes, editingModeId]);

  const activeModes = Object.entries(modes)
    .filter(([_, mode]) => mode.enabled)
    .map(([id, _]) => id);

  const modesList = Object.entries(modes);

  return (
    <View style={[styles.container, { paddingTop: Math.max(insets.top, 0) }]}>
      <View style={styles.header}>
        <TouchableOpacity
          style={styles.backButton}
          activeOpacity={0.7}
          onPress={() => navigation.goBack()}
        >
          <BackIcon color={L.charcoal} size={24} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>Modes</Text>
        <View style={styles.headerSpacer} />
      </View>

      <ScrollView
        style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}
      >
        {activeModes.length > 0 && (
          <View style={styles.activeBanner}>
            <Text style={styles.activeBannerText}>
              {activeModes.length} mode{activeModes.length > 1 ? 's' : ''}{' '}
              active
            </Text>
          </View>
        )}

        <Text style={styles.sectionLabel}>YOUR MODES</Text>
        <Text style={styles.sectionCaption}>
          Enable a mode to override your base settings. Tap a card to customize
          it.
        </Text>

        {modesList.map(([id, mode]) => (
          <ModeCard
            key={id}
            modeId={id}
            mode={mode}
            isActive={mode.enabled}
            onToggleActive={handleToggleActive}
            onEdit={handleEdit}
          />
        ))}

        <TouchableOpacity
          style={styles.createModeBtn}
          onPress={handleCreate}
          activeOpacity={0.7}
        >
          <Text style={styles.createModeBtnText}>+ Create Mode</Text>
        </TouchableOpacity>

        <View style={styles.infoSection}>
          <Text style={styles.infoTitle}>How modes work</Text>
          <Text style={styles.infoText}>
            Modes temporarily override your base settings when enabled. You can
            schedule modes to activate automatically at specific times, or
            enable them manually for on-demand control.
          </Text>
        </View>
      </ScrollView>

      <Animated.View
        style={[styles.savedToast, { opacity: savedOpacity }]}
        pointerEvents="none"
      >
        <Text style={styles.savedToastText}>Saved</Text>
      </Animated.View>

      <ModeEditorModal
        visible={modalVisible}
        mode={editingMode}
        modeId={editingModeId}
        onSave={handleSave}
        onDelete={handleDelete}
        onClose={handleCloseModal}
        isNew={isNewMode}
      />
    </View>
  );
};

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
  },
  headerSpacer: { width: 36 },
  scroll: {
    flex: 1,
  },
  scrollContent: {
    paddingHorizontal: 24,
    paddingTop: 28,
    paddingBottom: 48,
  },
  activeBanner: {
    backgroundColor: 'rgba(0,0,0,0.06)',
    borderRadius: 10,
    paddingVertical: 10,
    paddingHorizontal: 14,
    marginBottom: 20,
  },
  activeBannerText: {
    fontSize: 13,
    fontWeight: '500',
    color: L.charcoal,
    textAlign: 'center',
  },
  sectionLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: L.charcoal,
    textTransform: 'uppercase',
    letterSpacing: 1.5,
    marginBottom: 8,
  },
  sectionCaption: {
    fontSize: 13,
    color: L.muted,
    lineHeight: 18,
    marginBottom: 16,
  },
  modeCard: {
    backgroundColor: L.cardBg,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: L.cardBorder,
    padding: 16,
    marginBottom: 12,
  },
  modeCardHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  modeIcon: {
    fontSize: 24,
  },
  modeCardInfo: {
    flex: 1,
  },
  modeCardName: {
    fontSize: 16,
    fontWeight: '600',
    color: L.charcoal,
  },
  modeCardSummary: {
    fontSize: 12,
    color: L.muted,
    marginTop: 2,
    lineHeight: 16,
  },
  modeEditLink: {
    marginTop: 10,
    paddingTop: 10,
    borderTopWidth: 1,
    borderTopColor: L.border,
  },
  modeEditLinkText: {
    fontSize: 13,
    color: L.muted,
    fontWeight: '500',
  },
  activeBadge: {
    position: 'absolute',
    top: 10,
    right: 10,
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 6,
  },
  activeBadgeText: {
    fontSize: 10,
    fontWeight: '600',
    textTransform: 'uppercase',
  },
  createModeBtn: {
    backgroundColor: L.charcoal,
    borderRadius: 14,
    paddingVertical: 16,
    alignItems: 'center',
    marginTop: 8,
  },
  createModeBtnText: {
    fontSize: 16,
    fontWeight: '500',
    color: '#FFFFFF',
  },
  infoSection: {
    marginTop: 32,
    padding: 16,
    backgroundColor: L.cardBg,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: L.cardBorder,
  },
  infoTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: L.charcoal,
    marginBottom: 8,
  },
  infoText: {
    fontSize: 13,
    color: L.muted,
    lineHeight: 18,
  },
  savedToast: {
    position: 'absolute',
    bottom: 40,
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  savedToastText: {
    backgroundColor: L.charcoal,
    color: '#FFFFFF',
    fontSize: 14,
    fontWeight: '500',
    paddingHorizontal: 20,
    paddingVertical: 10,
    borderRadius: 20,
    overflow: 'hidden',
  },
});

export default ModesScreen;
