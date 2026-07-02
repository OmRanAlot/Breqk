/**
 * ModesScreen.styles.js — Styles + palette for the Modes list screen.
 * ─────────────────────────────────────────────────────────────────────────────
 * Extracted verbatim from ModesScreen.js. Exports the Break light palette (`L`)
 * so the screen shares a single source of truth for colors.
 */

import { StyleSheet } from 'react-native';

// ─── Break Light Palette ─────────────────────────────────────────────────────
export const L = {
  bg: '#FAFAFA',
  charcoal: '#1A1A1A',
  muted: '#737373',
  border: '#E5E5E5',
  cardBg: '#FFFFFF',
  cardBorder: 'rgba(0,0,0,0.07)',
};

export const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: L.bg,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 14,
    backgroundColor: L.bg,
    borderBottomWidth: 1,
    borderBottomColor: L.border,
  },
  backButton: {
    width: 36,
    height: 36,
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerTitle: {
    flex: 1,
    textAlign: 'center',
    fontSize: 18,
    fontWeight: '500',
    color: L.charcoal,
  },
  headerSpacer: { width: 36 },
  scroll: {
    flex: 1,
  },
  scrollContent: {
    paddingHorizontal: 24,
    paddingTop: 28,
    paddingBottom: 48,
  },
  activeBanner: {
    backgroundColor: 'rgba(0,0,0,0.06)',
    borderRadius: 10,
    paddingVertical: 10,
    paddingHorizontal: 14,
    marginBottom: 20,
  },
  activeBannerText: {
    fontSize: 13,
    fontWeight: '500',
    color: L.charcoal,
    textAlign: 'center',
  },
  sectionLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: L.charcoal,
    textTransform: 'uppercase',
    letterSpacing: 1.5,
    marginBottom: 8,
  },
  sectionCaption: {
    fontSize: 13,
    color: L.muted,
    lineHeight: 18,
    marginBottom: 16,
  },
  modeCard: {
    backgroundColor: L.cardBg,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: L.cardBorder,
    padding: 16,
    marginBottom: 12,
  },
  modeCardHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  modeIcon: {
    fontSize: 24,
  },
  modeCardInfo: {
    flex: 1,
  },
  modeCardName: {
    fontSize: 16,
    fontWeight: '600',
    color: L.charcoal,
  },
  modeCardSummary: {
    fontSize: 12,
    color: L.muted,
    marginTop: 2,
    lineHeight: 16,
  },
  modeEditLink: {
    marginTop: 10,
    paddingTop: 10,
    borderTopWidth: 1,
    borderTopColor: L.border,
  },
  modeEditLinkText: {
    fontSize: 13,
    color: L.muted,
    fontWeight: '500',
  },
  activeBadge: {
    position: 'absolute',
    top: 10,
    right: 10,
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 6,
  },
  activeBadgeText: {
    fontSize: 10,
    fontWeight: '600',
    textTransform: 'uppercase',
  },
  createModeBtn: {
    backgroundColor: L.charcoal,
    borderRadius: 14,
    paddingVertical: 16,
    alignItems: 'center',
    marginTop: 8,
  },
  createModeBtnText: {
    fontSize: 16,
    fontWeight: '500',
    color: '#FFFFFF',
  },
  notifCard: {
    backgroundColor: L.cardBg,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: L.cardBorder,
    padding: 16,
    marginTop: 8,
    marginBottom: 4,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  notifCardInfo: {
    flex: 1,
  },
  notifCardTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: L.charcoal,
  },
  notifCardCaption: {
    fontSize: 12,
    color: L.muted,
    marginTop: 2,
    lineHeight: 16,
  },
  infoSection: {
    marginTop: 32,
    padding: 16,
    backgroundColor: L.cardBg,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: L.cardBorder,
  },
  infoTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: L.charcoal,
    marginBottom: 8,
  },
  infoText: {
    fontSize: 13,
    color: L.muted,
    lineHeight: 18,
  },
  savedToast: {
    position: 'absolute',
    bottom: 40,
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  savedToastText: {
    backgroundColor: L.charcoal,
    color: '#FFFFFF',
    fontSize: 14,
    fontWeight: '500',
    paddingHorizontal: 20,
    paddingVertical: 10,
    borderRadius: 20,
    overflow: 'hidden',
  },
});
