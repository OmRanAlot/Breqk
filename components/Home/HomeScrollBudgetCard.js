/**
 * HomeScrollBudgetCard.js — "Scroll Budget" card on the Home screen.
 * ─────────────────────────────────────────────────────────────────────────────
 * Shows time remaining in the current scroll-budget window (green = available,
 * red = exhausted with a reset countdown). Renders nothing when no budget
 * status is available. Extracted from home.js.
 */

import React from 'react';
import { View, Text } from 'react-native';
import { styles, L } from './home.styles';
import { formatBudgetTime } from '../common/format';

const HomeScrollBudgetCard = ({ budgetStatus }) => {
  if (!budgetStatus) return null;

  // Defensive guard: remainingMs=0 with canScroll=true is a stuck 0:00 state.
  const canScroll = budgetStatus.canScroll && budgetStatus.remainingMs > 0;
  const statusColor = canScroll ? L.accentGreen : '#E53935';
  const allowanceMs = budgetStatus.allowanceMinutes * 60 * 1000;
  const statusLabel = canScroll
    ? `${formatBudgetTime(budgetStatus.remainingMs)} remaining`
    : `Resets in ${formatBudgetTime(budgetStatus.nextScrollAtMs - Date.now())}`;
  const filledRatio = canScroll
    ? Math.min(1, budgetStatus.usedMs / allowanceMs || 0)
    : 1;

  return (
    <View style={styles.budgetCard}>
      <View style={styles.budgetHeader}>
        <Text style={styles.sectionTitle}>Scroll Budget</Text>
        <View style={[styles.budgetDot, { backgroundColor: statusColor }]} />
      </View>
      <Text style={[styles.budgetStatusLabel, { color: statusColor }]}>
        {statusLabel}
      </Text>
      {/* Progress bar: filled = time used, unfilled = time remaining */}
      <View style={styles.budgetProgressBg}>
        <View
          style={{
            flex: filledRatio,
            backgroundColor: statusColor,
            borderRadius: 2,
          }}
        />
        <View style={{ flex: Math.max(0, 1 - filledRatio) }} />
      </View>
      <Text style={styles.budgetCaption}>
        {budgetStatus.allowanceMinutes}m allowed per{' '}
        {budgetStatus.windowMinutes}m window
      </Text>
    </View>
  );
};

export default HomeScrollBudgetCard;
