/**
 * SettingsLockGate
 * ----------------
 * Read-only card shown in place of a scope's settings while that scope is locked
 * by the opt-in Settings Change Lock. Tells the user they have to wait before they
 * can change these settings, with a live countdown.
 *
 * Purely presentational — the parent decides when to render it (lock.locked) and
 * passes the remaining time. The countdown ticks in the parent hook.
 *
 * Logging prefix: [SettingsLock]
 */

import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import Svg, { Path } from 'react-native-svg';

/** Formats a remaining duration (ms) as a compact "1d 4h" / "3h 12m" / "45m". */
function formatRemaining(ms) {
  if (ms <= 0) return 'any moment now';
  const totalMin = Math.ceil(ms / 60000);
  const days = Math.floor(totalMin / (60 * 24));
  const hours = Math.floor((totalMin % (60 * 24)) / 60);
  const mins = totalMin % 60;
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${mins}m`;
  return `${mins}m`;
}

const LockIcon = ({ color = '#1A1A1A', size = 22 }) => (
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
    <Path d="M5 11h14v9a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1z" />
    <Path d="M8 11V7a4 4 0 0 1 8 0v4" />
  </Svg>
);

/**
 * @param {{ remainingMs: number, scopeLabel?: string }} props
 *   remainingMs — ms until the scope unlocks.
 *   scopeLabel  — friendly name shown in the copy (e.g. "these settings", "Instagram").
 */
export default function SettingsLockGate({
  remainingMs,
  scopeLabel = 'these settings',
}) {
  return (
    <View style={styles.card} accessibilityRole="summary">
      <View style={styles.iconWrap}>
        <LockIcon />
      </View>
      <Text style={styles.title}>Settings locked</Text>
      <Text style={styles.body}>
        You chose to lock {scopeLabel} for a while after changing them. You can
        edit again in:
      </Text>
      <Text style={styles.countdown}>{formatRemaining(remainingMs)}</Text>
      <Text style={styles.hint}>
        This is the commitment you set for yourself. It lifts on its own when
        the timer ends — there’s no shortcut, by design.
      </Text>
    </View>
  );
}

const INK = '#1A1A1A';
const styles = StyleSheet.create({
  card: {
    backgroundColor: '#FFFFFF',
    borderWidth: 1,
    borderColor: 'rgba(0,0,0,0.07)',
    borderRadius: 16,
    paddingVertical: 28,
    paddingHorizontal: 22,
    alignItems: 'center',
    marginBottom: 18,
  },
  iconWrap: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: '#F2F0EC',
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 14,
  },
  title: { fontSize: 18, fontWeight: '700', color: INK, marginBottom: 8 },
  body: {
    fontSize: 13.5,
    lineHeight: 20,
    color: '#737373',
    textAlign: 'center',
    marginBottom: 14,
  },
  countdown: {
    fontSize: 30,
    fontWeight: '800',
    color: INK,
    letterSpacing: 0.5,
    marginBottom: 14,
  },
  hint: {
    fontSize: 12,
    lineHeight: 17,
    color: '#9A9A9A',
    textAlign: 'center',
  },
});
