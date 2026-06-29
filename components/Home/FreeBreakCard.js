/**
 * FreeBreakCard.js — "20-Min Free Break" card/button on the Home screen.
 * ─────────────────────────────────────────────────────────────────────────────
 * Three states driven by freeBreakStatus:
 *   • active   — green card with live countdown + "End Break Early"
 *   • usedToday — disabled pill with "Resets at midnight" caption
 *   • available — primary action pill to start a break
 * Renders nothing unless the free-break feature is enabled. Extracted from home.js.
 */

import React from 'react';
import { View, Text, TouchableOpacity } from 'react-native';
import { styles } from './home.styles';
import { formatBudgetTime } from '../common/format';

const FreeBreakCard = ({ freeBreakStatus, onStart, onEnd }) => {
  if (!freeBreakStatus?.enabled) return null;

  const { active, usedToday, remainingMs } = freeBreakStatus;

  if (active) {
    // Break in progress: green card with live countdown + early-end option
    return (
      <View style={styles.freeBreakCard}>
        <View style={styles.freeBreakCardHeader}>
          <Text style={styles.freeBreakCardTitle}>Free Break Active</Text>
          <View style={[styles.budgetDot, { backgroundColor: '#4CAF50' }]} />
        </View>
        <Text style={styles.freeBreakCountdown}>
          {formatBudgetTime(remainingMs)} remaining
        </Text>
        <Text style={styles.freeBreakSubtext}>
          Scroll freely — no interruptions until the timer ends.
        </Text>
        <TouchableOpacity
          style={styles.freeBreakEndButton}
          onPress={onEnd}
          activeOpacity={0.75}
          accessibilityRole="button"
          accessibilityLabel="End free break early"
        >
          <Text style={styles.freeBreakEndButtonText}>End Break Early</Text>
        </TouchableOpacity>
      </View>
    );
  }

  if (usedToday) {
    // Already used today: disabled pill. Caption tells the user WHEN it unlocks
    // again so the dead-end state has a clear resolution.
    return (
      <View style={styles.freeBreakDisabledWrap}>
        <TouchableOpacity
          style={[styles.freeBreakButton, styles.freeBreakButtonDisabled]}
          disabled={true}
          activeOpacity={1}
          accessibilityRole="button"
          accessibilityLabel="Free break already used today, resets at midnight"
          accessibilityState={{ disabled: true }}
        >
          <Text style={styles.freeBreakButtonTextDisabled}>
            Free Break Used Today
          </Text>
        </TouchableOpacity>
        <Text style={styles.freeBreakResetCaption}>Resets at midnight</Text>
      </View>
    );
  }

  // Available: primary action pill
  return (
    <TouchableOpacity
      style={styles.freeBreakButton}
      onPress={onStart}
      activeOpacity={0.85}
      accessibilityRole="button"
      accessibilityLabel="Start 20-minute free break"
    >
      <Text style={styles.freeBreakButtonText}>Start 20-Min Free Break</Text>
    </TouchableOpacity>
  );
};

export default FreeBreakCard;
