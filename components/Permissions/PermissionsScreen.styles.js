/**
 * PermissionsScreen.styles.js — Styles for the onboarding/permissions flow.
 * ─────────────────────────────────────────────────────────────────────────────
 * Extracted verbatim from PermissionsScreen.js. Colors come from the shared
 * onboarding theme tokens (`T`) so the flow stays visually consistent.
 */

import { StyleSheet } from 'react-native';
import { T } from './onboarding/theme';

// Bold inline span used inside reassurance copy. Kept as a plain object (not a
// StyleSheet entry) because it is consumed at module-load time by
// permissionSteps.js when it builds its reassurance JSX.
export const strongStyle = { fontWeight: '700', color: T.ink };

export const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: T.bg },
  screen: { flex: 1 },
  flex: { flex: 1, paddingHorizontal: 26, paddingTop: 22 },

  scrollBody: { paddingBottom: 16 },
  footer: { paddingTop: 14, paddingBottom: 30 },
  footerBordered: {
    paddingTop: 14,
    paddingBottom: 30,
    borderTopWidth: 1,
    borderTopColor: T.border,
  },

  // Centered layouts (Welcome, Done)
  centered: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 36,
  },
  welcomeTitle: {
    fontSize: 28,
    fontWeight: '600',
    letterSpacing: -0.6,
    color: T.ink,
    marginTop: 26,
    marginBottom: 12,
    textAlign: 'center',
  },
  welcomeBody: {
    fontSize: 15.5,
    lineHeight: 24,
    color: T.body,
    textAlign: 'center',
  },
  welcomeFooter: {
    position: 'absolute',
    bottom: 30,
    left: 36,
    right: 36,
    alignItems: 'center',
    gap: 22,
  },

  // Headings / body
  h2: {
    fontSize: 24,
    fontWeight: '600',
    letterSpacing: -0.4,
    color: T.ink,
    marginBottom: 8,
  },
  p: { fontSize: 14, lineHeight: 21, color: T.body, marginBottom: 6 },

  // Apps step
  appList: { marginTop: 14 },
  addAnother: {
    marginTop: 14,
    fontSize: 13,
    fontWeight: '600',
    color: T.label,
  },

  // Message step
  previewCard: {
    backgroundColor: T.inkDeep,
    borderRadius: 22,
    paddingVertical: 30,
    paddingHorizontal: 24,
    alignItems: 'center',
    marginTop: 12,
    marginBottom: 22,
  },
  previewLabel: {
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 2.5,
    color: '#6f6c64',
    textTransform: 'uppercase',
    marginBottom: 14,
  },
  previewMessage: {
    fontSize: 21,
    fontWeight: '600',
    color: T.iconOnInk,
    letterSpacing: -0.3,
    textAlign: 'center',
  },
  messageList: { gap: 10 },
  messageOption: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 14,
    paddingHorizontal: 16,
    borderRadius: 14,
    backgroundColor: T.card,
    borderWidth: 1.5,
    borderColor: T.border,
  },
  messageOptionActive: { borderColor: T.inkDeep },
  messageText: { fontSize: 15, fontWeight: '500', color: T.ink },
  messageTextMuted: { color: T.dim },
  messageCheck: {
    width: 18,
    height: 18,
    borderRadius: 9,
    backgroundColor: T.inkDeep,
    alignItems: 'center',
    justifyContent: 'center',
  },
  customOption: {
    paddingVertical: 14,
    paddingHorizontal: 16,
    borderRadius: 14,
    borderWidth: 1.5,
    borderStyle: 'dashed',
    borderColor: T.controlBorder,
  },
  customOptionText: { fontSize: 15, fontWeight: '500', color: T.label },
  customInput: {
    paddingVertical: 14,
    paddingHorizontal: 16,
    borderRadius: 14,
    borderWidth: 1.5,
    borderColor: T.inkDeep,
    backgroundColor: T.card,
    fontSize: 15,
    fontWeight: '500',
    color: T.ink,
  },

  // Breath step
  breathList: { gap: 12, marginTop: 4 },
  breathCard: {
    backgroundColor: T.card,
    borderWidth: 1.5,
    borderColor: T.border,
    borderRadius: 16,
    padding: 13,
    paddingHorizontal: 14,
  },
  breathHeader: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  breathName: { flex: 1, fontSize: 15.5, fontWeight: '600', color: T.ink },
  breathNameOff: { color: T.dim },
  breathSegment: { marginTop: 11 },
  breathOff: { marginTop: 9, fontSize: 13, color: T.faint, fontWeight: '500' },

  // Permission steps
  permTopRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 26,
    paddingTop: 22,
    marginBottom: 22,
  },
  permEyebrow: { color: T.faint, letterSpacing: 1.5 },
  permIconTile: {
    width: 64,
    height: 64,
    borderRadius: 18,
    backgroundColor: T.tile,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 22,
    marginHorizontal: 26,
  },
  requiredLabel: { marginBottom: 8, marginHorizontal: 26 },
  permHeadline: { marginHorizontal: 26 },
  permBody: {
    fontSize: 14.5,
    lineHeight: 22.5,
    color: T.body,
    marginBottom: 18,
    marginHorizontal: 26,
  },
  permReassure: { marginHorizontal: 26 },

  // "Not now" secondary action under the deletion-prevention CTA
  skipLink: { alignItems: 'center', paddingTop: 14 },
  skipLinkText: { fontSize: 14, fontWeight: '600', color: T.label },

  // Done step
  doneCircle: {
    width: 72,
    height: 72,
    borderRadius: 36,
    backgroundColor: T.inkDeep,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 28,
  },
  doneTitle: {
    fontSize: 27,
    fontWeight: '600',
    letterSpacing: -0.5,
    color: T.ink,
    marginBottom: 12,
    textAlign: 'center',
  },
  chipsRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    justifyContent: 'center',
    marginTop: 26,
  },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingVertical: 8,
    paddingHorizontal: 14,
    borderRadius: 20,
    backgroundColor: T.tile,
  },
  chipDot: { width: 6, height: 6, borderRadius: 3, backgroundColor: '#3bbf6f' },
  chipText: { fontSize: 13, fontWeight: '600', color: '#4a4840' },
  doneFooter: { position: 'absolute', bottom: 30, left: 26, right: 26 },
});
