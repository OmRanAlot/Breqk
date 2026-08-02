/**
 * ActiveModeBanner.js — "a mode is running" card on the Home screen.
 * ─────────────────────────────────────────────────────────────────────────────
 * Replaces the old one-line "Bedtime mode" text in the status strip, which was
 * far too quiet for something that overrides every setting in the app.
 *
 * Doubles as the escape hatch: the home-screen settings edit whatever mode is
 * active, so this card's End button is how the user drops the mode and returns
 * to editing Default. (There is no read-only lock any more.)
 *
 * Never rendered for the "default" mode — that is the always-on baseline, not a
 * mode the user deliberately entered. Home decides that; this component just
 * renders what it is handed.
 *
 * Logging prefix: [Home]
 */

import React from 'react';
import { View, Text, TouchableOpacity, Alert } from 'react-native';
import ModeIcon from '../shared/ModeIcons';
import { formatTime12h } from '../shared/scheduleWindow';
import { styles, L } from './home.styles';

/**
 * Builds the subline: what this mode is actually enforcing right now. Kept to
 * the facts the user cares about — when it ends, how long the pause is, and
 * which apps are affected.
 *
 * @param {object}   mode              The active mode's JSON.
 * @param {number}   delaySecs         Effective forced-pause duration, seconds.
 * @param {string[]} interceptedLabels Display names of intercepted apps.
 * @returns {string[]} Lines to render.
 */
const buildSummary = (mode, delaySecs, interceptedLabels) => {
  const lines = [];

  const parts = [];
  // Schedule times are stored 24h ("22:00") and shown 12-hour ("10:00 PM").
  if (mode?.schedule?.end_time) {
    parts.push('Until ' + formatTime12h(mode.schedule.end_time));
  }
  if (typeof delaySecs === 'number' && delaySecs > 0) {
    parts.push(delaySecs + 's pause');
  }
  if (parts.length > 0) {
    lines.push(parts.join(' · '));
  }

  if (interceptedLabels && interceptedLabels.length > 0) {
    lines.push(interceptedLabels.join(' + ') + ' intercepted');
  }

  return lines;
};

/**
 * @param {object}   mode              Active mode's JSON: name, color, icon,
 *                                     schedule, setting_overrides.
 * @param {number}   delaySecs         Effective forced-pause duration, seconds.
 * @param {string[]} interceptedLabels Display names of apps this mode intercepts.
 * @param {Function} onEnd             Ends the mode (native falls back to Default).
 */
const ActiveModeBanner = ({ mode, delaySecs, interceptedLabels, onEnd }) => {
  if (!mode) return null;

  const accent = mode.color || L.charcoal;
  const summaryLines = buildSummary(mode, delaySecs, interceptedLabels);

  // Confirm before ending. The mode is friction the user set up for themselves;
  // one stray tap on the Home screen should not be able to dismantle it.
  const handleEnd = () => {
    Alert.alert(
      'End ' + mode.name + '?',
      'Your settings go back to Default. You can turn ' +
        mode.name +
        ' back on from Modes.',
      [
        { text: 'Keep it on', style: 'cancel' },
        {
          text: 'End mode',
          style: 'destructive',
          onPress: () => {
            console.log('[Home] user ended active mode:', mode.name);
            onEnd();
          },
        },
      ],
    );
  };

  return (
    <View style={[styles.activeModeCard, { borderColor: accent + '40' }]}>
      <View style={[styles.activeModeRail, { backgroundColor: accent }]} />

      <View style={styles.activeModeContent}>
        <View
          style={[
            styles.activeModeIconTile,
            { backgroundColor: accent + '1F' },
          ]}
        >
          <ModeIcon name={mode.icon || 'focus'} size={20} color={accent} />
        </View>

        <View style={styles.activeModeTitleGroup}>
          <Text
            style={[styles.activeModeTitle, { color: accent }]}
            numberOfLines={1}
          >
            {String(mode.name || 'Mode').toUpperCase()} ACTIVE
          </Text>
          {summaryLines.map(line => (
            <Text key={line} style={styles.activeModeSubline} numberOfLines={1}>
              {line}
            </Text>
          ))}
        </View>

        <TouchableOpacity
          style={[styles.activeModeEndBtn, { borderColor: accent + '55' }]}
          onPress={handleEnd}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel={'End ' + mode.name + ' mode'}
        >
          <Text style={[styles.activeModeEndBtnText, { color: accent }]}>
            End
          </Text>
        </TouchableOpacity>
      </View>
    </View>
  );
};

export default ActiveModeBanner;
