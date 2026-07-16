/**
 * ModeGateBanner.js — "a mode owns these settings" notice.
 * ─────────────────────────────────────────────────────────────────────────────
 * Rendered at the top of Customize and AppDetail whenever a non-default mode is
 * active (see shared/useDefaultModeGate). Explains WHY the controls below are
 * frozen and offers the one action that unfreezes them: switching back to
 * Default mode.
 *
 * Styled in the active mode's own colour so it reads as "Bedtime is doing this",
 * not as a generic error.
 *
 * Distinct from Customize/SettingsLockGate, which covers the settings-lock
 * countdown — an orthogonal lock. Both can be showing at once.
 *
 * Logging prefix: [ModeGate]
 */

import React from 'react';
import { View, Text, TouchableOpacity, Alert } from 'react-native';
import ModeIcon from './ModeIcons';
import { styles, L } from './ModeGateBanner.styles';

/**
 * @param {string}   modeName          Active mode's display name, e.g. "Bedtime".
 * @param {string}   modeColor         Active mode's hex accent, e.g. "#7C4DFF".
 * @param {string}   modeIcon          Icon key from shared/ModeIcons.
 * @param {Function} onSwitchToDefault Drops back to Default mode.
 * @param {string}   scopeLabel        What is frozen — "these settings" on
 *                                     Customize, "Instagram's settings" on
 *                                     AppDetail. Keeps the copy specific to the
 *                                     screen the banner sits on.
 */
const ModeGateBanner = ({
  modeName,
  modeColor,
  modeIcon,
  onSwitchToDefault,
  scopeLabel = 'these settings',
}) => {
  const accent = modeColor || L.charcoal;
  const name = modeName || 'A mode';

  // Confirm before switching. Ending a mode defeats the friction the user set up
  // for themselves, so it must be a deliberate act — never a stray tap while
  // poking around a settings screen.
  const handleSwitch = () => {
    Alert.alert(
      'Switch to Default mode?',
      name +
        ' will end and its settings will stop applying. You can turn it back on from Modes.',
      [
        { text: 'Keep ' + name, style: 'cancel' },
        {
          text: 'Switch to Default',
          style: 'destructive',
          onPress: () => {
            console.log('[ModeGate] user confirmed switch to Default');
            onSwitchToDefault();
          },
        },
      ],
    );
  };

  return (
    <View style={[styles.banner, { borderColor: accent + '40' }]}>
      <View style={[styles.rail, { backgroundColor: accent }]} />

      <View style={styles.content}>
        <View style={styles.headerRow}>
          <View style={[styles.iconTile, { backgroundColor: accent + '1F' }]}>
            <ModeIcon name={modeIcon || 'focus'} size={18} color={accent} />
          </View>
          <Text style={styles.title} numberOfLines={1}>
            {name} is active
          </Text>
        </View>

        <Text style={styles.body}>
          {name} is controlling {scopeLabel}. Switch to Default mode to change
          them.
        </Text>

        <TouchableOpacity
          style={[styles.switchBtn, { backgroundColor: accent }]}
          onPress={handleSwitch}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel={'Switch to Default mode to edit ' + scopeLabel}
        >
          <Text style={styles.switchBtnText}>Switch to Default</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
};

export default ModeGateBanner;
