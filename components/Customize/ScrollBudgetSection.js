/**
 * ScrollBudgetSection.js — "Scroll Budget" section of the Customize screen.
 * ─────────────────────────────────────────────────────────────────────────────
 * Presentational: renders the allowance/window steppers and the live budget
 * status row. All state + handlers are owned by the Customize screen and passed
 * in as props. Extracted from customize.js to keep that screen focused.
 *
 * Logging prefix: [Customize]
 */

import React from 'react';
import { View, Text, TouchableOpacity } from 'react-native';
import { styles } from './customize.styles';
import { formatBudgetTime } from '../common/format';

/**
 * @param {boolean} disabled  True while a non-default mode owns the settings
 *                            (see shared/useDefaultModeGate). The steppers grey
 *                            out and stop responding. The live status row below
 *                            stays visible on purpose: the budget is still
 *                            running under the mode's rules, and the user should
 *                            be able to see where it stands.
 */
const ScrollBudgetSection = ({
  scrollAllowance,
  scrollWindow,
  budgetStatus,
  adjustAllowance,
  adjustWindow,
  disabled = false,
}) => (
  <View style={styles.section}>
    <Text style={styles.sectionLabel}>Scroll Budget</Text>

    <View style={[styles.budgetControls, disabled && styles.sectionDisabled]}>
      <View style={styles.stepperGroup}>
        <TouchableOpacity
          style={styles.stepperBtn}
          onPress={() => adjustAllowance(-1)}
          disabled={disabled}
          accessibilityRole="button"
          accessibilityLabel="Decrease allowance"
          accessibilityState={{ disabled }}
        >
          <Text style={styles.stepperBtnText}>−</Text>
        </TouchableOpacity>
        <Text style={styles.stepperValue}>{scrollAllowance}m</Text>
        <TouchableOpacity
          style={styles.stepperBtn}
          onPress={() => adjustAllowance(1)}
          disabled={disabled}
          accessibilityRole="button"
          accessibilityLabel="Increase allowance"
          accessibilityState={{ disabled }}
        >
          <Text style={styles.stepperBtnText}>+</Text>
        </TouchableOpacity>
      </View>

      <Text style={styles.budgetDivider}>per</Text>

      <View style={styles.stepperGroup}>
        <TouchableOpacity
          style={styles.stepperBtn}
          onPress={() => adjustWindow(-15)}
          disabled={disabled}
          accessibilityRole="button"
          accessibilityLabel="Decrease window"
          accessibilityState={{ disabled }}
        >
          <Text style={styles.stepperBtnText}>−</Text>
        </TouchableOpacity>
        <Text style={styles.stepperValue}>{scrollWindow}m</Text>
        <TouchableOpacity
          style={styles.stepperBtn}
          onPress={() => adjustWindow(15)}
          disabled={disabled}
          accessibilityRole="button"
          accessibilityLabel="Increase window"
          accessibilityState={{ disabled }}
        >
          <Text style={styles.stepperBtnText}>+</Text>
        </TouchableOpacity>
      </View>
    </View>

    {scrollAllowance === 0 && (
      <Text style={styles.budgetWarning}>
        0m allowance = Reels blocked immediately on every attempt.
      </Text>
    )}

    {/* Live status row */}
    {budgetStatus &&
      (() => {
        // Defensive guard: if remainingMs is 0 but canScroll is still true
        // (can happen briefly before native reconciliation), treat as exhausted.
        const canScroll =
          budgetStatus.canScroll && budgetStatus.remainingMs > 0;
        const statusColor = canScroll ? '#4CAF50' : '#E53935';
        const statusLabel = canScroll
          ? `${formatBudgetTime(budgetStatus.remainingMs)} remaining`
          : `Scroll again in ${formatBudgetTime(
              budgetStatus.nextScrollAtMs - Date.now(),
            )}`;
        const filledRatio = canScroll
          ? Math.min(
              1,
              budgetStatus.usedMs / (scrollAllowance * 60 * 1000) || 0,
            )
          : 1;
        return (
          <View style={styles.budgetStatusSection}>
            <View style={styles.budgetStatusRow}>
              <View
                style={[styles.budgetDot, { backgroundColor: statusColor }]}
              />
              <Text style={[styles.budgetStatusText, { color: statusColor }]}>
                {statusLabel}
              </Text>
            </View>
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
          </View>
        );
      })()}
  </View>
);

export default ScrollBudgetSection;
