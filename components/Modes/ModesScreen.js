import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  View,
  Text,
  Switch,
  TouchableOpacity,
  ScrollView,
  Animated,
  LayoutAnimation,
  Platform,
  UIManager,
  AppState,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Path } from 'react-native-svg';
import ModeMetaSheet from './ModeMetaSheet';
import ModeIcon from '../shared/ModeIcons';
import { styles, L } from './ModesScreen.styles';

if (
  Platform.OS === 'android' &&
  UIManager.setLayoutAnimationEnabledExperimental
) {
  UIManager.setLayoutAnimationEnabledExperimental(true);
}

const { SettingsModule, VPNModule } = require('react-native').NativeModules;

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

const ModeCard = ({ modeId, mode, isActive, onToggleActive, onOpenDetails }) => {
  const accent = mode.color || L.charcoal;

  return (
    <View
      style={[
        styles.modeCard,
        mode.enabled && { borderColor: mode.color || L.charcoal },
        isActive && { borderColor: mode.color || L.charcoal, borderWidth: 2 },
      ]}
    >
      <View style={styles.modeCardHeader}>
        <View style={[styles.modeIconTile, { backgroundColor: accent + '1F' }]}>
          <ModeIcon name={mode.icon} size={22} color={accent} />
        </View>
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
        onPress={() => onOpenDetails(modeId, mode)}
        style={styles.modeEditLink}
      >
        <Text style={styles.modeEditLinkText}>Rename &amp; schedule ▸</Text>
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
  const [activeModeId, setActiveModeId] = useState(null);
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
    const loadData = async () => {
      console.log('[ModesScreen] loading modes and active mode');
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
        } catch (_) {}
        if (!parsedModes || Object.keys(parsedModes).length === 0) {
          parsedModes = DEFAULT_MODES;
        }

        let needsSave = false;
        Object.keys(parsedModes).forEach(id => {
          const shouldBeEnabled = id === activeId;
          if (parsedModes[id].enabled !== shouldBeEnabled) {
            parsedModes[id].enabled = shouldBeEnabled;
            needsSave = true;
          }
        });

        if (needsSave) {
          SettingsModule.saveModes(JSON.stringify(parsedModes));
        }

        setModes(parsedModes);
      } catch (e) {
        console.warn('[ModesScreen] load error:', e);
      }
    };
    loadData();

    const sub = AppState.addEventListener('change', nextState => {
      if (nextState === 'active') {
        loadData();
      }
    });

    return () => sub?.remove();
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
      Promise.resolve(nativeCall)
        .then(() => {
          SettingsModule.getActiveMode(id => {
            setActiveModeId(id || null);
            // Native fallback to default on deactivate
            if (!newValue && id === 'default' && normalized['default']) {
              setModes(prev => {
                const next = { ...prev };
                Object.keys(next).forEach(
                  k => (next[k].enabled = k === 'default'),
                );
                SettingsModule.saveModes(JSON.stringify(next));
                return next;
              });
            }
          });
        })
        .catch(e =>
          console.warn('[ModesScreen] native mode toggle failed:', e),
        );
      showSaved();
    },
    [modes, showSaved],
  );

  // Opens the metadata sheet (name / icon / colour / schedule) for a mode. The
  // mode's blocking is edited from the home screen while it is active, not here.
  const handleOpenDetails = useCallback((modeId, mode) => {
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

  // Note: we deliberately do NOT prompt for the Android "Alarms & reminders"
  // (exact alarm) permission here. Scheduled modes fall back to inexact
  // alarms natively (ModeManager.setExactAlarm), which is good enough and
  // avoids an extra permission ask during mode creation.
  const handleSave = useCallback(
    (modeId, updatedMeta) => {
      console.log('[ModesScreen] saving mode metadata:', modeId);
      LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);

      // Merge ONLY the metadata (name/icon/color/schedule) onto the existing
      // mode so its policy_overrides / setting_overrides — edited from the home
      // screen — are preserved. A brand-new mode starts from an empty-override
      // skeleton so it has a valid shape before the user activates + edits it.
      const base = modes[modeId] || {
        enabled: false,
        policy_overrides: {},
        setting_overrides: { delay_time_seconds: 15 },
      };
      const updatedModes = {
        ...modes,
        [modeId]: { ...base, ...updatedMeta },
      };
      setModes(updatedModes);
      SettingsModule.saveModes(JSON.stringify(updatedModes));

      setModalVisible(false);
      showSaved();
    },
    [modes, showSaved],
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
              {activeModeId && modes[activeModeId]
                ? modes[activeModeId].name
                : 'A mode'}{' '}
              active
            </Text>
          </View>
        )}

        <Text style={styles.sectionLabel}>YOUR MODES</Text>
        <Text style={styles.sectionCaption}>
          Turn a mode on to make it active, then edit what it blocks from the
          home screen. Use “Rename &amp; schedule” to change its name or times.
        </Text>

        {modesList.map(([id, mode]) => (
          <ModeCard
            key={id}
            modeId={id}
            mode={mode}
            isActive={id === activeModeId}
            onToggleActive={handleToggleActive}
            onOpenDetails={handleOpenDetails}
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
            Only one mode is active at a time. Whatever mode is on is the one the
            home screen edits — turn a mode on, then change what it blocks from
            the home screen and its managed apps. Schedules can turn a mode on
            automatically at set times.
          </Text>
        </View>
      </ScrollView>

      <Animated.View
        style={[styles.savedToast, { opacity: savedOpacity }]}
        pointerEvents="none"
      >
        <Text style={styles.savedToastText}>Saved</Text>
      </Animated.View>

      <ModeMetaSheet
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

export default ModesScreen;
