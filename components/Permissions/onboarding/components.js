/**
 * components.js — Shared presentational pieces for the Break onboarding flow.
 * ─────────────────────────────────────────────────────────────────────────────
 * Small, stateless building blocks reused across the eight onboarding screens:
 * the charcoal pill CTA, step header, progress indicators, reassurance card,
 * app rows, toggle, and segmented duration control. Visuals reproduce the
 * "Break Onboarding" design. Colours/sizing come from `theme.js`.
 */

import React from 'react';
import { View, Text, StyleSheet, TouchableOpacity } from 'react-native';
import { T } from './theme';
import { ShieldIcon, CheckIcon } from './icons';

/** Full-width charcoal pill — the primary CTA on every screen. */
export const PillButton = ({ label, onPress, disabled = false }) => (
  <TouchableOpacity
    style={[styles.pill, disabled && styles.pillDisabled]}
    onPress={onPress}
    disabled={disabled}
    activeOpacity={0.85}
    accessibilityRole="button"
    accessibilityLabel={label}
  >
    <Text style={styles.pillText}>{label}</Text>
  </TouchableOpacity>
);

/** Tiny uppercase section label (e.g. "Permissions", "Required to continue"). */
export const Eyebrow = ({ children, style }) => (
  <Text style={[styles.eyebrow, style]}>{children}</Text>
);

/**
 * Header row used on the setup steps: a "‹ Back" affordance on the left and a
 * step counter on the right ("Step 1 of 3").
 */
export const StepHeader = ({ onBack, stepLabel }) => (
  <View style={styles.headerRow}>
    <TouchableOpacity
      onPress={onBack}
      disabled={!onBack}
      activeOpacity={0.6}
      accessibilityRole="button"
      accessibilityLabel="Back"
    >
      <Text style={styles.backText}>‹ Back</Text>
    </TouchableOpacity>
    {stepLabel ? <Text style={styles.stepText}>{stepLabel}</Text> : null}
  </View>
);

/**
 * Pill-shaped progress dots: the active index renders as a wide bar, the rest
 * as small squares. Used on Welcome and the permission screens.
 */
export const ProgressDots = ({ total, active }) => (
  <View style={styles.dotsRow}>
    {Array.from({ length: total }).map((_, i) => (
      <View
        key={i}
        style={i === active ? styles.dotActive : styles.dotInactive}
      />
    ))}
  </View>
);

/** Shield-backed "no data collected" reassurance card on the permission screens. */
export const ReassuranceCard = ({ children }) => (
  <View style={styles.reassureCard}>
    <View style={styles.reassureIcon}>
      <ShieldIcon size={20} color={T.ink} strokeWidth={1.5} />
    </View>
    <Text style={styles.reassureText}>{children}</Text>
  </View>
);

/** Dark rounded monogram tile (e.g. "In", "Tk") shown beside an app name. */
export const Monogram = ({
  text,
  active = true,
  size = 40,
  radius = 11,
  fontSize = 17,
}) => (
  <View
    style={[
      styles.monogram,
      {
        width: size,
        height: size,
        borderRadius: radius,
        backgroundColor: active ? T.inkDeep : T.track,
      },
    ]}
  >
    <Text
      style={[
        styles.monogramText,
        { fontSize, color: active ? T.iconOnInk : T.dim },
      ]}
    >
      {text}
    </Text>
  </View>
);

/** Circular checkbox used in the app-selection list. */
export const SelectCircle = ({ selected }) =>
  selected ? (
    <View style={styles.checkCircleOn}>
      <CheckIcon size={13} color={T.iconOnInk} strokeWidth={3} />
    </View>
  ) : (
    <View style={styles.checkCircleOff} />
  );

/** Selectable app row for the "What pulls you in?" screen. */
export const AppSelectRow = ({ app, selected, onToggle }) => (
  <TouchableOpacity
    style={styles.appRow}
    onPress={onToggle}
    activeOpacity={0.7}
    accessibilityRole="checkbox"
    accessibilityState={{ checked: selected }}
    accessibilityLabel={app.label}
  >
    <Monogram text={app.monogram} active={selected} />
    <Text style={[styles.appName, !selected && styles.appNameMuted]}>
      {app.label}
    </Text>
    <SelectCircle selected={selected} />
  </TouchableOpacity>
);

/** iOS-style on/off switch matching the design's charcoal track. */
export const Toggle = ({ value, onChange, label }) => (
  <TouchableOpacity
    onPress={() => onChange(!value)}
    activeOpacity={0.8}
    accessibilityRole="switch"
    accessibilityState={{ checked: value }}
    accessibilityLabel={label}
    style={[
      styles.toggle,
      { backgroundColor: value ? T.inkDeep : T.controlBorder },
    ]}
  >
    <View
      style={[
        styles.toggleKnob,
        value ? styles.toggleKnobOn : styles.toggleKnobOff,
      ]}
    />
  </TouchableOpacity>
);

/** Segmented control for choosing a breath duration (5s / 15s / 30s). */
export const Segmented = ({
  options,
  value,
  onChange,
  format = v => `${v}s`,
}) => (
  <View style={styles.segmented}>
    {options.map(opt => {
      const active = opt === value;
      return (
        <TouchableOpacity
          key={opt}
          style={[styles.segment, active && styles.segmentActive]}
          onPress={() => onChange(opt)}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityState={{ selected: active }}
          accessibilityLabel={format(opt)}
        >
          <Text
            style={[styles.segmentText, active && styles.segmentTextActive]}
          >
            {format(opt)}
          </Text>
        </TouchableOpacity>
      );
    })}
  </View>
);

const styles = StyleSheet.create({
  pill: {
    width: '100%',
    height: 54,
    borderRadius: 27,
    backgroundColor: T.inkDeep,
    alignItems: 'center',
    justifyContent: 'center',
  },
  pillDisabled: { opacity: 0.4 },
  pillText: {
    color: T.onInk,
    fontSize: 16,
    fontWeight: '600',
    letterSpacing: 0.2,
  },

  eyebrow: {
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 1.5,
    color: T.label,
    textTransform: 'uppercase',
  },

  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 18,
  },
  backText: { fontSize: 13, color: T.faint, fontWeight: '600' },
  stepText: {
    fontSize: 12,
    fontWeight: '600',
    letterSpacing: 1.5,
    color: T.faint,
    textTransform: 'uppercase',
  },

  dotsRow: { flexDirection: 'row', gap: 6, alignItems: 'center' },
  dotActive: {
    width: 18,
    height: 6,
    borderRadius: 3,
    backgroundColor: T.inkDeep,
  },
  dotInactive: {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: T.dotInactive,
  },

  reassureCard: {
    flexDirection: 'row',
    gap: 11,
    alignItems: 'flex-start',
    backgroundColor: T.reassure,
    borderRadius: 14,
    padding: 14,
    paddingHorizontal: 15,
  },
  reassureText: {
    flex: 1,
    fontSize: 13,
    lineHeight: 19.5,
    color: '#4a4840',
    fontWeight: '500',
  },
  reassureIcon: { marginTop: 1 },

  monogram: { alignItems: 'center', justifyContent: 'center' },
  monogramText: { fontWeight: '600' },

  appRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 14,
    paddingVertical: 11,
    paddingHorizontal: 2,
  },
  appName: { flex: 1, fontSize: 16, fontWeight: '500', color: T.ink },
  appNameMuted: { color: T.dim },

  checkCircleOn: {
    width: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: T.inkDeep,
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkCircleOff: {
    width: 24,
    height: 24,
    borderRadius: 12,
    borderWidth: 1.5,
    borderColor: T.controlBorder,
  },

  toggle: { width: 44, height: 26, borderRadius: 13, justifyContent: 'center' },
  toggleKnob: {
    width: 20,
    height: 20,
    borderRadius: 10,
    backgroundColor: T.iconOnInk,
    position: 'absolute',
  },
  toggleKnobOn: { left: 21 },
  toggleKnobOff: { left: 3 },

  segmented: {
    flexDirection: 'row',
    gap: 6,
    backgroundColor: T.track,
    padding: 4,
    borderRadius: 11,
  },
  segment: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: 7,
    borderRadius: 8,
  },
  segmentActive: { backgroundColor: T.bg },
  segmentText: { fontSize: 13, fontWeight: '600', color: '#8d8980' },
  segmentTextActive: { color: T.inkDeep },
});
