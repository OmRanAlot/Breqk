/**
 * HomeScrollBudgetCard.js — "Scroll Budget" card on the Home screen.
 * ─────────────────────────────────────────────────────────────────────────────
 * Shows time remaining in the current scroll-budget window (green = available,
 * red = exhausted with a reset countdown). Renders nothing when no budget
 * status is available. Extracted from home.js.
 */

import React, { useEffect, useRef } from 'react';
import { Animated, View, Text, TouchableOpacity } from 'react-native';
import { styles, L } from './home.styles';
import { formatBudgetTime } from '../common/format';
import { BAR_FILL_DURATION_MS, FILL_EASING } from '../common/useCountUp';

const HomeScrollBudgetCard = ({ budgetStatus, onPress }) => {
  // Defensive guard: remainingMs=0 with canScroll=true is a stuck 0:00 state.
  const canScroll = budgetStatus?.canScroll && budgetStatus?.remainingMs > 0;
  const statusColor = canScroll ? L.accentGreen : '#E53935';
  const allowanceMs = (budgetStatus?.allowanceMinutes || 0) * 60 * 1000;
  const filledRatio = budgetStatus
    ? canScroll
      ? Math.min(1, budgetStatus.usedMs / allowanceMs || 0)
      : 1
    : 0;

  // Fills in from 0 → filledRatio once, the first time budgetStatus arrives.
  // Later 2s poll refreshes just jump the bar to its new ratio — otherwise
  // the fill-in would visibly replay every 2 seconds.
  const fillAnim = useRef(new Animated.Value(0)).current;
  const hasAnimatedRef = useRef(false);
  useEffect(() => {
    if (!budgetStatus) return;
    if (!hasAnimatedRef.current) {
      hasAnimatedRef.current = true;
      Animated.timing(fillAnim, {
        toValue: filledRatio,
        duration: BAR_FILL_DURATION_MS,
        easing: FILL_EASING,
        useNativeDriver: false,
      }).start();
    } else {
      fillAnim.setValue(filledRatio);
    }
  }, [budgetStatus, filledRatio, fillAnim]);

  if (!budgetStatus) return null;

  const statusLabel = canScroll
    ? `${formatBudgetTime(budgetStatus.remainingMs)} remaining`
    : `Resets in ${formatBudgetTime(budgetStatus.nextScrollAtMs - Date.now())}`;

  return (
    <TouchableOpacity
      style={styles.budgetCard}
      activeOpacity={onPress ? 0.7 : 1}
      onPress={onPress}
      disabled={!onPress}
      accessibilityRole={onPress ? 'button' : undefined}
      accessibilityLabel="Scroll Budget settings"
    >
      <View style={styles.budgetHeader}>
        <Text style={styles.sectionTitle}>Scroll Budget</Text>
        <View style={[styles.budgetDot, { backgroundColor: statusColor }]} />
      </View>
      <Text style={[styles.budgetStatusLabel, { color: statusColor }]}>
        {statusLabel}
      </Text>
      {/* Progress bar: filled = time used, unfilled = time remaining */}
      <View style={styles.budgetProgressBg}>
        <Animated.View
          style={{
            width: fillAnim.interpolate({
              inputRange: [0, 1],
              outputRange: ['0%', '100%'],
            }),
            height: '100%',
            backgroundColor: statusColor,
            borderRadius: 2,
          }}
        />
      </View>
      <Text style={styles.budgetCaption}>
        {budgetStatus.allowanceMinutes}m allowed per{' '}
        {budgetStatus.windowMinutes}m window
      </Text>
    </TouchableOpacity>
  );
};

export default HomeScrollBudgetCard;
