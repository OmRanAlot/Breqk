/**
 * ModeGateBanner.styles.js — Styles + palette for the mode-gate notice.
 * ─────────────────────────────────────────────────────────────────────────────
 * Shares the Break light palette (`L`) used by the other screens so the banner
 * sits in the same visual system. The accent colour is deliberately NOT defined
 * here — it comes from the active mode at render time.
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
  banner: {
    flexDirection: 'row',
    backgroundColor: L.cardBg,
    borderRadius: 14,
    borderWidth: 1,
    overflow: 'hidden',
    marginHorizontal: 20,
    marginBottom: 16,
  },
  // Solid spine in the mode's accent colour — the "a mode owns this" signal.
  rail: {
    width: 4,
  },
  content: {
    flex: 1,
    padding: 14,
  },
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
  },
  iconTile: {
    width: 30,
    height: 30,
    borderRadius: 9,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 10,
  },
  title: {
    flex: 1,
    fontSize: 15,
    fontWeight: '700',
    color: L.charcoal,
  },
  body: {
    fontSize: 13,
    lineHeight: 19,
    color: L.muted,
    marginBottom: 12,
  },
  switchBtn: {
    alignSelf: 'flex-start',
    paddingHorizontal: 14,
    paddingVertical: 9,
    borderRadius: 10,
  },
  switchBtnText: {
    fontSize: 13,
    fontWeight: '600',
    color: '#FFFFFF',
  },
});
