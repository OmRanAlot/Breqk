/**
 * home.styles.js — Styles + palette for the Home screen.
 * ─────────────────────────────────────────────────────────────────────────────
 * Extracted verbatim from home.js. Only the style keys actually referenced by
 * the screen and its card sub-components remain — the legacy footer/primary/
 * secondary button + caption styles were unused and were dropped.
 *
 * Exports the Break light palette (`L`) so the screen and its sub-components
 * share a single source of truth for colors.
 */

import { StyleSheet } from 'react-native';

// ─── Break Light Palette ─────────────────────────────────────────────────────
export const L = {
  bg: '#F8F8F6',
  charcoal: '#1A1A1A',
  muted: '#757575',
  captionOpacity: 'rgba(26,26,26,0.6)',
  ctaBg: '#1A1A1A',
  ctaText: '#FFFFFF',
  // Accent colours for stats
  accentGreen: '#4CAF50',
  accentBlue: '#2196F3',
  accentOrange: '#FF9800',
  cardBg: '#FFFFFF',
  cardBorder: 'rgba(0,0,0,0.07)',
  barBg: 'rgba(0,0,0,0.07)',
  barFill: '#1A1A1A',
};

export const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: L.bg,
    paddingBottom: 32,
  },

  // ── Header ────────────────────────────────────────────────────────────────
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingTop: 8,
    paddingHorizontal: 28,
  },
  headerSpacer: {
    width: 76,
  },
  appName: {
    fontSize: 13,
    fontWeight: '500',
    color: L.charcoal,
    letterSpacing: 1.5,
    textAlign: 'center',
    flex: 1,
  },
  headerActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  headerButton: {
    width: 36,
    height: 36,
    alignItems: 'center',
    justifyContent: 'center',
  },

  // ── Status strip ──────────────────────────────────────────────────────────
  statusStrip: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    paddingHorizontal: 28,
    paddingTop: 6,
  },
  statusItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  statusDot: {
    width: 7,
    height: 7,
    borderRadius: 4,
  },
  statusText: {
    fontSize: 11,
    fontWeight: '500',
    color: L.muted,
    letterSpacing: 0.3,
  },
  statusDivider: {
    fontSize: 12,
    color: L.muted,
  },

  // ── Scroll ────────────────────────────────────────────────────────────────
  scrollView: {
    flex: 1,
  },
  scrollContent: {
    paddingHorizontal: 28,
    paddingTop: 20,
    paddingBottom: 12,
    gap: 20,
  },

  // ── Summary stat cards ────────────────────────────────────────────────────
  statsRow: {
    flexDirection: 'row',
    gap: 10,
  },
  statCard: {
    flex: 1,
    backgroundColor: L.cardBg,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: L.cardBorder,
    paddingVertical: 16,
    paddingHorizontal: 10,
    alignItems: 'center',
    gap: 4,
  },
  statValue: {
    fontSize: 22,
    fontWeight: '300',
    color: L.charcoal,
    letterSpacing: -0.5,
  },
  statLabel: {
    fontSize: 10,
    fontWeight: '600',
    color: L.muted,
    textTransform: 'uppercase',
    letterSpacing: 0.8,
    textAlign: 'center',
  },

  // ── Skeleton placeholders ─────────────────────────────────────────────────
  skeletonValue: {
    height: 24,
    width: '60%',
    backgroundColor: 'rgba(0,0,0,0.08)',
    borderRadius: 6,
  },
  skeletonText: {
    height: 12,
    backgroundColor: 'rgba(0,0,0,0.07)',
    borderRadius: 4,
  },

  // ── Error ─────────────────────────────────────────────────────────────────
  errorRow: {
    paddingVertical: 10,
    alignItems: 'center',
  },
  errorText: {
    fontSize: 13,
    color: '#C62828',
  },

  // ── Top apps section ──────────────────────────────────────────────────────
  topAppsSection: {
    gap: 10,
  },
  topAppsSectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  topAppsTotalTime: {
    fontSize: 12,
    fontWeight: '500',
    color: L.charcoal,
    fontVariant: ['tabular-nums'],
  },
  sectionTitle: {
    fontSize: 11,
    fontWeight: '600',
    color: L.muted,
    textTransform: 'uppercase',
    letterSpacing: 1.2,
  },
  appUsageRow: {
    gap: 6,
  },
  appUsageInfo: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'baseline',
  },
  appUsageName: {
    fontSize: 14,
    fontWeight: '400',
    color: L.charcoal,
    flex: 1,
    marginRight: 8,
  },
  appUsageTime: {
    fontSize: 13,
    color: L.muted,
    fontVariant: ['tabular-nums'],
  },
  usageBar: {
    height: 4,
    backgroundColor: L.barBg,
    borderRadius: 2,
    overflow: 'hidden',
  },
  usageBarFill: {
    height: '100%',
    backgroundColor: L.barFill,
    borderRadius: 2,
  },
  emptyText: {
    fontSize: 14,
    color: L.muted,
    textAlign: 'center',
    paddingVertical: 16,
  },

  // ── Scroll Budget card ──────────────────────────────────────────────────
  budgetCard: {
    backgroundColor: L.cardBg,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: L.cardBorder,
    padding: 16,
    gap: 8,
  },
  budgetHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  budgetDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  budgetStatusLabel: {
    fontSize: 16,
    fontWeight: '500',
    fontVariant: ['tabular-nums'],
  },
  budgetProgressBg: {
    height: 4,
    flexDirection: 'row',
    backgroundColor: L.barBg,
    borderRadius: 2,
    overflow: 'hidden',
  },
  budgetCaption: {
    fontSize: 11,
    color: L.muted,
    fontVariant: ['tabular-nums'],
  },

  // ── Free Break ──────────────────────────────────────────────────────────
  freeBreakCard: {
    backgroundColor: '#F0FAF1',
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#A5D6A7',
    padding: 16,
    gap: 8,
  },
  freeBreakCardHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  freeBreakCardTitle: {
    fontSize: 11,
    fontWeight: '600',
    color: '#2E7D32',
    textTransform: 'uppercase',
    letterSpacing: 1.2,
  },
  freeBreakCountdown: {
    fontSize: 26,
    fontWeight: '300',
    color: L.charcoal,
    fontVariant: ['tabular-nums'],
    letterSpacing: -0.5,
  },
  freeBreakSubtext: {
    fontSize: 12,
    color: '#555555',
    lineHeight: 17,
  },
  freeBreakEndButton: {
    marginTop: 4,
    paddingVertical: 10,
    paddingHorizontal: 16,
    borderRadius: 9999,
    borderWidth: 1,
    borderColor: '#A5D6A7',
    alignSelf: 'center',
  },
  freeBreakEndButtonText: {
    fontSize: 13,
    fontWeight: '500',
    color: '#2E7D32',
  },
  freeBreakButton: {
    backgroundColor: L.ctaBg,
    borderRadius: 9999,
    paddingVertical: 16,
    paddingHorizontal: 32,
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 2,
  },
  freeBreakButtonText: {
    color: L.ctaText,
    fontSize: 16,
    fontWeight: '500',
  },
  freeBreakButtonDisabled: {
    backgroundColor: 'rgba(0,0,0,0.08)',
    shadowOpacity: 0,
    elevation: 0,
  },
  freeBreakButtonTextDisabled: {
    color: 'rgba(0,0,0,0.35)',
    fontSize: 16,
    fontWeight: '500',
  },
  freeBreakDisabledWrap: {
    gap: 6,
    alignItems: 'center',
  },
  freeBreakResetCaption: {
    fontSize: 11,
    color: L.muted,
    fontVariant: ['tabular-nums'],
  },
});
