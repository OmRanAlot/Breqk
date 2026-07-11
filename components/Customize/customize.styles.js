/**
 * customize.styles.js — Styles + palette for the Customize screen.
 * ─────────────────────────────────────────────────────────────────────────────
 * Extracted verbatim from customize.js. Only the style keys actually referenced
 * by the screen remain — the legacy Modes/Schedule/AppCard styles were removed
 * when Modes management moved to the dedicated Modes screen.
 *
 * Exports the Break light palette (`L`) so the screen and its section
 * sub-components share a single source of truth for colors.
 */

import { StyleSheet } from 'react-native';

// ─── Break Light Palette ─────────────────────────────────────────────────────
export const L = {
  bg: '#FAFAFA',
  charcoal: '#1A1A1A',
  muted: '#737373',
  border: '#E5E5E5',
  sectionLabel: '#1A1A1A',
  inputBorder: '#1A1A1A',
  sliderTrack: '#E5E5E5',
  sliderThumb: '#1A1A1A',
  previewBorder: '#E5E5E5',
  cardBg: '#FFFFFF',
  cardBorder: 'rgba(0,0,0,0.07)',
};

export const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: L.bg,
  },

  // Sticky header
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 14,
    backgroundColor: L.bg,
    borderBottomWidth: 1,
    borderBottomColor: L.border,
    zIndex: 10,
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
    letterSpacing: -0.2,
  },
  headerSpacer: { width: 36 },

  // Scrollable area
  scroll: { flex: 1 },
  scrollContent: {
    paddingHorizontal: 24,
    paddingTop: 28,
    paddingBottom: 48,
  },

  // Section block
  section: { marginBottom: 36 },
  sectionLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: L.charcoal,
    textTransform: 'uppercase',
    letterSpacing: 1.5,
    marginBottom: 12,
  },

  // ── Toggle rows ───────────────────────────────────────────────────────────
  toggleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 4,
  },
  toggleLabel: {
    fontSize: 16,
    color: L.charcoal,
    fontWeight: '400',
  },
  toggleLabelGroup: {
    flex: 1,
    marginRight: 12,
  },
  toggleCaption: {
    fontSize: 12,
    color: L.muted,
    marginTop: 3,
    lineHeight: 17,
  },

  // Toggle label + InfoCircle side by side (double-safe disable row).
  labelWithInfo: {
    flexDirection: 'row',
    alignItems: 'center',
  },

  // Paragraphs inside InfoCircle modals.
  infoPara: {
    fontSize: 13.5,
    lineHeight: 20,
    color: '#525252',
    marginBottom: 12,
  },

  // ── Content-filter guard status panel ─────────────────────────────────────
  guardStatusText: {
    fontSize: 13,
    lineHeight: 19,
    color: L.muted,
  },
  guardStatusWarn: {
    fontSize: 13,
    lineHeight: 19,
    color: '#9a3412',
    backgroundColor: '#fff4ed',
    borderRadius: 10,
    padding: 12,
    fontWeight: '500',
  },
  guardStatusStrong: {
    fontWeight: '700',
    color: L.charcoal,
  },
  guardButtonRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 14,
  },
  guardConfirmButton: {
    paddingVertical: 10,
    paddingHorizontal: 18,
    borderRadius: 22,
    backgroundColor: '#9a3412',
    marginRight: 10,
  },
  guardConfirmText: {
    fontSize: 13.5,
    fontWeight: '700',
    color: '#FFFFFF',
  },
  guardCancelButton: {
    paddingVertical: 10,
    paddingHorizontal: 18,
    borderRadius: 22,
    backgroundColor: L.charcoal,
  },
  guardCancelText: {
    fontSize: 13.5,
    fontWeight: '700',
    color: '#FFFFFF',
  },

  // ── Deletion-prevention info modal ────────────────────────────────────────
  infoModalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.45)',
    justifyContent: 'center',
    paddingHorizontal: 24,
  },
  infoModalCard: {
    backgroundColor: L.cardBg,
    borderRadius: 16,
    maxHeight: '82%',
    overflow: 'hidden',
  },
  infoModalScroll: {
    flexGrow: 0,
  },
  infoModalContent: {
    padding: 24,
  },
  infoModalTitle: {
    fontSize: 20,
    fontWeight: '600',
    color: L.charcoal,
    letterSpacing: -0.3,
    marginBottom: 8,
  },
  infoModalSectionHeading: {
    fontSize: 11,
    fontWeight: '600',
    color: L.charcoal,
    textTransform: 'uppercase',
    letterSpacing: 1.5,
    marginTop: 20,
    marginBottom: 10,
  },
  infoModalBulletRow: {
    flexDirection: 'row',
    marginBottom: 8,
  },
  infoModalBullet: {
    fontSize: 14,
    color: L.muted,
    marginRight: 8,
    lineHeight: 20,
  },
  infoModalBulletText: {
    flex: 1,
    fontSize: 14,
    color: L.charcoal,
    lineHeight: 20,
  },
  infoModalPrivacy: {
    fontSize: 14,
    color: L.charcoal,
    lineHeight: 21,
    fontWeight: '500',
  },
  infoModalButtonRow: {
    flexDirection: 'row',
    borderTopWidth: 1,
    borderTopColor: L.border,
  },
  infoModalCancelButton: {
    flex: 1,
    paddingVertical: 16,
    alignItems: 'center',
    justifyContent: 'center',
    borderRightWidth: 1,
    borderRightColor: L.border,
  },
  infoModalCancelText: {
    fontSize: 16,
    color: L.muted,
    fontWeight: '500',
  },
  infoModalEnableButton: {
    flex: 1,
    paddingVertical: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  infoModalEnableText: {
    fontSize: 16,
    color: L.charcoal,
    fontWeight: '600',
  },
  permissionHint: {
    marginTop: 10,
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: '#FFF8E1',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#FFE082',
  },
  permissionHintText: {
    fontSize: 12,
    color: '#7B5800',
    lineHeight: 17,
  },

  // ── Scroll Budget ────────────────────────────────────────────────────────
  budgetControls: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
    marginBottom: 8,
  },
  stepperGroup: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  stepperBtn: {
    width: 30,
    height: 30,
    borderRadius: 8,
    backgroundColor: 'rgba(0,0,0,0.06)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  stepperBtnText: {
    fontSize: 18,
    color: L.charcoal,
    fontWeight: '400',
    lineHeight: 22,
  },
  stepperValue: {
    fontSize: 16,
    fontWeight: '500',
    color: L.charcoal,
    minWidth: 36,
    textAlign: 'center',
    fontVariant: ['tabular-nums'],
  },
  budgetDivider: {
    fontSize: 12,
    color: L.muted,
    fontWeight: '500',
  },
  budgetStatusSection: { gap: 6 },
  budgetStatusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  budgetDot: {
    width: 7,
    height: 7,
    borderRadius: 4,
  },
  budgetStatusText: {
    fontSize: 13,
    fontWeight: '500',
    fontVariant: ['tabular-nums'],
  },
  budgetProgressBg: {
    height: 4,
    flexDirection: 'row',
    backgroundColor: 'rgba(0,0,0,0.07)',
    borderRadius: 2,
    overflow: 'hidden',
  },
  budgetWarning: {
    fontSize: 12,
    color: '#C62828',
    marginTop: 8,
    lineHeight: 17,
  },

  // ── Intercept Message ────────────────────────────────────────────────────
  messageInput: {
    fontSize: 18,
    color: L.charcoal,
    borderBottomWidth: 1.5,
    borderBottomColor: L.inputBorder,
    paddingVertical: 8,
    paddingHorizontal: 0,
    backgroundColor: 'transparent',
    marginBottom: 24,
  },
  durationHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'baseline',
    marginBottom: 4,
  },
  durationLabel: {
    fontSize: 14,
    color: L.charcoal,
    fontWeight: '500',
  },
  durationValue: {
    fontSize: 14,
    color: L.muted,
    fontWeight: '400',
  },
  slider: {
    width: '100%',
    height: 40,
  },
  sliderLabels: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: -4,
    marginBottom: 20,
  },
  sliderRangeLabel: {
    fontSize: 11,
    color: L.muted,
  },
  previewButton: {
    borderWidth: 1,
    borderColor: L.previewBorder,
    borderRadius: 12,
    paddingVertical: 16,
    alignItems: 'center',
  },
  previewButtonText: {
    fontSize: 15,
    color: L.charcoal,
    fontWeight: '500',
  },

  // ── Saved toast ──────────────────────────────────────────────────────────
  savedToast: {
    position: 'absolute',
    bottom: 80,
    alignSelf: 'center',
    backgroundColor: '#1A1A1A',
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: 9999,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.15,
    shadowRadius: 8,
    elevation: 6,
  },
  savedToastText: {
    color: '#FFFFFF',
    fontSize: 14,
    fontWeight: '500',
    letterSpacing: 0.2,
  },

  // Footer
  footer: {
    textAlign: 'center',
    fontSize: 10,
    color: L.muted,
    letterSpacing: 1.5,
    textTransform: 'uppercase',
    marginTop: 8,
  },
});
